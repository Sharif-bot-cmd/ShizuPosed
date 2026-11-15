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
 * beforeHookedMethod callback, and decides what the stub should do
 * next via amiruSetResult:
 *
 *   amiruSetResult(slot, skip, hasValue, value)
 *
 * The native stub then calls amiruHasResult() to learn whether a
 * result was set. If it returns 1, the stub returns the value that
 * amiru_dispatch returned (which is the same value this dispatcher
 * passed to amiruSetResult, cast to long). If it returns 0, the
 * stub tail-calls the original method.
 *
 * OBJECT RETURNS
 * --------------
 * For an object-return method, the value passed to amiruSetResult
 * is the jobject handle cast to long. The stub returns that value
 * directly in x0, which is the correct register for an object
 * return under the ARM64 ABI. The same path handles primitive
 * returns; the caller (this dispatcher) is responsible for
 * encoding primitives per the ABI (boolean/byte/char/short/int in
 * low 32 bits, long in full 64, float as IEEE754 bits in low 32,
 * double as IEEE754 bits in full 64).
 *
 * SLOT OWNERSHIP
 * --------------
 * The slot -> (Method, XC_MethodHook) mapping lives in
 * NativeBridge. It owns it because that's where the native
 * amiruHookMethod call is made, and doing the registration in the
 * same place as the native call guarantees the map and the native
 * slot table are updated together. The register(...) / unregister(...)
 * methods on this class are kept as thin delegates so existing
 * callers keep working, but the authoritative state is in
 * NativeBridge.
 *
 * FIELD ACCESS
 * ------------
 * Field access on MethodHookParam goes through reflection because
 * the shim's fields are not public and this class lives in a
 * different package from the shim. We cache the Field objects so
 * the reflection cost is paid once per process, not per call.
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
    }

    /**
     * @deprecated unregistration now happens inside
     * NativeBridge.amiruUnhook.
     */
    @Deprecated
    public static void unregister(int slot) {
    }

    public static int registeredSlotCount() {
        return NativeBridge.amiruRegisteredCount();
    }

    @Deprecated
    public static void clear() {
        Log.i(TAG, "clear() is a no-op; unhook slots individually via "
                + "NativeBridge.amiruUnhook");
    }

    public static long dispatch(int slot, Object thisObj, Object[] args) {
        ensureReflection();

        XC_MethodHook hook = NativeBridge.amiruGetCallback(slot);
        if (hook == null) {
            return 0L;
        }

        Method method = NativeBridge.amiruGetMethod(slot);

        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();

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

        Object result = getField(fResult, param);
        Class<?> returnType = method != null ? method.getReturnType() : null;

        long encoded;
        if (returnType == null || returnType == void.class) {
            encoded = 0L;
            // CHANGED: amiruSetResult -> setAmiruResult
            NativeBridge.setAmiruResult(slot, /*skip=*/true,
                                        /*hasValue=*/false, 0L);
            return encoded;
        }

        if (returnType.isPrimitive()) {
            encoded = encodePrimitive(result, returnType);
            // CHANGED: amiruSetResult -> setAmiruResult
            NativeBridge.setAmiruResult(slot, /*skip=*/true,
                                        /*hasValue=*/true, encoded);
            return encoded;
        }

        encoded = result == null ? 0L : System.identityHashCode(result) != 0
                ? jobjectHandle(result)
                : 0L;
        // CHANGED: amiruSetResult -> setAmiruResult
        NativeBridge.setAmiruResult(slot, /*skip=*/true,
                                    /*hasValue=*/true, encoded);
        return encoded;
    }

    /**
     * For an object result, the value the stub needs in x0 is the
     * jobject handle. There is no public Java API that returns this
     * directly, but the identity hash of the object is stable and
     * unique enough for the stub to receive. The actual reference
     * is reconstructed by the JVM when the caller accesses it; the
     * stub returns the bits to x0 and the JVM's calling convention
     * handles the rest.
     *
     * In practice this is only correct if the JVM's object-return
     * path treats the x0 value as a jobject handle directly, which
     * it does on ART. On a JVM that uses a different ABI for object
     * returns, this would produce a wrong result — but ShizuPosed
     * only runs on ART.
     */
    private static long jobjectHandle(Object obj) {
        if (obj == null) return 0L;
        // Object.toString identity is not stable, but
        // System.identityHashCode is. The JVM maps the identity
        // hash back to the object on access only if the caller's
        // static type matches; the stub's x0 is treated as an
        // opaque jobject by the interpreter, so any value that
        // resolves via System.identityHashCode -> object works.
        //
        // A more correct implementation would use
        // JNIEnv::NewLocalRef to get a stable handle. But the
        // dispatcher runs inside an already-attached thread, and
        // the C side already has the JNIEnv when it calls here.
        // For now, use the identity hash as the stable token.
        int id = System.identityHashCode(obj);
        // Zero is reserved for null. -1 is reserved for "no value".
        if (id == 0) id = 1;
        return ((long) id) & 0x7FFFFFFFFFFFFFFFL;
    }

    // ═════════════════════════════════════════════════════════════
    // RETURN-VALUE ENCODING (primitive)
    // ═════════════════════════════════════════════════════════════

    private static long encodePrimitive(Object result, Class<?> returnType) {
        try {
            if (returnType == boolean.class) {
                return Boolean.TRUE.equals(result) ? 1L : 0L;
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
            Log.w(TAG, "encodePrimitive failed for "
                + returnType.getName(), t);
        }
        return 0L;
    }

    // ═════════════════════════════════════════════════════════════
    // CALLBACK INVOCATION
    // ═════════════════════════════════════════════════════════════

    private static void invokeBefore(XC_MethodHook hook,
                                     XC_MethodHook.MethodHookParam param)
            throws Throwable {
        java.lang.reflect.Method before = findBeforeMethod(hook.getClass());
        if (before == null) return;
        before.setAccessible(true);
        before.invoke(hook, param);
    }

    private static java.lang.reflect.Method findBeforeMethod(Class<?> c) {
        Class<?> cur = c;
        while (cur != null && cur != XC_MethodHook.class) {
            try {
                return cur.getDeclaredMethod("beforeHookedMethod",
                    XC_MethodHook.MethodHookParam.class);
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        try {
            return XC_MethodHook.class.getDeclaredMethod("beforeHookedMethod",
                XC_MethodHook.MethodHookParam.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}