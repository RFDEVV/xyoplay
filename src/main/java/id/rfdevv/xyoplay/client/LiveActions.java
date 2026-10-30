package id.rfdevv.xyoplay.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Chaos Mode": turns TikTok LIVE events into things that happen in the world.
 *
 * A trigger is written  PEMICU=RESEP  and saved in config/xyoplay/live-actions.json.
 *
 *   follow=zombie              share=skeleton        subscribe=wither       join=chicken
 *   Rose=creeper              (a gift by its name or an Indonesian nickname)
 *   id:5655=creeper           (a gift by its TikTok id, shown in the Gift tab)
 *   diamond:20=tnt*c          (any gift worth at least 20 coins each; "c" = the coin value of one gift)
 *   gift=acak:ringan          (a gift whose price is unknown)
 *   !boom=tnt                 (a viewer comment that starts with !boom)
 *
 * A recipe joins parts with + , amounts with * and waves with | (every wave starts a little after the previous one):
 *   zombie+creeper+skeleton+tnt*4        piglin_brute*5          give:oak_log*64
 *   acak:tools      acak:berguna    acak:bahaya*4    acak:boss    acak:ringan    acak:potion
 *   fx:speed  fx:slowness*2          (potion effect, *N = level)
 *   say:Teks banner                   mega  /  special   (ready-made big events)
 *
 * Priority: gift id > gift name > coin tier (highest threshold not above the gift's price) > "gift" (price unknown).
 * A special rule always wins over a tier; both never run together.
 */
final class LiveActions {
    private LiveActions() {}

    /** kind: mob | tnt | give | pool | fx | say.  n = -1 means "the coin value of the gift". */
    record Part(String kind, String id, int n) {}

    record Trigger(String type, String arg, String mob, int count) {
        String label() {
            return switch (type) {
                case "follow" -> "Follow";
                case "share" -> "Share";
                case "subscribe" -> "Subscribe";
                case "join" -> "Masuk LIVE";
                case "like" -> "Tiap " + ClientSettings.likeStep + " tap per orang";
                case "goal" -> "Target follow tercapai";
                case "gift" -> "Gift harga tak dikenal";
                case "diamond" -> tierName(Integer.parseInt(arg)) + " (≥ " + arg + " koin)";
                case "giftid" -> "Gift id " + arg;
                case "cmd" -> "Komentar " + arg;
                default -> "Gift " + arg;
            };
        }
        String keyText() {
            return switch (type) {
                case "diamond" -> "diamond:" + arg;
                case "giftid" -> "id:" + arg;
                case "cmd" -> arg;
                case "giftname" -> arg;
                default -> type;
            };
        }
        String spec() { return keyText() + "=" + mob; }
    }

    /** eid identifies one real TikTok event so the same one is never handled twice (empty = unknown). */
    record Event(String kind, String user, String uid, String text, String gift, int count, int diamonds, int giftId, String eid) {
        Event(String kind, String user, String uid, String text, String gift, int count, int diamonds, int giftId) { this(kind, user, uid, text, gift, count, diamonds, giftId, ""); }
        Event(String kind, String user, String uid, String text, String gift, int count, int diamonds) { this(kind, user, uid, text, gift, count, diamonds, 0, ""); }
    }

    /** What happened for one event: shown in the Monitor tab. */
    static final class EventRec {
        final long time = System.currentTimeMillis();
        final String who, source, effect;
        volatile int total, done, failed;
        volatile String error = "", note = "";
        EventRec(String who, String source, String effect) { this.who = who; this.source = source; this.effect = effect; }
        String state() {
            if (total == 0) return note.isEmpty() ? "TANPA EFEK" : "DITOLAK";
            if (done + failed < total) return "BERJALAN " + (done + failed) + "/" + total;
            if (failed == 0) return "OK " + done + "/" + total;
            return failed == total ? "GAGAL" : "SEBAGIAN " + done + "/" + total;
        }
        boolean bad() { return failed > 0 || (total == 0 && !note.isEmpty()); }
        boolean running() { return total > 0 && done + failed < total; }
    }

    private record Job(String kind, String id, int n, String viewer, long due, EventRec rec) {}

    private static final List<Trigger> TRIGGERS = new CopyOnWriteArrayList<>();
    private static final List<EventRec> LOG = new CopyOnWriteArrayList<>();
    private static final Object LOCK = new Object();
    private static final ArrayList<Job> JOBS = new ArrayList<>();
    private static final ArrayDeque<Long> RECENT = new ArrayDeque<>();
    private static final ArrayList<Long> HEAVY_ENDS = new ArrayList<>();
    private static final Map<String, Long> LAST_COMMAND = new HashMap<>();
    private static final Map<String, Long> SEEN_SOFT = new LinkedHashMap<>();
    private static final java.util.LinkedHashSet<String> SEEN_IDS = new java.util.LinkedHashSet<>();
    private static boolean loaded;
    private static volatile long tickNow, heavyBusyUntil, lastFailBanner;
    private static volatile int spawnedTotal, failedTotal, deferred;

    static int spawnedTotal() { return spawnedTotal; }
    static int failedTotal() { return failedTotal; }
    static int pending() { synchronized (LOCK) { return JOBS.size(); } }
    static int deferred() { return deferred; }
    static List<EventRec> log() { return List.copyOf(LOG); }
    static void clearLog() { LOG.clear(); failedTotal = 0; }
    static void clearQueue() { synchronized (LOCK) { JOBS.clear(); HEAVY_ENDS.clear(); heavyBusyUntil = 0; } }

    // ------------------------------------------------------------------ mob names

    private static final Map<String, String> MOBS = new HashMap<>();
    static final List<String> MOB_HELP = List.of("zombie", "creeper", "skeleton", "spider", "enderman", "witch", "slime", "blaze",
            "ghast", "phantom", "pillager", "ravager", "iron_golem", "wolf", "cat", "pig", "cow", "chicken", "sheep", "horse",
            "villager", "panda", "fox", "bee", "axolotl", "lightning", "tnt", "wither", "warden");
    private static void mob(String id, String... names) {
        MOBS.put(id, id);
        for (String n : names) MOBS.put(n, id);
    }
    static {
        mob("zombie", "zombi", "mayat_hidup");
        mob("creeper", "kreper", "creaper");
        mob("skeleton", "kerangka", "skeletor");
        mob("spider", "laba", "laba_laba", "labalaba");
        mob("cave_spider", "laba_gua");
        mob("enderman", "ender");
        mob("witch", "penyihir", "penyihir_wanita");
        mob("slime", "lendir");
        mob("magma_cube", "magma");
        mob("blaze", "api");
        mob("ghast", "hantu");
        mob("phantom", "hantu_malam");
        mob("drowned", "tenggelam");
        mob("husk", "zombi_gurun");
        mob("stray", "kerangka_salju");
        mob("pillager", "perampok");
        mob("vindicator", "penghukum");
        mob("evoker", "pemanggil");
        mob("ravager", "perusak");
        mob("vex", "vex");
        mob("silverfish", "ikan_perak");
        mob("guardian", "penjaga");
        mob("shulker", "shulker");
        mob("piglin", "piglin");
        mob("piglin_brute", "piglin_brute", "brute", "piglin_ganas");
        mob("zombified_piglin", "zombi_piglin");
        mob("hoglin", "hoglin");
        mob("wither_skeleton", "kerangka_wither");
        mob("wither", "wither");
        mob("warden", "warden");
        mob("iron_golem", "golem", "golem_besi");
        mob("snow_golem", "golem_salju");
        mob("villager", "warga", "penduduk");
        mob("wandering_trader", "pedagang");
        mob("chicken", "ayam");
        mob("pig", "babi");
        mob("cow", "sapi");
        mob("sheep", "domba");
        mob("wolf", "serigala");
        mob("cat", "kucing");
        mob("horse", "kuda");
        mob("donkey", "keledai");
        mob("rabbit", "kelinci");
        mob("fox", "rubah");
        mob("panda", "panda");
        mob("parrot", "beo", "burung");
        mob("bee", "lebah");
        mob("axolotl", "axolotl");
        mob("llama", "llama");
        mob("turtle", "penyu");
        mob("mooshroom", "sapi_jamur");
        mob("ocelot", "ocelot");
        mob("camel", "unta");
        mob("frog", "katak");
        mob("allay", "allay");
        mob("sniffer", "sniffer");
        mob("armadillo", "armadillo");
        mob("breeze", "breeze");
        mob("bogged", "bogged");
        mob("lightning_bolt", "lightning", "petir", "kilat");
        mob("tnt", "tnt", "bom", "dinamit");
        mob("firework_rocket", "firework", "kembang_api", "kembangapi");
    }

    /** "zombi" -> "minecraft:zombie"; null when the text cannot be a valid entity id. */
    static String resolveMob(String typed) {
        if (typed == null) return null;
        String t = typed.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (t.isEmpty()) return null;
        String id = MOBS.get(t);
        if (id != null) return "minecraft:" + id;
        if (t.matches("[a-z0-9_]+")) return "minecraft:" + t;
        if (t.matches("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+")) return t;
        return null;
    }

    static String prettyMob(String mob) {
        String id = resolveMob(mob);
        if (id == null) return mob;
        String s = id.startsWith("minecraft:") ? id.substring(10) : id;
        return s.replace('_', ' ');
    }


    // ------------------------------------------------------------------ pools and recipes

    private record Pool(List<String> good, List<String> bad) {}
    private static final Map<String, Pool> POOLS = new HashMap<>();
    private static final Map<String, String> POOL_ALIASES = new HashMap<>();
    static final List<String> POOL_NAMES = List.of("tools", "berguna", "bahaya", "boss", "ringan", "potion", "chaos");
    private static void pool(String name, List<String> good, List<String> bad, String... aliases) {
        POOLS.put(name, new Pool(good, bad));
        POOL_ALIASES.put(name, name);
        for (String a : aliases) POOL_ALIASES.put(a, name);
    }
    private static final List<String> FX_GOOD = List.of("speed", "regeneration", "strength", "resistance", "jump_boost", "fire_resistance", "absorption");
    private static final List<String> FX_BAD = List.of("slowness", "weakness", "poison", "blindness", "nausea", "mining_fatigue");
    static {
        pool("tools", List.of("give:iron_pickaxe", "give:diamond_pickaxe", "give:iron_axe", "give:diamond_axe", "give:iron_shovel",
                "give:diamond_shovel", "give:iron_sword", "give:diamond_sword", "give:shield", "give:shears", "give:fishing_rod", "give:iron_hoe"),
                List.of(), "alat", "tool");
        pool("berguna", List.of("give:diamond_helmet", "give:diamond_chestplate", "give:diamond_leggings", "give:diamond_boots", "give:golden_apple*2",
                "give:bow", "give:arrow*32", "give:ender_pearl*4", "give:experience_bottle*16", "give:cooked_beef*16", "give:golden_carrot*16",
                "give:shield", "give:torch*32", "give:water_bucket", "give:totem_of_undying", "give:iron_ingot*16", "give:diamond*3"),
                List.of(), "item", "useful");
        pool("bahaya", List.of("zombie", "skeleton", "creeper", "spider", "cave_spider", "enderman", "witch", "blaze", "pillager",
                "vindicator", "piglin_brute", "wither_skeleton", "husk", "stray", "phantom", "vex", "evoker", "drowned", "ravager"),
                List.of(), "mob_bahaya", "mobbahaya", "danger", "dangerous");
        pool("boss", List.of("warden", "ravager", "evoker", "wither_skeleton*2", "vindicator*3", "ravager+evoker"), List.of(), "bos");
        pool("ringan", List.of("fx:regeneration", "fx:speed", "fx:jump_boost", "fx:heal", "give:cooked_beef*8", "give:bread*16", "give:iron_pickaxe"),
                List.of("fx:slowness", "fx:weakness", "fx:nausea", "fx:mining_fatigue", "zombie", "chicken*3", "cave_spider*2"), "light", "ringan_acak");
        pool("chaos", List.of("fx:speed", "fx:regeneration", "fx:jump_boost", "give:golden_apple*3", "give:diamond*2", "give:cooked_beef*16",
                "give:experience_bottle*16", "iron_golem"),
                List.of("zombie*3", "creeper*2", "skeleton*3", "tnt*5", "witch+skeleton", "spider*3", "enderman", "lightning", "fx:slowness",
                        "fx:nausea", "fx:blindness", "pillager*2", "blaze*2", "phantom*2"), "random_event", "kejutan", "event");
        List<String> g = new ArrayList<>(), b = new ArrayList<>();
        for (String s : FX_GOOD) g.add("fx:" + s);
        for (String s : FX_BAD) b.add("fx:" + s);
        pool("potion", g, b, "efek", "ramuan");
    }

    private static final Map<String, String> FX = new LinkedHashMap<>();
    private static void fx(String id, String... aliases) { FX.put(id, id); for (String a : aliases) FX.put(a, id); }
    static {
        fx("speed", "cepat"); fx("slowness", "lambat"); fx("regeneration", "regen"); fx("strength", "kuat"); fx("resistance", "tahan");
        fx("jump_boost", "lompat", "jump"); fx("fire_resistance", "tahan_api"); fx("absorption", "penyerap"); fx("weakness", "lemah");
        fx("poison", "racun"); fx("blindness", "buta"); fx("nausea", "mual"); fx("mining_fatigue", "lelah");
        fx("instant_health", "heal", "sembuh"); fx("saturation", "kenyang"); fx("night_vision", "malam"); fx("glowing", "bersinar");
        fx("levitation", "melayang"); fx("slow_falling", "jatuh_pelan");
    }
    static String fxName(String id) { return id.replace("minecraft:", "").replace('_', ' '); }

    private static final Map<String, String> SOUNDS = new LinkedHashMap<>();
    static final List<String> SOUND_NAMES;
    static {
        SOUNDS.put("levelup", "entity.player.levelup");
        SOUNDS.put("peringatan", "entity.ender_dragon.growl");
        SOUNDS.put("ledakan", "entity.generic.explode");
        SOUNDS.put("petir", "entity.lightning_bolt.thunder");
        SOUNDS.put("tepuk", "entity.experience_orb.pickup");
        SOUNDS.put("lonceng", "block.bell.use");
        SOUNDS.put("hore", "entity.firework_rocket.blast");
        SOUNDS.put("hantu", "entity.ghast.scream");
        SOUNDS.put("boss", "entity.wither.spawn");
        SOUND_NAMES = List.copyOf(SOUNDS.keySet());
    }

    static final String MEGA_SPEC = "say:XYOMC MEGA CHAOS - HAT AND MUSTACHE!+title:{nama} - MEGA CHAOS!+sound:peringatan|say:3|say:2|say:1|warden|ravager*2|creeper*3+skeleton*5|"
            + "zombie*5+witch*2+piglin_brute*2+fx:blindness+fx:slowness|say:MEGA CHAOS selesai!";
    static final String SPECIAL_SPEC = "say:SPECIAL EVENT - 3 GELOMBANG!+title:SPECIAL EVENT+sound:boss|say:Gelombang 1|acak:bahaya*5|say:Gelombang 2|acak:boss*2+acak:bahaya*4|"
            + "say:Gelombang 3 - BOSS!|warden+ravager*2+tnt*10|give:golden_apple*8+give:totem_of_undying|say:Event spesial selesai!";

    /** A recipe: waves of parts. */
    record Recipe(List<List<Part>> waves) {}

    private static final Map<String, List<Part>> POOL_ENTRY_CACHE = new HashMap<>();

    /** Parses a recipe such as "zombie+creeper+tnt*4". Returns null and sets error[0] when it is not valid. */
    static Recipe parseRecipe(String spec, String[] error) {
        if (spec == null || spec.isBlank()) { error[0] = "Isi hadiahnya, contoh: zombie atau acak:tools"; return null; }
        String s = spec.strip();
        String low = s.toLowerCase(Locale.ROOT);
        if (low.equals("mega")) return parseRecipe(MEGA_SPEC, error);
        if (low.equals("special") || low.equals("spesial")) return parseRecipe(SPECIAL_SPEC, error);
        List<List<Part>> waves = new ArrayList<>();
        for (String waveText : s.split("\\|")) {
            List<Part> parts = parseWave(waveText, error);
            if (parts == null) return null;
            if (!parts.isEmpty()) waves.add(parts);
        }
        if (waves.isEmpty()) { error[0] = "Hadiah kosong"; return null; }
        if (waves.size() > 12) { error[0] = "Maksimal 12 gelombang"; return null; }
        return new Recipe(waves);
    }

    private static List<Part> parseWave(String text, String[] error) {
        List<Part> parts = new ArrayList<>();
        for (String raw : text.split("\\+")) {
            String token = raw.strip();
            if (token.isEmpty()) continue;
            String low = token.toLowerCase(Locale.ROOT);
            if (low.startsWith("say:")) {
                String msg = token.substring(4).strip().replaceAll("[\\p{Cntrl}]", " ");
                if (msg.isEmpty()) { error[0] = "say: butuh teks"; return null; }
                parts.add(new Part("say", msg.length() > 80 ? msg.substring(0, 80) : msg, 1));
                continue;
            }
            if (low.startsWith("title:") || low.startsWith("judul:")) {
                String msg = token.substring(token.indexOf(':') + 1).strip().replaceAll("[\\p{Cntrl}\"\\\\]", " ");
                if (msg.isEmpty()) { error[0] = "title: butuh teks"; return null; }
                parts.add(new Part("title", msg.length() > 40 ? msg.substring(0, 40) : msg, 1));
                continue;
            }
            if (low.startsWith("sound:") || low.startsWith("suara:")) {
                String snd = SOUNDS.get(low.substring(low.indexOf(':') + 1).strip().replace(' ', '_'));
                if (snd == null) {
                    String raw2 = low.substring(low.indexOf(':') + 1).strip();
                    if (raw2.matches("[a-z0-9_.]+")) snd = raw2;
                    else { error[0] = "Suara tidak dikenal: " + token + " (" + String.join(", ", SOUND_NAMES) + ")"; return null; }
                }
                parts.add(new Part("sound", snd, 1));
                continue;
            }
            if (low.equals("mega") || low.equals("special")) { error[0] = "\"" + low + "\" harus berdiri sendiri, tanpa + atau |"; return null; }
            int n = 1;
            int star = token.lastIndexOf('*');
            if (star > 0) {
                String amount = token.substring(star + 1).strip().toLowerCase(Locale.ROOT);
                if (amount.equals("c") || amount.equals("koin")) n = -1;
                else {
                    try { n = Integer.parseInt(amount); }
                    catch (NumberFormatException e) { error[0] = "Jumlah setelah * harus angka (atau c = nilai koin): " + token; return null; }
                    n = Math.max(1, Math.min(64, n));
                }
                token = token.substring(0, star).strip();
                low = token.toLowerCase(Locale.ROOT);
            }
            if (low.startsWith("give:") || low.startsWith("item:")) {
                String item = low.substring(low.indexOf(':') + 1).strip().replace(' ', '_');
                if (!item.matches("[a-z0-9_]+(:[a-z0-9_./\\-]+)?")) { error[0] = "Nama item tidak valid: " + token; return null; }
                parts.add(new Part("give", item.contains(":") ? item : "minecraft:" + item, n < 0 ? 1 : n));
            } else if (low.startsWith("fx:") || low.startsWith("efek:")) {
                String name = FX.get(low.substring(low.indexOf(':') + 1).strip().replace(' ', '_'));
                if (name == null) { error[0] = "Efek tidak dikenal: " + token + " (contoh: speed, slowness, regeneration, poison)"; return null; }
                parts.add(new Part("fx", "minecraft:" + name, n < 0 ? 1 : n));
            } else if (low.startsWith("acak:") || low.startsWith("random:")) {
                String pool = POOL_ALIASES.get(low.substring(low.indexOf(':') + 1).strip().replace(' ', '_'));
                if (pool == null) { error[0] = "Kumpulan acak tidak dikenal: " + token + " (" + String.join(", ", POOL_NAMES) + ")"; return null; }
                parts.add(new Part("pool", pool, n < 0 ? 1 : Math.min(n, 30)));
            } else {
                String id = resolveMob(token);
                if (id == null) { error[0] = "Nama mob tidak dikenal: " + token; return null; }
                if (id.equals("minecraft:tnt")) parts.add(new Part("tnt", id, n));
                else parts.add(new Part("mob", id, n < 0 ? 1 : Math.min(n, 30)));
            }
        }
        return parts;
    }

    /** The parts of one pool entry (an entry never contains another pool). */
    private static List<Part> poolEntry(String entry) {
        synchronized (POOL_ENTRY_CACHE) {
            List<Part> cached = POOL_ENTRY_CACHE.get(entry);
            if (cached != null) return cached;
            List<Part> parts = parseWave(entry, new String[1]);
            if (parts == null) parts = List.of();
            POOL_ENTRY_CACHE.put(entry, parts);
            return parts;
        }
    }

    private static String pick(Pool pool, boolean wantGood) {
        List<String> list = pool.bad().isEmpty() || wantGood ? pool.good() : pool.bad();
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    static String prettySpec(String spec) {
        String[] err = new String[1];
        Recipe r = parseRecipe(spec, err);
        if (r == null) return spec;
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < r.waves().size(); w++) {
            if (w > 0) sb.append(" › ");
            boolean first = true;
            for (Part p : r.waves().get(w)) {
                if (!first) sb.append(" + ");
                first = false;
                String name = switch (p.kind()) {
                    case "pool" -> "acak " + p.id();
                    case "give" -> "item " + p.id().replace("minecraft:", "").replace('_', ' ');
                    case "fx" -> "efek " + fxName(p.id());
                    case "say" -> "\"" + p.id() + "\"";
                    case "title" -> "judul \"" + p.id() + "\"";
                    case "sound" -> "suara " + p.id().replace("entity.", "").replace("block.", "");
                    case "tnt" -> "TNT";
                    default -> p.id().replace("minecraft:", "").replace('_', ' ');
                };
                sb.append(name);
                if (p.n() == -1) sb.append(" ×koin");
                else if (p.n() > 1) sb.append(" ×").append(p.n());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ presets

    /** Default rules: special gifts first, then the coin tiers for every other gift. */
    static final String[] PRESET_XYOMC = {
            "follow=zombie", "Rose=creeper", "Coffee=tnt", "Orange Juice=acak:tools", "GG=acak:berguna",
            "Kamu Hebat Banget=give:oak_log*64", "Finger Heart=piglin_brute*5", "Nasi Padang=skeleton", "Rosa=iron_golem",
            "Perfume=zombie+creeper+skeleton+tnt*4", "Doughnut=warden", "Hat and Mustache=mega",
            "diamond:1=acak:ringan", "diamond:5=tnt*c", "diamond:10=acak:bahaya*4", "diamond:20=tnt*c",
            "diamond:30=acak:boss+acak:bahaya*3", "diamond:50=acak:boss*2+acak:bahaya*5+tnt*8", "diamond:99=mega", "diamond:100=special",
            "gift=acak:ringan", "like=acak:chaos", "goal=acak:boss+acak:bahaya*3+sound:hore"};
    static final String[] PRESET_COMMANDS = {
            "!zombie=zombie", "!creeper=creeper", "!skeleton=skeleton", "!tnt=tnt", "!golem=iron_golem", "!item=acak:tools",
            "!makan=give:cooked_beef*16", "!kayu=give:oak_log*64", "!petir=lightning", "!potion=acak:potion"};

    static String tierName(int coins) {
        if (coins >= 100) return "SPECIAL EVENT";
        if (coins >= 99) return "MEGA CHAOS";
        if (coins >= 50) return "EXTREME CHAOS";
        if (coins >= 30) return "BOSS CHALLENGE";
        if (coins >= 20) return "COMBO ATTACK";
        if (coins >= 10) return "DANGEROUS CHALLENGE";
        if (coins >= 5) return "SMALL COMBO";
        return "LIGHT CHAOS";
    }

    static int tierColor(int coins) {
        if (coins >= 100) return 0xFFFFD700;
        if (coins >= 99) return 0xFFE879F9;
        if (coins >= 50) return 0xFFA78BFA;
        if (coins >= 30) return 0xFFF87171;
        if (coins >= 20) return 0xFFFB7185;
        if (coins >= 10) return 0xFFFB923C;
        if (coins >= 5) return 0xFFFACC15;
        return 0xFF4ADE80;
    }

    /** @return how many triggers were added or updated */
    static int loadPreset(String[] lines, boolean replaceAll) {
        ensureLoaded();
        if (replaceAll) TRIGGERS.clear();
        int ok = 0;
        for (String line : lines) if (add(line).startsWith("OK:")) ok++;
        save();
        return ok;
    }

    // ------------------------------------------------------------------ trigger list

    static synchronized void ensureLoaded() { if (!loaded) load(); }
    static List<Trigger> triggers() { ensureLoaded(); return List.copyOf(TRIGGERS); }

    /** Parses "Rose=creeper" and adds (or replaces) it. Returns a message for the player; starts with "OK:" on success. */
    static String add(String input) {
        ensureLoaded();
        if (input == null || input.isBlank()) return "Tulis pemicu=hadiah, contoh: follow=zombie";
        String s = input.strip();
        int eq = s.indexOf('=');
        if (eq <= 0 || eq == s.length() - 1) return "Format: pemicu=hadiah  (contoh: follow=zombie atau Rose=creeper)";
        String left = s.substring(0, eq).strip();
        String right = s.substring(eq + 1).strip();
        String[] err = new String[1];
        if (parseRecipe(right, err) == null) return err[0];

        String low = left.toLowerCase(Locale.ROOT);
        Trigger t;
        switch (low) {
            case "follow", "ikuti", "folow" -> t = new Trigger("follow", "", right, 1);
            case "share", "bagikan" -> t = new Trigger("share", "", right, 1);
            case "subscribe", "langganan", "sub" -> t = new Trigger("subscribe", "", right, 1);
            case "join", "masuk" -> t = new Trigger("join", "", right, 1);
            case "like", "tap", "suka" -> t = new Trigger("like", "", right, 1);
            case "goal", "target" -> t = new Trigger("goal", "", right, 1);
            case "gift", "hadiah", "semua" -> t = new Trigger("gift", "", right, 1);
            default -> {
                if (low.startsWith("id:")) {
                    String n = low.substring(3).strip();
                    if (!n.matches("\\d{1,12}")) return "Id gift harus angka, contoh: id:5655";
                    t = new Trigger("giftid", n, right, 1);
                } else if (low.startsWith("diamond:") || low.startsWith("koin:")) {
                    String n = low.substring(low.indexOf(':') + 1).strip();
                    int v;
                    try { v = Integer.parseInt(n); } catch (NumberFormatException e) { return "Angka koin tidak valid: " + n; }
                    if (v < 1 || v > 1000000) return "Koin minimal 1";
                    t = new Trigger("diamond", Integer.toString(v), right, 1);
                } else if (low.startsWith("!")) {
                    if (!low.matches("![a-z0-9_]{1,24}")) return "Perintah komentar contoh: !boom";
                    t = new Trigger("cmd", low, right, 1);
                } else {
                    String gift = GiftCatalog.canonical(left);
                    if (gift.isBlank() || gift.length() > 40) return "Nama gift tidak valid";
                    t = new Trigger("giftname", gift, right, 1);
                }
            }
        }
        for (Trigger existing : TRIGGERS) {
            if (existing.type().equals(t.type()) && existing.arg().equalsIgnoreCase(t.arg())) {
                if (existing.mob().equals(t.mob())) return "Pemicu itu sudah ada";
                TRIGGERS.set(TRIGGERS.indexOf(existing), t);   // same trigger, new reward
                save();
                return "OK: diganti → " + t.label() + " → " + prettySpec(t.mob());
            }
        }
        if (TRIGGERS.size() >= 80) return "Maksimal 80 pemicu";
        TRIGGERS.add(t);
        save();
        return "OK: " + t.label() + " → " + prettySpec(t.mob());
    }

    static boolean hasTrigger(String type) {
        ensureLoaded();
        for (Trigger t : TRIGGERS) if (t.type().equals(type)) return true;
        return false;
    }

    static boolean remove(Trigger t) {
        ensureLoaded();
        boolean removed = TRIGGERS.remove(t);
        if (removed) save();
        return removed;
    }

    /** Words of the "!command" triggers, sent to the reader so it can announce them quickly. */
    static List<String> commandWords() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (Trigger t : TRIGGERS) if (t.type().equals("cmd")) out.add(t.arg());
        return out;
    }

    // ------------------------------------------------------------------ matching

    private static boolean sameGift(String a, String b) {
        return GiftCatalog.key(GiftCatalog.canonical(a)).equals(GiftCatalog.key(GiftCatalog.canonical(b)));
    }

    /** Coin value of ONE gift piece (0 = unknown). */
    static int unitCoins(Event e) {
        if (e.count() > 0) return Math.max(0, e.diamonds() / e.count());
        return 0;
    }

    static List<Trigger> match(Event e) {
        ensureLoaded();
        List<Trigger> out = new ArrayList<>();
        switch (e.kind()) {
            case "follow", "share", "subscribe", "join", "like", "goal" -> { for (Trigger t : TRIGGERS) if (t.type().equals(e.kind())) out.add(t); }
            case "comment" -> {
                String text = e.text() == null ? "" : e.text().strip().toLowerCase(Locale.ROOT);
                if (!text.startsWith("!")) break;
                String first = text.split("\\s+", 2)[0];
                for (Trigger t : TRIGGERS) if (t.type().equals("cmd") && t.arg().equals(first)) out.add(t);
            }
            case "gift" -> {
                if (e.giftId() > 0) for (Trigger t : TRIGGERS) if (t.type().equals("giftid") && t.arg().equals(Integer.toString(e.giftId()))) out.add(t);
                if (!out.isEmpty()) break;
                for (Trigger t : TRIGGERS) if (t.type().equals("giftname") && sameGift(t.arg(), e.gift())) out.add(t);
                if (!out.isEmpty()) break;
                int unit = unitCoins(e), best = -1;
                for (Trigger t : TRIGGERS) {
                    if (!t.type().equals("diamond")) continue;
                    int need = Integer.parseInt(t.arg());
                    if (unit >= need && need > best) best = need;
                }
                if (best >= 0) { for (Trigger t : TRIGGERS) if (t.type().equals("diamond") && Integer.parseInt(t.arg()) == best) out.add(t); }
                if (!out.isEmpty()) break;
                for (Trigger t : TRIGGERS) if (t.type().equals("gift")) out.add(t);
            }
            default -> { }
        }
        return out;
    }

    /** The rule that would run for a gift (for the Gift tab). */
    static Trigger ruleFor(String gift, int giftId, int unitCoins) {
        List<Trigger> m = match(new Event("gift", "x", "x", "", gift, 1, unitCoins, giftId));
        return m.isEmpty() ? null : m.get(0);
    }

    // ------------------------------------------------------------------ likes / taps

    private static final Map<String, long[]> TAPS = new HashMap<>();   // viewer -> {taps so far, milestones already paid}
    private static final Map<String, String> TAP_NAMES = new HashMap<>();
    private static volatile long likeTotalShown;

    /** All taps seen in this LIVE. */
    static long likeCount() { return likeTotalShown; }
    static synchronized void resetLikes() { TAPS.clear(); TAP_NAMES.clear(); likeTotalShown = 0; }

    /** "Name 150/200" for the viewer closest to the next random event, or "" when nobody tapped yet. */
    static synchronized String tapLeader() {
        String best = "";
        long bestLeft = Long.MAX_VALUE;
        int step = Math.max(1, ClientSettings.likeStep);
        for (Map.Entry<String, long[]> e : TAPS.entrySet()) {
            long left = step - (e.getValue()[0] % step);
            if (left < bestLeft) { bestLeft = left; best = TAP_NAMES.getOrDefault(e.getKey(), e.getKey()) + " " + (step - left) + "/" + step; }
        }
        return best;
    }

    /**
     * Counts taps PER VIEWER. Every {@code likeStep} taps (default 200) by the same viewer, the "like" rules run once for
     * that viewer, e.g. like=acak:chaos gives them a random event. At most 3 milestones are paid for one report, so a
     * huge batch cannot flood the world.
     * @return number of events started
     */
    static int onLikes(String user, String uid, int count, long ignoredTotal) {
        int step = Math.max(1, ClientSettings.likeStep);
        String key = uid == null || uid.isBlank() ? (user == null ? "?" : user.toLowerCase(Locale.ROOT)) : uid.toLowerCase(Locale.ROOT);
        long reach, due;
        synchronized (LiveActions.class) {
            if (TAPS.size() > 3000) { TAPS.clear(); TAP_NAMES.clear(); }
            long[] st = TAPS.computeIfAbsent(key, k -> new long[2]);
            TAP_NAMES.put(key, user == null || user.isBlank() ? key : user);
            st[0] += Math.max(0, count);
            likeTotalShown += Math.max(0, count);
            reach = st[0] / step;
            due = Math.max(0, Math.min(3, reach - st[1]));
            st[1] = Math.max(st[1], reach);
        }
        if (!ClientSettings.actionsEnabled) return 0;
        int started = 0;
        for (long k = 0; k < due; k++) {
            long mark = (reach - due + 1 + k) * step;
            MemeSounds.play("tap");
            if (onEvent(new Event("like", user, uid, mark + " tap!", "", 1, 0)) > 0) started++;
        }
        if (started > 0 && ClientSettings.ttsReadGift) {
            String name = safeName(user);
            TikTokLiveService.speak((name.isEmpty() ? "Penonton" : name) + " dapat event acak dari " + (reach * step) + " tap", 2);
        }
        return started;
    }

    // ------------------------------------------------------------------ receiving events

    private static boolean duplicate(Event e) {
        long now = System.currentTimeMillis();
        synchronized (SEEN_SOFT) {
            if (e.eid() != null && !e.eid().isEmpty()) {
                if (!SEEN_IDS.add(e.kind() + ":" + e.eid())) return true;
                if (SEEN_IDS.size() > 600) { Iterator<String> it = SEEN_IDS.iterator(); it.next(); it.remove(); }
            }
            if (e.kind().equals("follow") || e.kind().equals("share") || e.kind().equals("subscribe")) {
                String key = e.kind() + ":" + (e.uid() == null || e.uid().isEmpty() ? e.user() : e.uid());
                Long last = SEEN_SOFT.get(key);
                SEEN_SOFT.put(key, now);
                if (SEEN_SOFT.size() > 300) SEEN_SOFT.clear();
                return last != null && now - last < 3000;
            }
        }
        return false;
    }

    /**
     * Called for every LIVE event (also by the simulator). Plans the matching recipes; the tick loop runs them.
     * A gift combo is handled piece by piece: every new piece (count) repeats the recipe once, up to the per-event limit.
     * @return number of jobs planned
     */
    static int onEvent(Event e) {
        if (!ClientSettings.actionsEnabled) return 0;
        boolean gift = e.kind().equals("gift");
        if (gift && e.count() <= 0) return 0;
        if (duplicate(e)) return 0;
        List<Trigger> matched = match(e);
        String who = e.user() == null || e.user().isBlank() ? "Penonton" : e.user();
        String giftName = gift ? GiftCatalog.canonical(e.gift()) : e.kind();
        int unit = unitCoins(e);
        if (matched.isEmpty()) {
            if (gift) {
                EventRec rec = new EventRec(who, giftName + (e.count() > 1 ? " ×" + e.count() : ""), "-");
                rec.note = unit == 0 ? "harga gift tidak diketahui dan tidak ada aturan 'gift='" : "tidak ada aturan";
                addLog(rec);
            }
            return 0;
        }
        if (e.kind().equals("comment")) {
            long now = System.currentTimeMillis();
            String key = e.uid() == null || e.uid().isBlank() ? who : e.uid();
            synchronized (LAST_COMMAND) {
                Long last = LAST_COMMAND.get(key);
                if (last != null && now - last < Math.max(0, ClientSettings.cmdCooldownSec) * 1000L) return 0;
                LAST_COMMAND.put(key, now);
                if (LAST_COMMAND.size() > 500) LAST_COMMAND.clear();
            }
        }
        int pieces = gift ? e.count() : 1;
        int used = Math.max(1, Math.min(pieces, Math.max(1, ClientSettings.actionsMaxPerEvent)));
        int planned = 0;
        for (Trigger t : matched) {
            Recipe recipe = parseRecipe(t.mob(), new String[1]);
            if (recipe == null) continue;
            String source = gift ? giftName + (pieces > 1 ? " ×" + pieces : "") : t.label();
            EventRec rec = new EventRec(who, source, prettySpec(t.mob()));
            if (pieces > used) rec.note = "jumlah " + pieces + " dibatasi " + used + " (batas aman per event)";
            int before = rec.total;
            for (int i = 0; i < used; i++) schedule(recipe, unit, who, rec);
            addLog(rec);
            planned += rec.total - before;
            if (rec.total > 0) announce(e, t, giftName, who, unit, used, pieces, rec);
            else LiveBanner.push(who + ": " + (rec.note.isEmpty() ? "tidak ada efek" : rec.note), 0xFFF87171, e.uid());
        }
        return planned;
    }

    private static void announce(Event e, Trigger t, String giftName, String who, int unit, int used, int pieces, EventRec rec) {
        boolean tier = t.type().equals("diamond");
        boolean gift = e.kind().equals("gift");
        String head = tier ? tierName(Integer.parseInt(t.arg())) + " · " : e.kind().equals("like") ? "TAP EVENT · " : "";
        String what = switch (e.kind()) {
            case "gift" -> giftName + (pieces > 1 ? " ×" + pieces : "");
            case "comment" -> t.arg();
            case "like", "goal" -> e.text();
            default -> e.kind().toUpperCase(Locale.ROOT);
        };
        String line = head + what + " dari " + who + " → " + prettySpec(t.mob());
        if (!rec.note.isEmpty()) line += " (" + rec.note + ")";
        int color = tier ? tierColor(Integer.parseInt(t.arg())) : e.kind().equals("gift") ? tierColor(Math.max(1, unit)) : 0xFF38BDF8;
        LiveBanner.push(line, color, e.uid());
        boolean big = e.kind().equals("like") || e.kind().equals("goal") || (gift && unit >= 10);
        if (big) bigTitle(safeNameOr(who), e.kind().equals("gift") ? giftName + (pieces > 1 ? " x" + pieces : "") : e.text());
    }

    private static void addLog(EventRec rec) {
        LOG.add(0, rec);
        while (LOG.size() > 80) LOG.remove(LOG.size() - 1);
    }

    private static boolean isEntity(String kind) { return kind.equals("mob") || kind.equals("tnt"); }

    /** Puts one run of a recipe into the job list, spreading entities over time so nothing appears on the same tick. */
    private static void schedule(Recipe recipe, int coins, String viewer, EventRec rec) {
        synchronized (LOCK) {
            boolean heavy = recipe.waves().size() > 1;
            long base = tickNow + 1;
            HEAVY_ENDS.removeIf(end -> end < tickNow);
            if (heavy) {
                if (HEAVY_ENDS.size() >= 6) { rec.note = "antrean event besar penuh (maks 6)"; return; }
                base = Math.max(base, heavyBusyUntil);
            }
            if (JOBS.size() > 400) { rec.note = "antrean penuh, event diabaikan"; return; }
            int gap = Math.max(1, ClientSettings.spawnGapTicks), waveGap = Math.max(5, ClientSettings.waveGapTicks);
            long last = base;
            for (int w = 0; w < recipe.waves().size(); w++) {
                long waveDue = base + (long) w * waveGap;
                int slot = 0;
                for (Part p : recipe.waves().get(w)) {
                    List<Part> flat = new ArrayList<>();
                    if (p.kind().equals("pool")) {
                        Pool pool = POOLS.get(p.id());
                        if (pool == null) continue;
                        for (int i = 0; i < p.n(); i++) {
                            boolean good = ThreadLocalRandom.current().nextInt(100) < ClientSettings.potionGoodPct;
                            flat.addAll(poolEntry(pick(pool, good)));
                        }
                    } else flat.add(p);
                    for (Part f : flat) {
                        int n = f.n() == -1 ? Math.max(1, coins) : f.n();
                        switch (f.kind()) {
                            case "mob" -> { for (int i = 0; i < n; i++) { last = Math.max(last, add(new Job("mob", f.id(), 1, viewer, waveDue + (long) slot++ * gap, rec))); } }
                            case "tnt" -> {
                                int cap = Math.max(1, ClientSettings.tntMax);
                                if (n > cap) { rec.note = "TNT dibatasi " + cap + " (kamu minta " + n + ")"; n = cap; }
                                for (int i = 0; i < n; i++) { last = Math.max(last, add(new Job("tnt", f.id(), 1, viewer, waveDue + (long) slot++ * gap, rec))); }
                            }
                            case "give", "fx" -> last = Math.max(last, add(new Job(f.kind(), f.id(), f.n() == -1 ? 1 : f.n(), viewer, waveDue, rec)));
                            case "say", "title", "sound" -> last = Math.max(last, add(new Job(f.kind(), f.id(), 1, viewer, waveDue, rec)));
                            default -> { }
                        }
                    }
                }
            }
            if (heavy) { heavyBusyUntil = last + 40; HEAVY_ENDS.add(last + 40); }
        }
    }

    private static long add(Job j) {
        JOBS.add(j);
        j.rec().total++;
        return j.due();
    }

    // ------------------------------------------------------------------ execution (client tick)

    /** Called every client tick (20 per second). Runs due jobs, at most four per tick, and keeps within the entity budget. */
    static void tick() {
        tickNow++;
        List<Job> toRun = null;
        synchronized (LOCK) {
            if (JOBS.isEmpty()) return;
            if (!McCompat.inWorld()) {
                for (Job j : JOBS) finish(j, "kamu keluar dari dunia sebelum efek berjalan");
                JOBS.clear();
                return;
            }
            while (!RECENT.isEmpty() && tickNow - RECENT.peekFirst() > 600) RECENT.pollFirst();
            int ran = 0, waiting = 0;
            Iterator<Job> it = JOBS.iterator();
            while (it.hasNext() && ran < 4) {
                Job j = it.next();
                if (j.due() > tickNow) continue;
                if (isEntity(j.kind()) && RECENT.size() >= Math.max(1, ClientSettings.maxEntities)) {
                    waiting++;
                    if (tickNow - j.due() > 2400) { it.remove(); finish(j, "batas entity penuh (menunggu lebih dari 2 menit)"); }
                    continue;
                }
                it.remove();
                if (isEntity(j.kind())) RECENT.addLast(tickNow);
                if (toRun == null) toRun = new ArrayList<>();
                toRun.add(j);
                ran++;
            }
            deferred = waiting;
        }
        if (toRun != null) for (Job j : toRun) run(j);
    }

    private static double[] ring() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2, radius = 3.0 + rnd.nextDouble() * 3.0;
        return new double[] {Math.cos(angle) * radius, Math.sin(angle) * radius};
    }

    private static String rel(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    private static void run(Job j) {
        switch (j.kind()) {
            case "mob" -> {
                double[] d = ring();
                String id = j.id();
                boolean plain = id.equals("minecraft:lightning_bolt") || id.equals("minecraft:firework_rocket");
                String base = "summon " + id + " ~" + rel(d[0]) + " ~ ~" + rel(d[1]);
                String name = safeName(j.viewer());
                if (ClientSettings.actionsNameTag && !plain && !name.isEmpty()) {
                    command(base + " {CustomName:\"" + name + "\",CustomNameVisible:1b}", true, j, base);
                } else command(base, true, j, null);
            }
            case "tnt" -> {
                double[] d = ring();
                if (ClientSettings.tntSafe) {
                    // no real TNT: the same bang (particles + sound) without touching a single block
                    McCompat.runCommandAsync("playsound minecraft:entity.generic.explode master @s ~" + rel(d[0]) + " ~ ~" + rel(d[1]) + " 1.5 1", false, err -> { });
                    command("particle minecraft:explosion_emitter ~" + rel(d[0]) + " ~1 ~" + rel(d[1]), false, j, null);
                } else {
                    int fuse = Math.max(1, ClientSettings.tntFuseSec) * 20;
                    String base = "summon minecraft:tnt ~" + rel(d[0]) + " ~2 ~" + rel(d[1]);
                    command(base + " {fuse:" + fuse + ",Fuse:" + fuse + "}", true, j, base);
                }
            }
            case "give" -> {
                int n = Math.max(1, Math.min(64, j.n()));
                command("give @s " + j.id() + " " + n, true, j, null);
            }
            case "fx" -> {
                String id = j.id();
                boolean instant = id.endsWith("instant_health") || id.endsWith("saturation");
                int seconds = instant ? 1 : Math.max(3, ClientSettings.potionSeconds);
                int amp = Math.max(0, Math.min(5, j.n() - 1));
                command("effect give @s " + id + " " + seconds + " " + amp + " true", true, j, null);
            }
            case "say" -> {
                LiveBanner.push(j.id().replace("{nama}", safeNameOr(j.viewer())), 0xFFFFD700, "");
                finish(j, null);
            }
            case "title" -> {
                McCompat.runCommandAsync("title @s times 5 50 15", false, err -> { });
                command("title @s title \"" + j.id().replace("{nama}", safeNameOr(j.viewer())) + "\"", true, j, null);
            }
            case "sound" -> command("playsound minecraft:" + j.id() + " master @s ~ ~ ~ 1 1", false, j, null);
            default -> finish(j, "jenis hadiah tidak dikenal");
        }
    }

    private static void command(String cmd, boolean strict, Job j, String fallback) {
        McCompat.runCommandAsync(cmd, strict, err -> {
            if (err != null && fallback != null) McCompat.runCommandAsync(fallback, strict, err2 -> finish(j, err2));
            else finish(j, err);
        });
    }

    private static void finish(Job j, String err) {
        EventRec r = j.rec();
        synchronized (r) {
            if (err == null) r.done++;
            else { r.failed++; if (r.error.isEmpty()) r.error = err; }
        }
        if (err == null) {
            spawnedTotal++;
            if (j.kind().equals("give")) LiveBanner.push((j.viewer().isBlank() ? "Hadiah" : j.viewer()) + " memberi " + j.n() + " " + j.id().replace("minecraft:", "").replace('_', ' '), 0xFF4ADE80, "");
            else if (j.kind().equals("fx")) LiveBanner.push("Efek: " + fxName(j.id()), 0xFFC084FC, "");
        } else {
            failedTotal++;
            System.err.println("[XYOPlay] Efek gagal (" + j.kind() + " " + j.id() + "): " + err);
            long now = System.currentTimeMillis();
            if (now - lastFailBanner > 4000) {
                lastFailBanner = now;
                LiveBanner.push("Gagal: " + j.id().replace("minecraft:", "") + " — " + err, 0xFFF87171, "");
            }
        }
    }

    // ------------------------------------------------------------------ test helpers (buttons, /xyoplay)

    /** Runs a whole recipe now, as if a gift worth `coins` per piece had arrived. */
    static int test(String spec, String viewer, int coins) {
        Recipe r = parseRecipe(spec, new String[1]);
        if (r == null) return 0;
        EventRec rec = new EventRec(viewer, "Uji", prettySpec(spec));
        schedule(r, coins, viewer, rec);
        addLog(rec);
        return rec.total;
    }

    /** Spawns one mob right now (tests, "Uji" button, /xyoplay spawn). */
    static boolean spawn(String mob, String viewer) {
        String id = resolveMob(mob);
        if (id == null) return false;
        return test(id.equals("minecraft:tnt") ? "tnt" : id.replace("minecraft:", ""), viewer, 1) > 0;
    }

    static String safeNameOr(String raw) { String n = safeName(raw); return n.isEmpty() ? "Penonton" : n; }

    /** Big on-screen title with a subtitle (used for big gifts, tap events and goals). Fire and forget. */
    static void bigTitle(String title, String subtitle) {
        if (!ClientSettings.bigTitle || !McCompat.inWorld()) return;
        String t = title == null ? "" : title.replaceAll("[\\p{Cntrl}\"\\\\]", " ");
        String sub = subtitle == null ? "" : subtitle.replaceAll("[\\p{Cntrl}\"\\\\]", " ");
        McCompat.runCommandAsync("title @s times 5 50 15", false, err -> { });
        McCompat.runCommandAsync("title @s subtitle \"" + (sub.length() > 60 ? sub.substring(0, 60) : sub) + "\"", false, err -> { });
        McCompat.runCommandAsync("title @s title \"" + (t.length() > 40 ? t.substring(0, 40) : t) + "\"", false, err -> { });
    }

    /** Viewer nicknames can contain anything; only harmless characters may reach a command. */
    static String safeName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[^\\p{L}\\p{N} _.\\-]", "").strip();
        return s.length() > 16 ? s.substring(0, 16).strip() : s;
    }

    // ------------------------------------------------------------------ persistence

    private static final int FILE_VERSION = 4;

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay").resolve("live-actions.json");
    }

    static synchronized void load() {
        loaded = true;
        TRIGGERS.clear();
        Path f = null;
        try {
            f = file();
            if (Files.exists(f)) {
                JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                int version = root.has("version") ? root.get("version").getAsInt() : 1;
                if (version >= FILE_VERSION && root.has("triggers") && root.get("triggers").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("triggers")) {
                        JsonObject o = el.getAsJsonObject();
                        String type = o.get("type").getAsString(), arg = o.has("arg") ? o.get("arg").getAsString() : "", mob = o.get("mob").getAsString();
                        if (parseRecipe(mob, new String[1]) != null) TRIGGERS.add(new Trigger(type, arg, mob, 1));
                    }
                    return;
                }
                // older format: keep a backup and start from the new default table
                Files.copy(f, f.resolveSibling("live-actions.old.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("[XYOPlay] live-actions.json rusak, dipulihkan ke bawaan: " + e.getMessage());
            try { if (f != null && Files.exists(f)) Files.copy(f, f.resolveSibling("live-actions.rusak.json"), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) { }
            TRIGGERS.clear();
        }
        for (String line : PRESET_XYOMC) add(line);
        save();
    }

    static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonArray arr = new JsonArray();
            for (Trigger t : TRIGGERS) {
                JsonObject o = new JsonObject();
                o.addProperty("type", t.type()); o.addProperty("arg", t.arg()); o.addProperty("mob", t.mob());
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", FILE_VERSION);
            root.add("triggers", arr);
            Files.writeString(f, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal menyimpan live-actions.json: " + e.getMessage());
        }
    }
}
