package dev.reportinglabs.core.internal;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers a JVM shutdown hook (once, on first install) that writes the
 * report just before the JVM exits. Framework bindings call
 * {@link #install()} lazily on the first test start.
 *
 * This lets us render one report regardless of which frameworks ran or how
 * they ran — Surefire fork, IDE runner, nested Suite runner. If the JVM
 * dies badly (Kill -9, OOM), no report is written. That trade-off is
 * documented; nothing here catches the OS signal.
 *
 * install() also warms the template cache immediately so the shutdown hook
 * never has to hit the classloader — Surefire's isolating classloader
 * (and some IDE runners) can already be closed by the time the hook fires,
 * which would otherwise strand the report.
 */
public final class ShutdownWriter {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private ShutdownWriter() {}

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;

        if (Config.captureStdout()) ConsoleCapture.install();
        RlInternal.loadIntegrations();

        // Pre-load the template while the framework's classloader is alive.
        try { TemplateRenderer.warmCache(); }
        catch (Throwable t) {
            System.err.println("[reporting-labs] could not pre-load template: " + t.getMessage());
        }

        // Preserve the framework's context classloader on the shutdown
        // thread — belt for the same braces.
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (tccl != null) {
                try { Thread.currentThread().setContextClassLoader(tccl); }
                catch (Throwable ignore) {}
            }
            try {
                String path = RlInternal.writeReport(Config.outputFolder());
                // stdout so terminals don't paint the success line red.
                System.out.println("[reporting-labs] wrote " + path);
                String pdf = PdfWriter.write(path);
                if (pdf != null) System.out.println("[reporting-labs] wrote " + pdf);
                maybeOpen(path);
            } catch (Throwable t) {
                System.err.println("[reporting-labs] failed to write report: " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }, "reporting-labs-writer"));
    }

    /** Opens the report in the default browser when the user asked for it AND
     *  we're not in a headless/CI environment. Any failure is swallowed —
     *  writing the report is what matters. */
    private static void maybeOpen(String path) {
        String mode = String.valueOf(Config.open()).toLowerCase(java.util.Locale.ROOT);
        if ("never".equals(mode) || mode.isEmpty()) return;

        // 'on-failure' respects RlInternal.hasFailures(); 'always' unconditional.
        if ("on-failure".equals(mode) && !RlInternal.hasFailures()) return;

        if (isCi() || isHeadless()) return;

        try {
            java.awt.Desktop d = java.awt.Desktop.getDesktop();
            if (d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                d.browse(new java.io.File(path).toURI());
            }
        } catch (Throwable ignore) { /* opening is best-effort */ }
    }

    /** Prints the star line when the test run ends. Called by the JUnit 5, TestNG and Cucumber listeners: a build
     *  tool shows what the test JVM prints while tests run, not what its shutdown hook prints. */
    public static void printStarLine() {
        String star = starLine();
        if (star != null) System.out.println(star);
    }

    /** At most once a week, on a developer's machine (never in CI), one line asking for a GitHub star: most people
     *  who use the reporter never see the repository. Off with REPORTING_LABS_NO_STAR=1. */
    static String starLine() {
        String off = System.getenv("REPORTING_LABS_NO_STAR");
        if ((off != null && !off.isEmpty()) || isCi()) return null;
        try {
            java.nio.file.Path mark = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "reporting-labs-star");
            if (java.nio.file.Files.exists(mark)
                    && System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(mark).toMillis() < 7L * 24 * 3600 * 1000) return null;
            // the time it was shown keeps it to once a week: if it cannot be kept, stay quiet
            java.nio.file.Files.write(mark, new byte[0]);
            java.nio.file.Files.setLastModifiedTime(mark, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception e) {
            return null;
        }
        return "[reporting-labs] If reportingLabs helps you, a GitHub star helps other testers find it: https://github.com/naveenautomationlabs/reporting-labs";
    }

    private static boolean isCi() {
        for (String k : new String[]{
            "CI", "GITHUB_ACTIONS", "JENKINS_URL", "GITLAB_CI",
            "CIRCLECI", "TRAVIS", "BITBUCKET_PIPELINE_UUID",
            "BUILDKITE", "TEAMCITY_VERSION", "TF_BUILD",
        }) {
            String v = System.getenv(k);
            if (v != null && !v.isEmpty() && !"false".equalsIgnoreCase(v)) return true;
        }
        return false;
    }

    private static boolean isHeadless() {
        try { return java.awt.GraphicsEnvironment.isHeadless(); }
        catch (Throwable t) { return true; }
    }
}
