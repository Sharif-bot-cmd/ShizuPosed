package com.shizuposed.manager.stealth.checks;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.shizuposed.manager.stealth.XStealthRegistry;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * DevOptionsCheck
 *
 * Hides that Developer Options are enabled. Hooks every common
 * path an app uses to read the setting:
 *
 *   • Settings.Global.getInt / getString / getLong
 *   • Settings.Global.getIntForUser / getStringForUser / getLongForUser
 *   • ContentResolver.query on content://settings/global/<key>
 *
 * Keys hidden:
 *   • development_settings_enabled
 *   • adb_enabled
 *   • adb_wifi_enabled
 *   • development_enable_adi
 *
 * The hook preserves all other key lookups. It does not modify the
 * actual settings; only what the caller sees.
 *
 * The direct ContentResolver.query path is the one that defeats a
 * naive hook-only approach. Most banking apps use it in addition
 * to the static getters, so hooking only the getters is not enough.
 *
 * Return values from query() are a MatrixCursor shaped like the
 * real settings cursor: columns "name" and "value". Apps that
 * expect those columns find them. Apps that expect columns we
 * don't provide fall through to their own error handling, which
 * for a detection check means "not detected" — the desired outcome.
 */
public final class DevOptionsCheck {

    private static final String TAG = "XStealth";

    private static final String[] HIDDEN_KEYS = {
        "development_settings_enabled",
        "adb_enabled",
        "adb_wifi_enabled",
        "development_enable_adi",
    };

    private DevOptionsCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> settingsGlobal = XposedHelpers.findClassIfExists(
            "android.provider.Settings$Global", lpparam.classLoader);
        if (settingsGlobal == null) return;

        hookStaticGetters(settingsGlobal);
        hookForUserGetters(settingsGlobal);
        hookContentResolverQuery();

        XposedBridge.log(TAG + ": DevOptionsCheck installed");
        XStealthRegistry.record("DevOptionsCheck");
    }

    // ═════════════════════════════════════════════════════════════
    // Settings.Global static getters
    // ═════════════════════════════════════════════════════════════

    private static void hookStaticGetters(Class<?> settingsGlobal) {
        // int getInt(ContentResolver cr, String name, int def)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getInt",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getInt hook failed: " + t);
        }

        // String getString(ContentResolver cr, String name)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getString hook failed: " + t);
        }

        // long getLong(ContentResolver cr, String name, long def)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getLong",
                ContentResolver.class, String.class, long.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0L);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getLong hook failed: " + t);
        }

        // float getFloat(ContentResolver cr, String name, float def)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getFloat",
                ContentResolver.class, String.class, float.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0f);
                    }
                });
        } catch (Throwable t) {
            // getFloat isn't always declared. Not fatal.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Settings.Global ForUser variants
    // ═════════════════════════════════════════════════════════════

    private static void hookForUserGetters(Class<?> settingsGlobal) {
        // int getIntForUser(ContentResolver cr, String name, int def, int userId)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getIntForUser",
                ContentResolver.class, String.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getIntForUser hook failed: " + t);
        }

        // String getStringForUser(ContentResolver cr, String name, int userId)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getStringForUser",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getStringForUser hook failed: " + t);
        }

        // long getLongForUser(ContentResolver cr, String name, long def, int userId)
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getLongForUser",
                ContentResolver.class, String.class, long.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0L);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getLongForUser hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ContentResolver.query — the direct path most apps use
    // ═════════════════════════════════════════════════════════════

    /**
     * Intercept direct queries against content://settings/global/<key>
     * and content://settings/secure/<key>. If the requested key is
     * one of the hidden ones, return a synthetic cursor whose value
     * column reads 0.
     *
     * The cursor shape matches what SettingsProvider returns: columns
     * "_id", "name", "value". Apps that expect those columns find
     * them.
     *
     * If the query has a WHERE clause filtering on name, we check
     * the name we're returning is consistent with the filter. If not,
     * we return an empty cursor. The app then reads zero rows and
     * treats the setting as absent, which is the desired outcome.
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

                        String selection = (String) p.args[2];
                        if (selection != null && !selectionMatches(selection, key)) {
                            // The query filters on a different name.
                            // Return an empty cursor so the caller
                            // sees no rows.
                            p.setResult(new MatrixCursor(
                                new String[]{"_id", "name", "value"}));
                            return;
                        }

                        MatrixCursor c = new MatrixCursor(
                            new String[]{"_id", "name", "value"});
                        c.addRow(new Object[]{0, key, "0"});
                        p.setResult(c);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ContentResolver.query hook failed: " + t);
        }
    }

    /**
     * Extract the setting key from a settings URI. Returns null for
     * any URI that isn't content://settings/{global,secure,<namespace>}/<key>.
     */
    private static String extractSettingsKey(Uri uri) {
        if (uri == null) return null;
        String authority = uri.getAuthority();
        if (!"settings".equals(authority)) return null;
        String path = uri.getPath();
        if (path == null) return null;
        // Paths look like /global/<key> or /secure/<key>.
        if (!path.startsWith("/global/") && !path.startsWith("/secure/")) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return null;
        return path.substring(slash + 1);
    }

    /**
     * Does the caller's WHERE clause reference the key we want to
     * hide? If it does, we can return a fake row. If it references
     * a different key, we should return no rows.
     */
    private static boolean selectionMatches(String selection, String key) {
        if (selection == null || key == null) return false;
        // Cheap check: does the selection contain the key name?
        // Full SQL parsing isn't needed here — SettingsProvider
        // queries typically use "name=?" or "name = ?" patterns.
        return selection.contains(key) || selection.contains("name");
    }

    // ═════════════════════════════════════════════════════════════
    // Key matching
    // ═════════════════════════════════════════════════════════════

    /**
     * Is this string one of the hidden keys?
     *
     * Accepts three forms:
     *   • "development_settings_enabled"
     *   • "content://settings/global/development_settings_enabled"
     *   • "/global/development_settings_enabled"
     *
     * The last two are useful when a caller passes a URI-shaped
     * string where a plain key was expected. Some apps do this by
     * mistake, or by design to sidestep naive hooks.
     */
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