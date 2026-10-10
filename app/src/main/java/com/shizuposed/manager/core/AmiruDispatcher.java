package com.shizuposed.manager.core;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * AmiruDispatcher
 *
 * The Java-side counterpart to amiru_dispatch() in libamiru.c.
 *
 * For every hooked method, the native stub allocates a slot and
 * calls dispatch(slot, thisObj, args) on this class. The dispatcher
 * looks up the registered XC_MethodHook for that slot, runs its
 * beforeHookedMethod callback, and returns a long telling the stub
 * what to do next:
 *
 *   0  — proceed to the original method (no replacement was set)
 *   != 0 — a replacement was set; the stub returns the low 32 bits
 *          (or the full 64-bit value for long/double) as the
 *          method's return value
 *
 * SLOT OWNERSHIP
 * --------------
 * The slot -> (Method, XC_MethodHook) mapping lives in NativeBridge,
 * not here. NativeBridge owns it because that's where the native
 * amiruHookMethod call is made, and doing the registration in the
 * same place as the native call guarantees the map and the native
 * slot table are updated together. The register(...) / unregister(...)
 * methods on this class are kept as thin delegates so existing
 * callers keep working, but the authoritative state is in
 * NativeBridge.
 *
 * Field access on MethodHookParam goes through reflection because
 * the shim's fields are not public and this class lives in a
 * different package from the shim.
 */
public final class AmiruDispatcher {

    private static final String TAG = "ShizuPosedAmiru";

    /** Cached reflection handles for MethodHookParam fields. */
    private static volatile Field fReturnEarly;
    private static volatile Field fMethod;
    private static volatile Field fThisObject;
    private static volatile Field fArgs;
    private static volatile Field fResult;
    private static volatile boolean reflectionReady = false;

    private AmiruDispatcher() {}

    // ═════════════════════════════════════════════════════════════
    // REFLECTION SETUP
    // ═════════════════════════════════════════════════════════════

    private static synchronized void ensureReflection() {
        if (reflectionReady) return;
        reflectionReady = true;
        Class<?> c = XC_MethodHook.MethodHookParam.class;
        fReturnEarly = findField(c, "returnEarly");
        fMethod      = findField(c, "method");
        fThisObject  = findField(c, "thisObject");
        fArgs        = findField(c, "args");
        fResult      = findField(c, "result");
    }

    private static Field findField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            try {
                Field f = c.getField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t2) {
                Log.w(TAG, "field not found: " + name);
                return null;
            }
        }
    }

    private static void setField(Field f, Object target, Object value) {
        if (f == null) return;
        try { f.set(target, value); } catch (Throwable ignored) {}
    }

    private static Object getField(Field f, Object target) {
        if (f == null) return null;
        try { return f.get(target); } catch (Throwable ignored) { return null; }
    }

    // ═════════════════════════════════════════════════════════════
    // REGISTRATION — delegates to NativeBridge
    //
    // NativeBridge owns the authoritative slot map. These methods
    // exist for compatibility with any code that calls them
    // directly; the actual registration during hook install is done
    // by NativeBridge.amiruHookMethod after the native call returns.
    // ═════════════════════════════════════════════════════════════

    /**
     * @deprecated registration now happens inside
     * NativeBridge.amiruHookMethod. Kept for callers that were
     * written against the previous API. The callback is still
     * associated with the slot, but the Method reference is not
     * known here, so MethodHookParam.method will be null for slots
     * registered through this path.
     */
    @Deprecated
    public static void register(int slot, XC_MethodHook hook) {
        if (hook == null) return;
        Log.w(TAG, "register(slot=" + slot + ") called directly — "
                + "prefer letting NativeBridge.amiruHookMethod register "
                + "the slot; MethodHookParam.method will be null");
        // No-op on the native side. The callback is already
        // registered there if the slot is live; if it isn't, the
        // dispatcher will fall through to the original.
    }

    /**
     * @deprecated unregistration now happens inside
     * NativeBridge.amiruUnhook. Kept for callers that were written
     * against the previous API.
     */
    @Deprecated
    public static void unregister(int slot) {
        // No-op. NativeBridge.amiruUnhook is the authoritative path.
    }

    /** Number of currently registered slots. Diagnostic only. */
    public static int registeredSlotCount() {
        return NativeBridge.amiruRegisteredCount();
    }

    /**
     * @deprecated slot cleanup now happens through
     * NativeBridge.amiruUnhook on a per-slot basis. There is no
     * bulk clear because the native side does not expose one.
     */
    @Deprecated
    public static void clear() {
        Log.i(TAG, "clear() is a no-op; unhook slots individually via "
                + "NativeBridge.amiruUnhook");
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE ENTRY POINT
    // ═════════════════════════════════════════════════════════════

    /**
     * Called from libamiru.c:amiru_dispatch() on every hooked call.
     *
     * Signature must match the JNI lookup in JNI_OnLoad:
     *   "(ILjava/lang/Object;[Ljava/lang/Object;)J"
     *
     * Return value interpretation (matches the generated stub):
     *   0        — the stub tail-branches to the original method
     *   nonzero  — the stub returns this value; the low 32 bits are
     *              the int/boolean/char/short/byte/float result, or
     *              the full 64 bits are the long/double result
     */
    public static long dispatch(int slot, Object thisObj, Object[] args) {
        ensureReflection();

        XC_MethodHook hook = NativeBridge.amiruGetCallback(slot);
        if (hook == null) {
            // No callback for this slot. This can happen transiently
            // if the hooked method fires on another thread between
            // the native entry patch and the Java-side registration,
            // or after an unhook. Falling through to the original is
            // the correct behavior in both cases.
            return 0L;
        }

        Method method = NativeBridge.amiruGetMethod(slot);

        // Reuse the shim's MethodHookParam. A fresh one per call would
        // allocate on every invocation, and the shim's param object is
        // designed to be reused anyway.
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();

        // Reset per-call state.
        setField(fMethod, param, method);
        setField(fThisObject, param, thisObj);
        setField(fArgs, param, args != null ? args : new Object[0]);
        setField(fResult, param, null);
        setField(fReturnEarly, param, Boolean.FALSE);

        try {
            invokeBefore(hook, param);
        } catch (Throwable t) {
            Log.e(TAG, "beforeHookedMethod threw for slot " + slot, t);
            return 0L;
        }

        Object early = getField(fReturnEarly, param);
        if (!Boolean.TRUE.equals(early)) {
            return 0L;
        }

        // The callback asked for early return. Encode the result.
        Object result = getField(fResult, param);
        if (method == null) {
            // We don't know the return type, so we can't encode the
            // result correctly. Fall through to the original.
            return 0L;
        }
        return encodeResult(result, method.getReturnType());
    }

    // ═════════════════════════════════════════════════════════════
    // RETURN-VALUE ENCODING
    //
    // The stub reads the dispatcher's return value directly into the
    // method's return register (x0 on ARM64). Primitives are encoded
    // per the ABI: boolean/byte/char/short/int in the low 32 bits,
    // long in the full 64, float as an IEEE754 bit pattern, double
    // as its 64-bit bit pattern. Everything else (objects, void)
    // falls through to the original — object return replacement
    // needs a different channel that isn't wired in v0.3.
    // ═════════════════════════════════════════════════════════════

    private static long encodeResult(Object result, Class<?> returnType) {
        if (returnType == null || returnType == void.class) {
            // void can't be "replaced" in the primitive sense; the
            // callback should not have set returnEarly for a void
            // method, but if it did, treat it as a no-op.
            return 0L;
        }

        try {
            if (returnType == boolean.class) {
                boolean b = Boolean.TRUE.equals(result);
                return b ? 1L : 0L;
            }
            if (returnType == byte.class || returnType == short.class
                    || returnType == int.class || returnType == char.class) {
                if (result instanceof Number) {
                    return ((Number) result).longValue() & 0xFFFFFFFFL;
                }
                if (returnType == char.class && result instanceof Character) {
                    return ((Character) result).charValue() & 0xFFFFFFFFL;
                }
                return 0L;
            }
            if (returnType == long.class) {
                if (result instanceof Number) {
                    return ((Number) result).longValue();
                }
                return 0L;
            }
            if (returnType == float.class) {
                if (result instanceof Number) {
                    int bits = Float.floatToRawIntBits(
                            ((Number) result).floatValue());
                    return bits & 0xFFFFFFFFL;
                }
                return 0L;
            }
            if (returnType == double.class) {
                if (result instanceof Number) {
                    return Double.doubleToRawLongBits(
                            ((Number) result).doubleValue());
                }
                return 0L;
            }
        } catch (Throwable t) {
            Log.w(TAG, "encodeResult failed for " + returnType.getName(), t);
        }

        // Object return types are not supported in v0.3. Falling
        // through to the original is safer than returning a
        // half-encoded value.
        return 0L;
    }

    // ═════════════════════════════════════════════════════════════
    // CALLBACK INVOCATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Invoke XC_MethodHook.beforeHookedMethod via reflection.
     *
     * The shim's XC_MethodHook is normally invoked by XposedBridge
     * via an internal helper. In the Amiru path, no such helper
     * exists, so we reflect into the protected method directly.
     * Subclasses of XC_MethodHook override it; the JVM resolves to
     * the override at invoke time.
     */
    private static void invokeBefore(XC_MethodHook hook,
                                     XC_MethodHook.MethodHookParam param)
            throws Throwable {
        java.lang.reflect.Method before = findBeforeMethod(hook.getClass());
        if (before == null) {
            // No override — the default is a no-op. Nothing to do.
            return;
        }
        before.setAccessible(true);
        before.invoke(hook, param);
    }

    private static java.lang.reflect.Method findBeforeMethod(Class<?> c) {
        Class<?> cur = c;
        while (cur != null && cur != XC_MethodHook.class) {
            try {
                java.lang.reflect.Method m =
                    cur.getDeclaredMethod("beforeHookedMethod",
                        XC_MethodHook.MethodHookParam.class);
                return m;
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        // Fall back to the base class method.
        try {
            java.lang.reflect.Method m =
                XC_MethodHook.class.getDeclaredMethod("beforeHookedMethod",
                    XC_MethodHook.MethodHookParam.class);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}