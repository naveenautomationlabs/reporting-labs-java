package dev.reportinglabs.restassured;

import dev.reportinglabs.core.internal.Config;
import dev.reportinglabs.core.spi.RlIntegration;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;

/**
 * Zero-code REST Assured support. Registered through ServiceLoader, so
 * having reporting-labs-rest-assured on the test classpath is the whole
 * setup: the recording filter is added to {@link RestAssured#filters()}
 * once when reportingLabs starts, and again if a test calls
 * {@code RestAssured.reset()} in between.
 *
 * Disable with reporting-labs.restassured.autoRecord=false.
 */
public final class RestAssuredIntegration implements RlIntegration {

    public RestAssuredIntegration() { register(); }

    @Override
    public void onTestInstance(Object instance) { register(); }

    /** Adds the global filter unless one is already there. Idempotent. */
    public static void register() {
        if (!Config.restAssuredAutoRecord()) return;
        try {
            for (Filter f : RestAssured.filters()) if (f instanceof RlRestAssuredFilter) return;
            RestAssured.filters(new RlRestAssuredFilter());
        } catch (Throwable ignore) { /* REST Assured not on the classpath after all */ }
    }
}
