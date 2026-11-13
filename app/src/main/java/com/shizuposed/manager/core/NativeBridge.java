package com.shizuposed.manager.core;

import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Java side of libshizuposed.so and libamiru.so.
 *
 * NAMING CONVENTION
 * -----------------
 * The JNI export in libamiru.c is named
 * Java_com_shizuposed_manager_core_NativeBridge_amiruProbeLayout,
 * which means the Java method must be `amiruProbeLayout` for the
 * JNI lookup to find the C function. That method has to be the
 * *native* — not a Java wrapper of the same name, because Java
 * doesn't allow two methods with the same signature.
 *
 * So the public methods that were previously `amiruProbeLayout`
 * and friends are renamed to `probeAmiruLayout` and friends, and
 * the `amiru*` names are the private natives. The rename keeps
 * the public API stable for callers (which call `probeAmiruLayout`,
 * `describeAmiruLayout`, `unhookAmiru`, `setAmiruResult`), and the
 * natives are called only by the wrappers inside this class.
 *
 * If you'd rather have the public API be `amiruProbeLayout` etc.,
 * the fix is the other direction: drop the wrapper, make the
 * public method the native, and move the logging into the native
 * or into the call sites. The wrapper's `try/catch` blocks are
 * the only reason it exists, so that's a bigger change.
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";

    // ─── libshizuposed state ───────────────────────────────────────
    private static volatile long dispatchStub = 0L;
    private static volatile boolean dispatchStubResolved = false;
    private static volatile boolean loaded = false;
    private static volatile boolean available = false;

    // ─── libamiru state ────────────────────────────────────────────
    private static volatile boolean amiruLoaded = false;
    private static volatile boolean amiruAvailable = false;
    private static volatile boolean amiruProbed = false;
    private static volatile String  amiruLayoutDescription = "not probed";

    // ─── libamiru slot registry ────────────────────────────────────
    private static final ConcurrentHashMap<Integer, XC_MethodHook> amiruCallbacks =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Method> amiruMethods =
            new ConcurrentHashMap<>();

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

        try {
            System.loadLibrary("amiru");
            amiruLoaded = true;
            CompatLog.d(TAG, "libamiru.so loaded");
        } catch (Throwable t) {
            CompatLog.w(TAG, "libamiru.so not loadable (optional)", t);
            amiruLoaded = false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // QUERIES — libshizuposed
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
    // RAW ART HOOK — libshizuposed
    // ═════════════════════════════════════════════════════════════

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
    // ROUTED CALLBACK API — libshizuposed
    // ═════════════════════════════════════════════════════════════

    public static long registerCallback(Method method, XC_MethodHook callback) {
        if (!isAvailable() || method == null || callback == null) return 0L;
        try {
            return nRegisterCallback(method, callback);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nRegisterCallback threw", t);
            return 0L;
        }
    }

    public static boolean unregisterCallback(Method method) {
        if (!isAvailable() || method == null) return false;
        try {
            return nUnregisterCallback(method);
        } catch (Throwable t) {
            CompatLog.w(TAG, "nUnregisterCallback threw", t);
            return false;
        }
    }

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

    public static boolean unhookArtMethodRouted(Method method) {
        if (!isAvailable() || method == null) return false;
        boolean unhooked = unhookArtMethod(method);
        boolean unregistered = unregisterCallback(method);
        return unhooked || unregistered;
    }

    // ═════════════════════════════════════════════════════════════
    // DISPATCH RESULT CHANNEL — libshizuposed
    // ═════════════════════════════════════════════════════════════

    public static void setDispatchResult(boolean hasResult,
                                         boolean isWide,
                                         long value) {
        if (!loaded) return;
        try {
            nSetDispatchResult(hasResult, isWide, value);
        } catch (Throwable t) {
            CompatLog.d(TAG, "nSetDispatchResult threw: " + t.getMessage());
        }
    }

    public static void setDispatchNoResult() {
        setDispatchResult(false, false, 0L);
    }

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
        setDispatchNoResult();
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE SYMBOL HOOK — libshizuposed
    // ═════════════════════════════════════════════════════════════

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
    // DLSYM — libshizuposed
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
    // AMIRU — LIBRARY + PROBE
    // ═════════════════════════════════════════════════════════════

    public static boolean isAmiruLoaded() { return amiruLoaded; }

    public static synchronized boolean amiruLoadLibrary() {
        if (amiruLoaded) return true;
        try {
            System.loadLibrary("amiru");
            amiruLoaded = true;
            CompatLog.d(TAG, "libamiru.so loaded (late)");
            return true;
        } catch (Throwable t) {
            CompatLog.w(TAG, "libamiru.so late load failed", t);
            return false;
        }
    }

    public static boolean isAmiruAvailable() {
        if (!amiruLoaded) return false;
        if (amiruAvailable) return true;
        return probeAmiruLayout();
    }

    /**
     * Run the native layout probe. Returns true if a valid layout
     * was found. Safe to call multiple times; the native side
     * caches the result.
     *
     * The public method is `probeAmiruLayout` (not `amiruProbeLayout`)
     * because the latter name is the private native, and Java
     * doesn't allow two methods with the same signature. See the
     * class javadoc for the naming convention.
     */
    public static boolean probeAmiruLayout() {
        if (!amiruLoaded) return false;
        if (amiruAvailable) return true;
        synchronized (NativeBridge.class) {
            if (amiruAvailable) return true;
            try {
                boolean ok = amiruProbeLayout();   // native
                if (ok) {
                    amiruAvailable = true;
                    amiruProbed = true;
                    try {
                        amiruLayoutDescription = amiruDescribeLayout();   // native
                    } catch (Throwable t) {
                        amiruLayoutDescription = "described:error";
                    }
                    CompatLog.d(TAG, "amiru layout: " + amiruLayoutDescription);
                } else {
                    amiruProbed = true;
                    amiruLayoutDescription = "probe returned false";
                }
                return ok;
            } catch (Throwable t) {
                CompatLog.w(TAG, "probeAmiruLayout threw", t);
                amiruProbed = true;
                amiruLayoutDescription = "probe threw: " + t.getMessage();
                return false;
            }
        }
    }

    /**
     * Human-readable description of the probed layout, or a status
     * string explaining why the probe is not available. Safe to
     * call before the probe runs.
     *
     * Public name is `describeAmiruLayout` (not `amiruDescribeLayout`,
     * which is the native).
     */
    public static String describeAmiruLayout() {
        if (!amiruLoaded) return "library not loaded";
        if (!amiruProbed) {
            probeAmiruLayout();
        }
        return amiruLayoutDescription;
    }

    // ═════════════════════════════════════════════════════════════
    // AMIRU — HOOK INSTALL / UNINSTALL
    // ═════════════════════════════════════════════════════════════

    /**
     * Install an Amiru native hook on a java.lang.reflect.Method.
     */
    public static int amiruHookMethod(Method method,
                                      String paramShorts,
                                      boolean isStatic,
                                      XC_MethodHook callback) {
        if (!amiruLoaded || !isAmiruAvailable()) return -1;
        if (method == null || paramShorts == null || callback == null) return -1;

        Class<?>[] params = method.getParameterTypes();
        Object[] shorties = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String s;
            if (paramShorts.length() > i) {
                s = String.valueOf(paramShorts.charAt(i));
            } else {
                Class<?> t = params[i];
                if (t == int.class) s = "I";
                else if (t == long.class) s = "J";
                else if (t == boolean.class) s = "Z";
                else if (t == byte.class) s = "B";
                else if (t == char.class) s = "C";
                else if (t == short.class) s = "S";
                else if (t == float.class) s = "F";
                else if (t == double.class) s = "D";
                else if (t.isArray()) s = "[";
                else s = "L";
            }
            shorties[i] = s;
        }

        try {
            int slot = amiruHookMethodNative(method,
                                             method.getName(),
                                             params.length,
                                             shorties,
                                             0);
            if (slot < 0) return -1;

            synchronized (NativeBridge.class) {
                amiruCallbacks.put(slot, callback);
                amiruMethods.put(slot, method);
            }
            return slot;
        } catch (Throwable t) {
            CompatLog.w(TAG, "amiruHookMethod threw", t);
            return -1;
        }
    }

    /**
     * Remove a previously installed Amiru hook by slot.
     * Returns 0 on success, -1 on failure.
     *
     * Public name is `unhookAmiru` (not `amiruUnhook`, which is the
     * native).
     */
    public static int unhookAmiru(int slot) {
        if (!amiruLoaded) return -1;
        try {
            int rc = amiruUnhook(slot);   // native
            if (rc == 0) {
                synchronized (NativeBridge.class) {
                    amiruCallbacks.remove(slot);
                    amiruMethods.remove(slot);
                }
            }
            return rc;
        } catch (Throwable t) {
            CompatLog.w(TAG, "unhookAmiru threw", t);
            return -1;
        }
    }

    /**
     * Report a dispatcher result to the native side.
     *
     * Public name is `setAmiruResult` (not `amiruSetResult`, which
     * is the native). See the class javadoc for the naming
     * convention.
     */
    public static void setAmiruResult(int slot,
                                      boolean skip,
                                      boolean hasValue,
                                      long value) {
        if (!amiruLoaded) return;
        try {
            amiruSetResult(slot, skip, hasValue, value);   // native
        } catch (Throwable t) {
            CompatLog.d(TAG, "setAmiruResult threw: " + t.getMessage());
        }
    }

    public static XC_MethodHook amiruGetCallback(int slot) {
        return amiruCallbacks.get(slot);
    }

    public static Method amiruGetMethod(int slot) {
        return amiruMethods.get(slot);
    }

    public static int amiruRegisteredCount() {
        return amiruCallbacks.size();
    }

    public static long amiruGetArtMethod(Method method) {
        if (method == null) return 0L;

        try {
            Method getArtMethod = Method.class.getDeclaredMethod("getArtMethod");
            getArtMethod.setAccessible(true);
            Object v = getArtMethod.invoke(method);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
        } catch (Throwable ignored) {
        }

        try {
            Field f = Method.class.getDeclaredField("artMethod");
            f.setAccessible(true);
            Object v = f.get(method);
            if (v instanceof Long) return (Long) v;
            if (v instanceof Integer) return ((Integer) v).longValue();
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {
        }

        return 0L;
    }

    // ═════════════════════════════════════════════════════════════
    // AMIRU — DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    public static String amiruSummary() {
        return "Amiru{loaded=" + amiruLoaded
            + ", available=" + amiruAvailable
            + ", slots=" + amiruCallbacks.size()
            + ", layout=" + amiruLayoutDescription + "}";
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVES — libshizuposed
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

    public static native void nSetDebugLogging(boolean enabled);
    public static native String nDescribeStubTemplate();

    // ═════════════════════════════════════════════════════════════
    // NATIVES — libamiru
    //
    // These names MUST match the JNI exports in libamiru.c:
    //   Java_com_shizuposed_manager_core_NativeBridge_amiruProbeLayout
    //   Java_com_shizuposed_manager_core_NativeBridge_amiruDescribeLayout
    //   Java_com_shizuposed_manager_core_NativeBridge_amiruHookMethod
    //   Java_com_shizuposed_manager_core_NativeBridge_amiruUnhook
    //   Java_com_shizuposed_manager_core_NativeBridge_amiruSetResult
    //   Java_com_shizuposed_manager_core_NativeBridge_amiruHasResult
    //
    // So the Java names are amiruProbeLayout, amiruDescribeLayout,
    // amiruUnhook, amiruSetResult, amiruHasResult. The public
    // wrappers (probeAmiruLayout, describeAmiruLayout, unhookAmiru,
    // setAmiruResult) call these. Don't rename them without also
    // renaming the C exports.
    // ═════════════════════════════════════════════════════════════

    private static native boolean amiruProbeLayout();
    private static native String  amiruDescribeLayout();
    private static native int     amiruHookMethodNative(Method method,
                                                        String name,
                                                        int argCount,
                                                        Object[] argTypes,
                                                        int flags);
    private static native int     amiruUnhook(int slot);
    private static native void    amiruSetResult(int slot,
                                                 boolean skip,
                                                 boolean hasValue,
                                                 long value);
    private static native int     amiruHasResult();
    public static native void amiruSetDebugLogging(boolean enabled);
    public static native String amiruDescribeStubTemplate();
}