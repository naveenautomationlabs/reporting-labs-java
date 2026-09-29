package dev.reportinglabs.restassured;

import dev.reportinglabs.core.internal.Config;
import dev.reportinglabs.core.spi.RlIntegration;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;

/**
 * Zero-code REST Assured support. Registered through ServiceLoader, so
 * having reporting-labs-rest-assured on the test classpath is the whole
 * setup: the recording filter is added to {@link RestAssured#filters()}
 * when reportingLabs starts, and put back at every lifecycle point (test
 * start, after each @Before* / @After*, each Cucumber step) if a hook
 * called {@code RestAssured.reset()} or {@code replaceFiltersWith(...)}
 * in between. The one thing that can't be covered is a reset inside a
 * test body: the requests after it in that same test go unrecorded unless
 * you add the filter back yourself ({@code RestAssured.filters(new
 * RlRestAssuredFilter())}) or use {@code given().filter(new
 * RlRestAssuredFilter())} for those requests.
 *
 * Disable with reporting-labs.restassured.autoRecord=false.
 */
public final class RestAssuredIntegration implements RlIntegration {

    public RestAssuredIntegration() { register(); }

    @Override
    public void onTestInstance(Object instance) { register(); }

    @Override
    public void onTestClass(Class<?> type) { register(); }

    /** Adds the global filter unless one is already there. Idempotent. */
    public static void register() {
        if (!Config.restAssuredAutoRecord()) return;
        try {
            for (Filter f : RestAssured.filters()) if (f instanceof RlRestAssuredFilter) return;
            RestAssured.filters(new RlRestAssuredFilter());
        } catch (Throwable ignore) { /* REST Assured not on the classpath after all */ }
    }
}
