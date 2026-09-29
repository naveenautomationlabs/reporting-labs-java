package dev.reportinglabs.testng;

import dev.reportinglabs.core.annotations.*;
import dev.reportinglabs.core.internal.RlInternal;
import dev.reportinglabs.core.internal.ShutdownWriter;
import org.testng.*;

import java.lang.reflect.Method;
import java.util.*;

/**
 * TestNG listener that wires each test into the reportingLabs collector.
 * Auto-registered via ServiceLoader — no <listeners> block needed in
 * testng.xml. See META-INF/services/org.testng.ITestNGListener.
 *
 * Lifecycle: TestNG runs @BeforeMethod BEFORE it fires onTestStart, so the
 * test slot is opened from beforeConfiguration() — that way anything a
 * @BeforeMethod does (RlPlaywright.attach(page), Rl.log(...)) already has a
 * current test to land on. onTestStart then just reuses that slot.
 */
public class ReportingLabsListener implements ITestListener, IConfigurationListener, IInvokedMethodListener {

    static { ShutdownWriter.install(); }

    /** The test method whose slot was opened early by a @BeforeMethod. */
    private static final ThreadLocal<ITestNGMethod> PRESTARTED = new ThreadLocal<>();
    /** The configuration method currently running on this thread, as a hook step. */
    private static final ThreadLocal<RlInternal.Step> HOOK = new ThreadLocal<>();
    /** Failures of configuration methods, so a test TestNG skips because of
     *  one can be reported as failed with that error. */
    private static final Map<ITestNGMethod, Throwable> CONFIG_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onConfigurationFailure(ITestResult tr) {
        ITestNGMethod tm = tr.getMethod();
        Throwable t = tr.getThrowable();
        if (tm == null || t == null) return;
        CONFIG_FAILURES.put(tm, t);
        // Suite/test/class level: nothing else will show it, so it goes to the
        // report's global errors too.
        if (tm.isBeforeSuiteConfiguration() || tm.isBeforeTestConfiguration() || tm.isBeforeClassConfiguration()
            || tm.isAfterSuiteConfiguration() || tm.isAfterTestConfiguration() || tm.isAfterClassConfiguration()) {
            RlInternal.globalError(hookTitle(tm) + " in " + tm.getRealClass().getSimpleName(), t);
        }
    }

    // ---- configuration methods as "Before Hooks" / "After Hooks" steps ----

    @Override
    public void beforeInvocation(IInvokedMethod m, ITestResult tr) {
        if (!m.isConfigurationMethod()) return;
        ITestNGMethod tm = m.getTestMethod();
        boolean before = tm.isBeforeMethodConfiguration() || tm.isBeforeClassConfiguration()
            || tm.isBeforeTestConfiguration() || tm.isBeforeSuiteConfiguration() || tm.isBeforeGroupsConfiguration();
        HOOK.set(RlInternal.hookBegin(before, hookTitle(tm)));
    }

    @Override
    public void afterInvocation(IInvokedMethod m, ITestResult tr) {
        if (!m.isConfigurationMethod()) return;
        RlInternal.Step s = HOOK.get();
        HOOK.remove();
        if (s != null) RlInternal.hookEnd(s, tr.getThrowable());
        // A @Before* just ran — the driver it created is now discoverable.
        RlInternal.testInstance(tr.getInstance());
    }

    private static String hookTitle(ITestNGMethod tm) {
        String kind =
            tm.isBeforeSuiteConfiguration()  ? "@BeforeSuite"  : tm.isAfterSuiteConfiguration()  ? "@AfterSuite"  :
            tm.isBeforeTestConfiguration()   ? "@BeforeTest"   : tm.isAfterTestConfiguration()   ? "@AfterTest"   :
            tm.isBeforeClassConfiguration()  ? "@BeforeClass"  : tm.isAfterClassConfiguration()  ? "@AfterClass"  :
            tm.isBeforeGroupsConfiguration() ? "@BeforeGroups" : tm.isAfterGroupsConfiguration() ? "@AfterGroups" :
            tm.isBeforeMethodConfiguration() ? "@BeforeMethod" : tm.isAfterMethodConfiguration() ? "@AfterMethod" : "@Configuration";
        return kind + " " + tm.getMethodName();
    }

    @Override
    public void beforeConfiguration(ITestResult configResult, ITestNGMethod upcomingTest) {
        if (upcomingTest == null) return;
        if (!configResult.getMethod().isBeforeMethodConfiguration()) return;
        if (RlInternal.current() != null && PRESTARTED.get() == upcomingTest) return; // 2nd+ @BeforeMethod
        begin(upcomingTest);
        PRESTARTED.set(upcomingTest);
    }

    @Override
    public void onTestStart(ITestResult tr) {
        ITestNGMethod tm = tr.getMethod();
        boolean reuse = RlInternal.current() != null && PRESTARTED.get() == tm;
        PRESTARTED.remove();
        if (!reuse) begin(tm);
        for (String g : tm.getGroups()) RlInternal.tag(g);
        captureParameters(tr);
        RlInternal.testInstance(tr.getInstance());
    }

    private static void begin(ITestNGMethod tm) {
        Class<?> cls = tm.getRealClass();
        Method m = tm.getConstructorOrMethod().getMethod();
        RlInternal.begin(displayTitle(tm, m), cls, m.getName(), "testng", classPath(cls));
        apply(cls);
        apply(m);
    }

    /** Auto-capture data-provider parameters as a pinned data block on the
     *  current test. Turns a single test method run against N rows into N
     *  rows in the report, each showing exactly the parameters it ran with,
     *  without any Rl.testData() call from the test itself. */
    private static void captureParameters(ITestResult tr) {
        Object[] params = tr.getParameters();
        if (params == null || params.length == 0) return;
        Method m = tr.getMethod().getConstructorOrMethod().getMethod();
        java.lang.reflect.Parameter[] formal = m.getParameters();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i++) {
            String name = (i < formal.length && formal[i].isNamePresent())
                ? formal[i].getName()
                : "arg" + i;
            row.put(name, params[i] == null ? "null" : params[i].toString());
        }
        RlInternal.testData("Parameters", row);
    }

    @Override public void onTestSuccess(ITestResult tr) { RlInternal.end(null, false); }
    @Override public void onTestFailure(ITestResult tr) {
        RlInternal.TestSlot s = RlInternal.current();
        if (s != null && wasRetried(tr)) s.retried = true;
        RlInternal.end(tr.getThrowable(), false);
    }

    /** TestNG reports a failed attempt that an IRetryAnalyzer will retry as
     *  SKIPPED with wasRetried() == true. That is not a skip: it is attempt
     *  N of a test whose next attempt is about to run. Record it as a failed
     *  attempt so the report can group the attempts and mark the test flaky. */
    private static boolean endRetriedAttempt(ITestResult tr) {
        RlInternal.TestSlot s = RlInternal.current();
        if (s == null || !wasRetried(tr)) return false;
        s.retried = true;
        RlInternal.end(tr.getThrowable() != null ? tr.getThrowable() : new AssertionError("retried"), false);
        return true;
    }

    /** ITestResult.wasRetried() exists since TestNG 7.0; older versions just
     *  never group attempts. */
    private static boolean wasRetried(ITestResult tr) {
        try { return tr.wasRetried(); } catch (Throwable t) { return false; }
    }
    @Override public void onTestSkipped(ITestResult tr) {
        PRESTARTED.remove();
        // Skipped because a @Before* failed: TestNG never called onTestStart,
        // so open the row here and report it as failed with the hook's error.
        List<ITestNGMethod> causedBy = skipCausedBy(tr);
        if (RlInternal.current() == null) begin(tr.getMethod());
        if (!causedBy.isEmpty()) {
            ITestNGMethod cause = causedBy.get(0);
            if (cause.isTest()) {
                // dependsOnMethods / dependsOnGroups: the test it needs failed.
                // That is a skip with a reason, not a failure of this test.
                RlInternal.end(null, true, "depends on " + cause.getMethodName() + ", which failed");
                return;
            }
            Throwable t = CONFIG_FAILURES.get(cause);
            RlInternal.endFailedByHook(t != null ? t : new IllegalStateException("configuration method failed"), hookTitle(cause));
            return;
        }
        if (endRetriedAttempt(tr)) return;
        String reason = tr.getThrowable() != null ? null : dependsReason(tr.getMethod());
        if (reason != null) { RlInternal.end(null, true, reason); return; }
        RlInternal.end(tr.getThrowable(), true);
    }

    /** A skip without a throwable on a method with dependencies: the thing it
     *  depends on was itself skipped (a chain behind one failure). */
    private static String dependsReason(ITestNGMethod m) {
        try {
            String[] methods = m.getMethodsDependedUpon();
            String[] groups = m.getGroupsDependedUpon();
            List<String> names = new ArrayList<>();
            if (methods != null) for (String s : methods) names.add(s.substring(s.lastIndexOf('.') + 1));
            if (groups != null) for (String s : groups) names.add("group " + s);
            return names.isEmpty() ? null : "depends on " + String.join(", ", names) + ", which did not pass";
        } catch (Throwable t) { return null; }
    }

    /** ITestResult.getSkipCausedBy() exists since TestNG 7.0. */
    private static List<ITestNGMethod> skipCausedBy(ITestResult tr) {
        try { List<ITestNGMethod> l = tr.getSkipCausedBy(); return l == null ? Collections.emptyList() : l; }
        catch (Throwable t) { return Collections.emptyList(); }
    }
    @Override public void onTestFailedButWithinSuccessPercentage(ITestResult tr) { RlInternal.end(tr.getThrowable(), false); }

    // ---- helpers ----

    private static String displayTitle(ITestNGMethod tm, Method m) {
        String d = tm.getDescription();
        return (d != null && !d.isEmpty()) ? d : m.getName();
    }

    private static List<String> classPath(Class<?> cls) {
        List<String> out = new ArrayList<>();
        String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
        if (!pkg.isEmpty()) out.add(pkg);
        out.add(cls.getSimpleName());
        return out;
    }

    private static void apply(java.lang.reflect.AnnotatedElement el) {
        Priority p = el.getAnnotation(Priority.class);   if (p != null) RlInternal.meta("priority",  p.value());
        Severity s = el.getAnnotation(Severity.class);   if (s != null) RlInternal.meta("severity",  s.value());
        Owner o    = el.getAnnotation(Owner.class);      if (o != null) RlInternal.meta("owner",     o.value());
        Feature f  = el.getAnnotation(Feature.class);    if (f != null) RlInternal.meta("feature",   f.value());
        Story st   = el.getAnnotation(Story.class);      if (st != null) RlInternal.meta("story",    st.value());
        Epic e     = el.getAnnotation(Epic.class);       if (e != null) RlInternal.meta("epic",      e.value());
        Issue i    = el.getAnnotation(Issue.class);      if (i != null) RlInternal.meta("issue",     i.value());
        Component c= el.getAnnotation(Component.class);  if (c != null) RlInternal.meta("component", c.value());
        Team tm    = el.getAnnotation(Team.class);       if (tm != null) RlInternal.meta("team",      tm.value());
        Meta[] metas = el.getAnnotationsByType(Meta.class);
        for (Meta mt : metas) RlInternal.meta(mt.key(), mt.value());
    }
}
