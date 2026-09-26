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
 */
public class ReportingLabsListener implements ITestListener {

    static { ShutdownWriter.install(); }

    @Override
    public void onTestStart(ITestResult tr) {
        Class<?> cls = tr.getTestClass().getRealClass();
        Method m = tr.getMethod().getConstructorOrMethod().getMethod();
        String title = displayTitle(tr, m);
        String file = cls.getSimpleName() + ".java";
        List<String> path = classPath(cls);
        RlInternal.begin(title, file, 0, "testng", path);
        applyAnnotations(cls, m, tr);
    }

    @Override public void onTestSuccess(ITestResult tr) { RlInternal.end(null, false); }
    @Override public void onTestFailure(ITestResult tr) { RlInternal.end(tr.getThrowable(), false); }
    @Override public void onTestSkipped(ITestResult tr) { RlInternal.end(null, true); }
    @Override public void onTestFailedButWithinSuccessPercentage(ITestResult tr) { RlInternal.end(tr.getThrowable(), false); }

    // ---- helpers ----

    private static String displayTitle(ITestResult tr, Method m) {
        String d = tr.getMethod().getDescription();
        return (d != null && !d.isEmpty()) ? d : m.getName();
    }

    private static List<String> classPath(Class<?> cls) {
        List<String> out = new ArrayList<>();
        String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
        if (!pkg.isEmpty()) out.add(pkg);
        out.add(cls.getSimpleName());
        return out;
    }

    private static void applyAnnotations(Class<?> cls, Method m, ITestResult tr) {
        apply(cls);
        apply(m);
        // TestNG groups → tags on the current slot
        for (String g : tr.getMethod().getGroups()) RlInternal.tag(g);
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
        Team tm    = el.getAnnotation(Team.class);       if (tm != null) RlInternal.meta("team",     tm.value());
        Meta[] metas = el.getAnnotationsByType(Meta.class);
        for (Meta mt : metas) RlInternal.meta(mt.key(), mt.value());
    }
}
