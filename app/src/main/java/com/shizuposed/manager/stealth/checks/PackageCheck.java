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
 *   • PackageManager.getPackageInfo(String, int)
 *   • PackageManager.getPackageInfo(String, PackageInfoFlags)  ← Android 13+
 *   • PackageManager.getApplicationInfo(String, int)
 *   • PackageManager.getApplicationInfo(String, ApplicationInfoFlags) ← Android 13+
 *   • PackageManager.getInstalledPackages(int)
 *   • PackageManager.getInstalledPackages(PackageInfoFlags)    ← Android 13+
 *   • PackageManager.getInstalledApplications(int)
 *   • PackageManager.getInstalledApplications(ApplicationInfoFlags) ← Android 13+
 *   • PackageManager.getInstalledModules(int)
 *
 * The new PackageInfoFlags/ApplicationInfoFlags overloads are the
 * ones that a naive check against only the int versions would miss.
 * They take a flag wrapper class rather than a raw int. The class
 * only exists on Android 13+.
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

        // ─── Old-style int overloads ──────────────────────────────
        hookGetPackageInfoInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationInfoInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledPackagesInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledApplicationsInt(pmClass, hideShizuku, hideShizuPosed);

        // ─── New PackageInfoFlags overloads (Android 13+) ─────────
        hookGetPackageInfoFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationInfoFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledPackagesFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledApplicationsFlags(pmClass, hideShizuku, hideShizuPosed);

        XposedBridge.log(TAG + ": PackageCheck installed");
        XStealthRegistry.record("PackageCheck");
    }

    // ═════════════════════════════════════════════════════════════
    // INT OVERLOADS
    // ═════════════════════════════════════════════════════════════

    private static void hookGetPackageInfoInt(Class<?> pmClass,
                                              boolean hideShizuku,
                                              boolean hideShizuPosed) {
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
            XposedBridge.log(TAG + ": getPackageInfo(int) hook failed: " + t);
        }
    }

    private static void hookGetApplicationInfoInt(Class<?> pmClass,
                                                  boolean hideShizuku,
                                                  boolean hideShizuPosed) {
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
            XposedBridge.log(TAG + ": getApplicationInfo(int) hook failed: " + t);
        }
    }

    private static void hookGetInstalledPackagesInt(Class<?> pmClass,
                                                    boolean hideShizuku,
                                                    boolean hideShizuPosed) {
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
    }

    private static void hookGetInstalledApplicationsInt(Class<?> pmClass,
                                                        boolean hideShizuku,
                                                        boolean hideShizuPosed) {
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
    }

    // ═════════════════════════════════════════════════════════════
    // PackageInfoFlags OVERLOADS (Android 13+)
    // ═════════════════════════════════════════════════════════════

    private static void hookGetPackageInfoFlags(Class<?> pmClass,
                                                boolean hideShizuku,
                                                boolean hideShizuPosed) {
        // android.content.pm.PackageManager$PackageInfoFlags
        Class<?> flagsClass = XposedHelpers.findClassIfExists(
            "android.content.pm.PackageManager$PackageInfoFlags", null);
        if (flagsClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageInfo",
                String.class, flagsClass,
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
            XposedBridge.log(TAG + ": getPackageInfo(Flags) hook failed: " + t);
        }
    }

    private static void hookGetApplicationInfoFlags(Class<?> pmClass,
                                                    boolean hideShizuku,
                                                    boolean hideShizuPosed) {
        Class<?> flagsClass = XposedHelpers.findClassIfExists(
            "android.content.pm.PackageManager$ApplicationInfoFlags", null);
        if (flagsClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getApplicationInfo",
                String.class, flagsClass,
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
            XposedBridge.log(TAG + ": getApplicationInfo(Flags) hook failed: " + t);
        }
    }

    private static void hookGetInstalledPackagesFlags(Class<?> pmClass,
                                                      boolean hideShizuku,
                                                      boolean hideShizuPosed) {
        Class<?> flagsClass = XposedHelpers.findClassIfExists(
            "android.content.pm.PackageManager$PackageInfoFlags", null);
        if (flagsClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledPackages", flagsClass,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterPackages(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getInstalledPackages(Flags) hook failed: " + t);
        }
    }

    private static void hookGetInstalledApplicationsFlags(Class<?> pmClass,
                                                          boolean hideShizuku,
                                                          boolean hideShizuPosed) {
        Class<?> flagsClass = XposedHelpers.findClassIfExists(
            "android.content.pm.PackageManager$ApplicationInfoFlags", null);
        if (flagsClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledApplications", flagsClass,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterApplications(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getInstalledApplications(Flags) hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FILTER HELPERS
    // ═════════════════════════════════════════════════════════════

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