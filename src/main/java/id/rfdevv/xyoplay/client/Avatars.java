package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profile pictures of the people who follow / gift. The reader downloads each picture once, shrinks it to 24x24 pixels
 * and sends it as base64 RGB; here it is kept in memory and drawn pixel by pixel (round), so no texture API is needed.
 */
final class Avatars {
    private Avatars() {}

    static final int SRC = 24;
    private static final ConcurrentHashMap<String, int[]> PICS = new ConcurrentHashMap<>();

    /** @param rgbBase64 SRC*SRC*3 bytes of RGB */
    static void put(String uid, String rgbBase64) {
        if (uid == null || uid.isBlank() || rgbBase64 == null) return;
        try {
            byte[] raw = Base64.getDecoder().decode(rgbBase64);
            if (raw.length < SRC * SRC * 3) return;
            int[] px = new int[SRC * SRC];
            for (int i = 0; i < px.length; i++) {
                px[i] = 0xFF000000 | ((raw[i * 3] & 0xFF) << 16) | ((raw[i * 3 + 1] & 0xFF) << 8) | (raw[i * 3 + 2] & 0xFF);
            }
            if (PICS.size() > 400) PICS.clear();
            PICS.put(key(uid), px);
        } catch (IllegalArgumentException ignored) { }
    }

    static boolean has(String uid) { return uid != null && PICS.containsKey(key(uid)); }
    static void clear() { PICS.clear(); }
    private static String key(String uid) { return uid.toLowerCase(java.util.Locale.ROOT); }

    /** Round picture (or a coloured disc with the first letter while the picture is still downloading). */
    static void draw(GuiGraphicsExtractor g, int x, int y, int size, String uid, String name, int accent, float alpha) {
        int[] px = uid == null || uid.isBlank() ? null : PICS.get(key(uid));
        double r = size / 2.0;
        Ui.disc(g, x + size / 2, y + size / 2, size / 2 + 1, Ui.alpha(accent, alpha));       // border ring
        if (px == null) {
            Ui.disc(g, x + size / 2, y + size / 2, size / 2 - 1, Ui.alpha(0xFF1F2A43, alpha));
            Font font = Minecraft.getInstance().font;
            String letter = name == null || name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
            g.text(font, letter, x + size / 2 - font.width(letter) / 2, y + size / 2 - 4, Ui.alpha(Ui.TEXT, alpha), false);
            return;
        }
        double inner = r - 1.2;
        for (int dy = 0; dy < size; dy++) {
            int sy = Math.min(SRC - 1, dy * SRC / size);
            int runStart = -1, runColor = 0;
            for (int dx = 0; dx <= size; dx++) {
                boolean inside = dx < size && Math.hypot(dx + 0.5 - r, dy + 0.5 - r) <= inner;
                int color = inside ? px[sy * SRC + Math.min(SRC - 1, dx * SRC / size)] : 0;
                if (runStart >= 0 && (!inside || color != runColor)) {
                    g.fill(x + runStart, y + dy, x + dx, y + dy + 1, Ui.alpha(runColor, alpha));
                    runStart = -1;
                }
                if (inside && runStart < 0) { runStart = dx; runColor = color; }
            }
        }
    }
}
