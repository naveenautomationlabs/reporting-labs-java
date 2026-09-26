package dev.reportinglabs.core.internal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads the shared HTML shell (bundled at src/main/resources/reporting-labs/template.html
 * — an exact snapshot of dist/template.html from the Node.js reporter, so the
 * report layout, CSS and JS stay identical across ports) and produces the
 * final index.html by replacing the __RL_DATA__ placeholder with the report's
 * JSON payload.
 *
 * The template contains one placeholder — the JSON script tag. Everything else
 * (CSS, JS, fonts, palettes) is baked in at snapshot time.
 */
public final class TemplateRenderer {

    private static final String RESOURCE = "reporting-labs/template.html";
    private static final String PLACEHOLDER = "__RL_DATA__";
    private static volatile String cached;

    private TemplateRenderer() {}

    /** Renders the report HTML for the given data map. */
    public static String render(Map<String, Object> data) {
        String tpl = template();
        String json = Json.write(data);
        // Guard against a </script sequence inside JSON string values ending the
        // rl-data script tag early. Same defence as the JS reporter.
        json = json.replace("</script", "<\\/script");
        int idx = tpl.indexOf(PLACEHOLDER);
        if (idx < 0) {
            throw new IllegalStateException(
                "reporting-labs: template.html is missing the __RL_DATA__ placeholder. " +
                "Was the resource swapped out or corrupted?");
        }
        StringBuilder sb = new StringBuilder(tpl.length() + json.length());
        sb.append(tpl, 0, idx);
        sb.append(json);
        sb.append(tpl, idx + PLACEHOLDER.length(), tpl.length());
        return sb.toString();
    }

    static String template() {
        String c = cached;
        if (c != null) return c;
        synchronized (TemplateRenderer.class) {
            if (cached != null) return cached;
            try (InputStream in = TemplateRenderer.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new FileNotFoundException(
                        "reporting-labs: bundled resource " + RESOURCE + " not found. " +
                        "The core JAR should ship this file — check your build.");
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream(300_000);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                cached = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                return cached;
            } catch (IOException e) {
                throw new IllegalStateException("reporting-labs: failed to load template.html from JAR", e);
            }
        }
    }
}
