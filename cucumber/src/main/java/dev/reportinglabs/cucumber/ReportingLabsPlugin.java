package dev.reportinglabs.cucumber;

import dev.reportinglabs.core.internal.RlInternal;
import dev.reportinglabs.core.internal.ShutdownWriter;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.*;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cucumber JVM plugin: every scenario becomes one row named after the
 * scenario, pointing at the feature file and line, with the Gherkin steps
 * (Given/When/Then), Cucumber hooks, data tables and doc strings in the
 * test detail, and feature/scenario tags as tags and meta.
 *
 * Register it once:
 * <pre>
 *   # src/test/resources/cucumber.properties
 *   cucumber.plugin=dev.reportinglabs.cucumber.ReportingLabsPlugin
 * </pre>
 * or {@code @CucumberOptions(plugin = "dev.reportinglabs.cucumber.ReportingLabsPlugin")}.
 *
 * With the TestNG runner (AbstractTestNGCucumberTests) the TestNG listener
 * already opens a row per scenario; the plugin renames it. With the JUnit
 * Platform engine, or the CLI, the plugin opens and closes the row itself.
 */
public final class ReportingLabsPlugin implements ConcurrentEventListener {

    /** Rows this plugin opened itself (JUnit Platform engine, CLI), by test case id. */
    private static final Set<UUID> OWNED = ConcurrentHashMap.newKeySet();
    private final Map<Object, RlInternal.Step> open = new ConcurrentHashMap<>();
    private final Map<URI, List<String>> featureLines = new ConcurrentHashMap<>();
    private final Map<UUID, String> undefinedStep = new ConcurrentHashMap<>();

    public ReportingLabsPlugin() { ShutdownWriter.install(); }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, this::onCaseStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::onStepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onStepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
    }

    private void onCaseStarted(TestCaseStarted e) {
        TestCase tc = e.getTestCase();
        String file = relative(tc.getUri());
        int line = tc.getLocation() != null ? tc.getLocation().getLine() : 0;
        List<String> path = Collections.singletonList(featureName(file));
        String title = tc.getName();
        // A Scenario Outline row: Cucumber names every example after the
        // outline, so add the example values and pin them as data.
        Example ex = exampleAt(tc.getUri(), line);
        if (ex != null && !ex.values.isEmpty()) {
            String vals = String.join(", ", ex.values);
            title = title + " (" + (vals.length() > 60 ? vals.substring(0, 60) + "…" : vals) + ")";
        }
        if (RlInternal.current() == null) {
            RlInternal.begin(title, file, line, "cucumber", path);
            OWNED.add(tc.getId());
        } else {
            // TestNG runner: the row exists as "Runs Cucumber Scenarios" with
            // the pickle as a Parameters block. Make it the scenario.
            RlInternal.relocate(title, file, line, path);
            RlInternal.removeData("Parameters");
        }
        for (String raw : tc.getTags()) applyTag(raw);
        if (ex != null && !ex.values.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < ex.values.size(); i++) row.put(i < ex.header.size() ? ex.header.get(i) : "col" + (i + 1), ex.values.get(i));
            RlInternal.testData("Examples", Collections.singletonList(row));
        }
    }

    private void onStepStarted(TestStepStarted e) {
        TestStep ts = e.getTestStep();
        if (ts instanceof HookTestStep) {
            HookType t = ((HookTestStep) ts).getHookType();
            boolean before = t == HookType.BEFORE || t == HookType.BEFORE_STEP;
            String name = t == HookType.BEFORE ? "@Before" : t == HookType.AFTER ? "@After" : t == HookType.BEFORE_STEP ? "@BeforeStep" : "@AfterStep";
            String code = ts.getCodeLocation();
            if (code != null) { int i = code.lastIndexOf('.'); int p = code.indexOf('('); if (i > 0 && p > i) code = code.substring(i + 1, p); else code = null; }
            if (t == HookType.BEFORE_STEP || t == HookType.AFTER_STEP) {
                // Step-level hooks nest under the running Gherkin step.
                open.put(ts, RlInternal.stepBegin(name + (code != null ? " " + code : ""), "hook"));
            } else {
                open.put(ts, RlInternal.hookBegin(before, name + (code != null ? " " + code : "")));
            }
            return;
        }
        if (ts instanceof PickleStepTestStep) {
            PickleStepTestStep ps = (PickleStepTestStep) ts;
            Step step = ps.getStep();
            String keyword = step.getKeyword() == null ? "" : step.getKeyword().trim();
            open.put(ts, RlInternal.stepBegin((keyword.isEmpty() ? "" : keyword + " ") + step.getText(), "test.step"));
            StepArgument arg = step.getArgument();
            if (arg instanceof DataTableArgument) {
                List<List<String>> cells = ((DataTableArgument) arg).cells();
                if (cells != null && cells.size() > 1) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (int r = 1; r < cells.size(); r++) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int c = 0; c < cells.get(0).size(); c++) row.put(cells.get(0).get(c), c < cells.get(r).size() ? cells.get(r).get(c) : "");
                        rows.add(row);
                    }
                    RlInternal.testData(step.getText(), rows);
                } else if (cells != null && cells.size() == 1) {
                    RlInternal.testData(step.getText(), String.join(" | ", cells.get(0)));
                }
            } else if (arg instanceof DocStringArgument) {
                RlInternal.testData(step.getText(), ((DocStringArgument) arg).getContent());
            }
        }
    }

    private void onStepFinished(TestStepFinished e) {
        TestStep ts = e.getTestStep();
        RlInternal.Step s = open.remove(ts);
        if (s == null) return;
        Result r = e.getResult();
        Status st = r.getStatus();
        boolean scenarioHook = ts instanceof HookTestStep && (((HookTestStep) ts).getHookType() == HookType.BEFORE || ((HookTestStep) ts).getHookType() == HookType.AFTER);
        if (st == Status.SKIPPED && !scenarioHook && r.getError() == null) { RlInternal.stepSkip(s); return; }
        Throwable err = r.getError();
        if (ts instanceof PickleStepTestStep && (st == Status.UNDEFINED || st == Status.PENDING)) {
            Step step = ((PickleStepTestStep) ts).getStep();
            String text = step.getText();
            String file = relative(e.getTestCase().getUri());
            int line = step.getLine();
            if (err == null) err = new UndefinedStepError(st == Status.UNDEFINED
                ? "The step '" + text + "' is undefined. Write a step definition for it (Cucumber printed a snippet in the console)."
                : "The step '" + text + "' is pending: its step definition throws PendingException.");
            undefinedStep.put(e.getTestCase().getId(), err.getMessage());
            RlInternal.errorAt(file, line, snippet(e.getTestCase().getUri(), line));
        }
        if (scenarioHook) RlInternal.hookEnd(s, err); else RlInternal.stepEnd(s, err);
    }

    private void onCaseFinished(TestCaseFinished e) {
        TestCase tc = e.getTestCase();
        if (!OWNED.remove(tc.getId())) return;   // the TestNG listener closes its own row
        Result r = e.getResult();
        Status st = r.getStatus();
        String undefined = undefinedStep.remove(tc.getId());
        if (st == Status.SKIPPED) { RlInternal.end(r.getError(), true, r.getError() != null ? r.getError().getMessage() : "skipped"); return; }
        Throwable err = r.getError();
        if (err == null && (st == Status.UNDEFINED || st == Status.PENDING)) err = new UndefinedStepError(undefined != null ? undefined : "Undefined step");
        if (err == null && st != Status.PASSED) err = new UndefinedStepError("Scenario " + st.name().toLowerCase(Locale.ROOT));
        RlInternal.end(err, false);
    }

    /** A scenario that failed without an exception of its own: a step with
     *  no step definition, or one still throwing PendingException. */
    public static final class UndefinedStepError extends RuntimeException {
        UndefinedStepError(String message) { super(message); }
    }

    // ---- feature file helpers ----

    private static final class Example { final List<String> header = new ArrayList<>(); final List<String> values = new ArrayList<>(); }

    /** If {@code line} is a row of an Examples table, its header and values. */
    private Example exampleAt(URI uri, int line) {
        List<String> ls = lines(uri);
        if (line <= 0 || line > ls.size() || !ls.get(line - 1).trim().startsWith("|")) return null;
        int top = line;
        while (top > 1 && ls.get(top - 2).trim().startsWith("|")) top--;
        if (top == line) return null;                      // a lone row is the header itself
        Example ex = new Example();
        ex.header.addAll(cells(ls.get(top - 1)));
        ex.values.addAll(cells(ls.get(line - 1)));
        return ex;
    }

    private static List<String> cells(String row) {
        String t = row.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> out = new ArrayList<>();
        for (String c : t.split("\\|")) out.add(c.trim());
        return out;
    }

    private String snippet(URI uri, int line) {
        List<String> ls = lines(uri);
        if (line <= 0 || line > ls.size()) return null;
        int from = Math.max(1, line - 3), to = Math.min(ls.size(), line + 3);
        int width = String.valueOf(to).length();
        StringBuilder sb = new StringBuilder();
        for (int n = from; n <= to; n++) sb.append(n == line ? "> " : "  ").append(String.format("%" + width + "d", n)).append(" | ").append(ls.get(n - 1)).append('\n');
        return sb.toString();
    }

    private List<String> lines(URI uri) {
        if (uri == null) return Collections.emptyList();
        return featureLines.computeIfAbsent(uri, u -> {
            try {
                if ("file".equals(u.getScheme())) return java.nio.file.Files.readAllLines(Paths.get(u));
                String rel = relative(u);
                for (String root : new String[] { "src/test/resources", "src/main/resources", "" }) {
                    Path p = Paths.get(root, rel);
                    if (java.nio.file.Files.isRegularFile(p)) return java.nio.file.Files.readAllLines(p);
                }
                java.io.InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(rel);
                if (in != null) try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                    List<String> out = new ArrayList<>(); String l; while ((l = br.readLine()) != null) out.add(l); return out;
                }
            } catch (Throwable ignore) { /* decoration only */ }
            return Collections.emptyList();
        });
    }

    /** @P1 -> priority, @blocker -> severity, @owner:naveen / @owner=naveen -> meta, anything else -> tag. */
    private static void applyTag(String raw) {
        String t = raw.startsWith("@") ? raw.substring(1) : raw;
        if (t.isEmpty()) return;
        if (t.matches("(?i)P[0-4]")) { RlInternal.meta("priority", t.toUpperCase(Locale.ROOT)); return; }
        if (t.matches("(?i)blocker|critical|major|normal|minor|trivial")) { RlInternal.meta("severity", t.toLowerCase(Locale.ROOT)); return; }
        int i = Math.max(t.indexOf(':'), t.indexOf('='));
        if (i > 0 && i < t.length() - 1) { RlInternal.meta(t.substring(0, i), t.substring(i + 1)); return; }
        RlInternal.tag(t);
    }

    private static String relative(URI uri) {
        if (uri == null) return "features";
        try {
            if ("file".equals(uri.getScheme())) {
                Path p = Paths.get(uri).toAbsolutePath();
                Path cwd = Paths.get(System.getProperty("user.dir", "")).toAbsolutePath();
                return (p.startsWith(cwd) ? cwd.relativize(p) : p).toString().replace('\\', '/');
            }
            // classpath:features/x.feature -> the source file when it is in the usual place,
            // so the row (and its history key) matches the TestNG runner's.
            String s = uri.getSchemeSpecificPart();
            if (s.startsWith("//")) s = s.substring(2);
            if (s.startsWith("/")) s = s.substring(1);
            for (String root : new String[] { "src/test/resources/", "src/main/resources/" })
                if (java.nio.file.Files.isRegularFile(Paths.get(root + s))) return root + s;
            return s;
        } catch (Throwable t) { return String.valueOf(uri); }
    }

    private static String featureName(String file) {
        String n = file.substring(file.lastIndexOf('/') + 1);
        return n.endsWith(".feature") ? n.substring(0, n.length() - 8) : n;
    }
}
