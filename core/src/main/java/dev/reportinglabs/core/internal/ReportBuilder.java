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
    private static final int HISTORY_KEEP = 30;

    private ReportBuilder() {}

    public static Map<String, Object> build(List<RlInternal.TestSlot> tests, long suiteStart) {
        long now = System.currentTimeMillis();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title",       Config.title());
        data.put("generatedAt", now);
        data.put("startTime",   suiteStart);
        data.put("duration",    Math.max(0, now - suiteStart));
        data.put("metadata",    Config.metadata());
        data.put("projects",    Config.projects());
        data.put("workers",     Config.workers());

        List<Map<String, Object>> testList = new ArrayList<>(tests.size());
        int i = 0;
        int passed = 0, failed = 0, flaky = 0, skipped = 0;
        for (RlInternal.TestSlot t : tests) {
            testList.add(toTestMap(t, i++));
            switch (t.outcome) {
                case "passed":  passed++;  break;
                case "failed":  failed++;  break;
                case "flaky":   flaky++;   break;
                case "skipped": skipped++; break;
                default: break;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total",       tests.size());
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

        data.put("bdd", false);
        data.put("rootDir", System.getProperty("user.dir", ""));
        data.put("env", envRows());
        data.put("runStatus", failed > 0 ? "failed" : "passed");
        data.put("globalErrors", Collections.emptyList());
        data.put("globalOutput", Collections.emptyList());

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("theme", Config.theme());
        options.put("palette", Config.palette());
        options.put("embedFonts", true);
        options.put("sections", Collections.emptyList());
        Map<String, Object> widgets = new LinkedHashMap<>();
        for (String w : new String[]{"overviewCards","breakdown","needsAttention","failureClusters","trend","slowest","env"}) {
            widgets.put(w, true);
        }
        options.put("widgets", widgets);
        options.put("dimensions", Arrays.asList("priority","severity","owner","feature"));
        options.put("dimensionOrder", Collections.emptyMap());
        Map<String, Object> project = Config.project();
        if (project != null) options.put("project", project);
        options.put("links", Config.links());
        options.put("customCss", "");
        options.put("editorLinks", false);
        data.put("options", options);

        return data;
    }

    private static Map<String, Object> toTestMap(RlInternal.TestSlot t, int idx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "t" + idx);
        m.put("key", t.key);
        m.put("title", t.title);
        m.put("path", t.path);
        m.put("file", t.file);
        m.put("line", t.line);
        m.put("project", t.projectName);
        m.put("tags", t.tags);
        m.put("annotations", Collections.emptyList());
        m.put("meta", t.meta);
        m.put("outcome", t.outcome);
        m.put("duration", t.duration);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logs", t.logs);
        result.put("data", t.dataBlocks);
        result.put("api", t.apiCalls);
        result.put("retry", 0);
        result.put("status", t.outcome);
        result.put("duration", t.duration);
        result.put("startTime", t.startTime);
        result.put("workerIndex", 0);
        List<Map<String, Object>> errors = new ArrayList<>();
        if (t.errorMessage != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("message", t.errorMessage == null ? "" : t.errorMessage);
            e.put("stack",   t.errorStack   == null ? "" : t.errorStack);
            errors.add(e);
        }
        result.put("errors", errors);
        result.put("steps", Collections.emptyList());
        result.put("attachments", t.attachments);
        result.put("stdout", Collections.emptyList());
        result.put("stderr", Collections.emptyList());
        m.put("results", Collections.singletonList(result));
        return m;
    }

    private static List<Map<String, Object>> envRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        addEnv(rows, "Java",     System.getProperty("java.version"));
        addEnv(rows, "Runtime",  System.getProperty("java.runtime.name"));
        addEnv(rows, "OS",       System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        addEnv(rows, "User",     System.getProperty("user.name"));
        return rows;
    }
    private static void addEnv(List<Map<String, Object>> rows, String k, String v) {
        if (v == null || v.isEmpty()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("k", k);
        r.put("v", v);
        rows.add(r);
    }

    // ---- history ----

    static final class History {
        final List<Map<String, Object>> entries;
        History(List<Map<String, Object>> entries) { this.entries = entries; }

        static History readAndAppend(long now, long start, List<Map<String, Object>> tests, Map<String, Object> stats) {
            Path file = Paths.get(System.getProperty("user.dir", "."), "reporting-labs.history.json");
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
            row.put("label",    (String) Config.metadata().getOrDefault("build", "run-" + now));
            row.put("time",     now);
            row.put("duration", Math.max(0, now - start));
            row.put("total",   ((Number) stats.get("total")).intValue());
            row.put("passed",  ((Number) stats.get("passed")).intValue());
            row.put("failed",  ((Number) stats.get("failed")).intValue());
            row.put("flaky",   ((Number) stats.get("flaky")).intValue());
            row.put("skipped", ((Number) stats.get("skipped")).intValue());
            // per-test outcome map, keyed by test.key, values are single-char arrays: 'p'|'f'|'k'|'s'
            Map<String, List<String>> ttm = new LinkedHashMap<>();
            for (Map<String, Object> t : tests) {
                String o = String.valueOf(t.get("outcome"));
                String tag = "p";
                switch (o) {
                    case "failed":  tag = "f"; break;
                    case "flaky":   tag = "k"; break;
                    case "skipped": tag = "s"; break;
                    default: tag = "p";
                }
                ttm.put(String.valueOf(t.get("key")), Collections.singletonList(tag));
            }
            row.put("tests", ttm);
            entries.add(row);
            while (entries.size() > HISTORY_KEEP) entries.remove(0);
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
