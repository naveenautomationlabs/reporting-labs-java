package dev.reportinglabs.selenium;

import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.internal.Config;
import dev.reportinglabs.core.spi.RlIntegration;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Zero-code Selenium support. Registered through ServiceLoader, so having
 * reporting-labs-selenium on the test classpath is the whole setup.
 *
 * Every time the framework hands over the test instance (test start, after
 * each @Before* / @After*), the instance is scanned for a WebDriver — a
 * {@code driver} field on the test or a base class, a {@code
 * ThreadLocal<WebDriver>} (instance or static, e.g. a DriverFactory), or a
 * WebDriver held by a page object / utility one to three levels down. Each
 * driver found is wrapped with the step-recording decorator (see
 * {@link RlSelenium#attach}) and the field is pointed at the wrapper, so
 * page objects built with the raw driver start recording too. Fields whose
 * declared type is a concrete driver class (ChromeDriver) can't hold the
 * wrapper; those keep the raw driver — screenshots still work.
 *
 * Disable with reporting-labs.selenium.autoAttach=false.
 */
public final class SeleniumIntegration implements RlIntegration {

    private static final int MAX_DEPTH = 3;

    /** True once ServiceLoader instantiated this class. */
    static volatile boolean loaded = false;

    public SeleniumIntegration() { loaded = true; }

    /** The instance the running test belongs to, for a late re-scan at test end. */
    private static final ThreadLocal<Object> LAST_INSTANCE = new ThreadLocal<>();

    @Override
    public void onTestInstance(Object instance) {
        if (!Config.seleniumAutoAttach()) return;
        LAST_INSTANCE.set(instance);
        scan(instance, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    @Override
    public void onTestEnd() {
        if (!Config.seleniumAutoAttach()) return;
        if (RlSelenium.currentDriver() == null) {
            // Driver may have been created inside the test body itself: look
            // once more so at least the screenshot is there.
            Object inst = LAST_INSTANCE.get();
            if (inst != null) scan(inst, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        if (RlSelenium.currentDriver() == null) return;
        // attachAuto: a screen.png the user attaches themselves (e.g. in an
        // @AfterMethod that runs after this) replaces ours instead of doubling.
        if (Rl.shouldCaptureScreenshot("selenium")) RlSelenium.autoScreenshot("screen.png");
    }

    private static void scan(Object obj, int depth, Set<Object> seen) {
        if (obj == null || depth > MAX_DEPTH || !seen.add(obj)) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!userClass(c)) return;
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(obj); } catch (Throwable t) { continue; }
                if (v == null) continue;
                if (v instanceof WebDriver) {
                    WebDriver wrapped = RlSelenium.attach((WebDriver) v);
                    if (wrapped != v) trySet(f, obj, wrapped);
                } else if (v instanceof ThreadLocal) {
                    try {
                        Object held = ((ThreadLocal<?>) v).get();
                        if (held instanceof WebDriver) {
                            WebDriver wrapped = RlSelenium.attach((WebDriver) held);
                            if (wrapped != held) {
                                @SuppressWarnings("unchecked")
                                ThreadLocal<Object> tl = (ThreadLocal<Object>) v;
                                tl.set(wrapped);
                            }
                        }
                    } catch (Throwable ignore) {}
                } else if (!isValueLike(v) && userClass(v.getClass())) {
                    scan(v, depth + 1, seen);
                }
            }
        }
        // Statics too: of the test class hierarchy and of every class it
        // reaches (a DriverFactory with a static ThreadLocal<WebDriver> that
        // the test only ever calls as DriverFactory.getDriver()).
        if (depth == 0) {
            for (Class<?> c : dev.reportinglabs.core.internal.ClassRefs.reachable(obj.getClass(), SeleniumIntegration::userClass)) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    Object v;
                    try { f.setAccessible(true); v = f.get(null); } catch (Throwable t) { continue; }
                    if (v != null && !isValueLike(v) && userClass(v.getClass()) && !(v instanceof Class)) scan(v, 1, seen);
                    else if (v instanceof WebDriver) {
                        WebDriver wrapped = RlSelenium.attach((WebDriver) v);
                        if (wrapped != v) trySet(f, null, wrapped);
                    } else if (v instanceof ThreadLocal) {
                        try {
                            Object held = ((ThreadLocal<?>) v).get();
                            if (held instanceof WebDriver) {
                                WebDriver wrapped = RlSelenium.attach((WebDriver) held);
                                @SuppressWarnings("unchecked") ThreadLocal<Object> tl = (ThreadLocal<Object>) v;
                                if (wrapped != held) tl.set(wrapped);
                            }
                        } catch (Throwable ignore) {}
                    }
                }
            }
        }
    }

    private static void trySet(Field f, Object target, Object value) {
        if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) return;
        if (!f.getType().isInstance(value)) return;   // e.g. a ChromeDriver-typed field
        try { f.set(target, value); } catch (Throwable ignore) {}
    }

    /** Classes we are willing to walk into: the user's own code, not the JDK,
     *  Selenium, the test framework or ourselves. */
    private static boolean userClass(Class<?> c) {
        String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
              || n.startsWith("org.openqa.") || n.startsWith("org.testng.") || n.startsWith("org.junit.")
              || n.startsWith("dev.reportinglabs.") || n.startsWith("net.bytebuddy.") || n.startsWith("com.google.")
              || n.startsWith("org.apache.") || n.startsWith("io.") || n.startsWith("kotlin."));
    }

    private static boolean isValueLike(Object v) {
        return v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Character
            || v instanceof Enum || v instanceof Class || v.getClass().isArray()
            || v instanceof java.util.Collection || v instanceof java.util.Map;
    }
}
