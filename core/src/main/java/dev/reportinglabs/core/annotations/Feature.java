package dev.reportinglabs.core.annotations;

import java.lang.annotation.*;

/**
 * Feature metadata for a test — shown as a chip in the report and used for
 * ranking, filtering and rollups. Place on a test method or on the class
 * (method-level wins).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Feature {
    String value();
}
