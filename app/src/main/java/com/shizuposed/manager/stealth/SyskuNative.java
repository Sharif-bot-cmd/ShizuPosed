package com.shizuposed.manager.stealth;

import android.util.Log;

/**
 * SyskuNative
 *
 * Java-side bridge to libsysku.so.
 *
 * MODES
 * -----
 * libsysku runs in one of two modes, decided at nativeInit():
 *
 *   FULL     — the Sysku ADB module's service.sh is running
 *              (heartbeat fresh). Both syscall() and getdents64()
 *              interposers are installed.
 *
 *   FALLBACK — the module isn't running. Only syscall() is
 *              installed. getdents64 is left alone. See
 *              libsysku.c for why: hiding directory entries is
 *              a more visible tell than hiding direct opens,
 *              and the hidden-path list may be stale without
 *              the module coordinating it.
 *
 * The mode is set from {@code moduleRunning} passed to
 * nativeInit. It is queried by nativeIsFallbackMode().
 *
 * LOAD ORDER
 * ----------
 * libxstealth.so must load before libsysku.so. If it doesn't,
 * nativeInit returns 0 and the interposers stay inert.
 */
public final class SyskuNative {

    private static final String TAG = "SyskuNative";

    private static volatile boolean sLoaded = false;

    private SyskuNative() {}

    // ═════════════════════════════════════════════════════════════
    // LOAD
    // ═════════════════════════════════════════════════════════════

    public static boolean load(String libDir) {
        if (sLoaded) return true;
        if (libDir == null || libDir.isEmpty()) {
            Log.w(TAG, "load: libDir is null/empty");
            return false;
        }

        if (!XStealthNative.isLoaded()) {
            Log.w(TAG, "load refused: libxstealth.so not loaded. "
                + "Load libxstealth first; libsysku depends on its "
                + "xstealth_* exports.");
            return false;
        }

        try {
            String path = libDir + "/libsysku.so";
            System.load(path);
            sLoaded = true;
            Log.i(TAG, "Loaded " + path);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "load failed: " + t.getMessage());
            return false;
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    // ═════════════════════════════════════════════════════════════
    // JNI
    // ═════════════════════════════════════════════════════════════

    /**
     * Initialize libsysku.
     *
     * @param shellBase     the shell-side cache root
     * @param moduleRunning true when the Sysku ADB module's
     *                      service.sh is alive (heartbeat fresh).
     *                      false means FALLBACK mode: syscall()
     *                      interposer only, no getdents64.
     * @return 1 if init succeeded (in either mode), 0 on failure.
     */
    public static native int nativeInit(String shellBase,
                                        boolean moduleRunning);

    public static native boolean nativeIsActive();

    /** True when libsysku is running in FALLBACK mode. */
    public static native boolean nativeIsFallbackMode();

    public static native int nativeInstalledCount();

    public static native int nativeGetCallsSeen();
    public static native int nativeGetCallsBlocked();
    public static native int nativeGetDentsSeen();
    public static native int nativeGetDentsDropped();

    public static native String nativeDescribe();
}