package dev.reportinglabs.core;

import dev.reportinglabs.core.internal.Config;
import dev.reportinglabs.core.internal.RlInternal;

import java.util.Map;

/**
 * The user-facing runtime API. Call these from inside a test body — the
 * framework binding (JUnit 5, TestNG, …) knows which test is "current".
 *
 * <pre>{@code
 * @Test @Priority("P0")
 * void places_order() {
 *   Rl.testData(Map.of("user", "demo@shop.io", "total", 99.90), "Cart");
 *   Rl.log("opening checkout");
 *   // your usual test code — no wrappers
 * }
 * }</pre>
 *
 * Every method is a no-op if called outside a test (e.g. from a static
 * block); it never throws just because the report is not active.
 */
public final class Rl {
    private Rl() {}

    /** Add or overwrite a key/value chip on the current test. */
    public static void meta(String key, String value) {
        RlInternal.meta(key, value);
    }

    /** Append a step message to the current test's log. */
    public static void log(String message) {
        RlInternal.log(message);
    }

    /** Pin a named data block on the current test. Nested Maps have
     *  sensitive keys (password, token, authorization, …) masked as ****.
     *  For non-Map values the block shows the value as text. */
    public static void testData(Object value) { RlInternal.testData(null, value); }
    public static void testData(Object value, String name) { RlInternal.testData(name, value); }
    public static void testData(String name, Object value) { RlInternal.testData(name, value); }
    /** Name first, then a CSV or plain-text value. (Without this overload a
     *  call with two Strings would be ambiguous between the two above.) */
    public static void testData(String name, String value) { RlInternal.testData(name, value); }

    /** Record an API call. Everything besides method/url/status is optional. */
    public static void api(String method, String url, int status) {
        RlInternal.api(method, url, status, 0, null, null, null, null);
    }
    public static void api(String method, String url, int status, long durationMs) {
        RlInternal.api(method, url, status, durationMs, null, null, null, null);
    }
    public static void api(String method, String url, int status, long durationMs,
                           Map<String, String> reqHeaders, String reqBody,
                           Map<String, String> respHeaders, String respBody) {
        RlInternal.api(method, url, status, durationMs, reqHeaders, reqBody, respHeaders, respBody);
    }

    /** Attach a binary blob (screenshot, video, trace, whatever). */
    public static void attach(String name, String contentType, byte[] bytes) {
        RlInternal.attach(name, contentType, bytes);
    }

    // ---- steps ----

    /** A block of test code that may throw. */
    @FunctionalInterface public interface Body { void run() throws Exception; }
    /** A block of test code that returns a value and may throw. */
    @FunctionalInterface public interface Supplier<T> { T get() throws Exception; }

    /** Runs {@code body} as a named, timed step in the report — the Java
     *  equivalent of Playwright's {@code test.step()}. Steps nest; a step
     *  whose body throws is marked failed with the exception, and the
     *  exception propagates unchanged.
     *  <pre>{@code
     *  Rl.step("login as demo", () -> {
     *      page.fill("#email", "demo@shop.io");
     *      page.click("#submit");
     *  });
     *  }</pre> */
    public static void step(String title, Body body) {
        RlInternal.Step s = RlInternal.stepBegin(title, "test.step");
        try { body.run(); RlInternal.stepEnd(s, null); }
        catch (Throwable t) { RlInternal.stepEnd(s, t); throw sneaky(t); }
    }

    /** Same as {@link #step(String, Body)} for a body that returns a value:
     *  {@code String id = Rl.step("create order", () -> api.create(order));} */
    public static <T> T step(String title, Supplier<T> body) {
        RlInternal.Step s = RlInternal.stepBegin(title, "test.step");
        try { T v = body.get(); RlInternal.stepEnd(s, null); return v; }
        catch (Throwable t) { RlInternal.stepEnd(s, t); throw sneaky(t); }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E { throw (E) t; }

    // ---- capture policy helpers ----
    //
    // The library never takes a screenshot itself — the caller does, from
    // whichever driver they're using (Selenium, Playwright, Appium, ...).
    // These helpers expose the reporting-labs.screenshot / .video / .trace
    // config so a base test class can decide whether to capture without
    // rolling its own switch. RlPlaywright.attach(page) already honours
    // Rl.screenshotMode() and Rl.traceMode() automatically.

    /** `always` | `on-failure` | `only-on-pass` | `never`. Default `on-failure`. */
    public static String screenshotMode() { return Config.screenshot(); }

    /** Same shape as {@link #screenshotMode()}, default `on-failure`. */
    public static String traceMode()      { return Config.trace(); }

    /** Same shape as {@link #screenshotMode()}, default `never`. */
    public static String videoMode()      { return Config.video(); }

    /** True when the current mode says to capture, using the outcome of the
     *  test that is running or — from an @AfterMethod / @AfterEach — the one
     *  that just finished on this thread. Example:
     *  <pre>{@code
     *  @AfterMethod(alwaysRun = true)
     *  void tearDown() {
     *    if (Rl.shouldCaptureScreenshot()) {
     *      Rl.attach("screen.png", "image/png",
     *        ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
     *    }
     *    driver.quit();
     *  }
     *  }</pre> */
    public static boolean shouldCaptureScreenshot() {
        return !RlInternal.currentOrLastSkipped() && shouldCaptureScreenshot(RlInternal.currentOrLastFailed());
    }
    public static boolean shouldCaptureTrace() {
        return !RlInternal.currentOrLastSkipped() && shouldCaptureTrace(RlInternal.currentOrLastFailed());
    }
    public static boolean shouldCaptureVideo() {
        return !RlInternal.currentOrLastSkipped() && shouldCaptureVideo(RlInternal.currentOrLastFailed());
    }

    /** Same as {@link #shouldCaptureScreenshot()} with an explicit outcome, for
     *  callers that already hold it (ITestResult, TestWatcher, …). */
    public static boolean shouldCaptureScreenshot(boolean failed) {
        return Config.shouldCapture(Config.screenshot(), failed);
    }

    public static boolean shouldCaptureTrace(boolean failed) {
        return Config.shouldCapture(Config.trace(), failed);
    }

    public static boolean shouldCaptureVideo(boolean failed) {
        return Config.shouldCapture(Config.video(), failed);
    }

    // ---- per-tool policies: reporting-labs.<tool>.screenshot / .trace / .video,
    //      falling back to the plain keys. tool is "selenium", "playwright", ... ----

    public static String screenshotMode(String tool) { return Config.screenshot(tool); }
    public static String traceMode(String tool)      { return Config.trace(tool); }
    public static String videoMode(String tool)      { return Config.video(tool); }
    public static boolean shouldCaptureScreenshot(String tool) {
        return !RlInternal.currentOrLastSkipped() && shouldCaptureScreenshot(tool, RlInternal.currentOrLastFailed());
    }
    public static boolean shouldCaptureScreenshot(String tool, boolean failed) { return Config.shouldCapture(Config.screenshot(tool), failed); }
    public static boolean shouldCaptureTrace(String tool, boolean failed)      { return Config.shouldCapture(Config.trace(tool), failed); }
    public static boolean shouldCaptureVideo(String tool, boolean failed)      { return Config.shouldCapture(Config.video(tool), failed);
    }
}
