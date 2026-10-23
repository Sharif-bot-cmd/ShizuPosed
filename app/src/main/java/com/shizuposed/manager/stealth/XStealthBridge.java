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
 *
 * HIDDEN PACKAGE
 * --------------
 * The native side filters paths, readlink targets, and process
 * comms against a list of hidden strings. That list includes
 * "shizuposed", "libxstealth", etc. — the names the standard
 * build uses. The stealth build's package name is a scrambled
 * <letter>.<letters>.<letters> like "h.sghdt.kihyturd", which
 * is not known at compile time, so it is passed to the native
 * side at load time via nativeSetHiddenPackage.
 *
 * Without this call, the bridge's filters miss any path that
 * contains the stealth package name — the marker files, the
 * module config files, and the manager's own data-dir entries.
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

            // Tell the native side which package name to treat as
            // hidden. Must happen before nativeSetActive(true) is
            // called, so the first filtered readlink already sees
            // the correct list.
            try {
                String pkg = currentPackageName();
                if (pkg != null && !pkg.isEmpty()) {
                    nativeSetHiddenPackage(pkg);
                    XposedBridge.log(TAG + ": hidden package = " + pkg);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": nativeSetHiddenPackage threw: " + t);
            }

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
    public  static native int     nativeGetProcPidReadsBlockedCount();
    public  static native int     nativeGetProcEntriesSkippedCount();

    /**
     * Tell the native side which package name to treat as hidden.
     * Called from load() before nativeInit(), so the first filtered
     * path, readlink, or comm check already sees the correct list.
     */
    private static native void    nativeSetHiddenPackage(String packageName);
}