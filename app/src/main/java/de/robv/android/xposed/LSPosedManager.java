package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public final class LSPosedManager {

    /** Framework name reported to modules. */
    public static final String FRAMEWORK_NAME = "ShizuPosed";

    /** Reported API version. Matches XposedBridge.XPOSED_BRIDGE_VERSION. */
    public static final int API_VERSION = 101;

    /**
     * The version name the framework reports to modules. This is
     * the app's own version, not a spoofed LSPosed version.
     *
     * The previous value was "(5740)" — an LSPosed version code
     * wrapped in parentheses. No module expects parentheses in a
     * version name; a module that parses it will misbehave. If a
     * module needs an LSPosed-compatible version code, it can use
     * getVersionCode(), which returns 101 (the API level).
     */
    public static final String FRAMEWORK_VERSION_NAME = "1.4.7";

    /**
     * Resolve the status provider authority at runtime.
     *
     * Order:
     *   1. shizuposed.status.authority system property. The cloak
     *      flow passes this when it spawns app_process, so a
     *      module running in a cloaked process sees the cloaked
     *      authority.
     *   2. getPackageName() + ".status", via the app instance or
     *      a fallback context.
     *   3. The canonical literal, ONLY as a pre-init fallback.
     */
    private static String providerAuthority() {
        String prop = System.getProperty("shizuposed.status.authority");
        if (prop != null && !prop.isEmpty()) return prop;
        String pkg = currentPackageName();
        if (pkg != null) return pkg + ".status";
        return "com.shizuposed.manager.status";
    }

    private static String currentPackageName() {
        try {
            com.shizuposed.manager.ShizuPosedManagerApp app =
                com.shizuposed.manager.ShizuPosedManagerApp.getInstance();
            if (app != null) return app.getPackageName();
        } catch (Throwable ignored) {}
        Context ctx = sFallbackContext;
        if (ctx != null) return ctx.getPackageName();
        return null;
    }

    private static final String LOG_TAG = "ShizuPosed";

    /** Last-resort context, populated by setFallbackContext(). */
    private static volatile Context sFallbackContext;

    private LSPosedManager() {}

    // ═════════════════════════════════════════════════════════════
    // FALLBACK CONTEXT
    // ═════════════════════════════════════════════════════════════

    public static void setFallbackContext(Context ctx) {
        if (ctx != null) {
            Context app = ctx.getApplicationContext();
            sFallbackContext = app != null ? app : ctx;
        }
    }

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
        // True when the manager runs under a package name other
        // than the canonical one. A module that hides its own UI
        // based on this should use the runtime answer.
        String current = currentPackageName();
        return current != null && !current.equals("com.shizuposed.manager");
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
        return FRAMEWORK_VERSION_NAME;
    }

    public static String getManagerPackageName() {
        String prop = System.getProperty("shizuposed.manager.package");
        if (prop != null && !prop.isEmpty()) return prop;
        String pkg = currentPackageName();
        if (pkg != null) return pkg;
        return "com.shizuposed.manager";
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE
    // ═════════════════════════════════════════════════════════════

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
            Uri uri = Uri.parse("content://" + providerAuthority()
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
            Uri uri = Uri.parse("content://" + providerAuthority()
                + "/modules");
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

    public static boolean isModuleActive() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(LOG_TAG, "isModuleActive(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleActive(pkg);
    }

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
            Uri uri = Uri.parse("content://" + providerAuthority()
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
            Uri uri = Uri.parse("content://" + providerAuthority()
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

    public static String[] getModuleScope(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        return getModuleScope(ctx, modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        if (context == null || modulePackage == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + providerAuthority()
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
            + providerAuthority() + "\" />\n"
            + "    </queries>\n"
            + "Without it, the provider is invisible to this app and every "
            + "activation query returns false, regardless of marker state.");
    }

    // ═════════════════════════════════════════════════════════════
    // CALLING PACKAGE RESOLUTION (unchanged)
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
    // CONTEXT RESOLUTION (unchanged)
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