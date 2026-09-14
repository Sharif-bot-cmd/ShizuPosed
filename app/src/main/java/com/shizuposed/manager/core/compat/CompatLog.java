package com.shizuposed.manager.core.compat;

/**
 * A tiny, dependency-free logger for the compat layer and the
 * classes that sit on top of it (backends, NativeBridge, hooks).
 *
 * The compat classes are the first things to load during bootstrap,
 * often before Logger is fully initialized. Using Logger from here
 * creates a static-init cycle:
 *
 *     Logger -> AndroidCompat -> Logger
 *
 * This class breaks that cycle by talking to android.util.Log
 * directly and never touching any other ShizuPosed class.
 *
 * Public so classes outside this package (backends, NativeBridge,
 * LifecycleRegistry, InstrumentationBackend, etc.) can use it
 * without re-importing Logger or duplicating the fallback logic.
 */
public final class CompatLog {

    private static final String PREFIX = "ShizuPosed.Compat.";

    private CompatLog() {}

    /** Debug-level. Never throws. */
    public static void d(String tag, String msg) {
        try {
            android.util.Log.d(PREFIX + tag, msg == null ? "null" : msg);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
        }
    }

    /** Info-level. Never throws. */
    public static void i(String tag, String msg) {
        try {
            android.util.Log.i(PREFIX + tag, msg == null ? "null" : msg);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
        }
    }

    /** Warn-level with optional cause. Never throws. */
    public static void w(String tag, String msg, Throwable t) {
        try {
            android.util.Log.w(PREFIX + tag, msg == null ? "null" : msg, t);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
            if (t != null) t.printStackTrace();
        }
    }

    /** Error-level with optional cause. Never throws. */
    public static void e(String tag, String msg, Throwable t) {
        try {
            android.util.Log.e(PREFIX + tag, msg == null ? "null" : msg, t);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
            if (t != null) t.printStackTrace();
        }
    }
}