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
    /** Play a song right after it has been downloaded from a search result. */
    public static volatile boolean autoPlayDownloads = true;
    public static volatile boolean shuffle = false;
    public static volatile boolean repeat = false;
    public static volatile boolean ttsEnabled = true;
    /** TTS loudness in percent. 100 is already normalised and loud; values above 100 boost further (limited, no clipping). */
    public static volatile int ttsVolume = 100;
    public static volatile String ttsVoiceKey = TtsCatalog.DEFAULT_KEY;
    public static volatile int ttsLanguage = 0;
    /** Speaking speed offset in percent (-50..+50) and pitch offset in Hz (-50..+50, Edge voices only). */
    public static volatile int ttsRate = 0;
    public static volatile int ttsPitch = 0;
    public static volatile boolean ttsReadNames = true;
    public static volatile boolean ttsReadFollow = true;
    public static volatile boolean ttsReadGift = true;
    public static volatile boolean ttsReadShare = true;
    public static volatile boolean ttsReadJoin = false;
    public static final String[] TTS_LANGS = {"Indonesia", "Inggris", "Jawa", "Sunda", "Jepang", "Korea", "Melayu", "Mandarin"};
    public static final String[] TTS_LANG_CODES = {"id", "en", "jv", "su", "ja", "ko", "ms", "zh"};
    /** Everything typed in the Minecraft chat is spoken (chatTts); chatTtsSend also posts it to the server chat. */
    public static volatile boolean chatTts = false;
    public static volatile boolean chatTtsSend = false;
    /** Badword filter. filterSkipComment: do not read a comment at all when it contains a badword. */
    public static volatile boolean filterEnabled = true;
    public static volatile boolean filterSkipComment = false;
    /** Mob / effect actions triggered by LIVE events. */
    public static volatile boolean actionsEnabled = false;
    public static volatile boolean actionsNameTag = true;
    public static volatile int actionsMaxPerEvent = 10;
    /** On-screen banners for follows / gifts / shares. */
    public static volatile boolean liveBanner = true;
    /** Small coordinates / direction overlay and death-point marker. */
    public static volatile boolean infoHud = false;
    public static volatile boolean deathMarker = true;
    public static volatile String chatVoiceKey = "edge:id-ID-ArdiNeural";
    public static volatile int chatVolume = 120, chatRate = 0, chatPitch = 0;
    public static volatile int infoCorner = 1;            // 0 kiri-atas, 1 kanan-atas, 2 kiri-bawah, 3 kanan-bawah
    public static volatile boolean markerShow = true, markerClearOnArrive = true;
    public static volatile int cmdCooldownSec = 5;
    // Chaos Mode
    public static volatile boolean tntSafe = true;         // true = TNT effect without real entity (no block damage)
    public static volatile int tntMax = 30;                // max TNT per gift piece
    public static volatile int tntFuseSec = 3;
    public static volatile int spawnGapTicks = 4;          // pause between two spawned entities
    public static volatile int maxEntities = 40;           // max entities spawned per 30 seconds
    public static volatile int waveGapTicks = 20;          // pause between waves of a big event
    public static volatile int potionSeconds = 20;
    public static volatile int potionGoodPct = 60;
    public static volatile boolean bannerAvatar = true;
    public static volatile boolean memeSounds = true;       // sound effects for follow / gift / tap / goal / winner
    public static volatile int memeVolume = 80;
    // ---- helper bots / AFK autopilot
    public static volatile int mpCmdRate = 2;               // commands per second typed on a server (anti-spam kick protection)
    public static volatile boolean botEnabled = false;
    public static volatile int botCount = 1;                // 1..5
    public static volatile int botType = 0;                 // index into Bots.TYPES (0 = player body)
    public static volatile int botSkin = 0;                 // index into Bots.SKINS
    public static volatile String botSkinName = "";         // Minecraft account name used when the skin is "custom"
    public static volatile int botOutfit = 1;               // cosmetic armor preset
    public static volatile boolean botSurvival = true;      // bots can be hurt and die
    public static volatile int botFollowDist = 3;           // 2..10
    public static volatile int botSpeed = 1;                // 0 slow, 1 normal, 2 fast
    public static volatile boolean botDig = true, botPlace = true, botFight = true, botAutoGear = true, botAutoBase = true, botNametag = true, botXray = true;
    public static volatile int botEco = 1;                  // 0 hemat, 1 normal, 2 kencang (path search budget)
    public static volatile int botStartKit = 1;             // 0 none, 1 basic, 2 iron, 3 diamond
    public static volatile int botSendEvery = 2;            // ticks between position updates
    public static volatile String botMine = "auto";
    public static volatile boolean afkReturn = true, afkFood = true, afkNightHome = true, afkRespawn = true;
    public static volatile boolean liveHud = true;          // top gifters + follow goal panel
    public static volatile boolean bigTitle = true;         // big on-screen title for big gifts / taps / goals
    public static volatile int goalStep = 1000;              // follow goal step (0 = off)
    public static volatile String quickPhrases = "";        // saved quick phrases, separated by |
    public static volatile String giveawayWord = "!ikut";
    public static volatile int likeStep = 200;             // taps PER VIEWER until a "like" rule runs for that viewer
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
            autoPlayDownloads = Boolean.parseBoolean(p.getProperty("autoPlayDownloads", "true"));
            shuffle = Boolean.parseBoolean(p.getProperty("shuffle", "false"));
            repeat = Boolean.parseBoolean(p.getProperty("repeat", "false"));
            ttsEnabled = Boolean.parseBoolean(p.getProperty("ttsEnabled", "true"));
            ttsVolume = clampInt(parseInt(p.getProperty("ttsVolume"), 100), 10, 300);
            ttsVoiceKey = TtsCatalog.normalize(p.getProperty("ttsVoiceKey", TtsCatalog.DEFAULT_KEY).trim());
            ttsLanguage = clampInt(parseInt(p.getProperty("ttsLanguage"), 0), 0, TTS_LANGS.length - 1);
            ttsRate = clampInt(parseInt(p.getProperty("ttsRate"), 0), -50, 50);
            ttsPitch = clampInt(parseInt(p.getProperty("ttsPitch"), 0), -50, 50);
            ttsReadNames = Boolean.parseBoolean(p.getProperty("ttsReadNames", "true"));
            ttsReadFollow = Boolean.parseBoolean(p.getProperty("ttsReadFollow", "true"));
            ttsReadGift = Boolean.parseBoolean(p.getProperty("ttsReadGift", "true"));
            ttsReadShare = Boolean.parseBoolean(p.getProperty("ttsReadShare", "true"));
            ttsReadJoin = Boolean.parseBoolean(p.getProperty("ttsReadJoin", "false"));
            chatTts = Boolean.parseBoolean(p.getProperty("chatTts", "false"));
            chatTtsSend = Boolean.parseBoolean(p.getProperty("chatTtsSend", "false"));
            filterEnabled = Boolean.parseBoolean(p.getProperty("filterEnabled", "true"));
            filterSkipComment = Boolean.parseBoolean(p.getProperty("filterSkipComment", "false"));
            actionsEnabled = Boolean.parseBoolean(p.getProperty("actionsEnabled", "false"));
            actionsNameTag = Boolean.parseBoolean(p.getProperty("actionsNameTag", "true"));
            actionsMaxPerEvent = clampInt(parseInt(p.getProperty("actionsMaxPerEvent"), 10), 1, 50);
            liveBanner = Boolean.parseBoolean(p.getProperty("liveBanner", "true"));
            infoHud = Boolean.parseBoolean(p.getProperty("infoHud", "false"));
            deathMarker = Boolean.parseBoolean(p.getProperty("deathMarker", "true"));
            chatVoiceKey = TtsCatalog.normalize(p.getProperty("chatVoiceKey", "edge:id-ID-ArdiNeural"));
            chatVolume = clampInt(parseInt(p.getProperty("chatVolume"), 120), 10, 300);
            chatRate = clampInt(parseInt(p.getProperty("chatRate"), 0), -50, 100);
            chatPitch = clampInt(parseInt(p.getProperty("chatPitch"), 0), -50, 50);
            infoCorner = clampInt(parseInt(p.getProperty("infoCorner"), 1), 0, 3);
            markerShow = Boolean.parseBoolean(p.getProperty("markerShow", "true"));
            markerClearOnArrive = Boolean.parseBoolean(p.getProperty("markerClearOnArrive", "true"));
            cmdCooldownSec = clampInt(parseInt(p.getProperty("cmdCooldownSec"), 5), 0, 120);
            tntSafe = Boolean.parseBoolean(p.getProperty("tntSafe", "true"));
            tntMax = clampInt(parseInt(p.getProperty("tntMax"), 30), 1, 100);
            tntFuseSec = clampInt(parseInt(p.getProperty("tntFuseSec"), 3), 1, 10);
            spawnGapTicks = clampInt(parseInt(p.getProperty("spawnGapTicks"), 4), 1, 40);
            maxEntities = clampInt(parseInt(p.getProperty("maxEntities"), 40), 5, 200);
            waveGapTicks = clampInt(parseInt(p.getProperty("waveGapTicks"), 20), 5, 200);
            potionSeconds = clampInt(parseInt(p.getProperty("potionSeconds"), 20), 3, 120);
            potionGoodPct = clampInt(parseInt(p.getProperty("potionGoodPct"), 60), 0, 100);
            bannerAvatar = Boolean.parseBoolean(p.getProperty("bannerAvatar", "true"));
            likeStep = clampInt(parseInt(p.getProperty("likeStep"), 200), 20, 5000);
            memeSounds = Boolean.parseBoolean(p.getProperty("memeSounds", "true"));
            memeVolume = clampInt(parseInt(p.getProperty("memeVolume"), 80), 0, 100);
            mpCmdRate = clampInt(parseInt(p.getProperty("mpCmdRate"), 2), 1, 10);
            botEnabled = Boolean.parseBoolean(p.getProperty("botEnabled", "false"));
            botCount = clampInt(parseInt(p.getProperty("botCount"), 1), 1, 5);
            botType = clampInt(parseInt(p.getProperty("botType"), 0), 0, 5);
            botSkin = clampInt(parseInt(p.getProperty("botSkin"), 0), 0, 9);
            botSkinName = p.getProperty("botSkinName", "").replaceAll("[^A-Za-z0-9_]", "");
            botOutfit = clampInt(parseInt(p.getProperty("botOutfit"), 1), 0, 4);
            botSurvival = Boolean.parseBoolean(p.getProperty("botSurvival", "true"));
            botFollowDist = clampInt(parseInt(p.getProperty("botFollowDist"), 3), 2, 10);
            botSpeed = clampInt(parseInt(p.getProperty("botSpeed"), 1), 0, 2);
            botDig = Boolean.parseBoolean(p.getProperty("botDig", "true"));
            botPlace = Boolean.parseBoolean(p.getProperty("botPlace", "true"));
            botFight = Boolean.parseBoolean(p.getProperty("botFight", "true"));
            botAutoGear = Boolean.parseBoolean(p.getProperty("botAutoGear", "true"));
            botAutoBase = Boolean.parseBoolean(p.getProperty("botAutoBase", "true"));
            botNametag = Boolean.parseBoolean(p.getProperty("botNametag", "true"));
            botXray = Boolean.parseBoolean(p.getProperty("botXray", "true"));
            botEco = clampInt(parseInt(p.getProperty("botEco"), 1), 0, 2);
            botStartKit = clampInt(parseInt(p.getProperty("botStartKit"), 1), 0, 3);
            botSendEvery = clampInt(parseInt(p.getProperty("botSendEvery"), 2), 1, 6);
            botMine = p.getProperty("botMine", "auto");
            afkReturn = Boolean.parseBoolean(p.getProperty("afkReturn", "true"));
            afkFood = Boolean.parseBoolean(p.getProperty("afkFood", "true"));
            afkNightHome = Boolean.parseBoolean(p.getProperty("afkNightHome", "true"));
            afkRespawn = Boolean.parseBoolean(p.getProperty("afkRespawn", "true"));
            liveHud = Boolean.parseBoolean(p.getProperty("liveHud", "true"));
            bigTitle = Boolean.parseBoolean(p.getProperty("bigTitle", "true"));
            goalStep = clampInt(parseInt(p.getProperty("goalStep"), 1000), 0, 1000000);
            if (goalStep == 50) goalStep = 1000;   // old default
            quickPhrases = p.getProperty("quickPhrases", "");
            String gw = p.getProperty("giveawayWord", "!ikut").trim().toLowerCase();
            giveawayWord = gw.matches("![a-z0-9_]{1,24}") ? gw : "!ikut";
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
            p.setProperty("autoPlayDownloads", Boolean.toString(autoPlayDownloads));
            p.setProperty("shuffle", Boolean.toString(shuffle));
            p.setProperty("repeat", Boolean.toString(repeat));
            p.setProperty("ttsEnabled", Boolean.toString(ttsEnabled));
            p.setProperty("ttsVolume", Integer.toString(ttsVolume));
            p.setProperty("ttsVoiceKey", nz(ttsVoiceKey));
            p.setProperty("ttsLanguage", Integer.toString(ttsLanguage));
            p.setProperty("ttsRate", Integer.toString(ttsRate));
            p.setProperty("ttsPitch", Integer.toString(ttsPitch));
            p.setProperty("ttsReadNames", Boolean.toString(ttsReadNames));
            p.setProperty("ttsReadFollow", Boolean.toString(ttsReadFollow));
            p.setProperty("ttsReadGift", Boolean.toString(ttsReadGift));
            p.setProperty("ttsReadShare", Boolean.toString(ttsReadShare));
            p.setProperty("ttsReadJoin", Boolean.toString(ttsReadJoin));
            p.setProperty("chatTts", Boolean.toString(chatTts));
            p.setProperty("chatTtsSend", Boolean.toString(chatTtsSend));
            p.setProperty("filterEnabled", Boolean.toString(filterEnabled));
            p.setProperty("filterSkipComment", Boolean.toString(filterSkipComment));
            p.setProperty("actionsEnabled", Boolean.toString(actionsEnabled));
            p.setProperty("actionsNameTag", Boolean.toString(actionsNameTag));
            p.setProperty("actionsMaxPerEvent", Integer.toString(actionsMaxPerEvent));
            p.setProperty("liveBanner", Boolean.toString(liveBanner));
            p.setProperty("infoHud", Boolean.toString(infoHud));
            p.setProperty("deathMarker", Boolean.toString(deathMarker));
            p.setProperty("chatVoiceKey", chatVoiceKey);
            p.setProperty("chatVolume", Integer.toString(chatVolume));
            p.setProperty("chatRate", Integer.toString(chatRate));
            p.setProperty("chatPitch", Integer.toString(chatPitch));
            p.setProperty("infoCorner", Integer.toString(infoCorner));
            p.setProperty("markerShow", Boolean.toString(markerShow));
            p.setProperty("markerClearOnArrive", Boolean.toString(markerClearOnArrive));
            p.setProperty("cmdCooldownSec", Integer.toString(cmdCooldownSec));
            p.setProperty("tntSafe", Boolean.toString(tntSafe));
            p.setProperty("tntMax", Integer.toString(tntMax));
            p.setProperty("tntFuseSec", Integer.toString(tntFuseSec));
            p.setProperty("spawnGapTicks", Integer.toString(spawnGapTicks));
            p.setProperty("maxEntities", Integer.toString(maxEntities));
            p.setProperty("waveGapTicks", Integer.toString(waveGapTicks));
            p.setProperty("potionSeconds", Integer.toString(potionSeconds));
            p.setProperty("potionGoodPct", Integer.toString(potionGoodPct));
            p.setProperty("bannerAvatar", Boolean.toString(bannerAvatar));
            p.setProperty("likeStep", Integer.toString(likeStep));
            p.setProperty("memeSounds", Boolean.toString(memeSounds));
            p.setProperty("memeVolume", Integer.toString(memeVolume));
            p.setProperty("mpCmdRate", Integer.toString(mpCmdRate));
            p.setProperty("botEnabled", Boolean.toString(botEnabled));
            p.setProperty("botCount", Integer.toString(botCount));
            p.setProperty("botType", Integer.toString(botType));
            p.setProperty("botSkin", Integer.toString(botSkin));
            p.setProperty("botSkinName", botSkinName);
            p.setProperty("botOutfit", Integer.toString(botOutfit));
            p.setProperty("botSurvival", Boolean.toString(botSurvival));
            p.setProperty("botFollowDist", Integer.toString(botFollowDist));
            p.setProperty("botSpeed", Integer.toString(botSpeed));
            p.setProperty("botDig", Boolean.toString(botDig));
            p.setProperty("botPlace", Boolean.toString(botPlace));
            p.setProperty("botFight", Boolean.toString(botFight));
            p.setProperty("botAutoGear", Boolean.toString(botAutoGear));
            p.setProperty("botAutoBase", Boolean.toString(botAutoBase));
            p.setProperty("botNametag", Boolean.toString(botNametag));
            p.setProperty("botXray", Boolean.toString(botXray));
            p.setProperty("botEco", Integer.toString(botEco));
            p.setProperty("botStartKit", Integer.toString(botStartKit));
            p.setProperty("botSendEvery", Integer.toString(botSendEvery));
            p.setProperty("botMine", botMine);
            p.setProperty("afkReturn", Boolean.toString(afkReturn));
            p.setProperty("afkFood", Boolean.toString(afkFood));
            p.setProperty("afkNightHome", Boolean.toString(afkNightHome));
            p.setProperty("afkRespawn", Boolean.toString(afkRespawn));
            p.setProperty("liveHud", Boolean.toString(liveHud));
            p.setProperty("bigTitle", Boolean.toString(bigTitle));
            p.setProperty("goalStep", Integer.toString(goalStep));
            p.setProperty("quickPhrases", quickPhrases);
            p.setProperty("giveawayWord", giveawayWord);
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
