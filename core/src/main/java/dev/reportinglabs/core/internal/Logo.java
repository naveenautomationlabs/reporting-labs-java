package dev.reportinglabs.core.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale;

/**
 * Resolves reporting-labs.logo the way the Node reporter does: an
 * https URL or a data URI passes through; a file (a path relative to the
 * working directory, or a resource on the test classpath such as
 * src/test/resources/logo.png) is embedded as a data URI so the report
 * stays a single self-contained file.
 */
final class Logo {

    private Logo() {}

    static String resolve(String logo) {
        if (logo == null) return null;
        String v = logo.trim();
        if (v.isEmpty()) return null;
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")) return v;
        String type = mimeOf(lower);
        if (type == null) {
            System.err.println("[reporting-labs] logo " + v + " is not a png, jpg, svg, gif, webp or ico file; ignored");
            return null;
        }
        byte[] bytes = read(v);
        if (bytes == null) {
            System.err.println("[reporting-labs] logo not found: " + v + " (looked in " + Paths.get("").toAbsolutePath() + " and on the test classpath)");
            return null;
        }
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String mimeOf(String lower) {
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return null;
    }

    /** The file as given (absolute, or relative to the working directory), else the classpath resource of that name. */
    private static byte[] read(String name) {
        try {
            Path p = Paths.get(name);
            if (Files.isRegularFile(p)) return Files.readAllBytes(p);
        } catch (Throwable ignore) { /* not a path: try the classpath */ }
        String res = name.startsWith("/") ? name.substring(1) : name;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = Logo.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(res)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignore) {}
        return null;
    }
}
