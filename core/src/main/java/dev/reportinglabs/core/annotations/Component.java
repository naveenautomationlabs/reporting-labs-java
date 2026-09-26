package dev.reportinglabs.core.annotations;

import java.lang.annotation.*;

/**
 * Component metadata for a test — shown as a chip in the report and used for
 * ranking, filtering and rollups. Place on a test method or on the class
 * (method-level wins).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Component {
    String value();
}
