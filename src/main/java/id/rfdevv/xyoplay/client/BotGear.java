package id.rfdevv.xyoplay.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the bot "owns": a virtual backpack, the tools and armor it wears, crafting recipes (a workbench and
 * furnace are assumed to be at hand, so iron ore counts as ingots), mining rules and what to gather next.
 */
final class BotGear {
    private BotGear() {}

    static final String[] TIER_NAMES = {"tangan kosong", "kayu", "batu", "besi", "berlian"};
    static final String[] TIER_ITEM = {"", "wooden", "stone", "iron", "diamond"};
    static final String[] SLOTS = {"pickaxe", "sword", "axe", "chestplate", "leggings", "helmet", "boots"};
    private static final int[] COST = {3, 2, 3, 8, 7, 5, 4};
    private static final int[] STICKS = {2, 1, 2, 0, 0, 0, 0};
    private static final String[] MAT = {"", "planks", "cobblestone", "iron_ingot", "diamond"};

    // ------------------------------------------------------------------ mining rules

    static int tierNeeded(String id) {
        if (id == null) return 0;
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.equals("obsidian")) return 4;
        if (n.contains("diamond_ore") || n.contains("emerald_ore") || n.contains("gold_ore") || n.contains("redstone_ore")) return 3;
        if (n.contains("iron_ore") || n.contains("lapis_ore") || n.contains("copper_ore")) return 2;
        if (n.endsWith("_ore")) return 1;
        for (String s : new String[] {"stone", "cobblestone", "deepslate", "andesite", "diorite", "granite", "tuff", "netherrack", "basalt", "blackstone", "sandstone", "terracotta", "calcite", "dripstone"})
            if (n.equals(s) || n.endsWith("_" + s) || n.endsWith(s)) return 1;
        return 0;
    }

    /** Backpack item gained from breaking a block (null = nothing useful). */
    static String drop(String id) {
        if (id == null) return null;
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.endsWith("_log") || n.endsWith("_stem") || n.endsWith("_wood")) return "log";
        if (n.endsWith("coal_ore")) return "coal";
        if (n.endsWith("iron_ore")) return "iron_ingot";
        if (n.endsWith("gold_ore")) return "gold_ingot";
        if (n.endsWith("diamond_ore")) return "diamond";
        if (n.endsWith("copper_ore")) return "copper";
        if (n.endsWith("lapis_ore")) return "lapis";
        if (n.endsWith("redstone_ore")) return "redstone";
        if (n.endsWith("emerald_ore")) return "emerald";
        if (n.equals("dirt") || n.equals("grass_block") || n.equals("podzol") || n.equals("mycelium") || n.equals("coarse_dirt") || n.equals("rooted_dirt") || n.equals("mud")) return "dirt";
        if (n.equals("sand") || n.equals("red_sand")) return "sand";
        if (n.equals("gravel")) return "gravel";
        if (n.contains("leaves")) return null;
        if (tierNeeded(id) >= 1 || n.equals("cobblestone") || n.equals("stone")) return "cobblestone";
        return null;
    }

    static boolean isWood(String id) { return id != null && (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem")); }

    /** Ticks to break a block with the bot's current tools. */
    static int digTicks(String id, Gear g) {
        String n = id == null ? "" : id;
        if (isWood(n)) return Math.max(3, (int) (10 / (1 + 0.6 * g.tier("axe"))));
        int need = tierNeeded(n);
        if (need == 0) return n.contains("leaves") ? 3 : 6;
        int base = n.endsWith("_ore") ? 20 : n.contains("obsidian") ? 80 : 14;
        return Math.max(3, (int) (base / (1 + 0.5 * g.tier("pickaxe"))));
    }

    // ------------------------------------------------------------------ backpack

    static final class Inv {
        final Map<String, Integer> m = new LinkedHashMap<>();
        int get(String k) { return m.getOrDefault(k, 0); }
        void add(String k, int n) { if (n != 0) { int v = get(k) + n; if (v <= 0) m.remove(k); else m.put(k, Math.min(v, 9999)); } }
        boolean take(String k, int n) { if (get(k) < n) return false; add(k, -n); return true; }
        int blocks() { return get("dirt") + get("cobblestone"); }
        /** Takes one placeable block; returns its item id or null. */
        String takeBlock() {
            for (String k : new String[] {"dirt", "cobblestone"}) if (take(k, 1)) return "minecraft:" + k;
            return null;
        }
        Inv copy() { Inv c = new Inv(); c.m.putAll(m); return c; }
        void setFrom(Inv o) { m.clear(); m.putAll(o.m); }
    }

    static final class Gear {
        final Map<String, Integer> tier = new HashMap<>();
        int tier(String slot) { return tier.getOrDefault(slot, 0); }
        void set(String slot, int t) { tier.put(slot, t); }
    }

    static int slotIndex(String slot) { for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i].equals(slot)) return i; return -1; }

    static String itemId(String slot, int tier) { return "minecraft:" + TIER_ITEM[tier] + "_" + slot; }

    static String equipSlot(String slot) {
        return switch (slot) {
            case "helmet" -> "armor.head";
            case "chestplate" -> "armor.chest";
            case "leggings" -> "armor.legs";
            case "boots" -> "armor.feet";
            default -> "weapon.mainhand";
        };
    }

    static boolean isArmor(String slot) { return slot.equals("helmet") || slot.equals("chestplate") || slot.equals("leggings") || slot.equals("boots"); }

    /** Raw units (planks-equivalent / cobblestone / ingots / diamonds) a slot costs at a tier, including sticks. */
    static int materialCost(String slot, int tier) {
        int i = slotIndex(slot);
        int c = COST[i];
        if (tier == 1) c += (STICKS[i] + 1) / 2;   // sticks are made from planks
        return c;
    }

    private static boolean ensurePlanks(Inv inv, int n) {
        while (inv.get("planks") < n) { if (!inv.take("log", 1)) return false; inv.add("planks", 4); }
        return true;
    }

    private static boolean ensureSticks(Inv inv, int n) {
        while (inv.get("stick") < n) { if (!ensurePlanks(inv, 2)) return false; inv.take("planks", 2); inv.add("stick", 4); }
        return true;
    }

    /** Crafts one item if the backpack allows it. */
    static boolean craft(Inv inv, String slot, int tier) {
        int i = slotIndex(slot);
        if (i < 0 || tier < 1 || tier > 4) return false;
        Inv t = inv.copy();
        boolean ok;
        if (tier == 1) {
            ok = ensurePlanks(t, COST[i]);
            if (ok) { t.take("planks", COST[i]); ok = ensureSticks(t, STICKS[i]); if (ok) t.take("stick", STICKS[i]); }
        } else {
            ok = t.take(MAT[tier], COST[i]) && ensureSticks(t, STICKS[i]);
            if (ok) t.take("stick", STICKS[i]);
        }
        if (ok) inv.setFrom(t);
        return ok;
    }

    /** Wood in log units still needed (for the wooden tier) or raw units for higher tiers. */
    static int have(Inv inv, int tier) {
        return tier == 1 ? inv.get("planks") + inv.get("log") * 4 : inv.get(MAT[tier]);
    }

    static int maxTier(String slot) { return isArmor(slot) ? 3 : slot.equals("axe") ? 3 : 4; }

    /** The upgrade the bot wants next: {slot, tier}, or null when fully equipped. */
    static String[] nextUpgrade(Gear g) {
        for (String s : SLOTS) {
            int cur = g.tier(s), mx = maxTier(s);
            int next = Math.max(cur + 1, isArmor(s) ? 3 : 1);
            // skip a tier of wood/stone when the next one up is just as easy
            if (next <= mx) return new String[] {s, Integer.toString(next)};
        }
        return null;
    }

    /** Upgrades gear with whatever is in the backpack. @return slots that changed, e.g. "pickaxe:2". */
    static List<String> autoCraft(Inv inv, Gear g) {
        List<String> done = new ArrayList<>();
        boolean again = true;
        int guard = 0;
        while (again && guard++ < 40) {
            again = false;
            for (String s : SLOTS) {
                int best = 0;
                for (int t = maxTier(s); t > g.tier(s) && t >= (isArmor(s) ? 3 : 1); t--) {
                    Inv probe = inv.copy();
                    if (craft(probe, s, t)) { best = t; break; }
                }
                // a pickaxe/sword/axe is worth having before spending iron on armor: go in SLOTS order
                if (best > 0) { craft(inv, s, best); g.set(s, best); done.add(s + ":" + best); again = true; break; }
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ what to gather

    enum Need { BLOCKS, LOGS, STONE, IRON, DIAMOND, NONE }

    /** Blocks that satisfy a need. */
    static boolean matches(Need n, String id) {
        if (id == null) return false;
        String s = id.startsWith("minecraft:") ? id.substring(10) : id;
        return switch (n) {
            case LOGS -> s.endsWith("_log");
            case STONE, BLOCKS -> s.equals("stone") || s.equals("cobblestone") || s.equals("deepslate") || s.equals("andesite") || s.equals("diorite") || s.equals("granite") || s.equals("tuff") || (n == Need.BLOCKS && (s.equals("dirt") || s.equals("grass_block")));
            case IRON -> s.endsWith("iron_ore");
            case DIAMOND -> s.endsWith("diamond_ore");
            default -> false;
        };
    }

    /** What the bot should go and collect to progress (own gear first, then spare building blocks). */
    static Need nextNeed(Inv inv, Gear g, int reserve) {
        String[] up = nextUpgrade(g);
        if (up != null) {
            int tier = Integer.parseInt(up[1]);
            int need = materialCost(up[0], tier);
            if (tier >= 2 && inv.get("stick") < STICKS[slotIndex(up[0])] && inv.get("planks") + inv.get("log") * 4 < 2) return Need.LOGS;
            if (have(inv, tier) < need) return tier == 1 ? Need.LOGS : tier == 2 ? Need.STONE : tier == 3 ? Need.IRON : Need.DIAMOND;
        }
        if (inv.blocks() < reserve) return Need.BLOCKS;
        return up == null ? Need.NONE : Need.NONE;
    }

    // ------------------------------------------------------------------ orders for the player

    /** Slot name from user text: "pickaxe", "pedang", "armor"... */
    static String slotFromText(String t) {
        t = t.toLowerCase();
        if (t.startsWith("pick") || t.contains("beliung") || t.contains("kapak_tambang")) return "pickaxe";
        if (t.startsWith("sword") || t.contains("pedang")) return "sword";
        if (t.startsWith("axe") || t.contains("kapak")) return "axe";
        if (t.startsWith("helm")) return "helmet";
        if (t.startsWith("chest") || t.contains("baju") || t.contains("zirah")) return "chestplate";
        if (t.startsWith("leg") || t.contains("celana")) return "leggings";
        if (t.startsWith("boot") || t.contains("sepatu")) return "boots";
        return null;
    }

    static int tierFromText(String t) {
        t = t.toLowerCase();
        for (int i = 4; i >= 1; i--) if (t.contains(TIER_ITEM[i]) || t.contains(TIER_NAMES[i])) return i;
        if (t.contains("kayu")) return 1;
        return 0;
    }
}
