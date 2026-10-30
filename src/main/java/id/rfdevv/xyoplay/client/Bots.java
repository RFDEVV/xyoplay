package id.rfdevv.xyoplay.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Manages the helper bots (real entities moved with commands) and the AFK autopilot (the player's own body driven the
 * same way). The heavy work is bounded: a small shared path-search budget per tick, idle bots send nothing.
 */
final class Bots {
    static final String[][] TYPES = {{"mannequin", "Pemain (skin)"}, {"zombie", "Zombie"}, {"skeleton", "Skeleton"}, {"piglin", "Piglin"}, {"villager", "Villager"}, {"iron_golem", "Iron Golem"}};
    /** Built-in skins (work offline) + any account name. {label, texture id, model} */
    static final String[][] SKINS = {
            {"Steve", "minecraft:entity/player/wide/steve", "wide"}, {"Alex", "minecraft:entity/player/slim/alex", "slim"},
            {"Kai", "minecraft:entity/player/wide/kai", "wide"}, {"Noor", "minecraft:entity/player/slim/noor", "slim"},
            {"Zuri", "minecraft:entity/player/wide/zuri", "wide"}, {"Sunny", "minecraft:entity/player/wide/sunny", "wide"},
            {"Ari", "minecraft:entity/player/wide/ari", "wide"}, {"Efe", "minecraft:entity/player/slim/efe", "slim"},
            {"Makena", "minecraft:entity/player/slim/makena", "slim"}, {"Nama akun…", "", ""}};
    static final String[] OUTFITS = {"Tanpa pakaian", "Petualang (hijau)", "Ninja (hitam)", "Ksatria (besi)", "Raja (emas)"};
    static final String[] SPEEDS = {"Santai", "Normal", "Cepat"};
    static final String[] ECO = {"Hemat", "Normal", "Kencang"};
    static final String[] KITS = {"Tanpa bekal", "Dasar (alat kayu)", "Besi lengkap", "Berlian"};
    static final String[] MINE_KINDS = {"auto", "kayu", "batu", "tanah", "besi", "berlian"};

    static final List<BotAgent> BOTS = new ArrayList<>();
    static BotAgent afk;
    static boolean afkOn;
    static String problem = "";

    // test hooks
    static BotWorld worldOverride;
    static BotAgent.IO ioOverride;
    static double[] playerOverride;
    static java.util.function.BooleanSupplier deadOverride;
    static Runnable respawnHook;

    private static McWorld mcw;
    private static BotOverlay overlay;
    private static long tick;
    private static int rr;
    private static String lastDim = "";
    private static int cmdFail;
    private static final BotAgent.Cfg CFG = new BotAgent.Cfg();
    private static boolean loaded;

    // AFK state
    private static boolean wasDead;
    private static long lastRespawn, afkReturnStart, lastFood, lastSync, lingerUntil;
    private static double[] lastAlive, deathPos;
    private static final List<Long> DEATHS = new ArrayList<>();
    private static boolean nightMode;
    static String afkStatus = "";

    private Bots() {}

    static void onLeave() { for (BotAgent a : BOTS) a.spawned = false; if (afkOn) setAfk(false); }

    static boolean active() { return ClientSettings.botEnabled || afkOn; }

    // ------------------------------------------------------------------ IO

    private static final BotAgent.IO REAL_IO = new BotAgent.IO() {
        private void send(String c) {
            McCompat.runCommandAsync(c, false, err -> {
                if (err == null) cmdFail = 0;
                else if (++cmdFail == 25) problem = "command ditolak (" + err + ") - butuh dunia sendiri atau OP";
            });
        }
        public void tp(String sel, double x, double y, double z, float yaw) {
            java.util.UUID id = null;
            float pitch = 0;
            try {
                if (sel.equals("@s")) { id = Minecraft.getInstance().player.getUUID(); pitch = McCompat.pitch(); }
                else {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("xyobot_(\\d+)").matcher(sel);
                    if (m.find()) { int n = Integer.parseInt(m.group(1)); for (BotAgent a : BOTS) if (a.id == n && a.uuid != null) id = java.util.UUID.fromString(a.uuid); }
                }
            } catch (Throwable ignored) { }
            if (id != null && ServerBridge.move(id, x, y, z, yaw, pitch)) return;
            if (ServerBridge.available() && sel.equals("@s")) return;      // never fall back to a command that may clash with other mods
            send(String.format(java.util.Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f 0", sel, x, y, z, yaw));
        }
        public void setBlock(int x, int y, int z, String b) { send("setblock " + x + " " + y + " " + z + " " + b + " replace"); }
        public boolean damage(String sel, String uuid, double amt) {
            send(String.format(java.util.Locale.ROOT, "damage %s %.1f minecraft:%s by %s", uuid, amt, sel.equals("@s") ? "player_attack" : "mob_attack", sel));
            return cmdFail < 25;
        }
        public void equip(String sel, String slot, String item) {
            if (sel.equals("@s")) {
                if (slot.startsWith("armor")) send("execute unless items entity @s " + slot + " * run item replace entity @s " + slot + " with " + item);
                else send("give @s " + item);
            } else send("item replace entity " + sel + " " + slot + " with " + item);
        }
        public void give(String item, int n) { send("give @s " + item + " " + n); }
        public void chat(String msg) { McCompat.localMessage("§b[Bot]§7 " + msg); }
    };

    private static BotAgent.IO io() { return ioOverride != null ? ioOverride : REAL_IO; }

    private static BotWorld world() {
        if (worldOverride != null) return worldOverride;
        if (mcw == null) { mcw = new McWorld(); overlay = new BotOverlay(mcw); }
        return overlay;
    }

    // ------------------------------------------------------------------ lifecycle

    static BotAgent newAgent(int id, String sel, boolean playerBody) {
        BotAgent a = new BotAgent(id, sel, playerBody, world(), io(), CFG);
        return a;
    }

    private static void syncCfg() {
        CFG.speed = new double[] {0.15, 0.22, 0.3}[ClientSettings.botSpeed];
        CFG.followDist = ClientSettings.botFollowDist;
        CFG.dig = ClientSettings.botDig; CFG.place = ClientSettings.botPlace; CFG.fight = ClientSettings.botFight; CFG.autoGear = ClientSettings.botAutoGear;
        CFG.maxNodes = new int[] {250, 600, 1500}[ClientSettings.botEco];
        CFG.mineKind = ClientSettings.botMine;
    }

    static void giveKit(BotAgent a, boolean announce) {
        int kit = ClientSettings.botStartKit;
        if (kit <= 0) return;
        int t = kit == 1 ? 1 : kit == 2 ? 3 : 4;
        for (String s : BotGear.SLOTS) {
            if (BotGear.isArmor(s) && kit == 1) continue;
            int tier = BotGear.isArmor(s) ? Math.min(t, 4) : (s.equals("axe") ? Math.min(t, 3) : t);
            if (tier < 1) continue;
            a.gear.set(s, tier);
            if (announce && !a.playerBody) a.io.equip(a.sel, BotGear.equipSlot(s), BotGear.itemId(s, tier));
        }
        a.inv.add("cobblestone", kit == 1 ? 16 : kit == 2 ? 48 : 96);
        if (kit >= 2) a.inv.add("log", 8);
    }

    private static String sel(int i) { return "@e[tag=xyobot_" + i + ",limit=1]"; }

    private static String skinProfile() {
        int k = Math.max(0, Math.min(SKINS.length - 1, ClientSettings.botSkin));
        if (SKINS[k][1].isEmpty()) {
            String n = ClientSettings.botSkinName;
            return n.length() >= 3 ? "profile:\"" + n + "\"," : "";
        }
        return "profile:{texture:\"" + SKINS[k][1] + "\",model:\"" + SKINS[k][2] + "\"},";
    }

    /** Summon command, kept well under the 256-character limit servers enforce on typed commands. */
    static String summonCmd(int id, String pos, boolean forceZombie) {
        String type = TYPES[Math.min(ClientSettings.botType, TYPES.length - 1)][0];
        if (forceZombie) type = "zombie";
        String inv = ClientSettings.botSurvival ? "0b" : "1b";
        String name = "CustomName:\"XYO Bot " + id + "\"" + (ClientSettings.botNametag ? ",CustomNameVisible:1b" : "");
        String tags = "Tags:[\"xyobot\",\"xyobot_" + id + "\"]";
        if (type.equals("mannequin")) {
            return "summon mannequin " + pos + " {" + skinProfile() + "hide_description:1b,immovable:1b,NoGravity:1b,Invulnerable:" + inv + "," + name + "," + tags + "}";
        }
        return "summon " + type + " " + pos + " {NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:" + inv + "," + name + "," + tags + (type.equals("piglin") ? ",IsImmuneToZombification:1b" : "") + "}";
    }

    private static void summon(BotAgent a, double[] p) {
        int[] c = a.safeCellNear((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2]));
        double x = c != null ? c[0] + 0.5 : p[0], y = c != null ? c[1] : p[1], z = c != null ? c[2] + 0.5 : p[2];
        a.place(x, y, z);
        a.alive = true; a.spawned = true; a.lastCheck = tick; a.spawnedAt = tick; a.uuid = null; a.missing = 0; a.seen = false;
        if (ioOverride != null) return;
        final boolean player = TYPES[Math.min(ClientSettings.botType, TYPES.length - 1)][0].equals("mannequin");
        String pos = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
        McCompatSend("kill @e[tag=xyobot_" + a.id + "]");
        if (player) {
            McCompat.runCommandAsync(summonCmd(a.id, pos, false), true, err -> {
                if (err != null) {
                    // this game version has no mannequin (or rejects the data): use a zombie so the bot still works
                    McCompatSend(summonCmd(a.id, pos, true));
                    a.note("bentuk pemain tidak didukung (" + err + "), memakai Zombie");
                }
            });
        } else McCompatSend(summonCmd(a.id, pos, false));
        dress(a, player);
    }

    /** Cosmetic outfit first, then any real armor the bot has crafted on top of it. */
    private static void dress(BotAgent a, boolean player) {
        String[] slots = {"armor.head", "armor.chest", "armor.legs", "armor.feet"};
        String[] parts = {"helmet", "chestplate", "leggings", "boots"};
        int o = ClientSettings.botOutfit;
        for (int i = 0; i < 4; i++) {
            String item = null;
            switch (o) {
                case 1 -> item = "leather_" + parts[i] + "[dyed_color=3825741]";
                case 2 -> item = "leather_" + parts[i] + "[dyed_color=1710618]";
                case 3 -> item = "iron_" + parts[i];
                case 4 -> item = "golden_" + parts[i];
                default -> { if (i == 0 && !player) item = "leather_helmet"; }
            }
            if (item == null) continue;
            final String slot = slots[i], plain = "minecraft:" + item.replaceAll("\\[.*", "");
            final String full = "minecraft:" + item;
            McCompat.runCommandAsync("item replace entity " + sel(a.id) + " " + slot + " with " + full, true, err -> {
                if (err != null && !full.equals(plain)) McCompatSend("item replace entity " + sel(a.id) + " " + slot + " with " + plain);
            });
        }
        for (String s : BotGear.SLOTS) { int t = a.gear.tier(s); if (t > 0) io().equip(a.sel, BotGear.equipSlot(s), BotGear.itemId(s, t)); }
    }

    /** Re-summons every bot so a new skin / outfit / body shows up. */
    static void refreshLook() { if (!BOTS.isEmpty()) callAll(); }

    private static void McCompatSend(String c) { if (ioOverride == null) McCompat.runCommandAsync(c, false, e -> { }); }

    private static void ensureCount(double[] p) {
        int want = ClientSettings.botEnabled ? ClientSettings.botCount : 0;
        while (BOTS.size() < want) {
            int id = BOTS.size() + 1;
            BotAgent a = newAgent(id, sel(id), false);
            a.setMode(BotAgent.Mode.FOLLOW);
            giveKit(a, false);
            restoreInto(a, "bot" + id);
            BOTS.add(a);
        }
        while (BOTS.size() > want) {
            BotAgent a = BOTS.remove(BOTS.size() - 1);
            McCompatSend("kill @e[tag=xyobot_" + a.id + "]");
        }
    }

    /** Brings every bot to the player (re-summons them). */
    static void callAll() {
        double[] p = player();
        if (p == null) return;
        for (BotAgent a : BOTS) { summon(a, p); a.setMode(BotAgent.Mode.FOLLOW); }
    }

    static void dismissAll() {
        for (BotAgent a : BOTS) McCompatSend("kill @e[tag=xyobot_" + a.id + "]");
        BOTS.clear();
        ClientSettings.botEnabled = false;
        ClientSettings.save();
    }

    private static double[] player() { return playerOverride != null ? playerOverride : McCompat.position(); }

    // ------------------------------------------------------------------ tick

    static void tick() {
        if (!active()) { if (!BOTS.isEmpty()) { for (BotAgent a : BOTS) McCompatSend("kill @e[tag=xyobot_" + a.id + "]"); BOTS.clear(); } return; }
        if (!McCompat.inWorld()) { for (BotAgent a : BOTS) a.spawned = false; return; }
        double[] p = player();
        if (p == null) return;
        tick++;
        if (!loaded) { loaded = true; load(); }
        BotWorld w = world();
        if (worldOverride == null) {
            if (!McWorld.healthy) { problem = McWorld.problem; return; }
            mcw.newTick(tick); overlay.tick(tick);
        }
        syncCfg();
        String dim = worldOverride != null ? "test" : McCompat.dimension();
        if (!dim.equals(lastDim)) { if (!lastDim.isEmpty()) for (BotAgent a : BOTS) a.spawned = false; lastDim = dim; }

        if (afkOn) afkTick(p);
        if (ClientSettings.botEnabled) {
            ensureCount(p);
            int budget = ClientSettings.botEco == 0 ? 1 : ClientSettings.botEco == 1 ? 1 : 2;
            int n = BOTS.size();
            for (int k = 0; k < n; k++) {
                BotAgent a = BOTS.get((rr + k) % n);
                if (!a.alive) { if (tick >= a.respawnAt) a.spawned = false; else continue; }
                if (!a.spawned) { summon(a, p); continue; }
                a.setTarget(p[0], p[1], p[2]);
                double dx = a.x - p[0], dz = a.z - p[2], dy = a.y - p[1];
                if (dx * dx + dz * dz > 48 * 48 || Math.abs(dy) > 40) { a.unstick(p[0], p[1], p[2], "terlalu jauh"); a.setTarget(p[0], p[1], p[2]); }
                if (ioOverride == null && worldOverride == null && tick % 20 == (a.id % 20)) watchBody(a);
                budget -= a.tick(Math.max(0, budget));
            }
            rr++;
        }
        if (tick % 600 == 0) save();
    }

    /** Survival feel: nearby monsters hurt the bot, and it slowly heals (monsters do not target a mannequin on their own). */
    private static void survivalTick(BotAgent a) {
        if (!ClientSettings.botSurvival || mcw == null) return;
        boolean hurt = !mcw.hostiles(a.x, a.y, a.z, 2.6).isEmpty();
        if (hurt) McCompatSend("damage " + sel(a.id) + " 2 minecraft:mob_attack");
        else if (tick % 100 < 20) McCompatSend("effect give " + sel(a.id) + " minecraft:regeneration 3 0 true");
    }

    /** Finds the real entity of a bot; re-summons it when it is missing or far from where the bot thinks it is. */
    private static void watchBody(BotAgent a) {
        if (mcw == null) return;
        BotWorld.Mob m = mcw.findBot(a.id);
        if (m == null) {
            if (tick - a.spawnedAt < 60) return;
            if (a.seen && ClientSettings.botSurvival && ++a.missing >= 2) {
                // it was alive and is now gone: in survival that means it died
                a.deaths++; a.seen = false; a.uuid = null; a.alive = false; a.spawned = false; a.respawnAt = tick + 160; a.missing = 0;
                a.inv.m.replaceAll((k, v) -> v / 2);
                a.inv.m.values().removeIf(v -> v <= 0);
                a.note("bot mati (" + a.deaths + "x), muncul lagi sebentar lagi");
                McCompat.localMessage("§c[Bot]§7 Bot " + a.id + " mati! Setengah bahannya hilang, muncul lagi dalam 8 detik.");
                return;
            }
            if (a.seen && ClientSettings.botSurvival) return;
            if (++a.missing >= 3) {
                a.missing = 0;
                if (++a.respawns > 6) { problem = "bot " + a.id + " tidak mau muncul (cek izin command / dunia)"; return; }
                a.spawned = false;
                a.note("bot hilang, dimunculkan lagi");
            }
            return;
        }
        a.missing = 0; a.uuid = m.uuid(); a.seen = true;
        survivalTick(a);
        double dx = m.x() - a.x, dy = m.y() - a.y, dz = m.z() - a.z;
        if (dx * dx + dy * dy + dz * dz > 49) {
            if (++a.desync >= 4) { a.desync = 0; if (++a.respawns > 6) { problem = "bot " + a.id + " tidak bisa digerakkan: " + ServerBridge.last; return; } a.spawned = false; a.note("posisi bot tidak sinkron, dimunculkan lagi"); }
        } else { a.desync = 0; if (a.respawns > 0 && tick % 600 == 0) a.respawns--; }
    }

    // ------------------------------------------------------------------ AFK autopilot

    static void setAfk(boolean on) {
        if (on == afkOn) return;
        afkOn = on;
        if (on) {
            double[] p = player();
            afk = newAgent(0, "@s", true);
            if (p != null) afk.place(p[0], p[1], p[2]);
            afk.spawned = true;
            giveKit(afk, false);
            restoreInto(afk, "afk");
            afk.setMode(BotAgent.Mode.AUTO);
            nightMode = false; wasDead = false; deathPos = null; lastAlive = p;
            afkStatus = "AFK aktif";
            McCompat.localMessage("§b[AFK]§7 Autopilot aktif. Kamu boleh diam; matikan dengan /xyoplay afk off atau tombol di tab Bot.");
        } else {
            afk = null; afkStatus = "";
            McCompat.localMessage("§b[AFK]§7 Autopilot mati.");
        }
    }

    private static void afkTick(double[] p) {
        if (afk == null) return;
        boolean dead = deadOverride != null ? deadOverride.getAsBoolean() : worldOverride == null && McWorld.playerDead();
        if (dead) {
            if (!wasDead) {
                wasDead = true; deathPos = lastAlive == null ? p : lastAlive; DEATHS.add(tick);
                afkStatus = "mati, respawn otomatis…";
                afk.note("mati di " + (int) deathPos[0] + "," + (int) deathPos[1] + "," + (int) deathPos[2]);
            }
            if (ClientSettings.afkRespawn && tick - lastRespawn > 30) { lastRespawn = tick; if (respawnHook != null) respawnHook.run(); else McWorld.respawn(); }
            return;
        }
        if (wasDead) {
            wasDead = false;
            afk.place(p[0], p[1], p[2]);
            DEATHS.removeIf(t -> tick - t > 6000);
            boolean tooDeadly = DEATHS.size() >= 3;
            if (ClientSettings.afkReturn && deathPos != null && !tooDeadly) {
                afk.returning = true; afkReturnStart = tick;
                afk.setTarget(deathPos[0], deathPos[1], deathPos[2]);
                afk.setMode(BotAgent.Mode.FOLLOW);
                afkStatus = "kembali ke titik mati";
            } else {
                afk.returning = false; lingerUntil = 0; afk.hasTarget = false;
                afk.setMode(BotAgent.Mode.AUTO);
                afkStatus = tooDeadly ? "area berbahaya, tidak kembali" : "lanjut bermain";
                if (tooDeadly) afk.note("mati 3x di area ini, titik mati dilewati");
            }
        }
        lastAlive = p.clone();
        // keep the agent glued to the real body (knockback, falls, manual moves)
        if (tick - lastSync > 20) {
            lastSync = tick;
            double dx = afk.x - p[0], dy = afk.y - p[1], dz = afk.z - p[2];
            if (dx * dx + dy * dy + dz * dz > 6.25) afk.place(p[0], p[1], p[2]);
        }
        if (ClientSettings.afkFood && tick - lastFood > 1200) {
            lastFood = tick;
            McCompat.runCommandAsync("effect give @s minecraft:saturation 2 2 true", false, e -> { });
        }
        if (afk.returning) {
            double dx = afk.x - deathPos[0], dz = afk.z - deathPos[2], dy = afk.y - deathPos[1];
            boolean near = dx * dx + dz * dz < 9 && Math.abs(dy) < 3;
            if (near && lingerUntil == 0) lingerUntil = tick + 80;   // wait a moment so dropped items are picked up
            if ((lingerUntil != 0 && tick >= lingerUntil) || tick - afkReturnStart > 6000) {
                afk.returning = false; lingerUntil = 0;
                afk.hasTarget = false;
                afk.setMode(BotAgent.Mode.AUTO);
                afkStatus = near ? "kembali bermain" : "tidak sampai di titik mati, lanjut bermain";
            } else afkStatus = "kembali ke titik mati";
        } else {
            boolean night = worldOverride == null && McWorld.isNight() && ClientSettings.afkNightHome && afk.home != null;
            if (night != nightMode) {
                nightMode = night;
                if (night) { afk.setTarget(afk.home[0] + 0.5, afk.home[1], afk.home[2] + 0.5); afk.setMode(BotAgent.Mode.FOLLOW); }
                else { afk.hasTarget = false; afk.setMode(BotAgent.Mode.AUTO); }
            }
            afkStatus = nightMode ? "malam: berlindung di base" : afk.status;
        }
        afk.tick(1);
    }

    // ------------------------------------------------------------------ commands

    static String handle(String line) {
        String[] a = line.trim().toLowerCase().split("\\s+");
        String cmd = a.length == 0 || a[0].isEmpty() ? "status" : a[0];
        switch (cmd) {
            case "aktif", "on" -> { ClientSettings.botEnabled = true; ClientSettings.save(); return "Bot aktif (" + ClientSettings.botCount + ")"; }
            case "mati", "off" -> { dismissAll(); return "Bot dimatikan"; }
            case "hapus" -> { dismissAll(); return "Semua bot dihapus"; }
            case "jumlah" -> {
                if (a.length < 2) return "Pakai: bot jumlah <1-5>";
                try { ClientSettings.botCount = Math.max(1, Math.min(5, Integer.parseInt(a[1]))); } catch (NumberFormatException e) { return "Angka tidak valid"; }
                ClientSettings.botEnabled = true; ClientSettings.save(); return "Jumlah bot: " + ClientSettings.botCount;
            }
            case "panggil" -> { ensureOn(); callAll(); return "Bot dipanggil ke posisimu"; }
            case "ikut" -> { ensureOn(); setModeAll(BotAgent.Mode.FOLLOW); return "Bot mengikutimu"; }
            case "diam", "tunggu" -> { setModeAll(BotAgent.Mode.STAY); return "Bot diam di tempat"; }
            case "otomatis", "auto" -> { ensureOn(); ClientSettings.botMine = "auto"; setModeAll(BotAgent.Mode.AUTO); return "Bot bermain otomatis (cari bahan, buat alat, bangun base)"; }
            case "tambang" -> {
                ensureOn();
                String k = a.length > 1 ? a[1] : "auto";
                if (!List.of(MINE_KINDS).contains(k)) return "Pilih: " + String.join(", ", MINE_KINDS);
                ClientSettings.botMine = k; ClientSettings.save(); setModeAll(BotAgent.Mode.MINE);
                return "Bot menambang: " + k;
            }
            case "base" -> { ensureOn(); setModeAll(BotAgent.Mode.BUILD); return "Bot membangun base sederhana (butuh " + BotAgent.BASE_COST + " blok di tas bot)"; }
            case "buat" -> {
                if (a.length < 2) return "Pakai: bot buat <pickaxe|sword|axe|helmet|chestplate|leggings|boots> [kayu|batu|besi|berlian]";
                if (BOTS.isEmpty()) return "Bot belum aktif (bot aktif)";
                String slot = BotGear.slotFromText(a[1]);
                if (slot == null) return "Alat tidak dikenal: " + a[1];
                int tier = a.length > 2 ? BotGear.tierFromText(a[2]) : 0;
                if (tier == 0) tier = BotGear.isArmor(slot) ? 3 : 1;
                String err = BOTS.get(0).craftFor(slot, tier);
                if (err == null) return "Bot membuat " + slot + " " + BotGear.TIER_NAMES[tier] + " untukmu";
                BOTS.get(0).setMode(BotAgent.Mode.AUTO);
                return "Belum bisa: " + err + ". Bot mencari bahan dulu (mode otomatis).";
            }
            case "afk" -> {
                boolean on = a.length < 2 ? !afkOn : a[1].equals("on") || a[1].equals("aktif");
                setAfk(on);
                return on ? "Mode AFK aktif" : "Mode AFK mati";
            }
            case "inventori", "tas" -> { return BOTS.isEmpty() && afk == null ? "Belum ada bot" : inventoryText(); }
            default -> { return status(); }
        }
    }

    private static void ensureOn() { if (!ClientSettings.botEnabled) { ClientSettings.botEnabled = true; ClientSettings.save(); } }

    static void setModeAll(BotAgent.Mode m) { for (BotAgent a : BOTS) a.setMode(m); }

    static String inventoryText() {
        StringBuilder sb = new StringBuilder();
        for (BotAgent a : BOTS) sb.append("Bot ").append(a.id).append(": ").append(a.inv.m).append(" ").append(a.gear.tier).append("; ");
        if (afk != null) sb.append("AFK: ").append(afk.inv.m).append(" ").append(afk.gear.tier);
        return sb.toString();
    }

    static String status() {
        if (!active()) return "Bot mati. Pakai: bot aktif | panggil | ikut | diam | otomatis | tambang <jenis> | base | buat <alat> | jumlah <n> | afk";
        StringBuilder sb = new StringBuilder();
        for (BotAgent a : BOTS) sb.append("Bot ").append(a.id).append(" [").append(a.mode.label).append("] ").append(a.status).append(" · ").append(a.summary()).append("\n");
        if (afk != null) sb.append("AFK [").append(afkStatus).append("] ").append(afk.summary());
        if (!problem.isEmpty()) sb.append("\n! ").append(problem);
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay").resolve("bots.json"); }

    private static JsonObject dump(BotAgent a) {
        JsonObject o = new JsonObject();
        JsonObject inv = new JsonObject(); a.inv.m.forEach(inv::addProperty); o.add("inv", inv);
        JsonObject g = new JsonObject(); a.gear.tier.forEach(g::addProperty); o.add("gear", g);
        if (a.home != null) { JsonArray h = new JsonArray(); for (int v : a.home) h.add(v); o.add("home", h); }
        return o;
    }

    private static void restore(BotAgent a, JsonObject o) {
        if (o == null) return;
        if (o.has("inv")) o.getAsJsonObject("inv").entrySet().forEach(e -> a.inv.m.put(e.getKey(), e.getValue().getAsInt()));
        if (o.has("gear")) o.getAsJsonObject("gear").entrySet().forEach(e -> a.gear.set(e.getKey(), e.getValue().getAsInt()));
        if (o.has("home")) { JsonArray h = o.getAsJsonArray("home"); a.home = new int[] {h.get(0).getAsInt(), h.get(1).getAsInt(), h.get(2).getAsInt()}; }
    }

    static JsonObject saved;

    static void load() {
        try { if (worldOverride == null && Files.exists(file())) saved = new Gson().fromJson(Files.readString(file()), JsonObject.class); } catch (Exception ignored) { }
    }

    static void restoreInto(BotAgent a, String key) { if (saved != null && saved.has(key)) restore(a, saved.getAsJsonObject(key)); }

    static void save() {
        if (worldOverride != null) return;
        try {
            JsonObject root = saved != null ? saved : new JsonObject();
            for (BotAgent a : BOTS) root.add("bot" + a.id, dump(a));
            if (afk != null) root.add("afk", dump(afk));
            saved = root;
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new Gson().toJson(root));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ HUD

    static void renderHud(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (!afkOn || afk == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        String t = "AFK AUTOPILOT · " + (afkStatus.isEmpty() ? afk.status : afkStatus);
        int w = mc.font.width(t) + 16, x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
        Ui.box(g, x, 4, w, 16, 5, 0xCC10131A, 0xFF8B5CF6);
        g.text(mc.font, t, x + 8, 8, 0xFFE9D5FF, false);
    }
}
