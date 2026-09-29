package dev.reportinglabs.playwright;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import dev.reportinglabs.core.internal.Config;
import dev.reportinglabs.core.spi.RlIntegration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Zero-code Playwright support. Registered through ServiceLoader, so having
 * reporting-labs-playwright on the test classpath is the whole setup.
 *
 * Every time the framework hands over the test instance (test start, after
 * each @Before* / @After*), the instance is scanned for Playwright objects:
 * a {@link Page} or {@link BrowserContext} field on the test or a base
 * class, a {@code ThreadLocal} of either, or one held by a factory / page
 * object up to three levels down. Each page found is attached exactly as
 * {@link RlPlaywright#attach(Page)} would: API calls recorded, trace and
 * screenshot per policy. An {@link APIRequestContext} field is replaced by
 * the recording wrapper from {@link RlPlaywright#record}, so plain API tests
 * get the API tab too.
 *
 * Disable with reporting-labs.playwright.autoAttach=false.
 */
public final class PlaywrightIntegration implements RlIntegration {

    private static final int MAX_DEPTH = 3;
    private static final ThreadLocal<Object> LAST_INSTANCE = new ThreadLocal<>();

    @Override
    public void onTestInstance(Object instance) {
        if (!Config.playwrightAutoAttach()) return;
        LAST_INSTANCE.set(instance);
        scan(instance, 0, Collections.newSetFromMap(new IdentityHashMap<>()), null);
    }

    /** Cucumber hands over the glue class instead of an instance: look at
     *  the statics it reaches (a PlaywrightFactory ThreadLocal). */
    @Override
    public void onTestClass(Class<?> type) {
        if (!Config.playwrightAutoAttach()) return;
        scanStatics(type, Collections.newSetFromMap(new IdentityHashMap<>()), null);
    }

    /** Cucumber: steps done, the @After hooks that close the browser are
     *  about to run. Screenshot now; the trace stops itself on page close. */
    @Override
    public void onTestBodyEnd(boolean failed) {
        if (!Config.playwrightAutoAttach()) return;
        RlPlaywright.captureBeforeAfterHooks(failed);
    }

    @Override
    public void onTestEnd() {
        if (!Config.playwrightAutoAttach()) return;
        if (RlPlaywright.currentPage() != null) return;   // attached at test start: the end hook did its work
        Object inst = LAST_INSTANCE.get();
        if (inst == null) return;
        // The page may have been created inside the test body: one late
        // look, so at least the screenshot is there.
        List<Page> found = new ArrayList<>();
        scan(inst, 0, Collections.newSetFromMap(new IdentityHashMap<>()), found);
        for (int i = found.size() - 1; i >= 0; i--) {
            Page p = found.get(i);
            try { if (!p.isClosed()) { RlPlaywright.captureNow(p); return; } } catch (Throwable ignore) {}
        }
    }

    /** With {@code late == null} pages and contexts are attached and API
     *  contexts wrapped in place; otherwise pages are only collected. */
    private static void scan(Object obj, int depth, Set<Object> seen, List<Page> late) {
        if (obj == null || depth > MAX_DEPTH || !seen.add(obj)) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!userClass(c)) return;
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(obj); } catch (Throwable t) { continue; }
                handle(f, obj, v, depth, seen, late);
            }
        }
        // Statics too: of the test class hierarchy and of every class it
        // reaches (a PlaywrightFactory with static ThreadLocal<Page> holders
        // that the test only ever calls as TlFactory.getPage()).
        if (depth == 0) scanStatics(obj.getClass(), seen, late);
    }

    private static void scanStatics(Class<?> from, Set<Object> seen, List<Page> late) {
        for (Class<?> c : dev.reportinglabs.core.internal.ClassRefs.reachable(from, PlaywrightIntegration::userClass)) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(null); } catch (Throwable t) { continue; }
                handle(f, null, v, 1, seen, late);
            }
        }
    }

    private static void handle(Field f, Object owner, Object v, int depth, Set<Object> seen, List<Page> late) {
        if (v == null) return;
        if (v instanceof ThreadLocal) {
            Object held;
            try { held = ((ThreadLocal<?>) v).get(); } catch (Throwable t) { return; }
            if (held instanceof APIRequestContext && late == null) {
                APIRequestContext wrapped = RlPlaywright.record((APIRequestContext) held);
                if (wrapped != held) { @SuppressWarnings("unchecked") ThreadLocal<Object> tl = (ThreadLocal<Object>) v; tl.set(wrapped); }
            } else if (held instanceof Browser && late == null) {
                RlPlaywright.attach((Browser) held);
                Browser wrapped = RlPlaywright.instrument((Browser) held);
                if (wrapped != held) { @SuppressWarnings("unchecked") ThreadLocal<Object> tl = (ThreadLocal<Object>) v; tl.set(wrapped); }
            } else {
                handle(null, null, held, depth, seen, late);
            }
            return;
        }
        if (v instanceof java.util.Collection || v instanceof java.util.Map) {
            // Page objects kept in a list, drivers in a map: look inside, a little.
            Iterable<?> items = v instanceof java.util.Map ? ((java.util.Map<?, ?>) v).values() : (java.util.Collection<?>) v;
            int n = 0;
            for (Object item : items) { if (++n > 50) break; if (item != null && !isValueLike(item)) handle(null, null, item, depth + 1, seen, late); }
            return;
        }
        if (v instanceof Page) {
            if (late != null) late.add((Page) v); else RlPlaywright.attach((Page) v);
        } else if (v instanceof BrowserContext) {
            if (late != null) { late.addAll(((BrowserContext) v).pages()); } else RlPlaywright.attach((BrowserContext) v);
        } else if (v instanceof APIRequestContext) {
            if (late != null || f == null) return;
            APIRequestContext wrapped = RlPlaywright.record((APIRequestContext) v);
            if (wrapped != v) trySet(f, owner, wrapped);
        } else if (v instanceof Browser) {
            if (late != null) { try { for (BrowserContext c : ((Browser) v).contexts()) late.addAll(c.pages()); } catch (Throwable ignore) {} return; }
            RlPlaywright.attach((Browser) v);
            if (f == null) return;
            Browser wrapped = RlPlaywright.instrument((Browser) v);
            if (wrapped != v) trySet(f, owner, wrapped);
        } else if (!isValueLike(v) && userClass(v.getClass())) {
            scan(v, depth + 1, seen, late);
        }
    }

    private static void trySet(Field f, Object target, Object value) {
        if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) return;
        if (!f.getType().isInstance(value)) return;   // a field typed as the impl class cannot hold the proxy
        try { f.set(target, value); } catch (Throwable ignore) {}
    }

    /** The user's own classes: not the JDK, Playwright, the test framework or ourselves. */
    private static boolean userClass(Class<?> c) {
        String n = c.getName();
        return !(n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
              || n.startsWith("com.microsoft.playwright.") || n.startsWith("org.testng.") || n.startsWith("org.junit.")
              || n.startsWith("dev.reportinglabs.") || n.startsWith("net.bytebuddy.") || n.startsWith("com.google.")
              || n.startsWith("org.apache.") || n.startsWith("io.") || n.startsWith("kotlin."));
    }

    private static boolean isValueLike(Object v) {
        return v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Character
            || v instanceof Enum || v instanceof Class || v.getClass().isArray();
    }
}
