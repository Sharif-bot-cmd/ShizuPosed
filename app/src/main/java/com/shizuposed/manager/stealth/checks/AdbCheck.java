package com.shizuposed.manager.stealth.checks;

import android.content.ContentResolver;
import android.database.MatrixCursor;
import android.net.Uri;

import com.shizuposed.manager.stealth.XStealthRegistry;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AdbCheck
 *
 * Hides ADB being enabled. Overlaps with DevOptionsCheck but covers
 * Settings.Secure lookups that DevOptionsCheck does not touch, plus
 * the Settings.Global keys that some detectors read via different
 * call sites.
 *
 * Keys hidden:
 *   • adb_enabled
 *   • adb_wifi_enabled
 *
 * Settings.Secure and Settings.Global both expose the same keys via
 * different APIs; the caller does not always know which one it is
 * reading from.
 *
 * Also hooks ContentResolver.query for content://settings/secure/...
 * and content://settings/global/..., so direct queries against the
 * settings database are intercepted the same way the static getters
 * are.
 */
public final class AdbCheck {

    private static final String TAG = "XStealth";

    private static final String[] HIDDEN_KEYS = {
        "adb_enabled",
        "adb_wifi_enabled",
    };

    private AdbCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookSettingsClass(lpparam, "android.provider.Settings$Global");
        hookSettingsClass(lpparam, "android.provider.Settings$Secure");
        hookContentResolverQuery();
        XStealthRegistry.record("AdbCheck");
    }

    private static void hookSettingsClass(XC_LoadPackage.LoadPackageParam lpparam,
                                          String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(
            className, lpparam.classLoader);
        if (clazz == null) return;

        // ─── getInt(ContentResolver, String, int) ────────────────
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + ".getInt hook failed: " + t);
        }

        // ─── getString(ContentResolver, String) ──────────────────
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + ".getString hook failed: " + t);
        }

        // ─── getLong(ContentResolver, String, long) ──────────────
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getLong",
                ContentResolver.class, String.class, long.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0L);
                    }
                });
        } catch (Throwable t) {
            // Not always declared on Settings.Secure.
        }

        // ─── getIntForUser(ContentResolver, String, int, int) ────
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getIntForUser",
                ContentResolver.class, String.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            // Not always available.
        }

        // ─── getStringForUser(ContentResolver, String, int) ──────
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getStringForUser",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            // Not always available.
        }

        XposedBridge.log(TAG + ": AdbCheck installed on " + className);
    }

    /**
     * Intercept direct ContentResolver queries against ADB keys.
     * Same pattern as DevOptionsCheck, but for adb_enabled and
     * adb_wifi_enabled. Covers both /global/ and /secure/ namespaces
     * because both exist and are kept in sync by the framework.
     */
    private static void hookContentResolverQuery() {
        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver.class, "query",
                Uri.class, String[].class, String.class,
                String[].class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        Uri uri = (Uri) p.args[0];
                        if (uri == null) return;
                        String key = extractSettingsKey(uri);
                        if (key == null) return;
                        if (!isHiddenKey(key)) return;

                        MatrixCursor c = new MatrixCursor(
                            new String[]{"_id", "name", "value"});
                        c.addRow(new Object[]{0, key, "0"});
                        p.setResult(c);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AdbCheck query hook failed: " + t);
        }
    }

    private static String extractSettingsKey(Uri uri) {
        if (uri == null) return null;
        String authority = uri.getAuthority();
        if (!"settings".equals(authority)) return null;
        String path = uri.getPath();
        if (path == null) return null;
        if (!path.startsWith("/global/") && !path.startsWith("/secure/")) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return null;
        return path.substring(slash + 1);
    }

    private static boolean isHiddenKey(String keyOrUri) {
        if (keyOrUri == null) return false;
        String key = keyOrUri;
        int slash = keyOrUri.lastIndexOf('/');
        if (slash >= 0 && slash < keyOrUri.length() - 1) {
            key = keyOrUri.substring(slash + 1);
        }
        for (String k : HIDDEN_KEYS) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }
}