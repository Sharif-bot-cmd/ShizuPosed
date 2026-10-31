package com.shizuposed.manager.stealth;

import android.util.Log;

/**
 * XStealthNativeNext
 *
 * Java side of libxstealth_next.so. This is the opt-in aggressive
 * hiding engine. It inline-patches libc syscall stubs to raise the
 * bar against native detection kits that bypass the standard libc
 * wrappers.
 *
 * It is only loaded and activated when the user has turned on the
 * "Enable XStealth Next" toggle in Settings. When the toggle is
 * off, this class never loads the library and never activates it.
 *
 * The library shares the same hidden path and string sets as
 * libxstealth.so, so a target sees consistent behavior from both
 * engines when both are active.
 *
 * TRUSTED PACKAGE
 * ---------------
 * The native side checks whether a thread belongs to the framework
 * by comparing its thread name against a prefix list. The standard
 * build's prefix (com.shizuposed.manager.) is not hardcoded — it
 * is passed in at load time via nativeSetTrustedPackage. Without
 * this call, the framework's own threads would be treated as
 * untrusted, and the next engine's filters would block the
 * framework's own syscalls — which breaks hooking.
 */
public final class XStealthNativeNext {

    private static final String TAG = "XStealthNativeNext";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;

    private XStealthNativeNext() {}

    // ═════════════════════════════════════════════════════════════
    // LOAD / ACTIVATE
    // ═════════════════════════════════════════════════════════════

    /**
     * Load libxstealth_next.so from libDir.
     *
     * sLoaded is set only AFTER System.load() succeeds, so a
     * transient failure (file not yet deployed, wrong path on
     * first launch, race with ensurePayloadReady) doesn't
     * permanently poison the field for the rest of the process.
     * A later call to load() will retry.
     */
    public static synchronized boolean load(String libDir) {
        if (sLoaded) return sAvailable;

        if (libDir == null || libDir.isEmpty()) {
            Log.w(TAG, "load: no lib dir provided");
            return false;
        }

        try {
            System.load(libDir + "/libxstealth_next.so");
            sLoaded = true;
            sAvailable = nativeInit();
            Log.i(TAG, "libxstealth_next.so loaded, available=" + sAvailable);
        } catch (Throwable t) {
            sAvailable = false;
            Log.w(TAG, "libxstealth_next.so not loaded: " + t.getMessage());
            return false;
        }

        // Tell the native side which package prefix to trust. Must
        // happen before any hooks are installed, because the
        // trusted check gates the framework's own syscalls.
        try {
            String pkg = currentPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                nativeSetTrustedPackage(pkg);
                Log.i(TAG, "trusted package set to " + pkg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "nativeSetTrustedPackage failed: " + t.getMessage());
        }

        return sAvailable;
    }

    /**
     * Resolve the running package name.
     *
     * Same logic as XStealthNative.currentPackageName(). In the
     * shell-side XposedHook process, the manager package name is
     * passed via the shizuposed.manager.package system property
     * (see ShizuPosedService.launchAppUnderShizuPosed), so check
     * that first. In a normal app process, ActivityThread knows it.
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
     * Set the Next engine active or inactive.
     *
     * nativeSetActive(true) triggers install_all_patches() on the
     * C side. If every strategy declined, the flag is set but no
     * patches landed — isActive() would return true even though
     * the engine is inert. This method logs the honest state by
     * checking isEffectivelyActive() and the patch counter, so a
     * reader of the log knows whether patches actually applied.
     */
    public static void setActive(boolean active) {
        if (!sAvailable) return;
        try {
            nativeSetActive(active);

            if (!active) {
                Log.i(TAG, "Next hiding active=false");
            } else if (isEffectivelyActive()) {
                Log.i(TAG, "Next hiding active=true ("
                    + getPatchCount() + " patches via "
                    + getStrategy() + ")");
            } else {
                Log.w(TAG, "Next hiding active=true but NO PATCHES "
                    + "APPLIED (strategy=" + getStrategy()
                    + ") — engine is loaded but ineffective");
            }
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
        if (!sAvailable) return "next: unavailable";
        try {
            return nativeDescribe();
        } catch (Throwable t) {
            return "next: error";
        }
    }

    public static String getStrategy() {
        if (!sAvailable) return "none";
        try { return nativeGetStrategy(); }
        catch (Throwable t) { return "none"; }
    }

    public static int getPatchCount() {
        if (!sAvailable) return 0;
        try { return nativeGetPatchCount(); }
        catch (Throwable t) { return 0; }
    }

    public static boolean isEffectivelyActive() {
        if (!sAvailable) return false;
        try { return nativeIsEffectivelyActive(); }
        catch (Throwable t) { return false; }
    }

    // ═════════════════════════════════════════════════════════════
    // MMAP GUARD / PREAD64
    // ═════════════════════════════════════════════════════════════

    public static boolean isMmapGuardActive() {
        if (!sAvailable) return false;
        try { return nativeIsMmapGuardActive(); }
        catch (Throwable t) { return false; }
    }

    public static int getMmapInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetMmapInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static String getMmapGuardInfo() {
        if (!sAvailable) return "unavailable";
        try { return nativeGetMmapGuardInfo(); }
        catch (Throwable t) { return "unavailable"; }
    }

    public static int getPread64InterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetPread64InterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    // ═════════════════════════════════════════════════════════════
    // PER-SYSCALL COUNTERS
    // ═════════════════════════════════════════════════════════════
    //
    // These are safe wrappers around the JNI counters. Calling the
    // native methods directly from outside the class throws
    // UnsatisfiedLinkError when the library isn't loaded. The
    // wrappers return 0 in that case.

    public static int getOpenat2InterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetOpenat2InterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getIoctlInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetIoctlInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getStatxInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetStatxInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getFaccessat2InterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetFaccessat2InterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getFchmodat2InterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetFchmodat2InterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    public static int getGetppidInterceptedCount() {
        if (!sAvailable) return 0;
        try { return nativeGetGetppidInterceptedCount(); }
        catch (Throwable t) { return 0; }
    }

    // ═════════════════════════════════════════════════════════════
    // JNI — single declaration block
    // ═════════════════════════════════════════════════════════════

    private static native boolean nativeInit();
    private static native boolean nativeSetActive(boolean active);
    private static native boolean nativeIsActive();
    private static native String  nativeDescribe();

    private static native boolean nativeIsEffectivelyActive();
    private static native int     nativeGetPatchCount();
    private static native String  nativeGetStrategy();

    private static native void    nativeSetTrustedPackage(String packageName);
    private static native boolean nativeIsMmapGuardActive();
    private static native int     nativeGetMmapInterceptedCount();
    private static native String  nativeGetMmapGuardInfo();
    private static native int     nativeGetPread64InterceptedCount();

    private static native int     nativeGetOpenat2InterceptedCount();
    private static native int     nativeGetIoctlInterceptedCount();
    private static native int     nativeGetStatxInterceptedCount();
    private static native int     nativeGetFaccessat2InterceptedCount();
    private static native int     nativeGetFchmodat2InterceptedCount();
    private static native int     nativeGetGetppidInterceptedCount();
}