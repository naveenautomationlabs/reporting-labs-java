package dev.reportinglabs.junit5;

import dev.reportinglabs.core.annotations.*;
import dev.reportinglabs.core.internal.RlInternal;
import dev.reportinglabs.core.internal.ShutdownWriter;
import org.junit.jupiter.api.extension.*;

import java.lang.annotation.Annotation;
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
 * for tests whose @BeforeEach threw (afterTestExecution never runs then)
 * and flips a passed test to failed when an @AfterEach throws, which is
 * what JUnit itself reports.
 *
 * Dynamic tests (@TestFactory), classes disabled as a whole and classes
 * whose @BeforeAll failed are handled by {@link ReportingLabsPlatformListener},
 * which sees the JUnit Platform events the Jupiter extension API does not.
 */
public class ReportingLabsExtension
    implements BeforeEachCallback, BeforeTestExecutionCallback,
               AfterTestExecutionCallback, AfterEachCallback, TestWatcher, InvocationInterceptor {

    static { ShutdownWriter.install(); }

    /** The @BeforeEach / @AfterEach group running on this thread, as a hook step. */
    private static final ThreadLocal<RlInternal.Step> HOOK = new ThreadLocal<>();
    /** Set while the running test is an invocation of a retrying test
     *  template (junit-pioneer's @RetryingTest): a failed attempt is
     *  reported by the library as "aborted", the report wants it as a
     *  failed attempt of the one test so the row reads Flaky. */
    private static final ThreadLocal<Boolean> RETRYING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** Title of the @BeforeAll / @AfterAll that just threw on this thread. */
    static final ThreadLocal<String> FAILED_CLASS_HOOK = new ThreadLocal<>();

    @Override
    public void beforeEach(ExtensionContext ctx) {
        begin(ctx);
        String names = hookNames(ctx, org.junit.jupiter.api.BeforeEach.class);
        if (names != null) HOOK.set(RlInternal.hookBegin(true, "@BeforeEach " + names));
    }

    @Override
    public void beforeTestExecution(ExtensionContext ctx) {
        closeHook(null);
        // Registered without beforeEach having fired (unusual), or no test open.
        if (RlInternal.current() == null) begin(ctx);
        // @BeforeEach has run — whatever it created (a WebDriver) is discoverable.
        ctx.getTestInstance().ifPresent(RlInternal::testInstance);
    }

    @Override
    public void afterTestExecution(ExtensionContext ctx) {
        Method m = ctx.getTestMethod().orElse(null);
        // Meta-annotations count: Karate's @Karate.Test is a @TestFactory underneath.
        boolean factory = m != null && org.junit.platform.commons.support.AnnotationSupport.isAnnotated(m, org.junit.jupiter.api.TestFactory.class);
        if (factory && !ctx.getExecutionException().isPresent() && ReportingLabsPlatformListener.hadDynamicChildren(ctx.getUniqueId())) {
            // The dynamic tests are rows of their own; the factory itself is not one.
            RlInternal.discard();
        } else {
            end(ctx);
        }
        String names = hookNames(ctx, org.junit.jupiter.api.AfterEach.class);
        if (names != null) HOOK.set(RlInternal.hookBegin(false, "@AfterEach " + names));
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        Throwable t = ctx.getExecutionException().orElse(null);
        if (RlInternal.current() != null) {
            // Still open here means the test body never ran (@BeforeEach
            // failed, or aborted the test with an assumption).
            closeHook(t);
            if (t instanceof org.opentest4j.TestAbortedException) RlInternal.end(t, true);
            else if (t != null) RlInternal.endFailedByHook(t, "@BeforeEach " + firstHookName(ctx, org.junit.jupiter.api.BeforeEach.class));
            else end(ctx);
        } else {
            closeHook(t);
            // JUnit fails the test when an @AfterEach throws; the row follows.
            if (t != null && !(t instanceof org.opentest4j.TestAbortedException)) RlInternal.failLastEnded(t, "@AfterEach " + firstHookName(ctx, org.junit.jupiter.api.AfterEach.class));
        }
        RETRYING.set(Boolean.FALSE);
    }

    /** @BeforeAll / @AfterAll, one hook step each. A @BeforeAll runs before
     *  any test of the class is open, so its step waits for the first test
     *  on this thread (same as TestNG's @BeforeClass); an @AfterAll lands
     *  on the test that ended last. */
    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> ctx, ExtensionContext ext) throws Throwable {
        runClassHook(invocation, true, "@BeforeAll " + ctx.getExecutable().getName(), ext);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> ctx, ExtensionContext ext) throws Throwable {
        runClassHook(invocation, false, "@AfterAll " + ctx.getExecutable().getName(), ext);
    }

    private static void runClassHook(Invocation<Void> invocation, boolean before, String title, ExtensionContext ext) throws Throwable {
        RlInternal.Step s = RlInternal.hookBegin(before, title);
        try { invocation.proceed(); RlInternal.hookEnd(s, null); }
        catch (Throwable t) {
            RlInternal.hookEnd(s, t);
            FAILED_CLASS_HOOK.set(title);
            // An @AfterAll failure fails the class in JUnit's eyes but no
            // test of it: surface it at the top of the report.
            if (!before) RlInternal.globalError(title + " in " + ext.getTestClass().map(Class::getSimpleName).orElse("?"), t);
            throw t;
        }
    }

    private static void closeHook(Throwable error) {
        RlInternal.Step s = HOOK.get();
        HOOK.remove();
        if (s != null) RlInternal.hookEnd(s, error);
    }

    /** The lifecycle methods of the test class, its superclasses and (for a
     *  @Nested class) its enclosing classes carrying the annotation, in the
     *  order JUnit runs them; null when there are none. */
    private static String hookNames(ExtensionContext ctx, Class<? extends Annotation> ann) {
        List<String> names = hookMethodNames(ctx, ann);
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static String firstHookName(ExtensionContext ctx, Class<? extends Annotation> ann) {
        List<String> names = hookMethodNames(ctx, ann);
        return names.isEmpty() ? "hook" : names.get(0);
    }

    private static List<String> hookMethodNames(ExtensionContext ctx, Class<? extends Annotation> ann) {
        Class<?> cls = ctx.getTestClass().orElse(null);
        List<String> names = new ArrayList<>();
        // A @Nested class runs its enclosing classes' @BeforeEach / @AfterEach
        // too: outermost first for @BeforeEach, innermost first for @AfterEach.
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> c = cls; c != null; c = c.getEnclosingClass()) {
            if (java.lang.reflect.Modifier.isStatic(c.getModifiers()) && c != cls) break;
            chain.addFirst(c);
        }
        if (ann == org.junit.jupiter.api.AfterEach.class) { Deque<Class<?>> r = new ArrayDeque<>(); for (Class<?> c : chain) r.addFirst(c); chain = r; }
        for (Class<?> owner : chain) {
            // Subclass methods run after superclass ones for @BeforeEach and
            // before them for @AfterEach.
            List<Class<?>> hierarchy = new ArrayList<>();
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) hierarchy.add(c);
            if (ann == org.junit.jupiter.api.BeforeEach.class) Collections.reverse(hierarchy);
            for (Class<?> c : hierarchy) for (Method m : c.getDeclaredMethods()) if (m.isAnnotationPresent(ann)) names.add(m.getName());
        }
        return names;
    }

    /** Assumption failures (Assumptions.assumeTrue) abort the test — that is
     *  a skip with a reason, not a failure. A failed attempt of a retrying
     *  test is also reported as aborted by the retry library; the row wants
     *  it as a failed attempt. */
    private static void end(ExtensionContext ctx) {
        Throwable t = ctx.getExecutionException().orElse(null);
        boolean aborted = t instanceof org.opentest4j.TestAbortedException;
        if (aborted && RETRYING.get()) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            RlInternal.TestSlot s = RlInternal.current();
            if (s != null) s.retried = true;   // groups with the next attempt, same as a TestNG retry
            RlInternal.end(cause, false);
            return;
        }
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
        String title = ctx.getDisplayName();
        boolean retrying = m != null && hasAnnotationNamed(m, "RetryingTest");
        if (retrying) title = m.getName() + "()";   // one row per test, the attempts grouped under it
        RETRYING.set(retrying);
        // The source is where the method is declared: a @Test inherited from
        // a base class or a test interface points there. The row still keys
        // on the concrete class, so two subclasses running it are two rows.
        Class<?> source = m != null && cls != null && m.getDeclaringClass() != cls ? m.getDeclaringClass() : cls;
        RlInternal.begin(title, source, m != null ? m.getName() : null, "junit5", classPath(cls));
        if (source != cls && cls != null) RlInternal.keyQualifier(cls.getName());
        for (String tag : ctx.getTags()) RlInternal.tag(tag);
        applyAnnotations(cls, m);
    }

    static boolean hasAnnotationNamed(Method m, String simpleName) {
        for (Annotation a : m.getAnnotations()) if (a.annotationType().getSimpleName().equals(simpleName)) return true;
        return false;
    }

    /** Enclosing classes first (a @Nested class inherits its outer class's
     *  @Owner / @Feature), then the class, then the method. */
    static void applyAnnotations(Class<?> cls, Method m) {
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> c = cls; c != null; c = c.getEnclosingClass()) chain.push(c);
        for (Class<?> c : chain) {
            // A base class's @Owner / @Feature applies to every subclass;
            // the subclass's own annotations win.
            Deque<Class<?>> hierarchy = new ArrayDeque<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) hierarchy.push(k);
            for (Class<?> k : hierarchy) apply(k);
        }
        if (m != null) apply(m);
    }

    /** Package, then the class chain outermost first, each by its
     *  @DisplayName when it has one. */
    static List<String> classPath(Class<?> cls) {
        List<String> out = new ArrayList<>();
        if (cls == null) return out;
        String pkg = cls.getPackage() != null ? cls.getPackage().getName() : "";
        if (!pkg.isEmpty()) out.add(pkg);
        Deque<String> chain = new ArrayDeque<>();
        for (Class<?> c = cls; c != null; c = c.getEnclosingClass()) {
            org.junit.jupiter.api.DisplayName dn = c.getAnnotation(org.junit.jupiter.api.DisplayName.class);
            chain.addFirst(dn != null && !dn.value().isEmpty() ? dn.value() : c.getSimpleName());
        }
        out.addAll(chain);
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
