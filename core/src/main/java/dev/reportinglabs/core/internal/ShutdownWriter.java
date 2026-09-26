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
 */
public final class ShutdownWriter {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private ShutdownWriter() {}

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                String path = RlInternal.writeReport(Config.outputFolder());
                System.err.println("[reporting-labs] wrote " + path);
            } catch (Throwable t) {
                System.err.println("[reporting-labs] failed to write report: " + t.getMessage());
                t.printStackTrace(System.err);
            }
        }, "reporting-labs-writer"));
    }
}
