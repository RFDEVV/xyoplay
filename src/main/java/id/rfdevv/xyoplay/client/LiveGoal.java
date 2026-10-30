package id.rfdevv.xyoplay.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Target follower: the bar shows the account's REAL follower total (read from TikTok when the LIVE starts and every
 * minute, plus +1 per new follow) against the next multiple of the step (default 1000). Crossing a multiple fires
 * the "goal" trigger.
 */
final class LiveGoal {
    private static volatile int total = -1;       // real follower total, -1 = not known yet
    private static volatile int session;          // follows seen since the LIVE started
    private static volatile int highest = -1;     // highest multiple already celebrated
    private static volatile int reachedCount;

    private LiveGoal() {}

    static int reached() { return reachedCount; }
    static int total() { return total; }
    static void reset() { total = -1; session = 0; highest = -1; reachedCount = 0; }

    static int step() { return ClientSettings.goalStep; }

    /** Followers counted for the bar (the real total, or this LIVE's follows until the total is known). */
    static int shown() { return total >= 0 ? total : session; }

    static int nextTarget() { int s = step(); return s <= 0 ? 0 : (shown() / s + 1) * s; }

    /** Followers still missing until the next target. */
    static int remaining() { return Math.max(0, nextTarget() - shown()); }

    /** Progress inside the current step, 0..1. */
    static double fraction() { int s = step(); return s <= 0 ? 0 : (shown() % s) / (double) s; }

    /** The real total arrived (profile page / room info / follow event). The first number only sets the baseline. */
    static void setTotal(int n) {
        if (n < 0) return;
        boolean first = total < 0;
        total = n;
        int s = step();
        if (first && s > 0) highest = Math.max(highest, n / s);   // never celebrate the number we started with
        check();
    }

    /** Called after every new follow. */
    static void onFollow(int ignoredSessionTotal) {
        session++;
        if (total >= 0) total++;
        check();
    }

    private static void check() {
        int s = step();
        if (s <= 0) return;
        int bucket = shown() / s;
        if (highest < 0) { highest = bucket; return; }
        int hits = 0;
        while (bucket > highest && hits < 3) { highest++; hits++; reachedCount++; announce(highest * s); }
        if (bucket > highest) highest = bucket;
    }

    static void announce(int target) {
        String text = "Target " + target + " follower tercapai!";
        LiveBanner.push(text, 0xFF4ADE80, "");
        MemeSounds.play("goal");
        LiveActions.onEvent(new LiveActions.Event("goal", "Penonton", "", text, "", 1, 0, 0, "goal-" + target + "-" + System.nanoTime()));
        TikTokLiveService.speak("Target " + target + " follower tercapai, terima kasih semuanya", 1);
    }

    static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientSettings.liveHud || mc.player == null || mc.options.hideGui || mc.screen instanceof MusicScreen) return;
        if (!TikTokLiveService.isRunning() && TikTokLiveService.diamondTotal() == 0 && step() <= 0) return;
        Font font = mc.font;
        List<TikTokLiveService.Gifter> top = TikTokLiveService.topGifters(3);
        int s = step();
        int rows = (top.isEmpty() ? 0 : top.size() + 1) + (s > 0 ? 2 : 0);
        if (rows == 0) return;
        int w = 150, h = 8 + rows * 11 + (top.isEmpty() || s <= 0 ? 0 : 2);
        int x = mc.getWindow().getGuiScaledWidth() - w - 6, y = mc.getWindow().getGuiScaledHeight() / 2 - h / 2;
        Ui.box(g, x, y, w, h, 5, 0xCC10131A, 0x55FFFFFF);
        int ty = y + 5;
        if (!top.isEmpty()) {
            g.text(font, "TOP GIFTER", x + 7, ty, 0xFFFFD700, false);
            ty += 11;
            int rank = 1;
            for (TikTokLiveService.Gifter gf : top) {
                g.text(font, Ui.fit(font, rank++ + ". " + gf.user(), w - 50), x + 7, ty, Ui.TEXT, false);
                String c = Integer.toString(gf.diamonds());
                g.text(font, c, x + w - 7 - font.width(c), ty, 0xFFFACC15, false);
                ty += 11;
            }
        }
        if (s > 0) {
            String known = total >= 0 ? "" : "*";
            String label = "FOLLOWER " + shown() + known + " / " + nextTarget();
            g.text(font, Ui.fit(font, label, w - 14), x + 7, ty + (top.isEmpty() ? 0 : 2), 0xFF4ADE80, false);
            int by = ty + 11 + (top.isEmpty() ? 0 : 2);
            g.fill(x + 7, by, x + w - 7, by + 4, 0x55FFFFFF);
            g.fill(x + 7, by, x + 7 + (int) ((w - 14) * fraction()), by + 4, 0xFF4ADE80);
        }
    }
}
