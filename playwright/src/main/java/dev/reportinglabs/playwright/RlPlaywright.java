package dev.reportinglabs.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.internal.RlInternal;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-line integration between Playwright for Java and reportingLabs.
 *
 * <pre>{@code
 * @BeforeEach
 * void setup() {
 *   playwright = Playwright.create();
 *   browser    = playwright.chromium().launch();
 *   page       = browser.newPage();
 *   RlPlaywright.attach(page);   // <-- once per test
 * }
 * }</pre>
 *
 * With that call in place, reportingLabs:
 *   - records every request/response the page makes as an API call in the report,
 *   - starts a Playwright trace and attaches it when the test ends (per policy),
 *   - screenshots the page when the test ends (per policy) and attaches the PNG.
 *
 * Nothing else in the test changes — the same page.click(), page.fill(),
 * page.request() calls work. If reportingLabs isn't loaded (or no test is
 * active), attach() is a no-op.
 *
 * Capture policy comes from reporting-labs.screenshot / reporting-labs.trace
 * (never | on-failure | always | only-on-pass).
 */
public final class RlPlaywright {

    /** Every page attached during the current test, in attach order. */
    private static final ThreadLocal<List<Page>> TEST_PAGES = ThreadLocal.withInitial(ArrayList::new);
    private static final Map<Page, PageState> STATES = new ConcurrentHashMap<>();
    private static volatile boolean shutdownHooked = false;

    private RlPlaywright() {}

    /** The most recently attached page of the current test, or null. */
    public static Page currentPage() {
        List<Page> pages = TEST_PAGES.get();
        return pages.isEmpty() ? null : pages.get(pages.size() - 1);
    }

    /** Attaches the given Page to the current test. Idempotent — calling
     *  twice with the same Page just returns. Safe to call outside a
     *  test (does nothing). */
    public static Page attach(Page page) {
        if (page == null) return null;
        if (RlInternal.current() == null) return page;  // outside a test — nothing to record against
        if (STATES.containsKey(page)) return page;

        installShutdownHook();

        List<Page> pages = TEST_PAGES.get();
        if (pages.isEmpty()) {
            // First page of this test: hook the test's finish exactly once.
            // Screenshot and trace both honour the capture policy — a user
            // can disable either without touching test code.
            RlInternal.onEndCurrent(failure -> {
                boolean failed = failure != null;
                RlInternal.TestSlot slot = RlInternal.current();
                boolean skipped = slot != null && "skipped".equals(slot.outcome());
                if (skipped) { finish(false, false); return; }   // nothing ran — no artifacts
                if (Rl.shouldCaptureScreenshot(failed)) screenshotOnFailure();
                finish(failed);
            });
        }
        pages.add(page);

        PageState st = new PageState(page);
        STATES.put(page, st);

        // API auto-capture: match request → response by request instance
        page.onRequest(st::onRequest);
        page.onRequestFinished(st::onRequestFinished);
        page.onRequestFailed(st::onRequestFailed);

        // Start a trace only if the policy could ever want one (skip when
        // trace=never, so we don't pay the recording cost). Tracing is per
        // BrowserContext: a second page in the same context finds it already
        // running and simply shares it. Whether the trace is attached is
        // decided in finish(failed).
        if (!"never".equals(Rl.traceMode())) {
            try {
                page.context().tracing().start(new Tracing.StartOptions()
                    .setScreenshots(true).setSnapshots(true).setSources(false));
                st.tracing = true;
            } catch (Throwable ignore) { /* older Playwright, or already tracing */ }
        }

        return page;
    }

    /** Attach every current AND future page of a BrowserContext. Handy for
     *  multi-tab flows: pages opened later (popups, window.open) are wired
     *  automatically. */
    public static BrowserContext attach(BrowserContext context) {
        if (context == null) return null;
        for (Page p : context.pages()) attach(p);
        context.onPage(RlPlaywright::attach);
        return context;
    }

    /** Takes a full-page screenshot of the current test's most recent page
     *  that is still open and attaches it. Called by the framework binding
     *  when the capture policy says so; safe to call yourself. */
    public static void screenshotOnFailure() {
        if (RlInternal.currentOrLast() == null) return;
        List<Page> pages = TEST_PAGES.get();
        for (int i = pages.size() - 1; i >= 0; i--) {
            Page p = pages.get(i);
            try {
                if (p.isClosed()) continue;
                byte[] png = p.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                Rl.attach("failure.png", "image/png", png);
                return;
            } catch (Throwable ignore) { /* try an earlier page */ }
        }
    }

    /** Called when the test finishes (pass or fail) — stops tracing on every
     *  page of the test, attaches the trace only if the policy says so, and
     *  detaches the pages. */
    public static void finish() { finish(false); }
    public static void finish(boolean failed) { finish(failed, Rl.shouldCaptureTrace(failed)); }

    private static void finish(boolean failed, boolean attachTrace) {
        List<Page> pages = TEST_PAGES.get();
        TEST_PAGES.remove();
        for (Page p : pages) {
            PageState st = STATES.remove(p);
            if (st != null) st.finish(attachTrace);
        }
    }

    private static void installShutdownHook() {
        if (shutdownHooked) return;
        synchronized (RlPlaywright.class) {
            if (shutdownHooked) return;
            shutdownHooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // In case a test failed to call finish(), tidy any stuck states.
                for (PageState st : STATES.values()) st.finish();
                STATES.clear();
            }, "reporting-labs-playwright-cleanup"));
        }
    }

    // ---- per-page state ----

    private static final class PageState {
        final Page page;
        boolean tracing;
        final Map<Request, Long> starts = new ConcurrentHashMap<>();

        PageState(Page page) { this.page = page; }

        void onRequest(Request req) { starts.put(req, System.currentTimeMillis()); }

        void onRequestFinished(Request req) {
            Long start = starts.remove(req);
            long dur = start == null ? 0 : Math.max(0, System.currentTimeMillis() - start);
            try {
                Response res = req.response();
                int status = res == null ? 0 : res.status();
                Rl.api(req.method(), req.url(), status, dur,
                       toStr(req.headers()), safeBody(req.postData()),
                       toStr(res == null ? null : res.headers()), null);
            } catch (Throwable ignore) { /* one bad frame does not fail the test */ }
        }

        void onRequestFailed(Request req) {
            Long start = starts.remove(req);
            long dur = start == null ? 0 : Math.max(0, System.currentTimeMillis() - start);
            try {
                Rl.api(req.method(), req.url(), 0, dur,
                       toStr(req.headers()), safeBody(req.postData()),
                       Collections.emptyMap(), "failed: " + req.failure());
            } catch (Throwable ignore) {}
        }

        /** Stops the trace unconditionally (so recording buffers don't
         *  leak) and attaches the zip only if the caller asked for it. */
        void finish(boolean attachTrace) {
            if (tracing) {
                try {
                    if (attachTrace) {
                        Path zip = Files.createTempFile("rl-trace-", ".zip");
                        page.context().tracing().stop(new Tracing.StopOptions().setPath(zip));
                        byte[] bytes = Files.readAllBytes(zip);
                        Rl.attach("trace.zip", "application/zip", bytes);
                        try { Files.deleteIfExists(zip); } catch (IOException ignore) {}
                    } else {
                        page.context().tracing().stop();
                    }
                } catch (Throwable ignore) { /* tracing may not be supported */ }
                tracing = false;
            }
        }

        /** Overload for the shutdown cleanup path — never attach on shutdown
         *  (the report has already been written). */
        void finish() { finish(false); }

        private static Map<String, String> toStr(Map<String, String> m) {
            return m == null ? Collections.emptyMap() : m;
        }

        private static String safeBody(String s) {
            if (s == null) return null;
            return s.length() > 4096 ? s.substring(0, 4096) + " …[truncated]" : s;
        }
    }
}
