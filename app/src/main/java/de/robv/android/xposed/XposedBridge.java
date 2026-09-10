package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Standard Xposed API shim.
 *
 * Modules call XposedBridge.isModuleEnabled(myPackageName) from their
 * own config UI to decide whether to display "Module is Active" or
 * "Module is Inactive".
 *
 * We answer by querying the ShizuPosed Manager's ModuleStatusProvider.
 * The provider is exported read-only, so no permission is required.
 */
public final class XposedBridge {

    /** Framework version reported to modules. LSPosed uses a 93.0xx scheme. */
    public static final int XPOSED_BRIDGE_VERSION = 93;

    private static final String AUTHORITY = "com.shizuposed.manager.status";

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    /**
     * Query the manager for the enabled state of `packageName`.
     *
     * If the manager isn't installed or the provider isn't reachable,
     * returns false (module shows "Inactive").
     */
    public static boolean isModuleEnabled(String packageName) {
        Context ctx = currentApplication();
        if (ctx == null) return false;
        return isModuleEnabled(ctx, packageName);
    }

    public static boolean isModuleEnabled(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/module/" + packageName);
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

    /** Optional: raw list of enabled packages. */
    public static String[] getEnabledModules(Context context) {
        if (context == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + AUTHORITY + "/modules");
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