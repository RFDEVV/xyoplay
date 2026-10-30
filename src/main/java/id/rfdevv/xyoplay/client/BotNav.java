package id.rfdevv.xyoplay.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Bounded A* over "standing cells" (feet position). Moves: walk, jump up one block, drop up to three blocks,
 * bridge over gaps, pillar up by placing blocks, and dig through rock. Every search is capped by a node budget so it
 * never costs more than a tiny, predictable slice of a tick.
 */
final class BotNav {
    enum Act { WALK, JUMP, DROP, PILLAR, BRIDGE, DIG }

    /** Stand at (x,y,z) after this step. */
    record Step(int x, int y, int z, Act act) {}

    interface Goal {
        boolean reached(int x, int y, int z);
        double h(int x, int y, int z);
    }

    static final class Opts {
        boolean dig, place;
        int blocks;          // placeable blocks the bot owns
        int digTier;         // pickaxe tier: 0 none, 1 wood, 2 stone, 3 iron, 4 diamond
        int maxNodes = 800;
        int radius = 48;
    }

    record Result(List<Step> path, boolean complete, int expanded) {}

    private static final class Node {
        int x, y, z; double g, f; Node parent; Act act; boolean closed;
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private BotNav() {}

    // ------------------------------------------------------------------ goals

    /** Stand within r blocks (horizontally) of a point and about the same height. */
    static Goal near(double tx, double ty, double tz, double r) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                double dx = x + 0.5 - tx, dz = z + 0.5 - tz;
                return Math.sqrt(dx * dx + dz * dz) <= r && Math.abs(y - ty) <= 2.0;
            }
            public double h(int x, int y, int z) {
                double dx = x + 0.5 - tx, dz = z + 0.5 - tz;
                return Math.max(0, Math.sqrt(dx * dx + dz * dz) - r) + Math.max(0, Math.abs(y - ty) - 1.5) * 1.5;
            }
        };
    }

    /** Stand where the eye is within reach of a block centre. */
    static Goal reach(int bx, int by, int bz, double reach) {
        return new Goal() {
            public boolean reached(int x, int y, int z) {
                if (x == bx && z == bz && (y == by || y + 1 == by)) return false;   // not inside the block
                double dx = x + 0.5 - (bx + 0.5), dy = y + 1.6 - (by + 0.5), dz = z + 0.5 - (bz + 0.5);
                return dx * dx + dy * dy + dz * dz <= reach * reach;
            }
            public double h(int x, int y, int z) {
                double dx = x + 0.5 - (bx + 0.5), dy = y + 1.6 - (by + 0.5), dz = z + 0.5 - (bz + 0.5);
                return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach);
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 24)) & 0x1FFFFFFL) << 38 | ((long) (z + (1 << 24)) & 0x1FFFFFFL) << 13 | ((y + 4096) & 0x1FFF);
    }

    static boolean standable(BotWorld w, int x, int y, int z) {
        if (!w.pass(x, y, z) || !w.pass(x, y + 1, z)) return false;
        return w.solid(x, y - 1, z) || w.kind(x, y, z) == BotWorld.WATER;
    }

    /** Cells that must be empty before taking this step (the ones to dig when they are solid). */
    static List<int[]> cells(int ox, int oy, int oz, int tx, int ty, int tz, Act act) {
        List<int[]> c = new ArrayList<>(4);
        if (act == Act.PILLAR) { c.add(new int[] {ox, oy + 2, oz}); return c; }
        if (act == Act.BRIDGE) { c.add(new int[] {tx, ty, tz}); c.add(new int[] {tx, ty + 1, tz}); return c; }
        c.add(new int[] {tx, ty, tz});
        c.add(new int[] {tx, ty + 1, tz});
        if (ty > oy) c.add(new int[] {ox, oy + 2, oz});
        if (ty < oy) { c.add(new int[] {tx, oy, tz}); c.add(new int[] {tx, oy + 1, tz}); }
        return c;
    }

    /** Blocks the bot is willing to dig (natural terrain only, so it never wrecks builds or chests). */
    static boolean natural(String id) {
        if (id == null) return false;
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        if (n.endsWith("_ore")) return true;
        if (n.contains("leaves")) return true;
        for (String s : NATURAL) if (n.equals(s) || (n.endsWith("_" + s)) ) return true;
        return false;
    }

    private static final String[] NATURAL = {"stone", "dirt", "grass_block", "sand", "gravel", "deepslate", "andesite", "diorite", "granite",
            "tuff", "netherrack", "sandstone", "clay", "snow", "podzol", "mycelium", "mud", "terracotta", "calcite", "basalt", "blackstone",
            "moss_block", "snow_block", "ice", "packed_ice", "dripstone_block", "cobblestone", "coarse_dirt", "rooted_dirt", "log", "obsidian"};

    private static boolean diggable(BotWorld w, int x, int y, int z, Opts o) {
        if (!o.dig || !w.solid(x, y, z)) return false;
        String id = w.id(x, y, z);
        if (!natural(id)) return false;
        if (BotGear.tierNeeded(id) > o.digTier) return false;
        // never open a wall next to fluid: it would flood the tunnel (and the player's cave)
        for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            int k = w.kind(x + d[0], y + d[1], z + d[2]);
            if (k == BotWorld.LAVA || k == BotWorld.WATER) return false;
        }
        return true;
    }

    /** @return number of cells to dig, or -1 when some cell is blocked for good. */
    private static int digCount(BotWorld w, List<int[]> cells, Opts o) {
        int n = 0;
        for (int[] c : cells) {
            if (w.pass(c[0], c[1], c[2])) continue;
            if (!diggable(w, c[0], c[1], c[2], o)) return -1;
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ search

    static Result search(BotWorld w, int sx, int sy, int sz, Goal goal, Opts o) {
        HashMap<Long, Node> all = new HashMap<>(256);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Node start = new Node(); start.x = sx; start.y = sy; start.z = sz; start.f = goal.h(sx, sy, sz) * 1.2;
        all.put(key(sx, sy, sz), start); open.add(start);
        Node best = start; double bestH = goal.h(sx, sy, sz);
        int expanded = 0;
        while (!open.isEmpty() && expanded < o.maxNodes) {
            Node cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true; expanded++;
            if (goal.reached(cur.x, cur.y, cur.z)) return new Result(build(cur, o), true, expanded);
            double hh = goal.h(cur.x, cur.y, cur.z);
            if (hh < bestH) { bestH = hh; best = cur; }
            expand(w, cur, goal, o, all, open, sx, sz);
        }
        return new Result(build(best, o), false, expanded);
    }

    private static void relax(Node from, int x, int y, int z, Act act, double cost, Goal goal, HashMap<Long, Node> all, PriorityQueue<Node> open) {
        long k = key(x, y, z);
        Node n = all.get(k);
        double g = from.g + cost;
        if (n == null) { n = new Node(); n.x = x; n.y = y; n.z = z; n.g = Double.MAX_VALUE; all.put(k, n); }
        if (n.closed || g >= n.g) return;
        n.g = g; n.parent = from; n.act = act; n.f = g + goal.h(x, y, z) * 1.2;
        open.add(n);
    }

    private static void expand(BotWorld w, Node c, Goal goal, Opts o, HashMap<Long, Node> all, PriorityQueue<Node> open, int sx, int sz) {
        int x = c.x, y = c.y, z = c.z;
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            if (Math.abs(nx - sx) > o.radius || Math.abs(nz - sz) > o.radius) continue;
            boolean freeBody = w.pass(nx, y, nz) && w.pass(nx, y + 1, nz);
            if (freeBody) {
                if (w.solid(nx, y - 1, nz) || w.kind(nx, y, nz) == BotWorld.WATER) {
                    relax(c, nx, y, nz, Act.WALK, w.kind(nx, y, nz) == BotWorld.WATER ? 3.0 : 1.0, goal, all, open);
                } else {
                    boolean landed = false;
                    for (int dd = 1; dd <= 3; dd++) {
                        int yy = y - dd;
                        if (!w.pass(nx, yy, nz)) break;
                        if (w.solid(nx, yy - 1, nz) || w.kind(nx, yy, nz) == BotWorld.WATER) {
                            relax(c, nx, yy, nz, Act.DROP, 1.0 + 0.7 * dd, goal, all, open);
                            landed = true;
                            break;
                        }
                    }
                    if (!landed && o.place && o.blocks > 0 && w.kind(nx, y - 1, nz) != BotWorld.LAVA) {
                        relax(c, nx, y, nz, Act.BRIDGE, 5.5, goal, all, open);
                    }
                }
            } else if (w.solid(nx, y, nz) && w.pass(nx, y + 1, nz) && w.pass(nx, y + 2, nz) && w.pass(x, y + 2, z)) {
                relax(c, nx, y + 1, nz, Act.JUMP, 2.0, goal, all, open);
            }
            if (o.dig) {
                // tunnel straight ahead
                if (w.solid(nx, y - 1, nz)) {
                    int n = digCount(w, cells(x, y, z, nx, y, nz, Act.WALK), o);
                    if (n > 0) relax(c, nx, y, nz, Act.DIG, 1.0 + 3.0 * n, goal, all, open);
                }
                // stairs up
                if (w.solid(nx, y, nz)) {
                    int n = digCount(w, cells(x, y, z, nx, y + 1, nz, Act.DIG), o);
                    if (n > 0) relax(c, nx, y + 1, nz, Act.DIG, 2.0 + 3.0 * n, goal, all, open);
                }
                // stairs down
                if (w.solid(nx, y - 2, nz)) {
                    int n = digCount(w, cells(x, y, z, nx, y - 1, nz, Act.DIG), o);
                    if (n > 0) relax(c, nx, y - 1, nz, Act.DIG, 2.0 + 3.0 * n, goal, all, open);
                }
            }
        }
        // pillar straight up
        if (o.place && o.blocks > 0 && w.pass(x, y + 2, z)) relax(c, x, y + 1, z, Act.PILLAR, 6.0, goal, all, open);
        else if (o.place && o.blocks > 0 && o.dig) {
            int n = digCount(w, cells(x, y, z, x, y + 1, z, Act.PILLAR), o);
            if (n > 0) relax(c, x, y + 1, z, Act.PILLAR, 6.0 + 3.0 * n, goal, all, open);
        }
    }

    private static List<Step> build(Node end, Opts o) {
        ArrayList<Step> out = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) out.add(new Step(n.x, n.y, n.z, n.act));
        Collections.reverse(out);
        // never promise more placed blocks than the bot has
        int left = o.blocks;
        for (int i = 0; i < out.size(); i++) {
            Act a = out.get(i).act();
            if (a == Act.PILLAR || a == Act.BRIDGE) {
                if (left <= 0) { return new ArrayList<>(out.subList(0, i)); }
                left--;
            }
        }
        return out;
    }
}
