package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Standard Xposed API shim.
 *
 * Modules call XposedBridge.getXposedVersion() to know which framework
 * API is available. They also call XposedBridge.log(...) to write
 * diagnostics from the target process. Both are implemented here.
 *
 * Version 93 (LSPosed's API generation) is reported so modules that
 * check for a minimum version accept ShizuPosed as compatible. Modules
 * that query LSPosedManager find it in the same package.
 *
 * Enabled / active state queries route to ShizuPosed's
 * ModuleStatusProvider. The provider is exported read-only.
 *
 * NO-ARG OVERLOADS
 * ----------------
 * LSPosed declares isModuleActive() and isModuleEnabled() with no
 * arguments. Module UIs call these forms because they run in their
 * own process and mean "am I — this module — active?" ShizuPosed
 * cannot inject a "current module" flag into the module's process
 * the way a zygote-level framework can, so the calling package is
 * derived from the stack.
 *
 * Without these overloads, a module UI fails to link with
 * NoSuchMethodError at dex verification, and the module's onCreate
 * throws before it can display anything. The UI then falls back to
 * its default "not activated" state, even if the module is fully
 * functional.
 */
public final class XposedBridge {

    /** Reported framework version. 93 = LSPosed's API generation. */
    public static final int XPOSED_BRIDGE_VERSION = 93;

    /** Logcat tag for XposedBridge.log output. */
    private static final String LOG_TAG = "Xposed";

    /** Logcat tag for framework-level diagnostics. */
    private static final String FRAMEWORK_TAG = "ShizuPosed";

    /** Authority of ShizuPosed's ModuleStatusProvider. */
    private static final String PROVIDER_AUTHORITY = "com.shizuposed.manager.status";

    private XposedBridge() {}

    // ═════════════════════════════════════════════════════════════
    // VERSION
    // ═════════════════════════════════════════════════════════════

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    public static int getVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    // ═════════════════════════════════════════════════════════════
    // LOGGING
    // ═════════════════════════════════════════════════════════════

    /** Log a plain string. */
    public static void log(String text) {
        try {
            Log.i(LOG_TAG, text == null ? "null" : text);
        } catch (Throwable ignored) {}
        refreshFallbackContext();
    }

    /** Log an exception's stack trace. */
    public static void log(Throwable t) {
        try {
            Log.e(LOG_TAG, Log.getStackTraceString(t));
        } catch (Throwable ignored) {}
        refreshFallbackContext();
    }

    /** Log a message with an associated exception. */
    public static void log(String text, Throwable t) {
        try {
            Log.e(LOG_TAG, text == null ? "null" : text, t);
        } catch (Throwable ignored) {}
        refreshFallbackContext();
    }

    /**
     * Opportunistically capture an Application context for later use.
     * Called from log() so that a module's first log line — which
     * almost always runs on the main thread — seeds the fallback.
     */
    private static void refreshFallbackContext() {
        try {
            if (LSPosedManager.getFallbackContext() != null) return;
            Context ctx = currentApplication();
            if (ctx != null) LSPosedManager.setFallbackContext(ctx);
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE — "is the module turned on?"
    // ═════════════════════════════════════════════════════════════

    /**
     * No-arg form. Derives the calling package from the stack and
     * asks whether that package is enabled.
     *
     * LSPosed declares this. Module UIs that call it fail to link
     * without it. See the class javadoc.
     */
    public static boolean isModuleEnabled() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(FRAMEWORK_TAG, "isModuleEnabled(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleEnabled(pkg);
    }

    public static boolean isModuleEnabled(String packageName) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(FRAMEWORK_TAG, "isModuleEnabled(" + packageName
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return isModuleEnabled(ctx, packageName);
    }

    public static boolean isModuleEnabled(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/module/" + packageName);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("enabled");
                    if (idx != -1) return c.getInt(idx) == 1;
                    int vIdx = c.getColumnIndex("value");
                    if (vIdx != -1) return "1".equals(c.getString(vIdx));
                }
                logVisibilityHint("isModuleEnabled", packageName);
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "isModuleEnabled(" + packageName + ") failed", t);
        }
        return false;
    }

    public static String[] getEnabledModules(Context context) {
        if (context == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY + "/modules");
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c == null) return new String[0];
                int idx = c.getColumnIndex("package");
                if (idx == -1) return new String[0];

                int count = 0;
                c.moveToPosition(-1);
                while (c.moveToNext()) {
                    if (c.getString(idx) != null) count++;
                }
                String[] out = new String[count];
                c.moveToPosition(-1);
                int i = 0;
                while (c.moveToNext()) {
                    String pkg = c.getString(idx);
                    if (pkg != null) out[i++] = pkg;
                }
                return out;
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "getEnabledModules failed", t);
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIVE STATE — "has the module actually done anything?"
    // ═════════════════════════════════════════════════════════════

    /**
     * No-arg form. Derives the calling package from the stack and
     * asks whether that package has loaded into at least one target.
     *
     * LSPosed declares this. Module UIs that call it fail to link
     * without it. See the class javadoc.
     */
    public static boolean isModuleActive() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(FRAMEWORK_TAG, "isModuleActive(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleActive(pkg);
    }

    public static boolean isModuleActive(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(FRAMEWORK_TAG, "isModuleActive(" + modulePackage
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return LSPosedManager.isModuleActive(ctx, modulePackage);
    }

    public static boolean isModuleActive(Context context, String modulePackage) {
        return LSPosedManager.isModuleActive(context, modulePackage);
    }

    public static String[] getModuleScope(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        return LSPosedManager.getModuleScope(ctx, modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        return LSPosedManager.getModuleScope(context, modulePackage);
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE VISIBILITY DIAGNOSTIC
    // ═════════════════════════════════════════════════════════════

    /**
     * On Android 11+, a query against an exported provider returns
     * null unless the querying app declared the provider's authority
     * in its own AndroidManifest.xml <queries> block. Most modules
     * were written against LSPosed, which uses a different authority,
     * so their manifest does not declare ShizuPosed's.
     *
     * The query silently returns nothing. Logging the hint here turns
     * a mystery into an actionable message in the module's own
     * logcat, which is where a module author would look.
     */
    private static void logVisibilityHint(String call, String packageName) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        Log.w(FRAMEWORK_TAG, call + "(" + packageName + "): empty result. "
            + "If this is Android 11+, the module's AndroidManifest.xml "
            + "must declare: <queries><provider android:authorities=\""
            + PROVIDER_AUTHORITY + "\" /></queries>. "
            + "Without it, the provider is invisible to this app.");
    }

    // ═════════════════════════════════════════════════════════════
    // CALLING PACKAGE RESOLUTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Walk the stack to find the first caller outside the framework,
     * then derive its package name. Used by the no-arg isModuleActive()
     * and isModuleEnabled() overloads.
     *
     * Returns null if no non-framework frame is found.
     */
    private static String getCallingPackage() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cls = e.getClassName();
                if (cls == null) continue;

                // Skip our own shim and the framework.
                if (cls.startsWith("de.robv.android.xposed.")) continue;
                if (cls.startsWith("com.shizuposed.")) continue;

                // Skip JDK, Android framework, and ART internals.
                if (cls.startsWith("java.")) continue;
                if (cls.startsWith("javax.")) continue;
                if (cls.startsWith("android.")) continue;
                if (cls.startsWith("com.android.")) continue;
                if (cls.startsWith("dalvik.")) continue;
                if (cls.startsWith("sun.")) continue;

                // Try to resolve the class to get its package.
                try {
                    Class<?> c = Class.forName(cls, false,
                        XposedBridge.class.getClassLoader());
                    Package p = c.getPackage();
                    if (p != null && p.getName() != null) {
                        return p.getName();
                    }
                } catch (Throwable ignored) {}

                // Fallback: split the class name at the last dot.
                int dot = cls.lastIndexOf('.');
                if (dot > 0) return cls.substring(0, dot);
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "getCallingPackage failed", t);
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Resolve a Context, working off the main thread.
     *
     * Order:
     *   1. ActivityThread.currentApplication()
     *   2. ActivityThread.currentActivityThread().getSystemContext()
     *   3. LSPosedManager's static fallback (shared with this class)
     *
     * The second step is what makes queries work from background
     * threads inside a target process, where currentApplication()
     * returns null but the system context is still available.
     */
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            // 1. currentApplication() — only reliable on the main thread.
            try {
                java.lang.reflect.Method m = at.getMethod("currentApplication");
                Object o = m.invoke(null);
                if (o instanceof Context) return (Context) o;
            } catch (Throwable ignored) {}

            // 2. currentActivityThread().getSystemContext() — works on
            //    any thread as long as a thread has been attached.
            try {
                java.lang.reflect.Method cur = at.getMethod("currentActivityThread");
                Object thread = cur.invoke(null);
                if (thread != null) {
                    java.lang.reflect.Method getSys = at.getMethod("getSystemContext");
                    Object sys = getSys.invoke(thread);
                    if (sys instanceof Context) {
                        // Cache the system context as the fallback so
                        // later off-thread calls short-circuit here.
                        LSPosedManager.setFallbackContext((Context) sys);
                        return (Context) sys;
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "currentApplication: reflective lookup failed", t);
        }
        return LSPosedManager.getFallbackContext();
    }
}