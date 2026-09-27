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
        /** Step tree: Rl.step() frames, framework hooks, driver actions. */
        final List<Map<String, Object>> steps = new ArrayList<>();
        final Deque<Map<String, Object>> openSteps = new ArrayDeque<>();
        Map<String, Object> beforeHooks, afterHooks;   // "Before Hooks" / "After Hooks" groups
        final List<String> stdout = new ArrayList<>();
        final List<String> stderr = new ArrayList<>();
        /** Per-test finish hooks — e.g. RlPlaywright uses this to screenshot on failure. */
        final List<java.util.function.Consumer<Throwable>> onEnd = new ArrayList<>();
        final long startTime;
        String outcome = "passed";    // 'passed' | 'failed' | 'flaky' | 'skipped'
        long duration = 0;
        String errorMessage;
        String errorStack;
        String skipReason;            // SkipException / @Disabled / assumption message
        /** This failed invocation will be retried (TestNG IRetryAnalyzer):
         *  it becomes an earlier attempt of the next invocation with the
         *  same key instead of its own row. */
        public boolean retried;

        /** 'passed' | 'failed' | 'skipped' — final once end() runs; hooks
         *  registered via onEndCurrent() see the final value. */
        public String outcome() { return outcome; }

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
    // Frameworks fire their "test finished" listener BEFORE @AfterMethod /
    // @AfterEach run, so a screenshot attached from an after-hook arrives once
    // CURRENT is already cleared. Remember the slot that just ended on this
    // thread so those late writes still land on the right test.
    private static final ThreadLocal<TestSlot> LAST_ENDED = new ThreadLocal<>();
    /** Hook steps that started before any test slot existed on this thread
     *  (@BeforeSuite/@BeforeTest/@BeforeClass, JUnit @BeforeAll) — they
     *  become the first test's "Before Hooks". */
    private static final ThreadLocal<List<Map<String, Object>>> PENDING_BEFORE_HOOKS =
        ThreadLocal.withInitial(ArrayList::new);
    /** Listeners told about every test end, after the outcome is settled —
     *  add-ons (RlSelenium) use this for per-test capture without needing a
     *  per-test attach() call. */
    private static final List<java.util.function.Consumer<TestSlot>> END_LISTENERS =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<String, TestSlot> FINISHED = new ConcurrentHashMap<>();
    private static final AtomicInteger IDX = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    private static final long SUITE_START = System.currentTimeMillis();
    private static final Masker MASKER = new Masker(Config.maskKeys());

    /** Every thread that runs at least one test lands here. The unique
     *  count is the real number of concurrent workers this suite used,
     *  regardless of whether the parallelism was configured in Surefire,
     *  in testng.xml or in Gradle's test task. */
    private static final Set<Long> WORKER_THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static int workerThreadCount() { return Math.max(1, WORKER_THREADS.size()); }

    public static TestSlot current() { return CURRENT.get(); }
    public static Masker masker()    { return MASKER; }

    /** The running test, or — from an after-hook — the test that just ended
     *  on this thread. Null only when no test has run on this thread yet or
     *  the next one has already begun. */
    public static TestSlot currentOrLast() {
        TestSlot s = CURRENT.get();
        return s != null ? s : LAST_ENDED.get();
    }

    /** True when the current-or-last test on this thread ended as failed.
     *  Backs the no-arg Rl.shouldCapture*() helpers used from after-hooks. */
    public static boolean currentOrLastFailed() {
        TestSlot s = currentOrLast();
        return s != null && "failed".equals(s.outcome);
    }

    /** True when the current-or-last test on this thread was skipped — a
     *  skipped test never gets capture artifacts, whatever the policy. */
    public static boolean currentOrLastSkipped() {
        TestSlot s = currentOrLast();
        return s != null && "skipped".equals(s.outcome);
    }

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
        LAST_ENDED.remove();
        WORKER_THREADS.add(Thread.currentThread().getId());
        List<Map<String, Object>> pending = PENDING_BEFORE_HOOKS.get();
        if (!pending.isEmpty()) {
            List<Map<String, Object>> kids = hookGroup(slot, true);
            for (Map<String, Object> h : pending) { kids.add(h); bumpGroup(slot.beforeHooks, h); }
            pending.clear();
        }
        slot.stdout.addAll(PENDING_STDOUT.get()); PENDING_STDOUT.get().clear();
        slot.stderr.addAll(PENDING_STDERR.get()); PENDING_STDERR.get().clear();
        return slot;
    }

    public static void addEndListener(java.util.function.Consumer<TestSlot> l) {
        if (l != null) END_LISTENERS.add(l);
    }

    // ---- steps & hooks ----

    /** An open step frame. Holds the mutable map that is already linked into
     *  the tree, so ending it in place is enough. */
    public static final class Step {
        final Map<String, Object> data;
        final long start = System.currentTimeMillis();
        Step(Map<String, Object> data) { this.data = data; }
    }

    private static Map<String, Object> newStep(String title, String category) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("title", title == null ? "" : title);
        s.put("category", category == null ? "test.step" : category);
        s.put("duration", 0L);
        s.put("steps", new ArrayList<Map<String, Object>>());
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> step) {
        return (List<Map<String, Object>>) step.get("steps");
    }

    /** Opens a step on the running (or just-ended) test, nested under any
     *  step that is still open. Outside a test the frame is timed but not
     *  recorded. */
    public static Step stepBegin(String title, String category) {
        Map<String, Object> m = newStep(title, category);
        TestSlot s = currentOrLast();
        Map<String, Object> hook = OPEN_HOOK.get();
        if (s != null) {
            Map<String, Object> parent = s.openSteps.peek();
            // Inside a hook (@BeforeMethod driver.get(...)) the action belongs
            // under that hook, not at the test's top level.
            List<Map<String, Object>> into = parent != null ? children(parent)
                : hook != null ? children(hook) : s.steps;
            into.add(m);
            s.openSteps.push(m);
        } else if (hook != null) {
            // @BeforeClass / @BeforeTest actions, before any test is open:
            // they ride along with the pending hook into the first test.
            children(hook).add(m);
        }
        return new Step(m);
    }

    /** The framework hook running on this thread, if any. */
    private static final ThreadLocal<Map<String, Object>> OPEN_HOOK = new ThreadLocal<>();

    public static void stepEnd(Step step, Throwable error) {
        if (step == null) return;
        step.data.put("duration", Math.max(0, System.currentTimeMillis() - step.start));
        if (error != null) step.data.put("error", errorSummary(error));
        TestSlot s = currentOrLast();
        if (s != null && s.openSteps.peek() == step.data) s.openSteps.pop();
    }

    /** Opens a framework hook step (@BeforeMethod, @AfterEach, …). Before-
     *  hooks go to the running test's "Before Hooks" group, or wait for the
     *  next test when none is open yet; after-hooks go to the test that just
     *  ended. */
    public static Step hookBegin(boolean before, String title) {
        Map<String, Object> m = newStep(title, "hook");
        TestSlot s = CURRENT.get();
        if (before) {
            if (s != null) hookGroup(s, true).add(m); else PENDING_BEFORE_HOOKS.get().add(m);
        } else {
            TestSlot target = s != null ? s : LAST_ENDED.get();
            if (target != null) hookGroup(target, false).add(m);
        }
        OPEN_HOOK.set(m);
        OPEN_HOOK_BEFORE.set(before);
        return new Step(m);
    }

    public static void hookEnd(Step step, Throwable error) {
        if (step == null) return;
        if (OPEN_HOOK.get() == step.data) OPEN_HOOK.remove();
        long d = Math.max(0, System.currentTimeMillis() - step.start);
        step.data.put("duration", d);
        if (error != null) step.data.put("error", errorSummary(error));
        TestSlot s = currentOrLast();
        if (s != null) {
            if (s.beforeHooks != null && children(s.beforeHooks).contains(step.data)) bumpGroup(s.beforeHooks, step.data);
            if (s.afterHooks  != null && children(s.afterHooks).contains(step.data))  bumpGroup(s.afterHooks,  step.data);
        }
    }

    private static List<Map<String, Object>> hookGroup(TestSlot s, boolean before) {
        if (before) {
            if (s.beforeHooks == null) { s.beforeHooks = newStep("Before Hooks", "hook"); s.steps.add(0, s.beforeHooks); }
            return children(s.beforeHooks);
        }
        if (s.afterHooks == null) { s.afterHooks = newStep("After Hooks", "hook"); s.steps.add(s.afterHooks); }
        return children(s.afterHooks);
    }

    private static void bumpGroup(Map<String, Object> group, Map<String, Object> child) {
        long g = ((Number) group.get("duration")).longValue();
        group.put("duration", g + ((Number) child.get("duration")).longValue());
        if (child.get("error") != null && group.get("error") == null) group.put("error", child.get("error"));
    }

    private static String errorSummary(Throwable t) {
        String msg = t.getMessage();
        String head = t.getClass().getSimpleName() + (msg == null ? "" : ": " + firstLine(msg, 300));
        return head;
    }

    static String firstLine(String s, int max) {
        if (s == null) return null;
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        line = line.trim();
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }

    // ---- console capture ----

    private static final int MAX_CONSOLE_LINES = 500;

    /** A line printed to System.out / System.err while a test is running (or
     *  just ended on this thread). Called by ConsoleCapture. */
    public static void console(boolean err, String line) {
        if (line == null || line.startsWith("[reporting-labs]")) return;
        // The template joins chunks with '' and splits on '\n' (Node stores
        // raw console chunks), so each stored line keeps its newline.
        String text = (line.length() > 2000 ? line.substring(0, 2000) + "…" : line) + "\n";
        TestSlot s = CURRENT.get();
        if (s == null && OPEN_HOOK.get() != null && OPEN_HOOK_BEFORE.get()) {
            // Printed from a @BeforeClass/@BeforeTest that precedes the next
            // test: it belongs to that test, not to the one that just ended.
            (err ? PENDING_STDERR : PENDING_STDOUT).get().add(text);
            return;
        }
        if (s == null) s = LAST_ENDED.get();
        if (s == null) return;
        List<String> target = err ? s.stderr : s.stdout;
        if (target.size() < MAX_CONSOLE_LINES) target.add(text);
    }

    private static final ThreadLocal<Boolean> OPEN_HOOK_BEFORE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<List<String>> PENDING_STDOUT = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<String>> PENDING_STDERR = ThreadLocal.withInitial(ArrayList::new);

    /** Register a callback invoked exactly once when the current test ends.
     *  Used by add-on modules (RlPlaywright) to attach a screenshot or a
     *  trace right before the slot is finalised. No-op outside a test. */
    public static void onEndCurrent(java.util.function.Consumer<Throwable> cb) {
        TestSlot s = CURRENT.get();
        if (s != null && cb != null) s.onEnd.add(cb);
    }

    /** Called by a framework listener when a test finishes. Pass null error
     *  for a pass, or the failure Throwable. For a skip, the throwable's
     *  message (SkipException, TestAbortedException) becomes the reason. */
    public static void end(Throwable failure, boolean skipped) {
        end(failure, skipped, skipped && failure != null ? failure.getMessage() : null);
    }

    public static void end(Throwable failure, boolean skipped, String skipReason) {
        TestSlot slot = CURRENT.get();
        if (slot == null) return;
        if (skipped && skipReason != null && !skipReason.isEmpty()) slot.skipReason = firstLine(skipReason, 200);
        slot.duration = Math.max(0, System.currentTimeMillis() - slot.startTime);
        // Close any step the test left open (an exception inside Rl.step()
        // is ended by Rl itself; this covers hooks that never returned).
        while (!slot.openSteps.isEmpty()) {
            Map<String, Object> open = slot.openSteps.pop();
            if (((Number) open.get("duration")).longValue() == 0) open.put("duration", slot.duration);
        }
        // Outcome is settled before the onEnd hooks run so they can read it
        // (a skipped test gets no screenshot/trace, whatever the policy).
        // A skip's throwable (SkipException) is a reason, not a failure —
        // hooks receive null for it.
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
        Throwable forHooks = skipped ? null : failure;
        for (java.util.function.Consumer<Throwable> cb : slot.onEnd) {
            try { cb.accept(forHooks); } catch (Throwable ignore) {}
        }
        for (java.util.function.Consumer<TestSlot> l : END_LISTENERS) {
            try { l.accept(slot); } catch (Throwable ignore) {}
        }
        // Unique id per invocation — every begin() call becomes its own row,
        // which is what data-driven tests and retries need. If a real flaky
        // detection layer is needed later, it belongs in ReportBuilder on
        // top of the raw invocations, not here.
        FINISHED.put(slot.id, slot);
        CURRENT.remove();
        LAST_ENDED.set(slot);
    }

    /** Called from Rl.meta at runtime. */
    public static void meta(String key, String value) {
        TestSlot s = currentOrLast();
        if (s != null && key != null) s.meta.put(key, value == null ? "" : value);
    }

    public static void log(String message) {
        TestSlot s = currentOrLast();
        if (s == null) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("t", System.currentTimeMillis());
        row.put("msg", message == null ? "" : message);
        s.logs.add(row);
    }

    /** Adds a pinned key-value block. Nested maps are recursively masked. */
    public static void testData(String name, Object value) {
        TestSlot s = currentOrLast();
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
        TestSlot s = currentOrLast();
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
        TestSlot s = currentOrLast();
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
