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
 */
public final class XposedBridge {

    /** Reported framework version. 93 = LSPosed's API generation. */
    public static final int XPOSED_BRIDGE_VERSION = 93;

    /** Logcat tag for XposedBridge.log output. */
    private static final String LOG_TAG = "Xposed";

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
    //
    // Every module calls one or more of these from
    // handleLoadPackage or from inside a hook callback. Without them,
    // those calls throw NoSuchMethodError and the module fails at the
    // first log line.
    //
    // All three overloads are wrapped in try/catch so a logging call
    // can never crash the module. Logcat is the destination; LSPosed
    // also writes into its own log file, but for ShizuPosed the
    // shell-side log at
    // /data/user/0/com.android.shell/files/.syscall_cache/xposed.log
    // already captures framework output separately.
    // ═════════════════════════════════════════════════════════════

    /** Log a plain string. */
    public static void log(String text) {
        try {
            Log.i(LOG_TAG, text == null ? "null" : text);
        } catch (Throwable ignored) {}
    }

    /** Log an exception's stack trace. */
    public static void log(Throwable t) {
        try {
            Log.e(LOG_TAG, Log.getStackTraceString(t));
        } catch (Throwable ignored) {}
    }

    /** Log a message with an associated exception. */
    public static void log(String text, Throwable t) {
        try {
            Log.e(LOG_TAG, text == null ? "null" : text, t);
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE — "is the module turned on?"
    // ═════════════════════════════════════════════════════════════

    public static boolean isModuleEnabled() {
        return true;
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
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String[] getEnabledModules(Context context) {
        if (context == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
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
    // ACTIVE STATE — "has the module actually done anything?"
    // ═════════════════════════════════════════════════════════════

    public static boolean isModuleActive(String modulePackage) {
        return LSPosedManager.isModuleActive(modulePackage);
    }

    public static boolean isModuleActive(Context context, String modulePackage) {
        return LSPosedManager.isModuleActive(context, modulePackage);
    }

    public static String[] getModuleScope(String modulePackage) {
        return LSPosedManager.getModuleScope(modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        return LSPosedManager.getModuleScope(context, modulePackage);
    }

    // ─── minimal reflective Application lookup ───────────────────────
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