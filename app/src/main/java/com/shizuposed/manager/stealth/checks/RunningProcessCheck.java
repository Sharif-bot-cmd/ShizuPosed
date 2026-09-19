package com.shizuposed.manager.stealth.checks;

import android.app.ActivityManager;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RunningProcessCheck
 *
 * Hides Shizuku, ShizuPosed, and app_process from ActivityManager
 * process and service listings. Detectors use these APIs to check
 * whether a privileged helper is running on the device.
 *
 * Hooks installed:
 *   • ActivityManager.getRunningAppProcesses   → filters the result
 *   • ActivityManager.getRunningServices       → filters the result
 *
 * Filtering preserves the input list's contents for everything except
 * the hidden packages, so callers see the device as if Shizuku and
 * ShizuPosed were not installed or not running.
 */
public final class RunningProcessCheck {

    private static final String TAG = "XStealth";

    private static final String SHIZUKU_PKG    = "moe.shizuku.privileged.api";
    private static final String SHIZUPOSED_PKG = "com.shizuposed.manager";

    private RunningProcessCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> amClass = android.app.ActivityManager.class;

        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getRunningAppProcesses",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterProcesses(
                            (List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getRunningAppProcesses hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getRunningServices", int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterServices(
                            (List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getRunningServices hook failed: " + t);
        }

        XposedBridge.log(TAG + ": RunningProcessCheck installed");
        XStealthRegistry.record("RunningProcessCheck");
    }

    private static List<ActivityManager.RunningAppProcessInfo> filterProcesses(
            List<?> in) {
        if (in == null) return null;
        List<ActivityManager.RunningAppProcessInfo> out =
            new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ActivityManager.RunningAppProcessInfo)) continue;
            ActivityManager.RunningAppProcessInfo info =
                (ActivityManager.RunningAppProcessInfo) o;
            if (shouldHide(info.processName)) continue;
            out.add(info);
        }
        return out;
    }

    private static List<ActivityManager.RunningServiceInfo> filterServices(
            List<?> in) {
        if (in == null) return null;
        List<ActivityManager.RunningServiceInfo> out =
            new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ActivityManager.RunningServiceInfo)) continue;
            ActivityManager.RunningServiceInfo info =
                (ActivityManager.RunningServiceInfo) o;
            if (info.service == null) continue;
            String pkg = info.service.getPackageName();
            if (shouldHide(pkg)) continue;
            out.add(info);
        }
        return out;
    }

    private static boolean shouldHide(String pkgOrProcess) {
        if (pkgOrProcess == null) return false;
        if (pkgOrProcess.startsWith(SHIZUKU_PKG))    return true;
        if (pkgOrProcess.startsWith(SHIZUPOSED_PKG)) return true;
        if (pkgOrProcess.equals("app_process"))      return true;
        if (pkgOrProcess.contains("com.android.shell")) return false; // legitimate
        return false;
    }
}