package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.nio.file.Path;
import java.util.Objects;

/** In-game "now playing" overlay: a small pill (mini) or a compact card, positioned in any corner. */
final class Hud {
    private Hud() {}

    private static Path lastPath;
    private static boolean lastPaused;
    private static long shownAt;

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || ClientSettings.hudMode == 0
                || !LocalAudioPlayer.isPlayingOrPaused() || mc.screen instanceof MusicScreen) return;

        long now = System.currentTimeMillis();
        Path current = LocalAudioPlayer.currentPath();
        boolean paused = LocalAudioPlayer.isPaused();
        if (!Objects.equals(current, lastPath) || paused != lastPaused) { lastPath = current; lastPaused = paused; shownAt = now; }
        float alpha = 1f;
        if (ClientSettings.hudAutoHide) {
            long dt = now - shownAt;
            if (dt > 5000) alpha = 1f - (dt - 5000) / 700f;
            if (alpha <= 0.04f) return;
        }

        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        String title = LocalAudioPlayer.currentTitle();
        int margin = 6;
        boolean right = ClientSettings.hudCorner % 2 == 1, bottom = ClientSettings.hudCorner >= 2;

        if (ClientSettings.hudMode == 1) {
            int textW = Math.min(font.width(title), 120);
            int w = 8 + 10 + 6 + textW + 9, h = 16;
            int x = right ? sw - w - margin : margin, y = bottom ? sh - h - margin : margin;
            Ui.box(g, x, y, w, h, 4, Ui.alpha(0xFF0E131E, alpha * 0.92f), Ui.alpha(0xFF2A3550, alpha));
            Ui.eq(g, x + 8, y + 3, 10, 10, Ui.alpha(Ui.ACCENT, alpha), !paused);
            g.text(font, Ui.marquee(font, title, 120), x + 24, y + 4, Ui.alpha(paused ? Ui.MUTED : Ui.TEXT, alpha), false);
        } else {
            int w = 150, h = 34;
            int x = right ? sw - w - margin : margin, y = bottom ? sh - h - margin : margin;
            Ui.box(g, x, y, w, h, 5, Ui.alpha(0xFF0E131E, alpha * 0.92f), Ui.alpha(0xFF2A3550, alpha));
            Ui.eq(g, x + 8, y + 7, 11, 11, Ui.alpha(Ui.ACCENT, alpha), !paused);
            g.text(font, Ui.marquee(font, title, w - 34), x + 24, y + 6, Ui.alpha(Ui.TEXT, alpha), false);
            double dur = LocalAudioPlayer.durationSeconds(), pos = LocalAudioPlayer.positionSeconds();
            String sub = (paused ? "Dijeda" : "Diputar") + (dur > 0 ? "  ·  " + clock(pos) + " / " + clock(dur) : "");
            g.text(font, Ui.fit(font, sub, w - 34), x + 24, y + 17, Ui.alpha(Ui.MUTED, alpha), false);
            int barX = x + 8, barW = w - 16;
            g.fill(barX, y + h - 5, barX + barW, y + h - 3, Ui.alpha(Ui.TRACK, alpha));
            if (dur > 0) Ui.hGrad(g, barX, y + h - 5, (int) (barW * Math.max(0, Math.min(1, pos / dur))), 2, Ui.alpha(Ui.ACCENT, alpha), Ui.alpha(Ui.ACCENT2, alpha));
        }
    }

    private static String clock(double s) {
        int v = (int) Math.max(0, s);
        return (v / 60) + ":" + String.format("%02d", v % 60);
    }
}
