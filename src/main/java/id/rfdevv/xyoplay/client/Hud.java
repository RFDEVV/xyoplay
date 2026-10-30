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

    private static final int C_X = 0xFFF87171, C_Y = 0xFF4ADE80, C_Z = 0xFF60A5FA, C_DEATH = 0xFFEF4444;

    /** Position card: coloured X/Y/Z, compass badge and, when a point is tracked, its name, distance and a live arrow. */
    static void renderInfo(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientSettings.infoHud || mc.player == null || mc.options.hideGui || mc.screen instanceof MusicScreen) return;
        double[] pos = McCompat.position();
        if (pos == null) return;
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        Waypoints.Point t = Waypoints.tracked();
        int dist = t == null ? -1 : Waypoints.distance(t);
        boolean showTarget = t != null;

        int w = 118, headH = 15, rowH = 11;
        int h = headH + 3 * rowH + 6 + (showTarget ? 30 : 0);
        int margin = 6, corner = ClientSettings.infoCorner;
        boolean right = corner % 2 == 1, bottom = corner >= 2;
        int x = right ? sw - w - margin : margin, y = bottom ? sh - h - margin : margin;
        // do not sit on top of the "now playing" card when both use the same corner
        if (corner == ClientSettings.hudCorner && ClientSettings.hudMode != 0 && LocalAudioPlayer.isPlayingOrPaused()) {
            int shift = (ClientSettings.hudMode == 1 ? 16 : 34) + 4;
            y += bottom ? -shift : shift;
        }

        Ui.box(g, x, y, w, h, 5, 0xEE0E131E, 0xFF2A3550);
        // header: label + compass badge
        g.fill(x + 8, y + 6, x + 10, y + 11, Ui.ACCENT);
        g.text(font, "POSISI", x + 13, y + 4, Ui.MUTED, false);
        String dir = McCompat.compass(McCompat.yaw());
        int bw = font.width(dir) + 8;
        Ui.box(g, x + w - bw - 6, y + 3, bw, 11, 4, 0xFF15304A, Ui.ACCENT);
        g.text(font, dir, x + w - bw - 2, y + 5, Ui.ACCENT, false);

        int ry = y + headH + 1;
        coordRow(g, font, x, ry, w, "X", (int) Math.floor(pos[0]), C_X);
        coordRow(g, font, x, ry + rowH, w, "Y", (int) Math.floor(pos[1]), C_Y);
        coordRow(g, font, x, ry + 2 * rowH, w, "Z", (int) Math.floor(pos[2]), C_Z);

        if (showTarget) {
            int ty = y + headH + 3 * rowH + 4;
            g.fill(x + 6, ty, x + w - 6, ty + 1, 0xFF2A3550);
            int col = t.death() ? C_DEATH : Ui.ACCENT2;
            int acx = x + 15, acy = ty + 15;
            Ui.disc(g, acx, acy, 10, 0xFF151C2B);
            Ui.ring(g, acx, acy, 10, 1, col);
            if (dist >= 0) Ui.arrow(g, acx, acy, 6, Waypoints.relativeAngle(t), col);
            else g.text(font, "?", acx - 2, acy - 4, Ui.MUTED, false);
            String name = Ui.fit(font, t.name(), w - 46);
            g.text(font, name, x + 30, ty + 5, Ui.TEXT, false);
            String sub = dist < 0 ? "dimensi lain" : dist + " m";
            g.text(font, sub, x + 30, ty + 16, dist < 0 ? Ui.MUTED : col, false);
        }
    }

    private static void coordRow(GuiGraphicsExtractor g, Font font, int x, int y, int w, String label, int value, int color) {
        g.fill(x + 8, y + 1, x + 10, y + 9, color);
        g.text(font, label, x + 14, y + 1, color, false);
        String v = Integer.toString(value);
        g.text(font, v, x + w - 8 - font.width(v), y + 1, Ui.TEXT, false);
    }

    // ------------------------------------------------------------------ map-pin marker in the world

    /**
     * Draws the tracked point (death place or saved point) as a pin on the screen, visible from far away. When the point is
     * outside the view the pin sticks to the screen edge with an arrow. It disappears once the player arrives.
     */
    static void renderMarker(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientSettings.markerShow || mc.player == null || mc.options.hideGui || mc.screen != null) return;
        Waypoints.Point t = Waypoints.tracked();
        if (t == null) return;
        int dist = Waypoints.distance(t);
        if (dist < 0) return;
        double[] pos = McCompat.position();
        if (pos == null) return;

        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        double yaw = Math.toRadians(McCompat.yaw()), pitch = Math.toRadians(McCompat.pitch());
        double dx = t.x() + 0.5 - pos[0], dy = t.y() + 1.0 - (pos[1] + 1.62), dz = t.z() + 0.5 - pos[2];
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;   // right x forward = up
        double depth = dx * fx + dy * fy + dz * fz;
        double sx = dx * rx + dy * ry + dz * rz, sy = dx * ux + dy * uy + dz * uz;

        double scale = (sh / 2.0) / Math.tan(Math.toRadians(McCompat.fov()) / 2.0);
        double px, py;
        boolean onScreen = false;
        if (depth > 0.1) {
            px = sw / 2.0 + sx / depth * scale;
            py = sh / 2.0 - sy / depth * scale;
            onScreen = px > 14 && px < sw - 14 && py > 30 && py < sh - 14;
        } else { px = sw / 2.0 + (sx >= 0 ? 1 : -1) * 10000; py = sh / 2.0; if (Math.abs(sx) < 0.01) { px = sw / 2.0; py = sh + 10000; } }

        int col = t.death() ? C_DEATH : Ui.ACCENT2;
        Font font = mc.font;
        String label = t.name() + "  " + dist + " m";
        long now = System.currentTimeMillis();

        if (onScreen) {
            int cx = (int) Math.round(px), tipY = (int) Math.round(py);
            // pulsing ground ring
            double pulse = (now % 1400) / 1400.0;
            int rr = 5 + (int) (7 * pulse);
            Ui.ring(g, cx, tipY, rr, 1, Ui.alpha(col, (float) (1 - pulse) * 0.8f + 0.1f));
            // pin: tip at the point, round head above
            int headR = 7, headCy = tipY - 14;
            for (int j = 0; j < 8; j++) {          // tapered tail
                int half = Math.max(0, 5 - j * 5 / 8);
                g.fill(cx - half, headCy + 5 + j, cx + half + 1, headCy + 6 + j, col);
            }
            Ui.disc(g, cx, headCy, headR + 1, 0xFF0B0F18);
            Ui.disc(g, cx, headCy, headR, col);
            Ui.disc(g, cx, headCy, 3, 0xFFFFFFFF);
            if (t.death()) {   // tiny cross over the head for "death"
                g.fill(cx - 1, headCy - 2, cx + 2, headCy + 3, col);
                g.fill(cx - 2, headCy - 1, cx + 3, headCy + 2, col);
            }
            int tw = font.width(label) + 10;
            int lx = Math.max(2, Math.min(sw - tw - 2, cx - tw / 2)), ly = headCy - headR - 15;
            Ui.box(g, lx, ly, tw, 12, 4, 0xEE0E131E, col);
            g.text(font, label, lx + 5, ly + 2, Ui.TEXT, false);
        } else {
            // clamp to the screen edge along the ray from the centre
            double cxs = sw / 2.0, cys = sh / 2.0, vx = px - cxs, vy = py - cys;
            double mx = cxs - 22, my = cys - 30;
            double k = Math.min(mx / Math.max(1e-6, Math.abs(vx)), my / Math.max(1e-6, Math.abs(vy)));
            int ex = (int) Math.round(cxs + vx * k), ey = (int) Math.round(cys + vy * k);
            double angle = Math.atan2(vx, -vy);
            Ui.disc(g, ex, ey, 11, 0xFF0B0F18);
            Ui.disc(g, ex, ey, 10, Ui.alpha(col, 0.35f));
            Ui.ring(g, ex, ey, 10, 2, col);
            Ui.arrow(g, ex, ey, 6, angle, 0xFFFFFFFF);
            int tw = font.width(label) + 10;
            int lx = Math.max(2, Math.min(sw - tw - 2, ex - tw / 2));
            int ly = ey < sh / 2 ? ey + 14 : ey - 26;
            Ui.box(g, lx, ly, tw, 12, 4, 0xEE0E131E, col);
            g.text(font, label, lx + 5, ly + 2, Ui.TEXT, false);
        }
    }

    private static String clock(double s) {
        int v = (int) Math.max(0, s);
        return (v / 60) + ":" + String.format("%02d", v % 60);
    }
}
