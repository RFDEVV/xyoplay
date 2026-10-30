package id.rfdevv.xyoplay.client;

import java.util.List;

/** What the bot needs to know about the world. The game adapter is {@link McWorld}; tests use a synthetic grid. */
interface BotWorld {
    int AIR = 0, SOLID = 1, WATER = 2, LAVA = 3, UNLOADED = 4;

    record Mob(String uuid, String type, double x, double y, double z) {}

    /** One of AIR / SOLID / WATER / LAVA / UNLOADED. Plants and other non-colliding blocks count as AIR. */
    int kind(int x, int y, int z);

    /** Registry id such as "minecraft:oak_log" (only called when the bot looks for something specific). */
    String id(int x, int y, int z);

    /** Hostile mobs near a point (never includes the bots themselves). */
    List<Mob> hostiles(double x, double y, double z, double radius);

    default boolean pass(int x, int y, int z) { int k = kind(x, y, z); return k == AIR || k == WATER; }
    default boolean solid(int x, int y, int z) { return kind(x, y, z) == SOLID; }
}
