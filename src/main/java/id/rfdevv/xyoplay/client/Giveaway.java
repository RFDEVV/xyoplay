package id.rfdevv.xyoplay.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Undian penonton: komentar kata kunci (bawaan !ikut) = peserta, lalu satu klik mengundi pemenang. */
final class Giveaway {
    record Winner(String name, String uid, long time) {}

    private static final Map<String, String[]> ENTRANTS = new LinkedHashMap<>();   // key -> {name, uid}
    private static final List<Winner> WINNERS = new ArrayList<>();
    private static final Random RNG = new Random();
    private static volatile boolean open = true;

    private Giveaway() {}

    static synchronized boolean isOpen() { return open; }
    static synchronized void setOpen(boolean v) { open = v; }
    static synchronized int size() { return ENTRANTS.size(); }
    static synchronized List<Winner> winners() { List<Winner> l = new ArrayList<>(WINNERS); Collections.reverse(l); return l; }

    /** Called for every LIVE comment. @return true when the comment joined the giveaway. */
    static synchronized boolean onComment(String user, String uid, String text) {
        if (!open || text == null) return false;
        String t = text.strip().toLowerCase();
        String word = ClientSettings.giveawayWord;
        if (!(t.equals(word) || t.startsWith(word + " "))) return false;
        String key = (uid == null || uid.isEmpty() ? user : uid).toLowerCase();
        if (ENTRANTS.containsKey(key)) return false;
        ENTRANTS.put(key, new String[] {user, uid == null ? "" : uid});
        return true;
    }

    static synchronized void addTest(int n) {
        for (int i = 0; i < n; i++) {
            String name = "Penonton" + (ENTRANTS.size() + 1);
            ENTRANTS.put("uji" + name.toLowerCase(), new String[] {name, ""});
        }
    }

    static synchronized void reset() { ENTRANTS.clear(); }
    static synchronized void clearWinners() { WINNERS.clear(); }

    /** Picks one winner (a person can only win once per round). @return the winner or null when nobody is left. */
    static Winner draw() {
        String[] pick;
        synchronized (Giveaway.class) {
            if (ENTRANTS.isEmpty()) return null;
            List<String> keys = new ArrayList<>(ENTRANTS.keySet());
            String key = keys.get(RNG.nextInt(keys.size()));
            pick = ENTRANTS.remove(key);
            WINNERS.add(new Winner(pick[0], pick[1], System.currentTimeMillis()));
            if (WINNERS.size() > 100) WINNERS.remove(0);
        }
        MemeSounds.play("winner");
        LiveBanner.push("PEMENANG UNDIAN: " + pick[0], 0xFFFFD700, pick[1]);
        LiveActions.bigTitle("PEMENANG!", LiveActions.safeNameOr(pick[0]));
        McCompat.runCommandAsync("playsound minecraft:entity.player.levelup master @s ~ ~ ~ 1 1", false, err -> { });
        McCompat.runCommandAsync("playsound minecraft:entity.firework_rocket.blast master @s ~ ~ ~ 1 1", false, err -> { });
        TikTokLiveService.speak("Selamat " + pick[0] + ", kamu pemenang undian", 1);
        return new Winner(pick[0], pick[1], System.currentTimeMillis());
    }
}
