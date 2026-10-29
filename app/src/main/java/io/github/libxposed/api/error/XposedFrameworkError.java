package io.github.libxposed.api.error;

/**
 * Base class for framework-level errors.
 *
 * Vendored from upstream libxposed. A module that catches
 * Exception will NOT catch these — they extend Error, matching
 * upstream. That's deliberate: a hook install failure must not
 * be silently swallowed by a module's try/catch.
 */
public class XposedFrameworkError extends Error {
    public XposedFrameworkError(String message) {
        super(message);
    }

    public XposedFrameworkError(String message, Throwable cause) {
        super(message, cause);
    }

    public XposedFrameworkError(Throwable cause) {
        super(cause);
    }
}