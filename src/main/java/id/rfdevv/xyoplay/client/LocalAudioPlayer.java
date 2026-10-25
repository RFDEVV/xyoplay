package id.rfdevv.xyoplay.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Decodes common audio formats through a local FFmpeg process and sends PCM to the local audio device. */
public final class LocalAudioPlayer {
    private static volatile Process process;
    private static volatile SourceDataLine line;
    private static volatile Path current;
    private static volatile boolean paused;
    private static volatile boolean running;
    private static volatile boolean enabled = true;
    private static volatile double durationSeconds = -1;
    private static volatile double startOffset = 0;
    private static volatile boolean completed = false;
    private static final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private static final AtomicLong generation = new AtomicLong(0);

    private LocalAudioPlayer() {}
    public static void initialize() {}

    public static boolean isEnabled() { return enabled; }
    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) { stop(); YoutubeService.cancelActive(); }
    }

    public static String ffmpegExe() {
        String p = ClientSettings.ffmpegPath;
        return p == null || p.isBlank() ? "ffmpeg" : p;
    }
    public static String ffprobeExe() {
        String p = ffmpegExe();
        String lower = p.toLowerCase(Locale.ROOT);
        int idx = lower.lastIndexOf("ffmpeg");
        if (idx >= 0 && (lower.endsWith("ffmpeg") || lower.endsWith("ffmpeg.exe"))) return p.substring(0, idx) + "ffprobe" + p.substring(idx + 6);
        return "ffprobe";
    }

    public static void play(Path file) { play(file, 0); }

    public static synchronized void play(Path file, double startSeconds) {
        if (!enabled) return;
        double keepDuration = file.equals(current) ? durationSeconds : -1;
        stop();
        long id = generation.incrementAndGet();
        current = file; paused = false; running = true; completed = false;
        durationSeconds = keepDuration; startOffset = Math.max(0, startSeconds);
        stopRequested.set(false);
        if (startSeconds <= 0.05) MusicLibraryData.recordPlayed(file); // seeking must not reorder the history
        double start = startOffset;
        Thread worker = new Thread(() -> decode(file, id, start, keepDuration), "XYOPlay-Audio");
        worker.setDaemon(true); worker.start();
    }

    /** Jumps to a position in the current track (restarts decoding from that offset). */
    public static void seek(double seconds) {
        Path p = current;
        double d = durationSeconds;
        if (p == null || !running) return;
        double target = Math.max(0, d > 0 ? Math.min(seconds, d - 0.5) : seconds);
        play(p, target);
    }

    private static void decode(Path file, long id, double start, double knownDuration) {
        if (!enabled) { running = false; return; }
        Process ownProcess = null;
        SourceDataLine ownLine = null;
        try {
            double d = knownDuration > 0 ? knownDuration : probeDuration(file);
            if (generation.get() == id) durationSeconds = d;
            java.util.ArrayList<String> cmd = new java.util.ArrayList<>(java.util.List.of(ffmpegExe(), "-nostdin", "-v", "error"));
            if (start > 0.05) { cmd.add("-ss"); cmd.add(String.format(Locale.ROOT, "%.3f", start)); }
            cmd.addAll(java.util.List.of("-i", file.toAbsolutePath().toString(), "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", "pipe:1"));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            ownProcess = pb.start();
            if (generation.get() != id || !enabled) { ownProcess.destroyForcibly(); return; }
            process = ownProcess;
            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            ownLine = (SourceDataLine) AudioSystem.getLine(info);
            ownLine.open(format, 65536); line = ownLine; applyVolume(); ownLine.start();
            try (InputStream in = new BufferedInputStream(ownProcess.getInputStream())) {
                byte[] buffer = new byte[8192]; int count;
                while (generation.get() == id && !stopRequested.get() && (count = in.read(buffer)) >= 0) {
                    while (paused && generation.get() == id && !stopRequested.get()) Thread.sleep(40);
                    if (generation.get() != id || stopRequested.get()) break;
                    if (count > 0) ownLine.write(buffer, 0, count);
                }
            }
            if (generation.get() == id && !stopRequested.get()) ownProcess.waitFor();
        } catch (Exception e) {
            if (generation.get() == id) System.err.println("[XYOPlay] Gagal memutar audio lokal. Pastikan FFmpeg tersedia dan perangkat audio aktif: " + e.getMessage());
        } finally {
            try { if (ownLine != null) { ownLine.stop(); ownLine.flush(); ownLine.close(); } } catch (Exception ignored) {}
            if (line == ownLine) line = null;
            if (process == ownProcess) process = null;
            if (ownProcess != null && ownProcess.isAlive()) ownProcess.destroyForcibly();
            if (generation.get() == id) {
                if (!stopRequested.get() && ownProcess != null) completed = true;
                running = false; paused = false;
            }
        }
    }

    public static synchronized void togglePause() {
        if (!enabled || !running) return;
        paused = !paused;
        SourceDataLine l = line;
        if (l != null) { if (paused) l.stop(); else l.start(); }
    }

    public static synchronized void stop() {
        generation.incrementAndGet();
        stopRequested.set(true); paused = false;
        Process p = process; if (p != null) p.destroyForcibly();
        SourceDataLine l = line; if (l != null) { try { l.stop(); l.flush(); l.close(); } catch (Exception ignored) {} }
        running = false; current = null; completed = false; durationSeconds = -1; startOffset = 0;
    }

    public static boolean isPlayingOrPaused() { return running && current != null; }
    public static boolean isPaused() { return paused; }
    public static String currentTitle() { Path p = current; return p == null ? "Tidak ada lagu" : MusicLibrary.title(p); }
    public static Path currentPath() { return current; }
    public static double durationSeconds() { return durationSeconds; }
    public static double positionSeconds() {
        SourceDataLine l = line;
        double base = startOffset;
        if (l == null) return base;
        double pos = base + Math.max(0, l.getLongFramePosition() / 44100.0);
        double d = durationSeconds;
        return d > 0 ? Math.min(pos, d) : pos;
    }
    public static boolean takeCompletion() {
        if (!completed) return false;
        completed = false;
        return true;
    }
    private static double probeDuration(Path file) {
        Process probe = null;
        try {
            probe = new ProcessBuilder(ffprobeExe(), "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", file.toAbsolutePath().toString()).redirectErrorStream(true).start();
            String text;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) { text = r.readLine(); }
            if (probe.waitFor() == 0 && text != null) return Double.parseDouble(text.trim());
        } catch (Exception ignored) { }
        finally { if (probe != null && probe.isAlive()) probe.destroyForcibly(); }
        return -1;
    }
    public static void setVolume(float volume) { ClientSettings.volume = Math.max(0f, Math.min(1f, volume)); applyVolume(); }
    private static void applyVolume() {
        SourceDataLine l = line;
        if (l == null || !l.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) return;
        javax.sound.sampled.FloatControl gain = (javax.sound.sampled.FloatControl) l.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
        float v = ClientSettings.volume;
        float db = v <= 0.001f ? gain.getMinimum() : (float) (20.0 * Math.log10(v));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
    }
}
