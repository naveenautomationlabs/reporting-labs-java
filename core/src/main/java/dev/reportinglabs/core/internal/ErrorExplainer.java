package dev.reportinglabs.core.internal;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw failure into a short, plain-language reading: the same
 * rule set as the Node reporter's explainError, plus the shapes Java
 * frameworks print (TestNG/JUnit/AssertJ assertions, Selenium exceptions,
 * Playwright for Java's wrapped messages, TestNG time-outs). No network,
 * no AI. The original message is always kept next to it.
 */
public final class ErrorExplainer {

    private ErrorExplainer() {}

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("not-found", "Element not found");
        LABELS.put("ambiguous", "Selector matches several elements");
        LABELS.put("not-visible", "Element not visible");
        LABELS.put("blocked", "Element covered by another element");
        LABELS.put("disabled", "Element disabled");
        LABELS.put("detached", "Element disappeared");
        LABELS.put("wrong-element", "Wrong element type");
        LABELS.put("assertion", "Assertion failed");
        LABELS.put("visual", "Screenshot mismatch");
        LABELS.put("navigation", "Page did not load");
        LABELS.put("network", "Site unreachable");
        LABELS.put("api", "API call failed");
        LABELS.put("test-timeout", "Test timed out");
        LABELS.put("hook-timeout", "Hook timed out");
        LABELS.put("closed", "Browser closed early");
        LABELS.put("script", "Error in test code");
        LABELS.put("file", "File not found");
        LABELS.put("thrown", "Test threw an error");
        LABELS.put("undefined-step", "Step has no step definition");
        LABELS.put("pending-step", "Step definition not written yet");
        LABELS.put("ambiguous-step", "Step matches more than one step definition");
    }

    /** Playwright for Java wraps the driver's message as
     *  {@code Error { message='…'  name='…'  stack='…' }}. Pull out the message. */
    public static String unwrap(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int i = s.indexOf("message='");
        if (s.startsWith("Error {") && i > 0) {
            int end = s.indexOf("\n  name='", i);
            if (end < 0) end = s.lastIndexOf('}');
            if (end > i) s = s.substring(i + 9, end).trim();
        }
        return s;
    }

    /** The message shown at the top of the error block. Unwrapped, with the
     *  exception type in front when the message alone would not say what
     *  happened (an assertion's own text is left as it is). */
    public static String displayMessage(Throwable t) {
        if (t == null) return "";
        String msg = unwrap(t.getMessage());
        String simple = t.getClass().getSimpleName();
        if (msg.isEmpty()) return simple;
        if (t instanceof AssertionError) return msg;
        if (msg.startsWith(simple + ":") || msg.startsWith(simple + " ")) return msg;
        return simple + ": " + msg;
    }

    private static String pick(String s, String re) { return pick(s, re, 0); }
    private static String pick(String s, String re, int flags) {
        Matcher m = Pattern.compile(re, flags | Pattern.MULTILINE).matcher(s);
        return m.find() ? m.group(1) : null;
    }
    private static boolean has(String s, String re) { return Pattern.compile(re, Pattern.MULTILINE).matcher(s).find(); }
    private static boolean hasI(String s, String re) { return Pattern.compile(re, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE).matcher(s).find(); }
    private static String secs(long ms) { return ms >= 1000 ? (ms % 1000 == 0 ? (ms / 1000) + "s" : String.format("%.1fs", ms / 1000.0)) : ms + "ms"; }
    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    private static Map<String, Object> out(String kind, String summary, String hint, Object... extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("label", LABELS.getOrDefault(kind, kind));
        m.put("summary", summary);
        if (hint != null) m.put("hint", hint);
        for (int i = 0; i + 1 < extra.length; i += 2) if (extra[i + 1] != null) m.put(String.valueOf(extra[i]), extra[i + 1]);
        return m;
    }

    /** Explain a failure, or null when there is nothing to say. */
    public static Map<String, Object> explain(Throwable t) { return explain(t, null, null); }

    /**
     * @param actionHint  the Playwright call that failed, from the stack (locator.click, page.navigate), when the message does not say
     * @param locatorHint the locator from the failing source line, when the message does not say
     */
    public static Map<String, Object> explain(Throwable t, String actionHint, String locatorHint) {
        if (t == null) return null;
        String type = t.getClass().getName();
        String msg = unwrap(t.getMessage());
        if (msg.isEmpty()) msg = t.getClass().getSimpleName();
        String first = msg.split("\n", 2)[0].trim();

        String locator = pick(msg, "(?:waiting for|Locator:\\s*|resolved to \\d+ elements?:?\\s*)\\s*(locator\\([^\\n]*?\\)(?:\\.\\w+\\([^\\n]*?\\))*)");
        if (locator == null) locator = pick(msg, "(?:waiting for|Locator:\\s*)\\s*(getBy\\w+\\([^\\n]*?\\)(?:\\.\\w+\\([^\\n]*?\\))*)");
        if (locator == null) { locator = pick(msg, "Unable to locate element: \\{\"method\":\"[^\"]+\",\"selector\":\"([^\"]+)\"\\}"); if (locator != null) locator = locator.replace("\\-", "-"); }
        if (locator == null) locator = locatorHint;
        String tm = pick(msg, "(?:Timeout|timeout of|Timed out|time-out|timeout)\\s+(\\d+)\\s*ms", Pattern.CASE_INSENSITIVE);
        Long timeoutMs = tm != null ? Long.valueOf(tm) : null;
        String action = pick(first, "^(?:Error: |TimeoutError: )?([a-zA-Z]+\\.[a-zA-Z]+):");
        if (action == null) action = actionHint;
        String url = pick(msg, "(?:navigating to|at|for)\\s+\"?(https?://[^\\s\"]+)");
        boolean pwTimeout = type.equals("com.microsoft.playwright.TimeoutError");

        // ── Cucumber: a Gherkin step with no glue, or glue still pending ──
        String undefinedStep = pick(msg, "^(?:UndefinedStepException: )?The step '(.+?)' is undefined");
        if (undefinedStep != null) {
            return out("undefined-step", "No step definition matches \"" + undefinedStep + "\", so the scenario stopped there and the steps after it did not run.",
                "Write a method annotated @Given/@When/@Then whose expression matches this text, in a class under your glue package. Cucumber printed a ready-to-paste snippet in the console.");
        }
        if (type.endsWith("AmbiguousStepDefinitionsException")) {
            String step = pick(msg, "^\\\"(.+?)\\\" matches more than one step definition");
            return out("ambiguous-step", "More than one step definition matches " + (step != null ? "\"" + step + "\"" : "this step") + ", so Cucumber could not pick one and the scenario stopped there.",
                "Make the expressions distinct (a literal word instead of {word}, or an anchored regular expression). The message below lists every matching method.");
        }
        String pendingStep = pick(msg, "^The step '(.+?)' is pending");
        if (pendingStep != null || type.endsWith("PendingException")) {
            return out("pending-step", "The step definition for " + (pendingStep != null ? "\"" + pendingStep + "\"" : "this step") + " still throws PendingException.",
                "Replace the throw new PendingException() body with the real implementation.");
        }

        // ── Test time-outs ────────────────────────────────────────────────
        Matcher m = Pattern.compile("didn't finish within the time-out (\\d+)").matcher(msg);
        if (type.endsWith("ThreadTimeoutException") || m.find()) {
            long ms = m.find(0) ? Long.parseLong(m.group(1)) : (timeoutMs == null ? 0 : timeoutMs);
            return out("test-timeout", "The whole test took longer than " + (ms > 0 ? secs(ms) : "its time-out") + ".", "Find the slow step in the Steps list below. Raise timeOut only if the flow is really that long.", "timeoutMs", ms > 0 ? ms : null);
        }

        // ── Playwright assertions (Java flavour) ───────────────────────────
        m = Pattern.compile("^(Locator|Page|Response|APIResponse) expected (?:to |not to )?(.+?)(?::|\\n|$)").matcher(msg);
        if (m.find()) {
            String what = m.group(2).trim();
            String expected = pick(msg, "^Expected(?:[^:\\n]{0,40})?:[ \\t]*(.+)$");
            String received = pick(msg, "^Received(?:[^:\\n]{0,40})?:[ \\t]*(.+)$");
            String matcher = what.replaceAll("\\s+", " ");
            if (has(msg, "(?i)not found|resolved to 0 elements") && locator != null)
                return out("not-found", locator + " was not on the page" + (timeoutMs != null ? " within " + secs(timeoutMs) : "") + ", so the check could not run.", "Check the selector, and whether the element is inside an iframe, behind a login, or only shown after a click.", "locator", locator, "matcher", matcher, "timeoutMs", timeoutMs);
            if (what.startsWith("be visible") || what.startsWith("be hidden")) {
                boolean vis = what.startsWith("be visible");
                return out(vis ? "not-visible" : "assertion", (locator != null ? locator : "The element") + " was expected to be " + (vis ? "visible" : "hidden") + (timeoutMs != null ? " within " + secs(timeoutMs) : "") + " but was " + (received != null ? received : vis ? "hidden" : "visible") + ".", vis ? "The element may still be loading, be hidden by CSS, or sit inside a closed menu or dialog." : "Something kept the element on screen. Check the step that should hide it.", "locator", locator, "matcher", matcher, "timeoutMs", timeoutMs);
            }
            if (expected != null && received != null)
                return out("assertion", (locator != null ? locator + " had the wrong " : "The ") + what.replaceFirst("^have ", "").replaceFirst("^contain ", "") + ": expected " + cut(expected, 80) + ", got " + cut(received, 80) + ".", "Compare expected and received below. A copy change, a data change or a timing issue are the usual causes.", "locator", locator, "matcher", matcher, "timeoutMs", timeoutMs);
            return out("assertion", m.group(1) + " expected to " + what + (locator != null ? " for " + locator : "") + " but did not.", "See the full message below for the expected and received values.", "locator", locator, "matcher", matcher, "timeoutMs", timeoutMs);
        }

        // ── Selenium ──────────────────────────────────────────────────────
        if (type.startsWith("org.openqa.selenium.")) {
            String simple = t.getClass().getSimpleName();
            String sel = pick(msg, "Unable to locate element: \\{\"method\":\"[^\"]+\",\"selector\":\"([^\"]+)\"\\}");
            if (sel == null) sel = pick(msg, "(By\\.[a-zA-Z]+: [^\\n]+)");
            if (sel != null) sel = sel.replace("\\-", "-");   // chromedriver escapes hyphens in CSS selectors
            switch (simple) {
                case "NoSuchElementException": return out("not-found", (sel != null ? sel : "The element") + " was not on the page.", "Check the selector, and whether the element is inside an iframe, behind a login, or only shown after another step.", "locator", sel);
                case "TimeoutException": {
                    boolean vis = hasI(msg, "visibility|visible");
                    return out(vis ? "not-visible" : "not-found", (sel != null ? sel : "The element") + (vis ? " never became visible" : " did not appear") + (timeoutMs != null ? " within " + secs(timeoutMs) : " in time") + ".", "The element may still be loading, be inside a closed menu, or the selector may be wrong.", "locator", sel, "timeoutMs", timeoutMs);
                }
                case "ElementClickInterceptedException": return out("blocked", (sel != null ? sel : "The element") + " was there, but another element was covering it, so the click never landed.", "A modal, cookie banner, toast or loading overlay is on top. Close it first, or wait for it to disappear.", "locator", sel);
                case "ElementNotInteractableException": return out("not-visible", (sel != null ? sel : "The element") + " exists but could not be interacted with.", "It may be hidden, zero-sized, or covered. Scroll it into view or wait for it to become visible.", "locator", sel);
                case "StaleElementReferenceException": return out("detached", "The element was removed from the page while the test was using it.", "The UI re-rendered the element. Re-locate it after the change, or wait for the update to finish.");
                case "InvalidSelectorException": return out("script", "The selector is not valid: " + cut(first, 100), "Check the CSS or XPath syntax.");
                case "NoSuchWindowException": case "NoSuchSessionException": case "SessionNotCreatedException": return out("closed", "The browser or window was closed before this step could run.", "A previous step closed it, the test ended early, or the browser crashed.");
                case "NoAlertPresentException": return out("assertion", "No alert was open when the test tried to use one.", "Check the step that should have opened the alert.");
                case "UnhandledAlertException": return out("blocked", "An alert was open and blocked the action.", "Accept or dismiss the alert before continuing.");
                default: break;
            }
        }

        // ── Assertions: TestNG, JUnit, AssertJ, Hamcrest ───────────────────
        boolean isAssert = t instanceof AssertionError || type.endsWith("AssertionFailedError") || type.endsWith("ComparisonFailure") || type.contains("opentest4j");
        if (isAssert) {
            // REST Assured: "1 expectation failed.\nJSON path status doesn't match.\nExpected: SHIPPED\n  Actual: DELIVERED"
            m = Pattern.compile("^\\d+ expectations? failed\\.\\s*\\n\\s*(.+?)\\s*$", Pattern.MULTILINE).matcher(msg);
            if (m.find()) {
                String what = m.group(1).trim();
                String e = pick(msg, "^\\s*Expected(?:[^:\\n]{0,40})?:[ \\t]*(.+)$"), r = pick(msg, "^\\s*(?:Actual|but was|Received)(?:[^:\\n]{0,40})?:[ \\t]*(.+)$");
                if (e != null && r != null) return out("assertion", cut(what.replaceFirst("[.:]$", ""), 90) + ": expected " + cut(e.trim(), 60) + ", got " + cut(r.trim(), 60) + ".", "Compare expected and actual below. The API tab has the request and the full response.", "matcher", "restassured");
                String tm2 = pick(msg, "was (\\d+) milliseconds");
                if (what.startsWith("Expected response time")) return out("assertion", "The response took " + (tm2 != null ? tm2 + " ms" : "too long") + ", more than the test allows.", "The API may be slow or the limit too tight. The API tab shows every call's timing.", "matcher", "time");
                if (what.startsWith("Expected status code")) return out("assertion", cut(what.replaceFirst("[.:]$", ""), 120) + ".", "Check the response body in the API tab; the server usually says why.", "matcher", "statusCode");
                return out("assertion", cut(what, 140), "See the full message below and the API tab for the request.", "matcher", "restassured");
            }
            String expected = null, received = null;
            m = Pattern.compile("expected \\[(.*?)\\] but found \\[(.*?)\\]", Pattern.DOTALL).matcher(msg);            // TestNG
            if (m.find()) { expected = m.group(1); received = m.group(2); }
            if (expected == null) { m = Pattern.compile("expected:\\s*<(.*?)> but was:\\s*<(.*?)>", Pattern.DOTALL).matcher(msg); if (m.find()) { expected = m.group(1); received = m.group(2); } }   // JUnit
            if (expected == null) { m = Pattern.compile("[Ee]xpecting[^\\n]*\\n?\\s*<?\"?(.*?)\"?>?\\s*\\n?\\s*(?:to be equal to|but was)[:\\s]*<?\"?(.*?)\"?>?\\s*$", Pattern.DOTALL).matcher(msg); if (m.find() && has(msg, "but was|to be equal to")) { received = m.group(1).trim(); expected = m.group(2).trim(); } }  // AssertJ
            if (expected == null) { String e = pick(msg, "^Expected(?:[^:\\n]{0,40})?:[ \\t]*(.+)$"), r = pick(msg, "^(?:but: |Received|Actual)(?:[^:\\n]{0,40})?:?[ \\t]*(.+)$"); if (e != null && r != null) { expected = e; received = r; } }  // Hamcrest
            String custom = first.replaceFirst("\\s*expected \\[.*$", "").replaceFirst("\\s*==>.*$", "").replaceFirst("\\s*expected:.*$", "").trim();
            if (expected != null && received != null) {
                String who = !custom.isEmpty() && !custom.startsWith("expected") ? custom : "The value";
                String hint = expected.equalsIgnoreCase(received) && !expected.equals(received) ? "Only the letter case differs." : "Compare expected and received below. A copy change, a data change or a timing issue are the usual causes.";
                return out("assertion", cut(who, 80) + " was wrong: expected " + cut(expected, 80) + ", got " + cut(received, 80) + ".", hint);
            }
            if (hasI(msg, "expected \\[true\\] but found \\[false\\]|expected: <true> but was: <false>")) return out("assertion", (custom.isEmpty() ? "A condition" : cut(custom, 100)) + " was expected to be true but was false.", "Check the step just before this assertion.");
            return out("assertion", first.isEmpty() ? "An assertion did not pass." : cut(first, 140), "See the full message below for the expected and received values.");
        }

        // ── Network / navigation ───────────────────────────────────────────
        m = Pattern.compile("net::(ERR_[A-Z_]+)|NS_ERROR_([A-Z_]+)|Could not connect to server|ECONNREFUSED|Connection refused|ENOTFOUND|UnknownHostException|ETIMEDOUT|ECONNRESET|Connection reset|certificate|ERR_CERT|SocketTimeoutException|ConnectException", Pattern.CASE_INSENSITIVE).matcher(msg + " " + type);
        if (m.find()) {
            String code = (m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group()).toUpperCase(Locale.ROOT);
            String reason = code.contains("REFUSED") ? "nothing is listening on that address" : code.contains("NOT_RESOLVED") || code.contains("ENOTFOUND") || code.contains("UNKNOWNHOST") ? "the host name could not be resolved" : code.contains("CERT") ? "the TLS certificate was rejected" : code.contains("TIMED_OUT") || code.contains("ETIMEDOUT") || code.contains("SOCKETTIMEOUT") ? "the connection timed out" : code.contains("RESET") || code.contains("ABORTED") ? "the connection was dropped" : "the connection failed";
            if ((action != null && action.startsWith("apiRequestContext.")) || type.startsWith("java.net.") || type.startsWith("io.restassured"))
                return out("api", "The API request could not be sent: " + reason + " (" + code + ").", "Check the base URL and that the API is up. In CI, check that the service started before the tests.", "action", action, "url", url);
            return out("network", "The browser could not reach " + (url != null ? url : "the site") + ": " + reason + " (" + code + ").", "Check the base URL, that the app is running, and VPN or proxy settings. In CI, make sure the web server starts before the tests.", "action", action, "url", url);
        }
        if (action != null && action.startsWith("apiRequestContext.")) {
            if (has(msg, "Request timed out|Timeout \\d+ms exceeded")) return out("api", "The API request did not answer" + (timeoutMs != null ? " within " + secs(timeoutMs) : " in time") + ".", "The API may be slow or hanging. Check its logs, or raise the request timeout.", "action", action, "url", url, "timeoutMs", timeoutMs);
            return out("api", "The API call " + action + " failed: " + cut(first.replaceFirst("^Error: ", ""), 120), "See the full message below and the API tab for the request.", "action", action, "url", url);
        }
        if (action != null && has(action, "^(page|frame)\\.(navigate|goto|reload|goBack|goForward|waitForURL|waitForLoadState|waitForNavigation)$")) {
            if (hasI(msg, "Timeout \\d+ms exceeded|Navigation timeout")) return out("navigation", (url != null ? url : "The page") + " did not finish loading within " + (timeoutMs != null ? secs(timeoutMs) : "the timeout") + ".", "The app may be slow, stuck on a request, or redirecting in a loop. Try WaitUntilState.DOMCONTENTLOADED if the page keeps long-running requests open.", "action", action, "url", url, "timeoutMs", timeoutMs);
            return out("navigation", action + " failed: " + cut(first.replaceFirst("^(Error|TimeoutError): ", ""), 120), "See the full message below.", "action", action, "url", url);
        }

        // ── Closed / detached ─────────────────────────────────────────────
        if (hasI(msg, "Target page, context or browser has been closed|Target closed|Browser has been closed|browser has disconnected|Page closed|Context closed|Playwright connection closed"))
            return out("closed", "The browser or page was closed before this step could run.", "A previous step closed it, the test ended early, or the browser crashed. Look at the step just before this one.", "action", action);
        if (has(msg, "Execution context was destroyed|most likely because of a navigation")) return out("detached", "The page navigated away while this step was running.", "Wait for the navigation to finish (page.waitForURL) before touching the page.", "action", action, "locator", locator);
        if (hasI(msg, "not attached to the DOM|element was detached|Element is not attached")) return out("detached", (locator != null ? locator : "The element") + " was removed from the page while Playwright was using it.", "The UI re-rendered the element. Re-locate it after the change, or wait for the update to finish.", "action", action, "locator", locator);

        // ── Playwright for Java time-outs: the message carries no call log, so
        //    the action comes from the stack and the locator from the source line.
        if (pwTimeout && !has(msg, "waiting for|intercepts|not visible|not enabled")) {
            String who = locator != null ? locator : "The element";
            String tmo = timeoutMs != null ? " within " + secs(timeoutMs) : " in time";
            String act = action != null ? action : "the action";
            if (action != null && has(action, "\\.(navigate|goto|reload|goBack|goForward|waitForURL|waitForLoadState)$"))
                return out("navigation", (url != null ? url : "The page") + " did not finish loading" + tmo + ".", "The app may be slow, stuck on a request, or redirecting in a loop. Try WaitUntilState.DOMCONTENTLOADED if the page keeps long-running requests open.", "action", action, "url", url, "timeoutMs", timeoutMs);
            if (action != null && has(action, "\\.(waitFor|waitForSelector)$"))
                return out("not-found", who + " did not reach the expected state" + tmo + ".", "The element may still be loading, be hidden by CSS, or sit inside a closed menu or dialog. Check the selector and the step before this one.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            if (action != null && has(action, "\\.(waitForResponse|waitForRequest|waitForEvent|waitForFunction|waitForCondition)$"))
                return out("test-timeout", act + " did not get what it was waiting for" + tmo + ".", "The request, event or condition never happened. Check the step that should trigger it.", "action", action, "timeoutMs", timeoutMs);
            return out("not-found", who + " was not ready" + tmo + ", so " + act + " could not run.", "It was not on the page, or not visible, enabled and stable. Check the selector, and whether the element is inside an iframe, behind a login, or only shown after another step. The trace shows what the page looked like.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
        }

        // ── Playwright action time-outs ───────────────────────────────────
        if (has(msg, "strict mode violation")) { String l = pick(msg, "strict mode violation: (.+?) resolved to"); String n = pick(msg, "resolved to (\\d+) elements"); return out("ambiguous", cut(l != null ? l : "The selector", 100) + " matched " + (n != null ? n : "several") + " elements, Playwright needs exactly one.", "Make the selector more specific, or pick one with .first(), .nth(i) or a filter such as setHasText.", "locator", l); }
        if (has(msg, "Timeout \\d+ms exceeded") && (action != null || locator != null)) {
            String tmo = timeoutMs != null ? " for " + secs(timeoutMs) : "";
            String who = locator != null ? locator : "The element";
            if (has(msg, "intercepts pointer events")) return out("blocked", who + " was there, but another element was covering it, so the action never landed.", "A modal, cookie banner, toast or loading overlay is on top. Close it first, or wait for it to disappear.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            if (has(msg, "element is not visible")) return out("not-visible", who + " exists but stayed hidden" + tmo + ".", "It may be inside a closed menu, collapsed section or hidden tab, or hidden by CSS. Open the container first.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            if (has(msg, "element is not enabled|is disabled")) return out("disabled", who + " stayed disabled" + tmo + ".", "A form may be invalid or still loading. Fill the required fields or wait for the button to enable.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            if (has(msg, "outside of the viewport")) return out("not-visible", who + " was outside the visible area" + tmo + ".", "Scroll it into view, or check for a fixed layout that keeps it off screen.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            if (has(msg, "not an? <input>|Element is not an")) return out("wrong-element", who + " is not the kind of element this action works on.", "For example fill() needs an <input> or <textarea>. Check the selector points at the right element.", "action", action, "locator", locator);
            if (has(msg, "waiting for element to be visible, enabled and stable")) return out("not-visible", who + " was found but never became ready (visible, enabled and stable)" + (timeoutMs != null ? " within " + secs(timeoutMs) : "") + ".", "The element may be animating, hidden, or disabled. Wait for the animation or the loading state to finish.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            if (has(msg, "waiting for")) return out("not-found", who + " was not on the page" + (timeoutMs != null ? " within " + secs(timeoutMs) : "") + ", so " + (action != null ? action : "the action") + " could not run.", "Check the selector. The element may be inside an iframe, behind a login, or only shown after another step.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
            return out("not-found", (action != null ? action : "The action") + " did not complete" + (timeoutMs != null ? " within " + secs(timeoutMs) : "") + (locator != null ? " on " + locator : "") + ".", "See the call log below for what Playwright was waiting on.", "action", action, "locator", locator, "timeoutMs", timeoutMs);
        }

        // ── Files and code errors ─────────────────────────────────────────
        if (type.endsWith("NoSuchFileException") || type.endsWith("FileNotFoundException") || has(msg, "ENOENT|No such file")) return out("file", "A file the test needs is missing" + (first.isEmpty() ? "" : ": " + cut(first, 100)) + ".", "Check the path, and that the file is committed or generated before the run.");
        if (type.startsWith("java.lang.")) {
            String simple = t.getClass().getSimpleName();
            String hint = simple.equals("NullPointerException") ? "Something was null at that point: an API response, a page object field, a config value."
                : simple.equals("ClassCastException") ? "An object was not of the type the code expected."
                : simple.contains("IndexOutOfBounds") ? "A list or array was shorter than the code assumed. Check the data the test received."
                : simple.equals("IllegalArgumentException") || simple.equals("IllegalStateException") ? "A helper was called with the wrong input or at the wrong time."
                : simple.equals("NumberFormatException") ? "A value that should be a number was not. Check the data source."
                : "This is a bug in the test or a helper, not in the app.";
            return out("script", simple + " in the test code" + (msg.equals(simple) ? "." : ": " + cut(first, 120)), hint);
        }
        if (type.startsWith("java.")) return out("thrown", cut(t.getClass().getSimpleName() + ": " + first, 140), "See the full message and stack trace below.");
        return out("thrown", cut(first, 140), "The test (or a helper) threw this error on purpose or via a failed check. The stack trace below points at the line.");
    }
}
