package id.rfdevv.xyoplay.client;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * Moves entities (and the player) directly on the integrated server instead of typing a /tp command ten times a
 * second. That avoids chat spam and command-name clashes with other mods. Only available in your own world; on a
 * server the bots fall back to commands. Everything is reflection and fails soft.
 */
final class ServerBridge {
    static volatile String last = "";
    static volatile boolean broken;

    private ServerBridge() {}

    private static Object server() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.getClass().getMethod("getSingleplayerServer").invoke(mc);
        } catch (Throwable t) { return null; }
    }

    static boolean available() { return !broken && server() != null; }

    static Method find(Class<?> c, String name, Class<?>... params) {
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != params.length) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < p.length; i++) if (p[i] != params[i]) { ok = false; break; }
            if (ok) return m;
        }
        return null;
    }

    /** Schedules the move on the server thread. @return false when this cannot be done (use the command instead). */
    static boolean move(UUID id, double x, double y, double z, float yaw, float pitch) {
        if (broken) return false;
        Object s = server();
        if (s == null) return false;
        try {
            Method exec = s.getClass().getMethod("execute", Runnable.class);
            exec.invoke(s, (Runnable) () -> moveNow(s, id, x, y, z, yaw, pitch));
            return true;
        } catch (Throwable t) {
            last = "server.execute gagal: " + t.getClass().getSimpleName();
            broken = true;
            return false;
        }
    }

    private static int fails;

    private static void moveNow(Object server, UUID id, double x, double y, double z, float yaw, float pitch) {
        try {
            Object entity = null;
            Iterable<?> levels = (Iterable<?>) server.getClass().getMethod("getAllLevels").invoke(server);
            for (Object lvl : levels) {
                Method get = find(lvl.getClass(), "getEntity", UUID.class);
                if (get == null) continue;
                Object e = get.invoke(lvl, id);
                if (e != null) { entity = e; break; }
            }
            if (entity == null) { last = "entitas belum ada di server"; return; }
            Class<?> c = entity.getClass();
            boolean done = false;
            // a real player: ask its connection to teleport (keeps the client in sync)
            try {
                Object conn = c.getField("connection").get(entity);
                if (conn != null) {
                    Method tp = find(conn.getClass(), "teleport", double.class, double.class, double.class, float.class, float.class);
                    if (tp != null) { tp.invoke(conn, x, y, z, yaw, pitch); done = true; }
                }
            } catch (NoSuchFieldException ignored) { }
            if (!done) {
                for (String name : new String[] {"snapTo", "moveTo"}) {
                    Method m = find(c, name, double.class, double.class, double.class, float.class, float.class);
                    if (m != null) { m.invoke(entity, x, y, z, yaw, pitch); done = true; break; }
                }
            }
            if (!done) {
                Method set = find(c, "setPos", double.class, double.class, double.class);
                if (set != null) {
                    set.invoke(entity, x, y, z);
                    Method ry = find(c, "setYRot", float.class); if (ry != null) ry.invoke(entity, yaw);
                    done = true;
                }
            }
            if (done) {
                Method head = find(c, "setYHeadRot", float.class); if (head != null) head.invoke(entity, yaw);
                Method body = find(c, "setYBodyRot", float.class); if (body != null) body.invoke(entity, yaw);
                fails = 0;
            } else if (++fails > 20) { broken = true; last = "tidak ada method teleport yang cocok"; }
        } catch (Throwable t) {
            last = "gerak langsung gagal: " + t.getClass().getSimpleName();
            if (++fails > 20) broken = true;
        }
    }
}
