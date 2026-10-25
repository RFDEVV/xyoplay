package id.rfdevv.xyoplay.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Free public TikTok API (tikwm.com): keyword search and no-watermark / audio links.
 * GET /api/feed/search?keywords=&count=&cursor=   ->  data.videos[]
 * GET /api/?url=&hd=1                              ->  data{ title, music_info.play, author.unique_id, ... }
 * The free tier allows about one request per second, so calls are serialized and throttled.
 */
final class TikWm {
    private TikWm() {}

    record Resolved(String title, String audioUrl, String author) {}

    private static final String BASE = System.getProperty("xyoplay.tikwmBase", "https://www.tikwm.com");
    private static final Map<String, String> HEADERS = Map.of("Accept", "application/json", "Referer", BASE + "/");
    private static long lastCall;

    static List<YoutubeService.Video> search(String keywords) throws IOException {
        JsonObject root = call("/api/feed/search?keywords=" + enc(keywords) + "&count=20&cursor=0&HD=1");
        JsonObject data = object(root, "data");
        List<YoutubeService.Video> out = new ArrayList<>();
        if (data == null || !data.has("videos") || !data.get("videos").isJsonArray()) return out;
        JsonArray videos = data.getAsJsonArray("videos");
        for (JsonElement element : videos) {
            if (!element.isJsonObject()) continue;
            JsonObject v = element.getAsJsonObject();
            String id = str(v, "video_id");
            if (id.isBlank()) id = str(v, "id");
            JsonObject author = object(v, "author");
            String uid = author == null ? "" : str(author, "unique_id");
            JsonObject music = object(v, "music_info");
            String audio = fixUrl(music != null ? str(music, "play") : "");
            if (audio.isBlank()) audio = fixUrl(str(v, "music"));
            String title = str(v, "title").replace('\n', ' ').trim();
            if (title.isBlank() && music != null) title = str(music, "title");
            if (title.isBlank()) title = "Video TikTok " + id;
            String webUrl = !uid.isBlank() && !id.isBlank() ? "https://www.tiktok.com/@" + uid + "/video/" + id : "";
            if (webUrl.isBlank() && audio.isBlank()) continue;
            long likes = num(v, "digg_count"), plays = num(v, "play_count");
            int seconds = (int) num(v, "duration");
            String uploader = (uid.isBlank() ? "TikTok" : "@" + uid) + " · " + compact(likes) + " suka · " + compact(plays) + " tayang";
            out.add(new YoutubeService.Video(title, webUrl, uploader, YoutubeService.formatDuration(seconds), audio, "TT", likes, plays, seconds));
        }
        return out;
    }

    static Resolved resolve(String videoUrl) throws IOException {
        JsonObject root = call("/api/?url=" + enc(videoUrl) + "&hd=1");
        JsonObject data = object(root, "data");
        if (data == null) throw new IOException("Respons tikwm kosong");
        JsonObject music = object(data, "music_info");
        String audio = fixUrl(music != null ? str(music, "play") : "");
        if (audio.isBlank()) audio = fixUrl(str(data, "music"));
        if (audio.isBlank()) throw new IOException("Video ini tidak punya link audio");
        JsonObject author = object(data, "author");
        String uid = author == null ? "" : str(author, "unique_id");
        String title = str(data, "title").replace('\n', ' ').trim();
        if (title.isBlank() && music != null) title = str(music, "title");
        return new Resolved(title, audio, uid.isBlank() ? "" : "@" + uid);
    }

    // ---------------------------------------------------------------- internals

    private static synchronized JsonObject call(String path) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long wait = 1250 - (System.currentTimeMillis() - lastCall);
            if (wait > 0) sleep(wait);
            String body;
            try { body = Http.get(BASE + path, HEADERS); }
            finally { lastCall = System.currentTimeMillis(); }
            JsonObject root;
            try { root = JsonParser.parseString(body).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Respons tikwm bukan JSON (layanan sedang bermasalah?)"); }
            int code = root.has("code") && root.get("code").isJsonPrimitive() ? root.get("code").getAsInt() : 0;
            if (code == 0) return root;
            String msg = str(root, "msg");
            last = new IOException(msg.isBlank() ? "tikwm kode " + code : msg);
            if (!msg.toLowerCase(Locale.ROOT).contains("limit")) break;
            sleep(1600);
        }
        throw last != null ? last : new IOException("tikwm tidak merespons");
    }

    private static void sleep(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Dibatalkan"); }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String fixUrl(String url) {
        if (url == null || url.isBlank()) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return BASE + url;
        return url.startsWith("http") ? url : "";
    }

    private static JsonObject object(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    static String str(JsonObject o, String key) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; } catch (RuntimeException e) { return ""; }
    }

    private static long num(JsonObject o, String key) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? (long) o.get(key).getAsDouble() : 0; } catch (RuntimeException e) { return 0; }
    }

    static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        return Long.toString(n);
    }
}
