package dev.reportinglabs.junit5;

import dev.reportinglabs.core.annotations.*;
import dev.reportinglabs.core.internal.RlInternal;
import dev.reportinglabs.core.internal.ShutdownWriter;
import org.junit.jupiter.api.extension.*;

import java.lang.reflect.Method;
import java.util.*;

/**
 * JUnit 5 extension that wires each test into the reportingLabs collector.
 * Auto-registered via ServiceLoader (META-INF/services/org.junit.jupiter.api.extension.Extension)
 * — enable JUnit's autodetection with
 *   junit.jupiter.extensions.autodetection.enabled=true
 * in junit-platform.properties and it lights up.
 */
public class ReportingLabsExtension
    implements BeforeTestExecutionCallback, AfterTestExecutionCallback, TestWatcher {

    static { ShutdownWriter.install(); }

    @Override
    public void beforeTestExecution(ExtensionContext ctx) {
        Method m = ctx.getTestMethod().orElse(null);
        Class<?> cls = ctx.getTestClass().orElse(null);
        String title = ctx.getDisplayName();
        String file = cls != null ? cls.getSimpleName() + ".java" : "unknown";
        int line = 0;
        List<String> path = classPath(cls);
        RlInternal.begin(title, file, line, "junit5", path);
        applyAnnotations(cls, m);
    }

    @Override
    public void afterTestExecution(ExtensionContext ctx) {
        Throwable failure = ctx.getExecutionException().orElse(null);
        RlInternal.end(failure, false);
    }

    @Override public void testDisabled(ExtensionContext ctx, Optional<String> reason) {
        Class<?> cls = ctx.getTestClass().orElse(null);
        Method m = ctx.getTestMethod().orElse(null);
        RlInternal.begin(ctx.getDisplayName(), cls != null ? cls.getSimpleName() + ".java" : "unknown",
                         0, "junit5", classPath(cls));
        applyAnnotations(cls, m);
        RlInternal.end(null, true);
    }

    @Override public void testSuccessful(ExtensionContext ctx) { /* handled in afterTestExecution */ }
    @Override public void testAborted(ExtensionContext ctx, Throwable cause) { /* handled */ }
    @Override public void testFailed(ExtensionContext ctx, Throwable cause) { /* handled */ }

    // ---- helpers ----

    private static List<String> classPath(Class<?> cls) {
        List<String> out = new ArrayList<>();
        if (cls != null) {
            String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
            if (!pkg.isEmpty()) out.add(pkg);
            out.add(cls.getSimpleName());
        }
        return out;
    }

    private static void applyAnnotations(Class<?> cls, Method m) {
        // class-level defaults first, then method-level overrides
        if (cls != null) apply(cls);
        if (m != null)   apply(m);
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
