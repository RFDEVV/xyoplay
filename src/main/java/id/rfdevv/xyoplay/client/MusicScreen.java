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

    private enum Mode {
        LOCAL("Pustaka", "Pustaka lokal", "Koleksi audio di perangkatmu", 0),
        YOUTUBE("YouTube", "Cari YouTube", "Cari lagu lalu klik hasilnya untuk mengunduh audio", 1),
        TIKTOK("TikTok", "Cari TikTok", "Cari kata kunci, @akun, #hashtag, atau link video TikTok", 2),
        SPOTIFY("Spotify", "Cari Spotify", "Cari lagu lalu klik hasilnya untuk mengunduh audionya", 11),
        LINK("Link", "Unduh via link", "Unduh audio dari link YouTube atau TikTok", 3),
        LIVE("LIVE", "TikTok LIVE", "Bacakan komentar LIVE dengan TTS lokal gratis", 4),
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

    private boolean hasField() { return mode == Mode.LOCAL || mode == Mode.YOUTUBE || mode == Mode.TIKTOK || mode == Mode.SPOTIFY || mode == Mode.LINK || mode == Mode.LIVE || (mode == Mode.PLAYLISTS && selectedPlaylist == null); }
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

    private void notice(String message) { notice = message; noticeUntil = System.currentTimeMillis() + 3200; }

    // ---------------------------------------------------------------- list model

    private int count() {
        return switch (mode) {
            case LOCAL -> visibleTracks.size();
            case YOUTUBE -> YoutubeService.results(YoutubeService.Source.YOUTUBE).size();
            case TIKTOK -> YoutubeService.results(YoutubeService.Source.TIKTOK).size();
            case SPOTIFY -> YoutubeService.results(YoutubeService.Source.SPOTIFY).size();
            case LINK -> 2;
            case LIVE -> 2 + Math.max(1, TikTokLiveService.comments().size());
            case SETTINGS -> 11;
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
            case LIVE -> i == 0 ? 62 : i == 1 ? 42 : 26;
            case SETTINGS -> 42;
            case TOOLS -> i >= 2 ? 60 : 42;
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
            g.text(font, Ui.fit(font, editingMetadata ? "Isi judul dan artis dipisahkan dengan |" : mode.subtitle, cw - btnW - 12 - (mode == Mode.TIKTOK ? 100 : mode == Mode.LOCAL && !editingMetadata ? 64 : 0)), cx, py + 26, Ui.MUTED, false);
            int by = py + 10;
            if (label != null) {
                button(g, cx + cw - btnW, by, btnW, 22, label, true, mx, my, this::doAction);
                if (mode == Mode.LOCAL && !editingMetadata) button(g, cx + cw - btnW - 64, by, 60, 22, "Folder", false, mx, my, this::openFolder);
                if (mode == Mode.TIKTOK) button(g, cx + cw - btnW - 100, by, 96, 22, "Urut: " + YoutubeService.tiktokSortLabel(), false, mx, my,
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
            String total = TikTokLiveService.totalComments() > 0 ? " · " + TikTokLiveService.totalComments() + " komentar" : "";
            g.text(font, Ui.fit(font, (on ? "AKTIF" : "SIAP") + " · " + user + total, w - 20), x + 10, y + 8, on ? Ui.OK : Ui.MUTED, false);
            g.text(font, Ui.fit(font, TikTokLiveService.status(), w - 20), x + 10, y + 21, Ui.TEXT, false);
            int bw = Math.min(92, (w - 20 - 8) / 3), by = y + 35;
            button(g, x + 10, by, bw, 18, "Mulai LIVE", true, mx, my, () -> {
                ClientSettings.tiktokUsername = text().trim().replaceFirst("^@", ""); ClientSettings.save();
                TikTokLiveService.start(ClientSettings.tiktokUsername); notice("Memulai pembaca TikTok LIVE");
            });
            button(g, x + 14 + bw, by, bw, 18, "Stop LIVE", false, mx, my, () -> { TikTokLiveService.stop(); notice("Pembaca TikTok LIVE dihentikan"); });
            button(g, x + 18 + bw * 2, by, bw, 18, ClientSettings.ttsEnabled ? "TTS: ON" : "TTS: OFF", false, mx, my, () -> {
                ClientSettings.ttsEnabled = !ClientSettings.ttsEnabled; ClientSettings.save();
                notice("TTS " + (ClientSettings.ttsEnabled ? "aktif" : "nonaktif") + " · berlaku saat Mulai LIVE berikutnya");
            });
        } else if (i == 1) {
            actionCard(g, x, y, w, h, mx, my, "Paket Python untuk LIVE", "Pasang TikTokLive + pyttsx3 lewat pip (butuh internet)",
                    TikTokLiveService.isInstalling() ? "Memasang…" : "Instal paket",
                    () -> { TikTokLiveService.installPackages(); notice("Memasang paket Python… lihat status di atas"); });
        } else {
            List<TikTokLiveService.Comment> comments = TikTokLiveService.comments();
            Ui.rect(g, x, y, w, h - 2, 5, Ui.CARD);
            int ty = y + (h - 2 - 8) / 2;
            if (comments.isEmpty()) { g.text(font, Ui.fit(font, "Komentar akan tampil di sini setelah LIVE tersambung.", w - 16), x + 8, ty, Ui.DIM, false); return; }
            TikTokLiveService.Comment c = comments.get(Math.min(comments.size() - 1, i - 2));
            String user = Ui.fit(font, c.user(), w / 3);
            g.text(font, user, x + 8, ty, Ui.ACCENT, false);
            int ux = x + 8 + font.width(user) + 6;
            g.text(font, Ui.fit(font, c.text(), w - (ux - x) - 8), ux, ty, Ui.TEXT, false);
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
            case 5 -> toggleCard(g, x, y, w, h, mx, my, "Bacakan komentar LIVE (TTS)", "Suara sistem offline · berlaku saat Mulai LIVE berikutnya", ClientSettings.ttsEnabled,
                    () -> { ClientSettings.ttsEnabled = !ClientSettings.ttsEnabled; ClientSettings.save(); });
            case 6 -> {
                Ui.box(g, x, y, w, h - 2, 5, Ui.CARD, Ui.BORDER);
                String key = XYOPlayClient.keyName();
                g.text(font, Ui.fit(font, "Tombol buka / tutup GUI: " + key, w - 20), x + 10, y + 8, key.equals("BELUM DIATUR") ? Ui.BAD : Ui.TEXT, false);
                g.text(font, Ui.fit(font, "Ubah di Options > Controls > Key Binds · atau ketik /xyoplay", w - 20), x + 10, y + 21, Ui.MUTED, false);
            }
            case 7 -> secretCard(g, x, y, w, h, mx, my, "Spotify Client ID", ClientSettings.spotifyClientId, v -> ClientSettings.spotifyClientId = v);
            case 8 -> secretCard(g, x, y, w, h, mx, my, "Spotify Client Secret", ClientSettings.spotifySecret, v -> ClientSettings.spotifySecret = v);
            case 9 -> secretCard(g, x, y, w, h, mx, my, "Euler Stream API key (LIVE, opsional)", ClientSettings.eulerApiKey, v -> ClientSettings.eulerApiKey = v);
            default -> actionCard(g, x, y, w, h, mx, my, "Folder musik lokal", "Buka folder untuk menambah file audio", "Buka folder", this::openFolder);
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
            g.text(font, part == 0 ? "Diagnostik alat" : "Python & paket LIVE", x + 10, y + 7, Ui.TEXT, false);
            if (part == 0) {
                String label = ToolCheck.running() ? "Memeriksa…" : "Periksa";
                int bw = Math.max(56, font.width(label) + 18);
                button(g, x + w - bw - 8, y + 4, bw, 16, label, true, mx, my, ToolCheck::run);
            }
            List<ToolCheck.Line> lines = ToolCheck.lines();
            if (lines.isEmpty()) g.text(font, Ui.fit(font, part == 0 ? "Klik Periksa untuk mengecek ffmpeg, ffprobe, yt-dlp, python." : "Hasil muncul setelah Periksa dijalankan.", w - 20), x + 10, y + 24, Ui.MUTED, false);
            for (int k = 0; k < 3; k++) {
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
            if (event.isConfirmation()) { doAction(); if (mode != Mode.LOCAL) inputFocused = false; return true; }
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
