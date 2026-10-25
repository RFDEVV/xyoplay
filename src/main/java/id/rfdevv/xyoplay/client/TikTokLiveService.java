package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional local TikTok LIVE reader. A small Python process (TikTokLive library) connects to the stream and prints every
 * comment as JSON; this class reads it, keeps the comment list, and reports readable status messages.
 * Chat is spoken locally by the script; nothing is sent to the Minecraft server. Log: config/xyoplay/tiktok.log
 */
public final class TikTokLiveService {
    public record Comment(String user, String text) {}

    private static final List<Comment> COMMENTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean installing = new AtomicBoolean(false);
    private static final AtomicInteger generation = new AtomicInteger();
    private static volatile Process process;
    private static volatile String status = "Masukkan username TikTok di tab LIVE";
    private static volatile int total;

    private TikTokLiveService() {}

    public static boolean isRunning() { return running.get(); }
    public static boolean isInstalling() { return installing.get(); }
    public static String status() { return status; }
    public static int totalComments() { return total; }

    /** Newest comment first. */
    public static List<Comment> comments() {
        List<Comment> copy = new ArrayList<>(COMMENTS);
        Collections.reverse(copy);
        return copy;
    }

    public static List<String> pythonCandidates() {
        String custom = ClientSettings.pythonPath;
        if (custom != null && !custom.isBlank()) return List.of(custom);
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("py", "python", "python3") : List.of("python3", "python");
    }

    // ------------------------------------------------------------------ start / stop

    public static void start(String username) {
        String clean = username == null ? "" : username.trim().replaceFirst("^@", "");
        if (clean.isBlank()) { status = "Masukkan username TikTok tanpa atau dengan @"; return; }
        if (!clean.matches("[A-Za-z0-9_.]{2,40}")) { status = "Username hanya boleh huruf, angka, titik, dan garis bawah"; return; }
        if (!running.compareAndSet(false, true)) { status = "Pembaca LIVE sudah berjalan"; return; }
        int myGeneration = generation.incrementAndGet();
        status = "Menyiapkan pembaca LIVE @" + clean + "…";
        COMMENTS.clear(); total = 0;
        Thread launcher = new Thread(() -> launch(clean, myGeneration), "XYOPlay-TikTok-Launch");
        launcher.setDaemon(true); launcher.start();
    }

    private static void launch(String clean, int myGeneration) {
        Process p = null;
        try {
            String python = findPython();
            if (python == null) {
                status = "Python 3.10+ tidak ditemukan. Pasang dari python.org (centang 'Add to PATH') atau isi pythonPath di config/xyoplay.properties";
                return;
            }
            Path script = scriptPath();
            Files.createDirectories(script.getParent());
            try (var in = TikTokLiveService.class.getResourceAsStream("/assets/xyoplay/tools/tiktok_reader.py")) {
                if (in == null) throw new IllegalStateException("Script pembaca TikTok tidak ada di JAR");
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder pb = new ProcessBuilder(python, "-u", script.toString(), clean,
                    ClientSettings.ttsEnabled ? "tts" : "notts",
                    String.valueOf(ClientSettings.ttsVolume),
                    voiceKey(ClientSettings.ttsVoice),
                    langKey(ClientSettings.ttsLanguage));
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("XYOPLAY_TTS_VOL", String.valueOf(ClientSettings.ttsVolume));
            pb.environment().put("XYOPLAY_TTS_VOICE", voiceKey(ClientSettings.ttsVoice));
            pb.environment().put("XYOPLAY_TTS_LANG", langKey(ClientSettings.ttsLanguage));
            String key = ClientSettings.eulerApiKey;
            if (key != null && !key.isBlank()) pb.environment().put("XYOPLAY_EULER_KEY", key.trim());
            pb.redirectErrorStream(true);
            p = pb.start();
            if (generation.get() != myGeneration) { p.destroyForcibly(); return; }
            process = p;
            status = "Menyambungkan ke LIVE @" + clean + "…";
            readOutput(p, myGeneration);
        } catch (Exception e) {
            status = "Gagal menjalankan pembaca LIVE: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log("launch error: " + e);
        } finally {
            if (generation.get() == myGeneration) { if (process == p) process = null; running.set(false); }
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static void readOutput(Process own, int myGeneration) throws IOException, InterruptedException {
        String lastLine = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(own.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (generation.get() != myGeneration) return;
                log(line);
                try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    String type = json.has("type") ? json.get("type").getAsString() : "";
                    String text = json.has("text") ? json.get("text").getAsString() : "";
                    switch (type) {
                        case "status", "missing" -> { status = text.isEmpty() ? line : text; lastLine = status; }
                        case "comment" -> {
                            String user = json.has("user") ? json.get("user").getAsString() : "penonton";
                            COMMENTS.add(new Comment(user, text));
                            while (COMMENTS.size() > 500) COMMENTS.remove(0);
                            total++;
                            status = "Komentar diterima: " + total;
                        }
                        case "follow" -> {
                            String user = json.has("user") ? json.get("user").getAsString() : "penonton";
                            COMMENTS.add(new Comment(user, text.isEmpty() ? "[FOLLOW] Sudah mem-follow!" : text));
                            while (COMMENTS.size() > 500) COMMENTS.remove(0);
                            total++;
                            status = "Follow baru: @" + user;
                        }
                        case "gift" -> {
                            String user = json.has("user") ? json.get("user").getAsString() : "penonton";
                            String gift = json.has("gift") ? json.get("gift").getAsString() : "gift";
                            int count = json.has("count") ? json.get("count").getAsInt() : 1;
                            COMMENTS.add(new Comment(user, text.isEmpty() ? "[GIFT] Mengirim " + count + "x " + gift : text));
                            while (COMMENTS.size() > 500) COMMENTS.remove(0);
                            total++;
                            status = "Gift baru: " + count + "x " + gift + " dari @" + user;
                        }
                        default -> { }
                    }
                } catch (RuntimeException notJson) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty()) { status = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed; lastLine = status; }
                }
            }
        }
        int exit = own.waitFor();
        if (generation.get() == myGeneration && exit != 0 && lastLine.isBlank()) {
            status = "Pembaca LIVE berhenti (kode " + exit + "). Lihat config/xyoplay/tiktok.log";
        } else if (generation.get() == myGeneration && exit == 0 && lastLine.isBlank()) status = "Pembaca LIVE berhenti";
    }

    public static String voiceKey(int index) {
        if (index >= 0 && index < ClientSettings.TTS_VOICE_KEYS.length) return ClientSettings.TTS_VOICE_KEYS[index];
        return "google";
    }

    public static String langKey(int index) {
        if (index >= 0 && index < ClientSettings.TTS_LANG_CODES.length) return ClientSettings.TTS_LANG_CODES[index];
        return "id";
    }

    public static void sendConfig() {
        Process p = process;
        if (p == null || !p.isAlive()) return;
        try {
            var out = p.getOutputStream();
            String json = String.format(java.util.Locale.ROOT,
                    "{\"cmd\":\"config\",\"tts\":%b,\"volume\":%d,\"voice\":\"%s\",\"lang\":\"%s\"}\n",
                    ClientSettings.ttsEnabled, ClientSettings.ttsVolume,
                    voiceKey(ClientSettings.ttsVoice), langKey(ClientSettings.ttsLanguage));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {}
    }

    public static void clearComments() {
        COMMENTS.clear();
        total = 0;
        status = "Histori komentar dibersihkan";
        Process p = process;
        if (p != null && p.isAlive()) {
            try {
                var out = p.getOutputStream();
                out.write("{\"cmd\":\"clear\"}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {}
        }
    }

    public static void stop() {
        generation.incrementAndGet();
        Process p = process; process = null; running.set(false);
        if (p != null) {
            try { p.toHandle().descendants().forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }
        status = "Pembaca TikTok LIVE dimatikan";
    }

    // ------------------------------------------------------------------ pip install

    /** Installs TikTokLive and pyttsx3 into the Python used by the reader (tries plain, --user, then --break-system-packages). */
    public static void installPackages() {
        if (!installing.compareAndSet(false, true)) { status = "Instalasi paket sedang berjalan…"; return; }
        status = "Menyiapkan instalasi paket Python…";
        Thread worker = new Thread(() -> {
            try {
                String python = findPython();
                if (python == null) { status = "Python 3.10+ tidak ditemukan. Pasang dari python.org (centang 'Add to PATH')"; return; }
                String[][] attempts = {
                        {"-m", "pip", "install", "--upgrade", "TikTokLive", "pyttsx3"},
                        {"-m", "pip", "install", "--upgrade", "--user", "TikTokLive", "pyttsx3"},
                        {"-m", "pip", "install", "--upgrade", "--break-system-packages", "TikTokLive", "pyttsx3"}};
                String lastOutput = "";
                for (String[] args : attempts) {
                    status = "Memasang TikTokLive & pyttsx3 lewat pip… (bisa 1-2 menit)";
                    List<String> cmd = new ArrayList<>(); cmd.add(python); cmd.addAll(List.of(args));
                    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    Thread dog = new Thread(() -> { try { if (!p.waitFor(600, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) {} });
                    dog.setDaemon(true); dog.start();
                    String last = "";
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) { log("pip: " + line); if (!line.isBlank()) last = line.strip(); }
                    }
                    if (p.waitFor() == 0) { status = "Paket terpasang. Klik Mulai LIVE."; return; }
                    lastOutput = last;
                }
                status = "Instalasi gagal: " + (lastOutput.length() > 120 ? lastOutput.substring(0, 120) : lastOutput);
            } catch (Exception e) {
                status = "Instalasi gagal: " + e.getMessage();
            } finally { installing.set(false); }
        }, "XYOPlay-Pip");
        worker.setDaemon(true); worker.start();
    }

    // ---------------------------------------------------------------------- helpers

    /** First candidate that really is Python 3 (skips the Windows Store stub that prints a message and exits with an error). */
    static String findPython() {
        for (String candidate : pythonCandidates()) {
            Process p = null;
            try {
                p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                String first;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) { first = r.readLine(); }
                if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); continue; }
                if (p.exitValue() == 0 && first != null && first.startsWith("Python 3")) return candidate;
            } catch (Exception ignored) {
            } finally { if (p != null && p.isAlive()) p.destroyForcibly(); }
        }
        return null;
    }

    private static Path scriptPath() { return configDir().resolve("tiktok_reader.py"); }
    private static Path configDir() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay"); }

    private static synchronized void log(String line) {
        try {
            Path file = configDir().resolve("tiktok.log");
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > 512_000) Files.delete(file);
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }
}
