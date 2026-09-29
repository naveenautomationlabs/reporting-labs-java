package dev.reportinglabs.playwright;

import dev.reportinglabs.core.internal.Json;
import dev.reportinglabs.core.internal.RlInternal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns the actions in a Playwright trace into report steps. Playwright
 * writes one "before" and one "after" line per API call into
 * {@code trace.trace} (class, method, params, start and end time, error),
 * which is the same record the trace viewer shows. Reading it back gives
 * every click, fill and navigation the test made, in order, with timing and
 * the failing one marked, without wrapping the Page or the Locator: those
 * are cast to their implementation classes inside Playwright's assertions,
 * so a proxy would break {@code assertThat(page)}.
 */
final class TraceSteps {
    private static final Pattern SENSITIVE = Pattern.compile("pass|pwd|secret|token|otp|pin|cvv|card", Pattern.CASE_INSENSITIVE);
    private static final Set<String> SKIP_CLASSES = new HashSet<>(Arrays.asList("Tracing", "Browser", "BrowserType", "Playwright", "APIRequestContext"));
    private static final Set<String> SKIP_METHODS = new HashSet<>(Arrays.asList("close", "setDefaultTimeout", "setDefaultNavigationTimeout", "route", "unroute", "on", "off", "once", "exposeFunction", "exposeBinding", "addInitScript", "setExtraHTTPHeaders", "setViewportSize", "emulateMedia", "bringToFront", "pause", "video", "context", "frames", "mainFrame", "url", "title", "isClosed"));

    private TraceSteps() {}

    static void record(Path zip, boolean dropOwnScreenshot) {
        List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Map<String, Object>> open = new HashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(z.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry e : entries) {
                if (!e.getName().endsWith(".trace")) continue;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        Object o;
                        try { o = Json.parse(line); } catch (Throwable t) { continue; }
                        if (!(o instanceof Map)) continue;
                        @SuppressWarnings("unchecked") Map<String, Object> ev = (Map<String, Object>) o;
                        String type = str(ev.get("type"));
                        String id = str(ev.get("callId"));
                        if ("before".equals(type) && id != null) { open.put(id, ev); calls.add(ev); }
                        else if ("after".equals(type) && id != null) {
                            Map<String, Object> b = open.remove(id);
                            if (b != null) { b.put("_end", ev.get("endTime")); b.put("_error", ev.get("error")); }
                        }
                    }
                }
            }
        } catch (Throwable ignore) { return; }
        if (dropOwnScreenshot && !calls.isEmpty()) {
            Map<String, Object> last = calls.get(calls.size() - 1);
            if ("screenshot".equals(str(last.get("method")))) calls.remove(calls.size() - 1);
        }
        for (Map<String, Object> c : calls) {
            String cls = str(c.get("class")), method = str(c.get("method"));
            if (cls == null || method == null || SKIP_CLASSES.contains(cls) || SKIP_METHODS.contains(method)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> params = c.get("params") instanceof Map ? (Map<String, Object>) c.get("params") : Collections.emptyMap();
            String title = describe(method, params);
            if (title == null) continue;
            long start = num(c.get("startTime")), end = num(c.get("_end"));
            long duration = end > 0 && start > 0 ? end - start : 0;
            String error = null;
            if (c.get("_error") instanceof Map) error = firstLine(str(((Map<?, ?>) c.get("_error")).get("message")));
            RlInternal.recordStep(title, "pw:api", duration, error);
        }
    }

    /** A human title for one call, or null for calls not worth a step. */
    static String describe(String method, Map<String, Object> params) {
        String sel = selector(str(params.get("selector")));
        switch (method) {
            case "goto":            return "navigate to " + str(params.get("url"));
            case "goBack":          return "navigate back";
            case "goForward":       return "navigate forward";
            case "reload":          return "reload";
            case "click":           return "click " + sel;
            case "dblclick":        return "double-click " + sel;
            case "tap":             return "tap " + sel;
            case "hover":           return "hover " + sel;
            case "focus":           return "focus " + sel;
            case "check":           return "check " + sel;
            case "uncheck":         return "uncheck " + sel;
            case "clear":           return "clear " + sel;
            case "fill":            return "fill " + sel + " with " + value(sel, str(params.get("value")));
            case "type":            return "type " + value(sel, str(params.get("text"))) + " into " + sel;
            case "pressSequentially": return "type " + value(sel, str(params.get("text"))) + " into " + sel;
            case "press":           return "press " + str(params.get("key")) + " on " + sel;
            case "selectOption":    return "select " + options(params) + " in " + sel;
            case "setInputFiles":   return "upload files to " + sel;
            case "dragAndDrop":     return "drag " + selector(str(params.get("source"))) + " to " + selector(str(params.get("target")));
            case "waitForSelector": return "wait for " + sel;
            case "waitForTimeout":  return "wait " + str(params.get("waitTimeout") != null ? params.get("waitTimeout") : params.get("timeout")) + " ms";
            case "waitForLoadState": return "wait for " + (params.get("state") == null ? "load" : str(params.get("state")));
            case "waitForURL":      return "wait for URL " + str(params.get("url"));
            case "waitForNavigation": return "wait for navigation";
            case "textContent":     return "read text of " + sel;
            case "innerText":       return "read inner text of " + sel;
            case "innerHTML":       return "read HTML of " + sel;
            case "inputValue":      return "read value of " + sel;
            case "getAttribute":    return "read attribute " + str(params.get("name")) + " of " + sel;
            case "isVisible":       return "is " + sel + " visible";
            case "isHidden":        return "is " + sel + " hidden";
            case "isEnabled":       return "is " + sel + " enabled";
            case "isDisabled":      return "is " + sel + " disabled";
            case "isChecked":       return "is " + sel + " checked";
            case "isEditable":      return "is " + sel + " editable";
            case "count": case "queryCount": return "count " + sel;
            case "boundingBox":     return "bounding box of " + sel;
            case "querySelector": case "querySelectorAll": return "find " + sel;
            case "screenshot":      return "screenshot";
            case "evaluate": case "evaluateHandle": case "evalOnSelector": case "evalOnSelectorAll": return "evaluate script" + (sel.isEmpty() ? "" : " on " + sel);
            case "expect":          return expect(sel, params);
            case "setContent":      return "set page content";
            case "content":         return "read page content";
            default:                return sel.isEmpty() ? null : method + " " + sel;
        }
    }

    private static String expect(String sel, Map<String, Object> params) {
        String expr = str(params.get("expression"));
        if (expr == null) return "expect " + sel;
        boolean not = Boolean.TRUE.equals(params.get("isNot"));
        String what = expr.replace("to.", "to ").replace("be.", "be ").replace("have.", "have ").replace('.', ' ');
        if (not) what = what.replaceFirst("^to ", "not to ");
        StringBuilder sb = new StringBuilder("expect ").append(sel.isEmpty() ? "page" : sel).append(' ').append(what);
        Object expected = params.get("expectedText");
        if (expected instanceof List && !((List<?>) expected).isEmpty()) {
            List<String> vals = new ArrayList<>();
            for (Object o : (List<?>) expected) {
                if (o instanceof Map) { Object v = ((Map<?, ?>) o).get("string"); if (v == null) v = ((Map<?, ?>) o).get("regexSource"); if (v != null) vals.add(quote(String.valueOf(v))); }
            }
            if (!vals.isEmpty()) sb.append(' ').append(String.join(", ", vals));
        } else if (params.get("expectedValue") != null) {
            sb.append(' ').append(quote(String.valueOf(params.get("expectedValue"))));
        } else if (params.get("expectedNumber") != null) {
            sb.append(' ').append(str(params.get("expectedNumber")));
        }
        return sb.toString();
    }

    private static String options(Map<String, Object> params) {
        Object o = params.get("options");
        if (!(o instanceof List)) return "option";
        List<String> vals = new ArrayList<>();
        for (Object item : (List<?>) o) {
            if (item instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) item;
                Object v = m.get("valueOrLabel") != null ? m.get("valueOrLabel") : m.get("label") != null ? m.get("label") : m.get("value") != null ? m.get("value") : m.get("index");
                if (v != null) vals.add(quote(String.valueOf(v)));
            } else vals.add(quote(String.valueOf(item)));
        }
        return vals.isEmpty() ? "option" : String.join(", ", vals);
    }

    private static String value(String sel, String v) {
        if (v == null) return "\"\"";
        if (SENSITIVE.matcher(sel).find()) return "••••";
        return quote(v.length() > 60 ? v.substring(0, 60) + "…" : v);
    }

    private static String quote(String v) { return "\"" + v + "\""; }

    /** Playwright's internal selector syntax back to what the test wrote. */
    static String selector(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (String part : raw.split(" >> ")) {
            String p = part.trim();
            if (p.startsWith("internal:role=")) {
                Matcher m = Pattern.compile("internal:role=([\\w-]+)(?:\\[name=\"(.*?)\"(i?)\\])?").matcher(p);
                if (m.find()) { p = "getByRole(\"" + m.group(1) + "\"" + (m.group(2) != null ? ", name \"" + m.group(2) + "\"" : "") + ")"; }
            } else if (p.startsWith("internal:text=")) {
                p = "getByText(" + strip(p.substring("internal:text=".length())) + ")";
            } else if (p.startsWith("internal:label=")) {
                p = "getByLabel(" + strip(p.substring("internal:label=".length())) + ")";
            } else if (p.startsWith("internal:attr=[placeholder=")) {
                p = "getByPlaceholder(" + strip(p.substring("internal:attr=[placeholder=".length()).replaceAll("\\]$", "")) + ")";
            } else if (p.startsWith("internal:attr=[alt=")) {
                p = "getByAltText(" + strip(p.substring("internal:attr=[alt=".length()).replaceAll("\\]$", "")) + ")";
            } else if (p.startsWith("internal:attr=[title=")) {
                p = "getByTitle(" + strip(p.substring("internal:attr=[title=".length()).replaceAll("\\]$", "")) + ")";
            } else if (p.startsWith("internal:testid=")) {
                Matcher m = Pattern.compile("\\[[\\w-]+=(\".*?\")").matcher(p);
                p = "getByTestId(" + (m.find() ? m.group(1) : p.substring("internal:testid=".length())) + ")";
            } else if (p.startsWith("internal:has-text=")) {
                p = "hasText " + strip(p.substring("internal:has-text=".length()));
            } else if (p.startsWith("internal:has=")) {
                p = "has " + selector(strip(p.substring("internal:has=".length())));
            } else if (p.startsWith("internal:control=")) {
                continue;
            } else if (p.startsWith("nth=")) {
                String n = p.substring(4);
                p = "0".equals(n) ? "first()" : "-1".equals(n) ? "last()" : "nth(" + n + ")";
            } else if (p.startsWith("internal:")) {
                p = p.substring("internal:".length());
            }
            if (out.length() > 0) out.append(" > ");
            out.append(p);
        }
        return out.toString();
    }

    /** "text"i -> "text"; strip the case-insensitivity flag and outer quotes. */
    private static String strip(String s) {
        String v = s.trim();
        if (v.endsWith("i") && v.length() > 1 && v.charAt(v.length() - 2) == '"') v = v.substring(0, v.length() - 1);
        if (v.endsWith("s") && v.length() > 1 && v.charAt(v.length() - 2) == '"') v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        int i = s.indexOf('\n');
        return (i > 0 ? s.substring(0, i) : s).trim();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static long num(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return o == null ? 0 : (long) Double.parseDouble(String.valueOf(o)); } catch (Throwable t) { return 0; }
    }
}
