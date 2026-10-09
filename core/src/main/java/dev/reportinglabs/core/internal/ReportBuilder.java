package dev.reportinglabs.core.internal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds the shared ReportData map that the HTML template renders. The
 * shape mirrors the JS reporter's src/types.ts ReportData interface — same
 * field names, same nesting — so both languages fill the same template.
 *
 * The history file (reporting-labs.history.json next to the working dir by
 * default) records one entry per run and powers the Trend chart, the
 * "failing since X" markers, and the flaky-history dots. Bounded to keep
 * the file small.
 */
public final class ReportBuilder {

    private ReportBuilder() {}

    public static Map<String, Object> build(List<RlInternal.TestSlot> tests, long suiteStart) {
        long now = System.currentTimeMillis();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title",       Config.title());
        data.put("generatedAt", now);
        data.put("startTime",   suiteStart);
        data.put("duration",    Math.max(0, now - suiteStart));
        // Merge CI-detected metadata (branch / commit / ci) with what the user
        // explicitly set. User wins if a key overlaps. The build number is not a
        // chip; it labels the trend below.
        Map<String, String> md = new LinkedHashMap<>();
        md.putAll(Config.ciDetected());
        md.putAll(Config.metadata());
        // Precedence for the env chip, highest first: -Dreporting-labs.metadata.env or
        // REPORTING_LABS_METADATA_ENV (explicit, the reporter's own key, already merged by
        // Config.metadata()); then the environment name found by convention (-Denv, ENV,
        // TEST_ENV, APP_ENV, anything ending in _ENV, or the variable reporting-labs.envVar
        // names), which beats metadata.env in the properties file as a runtime value beats
        // the file everywhere else; then the file.
        if (!Config.setAtRuntime("metadata.env")) {
            String envName = Config.detectedEnv();
            if (envName != null) md.put("env", envName);
        }
        data.put("metadata",    md);

        data.put("projects",    Config.projects());

        // Real worker count = number of distinct threads that actually ran a
        // test in this JVM. Falls back to the manual override if the user set
        // reporting-labs.workers, else the auto-detected count.
        Integer wOverride = Config.workersOverride();
        data.put("workers",     wOverride != null ? wOverride : RlInternal.workerThreadCount());

        // Run order: FINISHED is a concurrent map, so sort by start time —
        // the overview strip and retry grouping both depend on it.
        List<RlInternal.TestSlot> ordered = new ArrayList<>(tests);
        ordered.sort(Comparator.comparingLong((RlInternal.TestSlot t) -> t.startTime));

        // A retried invocation (TestNG IRetryAnalyzer) becomes an earlier
        // attempt of the next invocation with the same key; a test whose last
        // attempt passed after a failure is flaky — same as the Node reporter.
        List<Map<String, Object>> testList = new ArrayList<>(ordered.size());
        Map<String, Map<String, Object>> openRetries = new HashMap<>();
        int i = 0;
        for (RlInternal.TestSlot t : ordered) {
            Map<String, Object> result = toResult(t);
            Map<String, Object> test = openRetries.remove(t.key);
            if (test == null) {
                test = toTestMap(t, i++, result);
                testList.add(test);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> results = (List<Map<String, Object>>) test.get("results");
                result.put("retry", results.size());
                results.add(result);
                test.put("duration", ((Number) test.get("duration")).longValue() + t.duration);
                boolean earlierFailed = results.stream().limit(results.size() - 1).anyMatch(r -> "failed".equals(r.get("status")));
                test.put("outcome", "passed".equals(t.outcome) && earlierFailed ? "flaky" : t.outcome);
            }
            if (t.retried) openRetries.put(t.key, test);
        }
        int passed = 0, failed = 0, flaky = 0, skipped = 0;
        for (Map<String, Object> test : testList) {
            switch (String.valueOf(test.get("outcome"))) {
                case "passed":  passed++;  break;
                case "failed":  failed++;  break;
                case "flaky":   flaky++;   break;
                case "skipped": skipped++; break;
                default: break;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total",       testList.size());
        stats.put("passed",      passed);
        stats.put("failed",      failed);
        stats.put("flaky",       flaky);
        stats.put("skipped",     skipped);
        // The template computes: failedTotal = s.failed + s.timedOut + s.interrupted.
        // These fields do not exist on the Java side, so include them as 0 to
        // stop the template from adding undefined and producing NaN.
        stats.put("timedOut",    0);
        stats.put("interrupted", 0);
        data.put("stats", stats);
        data.put("tests", testList);

        // History: append this run to reporting-labs.history.json and pass through
        History history = History.readAndAppend(now, suiteStart, testList, stats);
        data.put("history", history.entries);

        data.put("bdd", Config.bdd());
        data.put("rootDir", System.getProperty("user.dir", ""));
        data.put("env", envRows());
        data.put("runStatus", failed > 0 || !RlInternal.globalErrors().isEmpty() ? "failed" : "passed");
        data.put("globalErrors", RlInternal.globalErrors());
        data.put("globalOutput", Collections.emptyList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("theme",          Config.theme());
        options.put("palette",        Config.palette());
        options.put("accent",         Config.accent());
        String logo = Logo.resolve(Config.logo());
        if (logo != null) options.put("logo", logo);
        options.put("customCss",      Config.customCss());
        options.put("embedFonts",     Config.embedFonts());
        options.put("editorLinks",    Config.editorLinks());
        options.put("expandFailedSteps", Config.expandFailedSteps());
        options.put("widgets",        Config.widgets());
        options.put("dimensions",     Config.dimensions());
        options.put("dimensionOrder", Config.dimensionOrder());
        options.put("links",          Config.links());
        options.put("sections",       Config.sections());
        Map<String, Object> project = Config.project();
        if (project != null) options.put("project", project);
        data.put("options", options);

        return data;
    }

    private static Map<String, Object> toTestMap(RlInternal.TestSlot t, int idx, Map<String, Object> firstResult) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "t" + idx);
        m.put("key", t.key);
        m.put("title", t.title);
        m.put("path", t.path);
        m.put("file", t.file);
        m.put("line", t.line);
        m.put("project", t.projectName);
        m.put("tags", t.tags);
        List<Map<String, Object>> annotations = new ArrayList<>();
        if (t.skipReason != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", "skip");
            a.put("description", t.skipReason);
            annotations.add(a);
        }
        m.put("annotations", annotations);
        m.put("meta", t.meta);
        m.put("outcome", t.outcome);
        m.put("duration", t.duration);
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(firstResult);
        m.put("results", results);
        return m;
    }

    private static Map<String, Object> toResult(RlInternal.TestSlot t) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logs", t.logs);
        result.put("data", t.dataBlocks);
        result.put("api", t.apiCalls);
        result.put("retry", 0);
        result.put("status", t.outcome);
        result.put("duration", t.duration);
        result.put("startTime", t.startTime);
        result.put("workerIndex", t.workerIndex);
        List<Map<String, Object>> errors = new ArrayList<>();
        if (t.errorMessage != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("message", t.errorMessage == null ? "" : t.errorMessage);
            e.put("stack",   t.errorStack   == null ? "" : t.errorStack);
            if (t.errorSnippet != null)  e.put("snippet",  t.errorSnippet);
            if (t.errorLocation != null) e.put("location", t.errorLocation);
            if (t.explain != null)       e.put("explain",  t.explain);
            errors.add(e);
        }
        result.put("errors", errors);
        result.put("steps", t.steps);
        result.put("attachments", t.attachments);
        result.put("stdout", t.stdout);
        result.put("stderr", t.stderr);
        return result;
    }

    private static List<Map<String, Object>> envRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        addEnv(rows, "Java",     System.getProperty("java.version"));
        addEnv(rows, "Runtime",  System.getProperty("java.runtime.name"));
        addEnv(rows, "OS",       System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        addEnv(rows, "User",     System.getProperty("user.name"));
        // Extra rows from reporting-labs.env.* — values that look like URLs
        // become links in the template's Environment card.
        for (Map.Entry<String, String> e : Config.env().entrySet()) {
            addEnv(rows, e.getKey(), e.getValue());
        }
        return rows;
    }
    private static void addEnv(List<Map<String, Object>> rows, String k, String v) {
        if (v == null || v.isEmpty()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("k", k);
        r.put("v", v);
        if (v.startsWith("http://") || v.startsWith("https://")) r.put("href", v);   // the card links it
        rows.add(r);
    }

    // ---- history ----

    static final class History {
        final List<Map<String, Object>> entries;
        History(List<Map<String, Object>> entries) { this.entries = entries; }

        static History readAndAppend(long now, long start, List<Map<String, Object>> tests, Map<String, Object> stats) {
            if (!Config.historyEnabled()) return new History(Collections.emptyList());
            Path file = Paths.get(System.getProperty("user.dir", "."), Config.historyFile());
            List<Map<String, Object>> entries = new ArrayList<>();
            if (Files.exists(file)) {
                try {
                    String txt = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                    Object parsed = new JsonReader(txt).read();
                    if (parsed instanceof List) {
                        for (Object o : (List<?>) parsed) if (o instanceof Map) entries.add(cast((Map<?, ?>) o));
                    }
                } catch (Exception ignore) { /* corrupt or missing — start fresh */ }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            // Label is only used as the trend chart's x-axis tick: metadata.build,
            // else the CI run number. Leave it empty on a local run — the template's
            // JS then formats a short 'Sep 26' date instead of a millisecond epoch.
            String build = Config.metadata().get("build");
            if (build == null) build = Config.ciRunNumber();
            row.put("label",    build == null ? "" : build);
            row.put("time",     now);
            row.put("duration", Math.max(0, now - start));
            row.put("total",   ((Number) stats.get("total")).intValue());
            row.put("passed",  ((Number) stats.get("passed")).intValue());
            row.put("failed",  ((Number) stats.get("failed")).intValue());
            row.put("flaky",   ((Number) stats.get("flaky")).intValue());
            row.put("skipped", ((Number) stats.get("skipped")).intValue());
            // per-test outcome map, keyed by test.key, values are single-char arrays: 'p'|'f'|'k'|'s'
            // Per test: outcome code and the last attempt's duration, the shape the
            // Node reporter writes; the duration is what "Got slower" compares.
            Map<String, List<Object>> ttm = new LinkedHashMap<>();
            for (Map<String, Object> t : tests) {
                String o = String.valueOf(t.get("outcome"));
                String tag = "p";
                switch (o) {
                    case "failed":  tag = "f"; break;
                    case "flaky":   tag = "k"; break;
                    case "skipped": tag = "s"; break;
                    default: tag = "p";
                }
                long last = 0;
                @SuppressWarnings("unchecked") List<Map<String, Object>> results = (List<Map<String, Object>>) t.get("results");
                if (results != null && !results.isEmpty()) last = ((Number) results.get(results.size() - 1).getOrDefault("duration", 0L)).longValue();
                ttm.put(String.valueOf(t.get("key")), Arrays.asList(tag, last));
            }
            row.put("tests", ttm);
            entries.add(row);
            int keep = Math.max(1, Config.historyKeep());
            while (entries.size() > keep) entries.remove(0);
            try {
                Files.write(file, Json.write(entries).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // history is best-effort — never fail the report because of it
            }
            return new History(entries);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cast(Map<?, ?> in) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : in.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
    }
}
