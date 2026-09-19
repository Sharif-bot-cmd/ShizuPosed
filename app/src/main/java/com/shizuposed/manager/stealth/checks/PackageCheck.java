package com.shizuposed.manager.stealth.checks;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PackageCheck
 *
 * Hides Shizuku and ShizuPosed from PackageManager lookups. Detectors
 * commonly call getPackageInfo("moe.shizuku.privileged.api") or
 * iterate getInstalledPackages() looking for names they recognize.
 *
 * Hooks installed:
 *   • PackageManager.getPackageInfo            → throws NameNotFoundException
 *   • PackageManager.getApplicationInfo        → throws NameNotFoundException
 *   • PackageManager.getInstalledPackages      → filters the result list
 *   • PackageManager.getInstalledApplications  → filters the result list
 *   • PackageManager.getPackageInfo (2-arg)    → throws NameNotFoundException
 *
 * Filtering preserves the original list's order and does not modify
 * the caller's other expectations about the list.
 */
public final class PackageCheck {

    private static final String TAG = "XStealth";

    public static final String SHIZUKU_PKG    = "moe.shizuku.privileged.api";
    public static final String SHIZUPOSED_PKG = "com.shizuposed.manager";

    private PackageCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam,
                               XStealthConfig config) {
        Class<?> pmClass = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager", lpparam.classLoader);
        if (pmClass == null) {
            pmClass = android.content.pm.PackageManager.class;
        }

        final boolean hideShizuku    = config.hideShizukuPackage;
        final boolean hideShizuPosed = config.hideShizuPosedPackage;

        // ─── getPackageInfo(String, int) ───────────────────────────
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageInfo",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            throw new android.content.pm.PackageManager
                                .NameNotFoundException(pkg);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getPackageInfo hook failed: " + t);
        }

        // ─── getApplicationInfo(String, int) ───────────────────────
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getApplicationInfo",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            throw new android.content.pm.PackageManager
                                .NameNotFoundException(pkg);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getApplicationInfo hook failed: " + t);
        }

        // ─── getInstalledPackages(int) ─────────────────────────────
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledPackages", int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterPackages(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getInstalledPackages hook failed: " + t);
        }

        // ─── getInstalledApplications(int) ─────────────────────────
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledApplications", int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterApplications(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getInstalledApplications hook failed: " + t);
        }

        XposedBridge.log(TAG + ": PackageCheck installed");
        XStealthRegistry.record("PackageCheck");
    }

    private static boolean shouldHide(String pkg, boolean hideShizuku,
                                      boolean hideShizuPosed) {
        if (pkg == null) return false;
        if (hideShizuku    && SHIZUKU_PKG.equals(pkg))    return true;
        if (hideShizuPosed && SHIZUPOSED_PKG.equals(pkg)) return true;
        return false;
    }

    private static List<PackageInfo> filterPackages(List<?> in,
                                                    boolean hideShizuku,
                                                    boolean hideShizuPosed) {
        if (in == null) return null;
        List<PackageInfo> out = new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof PackageInfo)) continue;
            PackageInfo pi = (PackageInfo) o;
            if (shouldHide(pi.packageName, hideShizuku, hideShizuPosed)) continue;
            out.add(pi);
        }
        return out;
    }

    private static List<ApplicationInfo> filterApplications(List<?> in,
                                                            boolean hideShizuku,
                                                            boolean hideShizuPosed) {
        if (in == null) return null;
        List<ApplicationInfo> out = new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ApplicationInfo)) continue;
            ApplicationInfo ai = (ApplicationInfo) o;
            if (shouldHide(ai.packageName, hideShizuku, hideShizuPosed)) continue;
            out.add(ai);
        }
        return out;
    }
}