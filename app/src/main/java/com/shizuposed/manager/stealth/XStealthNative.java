package com.shizuposed.manager.stealth;

import android.util.Log;

/**
 * XStealthNative
 *
 * Java-side bridge to libxstealth.so.
 *
 * LOAD ORDER
 * ----------
 * libxstealth.so must be loaded before libsysku.so. libsysku
 * depends on libxstealth's exports (xstealth_path_is_blocked,
 * xstealth_fd_resolves_to_hidden, xstealth_is_active,
 * xstealth_set_active). If libsysku loads first, its interposers
 * resolve to NULL and it stays inert — a startup warning appears
 * in the log.
 *
 * ACTIVATION
 * ----------
 * setActive(boolean) forwards to nativeSetActive(), which calls
 * xstealth_set_active() in the native library. The flag is read
 * by both libxstealth's own interposers and libsysku's (via
 * xstealth_is_active()). The two share one switch.
 *
 * setActive() does NOT gate on sAvailable. If nativeInit() failed
 * but the library loaded, the flag still reaches the C side via
 * the JNI bridge and libsysku can still read it. A failed init
 * means libxstealth's own interposers are inactive (missing
 * symbols), but the flag itself is always delivered.
 *
 * TRUSTED PREFIX
 * --------------
 * After load(), nativeSetTrustedPackage() is called with the
 * manager's package name. In the standard build this is
 * "com.shizuposed.manager." and is redundant (the C side already
 * has it built in). In the stealth build the prefix is the
 * scrambled name, which only this call conveys.
 *
 * getTrustedPrefix() returns the prefix that was actually set,
 * for diagnostics. If the C side has one but Java doesn't know
 * it, the two are out of sync and the diagnostics show the
 * mismatch.
 */
public final class XStealthNative {

    private static final String TAG = "XStealthNative";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;
    private static volatile long sLoadedAtMs = 0L;

    /**
     * The trusted package prefix that was successfully delivered to
     * the native side. Null until load() runs and the native call
     * returns. Used by SyskuNative.load() to confirm libxstealth's
     * exports will resolve, and by diagnostics to show the actual
     * prefix in use.
     */
    private static volatile String sTrustedPrefix = null;

    private XStealthNative() {}

    // ═════════════════════════════════════════════════════════════
    // LOAD / ACTIVATE
    // ═════════════════════════════════════════════════════════════

    public static synchronized boolean load(String libDir) {
        if (sLoaded) return sAvailable;
        sLoaded = true;

        if (libDir == null || libDir.isEmpty()) {
            Log.w(TAG, "load: no lib dir provided");
            return false;
        }

        try {
            System.load(libDir + "/libxstealth.so");
            sLoadedAtMs = System.currentTimeMillis();
            sAvailable = nativeInit();
            Log.i(TAG, "libxstealth.so loaded, available=" + sAvailable
                + " (at " + sLoadedAtMs + ")");
        } catch (Throwable t) {
            sAvailable = false;
            Log.w(TAG, "libxstealth.so not loaded: " + t.getMessage());
            return sAvailable;
        }

        // The native side starts with g_xstealth_active = 0. The
        // caller flips it on after load() returns, once it has
        // read XStealthPrefs.isEnabled(). Setting it explicitly
        // here makes the initial state unambiguous even if
        // nativeInit() left it at some stale value from a
        // previous load attempt in the same process (which can
        // happen in post-application mode if two modules
        // initialize the framework).
        try {
            nativeSetActive(false);
        } catch (Throwable ignored) {}

        // Tell the native side which package prefix to trust. Must
        // happen before any hooks are installed, because the
        // trusted check gates the framework's own syscalls.
        try {
            String pkg = currentPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                nativeSetTrustedPackage(pkg);
                sTrustedPrefix = pkg.endsWith(".") ? pkg : pkg + ".";
                Log.i(TAG, "trusted package set to " + pkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "nativeSetTrustedPackage failed: " + t.getMessage());
            sTrustedPrefix = null;
        }

        return sAvailable;
    }

    /** True if load() has run and the library is loaded. */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * The trusted package prefix the native side is using, with a
     * trailing dot. Null until load() has successfully called
     * nativeSetTrustedPackage().
     */
    public static String getTrustedPrefix() {
        return sTrustedPrefix;
    }

    /**
     * When the library loaded, in milliseconds since epoch. Zero
     * if load has not run. Used for diagnostics: compare this
     * against the target's first read of the settings file to
     * confirm the interposers were in place.
     */
    public static long getLoadedAtMs() {
        return sLoadedAtMs;
    }

    /**
     * Resolve the running package name.
     *
     * Same logic as XStealthBridge.currentPackageName(). In the
     * shell-side XposedHook process, the manager package name is
     * passed via the shizuposed.manager.package system property.
     */
    private static String currentPackageName() {
        String fromProp = System.getProperty("shizuposed.manager.package");
        if (fromProp != null && !fromProp.isEmpty()) return fromProp;

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getMethod("currentPackageName");
            Object v = m.invoke(null);
            if (v instanceof String && !((String) v).isEmpty()) {
                return (String) v;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cur = at.getMethod("currentActivityThread");
            Object thread = cur.invoke(null);
            if (thread != null) {
                java.lang.reflect.Method getApp =
                    at.getMethod("getApplication");
                Object app = getApp.invoke(thread);
                if (app != null) {
                    java.lang.reflect.Method getPkg =
                        app.getClass().getMethod("getPackageName");
                    Object pkg = getPkg.invoke(app);
                    if (pkg instanceof String && !((String) pkg).isEmpty()) {
                        return (String) pkg;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return "com.shizuposed.manager";
    }

    public static boolean isAvailable() {
        return sAvailable;
    }

    /**
     * Flip the shared XStealth / Sysku activation flag.
     *
     * Does NOT gate on sAvailable. If the library loaded but
     * nativeInit() failed (some libc symbols missing), the flag
     * still reaches xstealth_set_active() so libsysku — which
     * reads xstealth_is_active() independently of whether
     * libxstealth's own interposers resolved — sees the correct
     * value.
     */
    public static void setActive(boolean active) {
        if (!sLoaded) {
            Log.w(TAG, "setActive(" + active + "): library not loaded");
            return;
        }
        try {
            nativeSetActive(active);
            Log.i(TAG, "native hiding active=" + active);
        } catch (Throwable t) {
            Log.w(TAG, "setActive failed: " + t.getMessage());
        }
    }

    public static boolean isActive() {
        if (!sAvailable) return false;
        try {
            return nativeIsActive();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String describe() {
        if (!sAvailable) return "native: unavailable";
        try {
            return nativeDescribe();
        } catch (Throwable t) {
            return "native: error";
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SETTINGS XML / PREAD / LIBART BACKUP
    // ═════════════════════════════════════════════════════════════

    public static int getSettingsXmlBlockedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetSettingsXmlBlockedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getDlsymInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetDlsymInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getPtraceInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetPtraceInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getPreadInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetPreadInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static boolean hasLibartBackup() {
        if (!sAvailable) return false;
        try { return nativeHasLibartBackup(); }
        catch (Throwable t) { return false; }
    }

    public static String getLibartBackupInfo() {
        if (!sAvailable) return "unavailable";
        try { return nativeGetLibartBackupInfo(); }
        catch (Throwable t) { return "error"; }
    }

    public static int getSysfsBlockedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetSysfsBlockedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getGetppidInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetGetppidInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    // ═════════════════════════════════════════════════════════════
    // STUB TEMPLATE — status and diagnostics
    // ═════════════════════════════════════════════════════════════

    public static boolean isStubTemplateValid() {
        if (!sAvailable) return false;
        try { return nativeIsStubTemplateValid(); }
        catch (Throwable t) { return false; }
    }

    public static int getStubTemplateConfidence() {
        if (!sAvailable) return 0;
        try { return nativeGetStubTemplateConfidence(); }
        catch (Throwable t) { return 0; }
    }

    public static String getStubTemplateInfo() {
        if (!sAvailable) return "unavailable";
        try { return nativeGetStubTemplateInfo(); }
        catch (Throwable t) { return "error: " + t.getMessage(); }
    }

    public static String dumpStubTemplate() {
        if (!sAvailable) return "unavailable";
        try { return nativeDumpStubTemplate(); }
        catch (Throwable t) { return "error: " + t.getMessage(); }
    }

    public static String stubTemplateSummary() {
        if (!sAvailable) return "stub template: native unavailable";

        boolean valid = isStubTemplateValid();
        if (!valid) {
            return "stub template: not captured (backends use shipped prologue)";
        }

        int confidence = getStubTemplateConfidence();
        String info = getStubTemplateInfo();
        return "stub template: verified (" + confidence + "B prefix, "
                + info + ")";
    }

    // ═════════════════════════════════════════════════════════════
    // UNSAFE GATE STATUS
    // ═════════════════════════════════════════════════════════════

    public static boolean isUnsafeGateActive() {
        if (!sAvailable) return false;
        try {
            return nativeIsUnsafeGateActive();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getUnsafeGateInfo() {
        if (!sAvailable) return "unavailable";
        try {
            return nativeGetUnsafeGateInfo();
        } catch (Throwable t) {
            return "error: " + t.getMessage();
        }
    }

    public static String unsafeGateSummary() {
        if (!sAvailable) return "unsafe gate: unavailable";

        try {
            String info = nativeGetUnsafeGateInfo();
            if (info == null) return "unsafe gate: unknown";

            int installed = parseKV(info, "installed", 0);
            int field     = parseKV(info, "field", 0);
            int method    = parseKV(info, "method", 0);

            if (installed == 0) {
                return "unsafe gate: inactive (not installed)";
            }
            if (field == 1 && method == 1) {
                return "unsafe gate: active (field+method)";
            }
            if (field == 1) {
                return "unsafe gate: active (field only)";
            }
            if (method == 1) {
                return "unsafe gate: active (method only)";
            }
            if (info.contains("no Unsafe class found")) {
                return "unsafe gate: inactive (no Unsafe class)";
            }
            return "unsafe gate: inactive (ART layout changed)";
        } catch (Throwable t) {
            return "unsafe gate: error";
        }
    }

    private static int parseKV(String info, String key, int defaultValue) {
        if (info == null || key == null) return defaultValue;
        int idx = info.indexOf(key + "=");
        if (idx < 0) return defaultValue;
        int start = idx + key.length() + 1;
        int end = start;
        while (end < info.length() && Character.isDigit(info.charAt(end))) {
            end++;
        }
        if (end == start) return defaultValue;
        try {
            return Integer.parseInt(info.substring(start, end));
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TRUSTED PREFIX
    // ═════════════════════════════════════════════════════════════

    /**
     * Public wrapper around the JNI setter, for callers that want
     * to change the trusted prefix after load (e.g. a stealth build
     * whose package name is only known at runtime). Not normally
     * needed — load() already sets it.
     *
     * Returns the prefix that was sent to the native side (with a
     * trailing dot), or null on failure.
     */
    public static String setTrustedPackage(String packageName) {
        if (!sLoaded) return null;
        if (packageName == null || packageName.isEmpty()) return null;
        try {
            nativeSetTrustedPackage(packageName);
            sTrustedPrefix = packageName.endsWith(".")
                ? packageName : packageName + ".";
            return sTrustedPrefix;
        } catch (Throwable t) {
            Log.w(TAG, "setTrustedPackage failed: " + t.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // JNI — single declaration block
    // ═════════════════════════════════════════════════════════════

    private static native boolean nativeInit();

    /**
     * Forwarded to xstealth_set_active() in libxstealth.so.
     *
     * Return type is void to match the C side. The previous
     * declaration was boolean, which mismatched the C JNI bridge
     * `JNIEXPORT void JNICALL
     * Java_com_shizuposed_manager_stealth_XStealthNative_nativeSetActive`.
     * If your C side still declares `JNIEXPORT jboolean`, revert
     * this line to `private static native boolean nativeSetActive(
     * boolean active);` — otherwise keep it void.
     */
    private static native void    nativeSetActive(boolean active);

    private static native boolean nativeIsActive();
    private static native String  nativeDescribe();

    private static native boolean nativeIsUnsafeGateActive();
    private static native String  nativeGetUnsafeGateInfo();

    private static native int     nativeGetSettingsXmlBlockedCount();
    private static native int     nativeGetDlsymInterceptedCount();
    private static native int     nativeGetPtraceInterceptedCount();
    private static native int     nativeGetPreadInterceptedCount();

    private static native boolean nativeHasLibartBackup();
    private static native String  nativeGetLibartBackupInfo();
    private static native int     nativeGetSysfsBlockedCount();
    private static native int     nativeGetGetppidInterceptedCount();

    public static native int nativeGetOpenat2BlockedCount();
    public static native int nativeGetIoctlBlockedCount();
    public static native int nativeGetNetlinkInterceptedCount();
    public static native int nativeGetNetlinkDroppedCount();

    public static native int nativeGetStubTemplateConfidence();
    public static native boolean nativeIsStubTemplateValid();
    public static native String nativeGetStubTemplateInfo();
    public static native String nativeDumpStubTemplate();

    /**
     * Tell the native side which package prefix to trust.
     *
     * In the standard build the prefix is
     * "com.shizuposed.manager." and is redundant (the C side
     * already has it built in). In the stealth build the prefix
     * is the scrambled name, which only this call conveys.
     */
    public static native void nativeSetTrustedPackage(String packageName);
}