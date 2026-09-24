package com.shizuposed.manager.stealth;

import android.util.Log;

/**
 * XStealthNative
 *
 * Java side of libxstealth.so. The native library interposes libc
 * file and process inspection calls to hide ShizuPosed's presence
 * from native-side detection.
 *
 * The library is loaded by XStealthModule inside the target process.
 * Its JNI_OnLoad resolves the real libc symbols, and its JNI
 * surface lets the module turn hiding on or off. Until the module
 * calls setActive(true), every interposed function passes through
 * unchanged.
 */
public final class XStealthNative {

    private static final String TAG = "XStealthNative";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;

    private XStealthNative() {}

    /**
     * Load libxstealth.so from the shell side. Called by
     * XStealthModule after reading the config. Failure is not fatal:
     * the Java-level checks continue to work, and the process just
     * loses the native fallback.
     */
    public static synchronized boolean load(String libDir) {
        if (sLoaded) return sAvailable;
        sLoaded = true;

        if (libDir == null || libDir.isEmpty()) {
            Log.w(TAG, "load: no lib dir provided");
            return false;
        }

        try {
            System.load(libDir + "/libxstealth.so");
            sAvailable = nativeInit();
            Log.i(TAG, "libxstealth.so loaded, available=" + sAvailable);
        } catch (Throwable t) {
            sAvailable = false;
            Log.w(TAG, "libxstealth.so not loaded: " + t.getMessage());
        }
        return sAvailable;
    }

    /** True if the native library loaded and initialized. */
    public static boolean isAvailable() {
        return sAvailable;
    }

    /** Enable or disable native hiding. */
    public static void setActive(boolean active) {
        if (!sAvailable) return;
        try {
            nativeSetActive(active);
            Log.i(TAG, "native hiding active=" + active);
        } catch (Throwable t) {
            Log.w(TAG, "setActive failed: " + t.getMessage());
        }
    }

    /** Query current state. */
    public static boolean isActive() {
        if (!sAvailable) return false;
        try {
            return nativeIsActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Human-readable status, for the detail sheet. */
    public static String describe() {
        if (!sAvailable) return "native: unavailable";
        try {
            return nativeDescribe();
        } catch (Throwable t) {
            return "native: error";
        }
    }

    // ─── JNI ──────────────────────────────────────────────────────

    private static native boolean nativeInit();
    private static native boolean nativeSetActive(boolean active);
    private static native boolean nativeIsActive();
    private static native String  nativeDescribe();
}