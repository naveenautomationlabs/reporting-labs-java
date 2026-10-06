package dev.reportinglabs.core.internal;

import java.io.*;
import java.util.*;

/**
 * Reads report options from (in order, highest first):
 *   1. System properties: -Dreporting-labs.title=...
 *   2. Environment variables: REPORTING_LABS_TITLE=...
 *   3. src/test/resources/reporting-labs.properties
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
        // -D on the command line wins, then the environment (CI sets it),
        // then the file checked into the project.
        String v = System.getProperty("reporting-labs." + key);
        if (v == null) v = System.getenv("REPORTING_LABS_" + toEnv(key));
        if (v == null) v = FILE.getProperty("reporting-labs." + key);
        return v != null ? stripInlineComment(key, v) : def;
    }

    // Keys whose values may legitimately contain '#': colours, CSS, URLs,
    // free text. Everything else is an enum / boolean / number / list, where
    // a trailing "  # comment" can only be a comment.
    private static final String[] FREE_TEXT_PREFIXES = {
        "title", "accent", "customCss", "outputFolder", "outputFile", "pdfFile", "chromePath",
        "project.", "metadata.", "links.", "env.", "sections.",
    };

    /** java.util.Properties has no inline comments, so
     *  {@code screenshot=always   # never | on-failure | always} yields the
     *  value "always   # never | …". Tolerate that for option-style keys. */
    static String stripInlineComment(String key, String v) {
        for (String p : FREE_TEXT_PREFIXES) {
            if (key.equals(p) || (p.endsWith(".") && key.startsWith(p))) return v;
        }
        int i = -1;
        for (int j = 1; j < v.length(); j++) {
            if (v.charAt(j) == '#' && Character.isWhitespace(v.charAt(j - 1))) { i = j; break; }
        }
        return i < 0 ? v : v.substring(0, i).trim();
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
        // Same order as get(): -D, then the environment, then the file.
        String envPrefix = "REPORTING_LABS_" + toEnv(subPrefix.substring(0, subPrefix.length() - 1));
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith(envPrefix + "_")) {
                String rest = e.getKey().substring(envPrefix.length() + 1).toLowerCase(Locale.ROOT);
                if (!out.containsKey(rest)) out.put(rest, e.getValue());
            }
        }
        for (String k : FILE.stringPropertyNames()) {
            if (k.startsWith(full) && !out.containsKey(k.substring(full.length()))) {
                out.put(k.substring(full.length()), FILE.getProperty(k));
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

    /** Also write a print-ready report.pdf next to the HTML, rendered by a headless Chrome / Chromium.
     *  Default true; set reporting-labs.pdf=false to skip. The report also has an "Export PDF" button. */
    public static boolean pdf()         { return getBool("pdf", true); }
    /** Read meta from the Javadoc of a test method or class: {@code @owner naveen @priority P0}. Annotations win. */
    public static boolean commentMeta() { return getBool("commentMeta", true); }
    public static String pdfFile()      { return get("pdfFile", "report.pdf"); }
    /** Explicit path to a Chrome / Chromium binary; otherwise the usual locations are searched. */
    public static String chromePath()   { String v = get("chromePath", ""); return v.isEmpty() ? null : v; }

    /** When to open the generated HTML in the default browser after the run.
     *  `never` (default) | `always` | `on-failure`. Auto-skipped when running
     *  headless or on CI regardless of the setting. */
    public static String open()         { return get("open", "never"); }

    /** Capture policy for screenshots. `on-failure` (default) | `always` |
     *  `only-on-pass` | `never`. Read by reporting-labs-playwright's auto
     *  capture; also readable via {@link dev.reportinglabs.core.Rl#screenshotMode()}
     *  so Selenium / plain-Java tests can honour the same setting from an
     *  @AfterMethod hook. */
    /** Copy System.out / System.err lines into each test's Console output. */
    public static boolean captureStdout() { return getBool("captureStdout", true); }

    /** Selenium add-on: find the WebDriver in the test instance and wire it
     *  automatically (no RlSelenium.attach() call needed). */
    public static boolean seleniumAutoAttach() { return getBool("selenium.autoAttach", true); }
    public static boolean restAssuredAutoRecord() { return getBool("restassured.autoRecord", true); }
    /** Playwright add-on: find Page / BrowserContext / APIRequestContext on the test instance and attach them. */
    public static boolean playwrightAutoAttach() { return getBool("playwright.autoAttach", true); }
    /** Playwright add-on: turn the actions recorded in the Playwright trace into steps. */
    public static boolean playwrightSteps() { return getBool("playwright.steps", true); }

    public static String screenshot()   { return normalize(get("screenshot", "on-failure")); }

    /** Trace-capture policy for Playwright. Same values as `screenshot`
     *  (default `on-failure`) plus `retain-on-failure` which is treated the
     *  same as `on-failure`. */
    public static String trace()        { return normalize(get("trace", "never")); }

    /** Video-capture policy — informational for Selenium/Playwright users
     *  who record their own videos. The library never records; users attach
     *  bytes via Rl.attach(). Same values as `screenshot`. */
    public static String video()        { return normalize(get("video", "off")); }

    /** Per-tool policies: reporting-labs.playwright.screenshot,
     *  reporting-labs.selenium.screenshot, ... fall back to the plain key. */
    public static String screenshot(String tool) { String v = get(tool + ".screenshot", null); return v != null ? normalize(v) : screenshot(); }
    public static String trace(String tool)      { String v = get(tool + ".trace", null);      return v != null ? normalize(v) : trace(); }
    public static String video(String tool)      { String v = get(tool + ".video", null);      return v != null ? normalize(v) : video(); }

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
    /** Your logo in the report header: a png/jpg/svg/gif/webp file (a path, or a test-classpath resource), an https URL, or a data URI. */
    public static String logo()         { return get("logo", ""); }
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
    public static Map<String, String> env() {
        // java.util.Properties ends a key at the first space, so
        // "reporting-labs.env.App version=2.4.0" arrives as App -> "version=2.4.0".
        // Put the label back together when the value looks like that.
        Map<String, String> raw = prefixMap("env.");
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey(), v = e.getValue() == null ? "" : e.getValue();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z][\\w-]*)=(.*)$").matcher(v);
            if (!k.contains(" ") && m.matches()) { k = k + " " + m.group(1); v = m.group(2); }
            out.put(k, v);
        }
        return out;
    }

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

    /** The env chip from the runtime: -Denv, -Denvironment, -DtestEnv, or
     *  the ENV / TEST_ENV / ENVIRONMENT / APP_ENV variable a pipeline job
     *  exports. Wins over metadata.env in the properties file (a runtime
     *  value beats the file, like every other key), so one suite run against
     *  dev, qa and stage labels each report with no config change. Null when none. */
    public static String detectedEnv() {
        return detectedEnv(System.getenv(), System.getProperties(), envVar());
    }

    /** True when the key was given at runtime (-Dreporting-labs.<key> or REPORTING_LABS_<KEY>), not in the file. */
    public static boolean setAtRuntime(String key) {
        if (System.getProperty("reporting-labs." + key) != null) return true;
        String envKey = "REPORTING_LABS_" + key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        String v = System.getenv(envKey);
        return v != null && !v.trim().isEmpty();
    }

    /** Name of the variable that holds the environment name, for a project whose name the detection cannot guess. */
    public static String envVar() { String v = get("envVar", ""); return v.isEmpty() ? null : v; }

    /** Variables whose name ends in ENV but never hold an environment name. */
    private static final Set<String> NOT_AN_ENV_VAR = new HashSet<>(Arrays.asList("GITHUB_ENV", "NODE_ENV", "BASH_ENV", "VIRTUAL_ENV",
        "CONDA_DEFAULT_ENV", "RUNNER_ENVIRONMENT", "PIPENV_ACTIVE", "ZSH_ENV", "JAVA_ENV", "DOTNET_ENVIRONMENT", "ASPNETCORE_ENVIRONMENT", "HOSTING_ENVIRONMENT"));
    /** Names every project seems to pick first, in order. */
    private static final String[] ENV_VAR_NAMES = { "ENV", "TEST_ENV", "ENVIRONMENT", "APP_ENV", "TARGET_ENV", "RUN_ENV", "DEPLOY_ENV", "ENV_NAME",
        "TEST_ENVIRONMENT", "TARGET_ENVIRONMENT", "CI_ENVIRONMENT_NAME", "DEPLOYMENT_ENVIRONMENT", "STAGE" };
    private static final String[] ENV_PROPERTY_NAMES = { "env", "environment", "testEnv", "test.env", "app.env", "target.env", "spring.profiles.active" };
    /** dev, qa, stage-2, app_qa, prod-eu: a short token, never a path, URL or sentence. */
    private static boolean looksLikeEnvName(String v) { return v != null && v.trim().matches("[A-Za-z][\\w.-]{0,31}"); }

    /** -Denv style properties first, then the variable named by envVar, then the usual variable
     *  names, then any variable whose name ends in _ENV or _ENVIRONMENT (OPENCART_ENV, app_env).
     *  Every project names it differently; this finds it without being told. Null when nothing fits. */
    static String detectedEnv(Map<String, String> env, java.util.Properties props, String envVar) {
        for (String k : ENV_PROPERTY_NAMES) { String v = props.getProperty(k); if (looksLikeEnvName(v)) return v.trim(); }
        if (envVar != null) {
            String v = env.get(envVar); if (v == null) v = env.get(envVar.toLowerCase(Locale.ROOT)); if (v == null) v = props.getProperty(envVar);
            return looksLikeEnvName(v) ? v.trim() : null;
        }
        for (String k : ENV_VAR_NAMES) {
            String v = env.get(k); if (v == null) v = env.get(k.toLowerCase(Locale.ROOT));
            if (looksLikeEnvName(v)) return v.trim();
        }
        List<String> wild = new ArrayList<>();
        for (String k : env.keySet()) {
            String u = k.toUpperCase(Locale.ROOT);
            if ((u.endsWith("_ENV") || u.endsWith("_ENVIRONMENT") || u.endsWith("_ENV_NAME")) && !NOT_AN_ENV_VAR.contains(u)) wild.add(k);
        }
        Collections.sort(wild);
        for (String k : wild) if (looksLikeEnvName(env.get(k))) return env.get(k).trim();
        return null;
    }

    /** Pulls git branch, commit hash and the CI name out of common CI env
     *  vars (GitHub Actions, Jenkins, GitLab CI, CircleCI, Travis, Buildkite,
     *  TeamCity, Azure Pipelines). Anything the user already set in
     *  reporting-labs.metadata.<key> wins over the auto value. The build
     *  number is deliberately not a chip: it labels the trend (ciRunNumber)
     *  and is shown only when the user sets metadata.build, as in the Node
     *  reporter. */
    public static Map<String, String> ciDetected() {
        Map<String, String> out = new LinkedHashMap<>();
        String env = System.getenv("GITHUB_ACTIONS");
        if (env != null && !env.isEmpty()) {
            put(out, "branch", System.getenv("GITHUB_REF_NAME"));
            put(out, "commit", shortSha(System.getenv("GITHUB_SHA")));
            put(out, "ci",     "github-actions");
            return out;
        }
        if (System.getenv("JENKINS_URL") != null) {
            put(out, "branch", System.getenv("GIT_BRANCH"));
            put(out, "commit", shortSha(System.getenv("GIT_COMMIT")));
            put(out, "ci",     "jenkins");
            return out;
        }
        if (System.getenv("GITLAB_CI") != null) {
            put(out, "branch", System.getenv("CI_COMMIT_REF_NAME"));
            put(out, "commit", shortSha(System.getenv("CI_COMMIT_SHA")));
            put(out, "ci",     "gitlab-ci");
            return out;
        }
        if (System.getenv("CIRCLECI") != null) {
            put(out, "branch", System.getenv("CIRCLE_BRANCH"));
            put(out, "commit", shortSha(System.getenv("CIRCLE_SHA1")));
            put(out, "ci",     "circleci");
            return out;
        }
        if (System.getenv("TRAVIS") != null) {
            put(out, "branch", System.getenv("TRAVIS_BRANCH"));
            put(out, "commit", shortSha(System.getenv("TRAVIS_COMMIT")));
            put(out, "ci",     "travis");
            return out;
        }
        if (System.getenv("BUILDKITE") != null) {
            put(out, "branch", System.getenv("BUILDKITE_BRANCH"));
            put(out, "commit", shortSha(System.getenv("BUILDKITE_COMMIT")));
            put(out, "ci",     "buildkite");
            return out;
        }
        if (System.getenv("TEAMCITY_VERSION") != null) {
            put(out, "ci",     "teamcity");
            return out;
        }
        if (System.getenv("TF_BUILD") != null) {  // Azure Pipelines
            put(out, "branch", System.getenv("BUILD_SOURCEBRANCHNAME"));
            put(out, "commit", shortSha(System.getenv("BUILD_SOURCEVERSION")));
            put(out, "ci",     "azure-pipelines");
            return out;
        }
        return out;
    }

    /** The run number of the CI system, used to label the trend when metadata.build is not set. */
    public static String ciRunNumber() {
        for (String k : new String[] { "GITHUB_RUN_NUMBER", "BUILD_NUMBER", "CI_PIPELINE_IID", "CIRCLE_BUILD_NUM", "TRAVIS_BUILD_NUMBER", "BUILDKITE_BUILD_NUMBER", "BUILD_BUILDNUMBER", "BITBUCKET_BUILD_NUMBER" }) {
            String v = System.getenv(k);
            if (v != null && !v.trim().isEmpty()) return "#" + v.trim();
        }
        return null;
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
    /** Literal values to blank wherever they appear (the test password, a token): reporting-labs.maskValues=a,b */
    public static List<String> maskValues() { return getList("maskValues", Collections.emptyList()); }
    /** Learn the values of PASSWORD / API_TOKEN / *_SECRET environment variables and -D properties (default true). */
    public static boolean maskFromEnv()     { return getBool("maskFromEnv", true); }

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
