package dev.reportinglabs.core.internal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Internal collector shared by every framework binding (JUnit 5, TestNG, and
 * anything else). The framework tells this class when a test starts, when
 * it finishes, and whether it passed or failed. User code — via {@link
 * dev.reportinglabs.core.Rl} — pushes logs, data blocks, API calls
 * and attachments into the current test.
 *
 * Threading: one TestSlot per test, held in a ThreadLocal so parallel
 * workers do not step on each other. A concurrent registry holds finished
 * tests until the suite ends and {@link #writeReport(String)} runs.
 *
 * This class is public so framework modules can call it, but everything
 * here is for internal use — end-users touch only Rl.*.
 */
public final class RlInternal {
    private RlInternal() {}

    // ---- current test (ThreadLocal) ----

    public static final class TestSlot {
        final String id;              // stable id across processes; project|file|line|title
        final String key;             // same as id for now; keeps the JS field name
        final String title;
        final String file;
        final int line;
        final String projectName;
        final List<String> path;      // describe blocks, class chain, package tail
        final Map<String, String> meta = new LinkedHashMap<>();
        final List<String> tags = new ArrayList<>();
        final List<Map<String, Object>> logs = new ArrayList<>();
        final List<Map<String, Object>> dataBlocks = new ArrayList<>();
        final List<Map<String, Object>> apiCalls = new ArrayList<>();
        final List<Map<String, Object>> attachments = new ArrayList<>();
        /** Per-test finish hooks — e.g. RlPlaywright uses this to screenshot on failure. */
        final List<java.util.function.Consumer<Throwable>> onEnd = new ArrayList<>();
        final long startTime;
        String outcome = "passed";    // 'passed' | 'failed' | 'flaky' | 'skipped'
        long duration = 0;
        String errorMessage;
        String errorStack;

        TestSlot(String id, String key, String title, String file, int line, String projectName, List<String> path) {
            this.id = id;
            this.key = key;
            this.title = title;
            this.file = file;
            this.line = line;
            this.projectName = projectName == null ? "" : projectName;
            this.path = path == null ? new ArrayList<>() : new ArrayList<>(path);
            this.startTime = System.currentTimeMillis();
        }
    }

    private static final ThreadLocal<TestSlot> CURRENT = new ThreadLocal<>();
    private static final Map<String, TestSlot> FINISHED = new ConcurrentHashMap<>();
    private static final AtomicInteger IDX = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    private static final long SUITE_START = System.currentTimeMillis();
    private static final Masker MASKER = new Masker(Config.maskKeys());

    public static TestSlot current() { return CURRENT.get(); }
    public static Masker masker()    { return MASKER; }

    /** True if any test in this run finished as failed. Used by the
     *  auto-open logic to decide whether to open the report on `on-failure`. */
    public static boolean hasFailures() {
        for (TestSlot t : FINISHED.values()) {
            if ("failed".equals(t.outcome)) return true;
        }
        return false;
    }

    /** Add a tag to the current test (framework bindings use this for
     *  TestNG groups, JUnit tags, etc.). No-op outside a test. */
    public static void tag(String tag) {
        TestSlot s = CURRENT.get();
        if (s != null && tag != null && !tag.isEmpty()) s.tags.add(tag);
    }

    /** Called by a framework listener when a test starts. Every invocation
     *  gets a unique id so that data-driven tests, retries and re-runs of
     *  the same method don't collapse into one row. `key` stays stable
     *  across invocations so the history matcher can still line up runs. */
    public static TestSlot begin(String title, String file, int line, String projectName, List<String> path) {
        String key = idOf(projectName, file, line, title);
        String id  = key + "#" + SEQ.incrementAndGet();
        TestSlot slot = new TestSlot(id, key, title, file, line, projectName, path);
        CURRENT.set(slot);
        return slot;
    }

    /** Register a callback invoked exactly once when the current test ends.
     *  Used by add-on modules (RlPlaywright) to attach a screenshot or a
     *  trace right before the slot is finalised. No-op outside a test. */
    public static void onEndCurrent(java.util.function.Consumer<Throwable> cb) {
        TestSlot s = CURRENT.get();
        if (s != null && cb != null) s.onEnd.add(cb);
    }

    /** Called by a framework listener when a test finishes. Pass null error
     *  for a pass, or the failure Throwable. */
    public static void end(Throwable failure, boolean skipped) {
        TestSlot slot = CURRENT.get();
        if (slot == null) return;
        // Fire onEnd hooks first so attachments they add (screenshot, trace)
        // land in the slot before it's snapshotted.
        for (java.util.function.Consumer<Throwable> cb : slot.onEnd) {
            try { cb.accept(failure); } catch (Throwable ignore) {}
        }
        slot.duration = Math.max(0, System.currentTimeMillis() - slot.startTime);
        if (skipped) {
            slot.outcome = "skipped";
        } else if (failure != null) {
            slot.outcome = "failed";
            slot.errorMessage = String.valueOf(failure.getMessage());
            StringBuilder sb = new StringBuilder(1024);
            for (Throwable t = failure; t != null; t = t.getCause()) {
                sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
                for (StackTraceElement el : t.getStackTrace()) {
                    sb.append("\tat ").append(el).append('\n');
                    if (sb.length() > 8000) break;
                }
                if (t.getCause() != null && t.getCause() != t) sb.append("Caused by:\n");
                if (sb.length() > 8000) break;
            }
            slot.errorStack = sb.toString();
        }
        // Unique id per invocation — every begin() call becomes its own row,
        // which is what data-driven tests and retries need. If a real flaky
        // detection layer is needed later, it belongs in ReportBuilder on
        // top of the raw invocations, not here.
        FINISHED.put(slot.id, slot);
        CURRENT.remove();
    }

    /** Called from Rl.meta at runtime. */
    public static void meta(String key, String value) {
        TestSlot s = CURRENT.get();
        if (s != null && key != null) s.meta.put(key, value == null ? "" : value);
    }

    public static void log(String message) {
        TestSlot s = CURRENT.get();
        if (s == null) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("t", System.currentTimeMillis());
        row.put("msg", message == null ? "" : message);
        s.logs.add(row);
    }

    /** Adds a pinned key-value block. Nested maps are recursively masked. */
    public static void testData(String name, Object value) {
        TestSlot s = CURRENT.get();
        if (s == null) return;
        Object masked = MASKER.apply(value);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("name", name == null ? "Data" : name);
        if (masked instanceof Map) {
            List<Object[]> kv = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) masked).entrySet()) {
                kv.add(new Object[] { String.valueOf(e.getKey()), stringify(e.getValue()) });
            }
            block.put("kind", "kv");
            block.put("kv", kv);
        } else {
            block.put("kind", "text");
            block.put("text", stringify(masked));
        }
        s.dataBlocks.add(block);
    }

    private static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) return v.toString();
        return Json.write(v);
    }

    /** Records an API call. `duration` is the round-trip in ms. */
    public static void api(String method, String url, int status, long durationMs,
                           Map<String, String> reqHeaders, String reqBody,
                           Map<String, String> respHeaders, String respBody) {
        TestSlot s = CURRENT.get();
        if (s == null) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("method", method == null ? "GET" : method.toUpperCase(Locale.ROOT));
        row.put("url", url == null ? "" : url);
        row.put("status", status);
        row.put("duration", durationMs);
        row.put("requestHeaders",  MASKER.apply(reqHeaders  == null ? Collections.emptyMap() : reqHeaders));
        row.put("responseHeaders", MASKER.apply(respHeaders == null ? Collections.emptyMap() : respHeaders));
        if (reqBody  != null) row.put("requestBody",  reqBody);
        if (respBody != null) row.put("responseBody", respBody);
        s.apiCalls.add(row);
    }

    /** Attaches a binary blob (screenshot, trace, video, whatever). Small
     *  attachments are inlined as data URIs at write time; larger ones can
     *  be written as sibling files in a future revision. */
    public static void attach(String name, String contentType, byte[] bytes) {
        TestSlot s = CURRENT.get();
        if (s == null) return;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name == null ? "attachment" : name);
        a.put("contentType", contentType == null ? "application/octet-stream" : contentType);
        a.put("size", bytes == null ? 0 : bytes.length);
        if (bytes != null && bytes.length > 0) {
            // Small enough to inline as data URI; the JS side of the template does the same.
            a.put("src", "data:" + a.get("contentType") + ";base64," + Base64.getEncoder().encodeToString(bytes));
        }
        s.attachments.add(a);
    }

    // ---- report build ----

    /** Builds the shared ReportData map and writes an HTML file to the given
     *  path. Returns the file path written. */
    public static String writeReport(String outputFolder) {
        if (outputFolder == null || outputFolder.isEmpty()) outputFolder = "reporting-labs";
        Map<String, Object> data = ReportBuilder.build(new ArrayList<>(FINISHED.values()), SUITE_START);
        String html = TemplateRenderer.render(data);
        java.io.File dir = new java.io.File(outputFolder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("reporting-labs: could not create " + dir.getAbsolutePath());
        }
        java.io.File out = new java.io.File(dir, Config.outputFile());
        try (java.io.OutputStream fos = new java.io.FileOutputStream(out)) {
            fos.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("reporting-labs: could not write " + out.getAbsolutePath(), e);
        }
        return out.getAbsolutePath();
    }

    private static String idOf(String project, String file, int line, String title) {
        return (project == null ? "" : project) + "|" + (file == null ? "" : file) + "|" + line + "|" + (title == null ? "" : title);
    }
}
