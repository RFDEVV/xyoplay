package id.rfdevv.xyoplay.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Song catalogue search used by the Spotify tab.
 * With a Spotify Client ID + Secret (free, from developer.spotify.com) the Spotify Web API is used
 * (client-credentials flow, no user login). Without them, or if Spotify refuses, the free iTunes Search API is used instead.
 * Spotify audio itself is DRM-protected and is never downloaded from Spotify: a result is matched to the same
 * track on YouTube and downloaded from there (see YoutubeService.downloadMatched).
 */
final class Catalog {
    private Catalog() {}

    record Result(List<YoutubeService.Video> videos, String source, String note) {}

    private static final Pattern TRACK_ID = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]{10,32})");
    private static final String SPOTIFY_API = System.getProperty("xyoplay.spotifyApi", "https://api.spotify.com/v1");
    private static final String SPOTIFY_TOKEN = System.getProperty("xyoplay.spotifyToken", "https://accounts.spotify.com/api/token");
    private static final String ITUNES = System.getProperty("xyoplay.itunes", "https://itunes.apple.com");
    private static String token;
    private static long tokenExpiresAt;
    private static String tokenOwner = "";

    static boolean hasSpotifyCredentials() {
        return !blank(ClientSettings.spotifyClientId) && !blank(ClientSettings.spotifySecret);
    }

    static Result search(String query) throws IOException {
        String note = "";
        if (hasSpotifyCredentials()) {
            try { return new Result(spotify(query), "Spotify", ""); }
            catch (IOException e) { note = "Spotify gagal (" + e.getMessage() + "), memakai iTunes"; }
        } else if (TRACK_ID.matcher(query).find()) {
            throw new IOException("Link Spotify butuh Client ID & Secret (Pengaturan)");
        } else note = "Isi Client ID & Secret Spotify di Pengaturan untuk hasil Spotify; sementara memakai iTunes";
        return new Result(itunes(query), "iTunes", note);
    }

    // ----------------------------------------------------------------- Spotify

    private static List<YoutubeService.Video> spotify(String query) throws IOException {
        Matcher link = TRACK_ID.matcher(query);
        for (int attempt = 0; ; attempt++) {
            String bearer = accessToken(attempt > 0);
            Map<String, String> headers = Map.of("Authorization", "Bearer " + bearer, "Accept", "application/json");
            try {
                String body;
                List<JsonObject> items = new ArrayList<>();
                if (link.find()) {
                    body = Http.get(SPOTIFY_API + "/tracks/" + link.group(1), headers);
                    items.add(JsonParser.parseString(body).getAsJsonObject());
                } else {
                    body = Http.get(SPOTIFY_API + "/search?type=track&limit=10&q=" + enc(query), headers);
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject tracks = root.has("tracks") && root.get("tracks").isJsonObject() ? root.getAsJsonObject("tracks") : null;
                    if (tracks != null && tracks.has("items") && tracks.get("items").isJsonArray()) {
                        for (JsonElement e : tracks.getAsJsonArray("items")) if (e.isJsonObject()) items.add(e.getAsJsonObject());
                    }
                }
                List<YoutubeService.Video> out = new ArrayList<>();
                for (JsonObject t : items) {
                    String name = TikWm.str(t, "name");
                    if (name.isBlank()) continue;
                    List<String> artists = new ArrayList<>();
                    if (t.has("artists") && t.get("artists").isJsonArray()) {
                        for (JsonElement a : t.getAsJsonArray("artists")) if (a.isJsonObject()) artists.add(TikWm.str(a.getAsJsonObject(), "name"));
                    }
                    String artist = artists.isEmpty() ? "" : String.join(", ", artists);
                    String album = t.has("album") && t.get("album").isJsonObject() ? TikWm.str(t.getAsJsonObject("album"), "name") : "";
                    int seconds = (int) Math.round(number(t, "duration_ms") / 1000.0);
                    String url = "https://open.spotify.com/track/" + TikWm.str(t, "id");
                    out.add(new YoutubeService.Video(name, url, artist + (album.isBlank() ? "" : " · " + album), YoutubeService.formatDuration(seconds), null, "SP:" + artist, 0, 0, seconds));
                }
                return out;
            } catch (Http.StatusException e) {
                if (e.status == 401 && attempt == 0) continue;
                if (e.status == 429) throw new IOException("terlalu banyak permintaan, coba lagi sebentar");
                if (e.status == 401 || e.status == 403) throw new IOException("Client ID/Secret ditolak atau akses dibatasi");
                throw new IOException(e.getMessage());
            }
        }
    }

    private static synchronized String accessToken(boolean force) throws IOException {
        String owner = ClientSettings.spotifyClientId + ":" + ClientSettings.spotifySecret;
        if (!force && token != null && owner.equals(tokenOwner) && System.currentTimeMillis() < tokenExpiresAt - 60_000) return token;
        String basic = Base64.getEncoder().encodeToString(owner.getBytes(StandardCharsets.UTF_8));
        String body;
        try {
            body = Http.postForm(SPOTIFY_TOKEN, Map.of("Authorization", "Basic " + basic), "grant_type=client_credentials");
        } catch (Http.StatusException e) {
            if (e.status == 400 || e.status == 401) throw new IOException("Client ID/Secret salah");
            throw e;
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        String value = TikWm.str(json, "access_token");
        if (value.isBlank()) throw new IOException("token Spotify kosong");
        token = value; tokenOwner = owner;
        tokenExpiresAt = System.currentTimeMillis() + (long) (number(json, "expires_in") * 1000);
        return token;
    }

    // ------------------------------------------------------------------ iTunes

    private static List<YoutubeService.Video> itunes(String query) throws IOException {
        String body = Http.get(ITUNES + "/search?media=music&entity=song&limit=20&term=" + enc(query), Map.of("Accept", "application/json"));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        List<YoutubeService.Video> out = new ArrayList<>();
        if (!root.has("results") || !root.get("results").isJsonArray()) return out;
        JsonArray results = root.getAsJsonArray("results");
        for (JsonElement e : results) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String name = TikWm.str(t, "trackName");
            if (name.isBlank()) continue;
            String artist = TikWm.str(t, "artistName"), album = TikWm.str(t, "collectionName");
            int seconds = (int) Math.round(number(t, "trackTimeMillis") / 1000.0);
            out.add(new YoutubeService.Video(name, TikWm.str(t, "trackViewUrl"), artist + (album.isBlank() ? "" : " · " + album),
                    YoutubeService.formatDuration(seconds), null, "SP:" + artist, 0, 0, seconds));
        }
        return out;
    }

    private static double number(JsonObject o, String key) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0; } catch (RuntimeException e) { return 0; }
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
