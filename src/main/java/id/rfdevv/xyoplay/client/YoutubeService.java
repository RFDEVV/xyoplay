package id.rfdevv.xyoplay.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search + download hub.
 *  - YouTube: yt-dlp (search and download)
 *  - TikTok: keyword search through the free tikwm.com API, audio downloaded directly (yt-dlp is only a fallback);
 *            @account / #hashtag / links still go through yt-dlp
 *  - Spotify: Spotify Web API (or iTunes Search) for metadata, audio is matched on YouTube and downloaded with yt-dlp
 */
public final class YoutubeService {
    public enum Source { YOUTUBE, TIKTOK, SPOTIFY }

    /** tag: "" = plain link, "TT" = TikTok (audioUrl may be direct), "SP:artist" = Spotify/iTunes catalogue entry. */
    public record Video(String title, String url, String uploader, String duration, String audioUrl, String tag, long likes, long plays, int seconds) {
        public Video(String title, String url, String uploader, String duration) { this(title, url, uploader, duration, null, "", 0, 0, -1); }
    }

    private static final Map<Source, List<Video>> RESULTS = new EnumMap<>(Source.class);
    private static final List<Video> TIKTOK_ORIGINAL = new CopyOnWriteArrayList<>();
    static {
        for (Source s : Source.values()) RESULTS.put(s, new CopyOnWriteArrayList<>());
    }
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final Pattern PERCENT = Pattern.compile("\\[download]\\s+(\\d+(?:\\.\\d+)?)%");
    private static volatile String status = "Masukkan kata kunci untuk mencari";
    private static volatile boolean enabled = true;
    private static volatile Process activeProcess;
    private static volatile boolean cancelled;
    private static volatile double progress = -1;
    private static volatile int tiktokSort; // 0 relevance, 1 likes, 2 views
    private static final String[] SORT_NAMES = {"Relevan", "Suka", "Tayangan"};

    private YoutubeService() {}

    public static String ytDlpExe() {
        String p = ClientSettings.ytDlpPath;
        return p == null || p.isBlank() ? "yt-dlp" : p;
    }

    public static List<Video> results(Source source) { return List.copyOf(RESULTS.get(source)); }
    public static boolean isBusy() { return BUSY.get(); }
    public static boolean isEnabled() { return enabled; }
    /** 0..1 while a download is running, otherwise -1. */
    public static double progress() { return BUSY.get() ? progress : -1; }
    public static String status() { return status; }

    public static synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) cancelActive();
    }

    public static synchronized void cancelActive() {
        cancelled = true;
        Process process = activeProcess;
        activeProcess = null;
        if (process != null) {
            try { process.toHandle().descendants().forEach(handle -> { try { handle.destroyForcibly(); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
            try { process.destroyForcibly(); } catch (Exception ignored) {}
        }
        BUSY.set(false);
        progress = -1;
        status = "Proses dihentikan";
    }

    // ------------------------------------------------------------ TikTok sorting

    public static String tiktokSortLabel() { return SORT_NAMES[tiktokSort]; }

    public static String cycleTikTokSort() {
        tiktokSort = (tiktokSort + 1) % SORT_NAMES.length;
        applyTikTokSort();
        return SORT_NAMES[tiktokSort];
    }

    private static void applyTikTokSort() {
        List<Video> sorted = new ArrayList<>(TIKTOK_ORIGINAL);
        if (tiktokSort == 1) sorted.sort(Comparator.comparingLong(Video::likes).reversed());
        else if (tiktokSort == 2) sorted.sort(Comparator.comparingLong(Video::plays).reversed());
        List<Video> target = RESULTS.get(Source.TIKTOK);
        target.clear();
        target.addAll(sorted);
    }

    // ------------------------------------------------------------------- search

    public static void search(Source source, String query) {
        if (query == null || query.isBlank()) { status = "Masukkan kata kunci pencarian"; return; }
        String q = query.trim();
        boolean viaYtDlp = source == Source.YOUTUBE || (source == Source.TIKTOK && (q.startsWith("@") || q.startsWith("#")));
        if (viaYtDlp && !enabled) { status = "yt-dlp nonaktif. Nyalakan melalui tab Alat."; return; }
        String target = null;
        if (source == Source.YOUTUBE) {
            target = q.startsWith("http") ? q : "ytsearch10:" + q;
            if (q.startsWith("http") && !allowedUrl(q)) { status = "Gunakan link YouTube yang valid"; return; }
        } else if (source == Source.TIKTOK && (q.startsWith("@") || q.startsWith("#"))) {
            target = tiktokTarget(q);
            if (target == null) { status = "Gunakan @akun atau #hashtag TikTok yang valid"; return; }
        }
        if (!BUSY.compareAndSet(false, true)) { status = "Tunggu proses sebelumnya selesai…"; return; }
        cancelled = false;
        List<Video> out = RESULTS.get(source);
        out.clear();
        if (source == Source.TIKTOK) TIKTOK_ORIGINAL.clear();
        status = switch (source) { case YOUTUBE -> "Mencari di YouTube…"; case TIKTOK -> "Mencari di TikTok…"; case SPOTIFY -> "Mencari lagu…"; };
        final String finalTarget = target;
        Thread worker = new Thread(() -> {
            try {
                List<Video> found = new ArrayList<>();
                String suffix = "";
                if (source == Source.TIKTOK) {
                    if (q.startsWith("http")) {
                        try {
                            TikWm.Resolved resolved = TikWm.resolve(q);
                            found.add(new Video(resolved.title(), q, resolved.author(), "0:00", resolved.audioUrl(), "TT", 0, 0, -1));
                            suffix = " · link TikTok";
                        } catch (Exception e) {
                            found = ytDlpList(q, Source.TIKTOK);
                            suffix = " · link TikTok";
                        }
                    } else if (q.startsWith("@") || q.startsWith("#")) {
                        found = ytDlpList(finalTarget, Source.TIKTOK);
                        suffix = " · akun TikTok";
                    } else {
                        try {
                            found = TikWm.search(q);
                            if (found.isEmpty()) throw new IOException("TikWM kosong");
                            suffix = " · TikTok";
                        } catch (Exception tikWmErr) {
                            status = "Mencari sound TikTok alternatif…";
                            try {
                                found = ytDlpList("ytsearch15:tiktok " + q, Source.TIKTOK);
                                suffix = " · sound TikTok";
                            } catch (Exception ytErr) {
                                found = ytDlpList("ytsearch10:" + q, Source.TIKTOK);
                                suffix = " · audio pencarian";
                            }
                        }
                    }
                } else if (viaYtDlp) {
                    found = ytDlpList(finalTarget, source);
                } else {
                    Catalog.Result result = Catalog.search(q);
                    found = result.videos();
                    suffix = " · sumber " + result.source() + (result.note().isEmpty() ? "" : " (" + result.note() + ")");
                }
                if (cancelled) return;
                if (source == Source.TIKTOK) { TIKTOK_ORIGINAL.addAll(found); applyTikTokSort(); }
                else out.addAll(found);
                status = found.isEmpty() ? "Tidak ada hasil. Coba kata kunci lain." : "Ditemukan " + found.size() + " hasil · klik untuk mengunduh" + suffix;
            } catch (Exception e) {
                if (cancelled) return;
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                status = switch (source) {
                    case TIKTOK -> "Pencarian TikTok: " + shorten(detail, 90);
                    case SPOTIFY -> "Pencarian lagu gagal: " + shorten(detail, 90);
                    default -> "Pencarian gagal: " + shorten(detail, 90);
                };
                System.err.println("[XYOPlay] Pencarian: " + e);
            } finally { activeProcess = null; BUSY.set(false); }
        }, "XYOPlay-Search");
        worker.setDaemon(true); worker.start();
    }

    /** @account -> profile, #tag -> hashtag page, link -> as is. Plain keywords are handled by tikwm instead. */
    static String tiktokTarget(String query) {
        String q = query.trim();
        if (q.startsWith("http")) return allowedUrl(q) ? q : null;
        if (q.startsWith("@")) {
            String user = q.substring(1).replaceAll("[^A-Za-z0-9_.]", "");
            return user.isEmpty() ? null : "https://www.tiktok.com/@" + user;
        }
        if (!q.startsWith("#")) return null;
        String tag = q.substring(1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (tag.isEmpty()) return null;
        return "https://www.tiktok.com/tag/" + URLEncoder.encode(tag, StandardCharsets.UTF_8);
    }

    /** Runs yt-dlp --flat-playlist on a search expression / URL and parses the entries. */
    private static List<Video> ytDlpList(String target, Source source) throws Exception {
        Process process = new ProcessBuilder(ytDlpExe(), "--flat-playlist", "--dump-single-json", "--no-warnings", "--skip-download", "--playlist-end", "20", target).redirectErrorStream(true).start();
        activeProcess = process;
        watchdog(process, 90);
        if (!enabled) { process.destroyForcibly(); throw new IOException("yt-dlp nonaktif"); }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) { if (output.length() < 4_000_000) output.append(line).append('\n'); }
        }
        int exit = process.waitFor();
        String text = output.toString();
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (exit != 0 || start < 0 || end <= start) throw new IOException(firstError(text));
        JsonObject root = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        List<JsonObject> items = new ArrayList<>();
        if (root.has("entries") && root.get("entries").isJsonArray()) {
            JsonArray entries = root.getAsJsonArray("entries");
            for (JsonElement element : entries) if (element.isJsonObject()) items.add(element.getAsJsonObject());
        } else items.add(root);
        List<Video> found = new ArrayList<>();
        for (JsonObject item : items) {
            String id = string(item, "id", "");
            String url = string(item, "url", "");
            if (url.isBlank() || !url.startsWith("http")) url = string(item, "webpage_url", "");
            if (url.isBlank() && !id.isBlank()) {
                if (source == Source.YOUTUBE || !item.toString().contains("tiktok.com")) url = "https://www.youtube.com/watch?v=" + id;
                else url = "https://www.tiktok.com/video/" + id;
            }
            if (url.isBlank() || !allowedUrl(url)) continue;
            int seconds = item.has("duration") && item.get("duration").isJsonPrimitive() && item.get("duration").getAsJsonPrimitive().isNumber() ? item.get("duration").getAsInt() : -1;
            String title = string(item, "title", "");
            if (title.isBlank()) title = string(item, "description", "");
            if (title.isBlank()) title = isTikTok(url) ? "Video TikTok " + id : "Video tanpa judul";
            String uploader = string(item, "uploader", string(item, "channel", string(item, "creator", isTikTok(url) ? "TikTok" : "YouTube")));
            found.add(new Video(title.replace('\n', ' '), url, uploader, formatDuration(seconds), null, isTikTok(url) ? "TT" : "", 0, 0, seconds));
        }
        return found;
    }

    // ----------------------------------------------------------------- download

    public static void download(Video video, Path folder) {
        if (video.tag() != null && video.tag().startsWith("SP")) downloadMatched(video, folder);
        else if ("TT".equals(video.tag()) || isTikTok(video.url())) downloadTikTok(video.url(), video.title(), video.audioUrl(), video.uploader(), folder);
        else downloadUrl(video.url(), folder);
    }

    public static void downloadUrl(String url, Path folder) {
        if (url == null || url.isBlank()) { status = "Tempel link YouTube atau TikTok terlebih dahulu"; return; }
        String cleaned = url.trim();
        if (!allowedUrl(cleaned)) { status = "Gunakan link YouTube atau TikTok yang valid (https://…)"; return; }
        if (isTikTok(cleaned)) { downloadTikTok(cleaned, null, null, null, folder); return; }
        if (!requireTools()) return;
        if (!BUSY.compareAndSet(false, true)) { status = "Tunggu proses sebelumnya selesai…"; return; }
        beginTask("Mengunduh audio… jangan tutup game");
        runAsync("XYOPlay-Download", () -> ytDlpDownload(cleaned, folder, null, null, null));
    }

    private static void downloadTikTok(String pageUrl, String title, String audioUrl, String uploader, Path folder) {
        if (!BUSY.compareAndSet(false, true)) { status = "Tunggu proses sebelumnya selesai…"; return; }
        beginTask("Mengambil audio TikTok…");
        runAsync("XYOPlay-TikTok-Download", () -> {
            String name = title, audio = audioUrl, artist = uploader == null ? "" : uploader.replaceAll(" · .*$", "");
            IOException direct = null;
            try {
                if (audio == null || audio.isBlank()) {
                    TikWm.Resolved resolved = TikWm.resolve(pageUrl);
                    audio = resolved.audioUrl();
                    if (name == null || name.isBlank()) name = resolved.title();
                    if (artist.isBlank()) artist = resolved.author();
                }
                String base = baseName(name == null || name.isBlank() ? "TikTok " + System.currentTimeMillis() : name, 90);
                Path target = uniquePath(folder, base, "mp3");
                status = "Mengunduh audio TikTok…";
                progress = 0;
                Http.download(audio, target, Map.of("Referer", "https://www.tiktok.com/"), p -> progress = p, () -> cancelled);
                MusicLibrary.refresh();
                tagMetadata(target, name, artist);
                status = "Unduhan selesai · " + MusicLibrary.tracks().size() + " lagu di pustaka";
                return;
            } catch (IOException e) { direct = e; }
            if (cancelled) return;
            if (enabled && LocalAudioPlayer.isEnabled()) {
                status = "Cara cepat gagal (" + shorten(direct.getMessage() == null ? "" : direct.getMessage(), 40) + "), mencoba yt-dlp…";
                try { ytDlpDownload(pageUrl, folder, null, name, artist); return; }
                catch (Exception e) { throw new IOException(direct.getMessage() + " · yt-dlp: " + e.getMessage()); }
            }
            throw direct;
        });
    }

    /** Spotify / iTunes entry: find the same song on YouTube, then download that audio as "Artist - Title". */
    private static void downloadMatched(Video video, Path folder) {
        if (!requireTools()) return;
        if (!BUSY.compareAndSet(false, true)) { status = "Tunggu proses sebelumnya selesai…"; return; }
        beginTask("Mencari lagu yang cocok di YouTube…");
        String artist = video.tag().length() > 3 ? video.tag().substring(3) : "";
        runAsync("XYOPlay-Match-Download", () -> {
            Video best = findBestMatch(artist, video.title(), video.seconds());
            if (best == null) throw new IOException("tidak ada hasil YouTube yang cocok");
            status = "Mengunduh: " + shorten(best.title(), 50);
            ytDlpDownload(best.url(), folder, artist.isBlank() ? video.title() : artist + " - " + video.title(), video.title(), artist);
        });
    }

    static Video findBestMatch(String artist, String title, int seconds) throws Exception {
        List<Video> candidates = ytDlpList("ytsearch8:" + (artist + " " + title + " audio").trim(), Source.YOUTUBE);
        String wantTitle = title.toLowerCase(Locale.ROOT), wantArtist = artist.toLowerCase(Locale.ROOT);
        String[] bad = {"live", "cover", "remix", "karaoke", "instrumental", "sped up", "slowed", "reverb", "nightcore", "8d", "tutorial", "reaction"};
        Video best = null;
        double bestScore = -1e9;
        for (Video c : candidates) {
            String t = c.title().toLowerCase(Locale.ROOT), channel = c.uploader().toLowerCase(Locale.ROOT);
            double score = 0;
            if (seconds > 0 && c.seconds() > 0) {
                int diff = Math.abs(c.seconds() - seconds);
                score -= Math.min(diff, 120);
                if (diff <= 5) score += 40; else if (diff <= 12) score += 15;
            }
            if (t.contains(wantTitle)) score += 30;
            if (!wantArtist.isEmpty() && (t.contains(wantArtist) || channel.contains(wantArtist))) score += 20;
            if (channel.endsWith("- topic")) score += 25;
            if (t.contains("official audio")) score += 10;
            for (String word : bad) if (t.contains(word) && !wantTitle.contains(word)) score -= 40;
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    /** Core yt-dlp audio download. baseName == null uses the video title. Throws on failure. */
    private static void ytDlpDownload(String url, Path folder, String baseName, String metaTitle, String metaArtist) throws Exception {
        Files.createDirectories(folder);
        if (!enabled || !LocalAudioPlayer.isEnabled()) throw new IOException("Alat dinonaktifkan sebelum unduhan dimulai");
        String safeBase = baseName == null ? null : baseName(baseName, 120);
        String template = safeBase == null ? "%(title).150B.%(ext)s" : safeBase.replace("%", "%%") + ".%(ext)s";
        Process process = new ProcessBuilder(ytDlpExe(), "--no-playlist", "--playlist-end", "1", "--no-warnings", "--newline", "--extract-audio", "--audio-format", "mp3", "--audio-quality", "0", "--embed-metadata", "--windows-filenames", "-o", folder.resolve(template).toString(), url).redirectErrorStream(true).start();
        activeProcess = process;
        watchdog(process, 900);
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 12000) output.append(line).append('\n');
                Matcher m = PERCENT.matcher(line);
                if (m.find()) {
                    try { progress = Double.parseDouble(m.group(1)) / 100.0; } catch (NumberFormatException ignored) {}
                    status = "Mengunduh audio… " + Math.round(progress * 100) + "%";
                } else if (line.contains("[ExtractAudio]")) { progress = 1; status = "Mengonversi ke MP3…"; }
            }
        }
        int exit = process.waitFor();
        if (cancelled) return;
        if (exit != 0) throw new IOException(firstError(output.toString()));
        MusicLibrary.refresh();
        if (safeBase != null) tagMetadata(folder.resolve(safeBase + ".mp3"), metaTitle, metaArtist);
        status = "Unduhan selesai · " + MusicLibrary.tracks().size() + " lagu di pustaka";
    }

    // ------------------------------------------------------------------ helpers

    private interface Task { void run() throws Exception; }

    private static void beginTask(String message) { cancelled = false; progress = 0; status = message; }

    private static void runAsync(String name, Task task) {
        Thread worker = new Thread(() -> {
            try { task.run(); }
            catch (Exception e) {
                if (!cancelled) {
                    status = "Unduhan gagal: " + shorten(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 100);
                    System.err.println("[XYOPlay] Unduhan gagal: " + e);
                }
            } finally { activeProcess = null; progress = -1; BUSY.set(false); }
        }, name);
        worker.setDaemon(true); worker.start();
    }

    private static boolean requireTools() {
        if (!enabled) { status = "yt-dlp nonaktif. Nyalakan melalui tab Alat."; return false; }
        if (!LocalAudioPlayer.isEnabled()) { status = "FFmpeg nonaktif. Nyalakan melalui tab Alat."; return false; }
        return true;
    }

    private static void tagMetadata(Path file, String title, String artist) {
        try {
            if (!MusicLibrary.tracks().contains(file) || title == null || title.isBlank()) return;
            MusicLibraryData.setMetadata(file, title.length() > 120 ? title.substring(0, 120) : title, artist == null ? "" : artist);
        } catch (RuntimeException ignored) { }
    }

    static String baseName(String raw, int max) {
        String s = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > max) s = s.substring(0, max).trim();
        return s.isEmpty() ? "audio" : s;
    }

    private static Path uniquePath(Path folder, String base, String ext) {
        Path p = folder.resolve(base + "." + ext);
        for (int i = 2; Files.exists(p) && i < 1000; i++) p = folder.resolve(base + " (" + i + ")." + ext);
        return p;
    }

    private static boolean isTikTok(String url) {
        try { String h = new URI(url.trim()).getHost(); return h != null && (h.equalsIgnoreCase("tiktok.com") || h.toLowerCase(Locale.ROOT).endsWith(".tiktok.com")); }
        catch (Exception e) { return false; }
    }

    static boolean allowedUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return hostIs(host, "youtube.com") || host.equals("youtu.be") || hostIs(host, "tiktok.com");
        } catch (Exception e) { return false; }
    }
    private static boolean hostIs(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }

    private static void watchdog(Process process, int seconds) {
        Thread t = new Thread(() -> {
            try { if (!process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException ignored) {}
        }, "XYOPlay-Watchdog");
        t.setDaemon(true); t.start();
    }

    private static String firstError(String text) {
        if (text == null) return "";
        for (String line : text.split("\\R")) if (line.contains("ERROR")) return line.replaceFirst("^.*?ERROR:\\s*", "").trim();
        String t = text.strip();
        return t.isEmpty() ? "yt-dlp tidak menghasilkan data (sudah terpasang?)" : t.substring(Math.max(0, t.lastIndexOf('\n') + 1));
    }
    private static String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    static String formatDuration(int seconds) {
        if (seconds < 0) return "--:--";
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
