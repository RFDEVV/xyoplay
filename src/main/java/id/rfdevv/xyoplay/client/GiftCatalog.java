package id.rfdevv.xyoplay.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Known TikTok gifts (the names TikTok sends) plus Indonesian nicknames, so "mawar" works for "Rose".
 * Any other name can still be typed: matching is done on the gift name that arrives from the LIVE.
 */
final class GiftCatalog {
    private GiftCatalog() {}

    record Gift(String name, String... aliases) {}

    static final List<Gift> GIFTS = List.of(
            new Gift("Rose", "mawar", "bunga"),
            new Gift("Coffee", "kopi", "secangkir kopi", "coffee cup"),
            new Gift("Orange Juice", "es jeruk", "jus jeruk", "orange"),
            new Gift("Nasi Padang", "nasi padang", "padang", "padang rice", "rice padang"),
            new Gift("Kamu Hebat Banget", "kamu hebat", "hebat banget", "youre awesome", "awesome", "amazing", "kamu luar biasa"),
            new Gift("TikTok", "tiktok"),
            new Gift("GG", "gg"),
            new Gift("Ice Cream Cone", "es krim", "eskrim", "ice cream"),
            new Gift("Finger Heart", "jari hati", "fingerheart", "love jari"),
            new Gift("Heart Me", "hati", "heartme"),
            new Gift("Doughnut", "donat", "donut"),
            new Gift("Perfume", "parfum"),
            new Gift("Hand Hearts", "hati tangan", "handheart"),
            new Gift("Sunglasses", "kacamata"),
            new Gift("Corgi", "corgi", "anjing corgi"),
            new Gift("Paper Crane", "bangau", "bangau kertas"),
            new Gift("Money Gun", "pistol uang", "moneygun"),
            new Gift("Hat and Mustache", "topi kumis", "topi", "hat", "hat mustache", "hat & mustache"),
            new Gift("Cap", "topi biasa", "cap"),
            new Gift("Confetti", "konfeti"),
            new Gift("Wink Wink", "kedip"),
            new Gift("Love You", "cinta", "aku cinta kamu"),
            new Gift("Hearts", "banyak hati"),
            new Gift("Little Crown", "mahkota kecil", "mahkota"),
            new Gift("Crown", "mahkota besar"),
            new Gift("Birthday Cake", "kue ulang tahun", "kue"),
            new Gift("Boxing Gloves", "sarung tinju", "tinju"),
            new Gift("Gold Boxing Gloves", "sarung tinju emas"),
            new Gift("Bubble Gum", "permen karet"),
            new Gift("Sceptre", "tongkat"),
            new Gift("Dancing Hands", "tangan menari"),
            new Gift("Mirror", "cermin"),
            new Gift("Gift Box", "kado", "hadiah"),
            new Gift("Galaxy", "galaksi"),
            new Gift("Lion", "singa"),
            new Gift("Universe", "alam semesta", "semesta"),
            new Gift("TikTok Universe", "tiktok semesta"),
            new Gift("Drama Queen", "ratu drama"),
            new Gift("Gem Gun", "pistol permata"),
            new Gift("Interstellar", "antarbintang"),
            new Gift("Sports Car", "mobil balap", "mobil"),
            new Gift("Diamond Flight", "pesawat berlian"),
            new Gift("Diamond", "berlian"),
            new Gift("Train", "kereta"),
            new Gift("Concert", "konser"),
            new Gift("Stage", "panggung"),
            new Gift("Castle", "istana"),
            new Gift("Phoenix", "burung api", "feniks"),
            new Gift("Fire Phoenix", "phoenix api"),
            new Gift("Yacht", "kapal pesiar", "kapal"),
            new Gift("Whale Diving", "paus", "paus menyelam"),
            new Gift("Leon the Kitten", "kucing leon", "leon"),
            new Gift("Falcon", "elang"),
            new Gift("Swan", "angsa"),
            new Gift("Flying Jets", "jet", "pesawat jet"),
            new Gift("Cheer You Up", "semangat"),
            new Gift("Rosa", "rosa", "mawar besar"),
            new Gift("Rose Bouquet", "buket mawar"),
            new Gift("Lightning", "petir"),
            new Gift("Star", "bintang"),
            new Gift("Planet", "planet"),
            new Gift("Rocket", "roket"),
            new Gift("Treasure Chest", "peti harta", "harta karun"),
            new Gift("Lucky Airdrop Box", "airdrop"),
            new Gift("Fireworks", "kembang api"),
            new Gift("Balloon", "balon"),
            new Gift("Teddy Bear", "boneka beruang", "teddy"),
            new Gift("Panda", "panda"),
            new Gift("Unicorn", "unicorn", "kuda poni"),
            new Gift("Dragon", "naga"),
            new Gift("Motorcycle", "motor"),
            new Gift("Helicopter", "helikopter"));

    static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "mawar" -> "Rose"; an unknown name is returned unchanged (trimmed). */
    static String canonical(String typed) {
        String k = key(typed);
        if (k.isEmpty()) return "";
        for (Gift g : GIFTS) {
            if (key(g.name()).equals(k)) return g.name();
            for (String a : g.aliases()) if (key(a).equals(k)) return g.name();
        }
        return typed.strip();
    }

    static List<String> suggest(String typed, int max) {
        String k = key(typed);
        List<String> out = new ArrayList<>();
        if (k.isEmpty()) return out;
        for (Gift g : GIFTS) {
            boolean hit = key(g.name()).contains(k);
            for (String a : g.aliases()) if (key(a).contains(k)) hit = true;
            if (hit) { out.add(g.name()); if (out.size() >= max) break; }
        }
        return out;
    }

    static Map<String, String> names() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Gift g : GIFTS) m.put(g.name(), g.aliases().length > 0 ? g.aliases()[0] : "");
        return m;
    }
}
