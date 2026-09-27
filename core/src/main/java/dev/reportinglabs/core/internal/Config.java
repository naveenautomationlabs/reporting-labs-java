package dev.reportinglabs.core.internal;

import java.io.*;
import java.util.*;

/**
 * Reads report options from (in order, highest first):
 *   1. System properties: -Dreporting-labs.title=...
 *   2. src/test/resources/reporting-labs.properties
 *   3. Environment variables: REPORTING_LABS_TITLE=...
 *
 * Names mirror the JavaScript reporter's ReportingLabsOptions. Everything is
 * optional; sensible defaults apply. Unknown keys are ignored — safe to add
 * new options over time without breaking older versions of the file.
 */
public final class Config {
    private static final Properties FILE = loadFile();

    private Config() {}

    private static Properties loadFile() {
        Properties p = new Properties();
        String[] paths = {
            "reporting-labs.properties",
            "src/test/resources/reporting-labs.properties",
            "src/main/resources/reporting-labs.properties",
        };
        for (String path : paths) {
            File f = new File(path);
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) { load(p, in); break; } catch (IOException ignore) {}
            }
        }
        if (p.isEmpty()) {
            try (InputStream in = Config.class.getClassLoader().getResourceAsStream("reporting-labs.properties")) {
                if (in != null) load(p, in);
            } catch (IOException ignore) {}
        }
        return p;
    }

    // Properties.load(InputStream) assumes ISO-8859-1, which turns an em dash
    // or any non-ASCII title into mojibake. Files are UTF-8 in practice.
    private static void load(Properties p, InputStream in) throws IOException {
        p.load(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------- primitive lookup ----------

    private static String get(String key, String def) {
        String v = System.getProperty("reporting-labs." + key);
        if (v != null) return v;
        v = FILE.getProperty("reporting-labs." + key);
        if (v != null) return v;
        v = System.getenv("REPORTING_LABS_" + toEnv(key));
        return v != null ? v : def;
    }

    private static String toEnv(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static boolean getBool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) return def;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("on");
    }

    private static int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static List<String> getList(String key, List<String> def) {
        String v = get(key, null);
        if (v == null || v.isEmpty()) return def;
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static Map<String, String> prefixMap(String subPrefix) {
        String full = "reporting-labs." + subPrefix;
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith(full)) out.put(k.substring(full.length()), System.getProperty(k));
        }
        for (String k : FILE.stringPropertyNames()) {
            if (k.startsWith(full) && !out.containsKey(k.substring(full.length()))) {
                out.put(k.substring(full.length()), FILE.getProperty(k));
            }
        }
        String envPrefix = "REPORTING_LABS_" + toEnv(subPrefix.substring(0, subPrefix.length() - 1));
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith(envPrefix + "_")) {
                String rest = e.getKey().substring(envPrefix.length() + 1).toLowerCase(Locale.ROOT);
                if (!out.containsKey(rest)) out.put(rest, e.getValue());
            }
        }
        return out;
    }

    // ---------- look & feel ----------

    public static String title()        { return get("title", "Test report"); }

    /** Default is `target/reporting-labs` for Maven (`target/` exists) or
     *  `build/reporting-labs` for Gradle (`build/` exists), so the report
     *  lands under the standard build output folder and is wiped by
     *  `mvn clean` / `gradle clean`. Set to any path to override. */
    public static String outputFolder() {
        String v = get("outputFolder", null);
        if (v != null) return v;
        if (new File("target").isDirectory()) return "target/reporting-labs";   // Maven
        if (new File("build").isDirectory())  return "build/reporting-labs";    // Gradle
        return "reporting-labs";                                                // fallback (matches Node.js side)
    }

    public static String outputFile()   { return get("outputFile", "index.html"); }

    /** When to open the generated HTML in the default browser after the run.
     *  `never` (default) | `always` | `on-failure`. Auto-skipped when running
     *  headless or on CI regardless of the setting. */
    public static String open()         { return get("open", "never"); }

    /** Capture policy for screenshots. `on-failure` (default) | `always` |
     *  `only-on-pass` | `never`. Read by reporting-labs-playwright's auto
     *  capture; also readable via {@link dev.reportinglabs.core.Rl#screenshotMode()}
     *  so Selenium / plain-Java tests can honour the same setting from an
     *  @AfterMethod hook. */
    public static String screenshot()   { return normalize(get("screenshot", "on-failure")); }

    /** Trace-capture policy for Playwright. Same values as `screenshot`
     *  (default `on-failure`) plus `retain-on-failure` which is treated the
     *  same as `on-failure`. */
    public static String trace()        { return normalize(get("trace", "on-failure")); }

    /** Video-capture policy — informational for Selenium/Playwright users
     *  who record their own videos. The library never records; users attach
     *  bytes via Rl.attach(). Same values as `screenshot`. */
    public static String video()        { return normalize(get("video", "off")); }

    private static String normalize(String v) {
        if (v == null) return "off";
        String s = v.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "on":                 return "always";
            case "off":                return "never";
            case "retain-on-failure":  return "on-failure";
            default:                   return s;
        }
    }

    /** Convenience: should the caller capture given the current mode and
     *  whether the test failed? Handles all policy values. */
    public static boolean shouldCapture(String mode, boolean failed) {
        if (mode == null) return false;
        switch (mode) {
            case "always":       return true;
            case "on-failure":   return failed;
            case "only-on-pass": return !failed;
            case "never":        return false;
            default:             return false;
        }
    }
    public static String theme()        { return get("theme", "auto"); }
    public static String palette()      { return get("palette", "lab"); }
    public static String accent()       { return get("accent", ""); }
    public static String customCss()    { return get("customCss", ""); }
    public static boolean embedFonts()  { return getBool("embedFonts", true); }
    public static boolean editorLinks() { return getBool("editorLinks", false); }
    public static boolean bdd()         { return getBool("bdd", false); }

    // ---------- header ----------

    /** metadata.<name> becomes a header chip. metadata.build labels the run in the trend. */
    public static Map<String, String> metadata() { return prefixMap("metadata."); }

    /** Turn @Story("SHOP-1") etc. into clickable chips. Value is a URL
     *  template with {id}, e.g. https://acme.atlassian.net/browse/{id} */
    public static Map<String, String> links()    { return prefixMap("links."); }

    /** Extra key/value rows for the Environment card. */
    public static Map<String, String> env()      { return prefixMap("env."); }

    /** project.name / project.version / project.team / project.url / project.description */
    public static Map<String, Object> project() {
        Map<String, String> raw = prefixMap("project.");
        if (raw.isEmpty()) return null;
        return new LinkedHashMap<>(raw);
    }

    /** Manual override; the actual thread count used at runtime is
     *  auto-detected in ReportBuilder from RlInternal.workerThreadCount(). */
    public static Integer workersOverride() {
        String v = get("workers", null);
        if (v == null) return null;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Purely informational grouping. Blank by default — Java doesn't have
     *  Playwright's project concept unless the user opts in. */
    public static List<String> projects() { return getList("projects", Collections.emptyList()); }

    // ---------- CI auto-detect ----------

    /** Pulls build number, git branch and commit hash out of common CI env
     *  vars (GitHub Actions, Jenkins, GitLab CI, CircleCI, Travis, Buildkite,
     *  TeamCity, Azure Pipelines). Anything the user already set in
     *  reporting-labs.metadata.<key> wins over the auto value.
     *
     *  Called by ReportBuilder — merges into data.metadata so the trend
     *  chart's build label just works on any pipeline. */
    public static Map<String, String> ciDetected() {
        Map<String, String> out = new LinkedHashMap<>();
        String env = System.getenv("GITHUB_ACTIONS");
        if (env != null && !env.isEmpty()) {
            put(out, "build",  System.getenv("GITHUB_RUN_NUMBER"));
            put(out, "branch", System.getenv("GITHUB_REF_NAME"));
            put(out, "commit", shortSha(System.getenv("GITHUB_SHA")));
            put(out, "ci",     "github-actions");
            return out;
        }
        if (System.getenv("JENKINS_URL") != null) {
            put(out, "build",  System.getenv("BUILD_NUMBER"));
            put(out, "branch", System.getenv("GIT_BRANCH"));
            put(out, "commit", shortSha(System.getenv("GIT_COMMIT")));
            put(out, "ci",     "jenkins");
            return out;
        }
        if (System.getenv("GITLAB_CI") != null) {
            put(out, "build",  System.getenv("CI_PIPELINE_IID"));
            put(out, "branch", System.getenv("CI_COMMIT_REF_NAME"));
            put(out, "commit", shortSha(System.getenv("CI_COMMIT_SHA")));
            put(out, "ci",     "gitlab-ci");
            return out;
        }
        if (System.getenv("CIRCLECI") != null) {
            put(out, "build",  System.getenv("CIRCLE_BUILD_NUM"));
            put(out, "branch", System.getenv("CIRCLE_BRANCH"));
            put(out, "commit", shortSha(System.getenv("CIRCLE_SHA1")));
            put(out, "ci",     "circleci");
            return out;
        }
        if (System.getenv("TRAVIS") != null) {
            put(out, "build",  System.getenv("TRAVIS_BUILD_NUMBER"));
            put(out, "branch", System.getenv("TRAVIS_BRANCH"));
            put(out, "commit", shortSha(System.getenv("TRAVIS_COMMIT")));
            put(out, "ci",     "travis");
            return out;
        }
        if (System.getenv("BUILDKITE") != null) {
            put(out, "build",  System.getenv("BUILDKITE_BUILD_NUMBER"));
            put(out, "branch", System.getenv("BUILDKITE_BRANCH"));
            put(out, "commit", shortSha(System.getenv("BUILDKITE_COMMIT")));
            put(out, "ci",     "buildkite");
            return out;
        }
        if (System.getenv("TEAMCITY_VERSION") != null) {
            put(out, "build",  System.getenv("BUILD_NUMBER"));
            put(out, "ci",     "teamcity");
            return out;
        }
        if (System.getenv("TF_BUILD") != null) {  // Azure Pipelines
            put(out, "build",  System.getenv("BUILD_BUILDNUMBER"));
            put(out, "branch", System.getenv("BUILD_SOURCEBRANCHNAME"));
            put(out, "commit", shortSha(System.getenv("BUILD_SOURCEVERSION")));
            put(out, "ci",     "azure-pipelines");
            return out;
        }
        return out;
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !value.isEmpty()) out.put(key, value);
    }
    private static String shortSha(String s) {
        return (s == null || s.length() < 7) ? s : s.substring(0, 7);
    }

    // ---------- history ----------

    public static boolean historyEnabled() { return getBool("history.enabled", true); }
    public static String  historyFile()    { return get("history.file", "reporting-labs.history.json"); }
    public static int     historyKeep()    { return getInt("history.keep", 30); }

    // ---------- security / masking ----------

    /** Extra case-insensitive substrings to mask in testData / API headers,
     *  on top of the built-in defaults (password, token, authorization…). */
    public static List<String> maskKeys()  { return getList("maskKeys", Collections.emptyList()); }

    // ---------- charts / dimensions ----------

    public static List<String> dimensions() {
        return getList("dimensions", Arrays.asList("priority", "severity", "owner", "feature"));
    }

    /** Custom sort order for a dimension's values:
     *    reporting-labs.dimensionOrder.severity=blocker,critical,major,minor
     */
    public static Map<String, List<String>> dimensionOrder() {
        Map<String, String> raw = prefixMap("dimensionOrder.");
        Map<String, List<String>> out = new LinkedHashMap<>();
        // Same defaults as the JS reporter. The template's ranking code
        // indexes dimensionOrder.priority / .severity unconditionally, so
        // these must always be present — a report with two or more failures
        // renders blank otherwise.
        out.put("priority", Arrays.asList("P0", "P1", "P2", "P3", "P4"));
        out.put("severity", Arrays.asList("blocker", "critical", "major", "high", "medium", "normal", "minor", "low", "trivial"));
        for (Map.Entry<String, String> e : raw.entrySet()) {
            List<String> parts = new ArrayList<>();
            for (String p : e.getValue().split(",")) { String t = p.trim(); if (!t.isEmpty()) parts.add(t); }
            out.put(e.getKey(), parts);
        }
        return out;
    }

    // ---------- widgets (toggle report cards) ----------

    /** Which cards to render. All default to true; set any to false to hide. */
    public static Map<String, Object> widgets() {
        String[] known = {
            "runStrip","outcome","attention","dimensions","timeline","durations",
            "tags","slowest","projects","flaky","environment","skipped",
            "overviewCards","breakdown","needsAttention","failureClusters","trend","env",
        };
        Map<String, Object> out = new LinkedHashMap<>();
        for (String w : known) out.put(w, getBool("widgets." + w, true));
        return out;
    }

    // ---------- extra HTML sections ----------

    /** Extra sections rendered below the summary. Configure like:
     *   reporting-labs.sections.release.title=Release notes
     *   reporting-labs.sections.release.html=<p>See <a href=...>changelog</a></p>
     *   reporting-labs.sections.contacts.title=On-call
     *   reporting-labs.sections.contacts.html=<p>@qa-oncall</p>
     */
    public static List<Map<String, String>> sections() {
        Map<String, String> raw = prefixMap("sections.");
        Map<String, Map<String, String>> byName = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String[] p = e.getKey().split("\\.", 2);
            if (p.length != 2) continue;
            byName.computeIfAbsent(p[0], k -> new LinkedHashMap<>()).put(p[1], e.getValue());
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> s : byName.values()) {
            if (!s.containsKey("title") && !s.containsKey("html")) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("title", s.getOrDefault("title", ""));
            row.put("html",  s.getOrDefault("html", ""));
            out.add(row);
        }
        return out;
    }
}
