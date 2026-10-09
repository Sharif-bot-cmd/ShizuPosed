package com.shizuposed.manager.core.compat;

import android.util.Log;

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

    /**
     * Cached result of Log.isLoggable(PREFIX + "debug", DEBUG).
     *
     * Resolved lazily on first call. The value never changes at
     * runtime — a process that has debug logging off can't turn it
     * on without a restart, and vice versa. Caching avoids repeated
     * binder calls inside the AndroidCompat static initializer,
     * which runs once per process and would otherwise make N calls
     * to the same framework check.
     */
    private static volatile int sDebugState = 0; // 0 = unknown, 1 = on, -1 = off

    private CompatLog() {}

    /**
     * True if debug-level logging is enabled for this process.
     *
     * Callers use this to guard expensive message construction
     * (string concatenation, describeFull() calls, etc.) that
     * should only run when the output will actually be visible.
     *
     * Never throws. If the framework check itself fails, returns
     * false — an unknown state is treated as "off" so we don't
     * accidentally enable verbose logging.
     */
    public static boolean isDebugEnabled() {
        int cached = sDebugState;
        if (cached != 0) return cached > 0;

        boolean enabled;
        try {
            enabled = Log.isLoggable(PREFIX + "debug", Log.DEBUG);
        } catch (Throwable t) {
            enabled = false;
        }
        sDebugState = enabled ? 1 : -1;
        return enabled;
    }

    /** Debug-level. Never throws. */
    public static void d(String tag, String msg) {
        try {
            Log.d(PREFIX + tag, msg == null ? "null" : msg);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
        }
    }

    /** Info-level. Never throws. */
    public static void i(String tag, String msg) {
        try {
            Log.i(PREFIX + tag, msg == null ? "null" : msg);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
        }
    }

    /** Warn-level with optional cause. Never throws. */
    public static void w(String tag, String msg, Throwable t) {
        try {
            Log.w(PREFIX + tag, msg == null ? "null" : msg, t);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
            if (t != null) t.printStackTrace();
        }
    }

    /** Error-level with optional cause. Never throws. */
    public static void e(String tag, String msg, Throwable t) {
        try {
            Log.e(PREFIX + tag, msg == null ? "null" : msg, t);
        } catch (Throwable ignored) {
            System.out.println("[" + PREFIX + tag + "] " + msg);
            if (t != null) t.printStackTrace();
        }
    }
}