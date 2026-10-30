package io.github.libxposed.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an API element as reserved for framework implementations.
 * Modules must not call @InternalApi methods.
 *
 * Vendored from upstream libxposed. Carries no runtime behavior.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,
          ElementType.CONSTRUCTOR })
public @interface InternalApi {
}