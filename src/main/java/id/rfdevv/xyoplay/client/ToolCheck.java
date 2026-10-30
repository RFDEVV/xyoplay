package id.rfdevv.xyoplay.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs `--version` on the external tools and Python packages so the player can see what is missing. */
final class ToolCheck {
    record Line(boolean ok, String text) {}

    private static volatile List<Line> lines = List.of();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ToolCheck() {}

    static List<Line> lines() { return lines; }
    static boolean running() { return RUNNING.get(); }

    static void run() {
        if (!RUNNING.compareAndSet(false, true)) return;
        lines = List.of(new Line(true, "Memeriksa alat…"));
        Thread worker = new Thread(() -> {
            try {
                List<Line> result = new ArrayList<>();
                result.add(check("ffmpeg", List.of(LocalAudioPlayer.ffmpegExe()), "-version"));
                result.add(check("ffprobe", List.of(LocalAudioPlayer.ffprobeExe()), "-version"));
                result.add(check("yt-dlp", List.of(YoutubeService.ytDlpExe()), "--version"));
                String python = TikTokLiveService.findPython();
                result.add(python == null
                        ? new Line(false, "python 3: tidak ditemukan (pasang dari python.org, centang Add to PATH)")
                        : check("python", List.of(python), "--version"));
                String probe = "import importlib.metadata as m,sys\n"
                        + "for n in ('TikTokLive','edge-tts','pyttsx3'):\n"
                        + "    try: print(n+' '+m.version(n))\n"
                        + "    except Exception: print(n+' MISSING')\n";
                List<String> out = python == null ? List.of() : output(python, "-c", probe);
                for (String name : new String[] {"TikTokLive", "edge-tts", "pyttsx3"}) {
                    String found = null;
                    for (String l : out) if (l.startsWith(name + " ")) found = l.substring(name.length() + 1).trim();
                    if (found != null && !found.equals("MISSING")) result.add(new Line(true, name + ": " + found));
                    else result.add(new Line(false, name + ": belum terpasang" + (name.equals("pyttsx3") ? " (opsional)" : " (klik Instal paket di tab LIVE)")));
                }
                lines = List.copyOf(result);
            } finally { RUNNING.set(false); }
        }, "XYOPlay-ToolCheck");
        worker.setDaemon(true); worker.start();
    }

    private static List<String> output(String exe, String... args) {
        List<String> result = new ArrayList<>();
        Process p = null;
        try {
            List<String> cmd = new ArrayList<>(); cmd.add(exe); cmd.addAll(List.of(args));
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) result.add(line.trim());
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        } finally { if (p != null && p.isAlive()) p.destroyForcibly(); }
        return result;
    }

    private static Line check(String name, List<String> candidates, String arg) {
        for (String exe : candidates) {
            Process p = null;
            try {
                p = new ProcessBuilder(exe, arg).redirectErrorStream(true).start();
                String first;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) { first = r.readLine(); }
                if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); continue; }
                if (p.exitValue() == 0) return new Line(true, name + ": " + (first == null ? "OK" : first.trim()));
            } catch (Exception ignored) {
            } finally { if (p != null && p.isAlive()) p.destroyForcibly(); }
        }
        return new Line(false, name + ": tidak ditemukan (atur path di config/xyoplay.properties)");
    }
}
