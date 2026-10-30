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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gift catalog learned from real LIVE events (name, TikTok id, price in coins), kept in config/xyoplay/gift-catalog.json so
 * it keeps working offline. Prices only come from real events; the few "reference" prices below are the ones the reward
 * table was designed around and are always shown as such. Nothing here is fetched from the internet.
 */
final class GiftBook {
    private GiftBook() {}

    record Entry(String name, int id, int price, int times, long lastSeen, String source) {}

    /** Reference prices of the default reward table (they can change; a real LIVE event always overrides them). */
    private static final Map<String, Integer> REFERENCE = Map.ofEntries(
            Map.entry("Rose", 1), Map.entry("Coffee", 1), Map.entry("Orange Juice", 1), Map.entry("GG", 1), Map.entry("Kamu Hebat Banget", 1),
            Map.entry("Finger Heart", 5), Map.entry("Nasi Padang", 5), Map.entry("Rosa", 10), Map.entry("Perfume", 20),
            Map.entry("Doughnut", 30), Map.entry("Hat and Mustache", 99));

    private static final Map<String, Entry> LEARNED = new LinkedHashMap<>();
    private static boolean loaded;
    private static long updated;

    private static synchronized void ensureLoaded() { if (!loaded) load(); }

    static synchronized void learn(String name, int id, int price) {
        ensureLoaded();
        if (name == null || name.isBlank()) return;
        String canon = GiftCatalog.canonical(name);
        Entry old = LEARNED.get(canon);
        int newId = id > 0 ? id : old == null ? 0 : old.id();
        int newPrice = price > 0 ? price : old == null ? 0 : old.price();
        Entry now = new Entry(canon, newId, newPrice, (old == null ? 0 : old.times()) + 1, System.currentTimeMillis(), "LIVE");
        LEARNED.put(canon, now);
        updated = now.lastSeen();
        cacheAt = 0;
        boolean changed = old == null || old.id() != newId || old.price() != newPrice;
        if (changed || now.times() % 10 == 0) save();
    }

    static synchronized int idOf(String name) {
        ensureLoaded();
        Entry e = LEARNED.get(GiftCatalog.canonical(name));
        return e == null ? 0 : e.id();
    }

    /** Coins of one piece, or -1 when not known. */
    static synchronized int priceOf(String name) {
        ensureLoaded();
        String canon = GiftCatalog.canonical(name);
        Entry e = LEARNED.get(canon);
        if (e != null && e.price() > 0) return e.price();
        Integer ref = REFERENCE.get(canon);
        return ref == null ? -1 : ref;
    }

    static synchronized String sourceOf(String name) {
        ensureLoaded();
        String canon = GiftCatalog.canonical(name);
        Entry e = LEARNED.get(canon);
        if (e != null && e.price() > 0) return "LIVE";
        return REFERENCE.containsKey(canon) ? "acuan" : "belum diketahui";
    }

    static synchronized long lastUpdate() { ensureLoaded(); return updated; }

    /** Every gift we know a name for: the built-in list, the reference table and everything seen in a LIVE. */
    private static List<Entry> cache = List.of();
    private static long cacheAt;

    static synchronized List<Entry> entries() {
        ensureLoaded();
        long now = System.currentTimeMillis();
        if (now - cacheAt < 400 && !cache.isEmpty()) return cache;
        cache = build();
        cacheAt = now;
        return cache;
    }

    private static List<Entry> build() {
        Map<String, Entry> all = new LinkedHashMap<>();
        for (GiftCatalog.Gift g : GiftCatalog.GIFTS) all.put(g.name(), new Entry(g.name(), 0, -1, 0, 0, "bawaan"));
        for (Entry e : LEARNED.values()) all.put(e.name(), e);
        List<Entry> out = new ArrayList<>(all.values());
        out.sort(Comparator.comparing((Entry e) -> e.times() == 0).thenComparing(e -> e.price() <= 0 && !REFERENCE.containsKey(e.name()))
                .thenComparingInt(e -> e.price() > 0 ? e.price() : REFERENCE.getOrDefault(e.name(), 100000)).thenComparing(Entry::name));
        return out;
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay").resolve("gift-catalog.json");
    }

    private static void load() {
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            updated = root.has("updated") ? root.get("updated").getAsLong() : 0;
            for (JsonElement el : root.getAsJsonArray("gifts")) {
                JsonObject o = el.getAsJsonObject();
                String name = o.get("name").getAsString();
                LEARNED.put(name, new Entry(name, o.has("id") ? o.get("id").getAsInt() : 0, o.has("price") ? o.get("price").getAsInt() : 0,
                        o.has("times") ? o.get("times").getAsInt() : 1, o.has("seen") ? o.get("seen").getAsLong() : 0, "LIVE"));
            }
        } catch (Exception e) {
            System.err.println("[XYOPlay] gift-catalog.json tidak terbaca, dimulai kosong: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonArray arr = new JsonArray();
            for (Entry e : LEARNED.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.name()); o.addProperty("id", e.id()); o.addProperty("price", e.price());
                o.addProperty("times", e.times()); o.addProperty("seen", e.lastSeen());
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("updated", updated);
            root.add("gifts", arr);
            Files.writeString(f, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal menyimpan gift-catalog.json: " + e.getMessage());
        }
    }
}
