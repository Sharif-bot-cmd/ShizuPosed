package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * DevOptionsCheck
 *
 * Hides that Developer Options are enabled. Hooks
 * Settings.Global.getInt and Settings.Global.getString to return 0
 * or "0" for development-related keys.
 *
 * Keys hidden:
 *   • development_settings_enabled
 *   • adb_enabled
 *   • adb_wifi_enabled
 *   • development_enable_adi
 *
 * The hook preserves all other key lookups. It does not modify the
 * actual settings; only what the caller sees.
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

        // int getInt(ContentResolver cr, String name, int def)
        XposedHelpers.findAndHookMethod(
            settingsGlobal, "getInt",
            android.content.ContentResolver.class,
            String.class, int.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String key = (String) p.args[1];
                    if (isHiddenKey(key)) p.setResult(0);
                }
            });

        // String getString(ContentResolver cr, String name)
        XposedHelpers.findAndHookMethod(
            settingsGlobal, "getString",
            android.content.ContentResolver.class, String.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String key = (String) p.args[1];
                    if (isHiddenKey(key)) p.setResult("0");
                }
            });

        // long getLong(ContentResolver cr, String name, long def)
        XposedHelpers.findAndHookMethod(
            settingsGlobal, "getLong",
            android.content.ContentResolver.class,
            String.class, long.class,
            new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    String key = (String) p.args[1];
                    if (isHiddenKey(key)) p.setResult(0L);
                }
            });

        XposedBridge.log(TAG + ": DevOptionsCheck installed");
        XStealthRegistry.record("DevOptionsCheck");
    }

    private static boolean isHiddenKey(String key) {
        if (key == null) return false;
        for (String k : HIDDEN_KEYS) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }
}