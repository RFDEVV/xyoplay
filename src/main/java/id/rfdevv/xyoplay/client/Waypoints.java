package id.rfdevv.xyoplay.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Saved coordinates (home, village, portal…) plus an automatic "Mati" marker where the player last died. */
final class Waypoints {
    private Waypoints() {}

    record Point(String name, int x, int y, int z, String dimension, boolean death) {}

    private static final List<Point> POINTS = new CopyOnWriteArrayList<>();
    private static boolean loaded;
    private static boolean wasDead;
    private static double[] lastAlivePos;
    private static String lastAliveDim = "";
    private static volatile Point tracked;

    static synchronized void ensureLoaded() { if (!loaded) load(); }
    static List<Point> points() { ensureLoaded(); return List.copyOf(POINTS); }
    static Point tracked() { return tracked; }
    static void track(Point p) { tracked = p; }

    /** Adds the current position under the given name. Returns null when not in a world. */
    static Point addHere(String rawName) {
        ensureLoaded();
        double[] pos = McCompat.position();
        if (pos == null) return null;
        String name = rawName == null ? "" : rawName.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (name.isEmpty()) name = "Titik " + (POINTS.size() + 1);
        if (name.length() > 24) name = name.substring(0, 24);
        Point p = new Point(name, (int) Math.floor(pos[0]), (int) Math.floor(pos[1]), (int) Math.floor(pos[2]), McCompat.dimension(), false);
        POINTS.removeIf(o -> o.name().equalsIgnoreCase(p.name()));
        POINTS.add(0, p);
        while (POINTS.size() > 100) POINTS.remove(POINTS.size() - 1);
        save();
        return p;
    }

    static void remove(Point p) {
        ensureLoaded();
        POINTS.remove(p);
        if (tracked != null && tracked.equals(p)) tracked = null;
        save();
    }

    /** Distance in blocks, or -1 when the point is in another dimension / not in a world. */
    static int distance(Point p) {
        double[] pos = McCompat.position();
        if (pos == null) return -1;
        String dim = McCompat.dimension();
        if (!p.dimension().isEmpty() && !dim.isEmpty() && !p.dimension().equals(dim)) return -1;
        double dx = p.x() + 0.5 - pos[0], dy = p.y() - pos[1], dz = p.z() + 0.5 - pos[2];
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /** Compass direction from the player to the point, relative to where the player is looking: "depan", "kiri"… */
    static String direction(Point p) {
        double[] pos = McCompat.position();
        if (pos == null) return "";
        double dx = p.x() + 0.5 - pos[0], dz = p.z() + 0.5 - pos[2];
        if (Math.abs(dx) < 1 && Math.abs(dz) < 1) return "di sini";
        // Minecraft yaw 0 = +Z (south). Bearing of the target in the same convention:
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - McCompat.yaw()) % 360 + 540) % 360 - 180;   // -180..180, 0 = straight ahead
        if (Math.abs(rel) <= 22.5) return "↑ depan";
        if (Math.abs(rel) >= 157.5) return "↓ belakang";
        if (rel > 0) return rel < 67.5 ? "↗ kanan depan" : rel < 112.5 ? "→ kanan" : "↘ kanan belakang";
        return rel > -67.5 ? "↖ kiri depan" : rel > -112.5 ? "← kiri" : "↙ kiri belakang";
    }

    /** Angle to the point relative to where the player looks, in radians (0 = straight ahead, clockwise positive). */
    static double relativeAngle(Point p) {
        double[] pos = McCompat.position();
        if (pos == null) return 0;
        double dx = p.x() + 0.5 - pos[0], dz = p.z() + 0.5 - pos[2];
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - McCompat.yaw()) % 360 + 540) % 360 - 180;   // positive = to the right of where we look (same convention as direction())
        return Math.toRadians(rel);
    }

    static String coords(Point p) { return p.x() + " " + p.y() + " " + p.z(); }

    static String dimensionLabel(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            case "" -> "";
            default -> dim.startsWith("minecraft:") ? dim.substring(10) : dim;
        };
    }

    // ------------------------------------------------------------------ per tick

    /** Remembers where the player is alive and, the moment the player dies, stores a death marker there. */
    static void tick() {
        if (!McCompat.inWorld()) { wasDead = false; return; }
        boolean dead = McCompat.playerDead();
        if (!dead) {
            lastAlivePos = McCompat.position();
            lastAliveDim = McCompat.dimension();
        } else if (!wasDead && ClientSettings.deathMarker && lastAlivePos != null) {
            ensureLoaded();
            Point p = new Point("Mati", (int) Math.floor(lastAlivePos[0]), (int) Math.floor(lastAlivePos[1]), (int) Math.floor(lastAlivePos[2]), lastAliveDim, true);
            POINTS.removeIf(Point::death);
            POINTS.add(0, p);
            save();
            tracked = p;
            McCompat.localMessage("§c[XYOPlay]§f Kamu mati di §e" + coords(p) + "§f. Titik \"Mati\" tersimpan (buka menu > Titik).");
        }
        wasDead = dead;
        if (!dead) checkArrival();
    }

    /** Within this many blocks of the tracked point counts as "arrived". */
    static final int ARRIVE_BLOCKS = 5;

    private static void checkArrival() {
        Point t = tracked;
        if (t == null || !ClientSettings.markerClearOnArrive) return;
        int d = distance(t);
        if (d < 0 || d > ARRIVE_BLOCKS) return;
        tracked = null;
        if (t.death()) {
            POINTS.remove(t);
            save();
            McCompat.localMessage("§a[XYOPlay]§f Kamu sampai di lokasi matimu. Penanda dihapus.");
        } else {
            McCompat.localMessage("§a[XYOPlay]§f Sampai di \"" + t.name() + "\". Penanda disembunyikan.");
        }
    }

    // ------------------------------------------------------------------ info overlay

    /** One-line text for the info HUD, or null. */
    static String infoLine() {
        double[] pos = McCompat.position();
        if (pos == null) return null;
        String line = "XYZ " + (int) Math.floor(pos[0]) + " " + (int) Math.floor(pos[1]) + " " + (int) Math.floor(pos[2]) + "  " + McCompat.compass(McCompat.yaw());
        Point t = tracked;
        if (t != null) {
            int d = distance(t);
            if (d >= 0) line += "  |  " + t.name() + " " + d + "m " + direction(t);
        }
        return line;
    }

    // ------------------------------------------------------------------ persistence

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("xyoplay").resolve("waypoints.json");
    }

    static synchronized void load() {
        loaded = true;
        POINTS.clear();
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("points")) return;
            for (JsonElement el : root.getAsJsonArray("points")) {
                JsonObject o = el.getAsJsonObject();
                POINTS.add(new Point(o.get("name").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                        o.has("dim") ? o.get("dim").getAsString() : "", o.has("death") && o.get("death").getAsBoolean()));
            }
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal membaca waypoints.json: " + e.getMessage());
        }
    }

    static synchronized void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            JsonArray arr = new JsonArray();
            for (Point p : POINTS) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.name()); o.addProperty("x", p.x()); o.addProperty("y", p.y()); o.addProperty("z", p.z());
                o.addProperty("dim", p.dimension()); o.addProperty("death", p.death());
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("points", arr);
            Files.writeString(f, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[XYOPlay] Gagal menyimpan waypoints.json: " + e.getMessage());
        }
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
