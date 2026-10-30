package id.rfdevv.xyoplay.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;

/**
 * Sound effects for LIVE reactions. Each reaction has a folder in config/xyoplay/sounds/&lt;kind&gt;/; the mod fills it with
 * the bundled defaults the first time, and anything you drop in (wav, mp3, ogg, m4a) is picked at random. MP3/OGG are
 * converted once with FFmpeg and cached.
 */
final class MemeSounds {
    record Kind(String id, String label, String when) {}

    static final List<Kind> KINDS = List.of(
            new Kind("follow", "Follow", "penonton follow"),
            new Kind("gift", "Gift kecil", "gift di bawah 10 koin"),
            new Kind("biggift", "Gift besar", "gift 10–98 koin"),
            new Kind("mega", "Gift mega", "gift 99 koin ke atas"),
            new Kind("tap", "Tap event", "event acak dari tap tap"),
            new Kind("goal", "Target follow", "target follow tercapai"),
            new Kind("share", "Share", "penonton membagikan LIVE"),
            new Kind("winner", "Pemenang undian", "hasil undian"));

    private static final String[] BUNDLED = {
        "follow_1", "follow_2", "follow_3", "gift_1", "gift_2", "gift_3", "biggift_1", "biggift_2", "biggift_3",
        "tap_1", "tap_2", "tap_3", "tap_4", "goal_1", "winner_1", "winner_2", "mega_1", "mega_2", "share_1", "share_2"};

    /** Tests: receives "kind" instead of playing audio. */
    static volatile Consumer<String> testSink;

    private static final Random RNG = new Random();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static volatile boolean extracted;

    private MemeSounds() {}

    static Path root() {
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay").resolve("sounds");
    }

    static Path folder(String kind) { return root().resolve(kind); }

    /** Copies the bundled defaults into the per-reaction folders (only folders that do not exist yet). */
    static synchronized void installDefaults() {
        if (extracted) return;
        extracted = true;
        try {
            for (Kind k : KINDS) Files.createDirectories(folder(k.id()));
            for (String name : BUNDLED) {
                String kind = name.substring(0, name.indexOf('_'));
                Path dir = folder(kind);
                Path marker = dir.resolve(".defaults");
                if (Files.exists(marker)) continue;
                try (InputStream in = MemeSounds.class.getResourceAsStream("/assets/xyoplay/sfx/" + name + ".wav")) {
                    if (in != null) Files.copy(in, dir.resolve(name + ".wav"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            for (Kind k : KINDS) Files.writeString(folder(k.id()).resolve(".defaults"), "defaults installed");
        } catch (IOException | RuntimeException e) {
            System.err.println("[XYOPlay] Gagal memasang suara bawaan: " + e.getMessage());
        }
    }

    static List<Path> files(String kind) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder(kind))) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return !n.startsWith(".") && (n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".m4a") || n.endsWith(".flac"));
            }).sorted().forEach(out::add);
        } catch (IOException | RuntimeException ignored) { }
        return out;
    }

    static String kindForGift(int unitCoins) {
        return unitCoins >= 99 ? "mega" : unitCoins >= 10 ? "biggift" : "gift";
    }

    /** Plays one random sound of this reaction. Never throws, never blocks the game thread. */
    static void play(String kind) {
        if (!ClientSettings.memeSounds) return;
        Consumer<String> sink = testSink;
        if (sink != null) { sink.accept(kind); return; }
        long now = System.currentTimeMillis();
        Long last = LAST.get(kind);
        if (last != null && now - last < 350) return;
        if (ACTIVE.get() >= 3) return;
        LAST.put(kind, now);
        Thread t = new Thread(() -> {
            ACTIVE.incrementAndGet();
            try { playFile(pick(kind)); }
            catch (Throwable e) { /* no audio device or unsupported file: the game keeps going */ }
            finally { ACTIVE.decrementAndGet(); }
        }, "XYOPlay-SFX");
        t.setDaemon(true);
        t.start();
    }

    private static Path pick(String kind) {
        installDefaults();
        List<Path> f = files(kind);
        return f.isEmpty() ? null : f.get(RNG.nextInt(f.size()));
    }

    private static Path asWav(Path p) throws IOException, InterruptedException {
        if (p.getFileName().toString().toLowerCase().endsWith(".wav")) return p;
        Path cache = root().resolve(".cache");
        Files.createDirectories(cache);
        Path out = cache.resolve(Integer.toHexString((p.toAbsolutePath() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis()).hashCode()) + ".wav");
        if (Files.exists(out)) return out;
        Process pr = new ProcessBuilder(LocalAudioPlayer.ffmpegExe(), "-y", "-v", "error", "-i", p.toString(), "-t", "8", "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", out.toString())
                .redirectErrorStream(true).start();
        pr.getInputStream().readAllBytes();
        if (!pr.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
        return Files.exists(out) ? out : null;
    }

    private static void playFile(Path p) throws Exception {
        if (p == null) return;
        Path wav = asWav(p);
        if (wav == null) return;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl c = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float v = Math.max(1, Math.min(100, ClientSettings.memeVolume)) / 100f;
                float db = (float) (20 * Math.log10(v));
                c.setValue(Math.max(c.getMinimum(), Math.min(c.getMaximum(), db)));
            }
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            clip.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) done.countDown(); });
            clip.start();
            done.await(10, java.util.concurrent.TimeUnit.SECONDS);
            clip.close();
        }
    }
}
