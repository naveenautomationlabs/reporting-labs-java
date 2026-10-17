package dev.reportinglabs.core.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The command that runs a test again, shown on every test and, for all failed tests at once, on the Overview and
 * Failures tab. The template puts it together as pre + each test's target joined with sep + post, then the options
 * the tests carry (see the Node.js reporter's ReportData.rerun).
 *
 * <ul>
 *   <li>Maven: {@code mvn test -Dtest="com.shop.LoginTest#locksAccount,com.shop.CartTest#total"}</li>
 *   <li>Gradle: {@code ./gradlew test --tests "com.shop.LoginTest.locksAccount"}</li>
 *   <li>Cucumber: {@code mvn test -Dcucumber.features="src/test/resources/features/login.feature:13"}</li>
 * </ul>
 *
 * The build tool is read from the test JVM: Gradle's test worker sets {@code org.gradle.test.worker}; anything else
 * is taken as Maven. A wrapper script (mvnw, gradlew) next to the project is used when there is one.
 */
final class Rerun {
    private Rerun() {}

    private static final boolean GRADLE = System.getProperty("org.gradle.test.worker") != null;
    private static final Path DIR = Paths.get(System.getProperty("user.dir", "")).toAbsolutePath();

    /** How the command is put together, or null when no test in the run can be re-run. */
    static Map<String, Object> shape(List<RlInternal.TestSlot> tests) {
        boolean features = tests.stream().anyMatch(Rerun::isScenario);
        boolean classes = tests.stream().anyMatch(t -> t.testClass != null && t.testMethod != null);
        if (!features && !classes) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (features) {
            // Cucumber scenarios are picked by feature file and line; only Maven passes -D on to the tests
            if (GRADLE) return null;
            m.put("pre", maven() + " test -Dcucumber.features=\"");
            m.put("sep", ",");
            m.put("post", "\"");
        } else if (GRADLE) {
            m.put("pre", gradle() + " test --tests ");
            m.put("sep", " --tests ");
        } else {
            m.put("pre", maven() + " test -Dtest=\"");
            m.put("sep", ",");
            m.put("post", "\"");
        }
        return m;
    }

    /** One test's target, then its options; null when it cannot be re-run on its own. */
    static List<String> target(RlInternal.TestSlot t, boolean features) {
        List<String> out = new ArrayList<>();
        if (features) {
            if (!isScenario(t) || GRADLE) return null;
            out.add(t.file + (t.line > 0 ? ":" + t.line : ""));
        } else {
            if (t.testClass == null || t.testMethod == null) return null;
            String cls = t.testClass.getName();
            if (GRADLE) out.add(quote(cls + "." + t.testMethod));
            else {
                out.add(cls.replace("$", "\\$") + "#" + t.testMethod);   // a nested class, inside the double quotes
                // in a multi-module build the modules without this test would fail the run
                if (multiModule()) out.add("-Dsurefire.failIfNoSpecifiedTests=false");
            }
        }
        return out;
    }

    static boolean isScenario(RlInternal.TestSlot t) {
        return t.file != null && t.file.endsWith(".feature");
    }

    private static String maven() { return wrapper("mvnw") ? "./mvnw" : "mvn"; }

    private static String gradle() { return wrapper("gradlew") ? "./gradlew" : "gradle"; }

    /** A wrapper script in the folder the tests run from or one of its parents (a module of a larger build). */
    private static boolean wrapper(String name) {
        Path d = DIR;
        for (int i = 0; i < 3 && d != null; i++, d = d.getParent()) if (Files.isRegularFile(d.resolve(name))) return true;
        return false;
    }

    /** The tests run in a module whose parent pom lists modules. */
    private static boolean multiModule() {
        Path parent = DIR.getParent();
        if (parent == null) return false;
        try {
            Path pom = parent.resolve("pom.xml");
            return Files.isRegularFile(pom) && new String(Files.readAllBytes(pom), StandardCharsets.UTF_8).contains("<modules>");
        } catch (Exception e) {
            return false;
        }
    }

    private static String quote(String s) {
        return s.matches("[\\w@%+=:,./#-]+") ? s : "\"" + s.replaceAll("([\"\\\\$`])", "\\\\$1") + "\"";
    }
}
