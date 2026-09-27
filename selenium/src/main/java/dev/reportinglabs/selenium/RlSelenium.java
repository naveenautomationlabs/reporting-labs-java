package dev.reportinglabs.selenium;

import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.internal.RlInternal;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.events.EventFiringDecorator;
import org.openqa.selenium.support.events.WebDriverListener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
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
 *   - a step per action — open URL, click, type, clear, submit, alert —
 *     with timing, and the failing action (a NoSuchElementException on
 *     findElement, a stale click) marked in red,
 *   - a screenshot at the end of the test per reporting-labs.screenshot
 *     (never | on-failure | always | only-on-pass) — no @AfterMethod needed.
 *
 * The driver can be created once (@BeforeTest / @BeforeClass) and reused:
 * capture is per test, not per attach. Typed text into anything that looks
 * like a password field is shown as ••••.
 */
public final class RlSelenium {

    private static final ThreadLocal<WebDriver> ACTIVE = new ThreadLocal<>();
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
    private static final java.util.Map<WebDriver, WebDriver> WRAPPERS =
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
        if (known != null) { ACTIVE.set(rawOf(known)); return (T) known; }
        ACTIVE.set(driver);
        EventFiringDecorator<T> decorator = new EventFiringDecorator<T>(new Listener());
        T wrapped = decorator.decorate(driver);
        WRAPPERS.put(driver, wrapped);
        WRAPPERS.put(wrapped, wrapped);
        RAW.put(wrapped, driver);
        return wrapped;
    }

    private static final java.util.Map<WebDriver, WebDriver> RAW =
        Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static WebDriver rawOf(WebDriver wrapper) {
        WebDriver raw = RAW.get(wrapper);
        return raw != null ? raw : wrapper;
    }

    /** The raw (undecorated) driver attached on this thread, or null. */
    public static WebDriver currentDriver() { return ACTIVE.get(); }

    /** Takes a screenshot from the attached driver and attaches it to the
     *  current (or just-finished) test. Safe to call yourself. */
    public static void screenshot(String name) {
        WebDriver d = ACTIVE.get();
        if (d == null) {
            if (!warnedNoDriver) {
                warnedNoDriver = true;
                System.err.println("[reporting-labs] RlSelenium.screenshot(\"" + name + "\") was called but no WebDriver is attached on this thread. "
                    + "Auto-discovery finds drivers held in fields of the test class, its base classes, page objects or a ThreadLocal; "
                    + "for a driver kept elsewhere call RlSelenium.attach(driver) once after creating it.");
            }
            return;
        }
        try {
            byte[] png = ((TakesScreenshot) d).getScreenshotAs(OutputType.BYTES);
            Rl.attach(name, "image/png", png);
        } catch (Throwable ignore) { /* session already gone */ }
    }

    /** Screenshot taken by the integration itself: yields to a user
     *  attachment of the same name (before or after). */
    static void autoScreenshot(String name) {
        WebDriver d = ACTIVE.get();
        if (d == null) return;
        try {
            byte[] png = ((TakesScreenshot) d).getScreenshotAs(OutputType.BYTES);
            RlInternal.attachAuto(name, "image/png", png);
        } catch (Throwable ignore) { /* session already gone */ }
    }

    private static void installEndListener() {
        if (endListenerInstalled) return;
        synchronized (RlSelenium.class) {
            if (endListenerInstalled) return;
            endListenerInstalled = true;
            // The ServiceLoader integration already screenshots at test end
            // when it is loaded; this covers manual attach() without it.
            RlInternal.addEndListener(slot -> {
                if (ACTIVE.get() == null || SeleniumIntegration.loaded) return;
                if (Rl.shouldCaptureScreenshot()) autoScreenshot("screen.png");
            });
        }
    }

    // ---- step recording ----

    /** Public because EventFiringDecorator dispatches the specific callbacks
     *  (afterQuit, …) reflectively and needs an accessible class. */
    public static final class Listener implements WebDriverListener {

        @Override public void beforeAnyCall(Object target, Method method, Object[] args) {
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

        @Override public void afterQuit(WebDriver driver) { ACTIVE.remove(); }

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
                case "findElement":  return onError ? "find " + args[0] + " within " + el : null;
                case "findElements": return onError ? "find all " + args[0] + " within " + el : null;
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
                case "to":      return "navigate to " + (args != null && args.length > 0 ? args[0] : "");
                case "back":    return "navigate back";
                case "forward": return "navigate forward";
                case "refresh": return "refresh";
                default: return null;
            }
        }
        if (target instanceof WebDriver) {
            switch (n) {
                case "get":          return "open " + (args != null && args.length > 0 ? args[0] : "");
                case "findElement":  return onError ? "find " + args[0] : null;
                case "findElements": return onError ? "find all " + args[0] : null;
                case "close":        return "close window";
                default: return null;
            }
        }
        if (target instanceof WebDriver.TargetLocator) {
            switch (n) {
                case "frame":  return "switch to frame " + (args != null && args.length > 0 ? args[0] : "");
                case "window": return "switch to window " + (args != null && args.length > 0 ? args[0] : "");
                case "alert":  return null;
                default: return null;
            }
        }
        return null;
    }

    private static String elementDesc(WebElement el) {
        try {
            Matcher m = DESC.matcher(String.valueOf(el));
            return m.find() ? m.group(1) : "element";
        } catch (Throwable t) { return "element"; }
    }

    private static String keys(String el, Object[] args) {
        if (args == null || args.length == 0) return "\"\"";
        StringBuilder sb = new StringBuilder();
        Object first = args[0];
        Object[] parts = first instanceof Object[] ? (Object[]) first : args;
        for (Object p : parts) sb.append(p == null ? "" : String.valueOf(p));
        String text = sb.toString();
        if (SENSITIVE.matcher(el).find() || text.chars().anyMatch(c -> c == '' || c == '')) {
            // password-ish field, or special keys (ENTER/TAB) — don't echo
            return SENSITIVE.matcher(el).find() ? "••••" : "\"" + text.replaceAll("[\\uE000-\\uF8FF]", "⏎") + "\"";
        }
        return "\"" + (text.length() > 60 ? text.substring(0, 60) + "…" : text) + "\"";
    }
}
