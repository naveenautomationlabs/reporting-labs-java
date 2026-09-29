package dev.reportinglabs.core.spi;

/**
 * Extension point for tool add-ons (Selenium, …) that want to plug into the
 * test lifecycle without the user writing any code. Implementations are
 * discovered with {@link java.util.ServiceLoader} — an add-on JAR on the
 * test classpath registers itself through
 * {@code META-INF/services/dev.reportinglabs.core.spi.RlIntegration}.
 */
public interface RlIntegration {

    /** The test class instance about to run (or that just ran a lifecycle
     *  method). Called whenever the framework hands the instance over: on
     *  test start and after every @Before* / @After* method, so an add-on
     *  can discover objects the setup created (a WebDriver, a client). */
    default void onTestInstance(Object instance) {}

    /** A class whose code is about to run for the current test when no
     *  instance is available (Cucumber step definitions and hooks). Add-ons
     *  can discover what its statics hold (a DriverFactory ThreadLocal). */
    default void onTestClass(Class<?> type) {}

    /** The test body is over but its after-hooks have not run yet, and the
     *  outcome so far is known. Only bindings whose after-hooks run before
     *  the outcome is reported call this (Cucumber: @After hooks run inside
     *  the scenario), so an add-on can capture before a hook quits the
     *  browser. Followed by {@link #onTestEnd()} as usual. */
    default void onTestBodyEnd(boolean failed) {}

    /** A test finished; its outcome is final. Use for per-test capture. */
    default void onTestEnd() {}
}
