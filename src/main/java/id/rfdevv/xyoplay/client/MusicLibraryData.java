package id.rfdevv.xyoplay.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** User-managed library data kept separately from audio files. */
public final class MusicLibraryData {
    public record Metadata(String title, String artist) {}
    public record Playlist(String name, List<Path> tracks) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> FAVORITES = new LinkedHashSet<>();
    private static final List<String> QUEUE = new ArrayList<>();
    private static final List<String> HISTORY = new ArrayList<>();
    private static final Map<String, List<String>> PLAYLISTS = new LinkedHashMap<>();
    private static final Map<String, Metadata> METADATA = new LinkedHashMap<>();
    private static Path dataFile;

    private MusicLibraryData() {}

    public static synchronized void load() {
        dataFile = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("xyoplay-library.json");
        if (!Files.exists(dataFile)) return;
        try (var reader = Files.newBufferedReader(dataFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            readStrings(root, "favorites", FAVORITES);
            readStrings(root, "queue", QUEUE);
            readStrings(root, "history", HISTORY);
            if (root.has("playlists") && root.get("playlists").isJsonObject()) {
                for (var entry : root.getAsJsonObject("playlists").entrySet()) {
                    List<String> tracks = new ArrayList<>();
                    readStrings(entry.getValue(), tracks::add);
                    PLAYLISTS.put(entry.getKey(), tracks);
                }
            }
            if (root.has("metadata") && root.get("metadata").isJsonObject()) {
                for (var entry : root.getAsJsonObject("metadata").entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject value = entry.getValue().getAsJsonObject();
                    METADATA.put(entry.getKey(), new Metadata(
                            string(value, "title"), string(value, "artist")));
                }
            }
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal membaca data pustaka: " + e.getMessage());
        }
    }

    public static synchronized boolean isFavorite(Path track) { return FAVORITES.contains(key(track)); }

    public static synchronized void toggleFavorite(Path track) {
        String key = key(track);
        if (!FAVORITES.add(key)) FAVORITES.remove(key);
        save();
    }

    public static synchronized List<Path> favorites() { return paths(FAVORITES); }
    public static synchronized List<Path> queued() { return paths(QUEUE); }
    public static synchronized List<Path> history() { return paths(HISTORY); }

    public static synchronized boolean enqueue(Path track) {
        String key = key(track);
        if (MusicLibrary.tracks().contains(track) && !QUEUE.contains(key)) {
            QUEUE.add(key);
            save();
            return true;
        }
        return false;
    }

    public static synchronized void removeQueued(Path track) {
        if (QUEUE.remove(key(track))) save();
    }

    public static synchronized Path takeNextQueued() {
        boolean changed = false;
        while (!QUEUE.isEmpty()) {
            String key = QUEUE.remove(0);
            changed = true;
            Path next = resolve(key);
            if (next != null) {
                save();
                return next;
            }
        }
        if (changed) save();
        return null;
    }

    public static synchronized void recordPlayed(Path track) {
        String key = key(track);
        HISTORY.remove(key);
        HISTORY.add(0, key);
        while (HISTORY.size() > 100) HISTORY.remove(HISTORY.size() - 1);
        save();
    }

    public static synchronized void removeTrack(Path track) {
        String key = key(track);
        FAVORITES.remove(key);
        QUEUE.removeIf(key::equals);
        HISTORY.removeIf(key::equals);
        PLAYLISTS.values().forEach(tracks -> tracks.removeIf(key::equals));
        METADATA.remove(key);
        save();
    }

    public static synchronized List<Playlist> playlists() {
        List<Playlist> result = new ArrayList<>();
        for (var entry : PLAYLISTS.entrySet()) result.add(new Playlist(entry.getKey(), paths(entry.getValue())));
        return List.copyOf(result);
    }

    public static synchronized boolean createPlaylist(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 48 || PLAYLISTS.containsKey(clean)) return false;
        PLAYLISTS.put(clean, new ArrayList<>());
        save();
        return true;
    }

    public static synchronized void deletePlaylist(String name) {
        if (PLAYLISTS.remove(name) != null) save();
    }

    public static synchronized boolean addToPlaylist(String name, Path track) {
        List<String> tracks = PLAYLISTS.get(name);
        String key = key(track);
        if (tracks != null && MusicLibrary.tracks().contains(track) && !tracks.contains(key)) {
            tracks.add(key);
            save();
            return true;
        }
        return false;
    }

    public static synchronized void removeFromPlaylist(String name, Path track) {
        List<String> tracks = PLAYLISTS.get(name);
        if (tracks != null && tracks.remove(key(track))) save();
    }

    public static synchronized Metadata metadata(Path track) {
        Metadata value = METADATA.get(key(track));
        return value == null ? new Metadata(fileTitle(track), "") : value;
    }

    public static synchronized void setMetadata(Path track, String title, String artist) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanArtist = artist == null ? "" : artist.trim();
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("Judul tidak boleh kosong");
        if (!MusicLibrary.tracks().contains(track)) throw new IllegalArgumentException("Lagu tidak ada di pustaka");
        METADATA.put(key(track), new Metadata(cleanTitle, cleanArtist));
        save();
    }

    private static String fileTitle(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ');
    }

    private static String key(Path path) { return path.toAbsolutePath().normalize().toString(); }

    private static Path resolve(String key) {
        try {
            Path path = Path.of(key).toAbsolutePath().normalize();
            return MusicLibrary.tracks().stream()
                    .filter(track -> track.toAbsolutePath().normalize().equals(path))
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Path> paths(Iterable<String> keys) {
        List<Path> result = new ArrayList<>();
        for (String key : keys) {
            Path path = resolve(key);
            if (path != null) result.add(path);
        }
        return List.copyOf(result);
    }

    private static void readStrings(JsonObject root, String name, Set<String> target) {
        if (root.has(name)) readStrings(root.get(name), target::add);
    }

    private static void readStrings(JsonObject root, String name, List<String> target) {
        if (root.has(name)) readStrings(root.get(name), target::add);
    }

    private static void readStrings(JsonElement element, Consumer<String> target) {
        if (!element.isJsonArray()) return;
        JsonArray array = element.getAsJsonArray();
        for (JsonElement item : array) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                target.accept(item.getAsString());
            }
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static void save() {
        if (dataFile == null) return;
        JsonObject root = new JsonObject();
        root.add("favorites", stringArray(FAVORITES));
        root.add("queue", stringArray(QUEUE));
        root.add("history", stringArray(HISTORY));
        JsonObject playlists = new JsonObject();
        PLAYLISTS.forEach((name, tracks) -> playlists.add(name, stringArray(tracks)));
        root.add("playlists", playlists);
        JsonObject metadata = new JsonObject();
        METADATA.forEach((track, value) -> {
            JsonObject item = new JsonObject();
            item.addProperty("title", value.title());
            item.addProperty("artist", value.artist());
            metadata.add(track, item);
        });
        root.add("metadata", metadata);
        Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(dataFile.getParent());
            Files.writeString(temporary, GSON.toJson(root));
            try {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[XYOPlay] Gagal menyimpan data pustaka: " + e.getMessage());
        }
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }
}
