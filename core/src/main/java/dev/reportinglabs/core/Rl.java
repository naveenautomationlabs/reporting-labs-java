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
}
