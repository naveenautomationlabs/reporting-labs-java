package dev.reportinglabs.core.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the source file of a test class under the usual Maven / Gradle
 * roots and answers "which line is this method on" and "show me the code
 * around line N". Frameworks don't hand us line numbers, so this is what
 * gives the report a real {@code OrdersApiTest.java:42} and a code snippet
 * on failures, like the Node reporter has. Best effort: a class that isn't
 * found simply gets line 0 and no snippet.
 */
public final class SourceLocator {

    private SourceLocator() {}

    private static final String[] ROOTS = {
        "src/test/java", "src/main/java", "src/it/java", "src/integrationTest/java",
        "src/test/kotlin", "src/test/groovy", "src/main/kotlin",
    };
    private static final Path NONE = Paths.get("");
    private static final Map<String, Path> FILES = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> LINES = new ConcurrentHashMap<>();

    /** Absolute path of the class's source file, or null. Cached. */
    public static Path fileOf(Class<?> cls) {
        if (cls == null) return null;
        String name = cls.getName();
        int inner = name.indexOf('$');
        if (inner > 0) name = name.substring(0, inner);
        Path p = FILES.computeIfAbsent(name, SourceLocator::find);
        return p == NONE ? null : p;
    }

    /** Path relative to the working directory, for the report. */
    public static String relativeFile(Class<?> cls) {
        Path p = fileOf(cls);
        if (p == null) return cls == null ? "" : cls.getSimpleName() + ".java";
        Path cwd = Paths.get(System.getProperty("user.dir", "")).toAbsolutePath();
        try { return cwd.relativize(p.toAbsolutePath()).toString().replace('\\', '/'); }
        catch (IllegalArgumentException e) { return p.getFileName().toString(); }
    }

    private static Path find(String className) {
        String rel = className.replace('.', '/');
        Path cwd = Paths.get(System.getProperty("user.dir", "")).toAbsolutePath();
        // The working dir, then up to two parents (multi-module builds run from the root).
        Path dir = cwd;
        for (int up = 0; up < 3 && dir != null; up++, dir = dir.getParent()) {
            for (String root : ROOTS) {
                for (String ext : new String[] { ".java", ".kt", ".groovy" }) {
                    Path p = dir.resolve(root).resolve(rel + ext);
                    if (Files.isRegularFile(p)) return p;
                }
            }
            // Sibling modules: <root>/*/src/test/java/...
            try (DirectoryStream<Path> mods = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path mod : mods) {
                    for (String root : ROOTS) {
                        Path p = mod.resolve(root).resolve(rel + ".java");
                        if (Files.isRegularFile(p)) return p;
                    }
                }
            } catch (IOException | RuntimeException ignore) { /* keep looking */ }
        }
        return NONE;
    }

    private static List<String> lines(Path p) {
        return LINES.computeIfAbsent(p.toString(), k -> {
            try { return Files.readAllLines(p, StandardCharsets.UTF_8); }
            catch (IOException | RuntimeException e) { return Collections.emptyList(); }
        });
    }

    /** 1-based line of the method's declaration, or 0. */
    public static int lineOf(Class<?> cls, String method) {
        Path p = fileOf(cls);
        if (p == null || method == null) return 0;
        // Annotations may sit on the same line: "@Test public void x()".
        Pattern decl = Pattern.compile("^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|synchronized|default|abstract)\\s+)*[\\w$<>\\[\\],.? ]+\\s+" + Pattern.quote(method) + "\\s*\\(");
        List<String> ls = lines(p);
        for (int i = 0; i < ls.size(); i++) {
            String l = ls.get(i);
            if (l.trim().startsWith("//") || l.trim().startsWith("*")) continue;
            if (decl.matcher(l).find()) return i + 1;
        }
        return 0;
    }

    /** A few lines of code around {@code line}, the failing one marked with
     *  {@code >}: the same shape Playwright prints. Null when unavailable. */
    public static String snippet(Class<?> cls, int line) {
        Path p = fileOf(cls);
        if (p == null || line <= 0) return null;
        List<String> ls = lines(p);
        if (line > ls.size()) return null;
        int from = Math.max(1, line - 3), to = Math.min(ls.size(), line + 3);
        int width = String.valueOf(to).length();
        StringBuilder sb = new StringBuilder();
        for (int n = from; n <= to; n++) {
            String num = String.format("%" + width + "d", n);
            sb.append(n == line ? "> " : "  ").append(num).append(" | ").append(ls.get(n - 1)).append('\n');
        }
        return sb.toString();
    }

    /** One source line (1-based), or null. */
    public static String line(Class<?> cls, int line) {
        Path p = fileOf(cls);
        if (p == null || line <= 0) return null;
        List<String> ls = lines(p);
        return line <= ls.size() ? ls.get(line - 1) : null;
    }

    /** The Playwright call the test was in when it failed, read from the
     *  stack: the impl frame right above the test's own frame, e.g.
     *  LocatorImpl.click -> "locator.click". Null when not a Playwright failure. */
    public static String playwrightAction(Throwable t, Class<?> testClass) {
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            StackTraceElement[] st = x.getStackTrace();
            String last = null;
            for (StackTraceElement el : st) {
                String c = el.getClassName();
                if (c.startsWith("com.microsoft.playwright.impl.") && c.endsWith("Impl")) {
                    String owner = c.substring(c.lastIndexOf('.') + 1).replaceAll("Impl$", "");
                    owner = Character.toLowerCase(owner.charAt(0)) + owner.substring(1);
                    if (!el.getMethodName().endsWith("Impl") && !el.getMethodName().startsWith("lambda$") && !el.getMethodName().startsWith("with"))
                        last = owner + "." + el.getMethodName();
                } else if (last != null && !isFramework(c)) {
                    return last;   // first user frame after the Playwright frames
                }
            }
            if (last != null) return last;
        }
        return null;
    }

    private static final Pattern LOCATOR_CALL = Pattern.compile("((?:locator|getByRole|getByText|getByLabel|getByPlaceholder|getByTestId|getByTitle|getByAltText|frameLocator)\\((?:[^()]|\\([^()]*\\))*\\)(?:\\.(?:first|last|nth|filter|and|or|locator|getBy\\w+)\\((?:[^()]|\\([^()]*\\))*\\))*)");
    private static final Pattern STRING_ARG = Pattern.compile("\\.(?:click|fill|type|check|uncheck|hover|focus|press|selectOption|dblclick|tap|waitForSelector|innerText|textContent|isVisible|isEnabled|isChecked|setInputFiles|dispatchEvent)\\(\\s*\"([^\"]+)\"");

    /** The locator written on a source line: locator("#x"), getByRole(...)
     *  chains, or the selector string of page.click("#x"). Null when none. */
    public static String locatorIn(String sourceLine) {
        if (sourceLine == null) return null;
        Matcher m = LOCATOR_CALL.matcher(sourceLine);
        if (m.find()) return m.group(1);
        m = STRING_ARG.matcher(sourceLine);
        if (m.find()) return "\"" + m.group(1) + "\"";
        return null;
    }

    /** The frame of the failure that lives in the test class (or, failing
     *  that, the first frame outside frameworks and the JDK). */
    public static StackTraceElement frameIn(Throwable t, Class<?> testClass) {
        StackTraceElement fallback = null;
        for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
            for (StackTraceElement el : x.getStackTrace()) {
                String c = el.getClassName();
                if (testClass != null && c.equals(testClass.getName())) return el;
                if (fallback == null && el.getLineNumber() > 0 && !isFramework(c)) fallback = el;
            }
        }
        return fallback;
    }

    private static boolean isFramework(String c) {
        return c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("jdk.") || c.startsWith("sun.")
            || c.startsWith("org.testng.") || c.startsWith("org.junit.") || c.startsWith("org.apache.maven.")
            || c.startsWith("org.gradle.") || c.startsWith("dev.reportinglabs.") || c.startsWith("com.microsoft.playwright.")
            || c.startsWith("org.openqa.") || c.startsWith("io.restassured.") || c.startsWith("org.hamcrest.")
            || c.startsWith("org.assertj.") || c.startsWith("net.bytebuddy.") || c.startsWith("kotlin.")
            || c.startsWith("io.cucumber.") || c.startsWith("org.picocontainer.") || c.startsWith("org.opentest4j.")
            || c.startsWith("org.codehaus.groovy.") || c.startsWith("org.apache.groovy.") || c.startsWith("groovy.");
    }

    /** Resolve a stack frame's class, or null. */
    public static Class<?> classOf(StackTraceElement el) {
        if (el == null) return null;
        try { return Class.forName(el.getClassName(), false, Thread.currentThread().getContextClassLoader()); }
        catch (Throwable t) { try { return Class.forName(el.getClassName()); } catch (Throwable u) { return null; } }
    }

    static Matcher m(Pattern p, String s) { return p.matcher(s); }
}
