package dev.reportinglabs.junit5;

import dev.reportinglabs.core.internal.RlInternal;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The part of JUnit 5 support that needs the Platform's view rather than
 * Jupiter's extension callbacks. Registered through ServiceLoader
 * (META-INF/services/org.junit.platform.launcher.TestExecutionListener),
 * which every launcher (Maven Surefire, Gradle, IDEs) honours.
 *
 *  - Dynamic tests from a @TestFactory: one row each, named after the
 *    dynamic test, under the factory in the tree; the factory itself is
 *    not a row unless it threw before producing any.
 *  - A class disabled as a whole (@Disabled on the class): one skipped row
 *    per test in it, with the reason.
 *  - A class whose @BeforeAll threw: one failed row per test in it, with
 *    the hook's error, the way the Node reporter treats a beforeAll failure.
 *  - TestReporter.publishEntry(...): log lines on the running test.
 */
public final class ReportingLabsPlatformListener implements TestExecutionListener {

    private volatile TestPlan plan;
    /** Ids of tests the Jupiter extension (or this listener) has seen start. */
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    /** Factories that produced at least one dynamic test row. */
    private static final Set<String> FACTORIES_WITH_CHILDREN = ConcurrentHashMap.newKeySet();
    /** Per running dynamic test: the factory row parked while it runs. */
    private final Map<String, RlInternal.TestSlot> parked = new ConcurrentHashMap<>();

    static boolean hadDynamicChildren(String factoryUniqueId) { return FACTORIES_WITH_CHILDREN.remove(factoryUniqueId); }

    @Override public void testPlanExecutionStarted(TestPlan testPlan) { this.plan = testPlan; }

    @Override
    public void executionStarted(TestIdentifier id) {
        started.add(id.getUniqueId());
        if (!isDynamicTest(id)) return;
        TestIdentifier factory = factoryOf(id);
        if (factory != null) FACTORIES_WITH_CHILDREN.add(factory.getUniqueId());
        RlInternal.TestSlot outer = RlInternal.suspend();
        if (outer != null) parked.put(id.getUniqueId(), outer);
        Method m = methodOf(factory != null ? factory : id);
        Class<?> cls = m != null ? m.getDeclaringClass() : null;
        List<String> path = ReportingLabsExtension.classPath(cls);
        // The factory and any dynamic containers between it and the test.
        List<String> between = new ArrayList<>();
        for (TestIdentifier p = parentOf(id); p != null && (isDynamicContainer(p) || isFactory(p)); p = parentOf(p)) between.add(0, p.getDisplayName());
        path.addAll(between);
        RlInternal.begin(id.getDisplayName(), cls, m != null ? m.getName() : null, "junit5", path);
        for (org.junit.platform.engine.TestTag t : id.getTags()) RlInternal.tag(t.getName());
        ReportingLabsExtension.applyAnnotations(cls, m);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        Throwable t = result.getThrowable().orElse(null);
        if (isDynamicTest(id)) {
            RlInternal.end(t, result.getStatus() == TestExecutionResult.Status.ABORTED);
            RlInternal.resume(parked.remove(id.getUniqueId()));
            return;
        }
        // A class whose @BeforeAll threw: its tests never started. Give each
        // one a failed row carrying the hook's error.
        if (id.isContainer() && result.getStatus() == TestExecutionResult.Status.FAILED && t != null && plan != null) {
            String hook = ReportingLabsExtension.FAILED_CLASS_HOOK.get();
            ReportingLabsExtension.FAILED_CLASS_HOOK.remove();
            if (hook == null || !hook.startsWith("@BeforeAll")) return;
            for (TestIdentifier test : plan.getDescendants(id)) {
                if (!test.isTest() || started.contains(test.getUniqueId())) continue;
                Method m = methodOf(test);
                Class<?> cls = classOf(test);
                RlInternal.begin(test.getDisplayName(), cls, m != null ? m.getName() : null, "junit5", ReportingLabsExtension.classPath(cls));
                for (org.junit.platform.engine.TestTag tag : test.getTags()) RlInternal.tag(tag.getName());
                ReportingLabsExtension.applyAnnotations(cls, m);
                RlInternal.endFailedByHook(t, hook);
                started.add(test.getUniqueId());
            }
        }
    }

    /** A class disabled as a whole: the Jupiter extension never hears about
     *  its methods, so they are recorded here, skipped with the reason. */
    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        if (!id.isContainer() || plan == null) return;
        for (TestIdentifier test : plan.getDescendants(id)) {
            if (!test.isTest() || started.contains(test.getUniqueId())) continue;
            Method m = methodOf(test);
            Class<?> cls = classOf(test);
            RlInternal.begin(test.getDisplayName(), cls, m != null ? m.getName() : null, "junit5", ReportingLabsExtension.classPath(cls));
            ReportingLabsExtension.applyAnnotations(cls, m);
            RlInternal.end(null, true, reason);
            started.add(test.getUniqueId());
        }
    }

    /** TestReporter.publishEntry: "key = value" log lines on the running test. */
    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        if (RlInternal.current() == null) return;
        for (Map.Entry<String, String> e : entry.getKeyValuePairs().entrySet()) {
            RlInternal.log("value".equals(e.getKey()) ? e.getValue() : e.getKey() + " = " + e.getValue());
        }
    }

    // ---- identifiers ----

    private static String lastSegmentType(TestIdentifier id) {
        try { return id.getUniqueIdObject().getLastSegment().getType(); } catch (Throwable t) { return ""; }
    }
    private static boolean isDynamicTest(TestIdentifier id)      { return id.isTest() && "dynamic-test".equals(lastSegmentType(id)); }
    private static boolean isDynamicContainer(TestIdentifier id) { return "dynamic-container".equals(lastSegmentType(id)); }
    private static boolean isFactory(TestIdentifier id)          { return "test-factory".equals(lastSegmentType(id)); }

    private TestIdentifier parentOf(TestIdentifier id) {
        return plan == null ? null : plan.getParent(id).orElse(null);
    }

    private TestIdentifier factoryOf(TestIdentifier id) {
        for (TestIdentifier p = parentOf(id); p != null; p = parentOf(p)) if (isFactory(p)) return p;
        return null;
    }

    private static Method methodOf(TestIdentifier id) {
        try {
            MethodSource ms = id.getSource().filter(s -> s instanceof MethodSource).map(s -> (MethodSource) s).orElse(null);
            if (ms == null) return null;
            Class<?> c = Class.forName(ms.getClassName(), false, Thread.currentThread().getContextClassLoader());
            for (Class<?> k = c; k != null; k = k.getSuperclass()) for (Method m : k.getDeclaredMethods()) if (m.getName().equals(ms.getMethodName())) return m;
            for (Class<?> i : c.getInterfaces()) for (Method m : i.getDeclaredMethods()) if (m.getName().equals(ms.getMethodName())) return m;
        } catch (Throwable ignore) {}
        return null;
    }

    private static Class<?> classOf(TestIdentifier id) {
        try {
            MethodSource ms = id.getSource().filter(s -> s instanceof MethodSource).map(s -> (MethodSource) s).orElse(null);
            if (ms != null) return Class.forName(ms.getClassName(), false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable ignore) {}
        return null;
    }
}
