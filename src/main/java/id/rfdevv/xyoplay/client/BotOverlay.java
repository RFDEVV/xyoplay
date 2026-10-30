package id.rfdevv.xyoplay.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Remembers the bots' own block changes for a few seconds, until the game world has caught up. */
final class BotOverlay implements BotWorld {
    private record Cell(int kind, String id, long until) {}

    private final BotWorld base;
    private final Map<Long, Cell> cells = new HashMap<>();
    long now;

    BotOverlay(BotWorld base) { this.base = base; }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((y + 2048) & 0xFFF); }

    void set(int x, int y, int z, int kind, String id) { cells.put(key(x, y, z), new Cell(kind, id, now + 200)); }

    void tick(long t) {
        now = t;
        if (t % 100 == 0 && !cells.isEmpty()) cells.values().removeIf(c -> c.until() < now);
    }

    private Cell cell(int x, int y, int z) {
        if (cells.isEmpty()) return null;
        Cell c = cells.get(key(x, y, z));
        return c != null && c.until() >= now ? c : null;
    }

    @Override public int kind(int x, int y, int z) { Cell c = cell(x, y, z); return c != null ? c.kind() : base.kind(x, y, z); }
    @Override public String id(int x, int y, int z) { Cell c = cell(x, y, z); return c != null ? (c.id() == null ? "minecraft:air" : c.id()) : base.id(x, y, z); }
    @Override public List<Mob> hostiles(double x, double y, double z, double r) { return base.hostiles(x, y, z, r); }
}
