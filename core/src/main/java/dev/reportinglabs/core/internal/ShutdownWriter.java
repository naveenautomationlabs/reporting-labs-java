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
