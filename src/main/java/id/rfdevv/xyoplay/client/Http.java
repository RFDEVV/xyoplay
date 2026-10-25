package id.rfdevv.xyoplay.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Tiny blocking HTTP helper used by the TikTok / Spotify / iTunes integrations. */
final class Http {
    private Http() {}

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    static final class StatusException extends IOException {
        final int status;
        StatusException(int status, String body) { super("HTTP " + status + (body == null || body.isBlank() ? "" : ": " + shorten(body))); this.status = status; }
    }

    static String get(String url, Map<String, String> headers) throws IOException {
        return send(builder(url, headers).GET().build());
    }

    static String postForm(String url, Map<String, String> headers, String body) throws IOException {
        HttpRequest.Builder b = builder(url, headers).header("Content-Type", "application/x-www-form-urlencoded");
        return send(b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    /** Streams a file to {@code target} (via a .part file). Returns the number of bytes written. */
    static long download(String url, Path target, Map<String, String> headers, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        HttpRequest request = builder(url, headers).timeout(Duration.ofMinutes(5)).GET().build();
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream in = response.body()) { throw new StatusException(response.statusCode(), new String(in.readNBytes(300), StandardCharsets.UTF_8)); }
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1);
            long written = 0;
            Files.createDirectories(target.getParent());
            try (InputStream in = response.body(); var out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (cancelled != null && cancelled.getAsBoolean()) throw new IOException("Dibatalkan");
                    out.write(buffer, 0, n);
                    written += n;
                    if (progress != null && total > 0) progress.accept(Math.min(1.0, (double) written / total));
                }
            }
            if (written < 2048) throw new IOException("File audio terlalu kecil (" + written + " byte); tautan mungkin kedaluwarsa");
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            return written;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Dibatalkan");
        } finally {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
        }
    }

    private static HttpRequest.Builder builder(String url, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", UA);
        if (headers != null) headers.forEach(b::header);
        return b;
    }

    private static String send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new StatusException(response.statusCode(), response.body());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Dibatalkan");
        }
    }

    static String shorten(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 119) + "…";
    }
}
