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

    /** Loads the template. Callable at any point — the JVM shutdown hook
     *  path is the fragile one, because by then Surefire's isolating
     *  classloader may already be closed, so we call this once from
     *  {@link ShutdownWriter#install()} to warm the cache while the
     *  framework binding is still alive. */
    public static String template() {
        String c = cached;
        if (c != null) return c;
        synchronized (TemplateRenderer.class) {
            if (cached != null) return cached;
            cached = loadTemplateFromClasspath();
            return cached;
        }
    }

    /** Force-load and cache the template. Called eagerly by the framework
     *  bindings so the shutdown hook never has to hit the classloader. */
    public static void warmCache() { template(); }

    private static String loadTemplateFromClasspath() {
        // Try three lookup strategies. Surefire, isolated ClassLoaders in
        // IDE runners and some Gradle modes each fail in slightly different
        // ways, so we don't rely on a single one.
        InputStream in = tryOpen();
        if (in == null) {
            throw new IllegalStateException(
                "reporting-labs: bundled resource " + RESOURCE + " not found on the classpath. " +
                "This should be inside dev.reportinglabs:reporting-labs-core — verify the JAR " +
                "contains reporting-labs/template.html and that no shading/repackaging step stripped it.");
        }
        try (InputStream stream = in;
             ByteArrayOutputStream bos = new ByteArrayOutputStream(300_000)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = stream.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("reporting-labs: failed to load template.html from JAR", e);
        }
    }

    private static InputStream tryOpen() {
        // 1. Class-relative lookup — uses the classloader that defined
        //    TemplateRenderer (i.e. the classloader that owns this JAR).
        InputStream in = TemplateRenderer.class.getResourceAsStream("/" + RESOURCE);
        if (in != null) return in;

        // 2. Same class, no leading slash — some containers behave
        //    differently.
        in = TemplateRenderer.class.getResourceAsStream(RESOURCE);
        if (in != null) return in;

        // 3. Classloader of the defining class — belt-and-braces.
        ClassLoader cl = TemplateRenderer.class.getClassLoader();
        if (cl != null) {
            in = cl.getResourceAsStream(RESOURCE);
            if (in != null) return in;
        }

        // 4. Thread context classloader — for framework runners that set
        //    a specific one before invoking user code.
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null && tccl != cl) {
            in = tccl.getResourceAsStream(RESOURCE);
            if (in != null) return in;
        }
        return null;
    }
}
