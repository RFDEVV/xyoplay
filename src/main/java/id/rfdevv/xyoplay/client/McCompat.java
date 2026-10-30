package id.rfdevv.xyoplay.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

/**
 * Small wrappers around Minecraft calls whose signatures differ between versions. Everything here is resolved by
 * name at run time and fails soft (returns false / does nothing) instead of crashing the game.
 */
final class McCompat {
    private McCompat() {}

    private static boolean multiplayerHintShown;

    /** Adds a line to the local chat (only the player sees it). */
    static void localMessage(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            Method m = mc.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class);
            m.invoke(mc.player, Component.literal(text), false);
        } catch (Throwable ignored) { }
    }

    /** Tests replace the real game with this hook: it receives every command and answers through the callback. */
    static volatile java.util.function.BiConsumer<String, java.util.function.Consumer<String>> testSink;

    static boolean inWorld() {
        if (testSink != null) return true;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null;
    }

    /** Fire-and-forget command (no result needed). */
    static boolean runCommand(String command) {
        runCommandAsync(command, false, err -> { });
        return inWorld();
    }

    /**
     * Runs a command as the player and reports what really happened.
     * Singleplayer: runs on the integrated server as an elevated source, so no cheats are needed, and the callback gets
     * null on success or the game's own error text (also when the command ran but did nothing and strict is true).
     * Multiplayer: the command is sent like a typed one (needs operator rights); the outcome cannot be seen, so the
     * callback is told "success" as soon as it was sent.
     * The callback may run on the server thread.
     *
     * @param command without the leading slash
     */
    static void runCommandAsync(String command, boolean strict, java.util.function.Consumer<String> done) {
        java.util.function.BiConsumer<String, java.util.function.Consumer<String>> sink = testSink;
        if (sink != null) { sink.accept(command, done); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || command == null || command.isBlank()) { done.accept("tidak sedang di dalam dunia"); return; }
        String line = command.startsWith("/") ? command.substring(1) : command;
        if (runOnIntegratedServer(mc, line, strict, done)) return;
        enqueueRemote(line, done);
    }

    // ------------------------------------------------------------ servers: safe command sending

    /** A command waiting to be typed on a server. Same-target teleports replace each other so the queue never floods. */
    private record Queued(String line, String key, java.util.function.Consumer<String> done) {}

    private static final java.util.ArrayList<Queued> REMOTE = new java.util.ArrayList<>();
    private static double remoteTokens = 6;

    /** The server's packet decoder rejects commands over 256 characters (and disconnects you). */
    static final int MAX_COMMAND = 250;

    private static boolean legal(String line) {
        for (int i = 0; i < line.length(); i++) { char c = line.charAt(i); if (c < 32 || c == 127 || c == '\u00a7') return false; }
        return true;
    }

    static void enqueueRemote(String line, java.util.function.Consumer<String> done) {
        if (line.length() > MAX_COMMAND) { done.accept("command terlalu panjang untuk server (" + line.length() + " > " + MAX_COMMAND + "), tidak dikirim"); return; }
        if (!legal(line)) { done.accept("command berisi karakter yang ditolak server, tidak dikirim"); return; }
        String key = null;
        if (line.startsWith("tp ")) { int sp = line.indexOf(' ', 3); key = sp < 0 ? line : line.substring(0, sp); }
        synchronized (REMOTE) {
            if (key != null) { final String k = key; REMOTE.removeIf(q -> k.equals(q.key())); }
            REMOTE.add(new Queued(line, key, done));
            while (REMOTE.size() > 80) REMOTE.remove(0).done().accept("antrean command server penuh");
        }
    }

    /** Call every client tick: sends queued commands within the server's spam limit (kicks happen above ~1 command per second). */
    static void tickRemote() {
        synchronized (REMOTE) {
            double rate = Math.max(1, Math.min(10, ClientSettings.mpCmdRate));
            remoteTokens = Math.min(8, remoteTokens + rate / 20.0);
            while (remoteTokens >= 1 && !REMOTE.isEmpty()) {
                Queued q = REMOTE.remove(0);
                remoteTokens -= 1;
                sendNow(q.line(), q.done());
            }
        }
    }

    static int remotePending() { synchronized (REMOTE) { return REMOTE.size(); } }

    static volatile java.util.function.Consumer<String> remoteSink;   // tests

    private static void sendNow(String line, java.util.function.Consumer<String> done) {
        if (remoteSink != null) { remoteSink.accept(line); done.accept(null); return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { done.accept("tidak sedang di dalam dunia"); return; }
        try {
            Object connection = mc.player.getClass().getField("connection").get(mc.player);
            if (connection == null) { done.accept("tidak ada koneksi ke server"); return; }
            for (String name : new String[] {"sendUnsignedCommand", "sendCommand"}) {
                try {
                    Method m = connection.getClass().getMethod(name, String.class);
                    m.invoke(connection, line);
                    if (!multiplayerHintShown) {
                        multiplayerHintShown = true;
                        localMessage("§e[XYOPlay]§7 Di server, aksi butuh izin OP dan dikirim pelan-pelan (batas anti-spam server). Pakai dunia sendiri untuk kecepatan penuh.");
                    }
                    done.accept(null);
                    return;
                } catch (NoSuchMethodException ignored) { }
            }
            done.accept("versi game ini tidak punya cara mengirim command");
        } catch (Throwable t) {
            done.accept("gagal mengirim command: " + t.getClass().getSimpleName());
        }
    }

    private static boolean runOnIntegratedServer(Minecraft mc, String line, boolean strict, java.util.function.Consumer<String> done) {
        try {
            Object server = mc.getClass().getMethod("getSingleplayerServer").invoke(mc);
            if (server == null) return false;
            String uuid = mc.player.getUUID().toString();
            String full = "execute as " + uuid + " at " + uuid + " run " + line;
            Object source = server.getClass().getMethod("createCommandSourceStack").invoke(server);
            try { source = source.getClass().getMethod("withSuppressedOutput").invoke(source); } catch (Throwable ignored) { }   // no chat spam from our own commands
            Object commands = server.getClass().getMethod("getCommands").invoke(server);
            Object dispatcher = null; Method exec = null;
            try {
                dispatcher = commands.getClass().getMethod("getDispatcher").invoke(commands);
                exec = dispatcher.getClass().getMethod("execute", String.class, Object.class);
            } catch (Throwable ignored) { dispatcher = null; }
            final Object src = source, disp = dispatcher;
            if (disp == null) {
                // this game version hides the dispatcher: fall back to the plain runner (success cannot be verified)
                Method perform = null;
                for (Method m : commands.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("performPrefixedCommand") && p.length == 2 && p[1] == String.class) { perform = m; break; }
                }
                if (perform == null) return false;
                final Method run = perform; final Object cmds = commands;
                server.getClass().getMethod("execute", Runnable.class).invoke(server, (Runnable) () -> {
                    try { run.invoke(cmds, src, full); done.accept(null); } catch (Throwable t) { done.accept("command gagal: " + t.getClass().getSimpleName()); }
                });
                return true;
            }
            final Method execFinal = exec;
            Runnable task = () -> {
                try {
                    Object result = execFinal.invoke(disp, full, src);
                    int r = result instanceof Number n ? n.intValue() : 1;
                    done.accept(strict && r <= 0 ? "command tidak menghasilkan apa pun (dunia/aturan menolak)" : null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    String msg = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
                    done.accept(msg.length() > 120 ? msg.substring(0, 120) : msg);
                } catch (Throwable t) {
                    done.accept("error internal: " + t.getClass().getSimpleName());
                }
            };
            server.getClass().getMethod("execute", Runnable.class).invoke(server, task);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // --------------------------------------------------------------- player info

    static double[] position() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()};
    }

    static float yaw() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0f : mc.player.getYRot();
    }

    static float pitch() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0f : mc.player.getXRot();
    }

    /** Vertical field of view in degrees (the game option; 70 when it cannot be read). */
    static double fov() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Object opt = mc.options.getClass().getMethod("fov").invoke(mc.options);
            Object v = opt.getClass().getMethod("get").invoke(opt);
            if (v instanceof Number n) return Math.max(30, Math.min(120, n.doubleValue()));
        } catch (Throwable ignored) { }
        return 70;
    }

    static boolean playerDead() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.isDeadOrDying();
    }

    /** "minecraft:overworld", "minecraft:the_nether" ... */
    static String dimension() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return "";
            String s = String.valueOf(mc.level.dimension());
            int slash = s.lastIndexOf('/');
            int end = s.lastIndexOf(']');
            if (slash >= 0 && end > slash) return s.substring(slash + 1, end).trim();
            return s;
        } catch (Throwable t) { return ""; }
    }

    static String compass(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return names[Math.round(y / 45f) % 8];
    }
}
