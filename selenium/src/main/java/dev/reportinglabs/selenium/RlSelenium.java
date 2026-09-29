package dev.reportinglabs.selenium;

import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.internal.RlInternal;
import org.openqa.selenium.Alert;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WrapsDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.events.EventFiringDecorator;
import org.openqa.selenium.support.events.WebDriverListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-line integration between Selenium and reportingLabs.
 *
 * <pre>{@code
 * driver = RlSelenium.attach(new ChromeDriver());   // use the returned driver
 * }</pre>
 *
 * With that, every test that drives this WebDriver gets:
 *   - a step per action — open URL, click, type, clear, submit, alert,
 *     frame and window switches, navigation, scripts, Actions sequences —
 *     with timing, and the failing action (a NoSuchElementException on
 *     findElement, a stale click) marked in red,
 *   - a screenshot at the end of the test per reporting-labs.screenshot
 *     (never | on-failure | always | only-on-pass) — no @AfterMethod needed.
 *
 * The driver can be created once (@BeforeTest / @BeforeClass) and reused:
 * capture is per test, not per attach. Typed text into anything that looks
 * like a password field is shown as ••••.
 *
 * With reporting-labs-selenium on the test classpath none of this needs
 * calling: the add-on finds the driver in the test instance, its base
 * classes, page objects and factory ThreadLocals by itself.
 */
public final class RlSelenium {

    /** Drivers this thread has seen (attached, discovered or driven), the
     *  most recently used first. Several can be alive at once — a driver
     *  the test holds in a field and a quit one still sitting in a factory
     *  ThreadLocal from an earlier test on this thread — so the screenshot
     *  goes to the first one that still has a session. */
    private static final ThreadLocal<Deque<WebDriver>> SEEN = ThreadLocal.withInitial(ArrayDeque::new);
    /** One entry per in-flight decorated call: the open step, or NONE for
     *  calls we don't narrate (ArrayDeque refuses nulls). */
    private static final ThreadLocal<Deque<Object>> OPEN = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Object NONE = new Object();
    private static volatile boolean endListenerInstalled = false;
    private static volatile boolean warnedNoDriver = false;
    private static final Pattern DESC = Pattern.compile("-> (.*)\\]$");
    private static final Pattern SENSITIVE = Pattern.compile("pass|pwd|secret|token|otp|pin|cvv|card", Pattern.CASE_INSENSITIVE);

    private RlSelenium() {}

    /** raw driver -> its wrapper, and wrapper -> itself (identity keys). */
    private static final Map<WebDriver, WebDriver> WRAPPERS =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Wraps the driver. Idempotent: the same raw driver always yields the
     *  same wrapper, and passing a wrapper returns it unchanged. With
     *  reporting-labs-selenium on the classpath this happens automatically
     *  for any WebDriver the test instance holds; call it yourself only to
     *  wrap a driver the auto-discovery can't see. */
    @SuppressWarnings("unchecked")
    public static <T extends WebDriver> T attach(T driver) {
        if (driver == null) return null;
        installEndListener();
        WebDriver known = WRAPPERS.get(driver);
        if (known != null) { touch(rawOf(known)); return (T) known; }
        touch(driver);
        EventFiringDecorator<T> decorator = new EventFiringDecorator<T>(new Listener());
        T wrapped = decorator.decorate(driver);
        WRAPPERS.put(driver, wrapped);
        WRAPPERS.put(wrapped, wrapped);
        RAW.put(wrapped, driver);
        return wrapped;
    }

    private static final Map<WebDriver, WebDriver> RAW =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static WebDriver rawOf(WebDriver wrapper) {
        WebDriver raw = RAW.get(wrapper);
        return raw != null ? raw : wrapper;
    }

    /** Remembers the driver as the most recently used one on this thread. */
    static void touch(WebDriver raw) {
        if (raw == null) return;
        Deque<WebDriver> d = SEEN.get();
        if (d.peekFirst() == raw) return;
        removeIdentity(d, raw);
        d.addFirst(raw);
        while (d.size() > 8) d.removeLast();
    }

    private static void removeIdentity(Deque<WebDriver> d, WebDriver raw) {
        for (Iterator<WebDriver> it = d.iterator(); it.hasNext();) if (it.next() == raw) { it.remove(); return; }
    }

    /** Quit drivers are no use for a screenshot; a RemoteWebDriver tells
     *  without a round trip (its session id is nulled by quit()). */
    private static boolean alive(WebDriver d) {
        return !(d instanceof RemoteWebDriver) || ((RemoteWebDriver) d).getSessionId() != null;
    }

    /** The raw (undecorated) driver most recently used on this thread that
     *  still has a session, or null. */
    public static WebDriver currentDriver() {
        Deque<WebDriver> d = SEEN.get();
        for (Iterator<WebDriver> it = d.iterator(); it.hasNext();) {
            WebDriver w = it.next();
            if (alive(w)) return w;
            it.remove();
        }
        return null;
    }

    /** Takes a screenshot from the attached driver and attaches it to the
     *  current (or just-finished) test. Safe to call yourself. */
    public static void screenshot(String name) {
        byte[] png = grab();
        if (png != null) { Rl.attach(name, "image/png", png); return; }
        if (SEEN.get().isEmpty() && !warnedNoDriver) {
            warnedNoDriver = true;
            System.err.println("[reporting-labs] RlSelenium.screenshot(\"" + name + "\") was called but no WebDriver is attached on this thread. "
                + "Auto-discovery finds drivers held in fields of the test class, its base classes, page objects or a ThreadLocal; "
                + "for a driver kept elsewhere call RlSelenium.attach(driver) once after creating it.");
        }
    }

    /** Screenshot taken by the integration itself: yields to a user
     *  attachment of the same name (before or after). */
    static void autoScreenshot(String name) {
        byte[] png = grab();
        if (png != null) RlInternal.attachAuto(name, "image/png", png);
    }

    /** PNG from the first driver on this thread whose session still
     *  answers, or null when none does (all quit, or none seen). */
    private static byte[] grab() {
        Deque<WebDriver> d = SEEN.get();
        List<WebDriver> dead = new ArrayList<>();
        try {
            for (WebDriver w : d) {
                if (!alive(w) || !(w instanceof TakesScreenshot)) { dead.add(w); continue; }
                try { return ((TakesScreenshot) w).getScreenshotAs(OutputType.BYTES); }
                catch (Throwable gone) { dead.add(w); }
            }
            return null;
        } finally {
            for (WebDriver w : dead) removeIdentity(d, w);
        }
    }

    private static void installEndListener() {
        if (endListenerInstalled) return;
        synchronized (RlSelenium.class) {
            if (endListenerInstalled) return;
            endListenerInstalled = true;
            // The ServiceLoader integration already screenshots at test end
            // when it is loaded; this covers manual attach() without it.
            RlInternal.addEndListener(slot -> {
                if (SEEN.get().isEmpty() || SeleniumIntegration.loaded) return;
                if (Rl.shouldCaptureScreenshot("selenium")) autoScreenshot("screen.png");
            });
        }
    }

    // ---- step recording ----

    /** Public because EventFiringDecorator dispatches the specific callbacks
     *  (afterQuit, …) reflectively and needs an accessible class. */
    public static final class Listener implements WebDriverListener {

        @Override public void beforeAnyCall(Object target, Method method, Object[] args) {
            if (target instanceof WebDriver) touch((WebDriver) target);
            else if (target instanceof WrapsDriver) { try { touch(((WrapsDriver) target).getWrappedDriver()); } catch (Throwable ignore) {} }
            String title = describe(target, method, args);
            OPEN.get().push(title != null ? RlInternal.stepBegin(title, "selenium") : NONE);
        }

        @Override public void afterAnyCall(Object target, Method method, Object[] args, Object result) {
            RlInternal.Step s = pop();
            if (s != null) RlInternal.stepEnd(s, null);
        }

        @Override public void onError(Object target, Method method, Object[] args, InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            RlInternal.Step s = pop();
            if (s == null) {
                // Not an action we narrate (findElement, getText…) but it
                // failed — that is the moment the reader wants to see.
                String title = describe(target, method, args, true);
                s = RlInternal.stepBegin(title != null ? title : method.getName(), "selenium");
            }
            RlInternal.stepEnd(s, cause);
        }

        @Override public void afterQuit(WebDriver driver) { removeIdentity(SEEN.get(), driver); }

        private static RlInternal.Step pop() {
            Deque<Object> d = OPEN.get();
            if (d.isEmpty()) return null;
            Object o = d.pop();
            return o instanceof RlInternal.Step ? (RlInternal.Step) o : null;
        }
    }

    private static String describe(Object target, Method m, Object[] args) { return describe(target, m, args, false); }

    /** Human title for the calls worth a step; null for the rest. */
    private static String describe(Object target, Method m, Object[] args, boolean onError) {
        String n = m.getName();
        if (target instanceof WebElement) {
            String el = elementDesc((WebElement) target);
            switch (n) {
                case "click":    return "click " + el;
                case "sendKeys": return "type " + keys(el, args) + " into " + el;
                case "clear":    return "clear " + el;
                case "submit":   return "submit " + el;
                case "findElement":  return onError ? "find " + arg(args, 0) + " within " + el : null;
                case "findElements": return onError ? "find all " + arg(args, 0) + " within " + el : null;
                default: return null;
            }
        }
        if (target instanceof Alert) {
            switch (n) {
                case "accept":   return "accept alert";
                case "dismiss":  return "dismiss alert";
                case "sendKeys": return "type into alert";
                default: return null;
            }
        }
        if (target instanceof WebDriver.Navigation) {
            switch (n) {
                case "to":      return "navigate to " + arg(args, 0);
                case "back":    return "navigate back";
                case "forward": return "navigate forward";
                case "refresh": return "refresh";
                default: return null;
            }
        }
        if (target instanceof WebDriver) {
            switch (n) {
                case "get":          return "open " + arg(args, 0);
                case "findElement":  return onError ? "find " + arg(args, 0) : null;
                case "findElements": return onError ? "find all " + arg(args, 0) : null;
                case "close":        return "close window";
                case "executeScript":      return "run script " + script(args);
                case "executeAsyncScript": return "run async script " + script(args);
                case "perform":      return "perform " + actions(args);
                default: return null;
            }
        }
        if (target instanceof WebDriver.TargetLocator) {
            switch (n) {
                case "frame":          return "switch to frame " + arg(args, 0);
                case "window":         return "switch to window " + arg(args, 0);
                case "newWindow":      return "open new " + (arg(args, 0).toLowerCase().contains("tab") ? "tab" : "window");
                case "defaultContent": return "switch to default content";
                case "parentFrame":    return "switch to parent frame";
                default: return null;
            }
        }
        return null;
    }

    private static String arg(Object[] args, int i) {
        return args != null && args.length > i && args[i] != null ? String.valueOf(args[i]) : "";
    }

    /** First line of the script, shortened. */
    private static String script(Object[] args) {
        String s = arg(args, 0).trim().replaceAll("\\s+", " ");
        return "\"" + (s.length() > 60 ? s.substring(0, 60) + "…" : s) + "\"";
    }

    /** The distinct action types in an Actions sequence, in order:
     *  "pointerMove, pointerDown, pointerUp". */
    private static String actions(Object[] args) {
        Set<String> kinds = new LinkedHashSet<>();
        try {
            Object seqs = args != null && args.length > 0 ? args[0] : null;
            if (seqs instanceof Collection) {
                for (Object seq : (Collection<?>) seqs) {
                    Object json = seq.getClass().getMethod("toJson").invoke(seq);
                    Object acts = json instanceof Map ? ((Map<?, ?>) json).get("actions") : null;
                    if (!(acts instanceof Collection)) continue;
                    for (Object a : (Collection<?>) acts) {
                        Object type = a instanceof Map ? ((Map<?, ?>) a).get("type") : null;
                        if (type != null && !"pause".equals(type)) kinds.add(String.valueOf(type));
                    }
                }
            }
        } catch (Throwable ignore) {}
        return kinds.isEmpty() ? "actions" : "actions: " + String.join(", ", kinds);
    }

    /** "css selector: .row -> tag name: button" — the locator chain from
     *  RemoteWebElement's toString, which nests "[[parent] -> by: value]"
     *  one level per findElement hop. */
    private static String elementDesc(WebElement el) {
        try { return elementDesc(String.valueOf(el)); } catch (Throwable t) { return "element"; }
    }

    static String elementDesc(String toString) {
        List<String> chain = new ArrayList<>();
        chain(toString, chain);
        if (!chain.isEmpty()) return String.join(" -> ", chain);
        Matcher m = DESC.matcher(toString);
        return m.find() ? m.group(1) : "element";
    }

    private static void chain(String s, List<String> out) {
        if (s == null || !s.startsWith("[")) return;      // driver text: "ChromeDriver: chrome on linux (id)"
        int end = matching(s);
        if (end < 0) return;
        if (end == s.length() - 1) { chain(s.substring(1, end), out); return; }   // "[X]": unwrap
        if (s.startsWith(" -> ", end + 1)) { chain(s.substring(0, end + 1), out); out.add(s.substring(end + 5)); }
    }

    /** Index of the ']' closing the '[' at 0, or -1. */
    private static int matching(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    private static String keys(String el, Object[] args) {
        if (args == null || args.length == 0) return "\"\"";
        StringBuilder sb = new StringBuilder();
        Object first = args[0];
        Object[] parts = first instanceof Object[] ? (Object[]) first : args;
        for (Object p : parts) sb.append(p == null ? "" : String.valueOf(p));
        String text = sb.toString();
        if (SENSITIVE.matcher(el).find()) return "••••";
        String shown = text.replaceAll("[\\uE000-\\uF8FF]", "⏎");
        return "\"" + (shown.length() > 60 ? shown.substring(0, 60) + "…" : shown) + "\"";
    }
}
