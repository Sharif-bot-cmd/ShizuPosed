package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Handle for a hook installed via XposedBridge.hookMethod or
 * XposedHelpers.findAndHookMethod.
 *
 * RETURNED BY
 * -----------
 * XposedBridge.hookMethod(Method, XC_MethodHook) and its overloads,
 * XposedHelpers.findAndHookMethod, XposedHelpers.findAndHookConstructor.
 *
 * WHAT unhook() DOES
 * ------------------
 * Attempts to remove the hook. Whether this actually succeeds
 * depends on the backend that installed it:
 *
 *   • CallSite — unhooks cleanly. The flag is cleared and the
 *     intercept entry removed.
 *   • Proxy — unhooks cleanly. The original handler is restored.
 *   • Noop — trivially succeeds. Nothing was installed.
 *   • Pine (AUTO / REPLACEMENT) — cannot unhook. Pine doesn't
 *     expose a public reverse API.
 *   • Amiru — depends on the native library's capabilities.
 *   • Native — depends on the native library's capabilities.
 *   • Instrumentation — depends on the implementation.
 *
 * When the backend can't reverse the install, unhook() is a no-op
 * and logs a warning naming the backend. It never throws.
 *
 * WHY NOT THROW
 * -------------
 * Throwing would break modules that call unhook() in a cleanup
 * path — a module that tries to uninstall a hook on framework
 * shutdown shouldn't crash because the backend is Pine. The
 * correct behavior is to log and continue.
 *
 * GENERICS
 * --------
 * Upstream Xposed declares the generic form IXUnhook<T extends
 * XC_MethodHook>. Modules compiled against upstream link against
 * that signature. This shim declares the same generic bound so
 * that IXUnhook<XC_MethodHook> resolves.
 */
public interface IXUnhook<T extends XC_MethodHook> {

    /**
     * Remove the hook. See the class javadoc for what "remove"
     * means per backend. Safe to call multiple times.
     */
    void unhook();

    /**
     * Return the callback this handle is associated with.
     * Never null for a handle returned by a hook install method.
     */
    T getCallback();

    /**
     * Return the method or constructor this handle hooks.
     * This is a Method for method hooks and a Constructor for
     * constructor hooks. Never null for a handle returned by a
     * hook install method.
     */
    Member getHookedMethod();
}