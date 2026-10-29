package io.github.libxposed.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API element as having been introduced in a specific
 * libxposed API revision.
 *
 * Vendored from upstream libxposed. The annotation itself carries
 * no runtime behavior; the lint detectors in the libxposed project
 * read it at build time. ShizuPosed ships it so a vendored class
 * that references it compiles.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,
          ElementType.CONSTRUCTOR, ElementType.PACKAGE })
public @interface SinceApi {
    int value();
}