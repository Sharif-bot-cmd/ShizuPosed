package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

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
 * Hide My Applist and similar modules use isModuleActive to decide
 * whether to display "Activated" in their own UI. That state is
 * derived from the shell-side hooked markers written by XposedHook,
 * not from the manager's module list, so it reflects what the
 * framework has actually done.
 */
public final class LSPosedManager {

    /** Framework name reported to modules. */
    public static final String FRAMEWORK_NAME = "ShizuPosed";

    /** Reported API version. Matches XposedBridge.XPOSED_BRIDGE_VERSION. */
    public static final int API_VERSION = 93;

    /** Authority of ShizuPosed's ModuleStatusProvider. */
    private static final String PROVIDER_AUTHORITY = "com.shizuposed.manager.status";

    private LSPosedManager() {}

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
        return "2.9";
    }

    public static String getManagerPackageName() {
        return "com.shizuposed.manager";
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE
    // ═════════════════════════════════════════════════════════════

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
            }
        } catch (Throwable ignored) {}
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
                String[] out = new String[c.getCount()];
                int i = 0;
                while (c.moveToNext()) {
                    int idx = c.getColumnIndex("package");
                    out[i++] = idx != -1 ? c.getString(idx) : null;
                }
                return out;
            }
        } catch (Throwable ignored) {
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIVE STATE
    // ═════════════════════════════════════════════════════════════

    /**
     * Has the module loaded into at least one target process?
     *
     * Reads the shell-side hooked markers via
     * content://.../active/<pkg>. Returns false if no marker lists
     * this module's package name.
     *
     * Modules that display "Activated" in their own UI rely on this.
     * If the module has never been launched under ShizuPosed for any
     * target in its scope, this returns false even though the module
     * is enabled. That's the correct answer — the module hasn't
     * actually done anything yet.
     */
    public static boolean isModuleActive(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) return false;
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
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Packages the module has loaded into.
     *
     * Returns every target package whose shell-side hooked marker
     * lists this module in its moduleList.
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
                String[] out = new String[c.getCount()];
                int i = 0;
                while (c.moveToNext()) {
                    int idx = c.getColumnIndex("package");
                    out[i++] = idx != -1 ? c.getString(idx) : null;
                }
                return out;
            }
        } catch (Throwable ignored) {
            return new String[0];
        }
    }

    // ─── reflective Application lookup ───────────────────────────────
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getMethod("currentApplication");
            Object o = m.invoke(null);
            if (o instanceof Context) return (Context) o;
        } catch (Throwable ignored) {}
        return null;
    }
}