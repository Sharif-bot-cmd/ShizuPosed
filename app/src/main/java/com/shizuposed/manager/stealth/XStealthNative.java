package com.shizuposed.manager.stealth;

import android.util.Log;

/**
 * XStealthNative
 *
 * Java side of libxstealth.so. The native library interposes libc
 * file and process inspection calls to hide ShizuPosed's presence
 * from native-side detection. It also neutralizes reflection-based
 * acquisition of sun.misc.Unsafe at the ART structure level, blocks
 * direct reads of the settings XML, and closes the dlsym and ptrace
 * escapes.
 *
 * The library is loaded by XStealthModule inside the target process.
 * Its JNI_OnLoad resolves the real libc symbols and captures the
 * JavaVM. Its JNI surface lets the module turn hiding on or off and
 * query status. Until the module calls setActive(true), every
 * interposed function passes through unchanged and the Unsafe gate
 * is not installed.
 */
public final class XStealthNative {

    private static final String TAG = "XStealthNative";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;

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
            sAvailable = nativeInit();
            Log.i(TAG, "libxstealth.so loaded, available=" + sAvailable);
        } catch (Throwable t) {
            sAvailable = false;
            Log.w(TAG, "libxstealth.so not loaded: " + t.getMessage());
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

    /**
     * Number of times the settings XML scrub has fired in this
     * process. Non-zero means a detector tried to read the settings
     * backing files and was stopped.
     */
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
    // JNI — single declaration block
    // ═════════════════════════════════════════════════════════════

    private static native boolean nativeInit();
    private static native boolean nativeSetActive(boolean active);
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
    private static native int nativeGetSysfsBlockedCount();
    private static native int nativeGetGetppidInterceptedCount();
}