package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * LSPosed-compatible manager API shim.
 *
 * Modules written for LSPosed call these methods to discover which
 * framework they're running under and to query per-module state. By
 * providing the same class name and method signatures, ShizuPosed
 * makes those modules behave as if they were running under LSPosed.
 *
 * All answers are backed by ShizuPosed's ModuleStatusProvider
 * (content://com.shizuposed.manager.status).
 *
 * Two distinct questions modules ask:
 *   • isModuleEnabled(pkg) — did the user turn this module on?
 *   • isModuleActive(pkg)  — has the module actually loaded into at
 *                            least one target process at least once?
 *
 * SEMANTICS UNDER LSPosed vs ShizuPosed
 * -------------------------------------
 * Under LSPosed, isModuleActive(pkg) often functions as "am I
 * loaded anywhere" because LSPosed injects into every process — the
 * module's own UI included. Modules that scope themselves to a
 * target app and then call isModuleActive(getPackageName()) see
 * true as soon as the framework has loaded them into any process.
 *
 * ShizuPosed's coverage is narrower. Only processes that ShizuPosed
 * bootstrapped have hooks, so the honest answer to "am I loaded
 * into target T" is false until the user has launched T under
 * ShizuPosed. That's correct for the question being asked, but it
 * breaks the LSPosed idiom.
 *
 * To bridge that gap without changing what isModuleActive(pkg)
 * means, ShizuPosed provides isModuleActiveAnywhere(pkg). That
 * answers the LSPosed-style question directly: true if the module
 * is listed in any marker, regardless of which target the marker
 * belongs to.
 *
 * A module UI that wants to show "Activated" under both frameworks
 * can check its own hook first (the pure self-hook pattern), then
 * fall back to isModuleActive(getPackageName()), then
 * isModuleActiveAnywhere(getPackageName()).
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
 * CONTEXT RESOLUTION
 * ------------------
 * ActivityThread.currentApplication() reliably returns the
 * Application only on the main thread. Module UIs frequently call
 * isModuleActive() from background threads, WorkManager workers,
 * ContentProvider.onCreate, etc., where it can return null.
 *
 * This class therefore tries three sources in order:
 *   1. ActivityThread.currentApplication()
 *   2. ActivityThread.currentActivityThread().getSystemContext()
 *   3. A static fallback context registered via setFallbackContext()
 *
 * The static fallback is populated automatically by XposedBridge.log()
 * on first use, since log() runs in a context where the caller usually
 * has a valid Application.
 */
public final class LSPosedManager {

    /** Framework name reported to modules. */
    public static final String FRAMEWORK_NAME = "1.0.4";

    /** Reported API version. Matches XposedBridge.XPOSED_BRIDGE_VERSION. */
    public static final int API_VERSION = 100;

    /** Authority of ShizuPosed's ModuleStatusProvider. */
    private static final String PROVIDER_AUTHORITY = "com.shizuposed.manager.status";

    private static final String LOG_TAG = "ShizuPosed";

    /** Last-resort context, populated by setFallbackContext(). */
    private static volatile Context sFallbackContext;

    private LSPosedManager() {}

    // ═════════════════════════════════════════════════════════════
    // FALLBACK CONTEXT
    // ═════════════════════════════════════════════════════════════

    /**
     * Register a context to use when ActivityThread lookups fail.
     * Called automatically by XposedBridge.log() so that later
     * off-main-thread queries still have a usable Context.
     */
    public static void setFallbackContext(Context ctx) {
        if (ctx != null) {
            Context app = ctx.getApplicationContext();
            sFallbackContext = app != null ? app : ctx;
        }
    }

    /**
     * Accessor for the shared fallback context.
     *
     * XposedBridge uses this as the last step in its own context
     * resolution, so both shims agree on which Context is in play
     * when ActivityThread lookups fail.
     */
    public static Context getFallbackContext() {
        return sFallbackContext;
    }

    // ═════════════════════════════════════════════════════════════
    // FRAMEWORK IDENTITY
    // ═════════════════════════════════════════════════════════════

    public static boolean isManagerInstalled() {
        return true;
    }

    public static boolean isManagerHidden() {
        return false;
    }

    public static String getFrameworkName() {
        return FRAMEWORK_NAME;
    }

    public static int getApiVersion() {
        return API_VERSION;
    }

    public static int getVersionCode() {
        return API_VERSION;
    }

    public static String getVersionName() {
        return "(3810)";
    }

    public static String getManagerPackageName() {
        return "com.shizuposed.manager";
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE
    // ═════════════════════════════════════════════════════════════

    /**
     * No-arg form. Derives the calling package from the stack.
     * LSPosed declares this form.
     */
    public static boolean isModuleEnabled() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(LOG_TAG, "isModuleEnabled(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleEnabled(pkg);
    }

    public static boolean isModuleEnabled(String packageName) {
        Context ctx = currentApplication();
        if (ctx == null) return false;
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
            Log.e(LOG_TAG, "isModuleEnabled(" + packageName + ") failed", t);
        }
        return false;
    }

    public static String[] getEnabledModules() {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        try {
            ContentResolver cr = ctx.getContentResolver();
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
            Log.e(LOG_TAG, "getEnabledModules failed", t);
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIVE STATE
    // ═════════════════════════════════════════════════════════════

    /**
     * No-arg form. Derives the calling package from the stack.
     */
    public static boolean isModuleActive() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(LOG_TAG, "isModuleActive(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleActive(pkg);
    }

    /**
     * Has this module loaded into a process for the given package?
     *
     * Reads the shell-side hooked markers via
     * content://.../active/<pkg>. Returns true if any marker for
     * <pkg> lists this module's package in its moduleList.
     */
    public static boolean isModuleActive(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(LOG_TAG, "isModuleActive(" + modulePackage
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return isModuleActive(ctx, modulePackage);
    }

    public static boolean isModuleActive(Context context, String modulePackage) {
        if (context == null || modulePackage == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/active/" + modulePackage);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("active");
                    if (idx != -1) return c.getInt(idx) == 1;
                    int vIdx = c.getColumnIndex("value");
                    if (vIdx != -1) return "1".equals(c.getString(vIdx));
                }
                logVisibilityHint("isModuleActive", modulePackage);
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "isModuleActive(" + modulePackage + ") failed", t);
        }
        return false;
    }

    /**
     * Has this module been loaded into ANY process ShizuPosed has
     * bootstrapped?
     *
     * This is the LSPosed-style "am I active anywhere" question.
     * Under LSPosed, isModuleActive(getPackageName()) effectively
     * answers this, because LSPosed injects into every process
     * including the module's own UI. Under ShizuPosed the coverage
     * is narrower, so a module that has only been loaded into its
     * own UI would see false from the target-specific query.
     *
     * This method answers the "anywhere" question directly: true if
     * any marker exists whose moduleList contains this module's
     * package, regardless of which target the marker belongs to.
     *
     * Module UIs written for LSPosed that want to keep working
     * under both frameworks should check, in this order:
     *   1. Is my own hook installed in this process? (self-hook)
     *   2. isModuleActive(getPackageName())
     *   3. isModuleActiveAnywhere(getPackageName())
     *
     * The third check is ShizuPosed-specific. Modules that want to
     * stay LSPosed-clean can skip it and rely on (1) instead.
     */
    public static boolean isModuleActiveAnywhere(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(LOG_TAG, "isModuleActiveAnywhere(" + modulePackage
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return isModuleActiveAnywhere(ctx, modulePackage);
    }

    public static boolean isModuleActiveAnywhere(Context context,
                                                  String modulePackage) {
        if (context == null || modulePackage == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/any-active/" + modulePackage);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("active");
                    if (idx != -1) return c.getInt(idx) == 1;
                    int vIdx = c.getColumnIndex("value");
                    if (vIdx != -1) return "1".equals(c.getString(vIdx));
                }
                logVisibilityHint("isModuleActiveAnywhere", modulePackage);
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "isModuleActiveAnywhere(" + modulePackage
                + ") failed", t);
        }
        return false;
    }

    /**
     * Packages the module has loaded into.
     */
    public static String[] getModuleScope(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        return getModuleScope(ctx, modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        if (context == null || modulePackage == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/scope/" + modulePackage);
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
            Log.e(LOG_TAG, "getModuleScope(" + modulePackage + ") failed", t);
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE VISIBILITY DIAGNOSTIC
    // ═════════════════════════════════════════════════════════════

    private static void logVisibilityHint(String call, String packageName) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;

        Log.w(LOG_TAG,
            "⚠️ " + call + "(" + packageName + "): empty result on "
            + "Android " + android.os.Build.VERSION.SDK_INT + ". "
            + "This is almost always a package-visibility issue, not a "
            + "missing marker. The module's own AndroidManifest.xml must "
            + "declare:\n"
            + "    <queries>\n"
            + "        <provider android:authorities=\""
            + PROVIDER_AUTHORITY + "\" />\n"
            + "    </queries>\n"
            + "Without it, the provider is invisible to this app and every "
            + "activation query returns false, regardless of marker state.");
    }

    // ═════════════════════════════════════════════════════════════
    // CALLING PACKAGE RESOLUTION
    // ═════════════════════════════════════════════════════════════

    private static String getCallingPackage() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cls = e.getClassName();
                if (cls == null) continue;

                if (cls.startsWith("de.robv.android.xposed.")) continue;
                if (cls.startsWith("com.shizuposed.")) continue;
                if (cls.startsWith("java.")) continue;
                if (cls.startsWith("javax.")) continue;
                if (cls.startsWith("android.")) continue;
                if (cls.startsWith("com.android.")) continue;
                if (cls.startsWith("dalvik.")) continue;
                if (cls.startsWith("sun.")) continue;

                try {
                    Class<?> c = Class.forName(cls, false,
                        LSPosedManager.class.getClassLoader());
                    Package p = c.getPackage();
                    if (p != null && p.getName() != null) {
                        return p.getName();
                    }
                } catch (Throwable ignored) {}

                int dot = cls.lastIndexOf('.');
                if (dot > 0) return cls.substring(0, dot);
            }
        } catch (Throwable t) {
            Log.e(LOG_TAG, "getCallingPackage failed", t);
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════

    private static Context currentApplication() {
        Context fb = sFallbackContext;

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            try {
                java.lang.reflect.Method m = at.getMethod("currentApplication");
                Object o = m.invoke(null);
                if (o instanceof Context) {
                    Context app = (Context) o;
                    sFallbackContext = app;
                    return app;
                }
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Method cur = at.getMethod("currentActivityThread");
                Object thread = cur.invoke(null);
                if (thread != null) {
                    java.lang.reflect.Method getSys = at.getMethod("getSystemContext");
                    Object sys = getSys.invoke(thread);
                    if (sys instanceof Context) {
                        return (Context) sys;
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(LOG_TAG, "currentApplication: reflective lookup failed", t);
        }

        return fb;
    }
}