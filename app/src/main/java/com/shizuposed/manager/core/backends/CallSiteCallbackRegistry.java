package com.shizuposed.manager.core.backends;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;

/**
 * CallSiteCallbackRegistry
 *
 * Holds the XC_MethodHook callbacks that CallSiteBackend has
 * installed, keyed by an integer ID. The native dispatcher calls
 * back into Java with that ID when an intercepted method is
 * invoked.
 *
 * REFLECTIVE CALLBACK INVOCATION
 * ------------------------------
 * XC_MethodHook.beforeHookedMethod and afterHookedMethod are
 * protected. The framework normally calls them via subclass
 * dispatch from inside XC_MethodHook itself. From outside the
 * class hierarchy we invoke them reflectively.
 *
 * The reflective Method objects are resolved once at class load
 * and cached. If resolution fails — for example, on a shim that
 * declares a different method shape — dispatch becomes a no-op
 * for that hook, which is the correct degradation.
 *
 * DIAGNOSTICS
 * -----------
 * A process-wide dispatch counter tracks how many times any
 * intercepted method has been invoked. A non-zero count after
 * launching a scoped app confirms the interpreter patch is
 * actually routing calls.
 */
public final class CallSiteCallbackRegistry {

    private static final String TAG = "CallSite";

    private static final AtomicInteger sNextId = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, XC_MethodHook> sCallbacks =
        new ConcurrentHashMap<>();

    private static final AtomicLong sDispatchCount = new AtomicLong(0);

    /** Cached reflective handles to XC_MethodHook's protected hooks. */
    private static final Method sBeforeHook;
    private static final Method sAfterHook;

    static {
        Method before = null;
        Method after = null;
        try {
            before = XC_MethodHook.class.getDeclaredMethod(
                "beforeHookedMethod",
                XC_MethodHook.MethodHookParam.class);
            before.setAccessible(true);
        } catch (Throwable t) {
            android.util.Log.w(TAG,
                "beforeHookedMethod not reflectively reachable: " + t);
        }
        try {
            after = XC_MethodHook.class.getDeclaredMethod(
                "afterHookedMethod",
                XC_MethodHook.MethodHookParam.class);
            after.setAccessible(true);
        } catch (Throwable t) {
            // Optional. After-hooks aren't dispatched by this backend.
        }
        sBeforeHook = before;
        sAfterHook = after;
    }

    private CallSiteCallbackRegistry() {}

    public static int register(XC_MethodHook callback) {
        if (callback == null) return 0;
        int id = sNextId.getAndIncrement();
        sCallbacks.put(id, callback);
        return id;
    }

    public static void unregister(int id) {
        if (id > 0) sCallbacks.remove(id);
    }

    public static XC_MethodHook get(int id) {
        return sCallbacks.get(id);
    }

    public static int getRegisteredCount() {
        return sCallbacks.size();
    }

    public static long getDispatchCount() {
        return sDispatchCount.get();
    }

    /**
     * Called from native. Never throws back into native code.
     *
     * The return value tells the native dispatcher whether to
     * invoke the original method afterward. Always true in this
     * version.
     */
    public static boolean dispatch(int callbackId,
                                   Object thisObject,
                                   Object[] args) {
        sDispatchCount.incrementAndGet();

        try {
            XC_MethodHook callback = sCallbacks.get(callbackId);
            if (callback == null) return true;

            // Build the param object. Some shims have a public
            // "method" field; if so it would be set to the
            // intercepted Method. This backend doesn't know the
            // Method from the frame, so it leaves the field alone.
            XC_MethodHook.MethodHookParam param =
                new XC_MethodHook.MethodHookParam();
            param.thisObject = thisObject;
            param.args = args;

            if (sBeforeHook != null) {
                try {
                    sBeforeHook.invoke(callback, param);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    // Unwrap so the module's own throwable shows in
                    // the log, not the reflective wrapper.
                    Throwable cause = ite.getCause();
                    android.util.Log.w(TAG, "callback " + callbackId
                        + " threw: " + (cause != null ? cause : ite));
                }
            }

            // After-hooks aren't dispatched by this backend. The
            // reflective handle exists for a future version.
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "dispatch " + callbackId
                + " failed: " + t);
            return true;
        }
    }

    /** Test-only reset. */
    static void resetDispatchCount() {
        sDispatchCount.set(0);
    }
}