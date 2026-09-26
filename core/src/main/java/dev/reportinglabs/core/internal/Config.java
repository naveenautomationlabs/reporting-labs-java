package dev.reportinglabs.core.internal;

import java.io.*;
import java.util.*;

/**
 * Reads report options from (in order):
 *   1. System properties: -Dreporting-labs.title=...
 *   2. src/test/resources/reporting-labs.properties
 *   3. Environment variables: REPORTING_LABS_TITLE=...
 *
 * The names mirror the JS reporter's ReportingLabsOptions where sensible.
 * Everything is optional; sensible defaults apply.
 */
public final class Config {
    private static final Properties FILE = loadFile();

    private Config() {}

    private static Properties loadFile() {
        Properties p = new Properties();
        // Look in a few common places.
        String[] paths = {
            "reporting-labs.properties",
            "src/test/resources/reporting-labs.properties",
            "src/main/resources/reporting-labs.properties",
        };
        for (String path : paths) {
            File f = new File(path);
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) { p.load(in); break; } catch (IOException ignore) {}
            }
        }
        // Fallback to classpath.
        if (p.isEmpty()) {
            try (InputStream in = Config.class.getClassLoader().getResourceAsStream("reporting-labs.properties")) {
                if (in != null) p.load(in);
            } catch (IOException ignore) {}
        }
        return p;
    }

    private static String get(String key, String def) {
        String v = System.getProperty("reporting-labs." + key);
        if (v != null) return v;
        v = FILE.getProperty("reporting-labs." + key);
        if (v != null) return v;
        v = System.getenv("REPORTING_LABS_" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        if (v != null) return v;
        return def;
    }

    public static String title()        { return get("title", "Test report"); }
    public static String outputFolder() { return get("outputFolder", "reporting-labs"); }
    public static String theme()        { return get("theme", "auto"); }
    public static String palette()      { return get("palette", "lab"); }

    /** metadata.<name> becomes a chip in the header. metadata.build labels the run in the history. */
    public static Map<String, String> metadata() {
        return prefixMap("metadata.");
    }

    /** links.story = 'https://x/browse/{id}' turns a story chip into a clickable link. */
    public static Map<String, String> links() {
        return prefixMap("links.");
    }

    /** project.name / project.version / project.team / project.url */
    public static Map<String, Object> project() {
        Map<String, String> raw = prefixMap("project.");
        if (raw.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>(raw);
        return m;
    }

    public static List<String> projects() {
        String v = get("projects", null);
        if (v == null || v.isEmpty()) return Collections.singletonList("java");
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    public static int workers() {
        String v = get("workers", null);
        if (v == null) return 1;
        try { return Math.max(1, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return 1; }
    }

    private static Map<String, String> prefixMap(String subPrefix) {
        String full = "reporting-labs." + subPrefix;
        Map<String, String> out = new LinkedHashMap<>();
        // system properties
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith(full)) out.put(k.substring(full.length()), System.getProperty(k));
        }
        // file
        for (String k : FILE.stringPropertyNames()) {
            if (k.startsWith(full) && !out.containsKey(k.substring(full.length()))) out.put(k.substring(full.length()), FILE.getProperty(k));
        }
        // env
        String envPrefix = "REPORTING_LABS_" + subPrefix.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith(envPrefix)) {
                String rest = e.getKey().substring(envPrefix.length()).toLowerCase(Locale.ROOT);
                if (!out.containsKey(rest)) out.put(rest, e.getValue());
            }
        }
        return out;
    }
}
