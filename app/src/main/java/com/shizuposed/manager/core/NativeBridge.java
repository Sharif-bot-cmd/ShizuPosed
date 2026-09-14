package com.shizuposed.manager.core;

import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Java side of libshizuposed.so.
 *
 * The .so is loaded once per process. If loading fails (wrong ABI,
 * missing symbol, ROM block), every method here returns null/false
 * and the framework falls back to Pine-only hooking.
 *
 * Scope: in-process only. Native hooking cannot reach apps that
 * ShizuPosed did not launch. See NativeBackend for the dispatcher
 * integration.
 *
 * Two hook paths are exposed:
 *
 *   1. hookArtMethod(method, replacement)
 *      Raw: patches the ArtMethod entry point to a native function
 *      pointer you supply. Use this when you have a hand-written
 *      extern "C" function with the target's exact signature.
 *
 *   2. registerCallback(method, callback) + hookArtMethod(method, stub)
 *      Routed: registers a Java XC_MethodHook, returns the address
 *      of a per-method C stub (szp_dispatch_entry tail-branch), and
 *      you pass that stub to hookArtMethod. This is what
 *      NativeBackend uses.
 *
 * Dispatch result channel:
 *   When a hook fires, the C dispatcher forwards the raw argument
 *   registers to NativeDispatcher.dispatch(...) and waits for a
 *   result. The router calls setDispatchResult(...) to tell the C
 *   side whether to return a replacement value or fall through to
 *   the original. The result is delivered through a thread-local in
 *   libshizuposed.so, so it is per-call and thread-safe.
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";

    /** Address of the shared C dispatcher in libshizuposed.so. */
    private static volatile long dispatchStub = 0L;
    private static volatile boolean dispatchStubResolved = false;

    private static volatile boolean loaded = false;
    private static volatile boolean available = false;

    private NativeBridge() {}

    static {
        try {
            System.loadLibrary("shizuposed");
            loaded = true;
            CompatLog.d(TAG, "libshizuposed.so loaded");
        } catch (Throwable t) {
            CompatLog.w(TAG, "libshizuposed.so not loadable", t);
            loaded = false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // QUERIES
    // ═════════════════════════════════════════════════════════════

    public static boolean isLoaded() { return loaded; }

    public static boolean isAvailable() {
        if (!loaded) return false;
        if (available) return true;
        try {
            available = nIsAvailable();
        } catch (Throwable t) {
            CompatLog.w(TAG, "nIsAvailable threw", t);
            available = false;
        }
        return available;
    }

    public static String layoutInfo() {
        if (!loaded) return "not loaded";
        try {
            return nLayoutInfo();
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    /**
     * Resolve the shared C dispatcher entry once. Returns 0 if the
     * symbol is not exported (should not happen on a correct build).
     */
    public static long dispatchStubAddress() {
        if (dispatchStubResolved) return dispatchStub;
        synchronized (NativeBridge.class) {
            if (dispatchStubResolved) return dispatchStub;
            dispatchStubResolved = true;
            if (!loaded) return 0L;
            try {
                long p = nDlsym("libshizuposed.so", "szp_dispatch_entry");
                if (p == 0L) {
                    p = nDlsym("libshizuposed", "szp_dispatch_entry");
                }
                dispatchStub = p;
                if (p == 0L) {
                    CompatLog.w(TAG,
                            "szp_dispatch_entry not found in libshizuposed.so",
                            null);
                } else {
                    CompatLog.d(TAG, "szp_dispatch_entry = 0x"
                            + Long.toHexString(p));
                }
            } catch (Throwable t) {
                CompatLog.w(TAG, "dispatchStubAddress threw", t);
                dispatchStub = 0L;
            }
            return dispatchStub;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // RAW ART HOOK
    // ═════════════════════════════════════════════════════════════

    /**
     * Patch a Java method's ArtMethod entry point to `replacement`.
     * `replacement` must be a native function pointer to an
     * extern "C" function with the target's exact signature.
     *
     * Returns the trampoline address (call this to reach the
     * original), or 0 on failure.
     */
    public static long hookArtMethod(Method method, long replacement) {
        if (!isAvailable() || method == null || replacement == 0) return 0L;
        long[] out = new long[1];
        boolean ok;
        try {
            ok = nHookArtMethod(method, replacement, out);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nHookArtMethod threw", t);
            return 0L;
        }
        return ok ? out[0] : 0L;
    }

    public static boolean unhookArtMethod(Method method) {
        if (!isAvailable() || method == null) return false;
        try {
            return nUnhookArtMethod(method);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nUnhookArtMethod threw", t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ROUTED CALLBACK API (used by NativeBackend)
    // ═════════════════════════════════════════════════════════════

    /**
     * Register a Java XC_MethodHook with the C-side router. Returns
     * the address of a per-method C stub. Pass that stub to
     * hookArtMethod() to complete the hook.
     *
     * Returns 0 on failure (native side full, duplicate registration,
     * or native bridge unavailable).
     */
    public static long registerCallback(Method method, XC_MethodHook callback) {
        if (!isAvailable() || method == null || callback == null) return 0L;
        try {
            return nRegisterCallback(method, callback);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nRegisterCallback threw", t);
            return 0L;
        }
    }

    /**
     * Remove a callback registered by registerCallback(). Also frees
     * the per-method C stub. Does not unhook the ArtMethod entry; call
     * unhookArtMethod() for that first.
     */
    public static boolean unregisterCallback(Method method) {
        if (!isAvailable() || method == null) return false;
        try {
            return nUnregisterCallback(method);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nUnregisterCallback threw", t);
            return false;
        }
    }

    /**
     * Convenience: register + hook in one call. Returns the trampoline
     * address on success, or 0. On failure the registration is rolled
     * back so the C-side table is not left dirty.
     */
    public static long hookArtMethodRouted(Method method, XC_MethodHook callback) {
        if (!isAvailable() || method == null || callback == null) return 0L;
        long stub = registerCallback(method, callback);
        if (stub == 0L) return 0L;
        long trampoline = hookArtMethod(method, stub);
        if (trampoline == 0L) {
            unregisterCallback(method);
            return 0L;
        }
        return trampoline;
    }

    /**
     * Convenience: unhook + unregister in one call. Safe to call on a
     * method that was never hooked (returns false).
     */
    public static boolean unhookArtMethodRouted(Method method) {
        if (!isAvailable() || method == null) return false;
        boolean unhooked = unhookArtMethod(method);
        boolean unregistered = unregisterCallback(method);
        return unhooked || unregistered;
    }

    // ═════════════════════════════════════════════════════════════
    // DISPATCH RESULT CHANNEL
    //
    // When a native hook fires, the C dispatcher forwards the raw
    // argument registers to NativeDispatcher.dispatch(...). The
    // router decides what should happen next:
    //
    //   - Return a replacement value: call setDispatchResult(true,
    //     isWide, bits). The C asm entry returns that value without
    //     calling the original.
    //
    //   - Fall through to the original: call setDispatchResult(false,
    //     false, 0). The C asm entry restores the saved registers and
    //     tail-branches to the trampoline.
    //
    // The result travels through a thread-local in libshizuposed.so,
    // so it is scoped to the current hook call and cannot leak
    // between threads.
    // ═════════════════════════════════════════════════════════════

    /**
     * Report the result of a hook dispatch to the C side.
     *
     * @param hasResult true if the callback supplied a replacement
     *                  return value; false to fall through to the
     *                  original method.
     * @param isWide    true if the replacement value is a 64-bit
     *                  primitive (long / double); false otherwise.
     * @param value     raw bits of the replacement value. Ignored
     *                  when hasResult is false.
     */
    public static void setDispatchResult(boolean hasResult,
                                         boolean isWide,
                                         long value) {
        if (!loaded) return;
        try {
            nSetDispatchResult(hasResult, isWide, value);
        } catch (Throwable t) {
            // A failure here means the callback's result is lost and
            // the original method will run instead. Log at debug
            // because this can fire from hot paths.
            CompatLog.d(TAG, "nSetDispatchResult threw: " + t.getMessage());
        }
    }

    /** Convenience: tell the C side to fall through to the original. */
    public static void setDispatchNoResult() {
        setDispatchResult(false, false, 0L);
    }

    /**
     * Convenience for primitive callbacks: encode `result` per the
     * declared return type and push it. Handles boolean, byte, char,
     * short, int, long, float, double. Returns true if the value was
     * encoded and pushed; false if the type is unsupported (in which
     * case the caller should fall through).
     */
    public static boolean setDispatchPrimitiveResult(Object result,
                                                     Class<?> returnType) {
        if (returnType == null || returnType == void.class) {
            setDispatchNoResult();
            return false;
        }
        try {
            if (returnType == boolean.class) {
                setDispatchResult(true, false,
                        Boolean.TRUE.equals(result) ? 1L : 0L);
                return true;
            }
            if (returnType == byte.class || returnType == short.class
                    || returnType == int.class || returnType == char.class) {
                long v = (result instanceof Number)
                        ? ((Number) result).longValue()
                        : 0L;
                setDispatchResult(true, false, v);
                return true;
            }
            if (returnType == long.class) {
                long v = (result instanceof Number)
                        ? ((Number) result).longValue()
                        : 0L;
                setDispatchResult(true, true, v);
                return true;
            }
            if (returnType == float.class) {
                int bits = (result instanceof Number)
                        ? Float.floatToRawIntBits(((Number) result).floatValue())
                        : 0;
                setDispatchResult(true, false, bits & 0xFFFFFFFFL);
                return true;
            }
            if (returnType == double.class) {
                long bits = (result instanceof Number)
                        ? Double.doubleToRawLongBits(((Number) result).doubleValue())
                        : 0L;
                setDispatchResult(true, true, bits);
                return true;
            }
        } catch (Throwable t) {
            CompatLog.w(TAG, "setDispatchPrimitiveResult failed for "
                    + returnType.getName(), t);
        }
        // Object returns, void, and anything else: fall through.
        setDispatchNoResult();
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE (JNI) HOOK
    // ═════════════════════════════════════════════════════════════

    /**
     * Inline-hook a native symbol. `target` is an address returned by
     * dlsym(); `replacement` is a native function pointer. Returns the
     * trampoline address, or 0.
     */
    public static long hookNative(long target, long replacement) {
        if (!isAvailable() || target == 0 || replacement == 0) return 0L;
        long[] out = new long[1];
        boolean ok;
        try {
            ok = nHookNative(target, replacement, out);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nHookNative threw", t);
            return 0L;
        }
        return ok ? out[0] : 0L;
    }

    // ═════════════════════════════════════════════════════════════
    // DLSYM
    // ═════════════════════════════════════════════════════════════

    public static long dlsym(String library, String symbol) {
        if (!loaded || library == null || symbol == null) return 0L;
        try {
            return nDlsym(library, symbol);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nDlsym threw for " + library + "!" + symbol, t);
            return 0L;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVES
    // ═════════════════════════════════════════════════════════════

    private static native boolean nIsAvailable();
    private static native String  nLayoutInfo();
    private static native boolean nHookArtMethod(Method m, long replacement, long[] out);
    private static native boolean nUnhookArtMethod(Method m);
    private static native boolean nHookNative(long target, long replacement, long[] out);
    private static native long    nDlsym(String library, String symbol);
    private static native long    nRegisterCallback(Method m, XC_MethodHook callback);
    private static native boolean nUnregisterCallback(Method m);
    private static native void    nSetDispatchResult(boolean hasResult,
                                                     boolean isWide,
                                                     long value);
}