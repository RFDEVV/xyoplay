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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Badword list (words that are never read aloud and are shown as ***) and banned accounts (their comments, follows
 * and gifts are ignored completely). Both lists are saved in config/xyoplay/live-filter.json.
 */
final class LiveFilter {
    private LiveFilter() {}

    private static final List<String> WORDS = new CopyOnWriteArrayList<>();
    private static final List<String> BANNED = new CopyOnWriteArrayList<>();
    private static boolean loaded;

    private static final String[] FALLBACK_WORDS = {"anjing", "babi", "bangsat", "bajingan", "kontol", "memek", "ngentot", "jancok", "tolol", "goblok",
            "fuck", "shit", "bitch", "asshole", "bastard"};

    /** The built-in list (200+ words) from assets/xyoplay/badwords_default.txt; the player can remove any of them afterwards. */
    static List<String> defaultWords() {
        List<String> out = new ArrayList<>();
        try (java.io.InputStream in = LiveFilter.class.getResourceAsStream("/assets/xyoplay/badwords_default.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String w = normalizeWord(line);
                    if (!w.isEmpty() && !w.startsWith("#") && !out.contains(w)) out.add(w);
                }
            }
        } catch (Exception e) {
            System.err.println("[XYOPlay] Daftar kata bawaan tidak terbaca: " + e.getMessage());
        }
        if (out.isEmpty()) for (String w : FALLBACK_WORDS) out.add(w);
        return out;
    }

    static synchronized void ensureLoaded() { if (!loaded) load(); }

    static List<String> words() { ensureLoaded(); return List.copyOf(WORDS); }
    static List<String> banned() { ensureLoaded(); return List.copyOf(BANNED); }

    static String normalizeWord(String word) {
        if (word == null) return "";
        String w = word.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return w.length() > 40 ? w.substring(0, 40) : w;
    }

    static String normalizeUser(String user) {
        if (user == null) return "";
        String u = user.strip().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        return u.matches("[a-z0-9_.\\- ]{1,48}") ? u : "";
    }

    /** @return true when the word was new. */
    static boolean addWord(String word) {
        ensureLoaded();
        String w = normalizeWord(word);
        if (w.isEmpty() || WORDS.contains(w)) return false;
        WORDS.add(w);
        java.util.Collections.sort(WORDS);
        changed(true, false);
        return true;
    }

    static boolean removeWord(String word) {
        ensureLoaded();
        boolean removed = WORDS.remove(normalizeWord(word));
        if (removed) changed(true, false);
        return removed;
    }

    static int addDefaults() {
        ensureLoaded();
        int added = 0;
        for (String w : defaultWords()) if (!WORDS.contains(w)) { WORDS.add(w); added++; }
        java.util.Collections.sort(WORDS);
        if (added > 0) changed(true, false);
        return added;
    }

    static void clearWords() { ensureLoaded(); WORDS.clear(); changed(true, false); }

    static boolean ban(String user) {
        ensureLoaded();
        String u = normalizeUser(user);
        if (u.isEmpty() || BANNED.contains(u)) return false;
        BANNED.add(u);
        changed(false, true);
        return true;
    }

    static boolean unban(String user) {
        ensureLoaded();
        boolean removed = BANNED.remove(normalizeUser(user));
        if (removed) changed(false, true);
        return removed;
    }

    static boolean isBanned(String uid, String nickname) {
        ensureLoaded();
        if (BANNED.isEmpty()) return false;
        if (uid != null && !uid.isBlank() && BANNED.contains(uid.toLowerCase(Locale.ROOT).replaceFirst("^@", ""))) return true;
        return nickname != null && !nickname.isBlank() && BANNED.contains(nickname.toLowerCase(Locale.ROOT).strip());
    }

    private static void changed(boolean words, boolean banned) {
        save();
        TikTokLiveService.syncFilter();
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay").resolve("live-filter.json");
    }

    static synchronized void load() {
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) {      // first run: start with the built-in list
                WORDS.addAll(defaultWords());
                java.util.Collections.sort(WORDS);
                save();
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            WORDS.clear(); BANNED.clear();
            if (root.has("words") && root.get("words").isJsonArray()) for (JsonElement e : root.getAsJsonArray("words")) {
                String w = normalizeWord(e.getAsString()); if (!w.isEmpty() && !WORDS.contains(w)) WORDS.add(w);
            }
            if (root.has("banned") && root.get("banned").isJsonArray()) for (JsonElement e : root.getAsJsonArray("banned")) {
                String u = normalizeUser(e.getAsString()); if (!u.isEmpty() && !BANNED.contains(u)) BANNED.add(u);
            }
            java.util.Collections.sort(WORDS);
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal membaca live-filter.json: " + e.getMessage());
        }
    }

    static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonObject root = new JsonObject();
            JsonArray w = new JsonArray(); for (String s : WORDS) w.add(s);
            JsonArray b = new JsonArray(); for (String s : BANNED) b.add(s);
            root.add("words", w);
            root.add("banned", b);
            Files.writeString(f, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal menyimpan live-filter.json: " + e.getMessage());
        }
    }

    static JsonArray wordsJson() { JsonArray a = new JsonArray(); for (String s : words()) a.add(s); return a; }
    static JsonArray bannedJson() { JsonArray a = new JsonArray(); for (String s : banned()) a.add(s); return a; }
    static List<String> copyWords() { return new ArrayList<>(words()); }
}
