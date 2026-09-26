package dev.reportinglabs.core.annotations;

import java.lang.annotation.*;

/**
 * Free-form key/value metadata for a test. Repeatable — put as many as you
 * need on a method or class. Anything set here becomes a chip in the report
 * and can be filtered on.
 *
 * <pre>{@code
 * @Meta(key = "region", value = "apac")
 * @Meta(key = "browser", value = "chromium")
 * void my_test() { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(Meta.Metas.class)
public @interface Meta {
    String key();
    String value();

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface Metas {
        Meta[] value();
    }
}
