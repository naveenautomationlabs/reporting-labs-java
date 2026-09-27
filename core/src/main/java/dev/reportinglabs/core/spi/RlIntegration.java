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

    /** A test finished; its outcome is final. Use for per-test capture. */
    default void onTestEnd() {}
}
