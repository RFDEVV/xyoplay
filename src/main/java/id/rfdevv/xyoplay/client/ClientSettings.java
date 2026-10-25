package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Persistent client-side preferences. */
public final class ClientSettings {
    private ClientSettings() {}

    /** HUD mode: 0 = off, 1 = mini pill, 2 = card. */
    public static volatile int hudMode = 1;
    /** HUD corner: 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right. */
    public static volatile int hudCorner = 0;
    public static volatile boolean hudAutoHide = false;
    public static volatile float volume = 0.75f;
    public static volatile boolean shuffle = false;
    public static volatile boolean repeat = false;
    public static volatile boolean ttsEnabled = true;
    public static volatile int ttsVolume = 90;
    public static volatile int ttsVoice = 0;
    public static volatile int ttsLanguage = 0;
    public static final String[] TTS_VOICES = {"Google (Perempuan)", "Sistem (Offline)"};
    public static final String[] TTS_VOICE_KEYS = {"google", "system"};
    public static final String[] TTS_LANGS = {"Indonesia", "Inggris", "Jawa", "Sunda", "Jepang", "Korea"};
    public static final String[] TTS_LANG_CODES = {"id", "en", "jv", "su", "ja", "ko"};
    public static volatile String tiktokUsername = "";
    public static volatile String ffmpegPath = "";
    public static volatile String ytDlpPath = "";
    public static volatile String pythonPath = "";
    /** Optional Spotify developer app credentials (client-credentials flow, search only). */
    public static volatile String spotifyClientId = "";
    public static volatile String spotifySecret = "";
    /** Optional Euler Stream API key: raises the TikTok LIVE sign-server rate limits. */
    public static volatile String eulerApiKey = "";

    public static synchronized void load() {
        try {
            Path path = configPath();
            if (!Files.exists(path)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(path)) { p.load(in); }
            if (p.getProperty("hudMode") != null) hudMode = clampInt(parseInt(p.getProperty("hudMode"), 1), 0, 2);
            else hudMode = Boolean.parseBoolean(p.getProperty("showHud", "true")) ? 1 : 0; // migrate from older versions
            hudCorner = clampInt(parseInt(p.getProperty("hudCorner"), 0), 0, 3);
            hudAutoHide = Boolean.parseBoolean(p.getProperty("hudAutoHide", "false"));
            volume = clamp(parseFloat(p.getProperty("volume"), 0.75f));
            shuffle = Boolean.parseBoolean(p.getProperty("shuffle", "false"));
            repeat = Boolean.parseBoolean(p.getProperty("repeat", "false"));
            ttsEnabled = Boolean.parseBoolean(p.getProperty("ttsEnabled", "true"));
            ttsVolume = clampInt(parseInt(p.getProperty("ttsVolume"), 90), 0, 100);
            ttsVoice = clampInt(parseInt(p.getProperty("ttsVoice"), 0), 0, TTS_VOICES.length - 1);
            ttsLanguage = clampInt(parseInt(p.getProperty("ttsLanguage"), 0), 0, TTS_LANGS.length - 1);
            tiktokUsername = p.getProperty("tiktokUsername", "").trim();
            ffmpegPath = p.getProperty("ffmpegPath", "").trim();
            ytDlpPath = p.getProperty("ytDlpPath", "").trim();
            pythonPath = p.getProperty("pythonPath", "").trim();
            spotifyClientId = p.getProperty("spotifyClientId", "").trim();
            spotifySecret = p.getProperty("spotifySecret", "").trim();
            eulerApiKey = p.getProperty("eulerApiKey", "").trim();
        } catch (Exception e) { System.err.println("[XYOPlay] Gagal membaca config: " + e.getMessage()); }
    }

    public static synchronized void save() {
        try {
            Path path = configPath(); Files.createDirectories(path.getParent());
            Properties p = new Properties();
            p.setProperty("hudMode", Integer.toString(hudMode));
            p.setProperty("hudCorner", Integer.toString(hudCorner));
            p.setProperty("hudAutoHide", Boolean.toString(hudAutoHide));
            p.setProperty("volume", Float.toString(clamp(volume)));
            p.setProperty("shuffle", Boolean.toString(shuffle));
            p.setProperty("repeat", Boolean.toString(repeat));
            p.setProperty("ttsEnabled", Boolean.toString(ttsEnabled));
            p.setProperty("ttsVolume", Integer.toString(ttsVolume));
            p.setProperty("ttsVoice", Integer.toString(ttsVoice));
            p.setProperty("ttsLanguage", Integer.toString(ttsLanguage));
            p.setProperty("tiktokUsername", nz(tiktokUsername));
            p.setProperty("ffmpegPath", nz(ffmpegPath));
            p.setProperty("ytDlpPath", nz(ytDlpPath));
            p.setProperty("pythonPath", nz(pythonPath));
            p.setProperty("spotifyClientId", nz(spotifyClientId));
            p.setProperty("spotifySecret", nz(spotifySecret));
            p.setProperty("eulerApiKey", nz(eulerApiKey));
            try (var out = Files.newOutputStream(path)) { p.store(out, "XYOPlay client settings (ffmpegPath / ytDlpPath / pythonPath opsional: isi path lengkap bila tidak ada di PATH)"); }
        } catch (IOException e) { System.err.println("[XYOPlay] Gagal menyimpan config: " + e.getMessage()); }
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static Path configPath() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay.properties"); }
    private static float parseFloat(String s, float fallback) { try { return Float.parseFloat(s); } catch (Exception ignored) { return fallback; } }
    private static int parseInt(String s, int fallback) { try { return Integer.parseInt(s.trim()); } catch (Exception ignored) { return fallback; } }
    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
