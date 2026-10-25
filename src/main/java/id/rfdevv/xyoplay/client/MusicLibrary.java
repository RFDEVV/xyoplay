package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

public final class MusicLibrary {
    private static final List<Path> TRACKS = new CopyOnWriteArrayList<>();
    private static Path folder;

    private MusicLibrary() {}

    public static void initialize() {
        folder = Minecraft.getInstance().gameDirectory.toPath().resolve("xyoplay").resolve("music");
        try { Files.createDirectories(folder); refresh(); }
        catch (IOException e) { System.err.println("[XYOPlay] Tidak dapat membuat folder musik: " + e.getMessage()); }
        MusicLibraryData.load();
    }

    public static synchronized void refresh() {
        if (folder == null) return;
        try (Stream<Path> files = Files.list(folder)) {
            TRACKS.clear();
            files.filter(Files::isRegularFile)
                    .filter(p -> { String n = p.getFileName().toString().toLowerCase(Locale.ROOT); return n.endsWith(".ogg") || n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".m4a"); })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(TRACKS::add);
        } catch (IOException e) { System.err.println("[XYOPlay] Gagal membaca pustaka musik: " + e.getMessage()); }
    }

    public static List<Path> tracks() { return List.copyOf(TRACKS); }
    public static Path folder() { return folder; }
    public static synchronized void delete(Path track) throws IOException {
        if (folder == null || track == null || !TRACKS.contains(track)) {
            throw new IllegalArgumentException("Lagu tidak ada di pustaka");
        }
        Path normalizedFolder = folder.toAbsolutePath().normalize();
        Path normalizedTrack = track.toAbsolutePath().normalize();
        if (!normalizedFolder.equals(normalizedTrack.getParent()) || !Files.isRegularFile(normalizedTrack)) {
            throw new IllegalArgumentException("File bukan lagu yang terdaftar di pustaka");
        }
        Path current = LocalAudioPlayer.currentPath();
        if (current != null && normalizedTrack.equals(current.toAbsolutePath().normalize())) LocalAudioPlayer.stop();
        Files.delete(normalizedTrack);
        MusicLibraryData.removeTrack(normalizedTrack);
        refresh();
    }

    public static String title(Path path) {
        return MusicLibraryData.metadata(path).title();
    }
}
