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
public class ReportingLabsListener implements ITestListener, IConfigurationListener {

    static { ShutdownWriter.install(); }

    /** The test method whose slot was opened early by a @BeforeMethod. */
    private static final ThreadLocal<ITestNGMethod> PRESTARTED = new ThreadLocal<>();

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
    }

    private static void begin(ITestNGMethod tm) {
        Class<?> cls = tm.getRealClass();
        Method m = tm.getConstructorOrMethod().getMethod();
        RlInternal.begin(displayTitle(tm, m), cls.getSimpleName() + ".java", 0, "testng", classPath(cls));
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
    @Override public void onTestFailure(ITestResult tr) { RlInternal.end(tr.getThrowable(), false); }
    @Override public void onTestSkipped(ITestResult tr) { PRESTARTED.remove(); RlInternal.end(null, true); }
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
