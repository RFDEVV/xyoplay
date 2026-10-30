package id.rfdevv.xyoplay.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    /** kind: comment, follow, gift, share, subscribe, join. uid is the TikTok @username (lower case) used for banning. */
    public record Comment(String user, String uid, String text, String kind) {
        public Comment(String user, String text) { this(user, "", text, "comment"); }
    }
    public record Gifter(String user, int diamonds, int gifts) {}

    private static final List<Comment> COMMENTS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, int[]> GIFTERS = new ConcurrentHashMap<>(); // user -> {diamonds, gifts}
    private static final ConcurrentHashMap<String, Integer> SEEN_GIFTS = new ConcurrentHashMap<>(); // gift name -> times
    private static final ConcurrentHashMap<String, Integer> SEEN_GIFT_IDS = new ConcurrentHashMap<>(); // gift name -> TikTok gift id
    private static final ConcurrentLinkedQueue<String[]> PENDING_SPEECH = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean ttsStarting = new AtomicBoolean(false);
    private static volatile boolean liveMode;
    private static volatile int followCount, giftCount, shareCount, diamondTotal, likeEvents;
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
    public static int followCount() { return followCount; }
    public static int giftCount() { return giftCount; }
    public static int likeEvents() { return likeEvents; }
    public static int shareCount() { return shareCount; }
    public static int diamondTotal() { return diamondTotal; }

    public static List<Gifter> topGifters(int max) {
        List<Gifter> list = new ArrayList<>();
        GIFTERS.forEach((user, v) -> list.add(new Gifter(user, v[0], v[1])));
        list.sort((a, b) -> Integer.compare(b.diamonds(), a.diamonds()));
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    /** Gift names seen in this LIVE, most frequent first (handy for writing triggers with the exact name). */
    public static List<String> seenGifts() {
        List<String> names = new ArrayList<>(SEEN_GIFTS.keySet());
        names.sort((a, b) -> Integer.compare(SEEN_GIFTS.getOrDefault(b, 0), SEEN_GIFTS.getOrDefault(a, 0)));
        return names;
    }

    /** TikTok gift id of a gift seen in this LIVE (0 = unknown); lets the user write "id:5655=..." triggers. */
    public static int seenGiftId(String name) { return SEEN_GIFT_IDS.getOrDefault(name, 0); }

    public static void resetStats() {
        LiveGoal.reset();
        followCount = 0; giftCount = 0; shareCount = 0; diamondTotal = 0; likeEvents = 0;
        LiveActions.resetLikes();
        GIFTERS.clear(); SEEN_GIFTS.clear(); SEEN_GIFT_IDS.clear();
    }

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
        stopProcessOnly();   // a TTS-only helper may be running; the LIVE reader takes over its job
        int myGeneration = generation.incrementAndGet();
        status = "Menyiapkan pembaca LIVE @" + clean + "…";
        COMMENTS.clear(); total = 0; resetStats();
        LiveFilter.ensureLoaded();
        Thread launcher = new Thread(() -> launch(clean, myGeneration), "XYOPlay-TikTok-Launch");
        launcher.setDaemon(true); launcher.start();
    }

    /** @param clean TikTok username, or null for the TTS-only helper (chat -> voice). */
    private static void launch(String clean, int myGeneration) {
        Process p = null;
        boolean live = clean != null;
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
            List<String> cmd = new ArrayList<>(List.of(python, "-u", script.toString()));
            if (live) {
                cmd.add(clean);
                cmd.add(ClientSettings.ttsEnabled ? "tts" : "notts");
                cmd.add(String.valueOf(ClientSettings.ttsVolume));
                cmd.add(ClientSettings.ttsVoiceKey);
                cmd.add(langKey(ClientSettings.ttsLanguage));
            } else cmd.add("__tts__");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("XYOPLAY_FFMPEG", LocalAudioPlayer.ffmpegExe());
            pb.environment().put("XYOPLAY_CFG", configJson(true).toString());
            String key = ClientSettings.eulerApiKey;
            if (key != null && !key.isBlank()) pb.environment().put("XYOPLAY_EULER_KEY", key.trim());
            pb.redirectErrorStream(true);
            p = pb.start();
            if (generation.get() != myGeneration) { p.destroyForcibly(); return; }
            process = p;
            liveMode = live;
            status = live ? "Menyambungkan ke LIVE @" + clean + "…" : "TTS siap";
            flushPendingSpeech();
            readOutput(p, myGeneration);
        } catch (Exception e) {
            status = "Gagal menjalankan pembaca LIVE: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log("launch error: " + e);
        } finally {
            if (generation.get() == myGeneration) {
                if (process == p) process = null;
                if (live) running.set(false); else ttsStarting.set(false);
            }
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static String str(com.google.gson.JsonObject json, String key, String fallback) {
        try { return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback; } catch (RuntimeException e) { return fallback; }
    }

    private static int num(com.google.gson.JsonObject json, String key, int fallback) {
        try { return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback; } catch (RuntimeException e) { return fallback; }
    }

    private static void addComment(Comment c) {
        COMMENTS.add(c);
        while (COMMENTS.size() > 500) COMMENTS.remove(0);
        total++;
    }

    /** Handles one event from the reader (or from the simulator). @return true when it was a status message. */
    private static boolean handleEvent(String type, com.google.gson.JsonObject json) {
        String text = str(json, "text", "");
        String user = str(json, "user", "penonton");
        String uid = str(json, "uid", "");
        if (type.equals("avatar")) { Avatars.put(uid, str(json, "px", "")); return false; }
        if (!type.equals("status") && !type.equals("missing") && !type.equals("spoke") && LiveFilter.isBanned(uid, user)) return false;
        String eid = str(json, "eid", "");
        switch (type) {
            case "status", "missing" -> { status = text.isEmpty() ? json.toString() : text; return true; }
            case "comment" -> {
                addComment(new Comment(user, uid, text, "comment"));
                status = "Komentar diterima: " + total;
                if (Giveaway.onComment(user, uid, text)) LiveBanner.push(user + " ikut undian!", 0xFFC084FC, uid);
                LiveActions.onEvent(new LiveActions.Event("comment", user, uid, text, "", 1, 0, 0, eid));
            }
            case "followers" -> { LiveGoal.setTotal(num(json, "count", -1)); return false; }
            case "follow" -> {
                followCount++;
                addComment(new Comment(user, uid, text.isEmpty() ? "[FOLLOW] Sudah mem-follow!" : text, "follow"));
                status = "Follow baru: @" + user;
                LiveBanner.push(user + " follow!", 0xFF4ADE80, uid);
                MemeSounds.play("follow");
                LiveActions.onEvent(new LiveActions.Event("follow", user, uid, text, "", 1, 0, 0, eid));
                LiveGoal.onFollow(followCount);
            }
            case "share" -> {
                shareCount++;
                addComment(new Comment(user, uid, text.isEmpty() ? "[SHARE] Membagikan LIVE!" : text, "share"));
                status = "Share dari @" + user;
                LiveBanner.push(user + " share LIVE", 0xFF38BDF8, uid);
                MemeSounds.play("share");
                LiveActions.onEvent(new LiveActions.Event("share", user, uid, text, "", 1, 0, 0, eid));
            }
            case "subscribe" -> {
                addComment(new Comment(user, uid, text.isEmpty() ? "[SUBSCRIBE] Berlangganan!" : text, "subscribe"));
                LiveBanner.push(user + " berlangganan!", 0xFFFACC15, uid);
                LiveActions.onEvent(new LiveActions.Event("subscribe", user, uid, text, "", 1, 0, 0, eid));
            }
            case "like" -> {
                int count = Math.max(1, num(json, "count", 1));
                likeEvents += count;
                LiveActions.onLikes(user, uid, count, num(json, "total", 0));
                status = "Like: " + LiveActions.likeCount();
            }
            case "join" -> {
                addComment(new Comment(user, uid, text.isEmpty() ? "[JOIN] Masuk ke LIVE" : text, "join"));
                LiveActions.onEvent(new LiveActions.Event("join", user, uid, text, "", 1, 0, 0, eid));
            }
            case "gift" -> {
                String gift = str(json, "gift", "gift");
                int count = Math.max(0, num(json, "count", 1));          // new pieces in this event (combo delta)
                int total = Math.max(count, num(json, "total", count));  // pieces so far in the combo
                boolean fin = !json.has("final") || json.get("final").getAsBoolean();
                int diamonds = Math.max(0, num(json, "diamonds", 0));
                int giftId = Math.max(0, num(json, "gift_id", 0));
                giftCount += count; diamondTotal += diamonds;
                if (count > 0) {
                    GIFTERS.merge(user, new int[] {diamonds, count}, (old, add) -> new int[] {old[0] + add[0], old[1] + add[1]});
                    SEEN_GIFTS.merge(gift, count, Integer::sum);
                    if (giftId > 0) SEEN_GIFT_IDS.put(gift, giftId);
                    GiftBook.learn(gift, giftId, diamonds / count);
                    int unitPrice = diamonds > 0 ? diamonds / count : Math.max(0, GiftBook.priceOf(gift));
                    MemeSounds.play(MemeSounds.kindForGift(unitPrice));
                    // the game reacts at once to every piece, even while the combo is still running
                    LiveActions.onEvent(new LiveActions.Event("gift", user, uid, text, gift, count, diamonds, giftId, eid));
                }
                if (fin) {
                    addComment(new Comment(user, uid, text.isEmpty() ? "[GIFT] Mengirim " + total + "x " + gift : text, "gift"));
                    status = "Gift baru: " + total + "x " + gift + " dari @" + user;
                    LiveBanner.push(user + " kirim " + (total > 1 ? total + "x " : "") + gift, 0xFFFFD700, uid);
                }
            }
            default -> { }
        }
        return false;
    }

    /**
     * Fakes a LIVE event so triggers, banners and voice can be tested without being live (/xyoplay sim … or the
     * "Uji" buttons). The voice thanks the viewer the same way the real reader does.
     */
    public static void simulate(String kind, String user, String gift, int count, int diamonds) {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", kind);
        json.addProperty("user", user);
        json.addProperty("uid", user.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
        json.addProperty("gift", gift);
        json.addProperty("count", count);
        json.addProperty("diamonds", (long) diamonds * Math.max(1, count));   // "diamonds" given here is the price of one piece
        json.addProperty("total", count);
        json.addProperty("final", true);
        if (kind.equals("like")) { json.addProperty("count", ClientSettings.likeStep); json.addProperty("total", LiveActions.likeCount() + ClientSettings.likeStep); }
        json.addProperty("gift_id", GiftBook.idOf(gift));
        if (kind.equals("comment")) json.addProperty("text", gift);
        handleEvent(kind, json);
        switch (kind) {
            case "follow" -> { if (ClientSettings.ttsReadFollow) speak("Terima kasih " + user + " sudah follow", 2); }
            case "share" -> { if (ClientSettings.ttsReadShare) speak("Terima kasih " + user + " sudah share", 2); }
            case "gift" -> { if (ClientSettings.ttsReadGift) speak("Terima kasih " + user + " sudah kirim " + (count > 1 ? count + " " : "") + gift, 2); }
            case "comment" -> { if (ClientSettings.ttsEnabled) speak(user + " bilang, " + gift, 5); }
            default -> { }
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
                    String type = str(json, "type", "");
                    if (handleEvent(type, json)) lastLine = status;
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

    // ------------------------------------------------------------------ config sync

    public static String voiceKey(int index) { return ClientSettings.ttsVoiceKey; }

    public static String langKey(int index) {
        if (index >= 0 && index < ClientSettings.TTS_LANG_CODES.length) return ClientSettings.TTS_LANG_CODES[index];
        return "id";
    }

    /** All TTS settings in the format the Python reader understands. */
    static JsonObject configJson(boolean withLists) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "config");
        o.addProperty("tts", ClientSettings.ttsEnabled);
        o.addProperty("volume", ClientSettings.ttsVolume);
        o.addProperty("voice", ClientSettings.ttsVoiceKey);
        o.addProperty("lang", langKey(ClientSettings.ttsLanguage));
        o.addProperty("rate", ClientSettings.ttsRate);
        o.addProperty("pitch", ClientSettings.ttsPitch);
        o.addProperty("self_voice", ClientSettings.chatVoiceKey);
        o.addProperty("self_volume", ClientSettings.chatVolume);
        o.addProperty("self_rate", ClientSettings.chatRate);
        o.addProperty("self_pitch", ClientSettings.chatPitch);
        com.google.gson.JsonArray words = new com.google.gson.JsonArray();
        for (String w : LiveActions.commandWords()) words.add(w);
        o.add("cmd_words", words);
        o.addProperty("read_names", ClientSettings.ttsReadNames);
        o.addProperty("read_follow", ClientSettings.ttsReadFollow);
        o.addProperty("read_gift", ClientSettings.ttsReadGift);
        o.addProperty("read_share", ClientSettings.ttsReadShare);
        o.addProperty("read_join", ClientSettings.ttsReadJoin);
        o.addProperty("emit_join", LiveActions.hasTrigger("join"));
        o.addProperty("filter_on", ClientSettings.filterEnabled);
        o.addProperty("skip_badword_comments", ClientSettings.filterSkipComment);
        if (withLists) {
            o.add("badwords", ClientSettings.filterEnabled ? LiveFilter.wordsJson() : new JsonArray());
            o.add("banned", LiveFilter.bannedJson());
        }
        return o;
    }

    private static synchronized boolean writeLine(String line) {
        Process p = process;
        if (p == null || !p.isAlive()) return false;
        try {
            var out = p.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) { return false; }
    }

    public static void sendConfig() {
        JsonObject o = configJson(false);
        writeLine(o.toString());
        syncFilter();
    }

    /** Pushes the badword list and the banned accounts to the running reader. */
    static void syncFilter() {
        JsonObject words = new JsonObject();
        words.addProperty("cmd", "set_badwords");
        words.add("words", ClientSettings.filterEnabled ? LiveFilter.wordsJson() : new JsonArray());
        writeLine(words.toString());
        JsonObject banned = new JsonObject();
        banned.addProperty("cmd", "set_banned");
        banned.add("users", LiveFilter.bannedJson());
        writeLine(banned.toString());
    }

    // ------------------------------------------------------------------ speaking (chat -> voice, test button)

    /**
     * Speaks text through the same voice settings (works without a LIVE: a small TTS-only helper is started on demand).
     * @param priority 0 = before queued LIVE comments
     */
    public static void speak(String text, int priority) { speak(text, priority, false); }

    /** @param self true = the voice chosen for the player's own Minecraft chat instead of the viewer voice */
    public static void speak(String text, int priority, boolean self) {
        if (text == null || text.isBlank()) return;
        String t = text.strip();
        if (t.length() > 400) t = t.substring(0, 400);
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "speak");
        o.addProperty("text", t);
        o.addProperty("prio", priority);
        if (self) o.addProperty("profile", "self");
        if (writeLine(o.toString())) return;
        PENDING_SPEECH.add(new String[] {o.toString()});
        while (PENDING_SPEECH.size() > 20) PENDING_SPEECH.poll();
        startTtsOnly();
    }

    /** Starts the voice helper in the background now, so the first chat line is spoken without the cold-start delay. */
    public static void warmUp() {
        if (process != null && process.isAlive()) return;
        startTtsOnly();
    }

    private static void flushPendingSpeech() {
        String[] item;
        while ((item = PENDING_SPEECH.poll()) != null) writeLine(item[0]);
    }

    private static void startTtsOnly() {
        if (running.get()) return;   // LIVE reader starting: it flushes the pending lines itself
        if (!ttsStarting.compareAndSet(false, true)) return;
        int myGeneration = generation.incrementAndGet();
        status = "Menyiapkan suara TTS…";
        Thread launcher = new Thread(() -> launch(null, myGeneration), "XYOPlay-TTS-Launch");
        launcher.setDaemon(true); launcher.start();
    }

    /** Stops whatever is being spoken and empties the speech queue. */
    public static void stopSpeaking() {
        PENDING_SPEECH.clear();
        writeLine("{\"cmd\":\"clear\"}");
    }

    /** Removes every stored line from a (just banned) account. */
    public static void purgeUser(String uid) {
        if (uid == null || uid.isBlank()) return;
        COMMENTS.removeIf(c -> uid.equalsIgnoreCase(c.uid()) || uid.equalsIgnoreCase(c.user()));
    }

    public static void clearComments() {
        COMMENTS.clear();
        total = 0;
        status = "Histori komentar dibersihkan";
        stopSpeaking();
    }

    /** Kills the python process (LIVE or TTS-only) without touching the LIVE running flag. */
    private static void stopProcessOnly() {
        generation.incrementAndGet();
        Process p = process; process = null;
        ttsStarting.set(false);
        if (p != null) {
            try { p.toHandle().descendants().forEach(h -> { try { h.destroyForcibly(); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
            try { p.destroyForcibly(); } catch (Exception ignored) {}
        }
    }

    public static void stop() {
        stopProcessOnly();
        running.set(false);
        liveMode = false;
        status = "Pembaca TikTok LIVE dimatikan";
    }

    // ------------------------------------------------------------------ pip install

    /**
     * Installs TikTokLive and edge-tts (required) and pyttsx3 (optional offline voice) into the Python used by the reader.
     * Each package is installed on its own, so one failing package never blocks the others. Tries plain, --user, then
     * --break-system-packages.
     */
    public static void installPackages() {
        if (!installing.compareAndSet(false, true)) { status = "Instalasi paket sedang berjalan…"; return; }
        status = "Menyiapkan instalasi paket Python…";
        Thread worker = new Thread(() -> {
            try {
                String python = findPython();
                if (python == null) { status = "Python 3.10+ tidak ditemukan. Pasang dari python.org (centang 'Add to PATH')"; return; }
                String[] required = {"TikTokLive", "edge-tts"};
                String[] optional = {"pyttsx3"};
                List<String> failed = new ArrayList<>();
                String lastError = "";
                for (String pkg : required) {
                    String err = pipInstall(python, pkg);
                    if (err != null) { failed.add(pkg); lastError = err; }
                }
                for (String pkg : optional) pipInstall(python, pkg);
                if (failed.isEmpty()) status = "Paket terpasang (TikTokLive, edge-tts). Klik Mulai LIVE.";
                else status = "Instalasi gagal untuk " + String.join(", ", failed) + ": " + (lastError.length() > 100 ? lastError.substring(0, 100) : lastError);
            } catch (Exception e) {
                status = "Instalasi gagal: " + e.getMessage();
            } finally { installing.set(false); }
        }, "XYOPlay-Pip");
        worker.setDaemon(true); worker.start();
    }

    /** @return null on success, otherwise the last output line of the failed attempt. */
    private static String pipInstall(String python, String pkg) throws IOException, InterruptedException {
        String[][] attempts = {
                {"-m", "pip", "install", "--upgrade", pkg},
                {"-m", "pip", "install", "--upgrade", "--user", pkg},
                {"-m", "pip", "install", "--upgrade", "--break-system-packages", pkg}};
        String lastOutput = "";
        for (String[] args : attempts) {
            status = "Memasang " + pkg + " lewat pip… (bisa 1-2 menit)";
            List<String> cmd = new ArrayList<>(); cmd.add(python); cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread dog = new Thread(() -> { try { if (!p.waitFor(600, TimeUnit.SECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) {} });
            dog.setDaemon(true); dog.start();
            String last = "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) { log("pip: " + line); if (!line.isBlank()) last = line.strip(); }
            }
            if (p.waitFor() == 0) return null;
            lastOutput = last;
        }
        return lastOutput;
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
