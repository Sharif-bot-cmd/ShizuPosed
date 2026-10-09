package com.shizuposed.manager.stealth;

import de.robv.android.xposed.XposedBridge;

/**
 * XStealthBridge
 *
 * Java-side wrapper around libxstealth_bridge.so. The library
 * implements the enumeration-hiding layer: dl_iterate_phdr
 * filtering, /proc/self/fd and /proc/self/task enumeration
 * filtering, getenv scrubbing, and readlink filtering for fd
 * targets.
 *
 * Loading is separate from XStealthNative and XStealthNativeNext.
 * The three libraries are peers, each covering a different layer
 * of detection. Any combination can be enabled independently.
 *
 * The bridge depends on libxstealth.so for its /proc path table
 * and for the read interposer that scrubs /proc/self/environ and
 * /proc/self/cmdline reads. Loading the bridge without the
 * primary library still works — the bridge's own interposers
 * function independently — but the coverage is reduced.
 */
public final class XStealthBridge {

    private static final String TAG = "XStealthBridge";
    private static final String LIB_NAME = "libxstealth_bridge.so";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sActive = false;

    private XStealthBridge() {}

    public static boolean isLoaded() { return sLoaded; }
    public static boolean isActive() { return sActive; }

    /**
     * Load the library from the given directory. Returns true if
     * the library loaded and its symbols resolved.
     */
    public static boolean load(String libDir) {
        if (sLoaded) return true;
        try {
            String path = libDir + "/" + LIB_NAME;
            System.load(path);
            sLoaded = true;
            XposedBridge.log(TAG + ": loaded " + path);
            try {
                boolean ok = nativeInit();
                XposedBridge.log(TAG + ": nativeInit returned " + ok);
                return ok;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": nativeInit threw: " + t);
                return true;
            }
        } catch (Throwable t) {
            sLoaded = false;
            XposedBridge.log(TAG + ": load failed: " + t.getMessage());
            return false;
        }
    }

    public static void setActive(boolean active) {
        sActive = active;
        try {
            nativeSetActive(active);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": nativeSetActive threw: " + t);
        }
    }

    public static boolean isEffectivelyActive() {
        if (!sActive || !sLoaded) return false;
        try {
            return nativeGetCoverage() != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String describe() {
        try {
            return nativeDescribe();
        } catch (Throwable t) {
            return "libxstealth_bridge unavailable";
        }
    }

    /* ─── Native methods ─────────────────────────────────────── */

    private static native boolean nativeInit();
    private static native void    nativeSetActive(boolean active);
    private static native boolean nativeIsActive();
    private static native String  nativeDescribe();
    private static native int     nativeGetCoverage();
    public static native int nativeGetProcPidReadsBlockedCount();
    public static native int nativeGetProcEntriesSkippedCount();
}