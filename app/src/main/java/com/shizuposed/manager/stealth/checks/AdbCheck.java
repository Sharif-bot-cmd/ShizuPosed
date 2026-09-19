package com.shizuposed.manager.stealth.checks;

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
        XStealthRegistry.record("AdbCheck");
    }

    private static void hookSettingsClass(XC_LoadPackage.LoadPackageParam lpparam,
                                          String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(
            className, lpparam.classLoader);
        if (clazz == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                android.content.ContentResolver.class,
                String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + ".getInt hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getString",
                android.content.ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (isHiddenKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + ".getString hook failed: " + t);
        }

        XposedBridge.log(TAG + ": AdbCheck installed on " + className);
    }

    private static boolean isHiddenKey(String key) {
        if (key == null) return false;
        for (String k : HIDDEN_KEYS) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }
}