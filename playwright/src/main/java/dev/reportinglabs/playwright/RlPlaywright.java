package dev.reportinglabs.playwright;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.internal.RlInternal;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
    /** Contexts whose onPage hook is already installed (attach(context) is called per test). */
    private static final Set<BrowserContext> HOOKED_CONTEXTS = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static volatile boolean shutdownHooked = false;
    /** Videos to attach once their contexts are closed and the report is written. */
    private static final List<Object[]> PENDING_VIDEOS = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean videoHooked = false;

    /**
     * Context options that turn on video recording when the capture policy
     * (reporting-labs.video) is not {@code never}. Use it when creating the
     * context and the video lands in the report per policy:
     *
     * <pre>{@code
     * context = browser.newContext(RlPlaywright.contextOptions());
     * page    = context.newPage();
     * RlPlaywright.attach(page);
     * }</pre>
     *
     * Add your own options to the returned object (viewport, locale, …).
     * The video is only finished once the context is closed, so close it in
     * your after-hook as usual.
     */
    public static Browser.NewContextOptions contextOptions() {
        Browser.NewContextOptions o = new Browser.NewContextOptions();
        if (!"never".equals(Rl.videoMode("playwright"))) {
            Path dir = Paths.get(dev.reportinglabs.core.internal.Config.outputFolder(), "videos");
            try { Files.createDirectories(dir); } catch (IOException ignore) {}
            o.setRecordVideoDir(dir);
        }
        return o;
    }

    private static void rememberVideo(Page page) {
        if ("never".equals(Rl.videoMode("playwright"))) return;
        RlInternal.TestSlot slot = RlInternal.currentOrLast();
        if (slot == null) return;
        Video v;
        try { v = page.video(); } catch (Throwable t) { return; }
        if (v == null) return;
        PENDING_VIDEOS.add(new Object[] { slot, v });
        if (!videoHooked) {
            synchronized (RlPlaywright.class) {
                if (!videoHooked) { videoHooked = true; RlInternal.beforeWrite(RlPlaywright::attachVideos); }
            }
        }
    }

    /** Runs right before the report is written: by then every context is
     *  closed and the .webm files are complete. Copies each video that the
     *  policy wants into <report>/assets and links it from its test. */
    private static void attachVideos() {
        java.io.File out = RlInternal.outputDir();
        if (out == null) return;
        Path assets = out.toPath().resolve("assets");
        int n = 0;
        Path videosDir = null;
        for (Object[] pv : new ArrayList<>(PENDING_VIDEOS)) {
            RlInternal.TestSlot slot = (RlInternal.TestSlot) pv[0];
            Video v = (Video) pv[1];
            boolean failed = "failed".equals(slot.outcome());
            boolean wanted = !"skipped".equals(slot.outcome()) && Rl.shouldCaptureVideo("playwright", failed);
            try {
                Path src = v.path();
                if (src == null || !Files.isRegularFile(src)) continue;
                if (videosDir == null) videosDir = src.getParent();
                if (wanted && Files.size(src) > 0) {
                    Files.createDirectories(assets);
                    String name = "video-" + (++n) + ".webm";
                    Path dst = assets.resolve(name);
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    RlInternal.attachFile(slot, "video.webm", "video/webm", "assets/" + name, Files.size(dst));
                }
                Files.deleteIfExists(src);   // the report has its copy; the policy did not want the rest
            } catch (Throwable ignore) { /* a missing video never breaks the report */ }
        }
        PENDING_VIDEOS.clear();
        // Leave no empty recordings folder behind.
        try { if (videosDir != null && Files.isDirectory(videosDir) && !Files.list(videosDir).findAny().isPresent()) Files.delete(videosDir); } catch (Throwable ignore) {}
    }

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
                if (Rl.shouldCaptureScreenshot("playwright", failed)) screenshot(failed);
                finish(failed);
            });
        }
        pages.add(page);

        PageState st = new PageState(page);
        STATES.put(page, st);

        // API auto-capture: match request → response by request instance.
        // The consumers are kept so finish() can remove them: a page shared
        // across tests is attached once per test and must not record twice.
        page.onRequest(st.reqStarted);
        page.onRequestFinished(st.reqFinished);
        page.onRequestFailed(st.reqFailed);

        // Start a trace only if the policy could ever want one (skip when
        // trace=never, so we don't pay the recording cost). Tracing is per
        // BrowserContext: a second page in the same context finds it already
        // running and simply shares it. Whether the trace is attached is
        // decided in finish(failed).
        if (!"never".equals(Rl.traceMode("playwright"))) {
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
        if (HOOKED_CONTEXTS.add(context)) context.onPage(RlPlaywright::attach);
        return context;
    }

    /**
     * A {@link Browser} whose contexts and pages are attached as they are
     * created: {@code newContext()} hooks the context so every page it opens
     * is recorded, {@code newPage()} attaches the page. Used by the
     * auto-discovery for a test that keeps only the Browser and creates
     * pages inside the test body; returns the same object when it is
     * already instrumented. Everything else delegates unchanged.
     */
    public static Browser instrument(Browser browser) {
        if (browser == null || java.lang.reflect.Proxy.isProxyClass(browser.getClass())) return browser;
        return (Browser) java.lang.reflect.Proxy.newProxyInstance(
            Browser.class.getClassLoader(), new Class<?>[] { Browser.class },
            (proxy, m, args) -> {
                Object r;
                try { r = m.invoke(browser, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                try {
                    if (r instanceof BrowserContext) attach((BrowserContext) r);
                    else if (r instanceof Page) { attach(((Page) r).context()); attach((Page) r); }
                } catch (Throwable ignore) { /* recording never breaks the test */ }
                return r;
            });
    }

    /** Attach every page of every open context of the browser. */
    public static Browser attach(Browser browser) {
        if (browser == null) return null;
        try { for (BrowserContext c : browser.contexts()) attach(c); } catch (Throwable ignore) {}
        return browser;
    }

    /**
     * Records every call made through an {@link APIRequestContext} — the
     * Playwright API-testing client behind {@code page.request()} and
     * {@code playwright.request().newContext()}. Returns a wrapper; use it
     * in place of the original:
     *
     * <pre>{@code
     * APIRequestContext api = RlPlaywright.record(page.request());
     * api.post("/v1/orders", RequestOptions.create().setData(payload));
     * }</pre>
     *
     * Each call lands in the report with method, URL (query params included),
     * request headers and body, status, timing, response headers and body
     * (text types only, capped at 200 KB) — the same shape the Node reporter's
     * {@code import 'reporting-labs/auto'} produces. Outside a test the
     * wrapper just delegates.
     */
    public static APIRequestContext record(APIRequestContext ctx) {
        if (ctx == null) return null;
        if (java.lang.reflect.Proxy.isProxyClass(ctx.getClass())) return ctx;   // already wrapped
        return (APIRequestContext) java.lang.reflect.Proxy.newProxyInstance(
            APIRequestContext.class.getClassLoader(),
            new Class<?>[] { APIRequestContext.class },
            new ApiRecorder(ctx));
    }

    // ---- APIRequestContext recording ----

    private static final int MAX_BODY = 200 * 1024;
    private static final Pattern TEXT_TYPES =
        Pattern.compile("json|text|xml|html|javascript|x-www-form-urlencoded|graphql", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VERBS = new HashSet<>(Arrays.asList("get", "post", "put", "patch", "delete", "head", "fetch"));

    private static final class ApiRecorder implements InvocationHandler {
        private final APIRequestContext real;
        ApiRecorder(APIRequestContext real) { this.real = real; }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            boolean verb = VERBS.contains(m.getName()) && args != null && args.length >= 1
                && (args[0] instanceof String || args[0] instanceof Request);
            if (!verb || RlInternal.current() == null) return call(m, args);

            Object target = args[0];
            RequestOptions opts = args.length > 1 && args[1] instanceof RequestOptions ? (RequestOptions) args[1] : null;

            String method = "fetch".equals(m.getName())
                ? (target instanceof Request ? ((Request) target).method() : "GET")
                : m.getName().toUpperCase(Locale.ROOT);
            String url = target instanceof Request ? ((Request) target).url() : String.valueOf(target);
            Map<String, String> headers = new LinkedHashMap<>();
            String body = null;
            if (target instanceof Request) {
                headers.putAll(((Request) target).headers());
                body = ((Request) target).postData();
            }
            if (opts != null) {
                Map<String, Object> o = readOptions(opts);
                if (o.get("method") != null) method = String.valueOf(o.get("method")).toUpperCase(Locale.ROOT);
                if (o.get("headers") instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o.get("headers")).entrySet())
                        headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                url = withParams(url, o.get("params"));
                String described = describeRequestBody(o, headers);
                if (described != null) body = described;
            }

            long started = System.currentTimeMillis();
            APIResponse res = null;
            Throwable failure = null;
            try { res = (APIResponse) call(m, args); }
            catch (Throwable t) { failure = t; }
            long duration = Math.max(0, System.currentTimeMillis() - started);

            try {
                if (res != null) {
                    Rl.api(method, res.url(), res.status(), duration, headers, body, res.headers(), responseBody(res));
                } else {
                    Rl.api(method, url, 0, duration, headers, body, Collections.emptyMap(),
                           "Request failed: " + shortMessage(failure));
                }
            } catch (Throwable ignore) { /* recording never fails the test */ }

            if (failure != null) throw failure;
            return res;
        }

        private Object call(Method m, Object[] args) throws Throwable {
            try { return m.invoke(real, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        }
    }

    /** PlaywrightException messages are a multi-line "Error { message='…' …}"
     *  dump; keep just the message line. */
    private static String shortMessage(Throwable t) {
        if (t == null || t.getMessage() == null) return "unknown";
        String msg = t.getMessage();
        java.util.regex.Matcher m = Pattern.compile("message='([^\\n]*)").matcher(msg);
        if (m.find()) msg = m.group(1);
        return msg.trim();
    }

    /** RequestOptions has no getters; read the impl's fields reflectively.
     *  Any failure just means less detail — never an error. */
    private static Map<String, Object> readOptions(RequestOptions opts) {
        Map<String, Object> out = new HashMap<>();
        for (String f : new String[] { "method", "headers", "params", "data", "form", "multipart" }) {
            try {
                Field fld = opts.getClass().getDeclaredField(f);
                fld.setAccessible(true);
                Object v = fld.get(opts);
                if (v != null) out.put(f, v);
            } catch (Throwable ignore) {}
        }
        return out;
    }

    private static String withParams(String url, Object params) {
        if (!(params instanceof Map) || ((Map<?, ?>) params).isEmpty()) return url;
        StringBuilder q = new StringBuilder();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) params).entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(enc(String.valueOf(e.getKey()))).append('=').append(enc(String.valueOf(e.getValue())));
        }
        return url + (url.contains("?") ? "&" : "?") + q;
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    private static String describeRequestBody(Map<String, Object> o, Map<String, String> headers) {
        boolean hasType = headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("content-type"));
        if (o.containsKey("data")) {
            Object d = o.get("data");
            if (d instanceof byte[]) return "<binary " + ((byte[]) d).length + " bytes>";
            if (d instanceof String) return (String) d;
            if (!hasType) headers.put("content-type", "application/json");
            return dev.reportinglabs.core.internal.Json.write(d);
        }
        if (o.containsKey("form")) {
            if (!hasType) headers.put("content-type", "application/x-www-form-urlencoded");
            return formFields(o.get("form"));
        }
        if (o.containsKey("multipart")) {
            if (!hasType) headers.put("content-type", "multipart/form-data");
            return formFields(o.get("multipart"));
        }
        return null;
    }

    /** FormDataImpl keeps a list of Field{name, value | file}. */
    private static String formFields(Object formData) {
        try {
            Field fl = formData.getClass().getDeclaredField("fields");
            fl.setAccessible(true);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object f : (List<?>) fl.get(formData)) {
                String name = null; Object value = null;
                for (Field ff : f.getClass().getDeclaredFields()) {
                    ff.setAccessible(true);
                    Object v = ff.get(f);
                    if ("name".equals(ff.getName())) name = String.valueOf(v);
                    else if (v != null && value == null) {
                        boolean scalar = v instanceof CharSequence || v instanceof Number || v instanceof Boolean;
                        value = scalar ? String.valueOf(v) : "<file>";
                    }
                }
                if (name != null) out.put(name, value == null ? "" : value);
            }
            return dev.reportinglabs.core.internal.Json.write(out);
        } catch (Throwable t) { return "<form>"; }
    }

    private static String responseBody(APIResponse res) {
        return textBody(res.headers(), () -> res.text());
    }

    /** Text bodies only, capped; binary types become a short placeholder. */
    private static String textBody(Map<String, String> headers, java.util.function.Supplier<String> text) {
        String type = "", len = "";
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("content-type")) type = e.getValue();
            if (e.getKey().equalsIgnoreCase("content-length")) len = e.getValue();
        }
        if (!type.isEmpty() && !TEXT_TYPES.matcher(type).find()) {
            return "<" + type.split(";")[0] + (len.isEmpty() ? "" : " " + len + " bytes") + ">";
        }
        try {
            String t = text.get();
            if (t == null || t.isEmpty()) return null;
            return t.length() > MAX_BODY ? t.substring(0, MAX_BODY) + "\n… truncated (" + t.length() + " chars)" : t;
        } catch (Throwable ignore) { return null; }
    }

    /** For a page discovered only when the test is already ending (created
     *  inside the test body, found by the auto-discovery's last scan): no
     *  listeners, no trace, just the screenshot the policy asks for. */
    public static void captureNow(Page page) {
        RlInternal.TestSlot slot = RlInternal.current();
        if (page == null || slot == null || STATES.containsKey(page)) return;
        boolean failed = "failed".equals(slot.outcome());
        if ("skipped".equals(slot.outcome()) || !Rl.shouldCaptureScreenshot("playwright", failed)) return;
        try {
            if (page.isClosed()) return;
            byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
            RlInternal.attachAuto(failed ? "failure.png" : "screen.png", "image/png", png);
        } catch (Throwable ignore) {}
    }

    /** Takes a full-page screenshot of the current test's most recent page
     *  that is still open and attaches it. Called by the framework binding
     *  when the capture policy says so; safe to call yourself. */
    public static void screenshotOnFailure() { screenshot(true); }

    /** failure.png on a failed test, screen.png otherwise (policy always / only-on-pass). */
    private static void screenshot(boolean failed) {
        if (RlInternal.currentOrLast() == null) return;
        List<Page> pages = TEST_PAGES.get();
        for (int i = pages.size() - 1; i >= 0; i--) {
            Page p = pages.get(i);
            try {
                if (p.isClosed()) continue;
                byte[] png = p.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                RlInternal.attachAuto(failed ? "failure.png" : "screen.png", "image/png", png);
                return;
            } catch (Throwable ignore) { /* try an earlier page */ }
        }
    }

    /** Called when the test finishes (pass or fail) — stops tracing on every
     *  page of the test, attaches the trace only if the policy says so, and
     *  detaches the pages. */
    public static void finish() { finish(false); }
    public static void finish(boolean failed) { finish(failed, Rl.shouldCaptureTrace("playwright", failed)); }

    private static void finish(boolean failed, boolean attachTrace) {
        List<Page> pages = TEST_PAGES.get();
        TEST_PAGES.remove();
        for (Page p : pages) {
            PageState st = STATES.remove(p);
            if (st != null) st.finish(attachTrace);
            rememberVideo(p);
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
        final java.util.function.Consumer<Request> reqStarted = this::onRequest;
        final java.util.function.Consumer<Request> reqFinished = this::onRequestFinished;
        final java.util.function.Consumer<Request> reqFailed = this::onRequestFailed;

        PageState(Page page) { this.page = page; }

        void onRequest(Request req) { starts.put(req, System.currentTimeMillis()); }

        void onRequestFinished(Request req) {
            Long start = starts.remove(req);
            long dur = start == null ? 0 : Math.max(0, System.currentTimeMillis() - start);
            try {
                Response res = req.response();
                int status = res == null ? 0 : res.status();
                // Response bodies only for XHR/fetch — an API call's payload is
                // what a reader wants; documents, scripts and images are noise.
                String rt = req.resourceType();
                String respBody = null;
                if (res != null && ("xhr".equals(rt) || "fetch".equals(rt))) {
                    final Response r = res;
                    respBody = textBody(r.headers(), r::text);
                }
                Rl.api(req.method(), req.url(), status, dur,
                       toStr(req.headers()), safeBody(req.postData()),
                       toStr(res == null ? null : res.headers()), respBody);
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
            try { page.offRequest(reqStarted); page.offRequestFinished(reqFinished); page.offRequestFailed(reqFailed); }
            catch (Throwable ignore) { /* page already closed */ }
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
