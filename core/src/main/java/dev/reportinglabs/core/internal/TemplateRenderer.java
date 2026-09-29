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
    private static final String P_DATA        = "__RL_DATA__";
    private static final String P_ACCENT_CSS  = "__RL_ACCENT_CSS__";
    private static final String P_CUSTOM_CSS  = "__RL_CUSTOM_CSS__";
    private static volatile String cached;

    private TemplateRenderer() {}

    /** Renders the report HTML for the given data map. */
    public static String render(Map<String, Object> data) {
        String tpl = template();

        // Fill the render-time overrides. Empty string is fine — the template
        // shape stays valid CSS/HTML either way.
        String accent    = optString(data, "options.accent");
        String customCss = optString(data, "options.customCss");

        String accentCss = (accent == null || accent.isEmpty())
            ? ""
            : ":root{--accent:" + escapeForCss(accent) + "!important}";

        String customCssOut = customCss == null ? "" : customCss;

        String json = Json.write(data);
        // Guard against a </script sequence inside JSON string values ending
        // the rl-data script tag early. Same defence as the JS reporter.
        json = json.replace("</script", "<\\/script");

        String out = tpl;
        // Only the data placeholder is mandatory. The CSS hooks depend on the
        // template snapshot's vintage; when absent, fall back to a <style>
        // block in <head> so accent / customCss still work and — above all —
        // the report is still written.
        out = replaceOrInjectStyle(out, P_ACCENT_CSS, accentCss);
        out = replaceOrInjectStyle(out, P_CUSTOM_CSS, customCssOut);
        out = replaceOnce(out, P_DATA, json);
        if (!Config.embedFonts()) out = dropEmbeddedFonts(out);
        return out;
    }

    /** The snapshot carries IBM Plex as base64 (about 130 KB). With
     *  reporting-labs.embedFonts=false the report links Google Fonts instead,
     *  exactly what the Node.js reporter emits for the same option. */
    private static final java.util.regex.Pattern FONT_BLOCK =
        java.util.regex.Pattern.compile("<style>@font-face[\\s\\S]*?</style>");
    private static final String GOOGLE_FONTS =
        "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n"
      + "<link href=\"https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">";

    static String dropEmbeddedFonts(String html) {
        java.util.regex.Matcher m = FONT_BLOCK.matcher(html);
        return m.find() ? html.substring(0, m.start()) + GOOGLE_FONTS + html.substring(m.end()) : html;
    }

    private static String replaceOrInjectStyle(String html, String needle, String css) {
        int idx = html.indexOf(needle);
        if (idx >= 0) return splice(html, idx, needle.length(), css);
        if (css.isEmpty()) return html;
        int head = html.indexOf("</head>");
        if (head < 0) return html;
        return splice(html, head, 0, "<style>" + css + "</style>");
    }

    private static String replaceOnce(String haystack, String needle, String replacement) {
        int idx = haystack.indexOf(needle);
        if (idx < 0) {
            throw new IllegalStateException(
                "reporting-labs: template.html is missing the " + needle + " placeholder. " +
                "Was the bundled resource swapped out or corrupted?");
        }
        return splice(haystack, idx, needle.length(), replacement);
    }

    private static String splice(String s, int at, int len, String replacement) {
        StringBuilder sb = new StringBuilder(s.length() + replacement.length());
        sb.append(s, 0, at);
        sb.append(replacement);
        sb.append(s, at + len, s.length());
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String optString(Map<String, Object> data, String path) {
        Object v = data;
        for (String part : path.split("\\.")) {
            if (!(v instanceof Map)) return null;
            v = ((Map<String, Object>) v).get(part);
        }
        return v == null ? null : v.toString();
    }

    /** Keep to a safe subset. Sanitises anything that could escape the
     *  `:root{--accent: X !important}` rule. */
    private static String escapeForCss(String s) {
        return s.replaceAll("[<>\"'\\\\;{}\\r\\n]", "");
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
