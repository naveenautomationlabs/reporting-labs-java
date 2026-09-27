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
 *
 * Lifecycle: the slot opens in beforeEach(), which JUnit fires BEFORE the
 * user's @BeforeEach methods — so RlPlaywright.attach(page) or Rl.log(...)
 * inside a @BeforeEach already has a current test. It closes in
 * afterTestExecution() with the test's outcome; afterEach() is a safety net
 * for tests whose @BeforeEach threw (afterTestExecution never runs then).
 */
public class ReportingLabsExtension
    implements BeforeEachCallback, BeforeTestExecutionCallback,
               AfterTestExecutionCallback, AfterEachCallback, TestWatcher {

    static { ShutdownWriter.install(); }

    @Override
    public void beforeEach(ExtensionContext ctx) { begin(ctx); }

    @Override
    public void beforeTestExecution(ExtensionContext ctx) {
        // Registered without beforeEach having fired (unusual), or no test open.
        if (RlInternal.current() == null) begin(ctx);
    }

    @Override
    public void afterTestExecution(ExtensionContext ctx) {
        end(ctx);
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        // Still open here means the test body never ran (@BeforeEach failed).
        if (RlInternal.current() != null) end(ctx);
    }

    /** Assumption failures (Assumptions.assumeTrue) abort the test — that is
     *  a skip with a reason, not a failure. */
    private static void end(ExtensionContext ctx) {
        Throwable t = ctx.getExecutionException().orElse(null);
        boolean aborted = t instanceof org.opentest4j.TestAbortedException;
        RlInternal.end(t, aborted);
    }

    @Override public void testDisabled(ExtensionContext ctx, Optional<String> reason) {
        begin(ctx);
        RlInternal.end(null, true, reason.orElse(null));
    }

    @Override public void testSuccessful(ExtensionContext ctx) { /* handled in afterTestExecution */ }
    @Override public void testAborted(ExtensionContext ctx, Throwable cause) { /* handled */ }
    @Override public void testFailed(ExtensionContext ctx, Throwable cause) { /* handled */ }

    // ---- helpers ----

    private static void begin(ExtensionContext ctx) {
        Method m = ctx.getTestMethod().orElse(null);
        Class<?> cls = ctx.getTestClass().orElse(null);
        String file = cls != null ? cls.getSimpleName() + ".java" : "unknown";
        RlInternal.begin(ctx.getDisplayName(), file, 0, "junit5", classPath(cls));
        if (cls != null) apply(cls);   // class-level defaults first
        if (m != null)   apply(m);     // then method-level overrides
    }

    private static List<String> classPath(Class<?> cls) {
        List<String> out = new ArrayList<>();
        if (cls != null) {
            String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
            if (!pkg.isEmpty()) out.add(pkg);
            out.add(cls.getSimpleName());
        }
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
