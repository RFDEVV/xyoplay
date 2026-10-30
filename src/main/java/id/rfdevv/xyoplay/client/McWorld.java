package id.rfdevv.xyoplay.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/**
 * Reads the loaded client world through reflection (so a renamed method disables the bots instead of breaking the
 * build). Reads are cached for one tick.
 */
final class McWorld implements BotWorld {
    private static final Pattern BLOCK_ID = Pattern.compile("Block\\{([a-z0-9_./:-]+)\\}");
    private static final String[] HOSTILE = {"zombie", "husk", "drowned", "skeleton", "stray", "creeper", "spider", "cave_spider", "witch", "slime",
            "phantom", "pillager", "vindicator", "blaze", "ghast", "wither_skeleton", "magma_cube", "silverfish", "hoglin", "zoglin", "ravager", "evoker", "vex", "guardian", "bogged", "breeze"};

    static volatile boolean healthy = true;
    static String problem = "";

    private final Map<Long, Integer> kinds = new HashMap<>();
    private final Map<Long, String> ids = new HashMap<>();
    private long cacheTick = -1;

    private Constructor<?> posCtor;
    private Method getState, isAir, blocksMotion, getFluid, fluidEmpty;
    private Method entities;

    McWorld() { init(); }

    private void init() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Class<?> pos = Class.forName("net.minecraft.core.BlockPos");
            posCtor = pos.getConstructor(int.class, int.class, int.class);
            Object level = mc.level;
            getState = find(level.getClass(), "getBlockState", 1);
            Object st = getState.invoke(level, posCtor.newInstance(0, 0, 0));
            isAir = find(st.getClass(), "isAir", 0);
            blocksMotion = find(st.getClass(), "blocksMotion", 0);
            getFluid = find(st.getClass(), "getFluidState", 0);
            Object fs = getFluid.invoke(st);
            fluidEmpty = find(fs.getClass(), "isEmpty", 0);
            try { entities = find(level.getClass(), "entitiesForRendering", 0); } catch (Throwable ignored) { entities = null; }
        } catch (Throwable t) {
            healthy = false;
            problem = "tidak bisa membaca dunia (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static Method find(Class<?> c, String name, int params) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) { m.setAccessible(true); return m; }
        }
        throw new NoSuchMethodException(name);
    }

    void newTick(long t) {
        if (t != cacheTick) { cacheTick = t; kinds.clear(); ids.clear(); }
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((y + 2048) & 0xFFF); }

    private Object state(int x, int y, int z) throws Exception {
        return getState.invoke(Minecraft.getInstance().level, posCtor.newInstance(x, y, z));
    }

    @Override public int kind(int x, int y, int z) {
        if (!healthy) return UNLOADED;
        long k = key(x, y, z);
        Integer c = kinds.get(k);
        if (c != null) return c;
        int r;
        try {
            Object st = state(x, y, z);
            if ((Boolean) isAir.invoke(st)) r = AIR;
            else if (!(Boolean) fluidEmpty.invoke(getFluid.invoke(st))) r = st.toString().contains("lava") ? LAVA : WATER;
            else r = (Boolean) blocksMotion.invoke(st) ? SOLID : AIR;
        } catch (Throwable t) {
            healthy = false; problem = "gagal membaca blok (" + t.getClass().getSimpleName() + ")"; r = UNLOADED;
        }
        if (kinds.size() > 60000) kinds.clear();
        kinds.put(k, r);
        return r;
    }

    @Override public String id(int x, int y, int z) {
        if (!healthy) return "minecraft:air";
        long k = key(x, y, z);
        String c = ids.get(k);
        if (c != null) return c;
        String r = "minecraft:air";
        try {
            Matcher m = BLOCK_ID.matcher(state(x, y, z).toString());
            if (m.find()) r = m.group(1);
        } catch (Throwable ignored) { }
        ids.put(k, r);
        return r;
    }

    @Override public List<Mob> hostiles(double x, double y, double z, double radius) {
        List<Mob> out = new ArrayList<>();
        if (!healthy || entities == null) return out;
        try {
            Iterable<?> all = (Iterable<?>) entities.invoke(Minecraft.getInstance().level);
            for (Object e : all) {
                Class<?> c = e.getClass();
                String type = String.valueOf(c.getMethod("getType").invoke(e));
                boolean hostile = false;
                for (String h : HOSTILE) if (type.endsWith("." + h)) { hostile = true; break; }
                if (!hostile) continue;
                double ex = ((Number) c.getMethod("getX").invoke(e)).doubleValue(), ey = ((Number) c.getMethod("getY").invoke(e)).doubleValue(), ez = ((Number) c.getMethod("getZ").invoke(e)).doubleValue();
                double dx = ex - x, dy = ey - y, dz = ez - z;
                if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                Object alive = c.getMethod("isAlive").invoke(e);
                if (alive instanceof Boolean b && !b) continue;
                Object name = c.getMethod("getCustomName").invoke(e);
                if (name != null && String.valueOf(name.getClass().getMethod("getString").invoke(name)).contains("XYO Bot")) continue;
                out.add(new Mob(String.valueOf(c.getMethod("getUUID").invoke(e)), type, ex, ey, ez));
            }
        } catch (Throwable t) {
            entities = null;   // stop trying; the bot simply will not fight
        }
        return out;
    }

    /** The bot entity with this number (found by its name tag), or null when it is not loaded. */
    Mob findBot(int id) {
        if (!healthy || entities == null) return null;
        try {
            Iterable<?> all = (Iterable<?>) entities.invoke(Minecraft.getInstance().level);
            for (Object e : all) {
                Class<?> c = e.getClass();
                Object name = c.getMethod("getCustomName").invoke(e);
                if (name == null) continue;
                String n = String.valueOf(name.getClass().getMethod("getString").invoke(name));
                if (!n.equals("XYO Bot " + id)) continue;
                return new Mob(String.valueOf(c.getMethod("getUUID").invoke(e)), "bot",
                        ((Number) c.getMethod("getX").invoke(e)).doubleValue(), ((Number) c.getMethod("getY").invoke(e)).doubleValue(), ((Number) c.getMethod("getZ").invoke(e)).doubleValue());
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------------------------ player & time

    /** Game time of day 0..23999, or -1 when unknown. */
    static long dayTime() {
        try {
            Object level = Minecraft.getInstance().level;
            Object t = level.getClass().getMethod("getDayTime").invoke(level);
            return ((Number) t).longValue() % 24000L;
        } catch (Throwable t) { return -1; }
    }

    static boolean isNight() { long t = dayTime(); return t >= 13000 && t <= 23000; }

    static boolean playerDead() { return McCompat.playerDead(); }

    /** Presses "respawn" for the player. */
    static boolean respawn() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return false;
            mc.player.getClass().getMethod("respawn").invoke(mc.player);
            return true;
        } catch (Throwable t) { return false; }
    }

    static float health() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return ((Number) mc.player.getClass().getMethod("getHealth").invoke(mc.player)).floatValue();
        } catch (Throwable t) { return 20f; }
    }
}
