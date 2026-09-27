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
 *   - remembers the Page so the framework binding can screenshot on failure,
 *   - starts a Playwright trace and attaches it when the test ends,
 *   - screenshots on failure and attaches the PNG.
 *
 * Nothing else in the test changes — the same page.click(), page.fill(),
 * page.request() calls work. If reportingLabs isn't loaded (or no test is
 * active), attach() is a no-op.
 */
public final class RlPlaywright {

    private static final ThreadLocal<Page> CURRENT_PAGE = new ThreadLocal<>();
    private static final Map<Page, PageState> STATES = new ConcurrentHashMap<>();
    private static volatile boolean shutdownHooked = false;

    private RlPlaywright() {}

    /** Returns the Page attached to this thread, or null. Used by
     *  framework failure hooks to grab a screenshot. */
    public static Page currentPage() { return CURRENT_PAGE.get(); }

    /** Attaches the given Page to the current test. Idempotent — calling
     *  twice with the same Page just returns. Safe to call outside a
     *  test (does nothing). */
    public static Page attach(Page page) {
        if (page == null) return null;
        if (RlInternal.current() == null) return page;  // outside a test — nothing to record against
        if (STATES.containsKey(page)) { CURRENT_PAGE.set(page); return page; }

        installShutdownHook();

        // Wire capture hooks into the test's finish. Screenshot and trace
        // both honour reporting-labs.screenshot / .trace policy — user can
        // disable either without touching test code.
        RlInternal.onEndCurrent(failure -> {
            boolean failed = failure != null;
            if (Rl.shouldCaptureScreenshot(failed)) screenshotOnFailure();
            finish(failed);
        });

        PageState st = new PageState(page);
        STATES.put(page, st);
        CURRENT_PAGE.set(page);

        // API auto-capture: match request → response by request instance
        page.onRequest(st::onRequest);
        page.onRequestFinished(st::onRequestFinished);
        page.onRequestFailed(st::onRequestFailed);

        // Start a trace only if the policy could ever want one (skip when
        // trace=never, so we don't pay the recording cost). Whether the
        // trace is actually attached is decided in finish(failed).
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
     *  multi-tab flows: pages opened later are wired automatically. */
    public static BrowserContext attach(BrowserContext context) {
        if (context == null) return null;
        for (Page p : context.pages()) attach(p);
        context.onPage(RlPlaywright::attach);
        return context;
    }

    /** Called by the framework binding on test failure — takes a
     *  screenshot from the ThreadLocal page and attaches it. */
    public static void screenshotOnFailure() {
        Page p = CURRENT_PAGE.get();
        if (p == null || RlInternal.current() == null) return;
        try {
            byte[] png = p.screenshot(new Page.ScreenshotOptions().setFullPage(true));
            Rl.attach("failure.png", "image/png", png);
        } catch (Throwable ignore) { /* page may be closed already */ }
    }

    /** Called by the framework binding on test finish (pass or fail) —
     *  stops the trace, attaches it only if the policy says so, detaches
     *  the page. */
    public static void finish() { finish(false); }
    public static void finish(boolean failed) {
        Page p = CURRENT_PAGE.get();
        CURRENT_PAGE.remove();
        if (p == null) return;
        PageState st = STATES.remove(p);
        if (st != null) st.finish(Rl.shouldCaptureTrace(failed));
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
