package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * XYOPlay main window. Every clickable thing is registered as a {@link Hit} while it is drawn, so what you see
 * and what you can click always match, at any window size. Layout adapts: sidebar collapses to an icon rail on
 * narrow windows and the header/player get tighter on short ones.
 */
public final class MusicScreen extends Screen {
    private final Screen parent;

    private static final int TTS_FIXED = 13;
    private int ttsTarget;   // 0 = suara penonton LIVE, 1 = suara chat sendiri
    private static final String TTS_SAMPLE = "Halo semuanya, ini tes suara. Terima kasih sudah menonton.";

    private enum Mode {
        LOCAL("Pustaka", "Pustaka lokal", "Koleksi audio di perangkatmu", 0),
        YOUTUBE("YouTube", "Cari YouTube", "Cari lagu lalu klik hasilnya untuk mengunduh audio", 1),
        TIKTOK("TikTok", "Cari TikTok", "Cari kata kunci, @akun, #hashtag, atau link video TikTok", 2),
        SPOTIFY("Spotify", "Cari Spotify", "Cari lagu lalu klik hasilnya untuk mengunduh audionya", 11),
        LINK("Link", "Unduh via link", "Unduh audio dari link YouTube atau TikTok", 3),
        LIVE("LIVE", "TikTok LIVE", "Bacakan komentar LIVE dengan TTS lokal gratis", 4),
        TTS("Suara", "Suara & TTS", "Pilih suara gratis, atur volume, kecepatan, dan mode chat jadi suara", 12),
        ACTIONS("Aksi", "Aksi & Chaos", "Follow, gift, dan komentar memunculkan mob, TNT, item, dan efek", 13),
        GIFTS("Gift", "Katalog gift", "Nama, harga koin, dan hadiah tiap gift", 19),
        GIVEAWAY("Undian", "Undian penonton", "Penonton komentar kata kunci untuk ikut, lalu undi pemenang", 20),
        PHRASES("Frasa", "Frasa cepat", "Klik untuk membacakan kalimat siap pakai lewat TTS LIVE", 21),
        BOT("Bot", "Bot & AFK", "Asisten otomatis: ikut, tambang, buat alat, bangun base, dan mode AFK", 22),
        SFX("Efek", "Efek suara", "Suara seru saat follow, gift, tap, target, dan undian (bisa diganti dengan sound meme kamu)", 12),
        MONITOR("Monitor", "Monitor event", "Hasil tiap gift/follow: antre, berhasil, atau gagal", 18),
        FILTER("Filter", "Filter kata kasar", "Kata di daftar ini tidak dibacakan dan disensor jadi ***", 14),
        BANNED("Ban", "Akun diblokir", "Komentar, follow, dan gift dari akun ini diabaikan sepenuhnya", 15),
        STATS("Statistik", "Statistik LIVE", "Ringkasan penonton, gift, dan peringkat pengirim gift", 16),
        WAYPOINTS("Titik", "Titik & koordinat", "Simpan lokasi, pandu arah, dan penanda tempat mati", 17),
        SETTINGS("Setelan", "Pengaturan", "HUD, pemutaran, dan pintasan", 5),
        TOOLS("Alat", "Alat & proses", "Kelola FFmpeg, yt-dlp, dan diagnostik", 6),
        QUEUE("Antrean", "Antrean lagu", "Lagu berikutnya diputar sesuai urutan antrean", 7),
        FAVORITES("Favorit", "Lagu favorit", "Koleksi lagu yang ditandai favorit", 8),
        HISTORY("Riwayat", "Riwayat pemutaran", "Lagu yang terakhir diputar", 9),
        PLAYLISTS("Playlist", "Playlist lokal", "Buat playlist dan tambahkan lagu lokal", 10);

        final String label, title, subtitle; final int icon;
        Mode(String label, String title, String subtitle, int icon) { this.label = label; this.title = title; this.subtitle = subtitle; this.icon = icon; }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private Mode mode = Mode.LOCAL;
    private final EnumMap<Mode, String> texts = new EnumMap<>(Mode.class);
    private final List<Hit> hits = new ArrayList<>();
    private List<Path> visibleTracks = new ArrayList<>();
    private boolean inputFocused, allSelected, draggingVolume, draggingSeek, draggingScroll;
    private int cursorPos, inputScroll, scroll, clickX, clickY;
    private int navScroll;
    private double seekPreview;
    private String notice = "";
    private long noticeUntil;
    private Path pendingDelete;
    private long pendingDeleteUntil;
    private boolean editingMetadata;
    private Path metadataPath;
    private String metadataPreviousFilter = "";
    private String selectedPlaylist;

    // layout, recomputed every frame by layout()
    private int px, py, pw, ph, sideW, cx, cw, listTop, listBottom, playerY, playerH, footerY, rowH, itemW;
    private boolean rail, compact;
    private int volX, volW, barX, barW, trackY, trackH;

    public MusicScreen() { this(null); }
    public MusicScreen(Screen parent) { this(parent, false); }
    public MusicScreen(Screen parent, boolean openSettings) {
        super(Component.literal("XYOPlay Music"));
        this.parent = parent;
        if (openSettings) this.mode = Mode.SETTINGS;
        refreshVisible();
    }

    // ------------------------------------------------------------------ data

    private void refreshVisible() { MusicLibrary.refresh(); filterVisible(); }

    private void filterVisible() {
        if (editingMetadata) return;
        String q = texts.getOrDefault(Mode.LOCAL, "").toLowerCase(Locale.ROOT).trim();
        visibleTracks = MusicLibrary.tracks().stream().filter(p -> {
            MusicLibraryData.Metadata metadata = MusicLibraryData.metadata(p);
            return metadata.title().toLowerCase(Locale.ROOT).contains(q) || metadata.artist().toLowerCase(Locale.ROOT).contains(q);
        }).toList();
        clampScroll();
    }

    private boolean hasField() { return mode == Mode.LOCAL || mode == Mode.YOUTUBE || mode == Mode.TIKTOK || mode == Mode.SPOTIFY || mode == Mode.LINK || mode == Mode.LIVE || mode == Mode.FILTER || mode == Mode.BANNED || mode == Mode.ACTIONS || mode == Mode.GIVEAWAY || mode == Mode.PHRASES || mode == Mode.BOT || mode == Mode.WAYPOINTS || (mode == Mode.PLAYLISTS && selectedPlaylist == null); }
    private String text() { return texts.getOrDefault(mode, ""); }
    private void text(String value) { texts.put(mode, value); if (mode == Mode.LOCAL && !editingMetadata) { filterVisible(); scroll = 0; } }

    private void setMode(Mode next) {
        if (mode == Mode.LOCAL && editingMetadata && next != Mode.LOCAL) {
            editingMetadata = false;
            metadataPath = null;
            texts.put(Mode.LOCAL, metadataPreviousFilter);
        }
        mode = next; scroll = 0; allSelected = false; inputFocused = false; inputScroll = 0;
        int visibleModes = visibleModes();
        if (next.ordinal() < navScroll) navScroll = next.ordinal();
        else if (next.ordinal() >= navScroll + visibleModes) navScroll = next.ordinal() - visibleModes + 1;
        if (next == Mode.LIVE && !texts.containsKey(Mode.LIVE)) texts.put(Mode.LIVE, ClientSettings.tiktokUsername == null ? "" : ClientSettings.tiktokUsername);
        if (next == Mode.LOCAL) refreshVisible();
        cursorPos = text().length();
    }

    private void clearField() { texts.put(mode, ""); cursorPos = 0; inputScroll = 0; allSelected = false; }

    private void notice(String message) { notice = message; noticeUntil = System.currentTimeMillis() + 3200; }

    // ---------------------------------------------------------------- list model

    private int count() {
        return switch (mode) {
            case LOCAL -> visibleTracks.size();
            case YOUTUBE -> YoutubeService.results(YoutubeService.Source.YOUTUBE).size();
            case TIKTOK -> YoutubeService.results(YoutubeService.Source.TIKTOK).size();
            case SPOTIFY -> YoutubeService.results(YoutubeService.Source.SPOTIFY).size();
            case LINK -> 2;
            case LIVE -> 3 + Math.max(1, TikTokLiveService.comments().size());
            case SETTINGS -> 28;
            case TTS -> TTS_FIXED + TtsCatalog.VOICES.size();
            case ACTIONS -> 17 + Math.max(1, LiveActions.triggers().size());
            case GIFTS -> 1 + Math.max(1, GiftBook.entries().size());
            case GIVEAWAY -> 4 + Math.max(1, Giveaway.winners().size());
            case PHRASES -> 1 + phrases().size();
            case BOT -> 30 + Math.max(1, Bots.BOTS.size() + (Bots.afk != null ? 1 : 0));
            case SFX -> 3 + MemeSounds.KINDS.size();
            case MONITOR -> 1 + Math.max(1, LiveActions.log().size());
            case FILTER -> 4 + Math.max(1, LiveFilter.words().size());
            case BANNED -> 1 + Math.max(1, LiveFilter.banned().size());
            case STATS -> 3 + Math.max(1, TikTokLiveService.topGifters(10).size());
            case WAYPOINTS -> 3 + Math.max(1, Waypoints.points().size());
            case TOOLS -> 4;
            case QUEUE -> MusicLibraryData.queued().size();
            case FAVORITES -> MusicLibraryData.favorites().size();
            case HISTORY -> MusicLibraryData.history().size();
            case PLAYLISTS -> selectedPlaylist == null ? MusicLibraryData.playlists().size() : playlistTracks().size();
        };
    }

    private int heightOf(int i) {
        return switch (mode) {
            case LOCAL, QUEUE, FAVORITES, HISTORY -> rowH;
            case PLAYLISTS -> selectedPlaylist == null ? 34 : rowH;
            case YOUTUBE, TIKTOK, SPOTIFY -> compact ? 30 : 34;
            case LINK -> i == 0 ? 54 : 50;
            case LIVE -> i == 0 ? 62 : i == 1 ? 46 : i == 2 ? 42 : 26;
            case SETTINGS -> 42;
            case TTS -> i < TTS_FIXED ? 42 : 24;
            case ACTIONS -> i == 11 ? 52 : i >= 17 ? 28 : 42;
            case GIFTS -> i == 0 ? 44 : 32;
            case GIVEAWAY -> i < 4 ? 42 : 26;
            case PHRASES -> i == 0 ? 44 : 28;
            case BOT -> i == 0 ? 58 : i == 24 ? 26 : i >= 30 ? 32 : 42;
            case SFX -> i == 2 ? 58 : i < 2 ? 42 : 32;
            case MONITOR -> i == 0 ? 44 : 32;
            case FILTER -> i >= 4 ? 26 : 42;
            case BANNED -> i == 0 ? 42 : 26;
            case STATS -> i == 0 ? 50 : i == 1 ? 40 : i == 2 ? 24 : 26;
            case WAYPOINTS -> i == 0 ? 42 : i < 3 ? 42 : 32;
            case TOOLS -> i == 3 ? 72 : i >= 2 ? 60 : 42;
        };
    }

    private List<Path> libraryTracks() {
        return switch (mode) {
            case LOCAL -> visibleTracks;
            case QUEUE -> MusicLibraryData.queued();
            case FAVORITES -> MusicLibraryData.favorites();
            case HISTORY -> MusicLibraryData.history();
            case PLAYLISTS -> selectedPlaylist == null ? List.of() : playlistTracks();
            default -> List.of();
        };
    }

    private List<Path> playlistTracks() {
        return MusicLibraryData.playlists().stream()
                .filter(playlist -> playlist.name().equals(selectedPlaylist))
                .map(MusicLibraryData.Playlist::tracks)
                .findFirst().orElse(List.of());
    }

    private int maxScroll() {
        int viewH = listBottom - listTop, acc = 0, idx = count();
        while (idx > 0 && acc + heightOf(idx - 1) <= viewH) { acc += heightOf(idx - 1); idx--; }
        return idx;
    }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, maxScroll())); }
    private int visibleItems() {
        int viewH = listBottom - listTop, y = 0, n = 0;
        for (int i = scroll; i < count(); i++) { int h = heightOf(i); if (y + h > viewH) break; y += h; n++; }
        return Math.max(1, n);
    }

    // ---------------------------------------------------------------- layout

    private void layout() {
        int m = (width < 380 || height < 230) ? 3 : 10;
        pw = Math.min(780, width - 2 * m); ph = Math.min(430, height - 2 * m);
        px = (width - pw) / 2; py = (height - ph) / 2;
        rail = pw < 440; compact = ph < 210;
        sideW = rail ? 28 : 122;
        int pad = rail ? 6 : 14;
        cx = px + sideW + pad; cw = Math.max(80, px + pw - pad - cx);
        playerH = 46; int footerH = 12;
        footerY = py + ph - footerH - 3;
        playerY = footerY - playerH - 2;
        listBottom = playerY - 5;
        int header = hasField() ? (compact ? 31 : 68) : (compact ? 24 : 46);
        listTop = py + header;
        rowH = compact ? 24 : 34;
    }

    private int visibleModes() {
        int available = ph - (rail ? 30 : 42) - 10;
        int targetVisible = Math.min(Mode.values().length, 7);
        int tabH = Math.max(9, Math.min(22, available / targetVisible - 2));
        return Math.max(1, available / (tabH + 2));
    }

    // --------------------------------------------------------------- rendering

    private void hit(int x, int y, int w, int h, Runnable action) { hits.add(new Hit(x, y, w, h, action)); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        layout();
        hits.clear();
        clampScroll();
        g.fill(0, 0, width, height, Ui.BACKDROP);

        // panel with soft shadow and accent line
        Ui.rect(g, px - 2, py - 1, pw + 4, ph + 4, 8, 0x30000000);
        Ui.box(g, px, py, pw, ph, 7, Ui.PANEL, Ui.BORDER);
        Ui.hGrad(g, px + 7, py, pw - 14, 2, Ui.ACCENT, Ui.ACCENT2);

        drawSidebar(g, mouseX, mouseY);
        drawHeader(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);
        drawPlayer(g, mouseX, mouseY);
        drawFooter(g);
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mx, int my) {
        Ui.rect(g, px + 4, py + 5, sideW, ph - 9, 6, Ui.SIDEBAR);
        boolean playing = LocalAudioPlayer.isPlayingOrPaused() && !LocalAudioPlayer.isPaused();
        int brandY = py + 12;
        if (rail) {
            Ui.eq(g, px + 4 + (sideW - 10) / 2, brandY, 10, 10, Ui.ACCENT, playing);
        } else {
            Ui.eq(g, px + 16, brandY, 11, 11, Ui.ACCENT, playing);
            g.text(font, "XYOPlay", px + 33, brandY, Ui.TEXT, false);
            g.text(font, "by RFDEVV", px + 33, brandY + 11, Ui.DIM, false);
        }
        Mode[] modes = Mode.values();
        int startY = py + (rail ? 30 : 42);
        int available = ph - (startY - py) - 10;
        int targetVisible = Math.min(modes.length, 7);
        int tabH = Math.max(9, Math.min(22, available / targetVisible - 2));
        int visibleModes = Math.max(1, available / (tabH + 2));
        navScroll = Math.max(0, Math.min(navScroll, modes.length - visibleModes));
        if (mode.ordinal() < navScroll) navScroll = mode.ordinal();
        else if (mode.ordinal() >= navScroll + visibleModes) navScroll = mode.ordinal() - visibleModes + 1;
        for (int i = navScroll; i < Math.min(modes.length, navScroll + visibleModes); i++) {
            Mode m = modes[i];
            int ty = startY + (i - navScroll) * (tabH + 2), tx = px + 8, tw = sideW - 8;
            boolean sel = m == mode, hover = Ui.in(mx, my, tx, ty, tw, tabH);
            if (sel) { Ui.rect(g, tx, ty, tw, tabH, 5, Ui.CARD_ACTIVE); g.fill(tx, ty + 3, tx + 2, ty + tabH - 3, Ui.ACCENT); }
            else if (hover) Ui.rect(g, tx, ty, tw, tabH, 5, Ui.CARD);
            int color = sel ? Ui.ACCENT : hover ? Ui.TEXT : Ui.MUTED;
            int iy = ty + (tabH - 10) / 2;
            if (rail) Ui.tabIcon(g, m.icon, tx + (tw - 10) / 2, iy, color);
            else {
                Ui.tabIcon(g, m.icon, tx + 9, iy, color);
                g.text(font, m.label, tx + 26, ty + (tabH - 8) / 2, color, false);
            }
            hit(tx, ty, tw, tabH, () -> setMode(m));
        }
        if (modes.length > visibleModes) {
            String indicator = navScroll + visibleModes < modes.length ? "v" : "^";
            g.text(font, indicator, px + sideW / 2, py + ph - 18, Ui.DIM, false);
        }
        if (!rail && ph >= 300) {
            g.fill(px + 20, py + ph - 38, px + 24, py + ph - 34, Ui.OK);
            g.text(font, "Mode client", px + 30, py + ph - 40, Ui.OK, false);
            g.text(font, "Audio di perangkatmu", px + 20, py + ph - 27, Ui.DIM, false);
        }
    }

    private String actionLabel() {
        return switch (mode) {
            case LOCAL -> editingMetadata ? "Simpan" : "Pindai";
            case YOUTUBE, TIKTOK, SPOTIFY -> "Cari";
            case LINK -> "Unduh";
            case LIVE -> "Simpan";
            case FILTER, ACTIONS -> "Tambah";
            case BANNED -> "Ban";
            case GIVEAWAY -> "Atur kata";
            case BOT -> "Skin";
            case PHRASES -> "Tambah";
            case WAYPOINTS -> "Simpan";
            case PLAYLISTS -> selectedPlaylist == null ? "Buat" : "Kembali";
            default -> null;
        };
    }

    private void doAction() {
        switch (mode) {
            case LOCAL -> {
                if (editingMetadata) {
                    String[] fields = text().split("\\|", 2);
                    try {
                        MusicLibraryData.setMetadata(metadataPath, fields[0], fields.length > 1 ? fields[1] : "");
                        editingMetadata = false;
                        metadataPath = null;
                        texts.put(Mode.LOCAL, metadataPreviousFilter);
                        refreshVisible();
                        inputFocused = false;
                        allSelected = false;
                        cursorPos = text().length();
                        notice("Metadata lagu disimpan");
                    } catch (IllegalArgumentException e) {
                        notice("Gagal menyimpan metadata: " + e.getMessage());
                    }
                } else {
                    refreshVisible();
                    notice("Pustaka diperbarui · " + visibleTracks.size() + " lagu");
                }
            }
            case YOUTUBE -> { scroll = 0; YoutubeService.search(YoutubeService.Source.YOUTUBE, text()); }
            case TIKTOK -> { scroll = 0; YoutubeService.search(YoutubeService.Source.TIKTOK, text()); }
            case SPOTIFY -> { scroll = 0; YoutubeService.search(YoutubeService.Source.SPOTIFY, text()); }
            case LINK -> YoutubeService.downloadUrl(text(), MusicLibrary.folder());
            case LIVE -> { ClientSettings.tiktokUsername = text().trim().replaceFirst("^@", ""); ClientSettings.save(); notice("Username LIVE disimpan"); }
            case FILTER -> {
                int added = 0;
                for (String part : text().split("[,\\n]")) if (LiveFilter.addWord(part)) added++;
                clearField();
                notice(added > 0 ? added + " kata ditambahkan ke filter" : "Tidak ada kata baru (kosong atau sudah ada)");
            }
            case BANNED -> {
                int added = 0;
                for (String part : text().split("[,\\n]")) {
                    if (LiveFilter.ban(part)) { added++; TikTokLiveService.purgeUser(LiveFilter.normalizeUser(part)); }
                }
                clearField();
                notice(added > 0 ? added + " akun diblokir" : "Username kosong, tidak valid, atau sudah diblokir");
            }
            case ACTIONS -> {
                String result = LiveActions.add(text());
                if (result.startsWith("OK:")) clearField();
                notice(result);
            }
            case BOT -> {
                String n = text().strip();
                if (!n.matches("[A-Za-z0-9_]{3,16}")) { notice("Nama akun Minecraft: 3–16 huruf/angka/_"); return; }
                ClientSettings.botSkinName = n; ClientSettings.botSkin = Bots.SKINS.length - 1; ClientSettings.save(); clearField();
                Bots.refreshLook(); notice("Skin bot: " + n + " (butuh internet untuk mengambil skin)");
            }
            case GIVEAWAY -> {
                String w = text().strip().toLowerCase();
                if (!w.startsWith("!")) w = "!" + w;
                if (!w.matches("![a-z0-9_]{1,24}")) { notice("Kata harus huruf/angka, mis. !ikut"); return; }
                ClientSettings.giveawayWord = w; ClientSettings.save(); clearField();
                notice("Kata undian: " + w);
            }
            case PHRASES -> {
                String p = text().strip().replace("|", " ");
                if (p.isEmpty()) { notice("Ketik kalimatnya dulu"); return; }
                if (p.length() > 140) p = p.substring(0, 140);
                ClientSettings.quickPhrases = ClientSettings.quickPhrases.isEmpty() ? p : ClientSettings.quickPhrases + "|" + p;
                ClientSettings.save(); clearField();
                notice("Frasa disimpan");
            }
            case WAYPOINTS -> {
                Waypoints.Point point = Waypoints.addHere(text());
                if (point == null) notice("Masuk ke dunia dulu untuk menyimpan titik");
                else { clearField(); notice("Titik \"" + point.name() + "\" disimpan di " + Waypoints.coords(point)); }
            }
            case PLAYLISTS -> {
                if (selectedPlaylist == null) {
                    String name = text().trim();
                    if (MusicLibraryData.createPlaylist(name)) {
                        selectedPlaylist = name;
                        texts.put(Mode.PLAYLISTS, "");
                        scroll = 0;
                        notice("Playlist dibuat: " + name);
                    } else notice("Masukkan nama playlist baru (maks. 48 karakter)");
                } else {
                    selectedPlaylist = null;
                    scroll = 0;
                }
            }
            default -> { }
        }
    }

    private void drawHeader(GuiGraphicsExtractor g, int mx, int my) {
        String label = actionLabel();
        int btnW = label == null ? 0 : Math.max(50, font.width(label) + 22);
        if (!compact) {
            g.text(font, editingMetadata ? "Edit metadata" : mode.title, cx, py + 13, Ui.TEXT, false);
            g.text(font, Ui.fit(font, editingMetadata ? "Isi judul dan artis dipisahkan dengan |" : mode.subtitle, cw - btnW - 12 - (isResultMode() ? 74 : 0) - (mode == Mode.TIKTOK ? 100 : mode == Mode.LOCAL && !editingMetadata ? 64 : 0)), cx, py + 26, Ui.MUTED, false);
            int by = py + 10;
            if (label != null) {
                button(g, cx + cw - btnW, by, btnW, 22, label, true, mx, my, this::doAction);
                if (mode == Mode.LOCAL && !editingMetadata) button(g, cx + cw - btnW - 64, by, 60, 22, "Folder", false, mx, my, this::openFolder);
                int extra = 0;
                if (isResultMode()) {
                    extra = 74;
                    final YoutubeService.Source src = mode == Mode.TIKTOK ? YoutubeService.Source.TIKTOK : mode == Mode.SPOTIFY ? YoutubeService.Source.SPOTIFY : YoutubeService.Source.YOUTUBE;
                    button(g, cx + cw - btnW - extra, by, 70, 22, "Putar #1", false, mx, my, () -> {
                        scroll = 0;
                        YoutubeService.search(src, text(), true);
                        notice("Mencari lalu memutar hasil teratas…");
                    });
                }
                if (mode == Mode.TIKTOK) button(g, cx + cw - btnW - extra - 100, by, 96, 22, "Urut: " + YoutubeService.tiktokSortLabel(), false, mx, my,
                        () -> notice("Urutan TikTok: " + YoutubeService.cycleTikTokSort()));
            }
            if (hasField()) field(g, cx, py + 40, cw, 22, mx, my);
        } else {
            if (hasField()) {
                int fieldW = cw - (btnW > 0 ? btnW + 4 : 0);
                field(g, cx, py + 6, fieldW, 20, mx, my);
                if (label != null) button(g, cx + cw - btnW, py + 6, btnW, 20, label, true, mx, my, this::doAction);
            } else g.text(font, mode.title, cx, py + 9, Ui.TEXT, false);
        }
    }

    private void field(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my) {
        Ui.box(g, x, y, w, h, 5, inputFocused ? 0xFF18233A : Ui.CARD, inputFocused ? Ui.ACCENT : Ui.BORDER);
        Ui.iconSearch(g, x + 7, y + (h - 9) / 2, inputFocused ? Ui.ACCENT : Ui.MUTED, inputFocused ? 0xFF18233A : Ui.CARD);
        int textX = x + 21, availW = w - 29, textY = y + (h - 8) / 2;
        String value = text();
        cursorPos = Math.max(0, Math.min(cursorPos, value.length()));
        inputScroll = Math.max(0, Math.min(inputScroll, value.length()));
        if (cursorPos < inputScroll) inputScroll = cursorPos;
        while (inputScroll < cursorPos && font.width(value.substring(inputScroll, cursorPos)) > availW) inputScroll++;
        int end = inputScroll;
        while (end < value.length() && font.width(value.substring(inputScroll, end + 1)) <= availW) end++;
        String visible = value.substring(inputScroll, end);
        if (value.isEmpty()) g.text(font, Ui.fit(font, placeholder(), availW), textX, textY, Ui.DIM, false);
        else {
            if (allSelected && inputFocused) g.fill(textX - 1, y + 4, textX + font.width(visible) + 1, y + h - 4, 0x6638BDF8);
            g.text(font, visible, textX, textY, Ui.TEXT, false);
        }
        if (inputFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int caret = textX + font.width(value.substring(inputScroll, Math.min(cursorPos, end)));
            g.fill(caret, y + 4, caret + 1, y + h - 4, Ui.TEXT);
        }
        final int fieldTextX = textX;
        hit(x, y, w, h, () -> {
            inputFocused = true; allSelected = false;
            String t = text();
            int target = clickX - fieldTextX, pos = inputScroll, prev = 0;
            while (pos < t.length()) {
                int wNext = font.width(t.substring(inputScroll, pos + 1));
                if (prev + (wNext - prev) / 2 >= target) break;
                prev = wNext; pos++;
            }
            cursorPos = pos;
        });
    }

    private String placeholder() {
        return switch (mode) {
            case LOCAL -> editingMetadata ? "Judul | Artis" : "Cari di pustaka lokal…";
            case YOUTUBE -> "Ketik judul lagu / artis…";
            case TIKTOK -> "Kata kunci · @akun · #hashtag · link video";
            case SPOTIFY -> "Judul lagu atau artis…";
            case LINK -> "https://www.youtube.com/watch?v=… atau link TikTok";
            case LIVE -> "Username TikTok LIVE (tanpa @ juga boleh)";
            case FILTER -> "Kata kasar (pisahkan dengan koma)…";
            case BANNED -> "Username TikTok yang diblokir (tanpa @)";
            case GIVEAWAY -> "Kata untuk ikut undian, mis. !ikut";
            case BOT -> "Nama akun Minecraft untuk skin bot (mis. Notch)";
            case PHRASES -> "Kalimat baru untuk disimpan (dibacakan lewat suara chat sendiri)";
            case ACTIONS -> "pemicu=mob   contoh: follow=zombie · mawar=creeper*2";
            case WAYPOINTS -> "Nama titik (kosongkan = otomatis)";
            case PLAYLISTS -> "Nama playlist baru…";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- list

    private void drawList(GuiGraphicsExtractor g, int mx, int my) {
        int n = count();
        boolean bar = maxScroll() > 0;
        itemW = cw - (bar ? 9 : 0);
        if (n == 0 && (mode == Mode.LOCAL || mode == Mode.QUEUE || mode == Mode.FAVORITES || mode == Mode.HISTORY || mode == Mode.PLAYLISTS || isResultMode())) {
            String l1 = switch (mode) {
                case LOCAL -> "Belum ada file audio lokal.";
                case QUEUE -> "Antrean masih kosong.";
                case FAVORITES -> "Belum ada lagu favorit.";
                case HISTORY -> "Belum ada riwayat pemutaran.";
                case PLAYLISTS -> selectedPlaylist == null ? "Belum ada playlist." : "Playlist ini belum berisi lagu.";
                case YOUTUBE -> "Belum ada hasil pencarian.";
                case SPOTIFY -> "Belum ada hasil lagu.";
                default -> "Belum ada hasil TikTok.";
            };
            String l2 = switch (mode) {
                case LOCAL -> "Taruh audio di folder xyoplay/music lalu klik Pindai.";
                case QUEUE -> "Tambahkan lagu dengan tombol + pada lagu di pustaka.";
                case FAVORITES -> "Tandai lagu dengan tombol Fav pada pustaka.";
                case HISTORY -> "Riwayat diisi otomatis saat lagu diputar.";
                case PLAYLISTS -> selectedPlaylist == null ? "Ketik nama playlist lalu klik Buat." : "Kembali ke playlist, lalu pilih lagu.";
                case YOUTUBE -> "Masukkan kata kunci lalu tekan Cari.";
                case SPOTIFY -> "Ketik judul atau artis, atau tempel link lagu Spotify, lalu tekan Cari.";
                default -> "Ketik kata kunci, @akun, #hashtag, atau link video lalu tekan Cari.";
            };
            g.text(font, Ui.fit(font, l1, cw), cx + 4, listTop + 10, Ui.TEXT, false);
            g.text(font, Ui.fit(font, l2, cw), cx + 4, listTop + 23, Ui.MUTED, false);
            return;
        }
        int y = listTop;
        for (int i = scroll; i < n; i++) {
            int h = heightOf(i);
            if (y + h > listBottom) break;
            drawItem(g, i, cx, y, itemW, h, mx, my);
            y += h;
        }
        if (bar) drawScrollbar(g, mx, my);
    }

    private boolean isResultMode() { return mode == Mode.YOUTUBE || mode == Mode.TIKTOK || mode == Mode.SPOTIFY; }
    private List<YoutubeService.Video> results() {
        return YoutubeService.results(switch (mode) {
            case TIKTOK -> YoutubeService.Source.TIKTOK;
            case SPOTIFY -> YoutubeService.Source.SPOTIFY;
            default -> YoutubeService.Source.YOUTUBE;
        });
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int mx, int my) {
        int x = cx + cw - 5, h = listBottom - listTop, max = maxScroll();
        Ui.rect(g, x, listTop, 4, h, 2, Ui.BORDER);
        int thumbH = Math.max(14, h * visibleItems() / Math.max(1, count()));
        int thumbY = listTop + (h - thumbH) * scroll / Math.max(1, max);
        Ui.rect(g, x, thumbY, 4, thumbH, 2, draggingScroll || Ui.in(mx, my, x - 3, listTop, 10, h) ? Ui.ACCENT : Ui.MUTED);
        hit(x - 4, listTop, 12, h, () -> { draggingScroll = true; scrollToY(clickY); });
    }

    private void scrollToY(double y) {
        int h = listBottom - listTop, max = maxScroll();
        scroll = Math.max(0, Math.min(max, (int) Math.round(max * ((y - listTop) / Math.max(1.0, h)))));
    }

    private void drawItem(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (mode) {
            case LOCAL, QUEUE, FAVORITES, HISTORY -> drawTrack(g, libraryTracks().get(i), i, x, y, w, h, mx, my);
            case PLAYLISTS -> {
                if (selectedPlaylist == null) drawPlaylist(g, i, x, y, w, h, mx, my);
                else drawTrack(g, libraryTracks().get(i), i, x, y, w, h, mx, my);
            }
            case YOUTUBE, TIKTOK, SPOTIFY -> drawResult(g, i, x, y, w, h, mx, my);
            case LINK -> drawLinkCard(g, i, x, y, w, h);
            case LIVE -> drawLive(g, i, x, y, w, h, mx, my);
            case SETTINGS -> drawSettings(g, i, x, y, w, h, mx, my);
            case TTS -> drawTts(g, i, x, y, w, h, mx, my);
            case ACTIONS -> drawActions(g, i, x, y, w, h, mx, my);
            case GIFTS -> drawGifts(g, i, x, y, w, h, mx, my);
            case GIVEAWAY -> drawGiveaway(g, i, x, y, w, h, mx, my);
            case PHRASES -> drawPhrases(g, i, x, y, w, h, mx, my);
            case BOT -> drawBot(g, i, x, y, w, h, mx, my);
            case SFX -> drawSfx(g, i, x, y, w, h, mx, my);
            case MONITOR -> drawMonitor(g, i, x, y, w, h, mx, my);
            case FILTER -> drawFilter(g, i, x, y, w, h, mx, my);
            case BANNED -> drawBanned(g, i, x, y, w, h, mx, my);
            case STATS -> drawStats(g, i, x, y, w, h, mx, my);
            case WAYPOINTS -> drawWaypoints(g, i, x, y, w, h, mx, my);
            case TOOLS -> drawTools(g, i, x, y, w, h, mx, my);
        }
    }

    private void drawTrack(GuiGraphicsExtractor g, Path p, int i, int x, int y, int w, int h, int mx, int my) {
        boolean active = p.equals(LocalAudioPlayer.currentPath()) && LocalAudioPlayer.isPlayingOrPaused();
        boolean hover = Ui.in(mx, my, x, y, w, h - 2);
        Ui.rect(g, x, y, w, h - 2, 5, active ? Ui.CARD_ACTIVE : hover ? Ui.CARD_HOVER : Ui.CARD);
        if (active) g.fill(x, y + 4, x + 2, y + h - 6, Ui.ACCENT);
        int cy = y + (h - 2 - 8) / 2;
        if (active) Ui.eq(g, x + 9, y + (h - 2 - 10) / 2, 10, 10, Ui.ACCENT, !LocalAudioPlayer.isPaused());
        else if (hover) Ui.iconPlay(g, x + 6, y, 16, h - 2, Ui.TEXT);
        else g.text(font, String.valueOf(i + 1), x + 12 - font.width(String.valueOf(i + 1)) / 2, cy, Ui.DIM, false);
        int right = x + w - 4;
        int deleteX = -1, deleteW = 0;
        if (mode == Mode.LOCAL || mode == Mode.FAVORITES || mode == Mode.HISTORY) {
            deleteW = 42;
            deleteX = right - deleteW;
            right = deleteX - 3;
        }
        int metadataX = -1;
        if (mode == Mode.LOCAL) {
            metadataX = right - 32;
            right = metadataX - 3;
        }
        int playlistX = -1;
        if (mode == Mode.LOCAL && selectedPlaylist != null) {
            playlistX = right - 26;
            right = playlistX - 3;
        }
        int queueX = -1;
        if (mode == Mode.QUEUE) {
            queueX = right - 38;
            right = queueX - 3;
        } else {
            queueX = right - 24;
            right = queueX - 3;
        }
        int favoriteX = right - 34;
        right = favoriteX - 3;
        String ext = extension(p);
        int extW = font.width(ext) + 10;
        int extX = right - extW;
        Ui.rect(g, extX, y + (h - 2 - 12) / 2, extW, 12, 3, Ui.BTN);
        g.text(font, ext, extX + 5, cy, Ui.MUTED, false);
        MusicLibraryData.Metadata metadata = MusicLibraryData.metadata(p);
        int titleY = metadata.artist().isBlank() || compact ? cy : cy - 4;
        g.text(font, Ui.fit(font, metadata.title(), Math.max(0, extX - x - 42)), x + 28, titleY, active ? Ui.ACCENT : Ui.TEXT, false);
        if (!metadata.artist().isBlank() && !compact) {
            g.text(font, Ui.fit(font, metadata.artist(), Math.max(0, extX - x - 42)), x + 28, cy + 5, Ui.MUTED, false);
        }
        hit(x, y, w, h - 2, () -> {
            if (mode == Mode.PLAYLISTS && selectedPlaylist != null) Playback.playPlaylist(selectedPlaylist, p);
            else Playback.playTrack(p);
            notice("Memutar: " + MusicLibrary.title(p));
        });
        button(g, favoriteX, y + (h - 2 - 16) / 2, 34, 16, MusicLibraryData.isFavorite(p) ? "Unfav" : "Fav", false, mx, my,
                () -> { MusicLibraryData.toggleFavorite(p); notice(MusicLibraryData.isFavorite(p) ? "Ditambahkan ke favorit" : "Dihapus dari favorit"); });
        if (mode == Mode.QUEUE) {
            button(g, queueX, y + (h - 2 - 16) / 2, 38, 16, "Hapus", false, mx, my, () -> {
                MusicLibraryData.removeQueued(p);
                notice("Lagu dihapus dari antrean");
            });
        } else if (mode == Mode.PLAYLISTS && selectedPlaylist != null) {
            button(g, queueX, y + (h - 2 - 16) / 2, 24, 16, "Pl-", false, mx, my, () -> {
                MusicLibraryData.removeFromPlaylist(selectedPlaylist, p);
                notice("Lagu dihapus dari playlist");
            });
        } else {
            button(g, queueX, y + (h - 2 - 16) / 2, 24, 16, "+", false, mx, my, () -> {
                notice(MusicLibraryData.enqueue(p)
                        ? "Ditambahkan ke antrean: " + MusicLibrary.title(p)
                        : "Lagu sudah ada di antrean");
            });
        }
        if (playlistX >= 0) button(g, playlistX, y + (h - 2 - 16) / 2, 26, 16, "Pl", false, mx, my, () -> {
            notice(MusicLibraryData.addToPlaylist(selectedPlaylist, p)
                    ? "Ditambahkan ke playlist " + selectedPlaylist
                    : "Lagu sudah ada di playlist");
        });
        if (metadataX >= 0) button(g, metadataX, y + (h - 2 - 16) / 2, 32, 16, "Meta", false, mx, my, () -> {
            MusicLibraryData.Metadata current = MusicLibraryData.metadata(p);
            editingMetadata = true;
            metadataPath = p;
            metadataPreviousFilter = texts.getOrDefault(Mode.LOCAL, "");
            texts.put(Mode.LOCAL, current.title() + " | " + current.artist());
            cursorPos = text().length();
            inputScroll = 0;
            inputFocused = true;
            allSelected = true;
            scroll = 0;
            notice("Ubah format: Judul | Artis");
        });
        if (deleteX >= 0) {
            boolean confirmDelete = p.equals(pendingDelete) && System.currentTimeMillis() < pendingDeleteUntil;
            button(g, deleteX, y + (h - 2 - 16) / 2, deleteW, 16, confirmDelete ? "Yakin?" : "Hapus", false, mx, my, () -> {
                if (!p.equals(pendingDelete) || System.currentTimeMillis() >= pendingDeleteUntil) {
                    pendingDelete = p;
                    pendingDeleteUntil = System.currentTimeMillis() + 4000;
                    notice("Klik Yakin? untuk menghapus " + MusicLibrary.title(p));
                    return;
                }
                try {
                    MusicLibrary.delete(p);
                    pendingDelete = null;
                    filterVisible();
                    notice("Lagu dihapus dari perangkat: " + MusicLibrary.title(p));
                } catch (java.io.IOException | IllegalArgumentException e) {
                    pendingDelete = null;
                    notice("Gagal menghapus lagu: " + e.getMessage());
                }
            });
        }
    }

    private void drawPlaylist(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        MusicLibraryData.Playlist playlist = MusicLibraryData.playlists().get(i);
        Ui.rect(g, x, y, w, h - 2, 5, Ui.in(mx, my, x, y, w, h - 2) ? Ui.CARD_HOVER : Ui.CARD);
        String countText = playlist.tracks().size() + " lagu";
        g.text(font, Ui.fit(font, playlist.name(), w - 100), x + 10, y + (h - 8) / 2, Ui.TEXT, false);
        g.text(font, countText, x + w - font.width(countText) - 50, y + (h - 8) / 2, Ui.MUTED, false);
        hit(x, y, w, h - 2, () -> { selectedPlaylist = playlist.name(); scroll = 0; });
        button(g, x + w - 42, y + (h - 16) / 2, 38, 16, "Hapus", false, mx, my, () -> {
            MusicLibraryData.deletePlaylist(playlist.name());
            notice("Playlist dihapus: " + playlist.name());
        });
    }

    private void drawResult(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        YoutubeService.Video v = results().get(i);
        boolean hover = Ui.in(mx, my, x, y, w, h - 2);
        Ui.rect(g, x, y, w, h - 2, 5, hover ? Ui.CARD_HOVER : Ui.CARD);
        int bs = Math.min(20, h - 8);
        Ui.rect(g, x + 6, y + (h - 2 - bs) / 2, bs, bs, 5, hover ? Ui.ACCENT : Ui.BTN);
        Ui.tabIcon(g, 3, x + 6 + (bs - 10) / 2, y + (h - 2 - 10) / 2, hover ? Ui.ON_ACCENT : Ui.MUTED);
        int tx = x + 6 + bs + 8, durW = font.width(v.duration());
        g.text(font, v.duration(), x + w - durW - 8, y + 6, Ui.MUTED, false);
        g.text(font, Ui.fit(font, v.title(), w - (tx - x) - durW - 18), tx, y + 6, Ui.TEXT, false);
        g.text(font, Ui.fit(font, v.uploader(), w - (tx - x) - 10), tx, y + 18, Ui.MUTED, false);
        hit(x, y, w, h - 2, () -> { YoutubeService.download(v, MusicLibrary.folder()); notice("Unduhan dimulai: " + v.title()); });
    }

    private void drawLinkCard(GuiGraphicsExtractor g, int i, int x, int y, int w, int h) {
        Ui.box(g, x, y, w - 0, h - 4, 5, Ui.CARD, Ui.BORDER);
        if (i == 0) {
            g.text(font, "Link yang didukung", x + 10, y + 8, Ui.TEXT, false);
            g.text(font, Ui.fit(font, "YouTube: youtube.com · youtu.be · m.youtube.com", w - 20), x + 10, y + 21, Ui.MUTED, false);
            g.text(font, Ui.fit(font, "TikTok: tiktok.com · vm.tiktok.com · vt.tiktok.com", w - 20), x + 10, y + 32, Ui.MUTED, false);
        } else {
            g.text(font, YoutubeService.isBusy() ? "Sedang memproses…" : "Status", x + 10, y + 8, YoutubeService.isBusy() ? Ui.ACCENT : Ui.TEXT, false);
            g.text(font, Ui.fit(font, YoutubeService.status(), w - 20), x + 10, y + 21, Ui.MUTED, false);
            double pr = YoutubeService.progress();
            if (pr >= 0) {
                Ui.rect(g, x + 10, y + 34, w - 20, 4, 2, Ui.TRACK);
                Ui.rect(g, x + 10, y + 34, Math.max(3, (int) ((w - 20) * Math.min(1, pr))), 4, 2, Ui.ACCENT);
            }
        }
    }

    private void drawLive(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i == 0) {
            boolean on = TikTokLiveService.isRunning();
            Ui.box(g, x, y, w, h - 4, 5, Ui.CARD, Ui.BORDER);
            String user = ClientSettings.tiktokUsername == null || ClientSettings.tiktokUsername.isBlank() ? "username belum diatur" : "@" + ClientSettings.tiktokUsername;
            String total = TikTokLiveService.totalComments() > 0 ? " · " + TikTokLiveService.totalComments() + " pesan" : "";
            g.text(font, Ui.fit(font, (on ? "AKTIF" : "SIAP") + " · " + user + total + " · Aksi " + (ClientSettings.actionsEnabled ? "ON" : "OFF"), w - 20), x + 10, y + 8, on ? Ui.OK : Ui.MUTED, false);
            g.text(font, Ui.fit(font, TikTokLiveService.status(), w - 20), x + 10, y + 21, Ui.TEXT, false);
            int bw = Math.min(84, (w - 20 - 12) / 4), by = y + 35;
            button(g, x + 10, by, bw, 18, "Mulai LIVE", true, mx, my, () -> {
                ClientSettings.tiktokUsername = text().trim().replaceFirst("^@", ""); ClientSettings.save();
                TikTokLiveService.start(ClientSettings.tiktokUsername);
                notice(!ClientSettings.actionsEnabled ? "LIVE dimulai · Aksi mob MATI (nyalakan di tab Aksi)" : !McCompat.inWorld() ? "LIVE dimulai · masuk dunia dulu agar aksi berjalan" : "Memulai pembaca TikTok LIVE");
            });
            button(g, x + 14 + bw, by, bw, 18, "Stop LIVE", false, mx, my, () -> { TikTokLiveService.stop(); notice("Pembaca TikTok LIVE dihentikan"); });
            button(g, x + 18 + bw * 2, by, bw, 18, ClientSettings.ttsEnabled ? "TTS: ON" : "TTS: OFF", false, mx, my, () -> {
                ClientSettings.ttsEnabled = !ClientSettings.ttsEnabled; ClientSettings.save();
                TikTokLiveService.sendConfig();
                notice("TTS " + (ClientSettings.ttsEnabled ? "aktif" : "nonaktif"));
            });
            button(g, x + 22 + bw * 3, by, bw, 18, "Bersihkan", false, mx, my, () -> {
                TikTokLiveService.clearComments();
                notice("Histori komentar & command dibersihkan");
            });
        } else if (i == 1) {
            Ui.box(g, x, y, w, h - 4, 5, Ui.CARD, Ui.BORDER);
            g.text(font, Ui.fit(font, "Suara: " + TtsCatalog.byKey(ClientSettings.ttsVoiceKey).label(), w - 20), x + 10, y + 6, Ui.TEXT, false);
            int sbw = Math.min(96, (w - 20 - 8) / 3), sby = y + 20;
            button(g, x + 10, sby, sbw, 18, "Vol: " + ClientSettings.ttsVolume + "%", false, mx, my, () -> {
                int[] vols = {50, 80, 100, 150, 200, 250, 300};
                int next = 100;
                for (int idx = 0; idx < vols.length; idx++) if (vols[idx] > ClientSettings.ttsVolume) { next = vols[idx]; break; }
                if (ClientSettings.ttsVolume >= 300) next = 50;
                ClientSettings.ttsVolume = next;
                ClientSettings.save();
                TikTokLiveService.sendConfig();
                notice("Volume TTS: " + ClientSettings.ttsVolume + "%");
            });
            button(g, x + 14 + sbw, sby, sbw, 18, "Ganti suara", false, mx, my, () -> setMode(Mode.TTS));
            button(g, x + 18 + sbw * 2, sby, sbw, 18, "Tes suara", true, mx, my, () -> { TikTokLiveService.speak(TTS_SAMPLE, 0); notice("Memutar tes suara…"); });
        } else if (i == 2) {
            actionCard(g, x, y, w, h, mx, my, "Paket Python untuk LIVE", "Pasang TikTokLive + edge-tts (suara Microsoft) + pyttsx3 lewat pip",
                    TikTokLiveService.isInstalling() ? "Memasang…" : "Instal paket",
                    () -> { TikTokLiveService.installPackages(); notice("Memasang paket Python… lihat status di atas"); });
        } else {
            List<TikTokLiveService.Comment> comments = TikTokLiveService.comments();
            Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
            int ty = y + (h - 2 - 8) / 2;
            if (comments.isEmpty()) { g.text(font, Ui.fit(font, "Komentar, follow, dan gift akan tampil di sini saat LIVE tersambung.", w - 16), x + 8, ty, Ui.DIM, false); return; }
            TikTokLiveService.Comment c = comments.get(Math.min(comments.size() - 1, i - 3));
            boolean canBan = !c.uid().isBlank();
            int banW = canBan ? 34 : 0;
            String user = Ui.fit(font, c.user(), w / 3);
            String kind = c.kind();
            boolean isGift = "gift".equals(kind) || c.text().startsWith("[GIFT]");
            boolean isFollow = "follow".equals(kind) || c.text().startsWith("[FOLLOW]");
            boolean isOther = "share".equals(kind) || "subscribe".equals(kind) || "join".equals(kind);
            int userCol = isGift ? 0xFFFFD700 : isFollow ? Ui.OK : isOther ? 0xFFC4B5FD : Ui.ACCENT;
            int textCol = isGift ? 0xFFFFEA75 : isFollow ? 0xFFA5D6A7 : isOther ? 0xFFDDD6FE : Ui.TEXT;
            g.text(font, user, x + 8, ty, userCol, false);
            int ux = x + 8 + font.width(user) + 6;
            g.text(font, Ui.fit(font, c.text(), w - (ux - x) - 8 - banW), ux, ty, textCol, false);
            if (canBan) {
                final String uid = c.uid();
                button(g, x + w - banW - 2, y + (h - 2 - 16) / 2, 32, 16, "Ban", false, mx, my, () -> {
                    LiveFilter.ban(uid);
                    TikTokLiveService.purgeUser(uid);
                    notice("@" + uid + " diblokir. Komentarnya tidak ditampilkan lagi");
                });
            }
        }
    }

    // ------------------------------------------------------------- settings

    private static final String[] HUD_NAMES = {"Mati", "Mini", "Kartu"};
    private static final String[] CORNER_NAMES = {"Kiri atas", "Kanan atas", "Kiri bawah", "Kanan bawah"};

    private void drawSettings(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> actionCard(g, x, y, w, h, mx, my, "Tampilan HUD", "Overlay kecil saat lagu diputar (Mati / Mini / Kartu)", HUD_NAMES[ClientSettings.hudMode],
                    () -> { ClientSettings.hudMode = (ClientSettings.hudMode + 1) % 3; ClientSettings.save(); notice("HUD: " + HUD_NAMES[ClientSettings.hudMode]); });
            case 1 -> actionCard(g, x, y, w, h, mx, my, "Posisi HUD", "Pilih sudut layar untuk overlay", CORNER_NAMES[ClientSettings.hudCorner],
                    () -> { ClientSettings.hudCorner = (ClientSettings.hudCorner + 1) % 4; ClientSettings.save(); notice("Posisi HUD: " + CORNER_NAMES[ClientSettings.hudCorner]); });
            case 2 -> toggleCard(g, x, y, w, h, mx, my, "Sembunyikan HUD otomatis", "Tampil 5 detik saat lagu berganti lalu memudar", ClientSettings.hudAutoHide,
                    () -> { ClientSettings.hudAutoHide = !ClientSettings.hudAutoHide; ClientSettings.save(); });
            case 3 -> toggleCard(g, x, y, w, h, mx, my, "Acak (shuffle)", "Putar lagu berikutnya secara acak", ClientSettings.shuffle,
                    () -> { ClientSettings.shuffle = !ClientSettings.shuffle; ClientSettings.save(); });
            case 4 -> toggleCard(g, x, y, w, h, mx, my, "Ulangi lagu (repeat)", "Putar ulang lagu yang sama setelah selesai", ClientSettings.repeat,
                    () -> { ClientSettings.repeat = !ClientSettings.repeat; ClientSettings.save(); });
            case 5 -> toggleCard(g, x, y, w, h, mx, my, "Bacakan komentar LIVE (TTS)", "Aktifkan atau matikan pembacaan suara komentar, follow, dan gift", ClientSettings.ttsEnabled,
                    () -> { ClientSettings.ttsEnabled = !ClientSettings.ttsEnabled; ClientSettings.save(); TikTokLiveService.sendConfig(); notice("TTS " + (ClientSettings.ttsEnabled ? "aktif" : "nonaktif")); });
            case 6 -> stepperCard(g, x, y, w, h, mx, my, "Volume TTS", "100% sudah keras dan rata; naikkan sampai 300% bila masih pelan",
                    ClientSettings.ttsVolume + "%", () -> changeVolume(-10), () -> changeVolume(10));
            case 7 -> actionCard(g, x, y, w, h, mx, my, "Suara TTS", TtsCatalog.byKey(ClientSettings.ttsVoiceKey).label(), "Pilih suara", () -> setMode(Mode.TTS));
            case 8 -> actionCard(g, x, y, w, h, mx, my, "Bahasa TTS LIVE", "Dipakai oleh suara Google dan kalimat ucapan terima kasih", ClientSettings.TTS_LANGS[ClientSettings.ttsLanguage],
                    () -> {
                        ClientSettings.ttsLanguage = (ClientSettings.ttsLanguage + 1) % ClientSettings.TTS_LANGS.length;
                        ClientSettings.save();
                        TikTokLiveService.sendConfig();
                        notice("Bahasa TTS: " + ClientSettings.TTS_LANGS[ClientSettings.ttsLanguage]);
                    });
            case 9 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                String key = XYOPlayClient.keyName();
                g.text(font, Ui.fit(font, "Tombol buka / tutup GUI: " + key, w - 20), x + 10, y + 8, key.equals("BELUM DIATUR") ? Ui.BAD : Ui.TEXT, false);
                g.text(font, Ui.fit(font, "Ubah di Options > Controls > Key Binds · atau ketik /xyoplay", w - 20), x + 10, y + 21, Ui.MUTED, false);
            }
            case 10 -> secretCard(g, x, y, w, h, mx, my, "Spotify Client ID", ClientSettings.spotifyClientId, v -> ClientSettings.spotifyClientId = v);
            case 11 -> secretCard(g, x, y, w, h, mx, my, "Spotify Client Secret", ClientSettings.spotifySecret, v -> ClientSettings.spotifySecret = v);
            case 12 -> secretCard(g, x, y, w, h, mx, my, "Euler Stream API key (LIVE, opsional)", ClientSettings.eulerApiKey, v -> ClientSettings.eulerApiKey = v);
            case 13 -> toggleCard(g, x, y, w, h, mx, my, "Chat jadi suara", "Apa pun yang diketik di chat Minecraft dibacakan lewat TTS (/xyoplay tts on|off)", ClientSettings.chatTts,
                    () -> { XYOPlayClient.setChatTts(!ClientSettings.chatTts); notice("Chat jadi suara " + (ClientSettings.chatTts ? "aktif" : "mati")); });
            case 14 -> toggleCard(g, x, y, w, h, mx, my, "Kirim juga ke chat server", "Mati = hanya suara (pesan tidak dikirim ke chat)", ClientSettings.chatTtsSend,
                    () -> { ClientSettings.chatTtsSend = !ClientSettings.chatTtsSend; ClientSettings.save(); });
            case 15 -> toggleCard(g, x, y, w, h, mx, my, "Banner event LIVE", "Tampilkan follow, gift, dan share sebagai banner di layar game", ClientSettings.liveBanner,
                    () -> { ClientSettings.liveBanner = !ClientSettings.liveBanner; ClientSettings.save(); });
            case 16 -> toggleCard(g, x, y, w, h, mx, my, "Info koordinat di layar", "Tampilkan XYZ, arah mata angin, dan jarak ke titik yang dipandu", ClientSettings.infoHud,
                    () -> { ClientSettings.infoHud = !ClientSettings.infoHud; ClientSettings.save(); });
            case 17 -> toggleCard(g, x, y, w, h, mx, my, "Tandai lokasi mati", "Simpan titik \"Mati\" otomatis agar mudah mengambil barang", ClientSettings.deathMarker,
                    () -> { ClientSettings.deathMarker = !ClientSettings.deathMarker; ClientSettings.save(); });
            case 18 -> toggleCard(g, x, y, w, h, mx, my, "Putar otomatis setelah unduh", "Lagu dari hasil pencarian langsung diputar begitu selesai diunduh", ClientSettings.autoPlayDownloads,
                    () -> { ClientSettings.autoPlayDownloads = !ClientSettings.autoPlayDownloads; ClientSettings.save(); });
            case 19 -> toggleCard(g, x, y, w, h, mx, my, "Penanda peta (pin) di layar", "Titik yang dipandu (termasuk lokasi mati) tampil sebagai pin yang terlihat dari jauh", ClientSettings.markerShow,
                    () -> { ClientSettings.markerShow = !ClientSettings.markerShow; ClientSettings.save(); });
            case 20 -> toggleCard(g, x, y, w, h, mx, my, "Hapus penanda saat sampai", "Pin hilang otomatis dalam 5 blok; titik Mati ikut dihapus", ClientSettings.markerClearOnArrive,
                    () -> { ClientSettings.markerClearOnArrive = !ClientSettings.markerClearOnArrive; ClientSettings.save(); });
            case 21 -> actionCard(g, x, y, w, h, mx, my, "Posisi kartu koordinat", "Sudut layar untuk kartu XYZ / kompas / jarak", CORNER_NAMES[ClientSettings.infoCorner],
                    () -> { ClientSettings.infoCorner = (ClientSettings.infoCorner + 1) % 4; ClientSettings.save(); notice("Kartu koordinat: " + CORNER_NAMES[ClientSettings.infoCorner]); });
            case 24 -> toggleCard(g, x, y, w, h, mx, my, "Panel LIVE di layar", "Top gifter dan bar target follow di sisi kanan layar", ClientSettings.liveHud,
                    () -> { ClientSettings.liveHud = !ClientSettings.liveHud; ClientSettings.save(); });
            case 25 -> toggleCard(g, x, y, w, h, mx, my, "Judul besar di layar", "Nama penonton muncul besar saat gift besar, tap event, dan target tercapai", ClientSettings.bigTitle,
                    () -> { ClientSettings.bigTitle = !ClientSettings.bigTitle; ClientSettings.save(); });
            case 26 -> stepperCard(g, x, y, w, h, mx, my, "Target follower", "Kelipatan target dari follower ASLI akunmu (diambil saat LIVE mulai); 0 = mati", ClientSettings.goalStep == 0 ? "mati" : Integer.toString(ClientSettings.goalStep),
                    () -> { ClientSettings.goalStep = Math.max(0, ClientSettings.goalStep - (ClientSettings.goalStep > 1000 ? 500 : 100)); ClientSettings.save(); },
                    () -> { ClientSettings.goalStep = Math.min(1000000, ClientSettings.goalStep + (ClientSettings.goalStep >= 1000 ? 500 : 100)); ClientSettings.save(); });
            case 27 -> stepperCard(g, x, y, w, h, mx, my, "Batas command di server", "Command per detik saat bermain di server (terlalu tinggi = dikick anti-spam). Dunia sendiri tidak terbatas", ClientSettings.mpCmdRate + "/dtk",
                    () -> { ClientSettings.mpCmdRate = Math.max(1, ClientSettings.mpCmdRate - 1); ClientSettings.save(); },
                    () -> { ClientSettings.mpCmdRate = Math.min(10, ClientSettings.mpCmdRate + 1); ClientSettings.save(); });
            case 22 -> toggleCard(g, x, y, w, h, mx, my, "Foto profil di banner", "Tampilkan foto profil penonton yang follow / kirim gift", ClientSettings.bannerAvatar,
                    () -> { ClientSettings.bannerAvatar = !ClientSettings.bannerAvatar; ClientSettings.save(); });
            default -> actionCard(g, x, y, w, h, mx, my, "Folder musik lokal", "Buka folder untuk menambah file audio", "Buka folder", this::openFolder);
        }
    }

    private void changeVolume(int delta) {
        ClientSettings.ttsVolume = Math.max(10, Math.min(300, ClientSettings.ttsVolume + delta));
        ClientSettings.save();
        TikTokLiveService.sendConfig();
        notice("Volume TTS: " + ClientSettings.ttsVolume + "%");
    }

    // ------------------------------------------------------------- new tabs

    /** A row with a title, a description and a  [-] value [+]  control. */
    private void stepperCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, String title, String desc, String value, Runnable minus, Runnable plus) {
        Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
        int bw = 24, vw = Math.max(44, font.width(value) + 12), by = y + (h - 2 - 20) / 2;
        int right = x + w - 8;
        int plusX = right - bw, valX = plusX - 4 - vw, minusX = valX - 4 - bw;
        g.text(font, Ui.fit(font, title, minusX - x - 16), x + 10, y + 8, Ui.TEXT, false);
        g.text(font, Ui.fit(font, desc, minusX - x - 16), x + 10, y + 21, Ui.MUTED, false);
        button(g, minusX, by, bw, 20, "-", false, mx, my, minus);
        Ui.rect(g, valX, by, vw, 20, 5, Ui.TRACK);
        g.text(font, value, valX + (vw - font.width(value)) / 2, by + 6, Ui.TEXT, false);
        button(g, plusX, by, bw, 20, "+", false, mx, my, plus);
    }

    private void emptyRow(GuiGraphicsExtractor g, int x, int y, int w, int h, String message) {
        Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
        g.text(font, Ui.fit(font, message, w - 16), x + 8, y + (h - 2 - 8) / 2, Ui.DIM, false);
    }

    private void drawTts(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i >= TTS_FIXED) {
            TtsCatalog.Voice v = TtsCatalog.VOICES.get(i - TTS_FIXED);
            boolean selected = v.key().equals(ttsKey()), hover = Ui.in(mx, my, x, y, w, h - 2);
            Ui.rect(g, x, y, w, h - 2, 5, selected ? Ui.CARD_ACTIVE : hover ? Ui.CARD_HOVER : Ui.CARD);
            if (selected) g.fill(x, y + 4, x + 2, y + h - 6, Ui.ACCENT);
            int ty = y + (h - 2 - 8) / 2;
            int groupW = font.width(v.group());
            g.text(font, Ui.fit(font, v.label(), w - groupW - 30), x + 10, ty, selected ? Ui.ACCENT : Ui.TEXT, false);
            g.text(font, v.group(), x + w - groupW - 10, ty, Ui.DIM, false);
            hit(x, y, w, h - 2, () -> {
                if (ttsTarget == 0) ClientSettings.ttsVoiceKey = v.key(); else ClientSettings.chatVoiceKey = v.key();
                ClientSettings.save();
                TikTokLiveService.sendConfig();
                TikTokLiveService.speak("Halo, ini suara " + v.label().split(" · ")[0] + ". Terima kasih sudah menonton.", 0, ttsTarget == 1);
                notice("Suara dipilih: " + v.label());
            });
            return;
        }
        switch (i) {
            case 0 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Atur suara untuk", 110), x + 10, y + 8, Ui.TEXT, false);
                g.text(font, Ui.fit(font, ttsTarget == 0 ? "Penonton LIVE: komentar, follow, gift" : "Chat sendiri: yang kamu ketik di Minecraft", w - 260), x + 10, y + 21, Ui.MUTED, false);
                int by = y + (h - 2 - 20) / 2;
                button(g, x + w - 8 - 112, by, 112, 20, "Chat sendiri", ttsTarget == 1, mx, my, () -> { ttsTarget = 1; notice("Mengatur suara chat sendiri"); });
                button(g, x + w - 8 - 112 - 4 - 112, by, 112, 20, "Penonton LIVE", ttsTarget == 0, mx, my, () -> { ttsTarget = 0; notice("Mengatur suara penonton"); });
            }
            case 1 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Suara: " + TtsCatalog.byKey(ttsKey()).label(), w - 190), x + 10, y + 8, Ui.TEXT, false);
                g.text(font, Ui.fit(font, "Edge butuh paket edge-tts (tab LIVE > Instal paket)", w - 190), x + 10, y + 21, Ui.MUTED, false);
                int by = y + (h - 2 - 20) / 2;
                button(g, x + w - 76, by, 68, 20, "Tes suara", true, mx, my, () -> { TikTokLiveService.speak(TTS_SAMPLE, 0, ttsTarget == 1); notice("Memutar tes suara…"); });
                button(g, x + w - 76 - 72, by, 68, 20, "Diam", false, mx, my, () -> { TikTokLiveService.stopSpeaking(); notice("Suara dihentikan"); });
            }
            case 2 -> stepperCard(g, x, y, w, h, mx, my, "Volume", "100% sudah keras dan rata; naikkan sampai 300% bila masih pelan",
                    (ttsTarget == 0 ? ClientSettings.ttsVolume : ClientSettings.chatVolume) + "%", () -> adjustVolume(-10), () -> adjustVolume(10));
            case 3 -> stepperCard(g, x, y, w, h, mx, my, "Kecepatan bicara", "Lebih lambat = lebih jelas dan tidak belibet",
                    signed(ttsTarget == 0 ? ClientSettings.ttsRate : ClientSettings.chatRate) + "%", () -> adjustRate(-5), () -> adjustRate(5));
            case 4 -> stepperCard(g, x, y, w, h, mx, my, "Nada suara", "Hanya untuk suara Microsoft Edge",
                    signed(ttsTarget == 0 ? ClientSettings.ttsPitch : ClientSettings.chatPitch) + " Hz", () -> adjustPitch(-5), () -> adjustPitch(5));
            case 5 -> actionCard(g, x, y, w, h, mx, my, "Bahasa TTS", "Dipakai suara Google dan kalimat terima kasih", ClientSettings.TTS_LANGS[ClientSettings.ttsLanguage],
                    () -> {
                        ClientSettings.ttsLanguage = (ClientSettings.ttsLanguage + 1) % ClientSettings.TTS_LANGS.length;
                        ClientSettings.save(); TikTokLiveService.sendConfig();
                        notice("Bahasa TTS: " + ClientSettings.TTS_LANGS[ClientSettings.ttsLanguage]);
                    });
            case 6 -> toggleCard(g, x, y, w, h, mx, my, "Sebut nama penonton", "\"Budi bilang, halo\" — mati: hanya isi komentar", ClientSettings.ttsReadNames,
                    () -> { ClientSettings.ttsReadNames = !ClientSettings.ttsReadNames; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 7 -> toggleCard(g, x, y, w, h, mx, my, "Ucapkan terima kasih follow", "Dibacakan saat ada yang follow", ClientSettings.ttsReadFollow,
                    () -> { ClientSettings.ttsReadFollow = !ClientSettings.ttsReadFollow; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 8 -> toggleCard(g, x, y, w, h, mx, my, "Ucapkan terima kasih gift", "Dibacakan saat ada gift (combo dibacakan sekali di akhir)", ClientSettings.ttsReadGift,
                    () -> { ClientSettings.ttsReadGift = !ClientSettings.ttsReadGift; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 9 -> toggleCard(g, x, y, w, h, mx, my, "Ucapkan terima kasih share", "Dibacakan saat LIVE dibagikan", ClientSettings.ttsReadShare,
                    () -> { ClientSettings.ttsReadShare = !ClientSettings.ttsReadShare; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 10 -> toggleCard(g, x, y, w, h, mx, my, "Sapa penonton yang masuk", "Bisa ramai di LIVE besar; default mati", ClientSettings.ttsReadJoin,
                    () -> { ClientSettings.ttsReadJoin = !ClientSettings.ttsReadJoin; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 11 -> toggleCard(g, x, y, w, h, mx, my, "Chat jadi suara", "Ketik apa saja di chat Minecraft, langsung jadi suara (tanpa perlu LIVE)", ClientSettings.chatTts,
                    () -> { XYOPlayClient.setChatTts(!ClientSettings.chatTts); notice("Chat jadi suara " + (ClientSettings.chatTts ? "aktif" : "mati")); });
            default -> toggleCard(g, x, y, w, h, mx, my, "Kirim juga ke chat server", "Mati = hanya suara, pesan tidak muncul di chat server", ClientSettings.chatTtsSend,
                    () -> { ClientSettings.chatTtsSend = !ClientSettings.chatTtsSend; ClientSettings.save(); });
        }
    }

    private String ttsKey() { return ttsTarget == 0 ? ClientSettings.ttsVoiceKey : ClientSettings.chatVoiceKey; }
    private static String signed(int v) { return (v > 0 ? "+" : "") + v; }

    private void adjustVolume(int delta) {
        if (ttsTarget == 0) ClientSettings.ttsVolume = Math.max(10, Math.min(300, ClientSettings.ttsVolume + delta));
        else ClientSettings.chatVolume = Math.max(10, Math.min(300, ClientSettings.chatVolume + delta));
        ClientSettings.save(); TikTokLiveService.sendConfig();
        notice("Volume: " + (ttsTarget == 0 ? ClientSettings.ttsVolume : ClientSettings.chatVolume) + "%");
    }

    private void adjustRate(int delta) {
        if (ttsTarget == 0) ClientSettings.ttsRate = Math.max(-50, Math.min(50, ClientSettings.ttsRate + delta));
        else ClientSettings.chatRate = Math.max(-50, Math.min(50, ClientSettings.chatRate + delta));
        ClientSettings.save(); TikTokLiveService.sendConfig();
        notice("Kecepatan: " + (ttsTarget == 0 ? ClientSettings.ttsRate : ClientSettings.chatRate) + "%");
    }

    private void adjustPitch(int delta) {
        if (ttsTarget == 0) ClientSettings.ttsPitch = Math.max(-50, Math.min(50, ClientSettings.ttsPitch + delta));
        else ClientSettings.chatPitch = Math.max(-50, Math.min(50, ClientSettings.chatPitch + delta));
        ClientSettings.save(); TikTokLiveService.sendConfig();
        notice("Nada: " + (ttsTarget == 0 ? ClientSettings.ttsPitch : ClientSettings.chatPitch) + " Hz");
    }

    private void changeRate(int delta) {
        ClientSettings.ttsRate = Math.max(-50, Math.min(50, ClientSettings.ttsRate + delta));
        ClientSettings.save(); TikTokLiveService.sendConfig();
        notice("Kecepatan: " + ClientSettings.ttsRate + "%");
    }

    private void changePitch(int delta) {
        ClientSettings.ttsPitch = Math.max(-50, Math.min(50, ClientSettings.ttsPitch + delta));
        ClientSettings.save(); TikTokLiveService.sendConfig();
        notice("Nada: " + ClientSettings.ttsPitch + " Hz");
    }

    private long pendingClearUntil;

    private void drawFilter(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> toggleCard(g, x, y, w, h, mx, my, "Filter kata kasar", "Kata di daftar tidak dibacakan dan tampil sebagai ***", ClientSettings.filterEnabled,
                    () -> { ClientSettings.filterEnabled = !ClientSettings.filterEnabled; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 1 -> toggleCard(g, x, y, w, h, mx, my, "Lewati seluruh komentar", "Aktif: komentar yang memuat kata kasar tidak dibacakan sama sekali", ClientSettings.filterSkipComment,
                    () -> { ClientSettings.filterSkipComment = !ClientSettings.filterSkipComment; ClientSettings.save(); TikTokLiveService.sendConfig(); });
            case 2 -> actionCard(g, x, y, w, h, mx, my, "Daftar bawaan (" + LiveFilter.words().size() + " kata di daftarmu)", "Tambahkan kata kasar umum Indonesia & Inggris; bisa dihapus satu per satu", "Muat bawaan",
                    () -> notice(LiveFilter.addDefaults() + " kata ditambahkan"));
            case 3 -> {
                boolean confirm = System.currentTimeMillis() < pendingClearUntil;
                actionCard(g, x, y, w, h, mx, my, "Kosongkan daftar", "Menghapus semua kata filter sekaligus", confirm ? "Yakin?" : "Kosongkan", () -> {
                    if (System.currentTimeMillis() < pendingClearUntil) { LiveFilter.clearWords(); pendingClearUntil = 0; notice("Daftar filter dikosongkan"); }
                    else { pendingClearUntil = System.currentTimeMillis() + 4000; notice("Klik Yakin? untuk menghapus semua kata"); }
                });
            }
            default -> {
                List<String> words = LiveFilter.words();
                if (words.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada kata. Ketik di kolom atas lalu klik Tambah (pisahkan dengan koma)."); return; }
                String word = words.get(Math.min(words.size() - 1, i - 4));
                Ui.rect(g, x, y, w, h - 2, 5, Ui.in(mx, my, x, y, w, h - 2) ? Ui.CARD_HOVER : Ui.CARD);
                g.text(font, Ui.fit(font, word, w - 70), x + 10, y + (h - 2 - 8) / 2, Ui.TEXT, false);
                button(g, x + w - 46, y + (h - 2 - 16) / 2, 42, 16, "Hapus", false, mx, my, () -> { LiveFilter.removeWord(word); notice("Dihapus dari filter: " + word); });
            }
        }
    }

    private void drawBanned(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i == 0) {
            Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
            g.text(font, Ui.fit(font, "Akun diblokir: " + LiveFilter.banned().size(), w - 20), x + 10, y + 8, Ui.TEXT, false);
            g.text(font, Ui.fit(font, "Ban juga bisa dari tab LIVE (tombol Ban di tiap komentar) atau /xyoplay ban <akun>", w - 20), x + 10, y + 21, Ui.MUTED, false);
            return;
        }
        List<String> banned = LiveFilter.banned();
        if (banned.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada akun diblokir. Ketik username lalu klik Ban."); return; }
        String user = banned.get(Math.min(banned.size() - 1, i - 1));
        Ui.rect(g, x, y, w, h - 2, 5, Ui.in(mx, my, x, y, w, h - 2) ? Ui.CARD_HOVER : Ui.CARD);
        g.text(font, Ui.fit(font, "@" + user, w - 100), x + 10, y + (h - 2 - 8) / 2, Ui.BAD, false);
        button(g, x + w - 76, y + (h - 2 - 16) / 2, 72, 16, "Buka blokir", false, mx, my, () -> { LiveFilter.unban(user); notice("Blokir dibuka: @" + user); });
    }

    private void drawActions(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> toggleCard(g, x, y, w, h, mx, my, "Aksi LIVE aktif", "Follow / gift / komentar memunculkan mob, TNT, item, efek (singleplayer tanpa cheat; server butuh OP)", ClientSettings.actionsEnabled,
                    () -> { ClientSettings.actionsEnabled = !ClientSettings.actionsEnabled; ClientSettings.save(); if (!ClientSettings.actionsEnabled) LiveActions.clearQueue(); notice("Aksi LIVE " + (ClientSettings.actionsEnabled ? "aktif" : "mati")); });
            case 1 -> toggleCard(g, x, y, w, h, mx, my, "Nama penonton di atas mob", "Mob muncul dengan nama akun yang follow / kirim gift", ClientSettings.actionsNameTag,
                    () -> { ClientSettings.actionsNameTag = !ClientSettings.actionsNameTag; ClientSettings.save(); });
            case 2 -> stepperCard(g, x, y, w, h, mx, my, "Batas gift per event", "Combo gift diproses per potong sampai batas ini; sisanya ditandai \"dibatasi\" di Monitor",
                    Integer.toString(ClientSettings.actionsMaxPerEvent), () -> changeMaxMobs(-1), () -> changeMaxMobs(1));
            case 3 -> toggleCard(g, x, y, w, h, mx, my, "TNT mode aman", "Aman: ledakan suara + partikel, blok tidak rusak. Mati: TNT asli menyala dan merusak blok", ClientSettings.tntSafe,
                    () -> { ClientSettings.tntSafe = !ClientSettings.tntSafe; ClientSettings.save(); notice(ClientSettings.tntSafe ? "TNT aman: tidak merusak blok" : "TNT ASLI: merusak blok!"); });
            case 4 -> stepperCard(g, x, y, w, h, mx, my, "Batas TNT per gift", "Gift 25 koin = 25 TNT, tapi tidak lebih dari batas ini (diberi tahu di banner)",
                    Integer.toString(ClientSettings.tntMax), () -> changeInt(v -> ClientSettings.tntMax = v, ClientSettings.tntMax, -5, 1, 100), () -> changeInt(v -> ClientSettings.tntMax = v, ClientSettings.tntMax, 5, 1, 100));
            case 5 -> stepperCard(g, x, y, w, h, mx, my, "Jeda antar spawn", "Entity muncul satu per satu, bukan semua dalam satu tick",
                    ClientSettings.spawnGapTicks + " tick", () -> changeInt(v -> ClientSettings.spawnGapTicks = v, ClientSettings.spawnGapTicks, -1, 1, 40), () -> changeInt(v -> ClientSettings.spawnGapTicks = v, ClientSettings.spawnGapTicks, 1, 1, 40));
            case 6 -> stepperCard(g, x, y, w, h, mx, my, "Batas entity per 30 detik", "Lewat batas: sisa efek menunggu, tidak membuat game lag",
                    Integer.toString(ClientSettings.maxEntities), () -> changeInt(v -> ClientSettings.maxEntities = v, ClientSettings.maxEntities, -5, 5, 200), () -> changeInt(v -> ClientSettings.maxEntities = v, ClientSettings.maxEntities, 5, 5, 200));
            case 7 -> stepperCard(g, x, y, w, h, mx, my, "Jeda antar gelombang", "Untuk event besar (Mega Chaos, Special) dan hitung mundur",
                    ClientSettings.waveGapTicks + " tick", () -> changeInt(v -> ClientSettings.waveGapTicks = v, ClientSettings.waveGapTicks, -5, 5, 200), () -> changeInt(v -> ClientSettings.waveGapTicks = v, ClientSettings.waveGapTicks, 5, 5, 200));
            case 8 -> stepperCard(g, x, y, w, h, mx, my, "Durasi potion", "Lama efek potion dari gift (tidak pernah permanen)",
                    ClientSettings.potionSeconds + " dtk", () -> changeInt(v -> ClientSettings.potionSeconds = v, ClientSettings.potionSeconds, -5, 3, 120), () -> changeInt(v -> ClientSettings.potionSeconds = v, ClientSettings.potionSeconds, 5, 3, 120));
            case 9 -> stepperCard(g, x, y, w, h, mx, my, "Peluang efek baik", "Sisanya efek gangguan (acak:ringan dan acak:potion)",
                    ClientSettings.potionGoodPct + "%", () -> changeInt(v -> ClientSettings.potionGoodPct = v, ClientSettings.potionGoodPct, -10, 0, 100), () -> changeInt(v -> ClientSettings.potionGoodPct = v, ClientSettings.potionGoodPct, 10, 0, 100));
            case 10 -> stepperCard(g, x, y, w, h, mx, my, "Cooldown command komentar", "Satu penonton hanya bisa memakai !command sekali per jeda ini",
                    ClientSettings.cmdCooldownSec + " dtk", () -> changeInt(v -> ClientSettings.cmdCooldownSec = v, ClientSettings.cmdCooldownSec, -1, 0, 120), () -> changeInt(v -> ClientSettings.cmdCooldownSec = v, ClientSettings.cmdCooldownSec, 1, 0, 120));
            case 11 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Tulis pemicu=hadiah lalu Tambah. Pemicu: follow · share · Rose · id:5655 · diamond:20 · gift · !boom", w - 20), x + 10, y + 7, Ui.TEXT, false);
                String typed = text().contains("=") ? text().substring(0, text().indexOf('=')) : text();
                List<String> sug = GiftCatalog.suggest(typed.strip(), 4);
                String line2 = sug.isEmpty() ? "Hadiah: zombie+creeper+tnt*4 · give:oak_log*64 · acak:tools|berguna|bahaya|boss|ringan|potion · fx:speed · mega" : "Gift cocok: " + String.join(", ", sug);
                g.text(font, Ui.fit(font, line2, w - 20), x + 10, y + 20, Ui.MUTED, false);
                g.text(font, Ui.fit(font, "tnt*c = TNT sebanyak koin gift · a|b = gelombang · say:teks = banner · menimpa aturan yang sama", w - 20), x + 10, y + 33, Ui.DIM, false);
            }
            case 12 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Uji tanpa LIVE", 90), x + 10, y + (h - 2 - 8) / 2, Ui.MUTED, false);
                String[][] tests = {{"Follow", "follow"}, {"Like", "like"}, {"Rose", "Rose"}, {"Finger Heart", "Finger Heart"}, {"Perfume", "Perfume"}, {"Doughnut", "Doughnut"}, {"Mega", "Hat and Mustache"}};
                int area = w - 104, gap = 3, bw = Math.max(24, (area - gap * (tests.length - 1)) / tests.length), by = y + (h - 2 - 18) / 2;
                for (int t = 0; t < tests.length; t++) {
                    final String[] test = tests[t];
                    button(g, x + 98 + t * (bw + gap), by, bw, 18, test[0], false, mx, my, () -> {
                        if (test[1].equals("follow")) testEvent("follow", "", 1, 0);
                        else if (test[1].equals("like")) testEvent("like", "", 1, 0);
                        else testEvent("gift", test[1], 1, Math.max(1, GiftBook.priceOf(test[1])));
                    });
                }
            }
            case 13 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Uji koin", 90), x + 10, y + (h - 2 - 8) / 2, Ui.MUTED, false);
                int[] coins = {1, 5, 10, 20, 30, 50, 99, 100};
                int area = w - 104, gap = 3, bw = Math.max(20, (area - gap * (coins.length - 1)) / coins.length), by = y + (h - 2 - 18) / 2;
                for (int t = 0; t < coins.length; t++) {
                    final int c = coins[t];
                    button(g, x + 98 + t * (bw + gap), by, bw, 18, Integer.toString(c), false, mx, my, () -> testEvent("gift", "Gift Uji " + c, 1, c));
                }
            }
            case 14 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                int by = y + (h - 2 - 18) / 2, bw = Math.max(60, (w - 20 - 12) / 3);
                button(g, x + 10, by, bw, 18, "Paket XYOMC", true, mx, my, () -> notice(LiveActions.loadPreset(LiveActions.PRESET_XYOMC, true) + " aturan gift bawaan dimuat (aturan lama diganti)"));
                button(g, x + 14 + bw, by, bw, 18, "Contoh !command", false, mx, my, () -> notice(LiveActions.loadPreset(LiveActions.PRESET_COMMANDS, false) + " contoh command ditambahkan"));
                button(g, x + 18 + bw * 2, by, bw, 18, "Kosongkan antrean", false, mx, my, () -> { LiveActions.clearQueue(); notice("Antrean efek dikosongkan"); });
            }
            case 15 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Antre " + LiveActions.pending() + " · menunggu batas entity " + LiveActions.deferred() + " · berhasil " + LiveActions.spawnedTotal() + " · gagal " + LiveActions.failedTotal(), w - 100), x + 10, y + 8, LiveActions.failedTotal() > 0 ? Ui.BAD : Ui.TEXT, false);
                g.text(font, Ui.fit(font, McCompat.inWorld() ? "Di dunia · efek berjalan lewat command Minecraft" : "Belum di dunia: masuk dunia supaya efek bisa berjalan", w - 100), x + 10, y + 21, McCompat.inWorld() ? Ui.MUTED : Ui.BAD, false);
                button(g, x + w - 78, y + (h - 2 - 18) / 2, 70, 18, "Monitor", false, mx, my, () -> setMode(Mode.MONITOR));
            }
            case 16 -> stepperCard(g, x, y, w, h, mx, my, "Event random tiap tap per orang", "Tiap penonton mencapai jumlah tap ini dapat 1 event acak (like=acak:chaos). " + (LiveActions.tapLeader().isEmpty() ? "Total tap: " + LiveActions.likeCount() : "Terdekat: " + LiveActions.tapLeader()),
                    Integer.toString(ClientSettings.likeStep), () -> changeInt(v -> ClientSettings.likeStep = v, ClientSettings.likeStep, -50, 20, 5000),
                    () -> changeInt(v -> ClientSettings.likeStep = v, ClientSettings.likeStep, 50, 20, 5000));
            default -> {
                List<LiveActions.Trigger> triggers = LiveActions.triggers();
                if (triggers.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada pemicu. Tekan \"Paket XYOMC\" atau tulis: follow=zombie"); return; }
                LiveActions.Trigger t = triggers.get(Math.min(triggers.size() - 1, i - 17));
                Ui.rect(g, x, y, w, h - 2, 5, Ui.in(mx, my, x, y, w, h - 2) ? Ui.CARD_HOVER : Ui.CARD);
                String text = t.label() + "  →  " + LiveActions.prettySpec(t.mob());
                g.text(font, Ui.fit(font, text, w - 150), x + 10, y + (h - 2 - 8) / 2, t.type().equals("diamond") ? LiveActions.tierColor(Integer.parseInt(t.arg())) : Ui.TEXT, false);
                int by = y + (h - 2 - 16) / 2;
                button(g, x + w - 46, by, 42, 16, "Hapus", false, mx, my, () -> { LiveActions.remove(t); notice("Pemicu dihapus"); });
                button(g, x + w - 46 - 34, by, 30, 16, "Uji", false, mx, my, () -> {
                    if (!McCompat.inWorld()) { notice("Masuk ke dunia dulu"); return; }
                    int coins = t.type().equals("diamond") ? Integer.parseInt(t.arg()) : t.type().equals("giftname") ? Math.max(1, GiftBook.priceOf(t.arg())) : 1;
                    int n = LiveActions.test(t.mob(), "Uji", coins);
                    notice(n > 0 ? "Menjalankan " + n + " efek: " + LiveActions.prettySpec(t.mob()) : "Tidak ada efek yang bisa dijalankan");
                });
                button(g, x + w - 46 - 34 - 38, by, 34, 16, "Ubah", false, mx, my, () -> {
                    texts.put(Mode.ACTIONS, t.spec());
                    cursorPos = text().length(); inputFocused = true; allSelected = false; scroll = 0;
                    notice("Ubah teksnya di kolom atas lalu tekan Tambah (menimpa aturan ini)");
                });
            }
        }
    }

    private void changeInt(java.util.function.IntConsumer setter, int current, int delta, int min, int max) {
        setter.accept(Math.max(min, Math.min(max, current + delta)));
        ClientSettings.save();
    }

    private static final java.text.SimpleDateFormat CLOCK = new java.text.SimpleDateFormat("HH:mm:ss");

    private void drawGifts(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i == 0) {
            Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
            long up = GiftBook.lastUpdate();
            g.text(font, Ui.fit(font, "Katalog gift · harga koin hanya dari event LIVE nyata (atau acuan tabel hadiah)", w - 20), x + 10, y + 8, Ui.TEXT, false);
            g.text(font, Ui.fit(font, up == 0 ? "Belum ada data LIVE. Mulai LIVE sekali: nama, id, dan harga gift tersimpan otomatis dan tetap ada saat offline." : "Pembaruan terakhir dari LIVE: " + CLOCK.format(new java.util.Date(up)) + " · '?' berarti harga belum diketahui (tidak ditebak)", w - 20), x + 10, y + 22, Ui.MUTED, false);
            return;
        }
        List<GiftBook.Entry> entries = GiftBook.entries();
        if (entries.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada gift."); return; }
        GiftBook.Entry e = entries.get(Math.min(entries.size() - 1, i - 1));
        int price = GiftBook.priceOf(e.name());
        String source = GiftBook.sourceOf(e.name());
        Ui.rect(g, x, y, w, h - 2, 5, Ui.in(mx, my, x, y, w, h - 2) ? Ui.CARD_HOVER : Ui.CARD);
        LiveActions.Trigger rule = LiveActions.ruleFor(e.name(), e.id(), Math.max(0, price));
        String priceText = price > 0 ? (source.equals("acuan") ? "~" : "") + price + " koin" : "? koin";
        String idText = e.id() > 0 ? " · id " + e.id() : "";
        int right = 78;
        g.text(font, Ui.fit(font, e.name() + "  " + priceText + idText + (e.times() > 0 ? " · terlihat " + e.times() + "×" : ""), w - right - 14), x + 10, y + 5, price > 0 ? LiveActions.tierColor(price) : Ui.TEXT, false);
        g.text(font, Ui.fit(font, rule == null ? "Belum ada aturan" : "→ " + LiveActions.prettySpec(rule.mob()) + (rule.type().equals("giftname") ? "" : "  (" + rule.label() + ")"), w - right - 14), x + 10, y + 17, rule == null ? Ui.DIM : Ui.MUTED, false);
        int by = y + (h - 2 - 16) / 2;
        final GiftBook.Entry entry = e;
        button(g, x + w - 36, by, 32, 16, "Atur", false, mx, my, () -> {
            texts.put(Mode.ACTIONS, entry.name() + "=");
            setMode(Mode.ACTIONS);
            texts.put(Mode.ACTIONS, entry.name() + "=");
            cursorPos = text().length(); inputFocused = true;
            notice("Tulis hadiahnya setelah = lalu tekan Tambah");
        });
        button(g, x + w - 36 - 36, by, 32, 16, "Uji", false, mx, my, () -> testEvent("gift", entry.name(), 1, Math.max(0, GiftBook.priceOf(entry.name()))));
    }

    private static final String[] DEFAULT_PHRASES = {
        "Selamat datang di LIVE, jangan lupa follow ya",
        "Terima kasih sudah menonton, kalian luar biasa",
        "Tap tap layarnya, setiap dua ratus tap ada event acak",
        "Kirim gift untuk memunculkan mob dan TNT",
        "Ketik ikut di kolom komentar untuk ikut undian",
        "Sebentar lagi ada kejutan besar, tetap di sini",
        "Sampai jumpa di LIVE berikutnya"
    };

    private List<String> phrases() {
        List<String> l = new ArrayList<>(List.of(DEFAULT_PHRASES));
        if (!ClientSettings.quickPhrases.isEmpty()) for (String p : ClientSettings.quickPhrases.split("\\|")) if (!p.isBlank()) l.add(p);
        return l;
    }

    private void drawPhrases(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i == 0) {
            Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
            g.text(font, Ui.fit(font, "Klik kalimat untuk membacakannya dengan suara chat sendiri", w - 20), x + 10, y + 8, Ui.TEXT, false);
            g.text(font, Ui.fit(font, "Ketik kalimat di kolom atas lalu Tambah · kalimat buatanmu bisa dihapus", w - 20), x + 10, y + 22, Ui.MUTED, false);
            return;
        }
        List<String> all = phrases();
        int idx = i - 1;
        if (idx >= all.size()) return;
        boolean custom = idx >= DEFAULT_PHRASES.length;
        String p = all.get(idx);
        int bw = custom ? 52 : 0;
        boolean over = mx >= x && mx < x + w - bw && my >= y && my < y + h - 2;
        Ui.rect(g, x, y, w, h - 2, 5, over ? Ui.CARD_HOVER : Ui.CARD);
        g.text(font, Ui.fit(font, "▶  " + p, w - 20 - bw), x + 10, y + (h - 2 - 8) / 2, Ui.TEXT, false);
        hits.add(new Hit(x, y, w - bw, h - 2, () -> {
            TikTokLiveService.speak(p, 0, true); notice("Dibacakan");
        }));
        if (custom) {
            final int ci = idx - DEFAULT_PHRASES.length;
            button(g, x + w - 48, y + (h - 2 - 16) / 2, 42, 16, "Hapus", false, mx, my, () -> {
                List<String> mine = new ArrayList<>(List.of(ClientSettings.quickPhrases.split("\\|")));
                mine.removeIf(String::isBlank);
                if (ci >= 0 && ci < mine.size()) mine.remove(ci);
                ClientSettings.quickPhrases = String.join("|", mine);
                ClientSettings.save();
            });
        }
    }

    private int botSlot, botTier = 3;

    private void cycleBool(String label, boolean v) { notice(label + (v ? ": ON" : ": MATI")); }

    private void drawBot(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, (ClientSettings.botEnabled ? "Bot AKTIF · " + Bots.BOTS.size() + " bot" : "Bot MATI") + " · " + (Bots.afkOn ? "AFK AUTOPILOT AKTIF" : "AFK mati"), w - 20), x + 10, y + 7, Bots.afkOn ? 0xFFC084FC : Ui.TEXT, false);
                String first = Bots.BOTS.isEmpty() ? (Bots.afk != null ? Bots.afkStatus : "Aktifkan bot lalu klik Panggil. Perintah: /xyoplay bot panggil · ikut · diam · otomatis · base") : "Bot 1: " + Bots.BOTS.get(0).status + " · " + Bots.BOTS.get(0).summary();
                g.text(font, Ui.fit(font, first, w - 20), x + 10, y + 21, Ui.MUTED, false);
                String diag = !Bots.problem.isEmpty() ? "! " + Bots.problem : !ServerBridge.last.isEmpty() && ServerBridge.broken ? "! " + ServerBridge.last : "Gerak: " + (ServerBridge.available() ? "langsung di dunia sendiri" : "lewat command (server: butuh OP)") + (McWorld.healthy ? "" : " · dunia tidak terbaca");
                g.text(font, Ui.fit(font, diag, w - 20), x + 10, y + 35, diag.startsWith("!") ? Ui.BAD : Ui.DIM, false);
            }
            case 1 -> toggleCard(g, x, y, w, h, mx, my, "Bot aktif", "Muncul di sebelahmu dan mengikuti ke mana pun (dimatikan = bot hilang)", ClientSettings.botEnabled,
                    () -> { ClientSettings.botEnabled = !ClientSettings.botEnabled; ClientSettings.save(); });
            case 2 -> stepperCard(g, x, y, w, h, mx, my, "Jumlah bot", "Berapa bot yang muncul (1–5)", Integer.toString(ClientSettings.botCount),
                    () -> { ClientSettings.botCount = Math.max(1, ClientSettings.botCount - 1); ClientSettings.save(); },
                    () -> { ClientSettings.botCount = Math.min(5, ClientSettings.botCount + 1); ClientSettings.save(); });
            case 3 -> actionCard(g, x, y, w, h, mx, my, "Bentuk bot", "Pemain biasa (pakai skin) atau mob; klik untuk ganti", Bots.TYPES[Math.min(ClientSettings.botType, Bots.TYPES.length - 1)][1],
                    () -> { ClientSettings.botType = (ClientSettings.botType + 1) % Bots.TYPES.length; ClientSettings.save(); Bots.refreshLook(); });
            case 4 -> stepperCard(g, x, y, w, h, mx, my, "Skin pemain", "Skin bawaan (offline) atau ketik nama akun Minecraft di kolom atas lalu Skin", Bots.SKINS[ClientSettings.botSkin][0].equals("Nama akun…") ? (ClientSettings.botSkinName.isEmpty() ? "Nama akun…" : ClientSettings.botSkinName) : Bots.SKINS[ClientSettings.botSkin][0],
                    () -> { ClientSettings.botSkin = (ClientSettings.botSkin + Bots.SKINS.length - 1) % Bots.SKINS.length; ClientSettings.save(); Bots.refreshLook(); },
                    () -> { ClientSettings.botSkin = (ClientSettings.botSkin + 1) % Bots.SKINS.length; ClientSettings.save(); Bots.refreshLook(); });
            case 5 -> stepperCard(g, x, y, w, h, mx, my, "Pakaian", "Armor hias awal; armor asli buatan bot menggantikannya", Bots.OUTFITS[ClientSettings.botOutfit],
                    () -> { ClientSettings.botOutfit = (ClientSettings.botOutfit + Bots.OUTFITS.length - 1) % Bots.OUTFITS.length; ClientSettings.save(); Bots.refreshLook(); },
                    () -> { ClientSettings.botOutfit = (ClientSettings.botOutfit + 1) % Bots.OUTFITS.length; ClientSettings.save(); Bots.refreshLook(); });
            case 6 -> toggleCard(g, x, y, w, h, mx, my, "Mode survival", "Bot bisa terluka dan mati (bahannya hilang separuh, lalu muncul lagi); mati = mulai dari alat yang sudah dibuat", ClientSettings.botSurvival,
                    () -> { ClientSettings.botSurvival = !ClientSettings.botSurvival; ClientSettings.save(); Bots.refreshLook(); });
            case 7 -> actionCard(g, x, y, w, h, mx, my, "Panggil ke sini", "Semua bot muncul lagi di dekatmu dan mengikuti", "Panggil", () -> notice(Bots.handle("panggil")));
            case 8 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, "Perintah untuk bot", x + 10, y + 8, Ui.TEXT, false);
                g.text(font, Ui.fit(font, "Ikut = jaga di dekatmu · Diam · Otomatis = cari bahan & buat alat sendiri · Base = rumah kecil", w - 20), x + 10, y + 21, Ui.MUTED, false);
                String[] names = {"Ikut", "Diam", "Otomatis", "Base"};
                String[] cmds = {"ikut", "diam", "otomatis", "base"};
                int bw = 64, bx = x + w - 8 - names.length * (bw + 4) + 4, by = y + 30;
                for (int k = 0; k < names.length; k++) { final String c = cmds[k]; button(g, bx + k * (bw + 4), by, bw, 20, names[k], k == 0, mx, my, () -> notice(Bots.handle(c))); }
            }
            case 9 -> actionCard(g, x, y, w, h, mx, my, "Tambang / cari balok", "Bot mencari: " + ClientSettings.botMine + " (klik untuk ganti jenis, lalu Mulai)", "Ganti",
                    () -> { int k = java.util.List.of(Bots.MINE_KINDS).indexOf(ClientSettings.botMine); ClientSettings.botMine = Bots.MINE_KINDS[(k + 1) % Bots.MINE_KINDS.length]; ClientSettings.save(); });
            case 10 -> actionCard(g, x, y, w, h, mx, my, "Mulai tambang", "Bot menambang \"" + ClientSettings.botMine + "\" di sekitar lalu menyimpannya di tasnya", "Mulai", () -> notice(Bots.handle("tambang " + ClientSettings.botMine)));
            case 11 -> stepperCard(g, x, y, w, h, mx, my, "Buatkan alat: " + BotGear.SLOTS[botSlot], "Pilih alat atau armor yang dibuatkan bot untukmu", BotGear.SLOTS[botSlot],
                    () -> botSlot = (botSlot + BotGear.SLOTS.length - 1) % BotGear.SLOTS.length, () -> botSlot = (botSlot + 1) % BotGear.SLOTS.length);
            case 12 -> stepperCard(g, x, y, w, h, mx, my, "Bahan alat", "Kayu, batu, besi, atau berlian (bot memakai bahan di tasnya)", BotGear.TIER_NAMES[botTier],
                    () -> botTier = botTier <= 1 ? 4 : botTier - 1, () -> botTier = botTier >= 4 ? 1 : botTier + 1);
            case 13 -> actionCard(g, x, y, w, h, mx, my, "Buat & beri ke aku", "Bot membuat " + BotGear.TIER_NAMES[botTier] + " " + BotGear.SLOTS[botSlot] + " dari tasnya; bahan kurang = bot mencarinya", "Buat",
                    () -> notice(Bots.handle("buat " + BotGear.SLOTS[botSlot] + " " + BotGear.TIER_NAMES[botTier])));
            case 14 -> toggleCard(g, x, y, w, h, mx, my, "Boleh menggali", "Gali terowongan untuk mengikutimu menembus tanah dan gunung", ClientSettings.botDig, () -> { ClientSettings.botDig = !ClientSettings.botDig; ClientSettings.save(); });
            case 15 -> toggleCard(g, x, y, w, h, mx, my, "Boleh menaruh blok", "Pilar ke atas, jembatan di jurang (naik gunung, keluar gua)", ClientSettings.botPlace, () -> { ClientSettings.botPlace = !ClientSettings.botPlace; ClientSettings.save(); });
            case 16 -> toggleCard(g, x, y, w, h, mx, my, "Lawan mob otomatis", "Menyerang monster di dekatmu", ClientSettings.botFight, () -> { ClientSettings.botFight = !ClientSettings.botFight; ClientSettings.save(); });
            case 17 -> toggleCard(g, x, y, w, h, mx, my, "Buat & upgrade alat otomatis", "Kayu → batu → besi → berlian, plus armor besi", ClientSettings.botAutoGear, () -> { ClientSettings.botAutoGear = !ClientSettings.botAutoGear; ClientSettings.save(); });
            case 18 -> toggleCard(g, x, y, w, h, mx, my, "Bangun base otomatis", "Mode Otomatis membangun rumah 5x5 saat bahan cukup (72 blok)", ClientSettings.botAutoBase, () -> { ClientSettings.botAutoBase = !ClientSettings.botAutoBase; ClientSettings.save(); });
            case 19 -> toggleCard(g, x, y, w, h, mx, my, "Deteksi bijih tersembunyi", "Bot tahu letak besi/berlian dalam 12 blok (matikan = hanya yang terlihat)", ClientSettings.botXray, () -> { ClientSettings.botXray = !ClientSettings.botXray; ClientSettings.save(); });
            case 20 -> toggleCard(g, x, y, w, h, mx, my, "Nama di atas bot", "Tampilkan nama \"XYO Bot\" di atas kepala", ClientSettings.botNametag, () -> { ClientSettings.botNametag = !ClientSettings.botNametag; ClientSettings.save(); });
            case 21 -> stepperCard(g, x, y, w, h, mx, my, "Jarak ikut", "Bot berhenti sekitar sekian blok darimu", ClientSettings.botFollowDist + " blok",
                    () -> { ClientSettings.botFollowDist = Math.max(2, ClientSettings.botFollowDist - 1); ClientSettings.save(); },
                    () -> { ClientSettings.botFollowDist = Math.min(10, ClientSettings.botFollowDist + 1); ClientSettings.save(); });
            case 22 -> actionCard(g, x, y, w, h, mx, my, "Kecepatan bot", "Santai / Normal / Cepat", Bots.SPEEDS[ClientSettings.botSpeed], () -> { ClientSettings.botSpeed = (ClientSettings.botSpeed + 1) % 3; ClientSettings.save(); });
            case 23 -> actionCard(g, x, y, w, h, mx, my, "Hemat resource", "Batas pencarian jalan per bot: " + Bots.ECO[ClientSettings.botEco] + " (Hemat = CPU paling ringan)", Bots.ECO[ClientSettings.botEco],
                    () -> { ClientSettings.botEco = (ClientSettings.botEco + 1) % 3; ClientSettings.save(); });
            case 24 -> { g.text(font, "MODE AFK — kamu diam, karaktermu bermain sendiri", x + 4, y + 9, 0xFFC084FC, false); }
            case 25 -> toggleCard(g, x, y, w, h, mx, my, "AFK autopilot", "Karaktermu menebang pohon, menambang, membuat alat, bangun base, dan melawan mob sendiri", Bots.afkOn,
                    () -> Bots.setAfk(!Bots.afkOn));
            case 26 -> toggleCard(g, x, y, w, h, mx, my, "Respawn otomatis", "Saat mati, tombol respawn ditekan sendiri", ClientSettings.afkRespawn, () -> { ClientSettings.afkRespawn = !ClientSettings.afkRespawn; ClientSettings.save(); });
            case 27 -> toggleCard(g, x, y, w, h, mx, my, "Kembali ke titik mati", "Setelah respawn, berjalan kembali ke tempat mati (dilewati kalau mati 3x di area yang sama)", ClientSettings.afkReturn, () -> { ClientSettings.afkReturn = !ClientSettings.afkReturn; ClientSettings.save(); });
            case 28 -> toggleCard(g, x, y, w, h, mx, my, "Isi rasa lapar otomatis", "Efek kenyang kecil tiap 1 menit agar tidak kelaparan saat AFK", ClientSettings.afkFood, () -> { ClientSettings.afkFood = !ClientSettings.afkFood; ClientSettings.save(); });
            case 29 -> toggleCard(g, x, y, w, h, mx, my, "Berlindung saat malam", "Jika sudah punya base, pulang dan menunggu di dalam saat malam", ClientSettings.afkNightHome, () -> { ClientSettings.afkNightHome = !ClientSettings.afkNightHome; ClientSettings.save(); });
            default -> {
                int idx = i - 30;
                List<BotAgent> all = new ArrayList<>(Bots.BOTS);
                if (Bots.afk != null) all.add(Bots.afk);
                if (all.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada bot. Aktifkan bot lalu klik Panggil."); return; }
                BotAgent a = all.get(Math.min(all.size() - 1, idx));
                Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
                g.text(font, Ui.fit(font, (a.playerBody ? "Kamu (AFK)" : "Bot " + a.id) + " · " + a.mode.label + " · " + a.status, w - 20), x + 10, y + 5, Ui.TEXT, false);
                g.text(font, Ui.fit(font, "Tas: " + a.inv.m + " · alat: pickaxe " + BotGear.TIER_NAMES[a.gear.tier("pickaxe")] + ", pedang " + BotGear.TIER_NAMES[a.gear.tier("sword")] + " · tambang " + a.blocksMined, w - 20), x + 10, y + 17, Ui.MUTED, false);
            }
        }
    }

    private void drawSfx(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        MemeSounds.installDefaults();
        switch (i) {
            case 0 -> toggleCard(g, x, y, w, h, mx, my, "Efek suara aktif", "Putar suara seru untuk follow, gift, tap event, target, dan pemenang undian", ClientSettings.memeSounds,
                    () -> { ClientSettings.memeSounds = !ClientSettings.memeSounds; ClientSettings.save(); });
            case 1 -> stepperCard(g, x, y, w, h, mx, my, "Volume efek", "Terpisah dari volume musik dan TTS", ClientSettings.memeVolume + "%",
                    () -> { ClientSettings.memeVolume = Math.max(0, ClientSettings.memeVolume - 10); ClientSettings.save(); },
                    () -> { ClientSettings.memeVolume = Math.min(100, ClientSettings.memeVolume + 10); ClientSettings.save(); });
            case 2 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Mau pakai sound meme sendiri? Taruh file wav / mp3 / ogg ke folder jenisnya:", w - 20), x + 10, y + 8, Ui.TEXT, false);
                g.text(font, Ui.fit(font, "config/xyoplay/sounds/<jenis>/  (mis. sounds/follow/)", w - 20), x + 10, y + 22, Ui.MUTED, false);
                g.text(font, Ui.fit(font, "Beberapa file = dipilih acak. Hapus file bawaan jika tidak mau dipakai.", w - 20), x + 10, y + 35, Ui.MUTED, false);
            }
            default -> {
                MemeSounds.Kind k = MemeSounds.KINDS.get(Math.min(MemeSounds.KINDS.size() - 1, i - 3));
                Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
                g.text(font, Ui.fit(font, k.label() + " · " + k.when(), w - 130), x + 10, y + 5, Ui.TEXT, false);
                g.text(font, Ui.fit(font, MemeSounds.files(k.id()).size() + " file · sounds/" + k.id(), w - 130), x + 10, y + 17, Ui.MUTED, false);
                button(g, x + w - 62, y + (h - 2 - 18) / 2, 54, 18, "Uji", true, mx, my, () -> {
                    if (MemeSounds.files(k.id()).isEmpty()) notice("Folder " + k.id() + " kosong");
                    else { MemeSounds.play(k.id()); notice("Memutar: " + k.label()); }
                });
            }
        }
    }

    private void drawGiveaway(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> toggleCard(g, x, y, w, h, mx, my, "Pendaftaran dibuka · " + Giveaway.size() + " peserta",
                    "Penonton komentar \"" + ClientSettings.giveawayWord + "\" untuk ikut (ganti kata lewat kolom di atas)", Giveaway.isOpen(),
                    () -> Giveaway.setOpen(!Giveaway.isOpen()));
            case 1 -> actionCard(g, x, y, w, h, mx, my, "Undi pemenang", "Banner, judul besar, suara, dan TTS \"Selamat …\" untuk pemenang", "Undi!", () -> {
                Giveaway.Winner win = Giveaway.draw();
                notice(win == null ? "Belum ada peserta" : "Pemenang: " + win.name());
            });
            case 2 -> actionCard(g, x, y, w, h, mx, my, "Reset peserta", "Kosongkan daftar peserta untuk ronde baru (riwayat pemenang tetap)", "Reset", () -> { Giveaway.reset(); notice("Peserta dikosongkan"); });
            case 3 -> actionCard(g, x, y, w, h, mx, my, "Tambah 5 peserta uji", "Untuk mencoba undian tanpa sedang LIVE", "Tambah", () -> { Giveaway.addTest(5); notice("5 peserta uji ditambahkan"); });
            default -> {
                List<Giveaway.Winner> ws = Giveaway.winners();
                if (ws.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada pemenang."); return; }
                Giveaway.Winner win = ws.get(Math.min(ws.size() - 1, i - 4));
                Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
                g.text(font, Ui.fit(font, "★ " + win.name(), w - 80), x + 10, y + (h - 2 - 8) / 2, 0xFFFFD700, false);
                String t = CLOCK.format(new java.util.Date(win.time()));
                g.text(font, t, x + w - 10 - font.width(t), y + (h - 2 - 8) / 2, Ui.MUTED, false);
            }
        }
    }

    private void drawMonitor(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i == 0) {
            Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
            g.text(font, Ui.fit(font, "Antre " + LiveActions.pending() + " · menunggu " + LiveActions.deferred() + " · berhasil " + LiveActions.spawnedTotal() + " · gagal " + LiveActions.failedTotal() + " · " + (ClientSettings.actionsEnabled ? "Aksi AKTIF" : "Aksi MATI"), w - 100), x + 10, y + 8, LiveActions.failedTotal() > 0 ? Ui.BAD : Ui.TEXT, false);
            g.text(font, Ui.fit(font, TikTokLiveService.isRunning() ? "LIVE tersambung · " + TikTokLiveService.status() : "LIVE belum tersambung (tab LIVE) · uji dengan tab Aksi", w - 100), x + 10, y + 22, TikTokLiveService.isRunning() ? Ui.OK : Ui.MUTED, false);
            button(g, x + w - 78, y + (h - 2 - 18) / 2, 70, 18, "Bersihkan", false, mx, my, () -> { LiveActions.clearLog(); notice("Log dibersihkan"); });
            return;
        }
        List<LiveActions.EventRec> log = LiveActions.log();
        if (log.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada event. Gift, follow, dan command LIVE muncul di sini beserta hasilnya."); return; }
        LiveActions.EventRec r = log.get(Math.min(log.size() - 1, i - 1));
        Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
        int stateCol = r.bad() ? Ui.BAD : r.running() ? 0xFFFACC15 : Ui.OK;
        String state = r.state();
        int sw = font.width(state);
        g.text(font, state, x + w - sw - 10, y + 5, stateCol, false);
        g.text(font, Ui.fit(font, CLOCK.format(new java.util.Date(r.time)) + "  " + r.who + " · " + r.source, w - sw - 30), x + 10, y + 5, Ui.TEXT, false);
        String detail = r.effect + (!r.error.isEmpty() ? "  ✖ " + r.error : !r.note.isEmpty() ? "  ! " + r.note : "");
        g.text(font, Ui.fit(font, detail, w - 20), x + 10, y + 17, !r.error.isEmpty() ? Ui.BAD : !r.note.isEmpty() ? 0xFFFACC15 : Ui.MUTED, false);
    }

    private void changeMaxMobs(int delta) {
        ClientSettings.actionsMaxPerEvent = Math.max(1, Math.min(50, ClientSettings.actionsMaxPerEvent + delta));
        ClientSettings.save();
    }

    private void testEvent(String kind, String gift, int count, int diamonds) {
        if (!ClientSettings.actionsEnabled) { notice("Nyalakan \"Aksi LIVE aktif\" dulu"); return; }
        if (!McCompat.inWorld()) { notice("Masuk ke dunia dulu untuk menguji"); return; }
        TikTokLiveService.simulate(kind, "Penonton", gift, count, diamonds);   // diamonds = price of ONE piece
        notice("Event uji dikirim");
    }

    private void drawStats(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                g.text(font, Ui.fit(font, "Pesan " + TikTokLiveService.totalComments() + " · Follow " + TikTokLiveService.followCount() + " · Share " + TikTokLiveService.shareCount(), w - 20), x + 10, y + 8, Ui.TEXT, false);
                g.text(font, Ui.fit(font, "Gift " + TikTokLiveService.giftCount() + " · Like " + LiveActions.likeCount() + " · Total " + TikTokLiveService.diamondTotal() + " koin · Efek jalan " + LiveActions.spawnedTotal(), w - 20), x + 10, y + 22, Ui.MUTED, false);
                g.text(font, Ui.fit(font, TikTokLiveService.isRunning() ? "LIVE tersambung" : "LIVE belum dimulai (tab LIVE > Mulai LIVE)", w - 20), x + 10, y + 35, TikTokLiveService.isRunning() ? Ui.OK : Ui.DIM, false);
            }
            case 1 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                List<String> seen = TikTokLiveService.seenGifts();
                g.text(font, "Gift yang terlihat", x + 10, y + 8, Ui.TEXT, false);
                g.text(font, Ui.fit(font, seen.isEmpty() ? "Belum ada gift. Nama persisnya muncul di sini untuk dipakai di tab Aksi." : String.join(", ", seen), w - 20), x + 10, y + 22, Ui.MUTED, false);
            }
            case 2 -> {
                g.text(font, "Peringkat pengirim gift", x + 4, y + 8, Ui.ACCENT, false);
                button(g, x + w - 56, y + 2, 52, 16, "Reset", false, mx, my, () -> { TikTokLiveService.resetStats(); notice("Statistik direset"); });
            }
            default -> {
                List<TikTokLiveService.Gifter> top = TikTokLiveService.topGifters(10);
                if (top.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada gift pada LIVE ini."); return; }
                int idx = Math.min(top.size() - 1, i - 3);
                TikTokLiveService.Gifter gifter = top.get(idx);
                Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
                int ty = y + (h - 2 - 8) / 2;
                String rank = "#" + (idx + 1);
                g.text(font, rank, x + 10, ty, idx == 0 ? 0xFFFFD700 : idx == 1 ? 0xFFD1D5DB : idx == 2 ? 0xFFFB923C : Ui.MUTED, false);
                String right = gifter.diamonds() + " diamond · " + gifter.gifts() + " gift";
                g.text(font, right, x + w - font.width(right) - 10, ty, 0xFFFFEA75, false);
                g.text(font, Ui.fit(font, gifter.user(), w - font.width(right) - 60), x + 14 + font.width(rank), ty, Ui.TEXT, false);
            }
        }
    }

    private void drawWaypoints(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        switch (i) {
            case 0 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                double[] pos = McCompat.position();
                String line1 = pos == null ? "Masuk ke dunia untuk memakai titik" : "Posisi: " + (int) Math.floor(pos[0]) + " " + (int) Math.floor(pos[1]) + " " + (int) Math.floor(pos[2]) + " · menghadap " + McCompat.compass(McCompat.yaw());
                g.text(font, Ui.fit(font, line1, w - 20), x + 10, y + 8, Ui.TEXT, false);
                Waypoints.Point tracked = Waypoints.tracked();
                String line2 = tracked == null ? "Ketik nama di atas lalu Simpan · atau /xyoplay titik <nama>" : "Dipandu: " + tracked.name() + " · " + Waypoints.distance(tracked) + " blok · " + Waypoints.direction(tracked);
                g.text(font, Ui.fit(font, line2, w - 20), x + 10, y + 21, tracked == null ? Ui.MUTED : Ui.OK, false);
            }
            case 1 -> toggleCard(g, x, y, w, h, mx, my, "Info koordinat di layar", "XYZ, arah, dan jarak ke titik yang dipandu", ClientSettings.infoHud,
                    () -> { ClientSettings.infoHud = !ClientSettings.infoHud; ClientSettings.save(); });
            case 2 -> toggleCard(g, x, y, w, h, mx, my, "Tandai lokasi mati", "Titik \"Mati\" tersimpan otomatis dan langsung dipandu", ClientSettings.deathMarker,
                    () -> { ClientSettings.deathMarker = !ClientSettings.deathMarker; ClientSettings.save(); });
            default -> {
                List<Waypoints.Point> points = Waypoints.points();
                if (points.isEmpty()) { emptyRow(g, x, y, w, h, "Belum ada titik tersimpan."); return; }
                Waypoints.Point p = points.get(Math.min(points.size() - 1, i - 3));
                boolean isTracked = p.equals(Waypoints.tracked());
                Ui.rect(g, x, y, w, h - 2, 5, isTracked ? Ui.CARD_ACTIVE : Ui.in(mx, my, x, y, w, h - 2) ? Ui.CARD_HOVER : Ui.CARD);
                int dist = Waypoints.distance(p);
                String dimLabel = Waypoints.dimensionLabel(p.dimension());
                String sub = Waypoints.coords(p) + (dimLabel.isEmpty() ? "" : " · " + dimLabel) + (dist >= 0 ? " · " + dist + " blok " + Waypoints.direction(p) : "");
                boolean wide = w >= 330;
                int buttonsW = wide ? 148 : 78;
                g.text(font, Ui.fit(font, p.name(), w - buttonsW - 20), x + 10, y + 6, p.death() ? Ui.BAD : Ui.TEXT, false);
                g.text(font, Ui.fit(font, sub, w - buttonsW - 20), x + 10, y + 18, Ui.MUTED, false);
                int by = y + (h - 2 - 16) / 2, bx = x + w - 4;
                bx -= 40; button(g, bx, by, 38, 16, "Hapus", false, mx, my, () -> { Waypoints.remove(p); notice("Titik dihapus: " + p.name()); });
                bx -= 40; button(g, bx, by, 38, 16, isTracked ? "Lepas" : "Pandu", false, mx, my, () -> { Waypoints.track(isTracked ? null : p); notice(isTracked ? "Pemanduan dimatikan" : "Dipandu ke " + p.name()); });
                if (wide) {
                    bx -= 36; button(g, bx, by, 34, 16, "Salin", false, mx, my, () -> { Minecraft.getInstance().keyboardHandler.setClipboard(Waypoints.coords(p)); notice("Koordinat disalin: " + Waypoints.coords(p)); });
                    bx -= 28; button(g, bx, by, 26, 16, "TP", false, mx, my, () -> {
                        String dim = McCompat.dimension();
                        if (!p.dimension().isEmpty() && !dim.isEmpty() && !p.dimension().equals(dim)) { notice("Titik ada di dimensi lain"); return; }
                        notice(McCompat.runCommand("tp @s " + p.x() + " " + p.y() + " " + p.z()) ? "Teleport ke " + p.name() : "Tidak bisa teleport (butuh cheat/OP)");
                    });
                }
            }
        }
    }

    private void drawTools(GuiGraphicsExtractor g, int i, int x, int y, int w, int h, int mx, int my) {
        if (i == 0) {
            boolean on = YoutubeService.isEnabled();
            toggleCard(g, x, y, w, h, mx, my, "yt-dlp · " + (on ? "AKTIF" : "NONAKTIF"), YoutubeService.isBusy() ? "Proses berjalan…" : "Untuk YouTube, link, dan pencocokan lagu Spotify", on, () -> {
                YoutubeService.setEnabled(!YoutubeService.isEnabled());
                notice(YoutubeService.isEnabled() ? "yt-dlp diaktifkan" : "yt-dlp dinonaktifkan dan proses aktif dihentikan");
            });
        } else if (i == 1) {
            boolean on = LocalAudioPlayer.isEnabled();
            toggleCard(g, x, y, w, h, mx, my, "FFmpeg · " + (on ? "AKTIF" : "NONAKTIF"), LocalAudioPlayer.isPlayingOrPaused() ? "Audio sedang diputar" : "Decoder hanya berjalan saat dibutuhkan", on, () -> {
                LocalAudioPlayer.setEnabled(!LocalAudioPlayer.isEnabled());
                notice(LocalAudioPlayer.isEnabled() ? "FFmpeg diaktifkan" : "FFmpeg dinonaktifkan; pemutaran dihentikan");
            });
        } else {
            int part = i - 2;
            Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
            g.text(font, part == 0 ? "Diagnostik alat" : "Python & paket LIVE / TTS", x + 10, y + 7, Ui.TEXT, false);
            if (part == 0) {
                String label = ToolCheck.running() ? "Memeriksa…" : "Periksa";
                int bw = Math.max(56, font.width(label) + 18);
                button(g, x + w - bw - 8, y + 4, bw, 16, label, true, mx, my, ToolCheck::run);
            }
            List<ToolCheck.Line> lines = ToolCheck.lines();
            if (lines.isEmpty()) g.text(font, Ui.fit(font, part == 0 ? "Klik Periksa untuk mengecek ffmpeg, ffprobe, yt-dlp, python." : "Hasil muncul setelah Periksa dijalankan.", w - 20), x + 10, y + 24, Ui.MUTED, false);
            for (int k = 0; k < (part == 0 ? 3 : 4); k++) {
                int idx = part * 3 + k;
                if (idx >= lines.size()) break;
                ToolCheck.Line l = lines.get(idx);
                g.text(font, Ui.fit(font, (l.ok() ? "+ " : "- ") + l.text(), w - 20), x + 10, y + 22 + k * 11, l.ok() ? Ui.OK : Ui.BAD, false);
            }
        }
    }

    /** Card for a value that is pasted from the clipboard (API keys). The value itself is never shown in full. */
    private void secretCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, String title, String value, java.util.function.Consumer<String> setter) {
        boolean set = value != null && !value.isBlank();
        Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
        int pasteW = 54, clearW = set ? 22 : 0, bx = x + w - pasteW - clearW - (set ? 12 : 8);
        g.text(font, Ui.fit(font, title, bx - x - 16), x + 10, y + 8, Ui.TEXT, false);
        String desc = set ? "Terisi: " + value.substring(0, Math.min(4, value.length())) + "… (" + value.length() + " karakter)" : "Belum diatur · salin dari situs lalu klik Tempel";
        g.text(font, Ui.fit(font, desc, bx - x - 16), x + 10, y + 21, set ? Ui.OK : Ui.MUTED, false);
        int by = y + (h - 2 - 20) / 2;
        button(g, bx, by, pasteW, 20, "Tempel", true, mx, my, () -> {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            String v = clip == null ? "" : clip.replaceAll("\\s", "").replaceAll("\\p{Cntrl}", "");
            if (v.isEmpty()) { notice("Clipboard kosong. Salin nilainya dulu, lalu klik Tempel"); return; }
            if (v.length() > 200) { notice("Teks di clipboard terlalu panjang untuk kunci API"); return; }
            setter.accept(v); ClientSettings.save();
            notice(title + " disimpan");
        });
        if (set) button(g, bx + pasteW + 4, by, clearW, 20, "X", false, mx, my, () -> { setter.accept(""); ClientSettings.save(); notice(title + " dihapus"); });
    }

    private void toggleCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, String title, String desc, boolean on, Runnable toggle) {
        boolean hover = Ui.in(mx, my, x, y, w, h - 2);
        Ui.box(g, x, y, w, h - 2, 5, hover ? Ui.CARD_HOVER : Ui.CARD, Ui.BORDER);
        g.text(font, Ui.fit(font, title, w - 66), x + 10, y + 8, Ui.TEXT, false);
        g.text(font, Ui.fit(font, desc, w - 66), x + 10, y + 21, Ui.MUTED, false);
        int sx = x + w - 40, sy = y + (h - 2 - 14) / 2;
        Ui.rect(g, sx, sy, 28, 14, 7, on ? Ui.ACCENT : Ui.TRACK);
        Ui.rect(g, on ? sx + 16 : sx + 2, sy + 2, 10, 10, 5, on ? Ui.ON_ACCENT : Ui.TEXT);
        hit(x, y, w, h - 2, toggle);
    }

    private void actionCard(GuiGraphicsExtractor g, int x, int y, int w, int h, int mx, int my, String title, String desc, String label, Runnable action) {
        Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
        int bw = Math.max(66, font.width(label) + 20);
        g.text(font, Ui.fit(font, title, w - bw - 28), x + 10, y + 8, Ui.TEXT, false);
        g.text(font, Ui.fit(font, desc, w - bw - 28), x + 10, y + 21, Ui.MUTED, false);
        button(g, x + w - bw - 8, y + (h - 2 - 20) / 2, bw, 20, label, false, mx, my, action);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean primary, int mx, int my, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        Ui.rect(g, x, y, w, h, 5, primary ? (hover ? 0xFF6AD0FB : Ui.ACCENT) : (hover ? Ui.BTN_HOVER : Ui.BTN));
        int color = primary ? Ui.ON_ACCENT : hover ? Ui.TEXT : 0xFFC9D5E8;
        String fitted = Ui.fit(font, label, w - 6);
        g.text(font, fitted, x + (w - font.width(fitted)) / 2, y + (h - 8) / 2, color, false);
        hit(x, y, w, h, action);
    }

    private void iconButton(GuiGraphicsExtractor g, int x, int y, int w, int h, int kind, boolean primary, int mx, int my, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        Ui.rect(g, x, y, w, h, h / 2, primary ? (hover ? 0xFF6AD0FB : Ui.ACCENT) : (hover ? Ui.BTN_HOVER : Ui.BTN));
        int c = primary ? Ui.ON_ACCENT : hover ? Ui.TEXT : 0xFFC9D5E8;
        switch (kind) {
            case 0 -> Ui.iconPrev(g, x, y, w, h, c);
            case 1 -> Ui.iconPlay(g, x, y, w, h, c);
            case 2 -> Ui.iconPause(g, x, y, w, h, c);
            case 3 -> Ui.iconNext(g, x, y, w, h, c);
            default -> Ui.iconStop(g, x, y, w, h, c);
        }
        hit(x, y, w, h, action);
    }

    // ---------------------------------------------------------------- player

    private void drawPlayer(GuiGraphicsExtractor g, int mx, int my) {
        Ui.box(g, cx, playerY, cw, playerH, 6, Ui.CARD, Ui.BORDER);
        boolean has = LocalAudioPlayer.isPlayingOrPaused();
        double dur = LocalAudioPlayer.durationSeconds();
        double pos = draggingSeek ? seekPreview * Math.max(0, dur) : LocalAudioPlayer.positionSeconds();
        String clock = dur > 0 ? time(pos) + " / " + time(dur) : has ? time(pos) : "--:-- / --:--";
        int clockW = font.width(clock);
        String title = has ? LocalAudioPlayer.currentTitle() : "Belum ada lagu diputar";
        g.text(font, Ui.marquee(font, title, cw - 32 - clockW), cx + 10, playerY + 7, has ? Ui.TEXT : Ui.MUTED, false);
        g.text(font, clock, cx + cw - 10 - clockW, playerY + 7, Ui.MUTED, false);

        // progress / seek bar
        barX = cx + 10; barW = cw - 20; trackY = playerY + 21; trackH = 4;
        double frac = dur > 0 ? Math.max(0, Math.min(1, pos / dur)) : 0;
        int fillW = (int) (barW * frac);
        boolean barHover = Ui.in(mx, my, barX, trackY - 4, barW, 12) || draggingSeek;
        Ui.rect(g, barX, trackY, barW, trackH, 2, Ui.TRACK);
        if (fillW > 0) Ui.hGrad(g, barX, trackY, Math.max(3, fillW), trackH, Ui.ACCENT, Ui.ACCENT2);
        if (has && dur > 0 && barHover) Ui.rect(g, barX + fillW - 3, trackY - 2, 6, 8, 3, Ui.TEXT);
        hit(barX, trackY - 4, barW, 12, () -> {
            if (LocalAudioPlayer.durationSeconds() > 0) { draggingSeek = true; seekPreview = Math.max(0, Math.min(1, (clickX - barX) / (double) barW)); }
        });

        // transport controls
        int by = playerY + 29, bh = 15, x0 = cx + 10;
        iconButton(g, x0, by, 20, bh, 0, false, mx, my, () -> { Path p = Playback.step(-1); if (p == null) notice("Pustaka musik masih kosong"); else notice("Memutar: " + MusicLibrary.title(p)); });
        boolean showPause = has && !LocalAudioPlayer.isPaused();
        iconButton(g, x0 + 24, by, 28, bh, showPause ? 2 : 1, true, mx, my, () -> {
            if (LocalAudioPlayer.isPlayingOrPaused()) LocalAudioPlayer.togglePause();
            else { Path p = Playback.step(1); if (p == null) notice("Pustaka musik masih kosong"); }
        });
        iconButton(g, x0 + 56, by, 20, bh, 3, false, mx, my, () -> { Path p = Playback.step(1); if (p == null) notice("Pustaka musik masih kosong"); else notice("Memutar: " + MusicLibrary.title(p)); });
        iconButton(g, x0 + 80, by, 20, bh, 4, false, mx, my, () -> { LocalAudioPlayer.stop(); notice("Pemutaran dihentikan"); });

        // shuffle / repeat chips
        int chipX = x0 + 106;
        if (cw >= 250) {
            chip(g, chipX, by, "Acak", ClientSettings.shuffle, mx, my, () -> { ClientSettings.shuffle = !ClientSettings.shuffle; ClientSettings.save(); });
            chip(g, chipX + font.width("Acak") + 14, by, "Ulang", ClientSettings.repeat, mx, my, () -> { ClientSettings.repeat = !ClientSettings.repeat; ClientSettings.save(); });
            chipX += font.width("Acak") + font.width("Ulang") + 28;
        }

        // volume
        String pct = Math.round(ClientSettings.volume * 100) + "%";
        int pctW = font.width("100%"), pctX = cx + cw - 10 - pctW;
        int minVol = chipX + 14;
        volW = Math.min(100, pctX - 6 - minVol);
        if (volW < 24) { volW = 0; }
        if (volW > 0) {
            volX = pctX - 6 - volW;
            Ui.tri(g, volX - 9, by + 4, 4, 7, Ui.MUTED, false);
            g.fill(volX - 12, by + 6, volX - 9, by + 9, Ui.MUTED);
            int vy = by + (bh - 3) / 2;
            Ui.rect(g, volX, vy, volW, 3, 1, Ui.TRACK);
            int vf = (int) (volW * ClientSettings.volume);
            if (vf > 0) Ui.rect(g, volX, vy, Math.max(2, vf), 3, 1, Ui.ACCENT);
            boolean vHover = Ui.in(mx, my, volX - 3, by, volW + 6, bh) || draggingVolume;
            Ui.rect(g, volX + vf - 3, by + 3, 6, 9, 3, vHover ? Ui.TEXT : 0xFFC9D5E8);
            hit(volX - 3, by, volW + 6, bh, () -> { draggingVolume = true; setVolumeFromX(clickX); });
        }
        g.text(font, pct, pctX + pctW - font.width(pct), by + (bh - 8) / 2, Ui.TEXT, false);
    }

    private void chip(GuiGraphicsExtractor g, int x, int y, String label, boolean on, int mx, int my, Runnable toggle) {
        int w = font.width(label) + 10;
        boolean hover = Ui.in(mx, my, x, y, w, 15);
        Ui.rect(g, x, y, w, 15, 5, on ? Ui.CARD_ACTIVE : hover ? Ui.BTN_HOVER : Ui.BTN);
        g.text(font, label, x + 5, y + 4, on ? Ui.ACCENT : Ui.MUTED, false);
        hit(x, y, w, 15, toggle);
    }

    private void setVolumeFromX(double x) {
        if (volW <= 0) return;
        LocalAudioPlayer.setVolume((float) Math.max(0, Math.min(1, (x - volX) / volW)));
    }

    private void drawFooter(GuiGraphicsExtractor g) {
        long now = System.currentTimeMillis();
        boolean showNotice = now < noticeUntil;
        String message;
        if (showNotice) message = notice;
        else message = switch (mode) {
            case YOUTUBE, TIKTOK, SPOTIFY, LINK -> YoutubeService.status();
            case LIVE -> TikTokLiveService.status();
            default -> "Klik lagu untuk memutar · Spasi jeda · " + XYOPlayClient.keyName() + " / Esc untuk menutup";
        };
        double pr = YoutubeService.progress();
        int reserve = pr >= 0 ? 70 : 0;
        g.text(font, Ui.fit(font, message, cw - reserve), cx, footerY + 2, showNotice ? Ui.ACCENT : Ui.MUTED, false);
        if (pr >= 0) {
            int bw = 60, bx = cx + cw - bw;
            Ui.rect(g, bx, footerY + 4, bw, 4, 2, Ui.TRACK);
            Ui.rect(g, bx, footerY + 4, Math.max(2, (int) (bw * Math.min(1, pr))), 4, 2, Ui.ACCENT);
        }
    }

    // ----------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            clickX = (int) event.x(); clickY = (int) event.y();
            inputFocused = false;
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in(event.x(), event.y(), h.x(), h.y(), h.w(), h.h())) { h.action().run(); return true; }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == 0) {
            if (draggingVolume) { setVolumeFromX(event.x()); return true; }
            if (draggingSeek) { seekPreview = Math.max(0, Math.min(1, (event.x() - barX) / Math.max(1.0, barW))); return true; }
            if (draggingScroll) { scrollToY(event.y()); return true; }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingVolume) ClientSettings.save();
        if (draggingSeek) {
            double dur = LocalAudioPlayer.durationSeconds();
            if (dur > 0) LocalAudioPlayer.seek(seekPreview * dur);
        }
        draggingVolume = false; draggingSeek = false; draggingScroll = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int sidebarRight = px + sideW + 4;
        if (mouseX >= px && mouseX <= sidebarRight && mouseY >= py + 25 && mouseY <= py + ph - 12) {
            int available = ph - ((rail ? 30 : 42)) - 10;
            int targetVisible = Math.min(Mode.values().length, 7);
            int tabH = Math.max(9, Math.min(22, available / targetVisible - 2));
            int visibleModes = Math.max(1, available / (tabH + 2));
            navScroll = Math.max(0, Math.min(navScroll - (int) Math.signum(scrollY), Mode.values().length - visibleModes));
            return true;
        }
        if (mouseX >= cx && mouseX <= cx + cw && mouseY >= listTop && mouseY <= listBottom) {
            scroll -= (int) Math.signum(scrollY) * Math.max(1, (int) Math.ceil(Math.abs(scrollY)));
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        String typed = event.codepointAsString();
        if (inputFocused && hasField() && typed != null && !typed.isEmpty() && typed.codePoints().allMatch(c -> !Character.isISOControl(c))) {
            insert(typed);
            return true;
        }
        return super.charTyped(event);
    }

    private void insert(String s) {
        String t = allSelected ? "" : text();
        int at = allSelected ? 0 : Math.min(cursorPos, t.length());
        allSelected = false;
        text(t.substring(0, at) + s + t.substring(at));
        cursorPos = at + s.length();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (event.isEscape()) { onClose(); return true; }
        if (inputFocused && hasField()) {
            String t = text();
            if (event.hasControlDown() && key == GLFW.GLFW_KEY_A) { allSelected = true; cursorPos = t.length(); return true; }
            if (event.hasControlDown() && key == GLFW.GLFW_KEY_V) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null && !clip.isEmpty()) insert(clip.replaceAll("[\\p{Cntrl}]", " ").trim());
                return true;
            }
            if (event.hasControlDown() && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_X) && allSelected) {
                Minecraft.getInstance().keyboardHandler.setClipboard(t);
                if (key == GLFW.GLFW_KEY_X) { text(""); cursorPos = 0; }
                allSelected = false; return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                if (allSelected) { text(""); cursorPos = 0; allSelected = false; return true; }
                if (key == GLFW.GLFW_KEY_BACKSPACE && cursorPos > 0) { text(t.substring(0, cursorPos - 1) + t.substring(cursorPos)); cursorPos--; }
                else if (key == GLFW.GLFW_KEY_DELETE && cursorPos < t.length()) text(t.substring(0, cursorPos) + t.substring(cursorPos + 1));
                return true;
            }
            if (key == GLFW.GLFW_KEY_LEFT) { allSelected = false; cursorPos = Math.max(0, cursorPos - 1); return true; }
            if (key == GLFW.GLFW_KEY_RIGHT) { allSelected = false; cursorPos = Math.min(t.length(), cursorPos + 1); return true; }
            if (key == GLFW.GLFW_KEY_HOME) { allSelected = false; cursorPos = 0; return true; }
            if (key == GLFW.GLFW_KEY_END) { allSelected = false; cursorPos = t.length(); return true; }
            if (event.isConfirmation()) { doAction(); if (mode != Mode.LOCAL && mode != Mode.FILTER && mode != Mode.BANNED && mode != Mode.ACTIONS && mode != Mode.GIVEAWAY && mode != Mode.PHRASES && mode != Mode.BOT) inputFocused = false; return true; }
            return true; // swallow everything else while typing (so letters like X do not close the window)
        }
        if (key == GLFW.GLFW_KEY_X) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { LocalAudioPlayer.togglePause(); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scroll++; clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_UP) { scroll--; clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll += visibleItems(); clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll -= visibleItems(); clampScroll(); return true; }
        if (event.hasControlDown() && key == GLFW.GLFW_KEY_F && hasField()) { inputFocused = true; cursorPos = text().length(); return true; }
        if (event.hasControlDown() && key == GLFW.GLFW_KEY_O) { openFolder(); return true; }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    // --------------------------------------------------------------- helpers

    private void openFolder() {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(MusicLibrary.folder().toFile());
            notice("Folder musik: " + MusicLibrary.folder());
        } catch (Exception e) { notice("Tidak bisa membuka folder otomatis: " + MusicLibrary.folder()); }
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString(); int i = n.lastIndexOf('.'); return i < 0 ? "AUDIO" : n.substring(i + 1).toUpperCase(Locale.ROOT);
    }

    private static String time(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return "--:--";
        int s = (int) seconds; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
