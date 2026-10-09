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
 */
public final class XStealthNativeNext {

    private static final String TAG = "XStealthNativeNext";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;

    private XStealthNativeNext() {}

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
            System.load(libDir + "/libxstealth_next.so");
            sAvailable = nativeInit();
            Log.i(TAG, "libxstealth_next.so loaded, available=" + sAvailable);
        } catch (Throwable t) {
            sAvailable = false;
            Log.w(TAG, "libxstealth_next.so not loaded: " + t.getMessage());
        }
        return sAvailable;
    }

    public static boolean isAvailable() {
        return sAvailable;
    }

    public static void setActive(boolean active) {
        if (!sAvailable) return;
        try {
            nativeSetActive(active);
            Log.i(TAG, "Next hiding active=" + active);
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
    // JNI — single declaration block
    // ═════════════════════════════════════════════════════════════

    private static native boolean nativeInit();
    private static native boolean nativeSetActive(boolean active);
    private static native boolean nativeIsActive();
    private static native String  nativeDescribe();

    private static native boolean nativeIsEffectivelyActive();
    private static native int     nativeGetPatchCount();
    private static native String  nativeGetStrategy();

    private static native boolean nativeIsMmapGuardActive();
    private static native int     nativeGetMmapInterceptedCount();
    private static native String  nativeGetMmapGuardInfo();
    private static native int     nativeGetPread64InterceptedCount();
    public static native int nativeGetOpenat2InterceptedCount();
    public static native int nativeGetIoctlInterceptedCount();
    public static native int nativeGetStatxInterceptedCount();
    public static native int nativeGetFaccessat2InterceptedCount();
    public static native int nativeGetFchmodat2InterceptedCount();
}