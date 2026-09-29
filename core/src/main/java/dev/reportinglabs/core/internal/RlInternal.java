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
        String key;                   // same as id for now; keeps the JS field name
        String title;
        String file;
        int line;
        final String projectName;
        List<String> path;            // describe blocks, class chain, package tail
        final Map<String, String> meta = new LinkedHashMap<>();
        final List<String> tags = new ArrayList<>();
        final List<Map<String, Object>> logs = new ArrayList<>();
        final List<Map<String, Object>> dataBlocks = new ArrayList<>();
        final List<Map<String, Object>> apiCalls = new ArrayList<>();
        final List<Map<String, Object>> attachments = new ArrayList<>();
        /** Attachments an integration added on its own (auto screenshot). A
         *  later user attachment with the same name replaces it instead of
         *  showing up as a twin. */
        final Set<Map<String, Object>> autoAttachments = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Step tree: Rl.step() frames, framework hooks, driver actions. */
        final List<Map<String, Object>> steps = new ArrayList<>();
        final Deque<Map<String, Object>> openSteps = new ArrayDeque<>();
        Map<String, Object> beforeHooks, afterHooks;   // "Before Hooks" / "After Hooks" groups
        final List<String> stdout = new ArrayList<>();
        final List<String> stderr = new ArrayList<>();

        /** True when an attachment with this name was already added to the
         *  test — lets integrations skip a screenshot the user took themselves. */
        public boolean hasAttachment(String name) {
            for (Map<String, Object> a : attachments) if (name != null && name.equals(a.get("name"))) return true;
            return false;
        }
        /** Per-test finish hooks — e.g. RlPlaywright uses this to screenshot on failure. */
        final List<java.util.function.Consumer<Throwable>> onEnd = new ArrayList<>();
        final long startTime;
        String outcome = "passed";    // 'passed' | 'failed' | 'flaky' | 'skipped'
        long duration = 0;
        String errorMessage;
        String errorStack;
        String errorSnippet;                 // code around the failing line, when the source is found
        Map<String, Object> errorLocation;   // { file, line, column }
        Map<String, Object> errorLocationHint; // set by a plugin that knows better than the stack (feature file line)
        String errorSnippetHint;
        Map<String, Object> explain;         // plain-language reading of the failure
        String skipReason;            // SkipException / @Disabled / assumption message
        /** The test class and method, for source lookups. */
        Class<?> testClass;
        String testMethod;
        /** Index of the worker thread that ran this test, first-seen order. */
        int workerIndex;
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
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\u001b\\[[0-9;]*[A-Za-z]");
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
    /** Thread id -> worker index, so the Timeline can show one lane per thread. */
    private static final Map<Long, Integer> WORKER_INDEX = new ConcurrentHashMap<>();
    /** Errors outside any test: a @BeforeSuite / @BeforeClass that failed. */
    private static final List<Map<String, Object>> GLOBAL_ERRORS = Collections.synchronizedList(new ArrayList<>());
    /** Work to do right before the report is built (attach videos, …). */
    private static final List<Runnable> BEFORE_WRITE = Collections.synchronizedList(new ArrayList<>());
    private static volatile java.io.File OUTPUT_DIR;

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
    /** Re-describe the running test: a Cucumber scenario runs inside a
     *  generic TestNG method ("Runs Cucumber Scenarios"); once the plugin
     *  knows the scenario it points the row at the feature file instead. */
    public static void relocate(String title, String file, int line, List<String> path) {
        TestSlot s = CURRENT.get();
        if (s == null) return;
        if (title != null && !title.isEmpty()) s.title = title;
        if (file != null && !file.isEmpty()) s.file = file;
        if (line > 0) s.line = line;
        if (path != null) s.path = new ArrayList<>(path);
        s.key = idOf(s.projectName, s.file, s.line, s.title);
        s.testClass = null; s.testMethod = null;
    }

    /** Where the failure is, when a plugin knows better than the stack
     *  trace: a Cucumber undefined step has no user frame at all, but the
     *  feature file line is exactly what the reader wants. Wins over the
     *  stack-derived location when set. */
    public static void errorAt(String file, int line, String snippet) {
        TestSlot s = CURRENT.get();
        if (s == null || file == null || line <= 0) return;
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("file", file); loc.put("line", line); loc.put("column", 0);
        s.errorLocationHint = loc;
        s.errorSnippetHint = snippet;
    }

    /** Closes a step the runner never executed (a Cucumber step after a
     *  failed one). Shown greyed out as "not run". */
    public static void stepSkip(Step step) {
        if (step == null) return;
        step.data.put("duration", 0L);
        step.data.put("status", "skipped");
        TestSlot s = currentOrLast();
        if (s != null && s.openSteps.peek() == step.data) s.openSteps.pop();
    }

    /** Drop a pinned data block by name (e.g. framework parameters that a
     *  plugin replaces with something more readable). */
    public static void removeData(String name) {
        TestSlot s = CURRENT.get();
        if (s == null || name == null) return;
        s.dataBlocks.removeIf(b -> name.equals(b.get("name")));
    }

    public static void tag(String tag) {
        TestSlot s = CURRENT.get();
        if (s != null && tag != null && !tag.isEmpty()) s.tags.add(tag);
    }

    /** Called by a framework listener when a test starts. Every invocation
     *  gets a unique id so that data-driven tests, retries and re-runs of
     *  the same method don't collapse into one row. `key` stays stable
     *  across invocations so the history matcher can still line up runs. */
    /** Begin a test whose source location is resolved from the class and
     *  method: the report then shows Foo.java:42 and a code snippet on failure. */
    public static TestSlot begin(String title, Class<?> cls, String method, String projectName, List<String> path) {
        String file = cls != null ? SourceLocator.relativeFile(cls) : "unknown";
        int line = SourceLocator.lineOf(cls, method);
        TestSlot s = begin(title, file, line, projectName, path);
        s.testClass = cls;
        s.testMethod = method;
        return s;
    }

    public static TestSlot begin(String title, String file, int line, String projectName, List<String> path) {
        String key = idOf(projectName, file, line, title);
        String id  = key + "#" + SEQ.incrementAndGet();
        TestSlot slot = new TestSlot(id, key, title, file, line, projectName, path);
        CURRENT.set(slot);
        LAST_ENDED.remove();
        long tid = Thread.currentThread().getId();
        WORKER_THREADS.add(tid);
        slot.workerIndex = WORKER_INDEX.computeIfAbsent(tid, k -> WORKER_INDEX.size());
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

    // ---- add-on integrations (ServiceLoader) ----

    private static final List<dev.reportinglabs.core.spi.RlIntegration> INTEGRATIONS =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean integrationsLoaded = false;

    /** Loads every add-on on the classpath once. Called by ShutdownWriter
     *  when the first framework binding initialises. */
    public static void loadIntegrations() {
        if (integrationsLoaded) return;
        synchronized (RlInternal.class) {
            if (integrationsLoaded) return;
            integrationsLoaded = true;
            try {
                ClassLoader cl = dev.reportinglabs.core.spi.RlIntegration.class.getClassLoader();
                for (dev.reportinglabs.core.spi.RlIntegration i :
                        ServiceLoader.load(dev.reportinglabs.core.spi.RlIntegration.class, cl)) {
                    INTEGRATIONS.add(i);
                    END_LISTENERS.add(slot -> i.onTestEnd());
                }
            } catch (Throwable ignore) { /* an add-on that fails to load is simply absent */ }
        }
    }

    /** Called by the framework bindings with the test class instance — on
     *  test start and after every lifecycle method — so add-ons can find
     *  what the setup created (e.g. a WebDriver field). */
    public static void testInstance(Object instance) {
        if (instance == null || INTEGRATIONS.isEmpty()) return;
        for (dev.reportinglabs.core.spi.RlIntegration i : INTEGRATIONS) {
            try { i.onTestInstance(instance); } catch (Throwable ignore) {}
        }
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
        s.put("title", title == null ? "" : MASKER.maskText(title));
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
        return MASKER.maskText(head);
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
        // Colour codes from pretty printers (Cucumber, logback) are noise in HTML.
        line = ANSI.matcher(line).replaceAll("");
        // The template joins chunks with '' and splits on '\n' (Node stores
        // raw console chunks), so each stored line keeps its newline.
        String text = MASKER.maskText(line.length() > 2000 ? line.substring(0, 2000) + "…" : line) + "\n";
        TestSlot s = CURRENT.get();
        if (s == null && OPEN_HOOK.get() != null && OPEN_HOOK_BEFORE.get()) {
            // Printed from a @BeforeClass/@BeforeTest that precedes the next
            // test: it belongs to that test, not to the one that just ended.
            (err ? PENDING_STDERR : PENDING_STDOUT).get().add(text);
            return;
        }
        // After a test ended, only lines printed by its own after-hooks belong
        // to it; the framework's own chatter (TestNG's "PASSED: …" summary)
        // printed later on this thread does not.
        if (s == null && OPEN_HOOK.get() != null) s = LAST_ENDED.get();
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
        end(failure, skipped, skipReason, null);
    }

    /** A test that never ran because a before-hook failed: reported as
     *  failed with the hook's error, the way the Node reporter treats a
     *  beforeEach failure. */
    public static void endFailedByHook(Throwable hookFailure, String hookTitle) {
        end(hookFailure, false, null, hookTitle);
    }

    private static void end(Throwable failure, boolean skipped, String skipReason, String failedHook) {
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
            String shown = ErrorExplainer.displayMessage(failure);
            if (failedHook != null) shown = failedHook + " failed: " + shown;
            slot.errorMessage = MASKER.maskText(shown);
            try {
                StackTraceElement frame = SourceLocator.frameIn(failure, slot.testClass);
                Class<?> where = null; int line = 0; String sourceLine = null;
                if (frame != null && frame.getLineNumber() > 0) {
                    where = slot.testClass != null && frame.getClassName().equals(slot.testClass.getName()) ? slot.testClass : SourceLocator.classOf(frame);
                    line = frame.getLineNumber();
                } else if (slot.testClass != null && slot.testMethod != null) {
                    // No frame in the test (a TestNG time-out kills it from another thread): point at the method.
                    where = slot.testClass; line = SourceLocator.lineOf(where, slot.testMethod);
                }
                if (slot.errorLocationHint != null) {
                    slot.errorLocation = slot.errorLocationHint;
                    if (slot.errorSnippetHint != null) slot.errorSnippet = MASKER.maskText(slot.errorSnippetHint);
                } else if (where != null && line > 0) {
                    Map<String, Object> loc = new LinkedHashMap<>();
                    loc.put("file", SourceLocator.relativeFile(where));
                    loc.put("line", line);
                    loc.put("column", 0);
                    slot.errorLocation = loc;
                    String snip = SourceLocator.snippet(where, line);
                    if (snip != null) slot.errorSnippet = MASKER.maskText(snip);
                    sourceLine = SourceLocator.line(where, line);
                }
                Map<String, Object> ex = ErrorExplainer.explain(failure, SourceLocator.playwrightAction(failure, slot.testClass), SourceLocator.locatorIn(sourceLine));
                if (ex != null) {
                    if (failedHook != null) ex.put("summary", "The " + failedHook.split(" ")[0] + " hook failed, so the test never ran. " + ex.get("summary"));
                    for (String k : new String[] { "summary", "hint", "locator" }) if (ex.get(k) != null) ex.put(k, MASKER.maskText(String.valueOf(ex.get(k))));
                    slot.explain = ex;
                }
            } catch (Throwable ignore) { /* explain is decoration, never a failure */ }
            StringBuilder sb = new StringBuilder(1024);
            for (Throwable t = failure; t != null; t = t.getCause()) {
                sb.append(t.getClass().getName()).append(": ").append(ErrorExplainer.unwrap(t.getMessage())).append('\n');
                for (StackTraceElement el : t.getStackTrace()) {
                    sb.append("\tat ").append(el).append('\n');
                    if (sb.length() > 8000) break;
                }
                if (t.getCause() != null && t.getCause() != t) sb.append("Caused by:\n");
                if (sb.length() > 8000) break;
            }
            slot.errorStack = MASKER.maskText(sb.toString());
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
        row.put("msg", message == null ? "" : MASKER.maskText(message));
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
        } else if (masked instanceof List && !((List<?>) masked).isEmpty() && ((List<?>) masked).stream().allMatch(x -> x instanceof Map)) {
            // Rows from JSON / Excel / DB: one table, columns in first-seen order (same as the Node reporter).
            List<String> columns = new ArrayList<>();
            for (Object o : (List<?>) masked) for (Object k : ((Map<?, ?>) o).keySet()) if (!columns.contains(String.valueOf(k))) columns.add(String.valueOf(k));
            List<List<String>> rows = new ArrayList<>();
            for (Object o : (List<?>) masked) { List<String> r = new ArrayList<>(); for (String c : columns) r.add(stringify(((Map<?, ?>) o).get(c))); rows.add(r); }
            block.put("kind", "table"); block.put("columns", columns); block.put("rows", rows);
        } else if (masked instanceof CharSequence && looksLikeCsv(masked.toString())) {
            String[] lines = masked.toString().replace("\r", "").trim().split("\n");
            List<String> columns = splitCsv(lines[0]);
            List<List<String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                List<String> cells = splitCsv(lines[i]);
                for (int c = 0; c < cells.size(); c++) if (c < columns.size() && MASKER.sensitive(columns.get(c))) cells.set(c, "****");
                rows.add(cells);
            }
            block.put("kind", "table"); block.put("columns", columns); block.put("rows", rows);
        } else {
            block.put("kind", "text");
            block.put("text", stringify(masked));
        }
        s.dataBlocks.add(block);
    }

    private static boolean looksLikeCsv(String s) {
        String t = s.trim();
        if (!t.contains("\n") || t.startsWith("{") || t.startsWith("[")) return false;
        String[] lines = t.split("\n");
        return lines.length >= 2 && lines[0].contains(",") && lines[1].contains(",");
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder(); boolean q = false;
        for (char c : line.toCharArray()) {
            if (c == '"') q = !q;
            else if (c == ',' && !q) { out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
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
        // Bodies are text (JSON, form, XML): secret-looking fields inside them are masked too.
        if (reqBody  != null) row.put("requestBody",  MASKER.maskText(reqBody));
        if (respBody != null) row.put("responseBody", MASKER.maskText(respBody));
        s.apiCalls.add(row);
    }

    /** Attaches a binary blob (screenshot, trace, video, whatever). Small
     *  attachments are inlined as data URIs at write time; larger ones can
     *  be written as sibling files in a future revision. */
    public static void attach(String name, String contentType, byte[] bytes) {
        attach(name, contentType, bytes, false);
    }

    /** Like {@link #attach} but marks the attachment as integration-made:
     *  a user attachment with the same name replaces it. If the user already
     *  attached one with that name, this is a no-op. */
    public static void attachAuto(String name, String contentType, byte[] bytes) {
        TestSlot s = currentOrLast();
        if (s != null && s.hasAttachment(name)) return;
        attach(name, contentType, bytes, true);
    }

    private static void attach(String name, String contentType, byte[] bytes, boolean auto) {
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
        if (auto) {
            s.attachments.add(a);
            s.autoAttachments.add(a);
            return;
        }
        for (int i = 0; i < s.attachments.size(); i++) {
            Map<String, Object> old = s.attachments.get(i);
            if (s.autoAttachments.contains(old) && a.get("name").equals(old.get("name"))) {
                s.attachments.set(i, a);          // user's own screenshot wins
                s.autoAttachments.remove(old);
                return;
            }
        }
        s.attachments.add(a);
    }

    // ---- report build ----

    /** Builds the shared ReportData map and writes an HTML file to the given
     *  path. Returns the file path written. */
    /** Record an error that belongs to no single test (a @BeforeSuite or
     *  @BeforeClass that failed). Shown at the top of the report. */
    public static void globalError(String context, Throwable t) {
        Map<String, Object> e = new LinkedHashMap<>();
        String msg = (context == null ? "" : context + ": ") + ErrorExplainer.displayMessage(t);
        e.put("message", MASKER.maskText(msg));
        if (t != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(t.getClass().getName()).append(": ").append(ErrorExplainer.unwrap(t.getMessage())).append('\n');
            for (StackTraceElement el : t.getStackTrace()) { sb.append("\tat ").append(el).append('\n'); if (sb.length() > 4000) break; }
            e.put("stack", MASKER.maskText(sb.toString()));
            Map<String, Object> ex = ErrorExplainer.explain(t);
            if (ex != null) e.put("explain", ex);
        }
        GLOBAL_ERRORS.add(e);
    }
    static List<Map<String, Object>> globalErrors() { return new ArrayList<>(GLOBAL_ERRORS); }

    /** Run something right before the report is built, when every test has
     *  finished (an add-on attaches files that only exist by then). */
    public static void beforeWrite(Runnable r) { if (r != null) BEFORE_WRITE.add(r); }

    /** The folder the report is being written to; valid inside beforeWrite. */
    public static java.io.File outputDir() { return OUTPUT_DIR; }

    /** Attach a file that lives next to the report (assets/…): the report
     *  links to it instead of inlining it. */
    public static void attachFile(TestSlot slot, String name, String contentType, String relativeSrc, long size) {
        if (slot == null) return;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", name);
        a.put("contentType", contentType);
        a.put("size", size);
        a.put("src", relativeSrc);
        slot.attachments.add(a);
    }

    public static String writeReport(String outputFolder) {
        if (outputFolder == null || outputFolder.isEmpty()) outputFolder = "reporting-labs";
        java.io.File dir = new java.io.File(outputFolder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("reporting-labs: could not create " + dir.getAbsolutePath());
        }
        OUTPUT_DIR = dir;
        for (Runnable r : new ArrayList<>(BEFORE_WRITE)) { try { r.run(); } catch (Throwable ignore) {} }
        Map<String, Object> data = ReportBuilder.build(new ArrayList<>(FINISHED.values()), SUITE_START);
        String html = TemplateRenderer.render(data);
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
