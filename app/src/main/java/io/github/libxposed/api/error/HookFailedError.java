package io.github.libxposed.api.error;

/**
 * Thrown when a hook cannot be installed.
 *
 * Vendored from upstream libxposed.
 */
public class HookFailedError extends XposedFrameworkError {
    public HookFailedError(String message) {
        super(message);
    }

    public HookFailedError(String message, Throwable cause) {
        super(message, cause);
    }

    public HookFailedError(Throwable cause) {
        super(cause);
    }
}