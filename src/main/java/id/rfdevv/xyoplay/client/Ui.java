package id.rfdevv.xyoplay.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small drawing toolkit (rounded boxes, gradients, vector icons) built only on fill() and text(). */
final class Ui {
    private Ui() {}

    static final int BACKDROP = 0xB0060910;
    static final int PANEL = 0xFF0E131E;
    static final int SIDEBAR = 0xFF0A0E17;
    static final int CARD = 0xFF151C2B;
    static final int CARD_HOVER = 0xFF1C2639;
    static final int CARD_ACTIVE = 0xFF15304A;
    static final int BORDER = 0xFF222C41;
    static final int TEXT = 0xFFEAF0FA;
    static final int MUTED = 0xFF8493AB;
    static final int DIM = 0xFF566176;
    static final int ACCENT = 0xFF38BDF8;
    static final int ACCENT2 = 0xFF8B7CF8;
    static final int OK = 0xFF4ADE80;
    static final int BAD = 0xFFF87171;
    static final int BTN = 0xFF1F2A43;
    static final int BTN_HOVER = 0xFF2B3B5E;
    static final int TRACK = 0xFF2A3449;
    static final int ON_ACCENT = 0xFF06101C;

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static int alpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        if (a < 5) a = 5;
        return (a << 24) | (color & 0xFFFFFF);
    }

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (int) (((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * t);
        int ar = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int ag = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int ab = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    /** Filled rounded rectangle. */
    static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x, y, x + w, y + h, color); return; }
        if (h > 2 * r) g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /** Rounded rectangle with a 1px border (use opaque colors). */
    static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int fill, int border) {
        rect(g, x, y, w, h, r, border);
        rect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    static void hGrad(GuiGraphicsExtractor g, int x, int y, int w, int h, int c1, int c2) {
        if (w <= 0 || h <= 0) return;
        int step = Math.max(1, w / 48);
        for (int i = 0; i < w; i += step) {
            int sw = Math.min(step, w - i);
            g.fill(x + i, y, x + i + sw, y + h, lerp(c1, c2, (float) i / w));
        }
    }

    static void tri(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, boolean right) {
        for (int j = 0; j < h; j++) {
            double t = h <= 1 ? 0 : Math.abs((2.0 * j) / (h - 1) - 1);
            int rw = Math.max(1, (int) Math.round(w * (1 - t)));
            if (right) g.fill(x, y + j, x + rw, y + j + 1, color);
            else g.fill(x + w - rw, y + j, x + w, y + j + 1, color);
        }
    }

    /** Animated equalizer bars. */
    static void eq(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, boolean active) {
        int n = 4, bw = Math.max(1, (w - (n - 1)) / n);
        long t = System.currentTimeMillis();
        double[] idle = {0.35, 0.6, 0.45, 0.3};
        for (int i = 0; i < n; i++) {
            double f = active ? 0.28 + 0.72 * Math.abs(Math.sin(t / (170.0 + i * 55) + i * 1.9)) : idle[i];
            int bh = Math.max(2, (int) (h * f));
            int bx = x + i * (bw + 1);
            g.fill(bx, y + h - bh, bx + bw, y + h, color);
        }
    }

    // ---- transport icons, centered in a box ----
    static void iconPlay(GuiGraphicsExtractor g, int bx, int by, int bw, int bh, int c) {
        int s = Math.max(6, Math.min(bw, bh) * 5 / 10);
        int w = Math.max(4, s * 4 / 5);
        tri(g, bx + (bw - w) / 2 + 1, by + (bh - s) / 2, w, s, c, true);
    }
    static void iconPause(GuiGraphicsExtractor g, int bx, int by, int bw, int bh, int c) {
        int s = Math.max(6, Math.min(bw, bh) * 5 / 10);
        int bar = Math.max(2, s / 3), gap = Math.max(2, s / 3), total = bar * 2 + gap;
        int x0 = bx + (bw - total) / 2, y0 = by + (bh - s) / 2;
        g.fill(x0, y0, x0 + bar, y0 + s, c);
        g.fill(x0 + bar + gap, y0, x0 + total, y0 + s, c);
    }
    static void iconStop(GuiGraphicsExtractor g, int bx, int by, int bw, int bh, int c) {
        int s = Math.max(5, Math.min(bw, bh) * 4 / 10);
        g.fill(bx + (bw - s) / 2, by + (bh - s) / 2, bx + (bw - s) / 2 + s, by + (bh - s) / 2 + s, c);
    }
    static void iconPrev(GuiGraphicsExtractor g, int bx, int by, int bw, int bh, int c) {
        int s = Math.max(6, Math.min(bw, bh) * 5 / 10), w = Math.max(4, s * 3 / 4);
        int x0 = bx + (bw - (w + 3)) / 2, y0 = by + (bh - s) / 2;
        g.fill(x0, y0, x0 + 2, y0 + s, c);
        tri(g, x0 + 3, y0, w, s, c, false);
    }
    static void iconNext(GuiGraphicsExtractor g, int bx, int by, int bw, int bh, int c) {
        int s = Math.max(6, Math.min(bw, bh) * 5 / 10), w = Math.max(4, s * 3 / 4);
        int x0 = bx + (bw - (w + 3)) / 2, y0 = by + (bh - s) / 2;
        tri(g, x0, y0, w, s, c, true);
        g.fill(x0 + w + 1, y0, x0 + w + 3, y0 + s, c);
    }

    /** 10x10 icons for navigation tabs. */
    static void tabIcon(GuiGraphicsExtractor g, int id, int x, int y, int c) {
        switch (id) {
            case 0 -> {
                g.fill(x, y + 1, x + 10, y + 3, c);
                g.fill(x, y + 4, x + 10, y + 6, c);
                g.fill(x, y + 7, x + 7, y + 9, c);
            }
            case 1 -> {
                rect(g, x, y + 1, 10, 8, 2, c);
                tri(g, x + 4, y + 3, 3, 4, ON_ACCENT, true);
            }
            case 2 -> {
                g.fill(x + 5, y, x + 7, y + 8, c);
                g.fill(x + 7, y, x + 9, y + 2, c);
                rect(g, x + 1, y + 6, 6, 4, 2, c);
            }
            case 3 -> {
                g.fill(x + 4, y, x + 6, y + 5, c);
                g.fill(x + 1, y + 4, x + 9, y + 5, c);
                g.fill(x + 2, y + 5, x + 8, y + 6, c);
                g.fill(x + 3, y + 6, x + 7, y + 7, c);
                g.fill(x + 4, y + 7, x + 6, y + 8, c);
                g.fill(x, y + 9, x + 10, y + 10, c);
            }
            case 4 -> {
                rect(g, x, y, 10, 10, 5, alpha(c, 0.35f));
                rect(g, x + 2, y + 2, 6, 6, 3, c);
            }
            case 5 -> {
                g.fill(x, y + 1, x + 10, y + 2, c);
                g.fill(x + 6, y, x + 8, y + 3, c);
                g.fill(x, y + 5, x + 10, y + 6, c);
                g.fill(x + 2, y + 4, x + 4, y + 7, c);
                g.fill(x, y + 9, x + 10, y + 10, c);
                g.fill(x + 5, y + 8, x + 7, y + 11, c);
            }
            case 7 -> {
                g.fill(x + 1, y + 1, x + 9, y + 2, c);
                g.fill(x + 1, y + 4, x + 9, y + 5, c);
                g.fill(x + 1, y + 7, x + 9, y + 8, c);
                g.fill(x + 2, y + 2, x + 3, y + 3, c);
                g.fill(x + 2, y + 5, x + 3, y + 6, c);
            }
            case 8 -> {
                g.fill(x + 1, y + 2, x + 4, y + 5, c);
                g.fill(x + 6, y + 2, x + 9, y + 5, c);
                g.fill(x + 2, y + 5, x + 8, y + 7, c);
                g.fill(x + 4, y + 7, x + 6, y + 9, c);
            }
            case 9 -> {
                rect(g, x, y, 10, 10, 5, c);
                g.fill(x + 4, y + 2, x + 6, y + 6, ON_ACCENT);
                g.fill(x + 5, y + 5, x + 8, y + 7, ON_ACCENT);
            }
            case 10 -> {
                g.fill(x, y + 1, x + 8, y + 2, c);
                g.fill(x + 2, y + 4, x + 10, y + 5, c);
                g.fill(x, y + 7, x + 8, y + 8, c);
                g.fill(x + 1, y + 2, x + 2, y + 3, c);
                g.fill(x + 8, y + 5, x + 9, y + 6, c);
            }
            case 11 -> {
                rect(g, x, y, 10, 10, 5, c);
                g.fill(x + 2, y + 3, x + 8, y + 4, ON_ACCENT);
                g.fill(x + 3, y + 5, x + 7, y + 6, ON_ACCENT);
                g.fill(x + 3, y + 7, x + 6, y + 8, ON_ACCENT);
            }
            default -> {
                rect(g, x, y, 4, 4, 1, c);
                rect(g, x + 6, y, 4, 4, 1, c);
                rect(g, x, y + 6, 4, 4, 1, c);
                rect(g, x + 6, y + 6, 4, 4, 1, c);
            }
        }
    }

    static void iconSearch(GuiGraphicsExtractor g, int x, int y, int c, int bg) {
        rect(g, x, y, 7, 7, 3, c);
        rect(g, x + 1, y + 1, 5, 5, 2, bg);
        g.fill(x + 6, y + 6, x + 8, y + 8, c);
        g.fill(x + 8, y + 8, x + 9, y + 9, c);
    }

    static String fit(Font font, String text, int maxW) {
        if (text == null) return "";
        if (maxW <= 6) return "";
        if (font.width(text) <= maxW) return text;
        int end = text.length();
        while (end > 1 && font.width(text.substring(0, end) + "…") > maxW) end--;
        return text.substring(0, Math.max(0, end)) + "…";
    }

    /** Scrolls long text horizontally when it does not fit. */
    static String marquee(Font font, String text, int maxW) {
        if (text == null) return "";
        if (maxW <= 6 || font.width(text) <= maxW) return text;
        String ring = text + "     " + text;
        int cycle = text.length() + 5;
        int start = (int) ((System.currentTimeMillis() / 230) % cycle);
        int end = start;
        while (end < ring.length() && font.width(ring.substring(start, end + 1)) <= maxW) end++;
        return ring.substring(start, end);
    }
}
