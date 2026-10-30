package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pop-up lines at the top of the game screen: "Budi follow!", "Siti kirim 3x Rose → creeper"… With the profile picture of
 * the viewer when it is known. Great for screen capture.
 */
final class LiveBanner {
    private LiveBanner() {}

    private record Line(String text, int color, long at, String uid, String name) {}

    private static final List<Line> LINES = new CopyOnWriteArrayList<>();
    private static final long LIFETIME = 7000;

    static void push(String text, int color) { push(text, color, ""); }

    /** @param uid TikTok @username of the viewer whose picture should be shown (empty = no picture) */
    static void push(String text, int color, String uid) {
        if (!ClientSettings.liveBanner || text == null) return;
        String plain = text.replaceAll("§.", "");
        String name = plain.contains(" dari ") ? plain.substring(plain.indexOf(" dari ") + 6).split(" → ")[0] : plain.split("[ :]")[0];
        LINES.add(new Line(plain, color, System.currentTimeMillis(), uid == null ? "" : uid, name));
        while (LINES.size() > 5) LINES.remove(0);
    }

    static void render(GuiGraphicsExtractor g) {
        if (LINES.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        List<Line> alive = new ArrayList<>();
        for (Line l : LINES) { if (now - l.at() > LIFETIME) LINES.remove(l); else alive.add(l); }
        if (alive.isEmpty()) return;
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int y = 6;
        for (Line l : alive) {
            long age = now - l.at();
            float alpha = age < 250 ? age / 250f : age > LIFETIME - 700 ? Math.max(0f, (LIFETIME - age) / 700f) : 1f;
            if (alpha <= 0.04f) continue;
            boolean pic = ClientSettings.bannerAvatar && !l.uid().isEmpty();
            int h = pic ? 26 : 16, left = pic ? 34 : 14;
            String text = Ui.fit(font, l.text(), Math.max(60, sw - 40 - left));
            int w = font.width(text) + left + 10, x = (sw - w) / 2;
            Ui.box(g, x, y, w, h, 6, Ui.alpha(0xFF0E131E, alpha * 0.93f), Ui.alpha(l.color(), alpha));
            if (pic) Avatars.draw(g, x + 5, y + 3, 20, l.uid(), l.name(), l.color(), alpha);
            else g.fill(x + 6, y + 6, x + 10, y + 10, Ui.alpha(l.color(), alpha));
            g.text(font, text, x + left, y + (h - 8) / 2, Ui.alpha(Ui.TEXT, alpha), false);
            y += h + 3;
        }
    }
}
