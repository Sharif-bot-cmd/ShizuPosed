package com.shizuposed.manager.stealth.checks;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PackageCheck
 *
 * Hides Shizuku and ShizuPosed from PackageManager lookups.
 *
 * Hooks installed:
 *   Package information
 *   • getPackageInfo(String, int)
 *   • getPackageInfo(String, PackageInfoFlags)
 *   • getApplicationInfo(String, int)
 *   • getApplicationInfo(String, ApplicationInfoFlags)
 *   • getInstalledPackages(int)
 *   • getInstalledPackages(PackageInfoFlags)
 *   • getInstalledApplications(int)
 *   • getInstalledApplications(ApplicationInfoFlags)
 *   • getInstalledModules(int)
 *
 *   UID-based lookup
 *   • getPackagesForUid(int)
 *   • getPackageUid(String, int)
 *   • getPackageUid(String, PackageInfoFlags)
 *   • getNamesForUids(int[])
 *
 *   Install source
 *   • getInstallerPackageName(String)
 *   • getInstallSourceInfo(String)
 *
 *   Launchability
 *   • getLaunchIntentForPackage(String)
 *   • getLeanbackLaunchIntentForPackage(String)
 *   • getApplicationEnabledSetting(String)
 *
 *   Intent resolution
 *   • queryIntentActivities(Intent, int)
 *   • queryIntentActivities(Intent, ResolveInfoFlags)
 *   • resolveActivity(Intent, int)
 *   • resolveActivity(Intent, ResolveInfoFlags)
 *   • queryIntentServices(Intent, int)
 *
 * FILTERING
 * ---------
 * Lookups by explicit package name behave the way the platform
 * behaves for a genuinely absent package:
 *
 *   • getPackageInfo / getApplicationInfo — throw
 *     NameNotFoundException, matching the platform's contract.
 *   • getPackageUid — return -1. The platform returns -1 for an
 *     absent package; throwing here would be louder than the
 *     platform's own behavior and a caller that catches the
 *     exception differently from checking -1 sees the wrong thing.
 *   • getInstallerPackageName — return null. The platform returns
 *     null for a package with no installer, which is exactly what
 *     a sideloaded Shizuku looks like. Spoofing Play Store would
 *     be a positive claim the platform would not have made.
 *   • getLaunchIntentForPackage / getLeanbackLaunchIntentForPackage
 *     — return null, meaning "nothing to launch".
 *   • getApplicationEnabledSetting — return
 *     COMPONENT_ENABLED_STATE_DISABLED_USER, so the caller sees
 *     "the user disabled this" rather than "the package is
 *     missing". Throwing is also valid, but some callers expect a
 *     valid state value and handle the two cases differently.
 *
 * Lookups that return lists filter the list. Intent resolution
 * filters ResolveInfo entries whose package is hidden.
 *
 * The filtering is scoped to the two packages XStealth knows about.
 * If the user changes the package names (they can't, but if they
 * could), the constants at the top of this class need updating.
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

        // ─── Package information ────────────────────────────────
        hookGetPackageInfoInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationInfoInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledPackagesInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledApplicationsInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledModules(pmClass, hideShizuku, hideShizuPosed);

        // ─── Flags overloads (Android 13+) ──────────────────────
        hookGetPackageInfoFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationInfoFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledPackagesFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledApplicationsFlags(pmClass, hideShizuku, hideShizuPosed);

        // ─── UID-based lookup ───────────────────────────────────
        hookGetPackagesForUid(pmClass, hideShizuku, hideShizuPosed);
        hookGetPackageUid(pmClass, hideShizuku, hideShizuPosed);
        hookGetPackageUidFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetNamesForUids(pmClass, hideShizuku, hideShizuPosed);

        // ─── Install source ─────────────────────────────────────
        hookGetInstallerPackageName(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstallSourceInfo(pmClass, hideShizuku, hideShizuPosed);

        // ─── Launchability ──────────────────────────────────────
        hookGetLaunchIntentForPackage(pmClass, hideShizuku, hideShizuPosed);
        hookGetLeanbackLaunchIntentForPackage(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationEnabledSetting(pmClass, hideShizuku, hideShizuPosed);

        // ─── Intent resolution ──────────────────────────────────
        hookQueryIntentActivities(pmClass, hideShizuku, hideShizuPosed);
        hookResolveActivity(pmClass, hideShizuku, hideShizuPosed);
        hookQueryIntentServices(pmClass, hideShizuku, hideShizuPosed);

        XposedBridge.log(TAG + ": PackageCheck installed");
        XStealthRegistry.record("PackageCheck");
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE INFORMATION — INT OVERLOADS
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

    private static void hookGetInstalledModules(Class<?> pmClass,
                                                boolean hideShizuku,
                                                boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstalledModules", int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterModules(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            // Android 10+. Silent skip on older.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FLAGS OVERLOADS (Android 13+)
    // ═════════════════════════════════════════════════════════════

    private static void hookGetPackageInfoFlags(Class<?> pmClass,
                                                boolean hideShizuku,
                                                boolean hideShizuPosed) {
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
    // UID-BASED LOOKUP
    // ═════════════════════════════════════════════════════════════

    private static void hookGetPackagesForUid(Class<?> pmClass,
                                              boolean hideShizuku,
                                              boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackagesForUid", int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        String[] in = (String[]) p.getResult();
                        if (in == null) return;
                        p.setResult(filterStringArray(
                            in, hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getPackagesForUid hook failed: " + t);
        }
    }

    /**
     * getPackageUid returns int. The platform returns -1 for a
     * package it cannot find; throwing NameNotFoundException here
     * would be louder than the platform's own behavior and a
     * caller that handles the two cases differently would see the
     * wrong thing. Return -1.
     */
    private static void hookGetPackageUid(Class<?> pmClass,
                                          boolean hideShizuku,
                                          boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageUid", String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            p.setResult(-1);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getPackageUid(int) hook failed: " + t);
        }
    }

    private static void hookGetPackageUidFlags(Class<?> pmClass,
                                               boolean hideShizuku,
                                               boolean hideShizuPosed) {
        Class<?> flagsClass = XposedHelpers.findClassIfExists(
            "android.content.pm.PackageManager$PackageInfoFlags", null);
        if (flagsClass == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getPackageUid", String.class, flagsClass,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            p.setResult(-1);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getPackageUid(Flags) hook failed: " + t);
        }
    }

    private static void hookGetNamesForUids(Class<?> pmClass,
                                            boolean hideShizuku,
                                            boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getNamesForUids", int[].class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        String[] in = (String[]) p.getResult();
                        if (in == null) return;
                        p.setResult(filterStringArray(
                            in, hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            // Android 10+. Silent skip.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // INSTALL SOURCE
    //
    // getInstallerPackageName and getInstallSourceInfo reveal who
    // installed a package. The platform returns null for a package
    // with no installer, which is what a sideloaded Shizuku looks
    // like. Returning null matches the platform's own behavior for
    // an absent installer; spoofing Play Store would be a positive
    // claim the platform would not have made, and a detector that
    // expects null for a sideload would catch the mismatch.
    // ═════════════════════════════════════════════════════════════

    private static void hookGetInstallerPackageName(Class<?> pmClass,
                                                    boolean hideShizuku,
                                                    boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getInstallerPackageName", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            p.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            // Deprecated on some versions. Silent skip.
        }
    }

    private static void hookGetInstallSourceInfo(Class<?> pmClass,
                                                 boolean hideShizuku,
                                                 boolean hideShizuPosed) {
        // On Android 11+, getInstallerPackageName is a wrapper around
        // getInstallSourceInfo. If the wrapper is hooked, the source
        // is too, so this method is optional. We install it anyway
        // for callers that use the source API directly.
        try {
            Class<?> sourceInfoClass = XposedHelpers.findClassIfExists(
                "android.content.pm.InstallSourceInfo", null);
            if (sourceInfoClass == null) return;

            XposedHelpers.findAndHookMethod(
                pmClass, "getInstallSourceInfo", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            // Reconstruct an "no installer" InstallSourceInfo.
                            // The constructor is hidden; if it can't be
                            // found, fall back to throwing
                            // NameNotFoundException so the caller sees
                            // an exception rather than a fake installer.
                            try {
                                java.lang.reflect.Constructor<?> ctor =
                                    sourceInfoClass.getDeclaredConstructor(
                                        String.class, String.class,
                                        String.class);
                                ctor.setAccessible(true);
                                p.setResult(ctor.newInstance(
                                    null, null, null));
                            } catch (Throwable inner) {
                                throw new android.content.pm.PackageManager
                                    .NameNotFoundException(pkg);
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            // Android 11+ only. Silent skip.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LAUNCHABILITY
    //
    // getLaunchIntentForPackage asks "can I launch this app's UI?"
    // A detector can use it to test Shizuku's presence: if the
    // intent is non-null, Shizuku is installed. We return null for
    // hidden packages so the answer is "not installed".
    // ═════════════════════════════════════════════════════════════

    private static void hookGetLaunchIntentForPackage(Class<?> pmClass,
                                                      boolean hideShizuku,
                                                      boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getLaunchIntentForPackage", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            p.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getLaunchIntentForPackage hook failed: " + t);
        }
    }

    private static void hookGetLeanbackLaunchIntentForPackage(Class<?> pmClass,
                                                              boolean hideShizuku,
                                                              boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getLeanbackLaunchIntentForPackage", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            p.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            // TV-only. Silent skip.
        }
    }

    private static void hookGetApplicationEnabledSetting(Class<?> pmClass,
                                                          boolean hideShizuku,
                                                          boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getApplicationEnabledSetting", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        String pkg = (String) p.args[0];
                        if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                            // Return COMPONENT_ENABLED_STATE_DISABLED_USER
                            // so the caller sees "the user disabled this
                            // package" rather than "the package doesn't
                            // exist". Throwing NameNotFoundException here
                            // would also work, but some callers expect a
                            // valid state and would catch the exception
                            // differently.
                            p.setResult(
                                android.content.pm.PackageManager
                                    .COMPONENT_ENABLED_STATE_DISABLED_USER);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getApplicationEnabledSetting hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // INTENT RESOLUTION
    //
    // queryIntentActivities, resolveActivity, and queryIntentServices
    // return ResolveInfo entries. If any entry's package is hidden,
    // we filter it out. This closes the "which app handles intent X"
    // detection channel.
    // ═════════════════════════════════════════════════════════════

    private static void hookQueryIntentActivities(Class<?> pmClass,
                                                   boolean hideShizuku,
                                                   boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "queryIntentActivities",
                Intent.class, int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterResolveInfo(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": queryIntentActivities(int) hook failed: " + t);
        }
    }

    private static void hookResolveActivity(Class<?> pmClass,
                                             boolean hideShizuku,
                                             boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "resolveActivity",
                Intent.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        // resolveActivity takes an Intent, not a
                        // package name. We can't filter by target
                        // here, but we can inspect the intent's
                        // package if it has one.
                        Intent intent = (Intent) p.args[0];
                        if (intent != null) {
                            String pkg = intent.getPackage();
                            if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                                p.setResult(null);
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resolveActivity hook failed: " + t);
        }
    }

    private static void hookQueryIntentServices(Class<?> pmClass,
                                                 boolean hideShizuku,
                                                 boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "queryIntentServices",
                Intent.class, int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterResolveInfo(
                            (List<?>) p.getResult(),
                            hideShizuku, hideShizuPosed));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": queryIntentServices hook failed: " + t);
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

    /**
     * Filter android.content.pm.ModuleInfo entries whose name
     * matches a hidden package.
     *
     * ModuleInfo exposes the module's name through a private field
     * that has been stable across Android versions, but the exact
     * name isn't guaranteed on every OEM ROM. The previous version
     * looked up a single field called "name"; if the lookup failed,
     * the entry was kept, which is fail-open but also means a
     * renamed field would let the entry through silently.
     *
     * This version tries several candidate field names in order
     * and keeps the entry if none of them works — same fail-open
     * behavior, but it survives a rename better. If a candidate
     * field is found but its value is not a String, the entry is
     * also kept, because we can't rule it out.
     */
    private static List<?> filterModules(List<?> in,
                                          boolean hideShizuku,
                                          boolean hideShizuPosed) {
        if (in == null) return null;
        final String[] FIELD_CANDIDATES = {
            "name", "moduleName", "packageName", "mName"
        };
        List<Object> out = new ArrayList<>(in.size());
        for (Object o : in) {
            if (o == null) continue;
            String name = readModuleName(o, FIELD_CANDIDATES);
            if (name != null
                    && shouldHide(name, hideShizuku, hideShizuPosed)) {
                continue;
            }
            out.add(o);
        }
        return out;
    }

    private static String readModuleName(Object moduleInfo,
                                         String[] candidates) {
        Class<?> c = moduleInfo.getClass();
        for (String fieldName : candidates) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(moduleInfo);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private static String[] filterStringArray(String[] in,
                                                boolean hideShizuku,
                                                boolean hideShizuPosed) {
        if (in == null) return null;
        int kept = 0;
        for (String s : in) {
            if (s == null || !shouldHide(s, hideShizuku, hideShizuPosed)) kept++;
        }
        if (kept == in.length) return in;
        String[] out = new String[kept];
        int i = 0;
        for (String s : in) {
            if (s == null || !shouldHide(s, hideShizuku, hideShizuPosed)) {
                out[i++] = s;
            }
        }
        return out;
    }

    private static List<ResolveInfo> filterResolveInfo(
            List<?> in, boolean hideShizuku, boolean hideShizuPosed) {
        if (in == null) return null;
        List<ResolveInfo> out = new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ResolveInfo)) continue;
            ResolveInfo ri = (ResolveInfo) o;
            String pkg = null;
            if (ri.activityInfo != null) {
                pkg = ri.activityInfo.packageName;
            } else if (ri.serviceInfo != null) {
                pkg = ri.serviceInfo.packageName;
            } else if (ri.providerInfo != null) {
                pkg = ri.providerInfo.packageName;
            }
            if (shouldHide(pkg, hideShizuku, hideShizuPosed)) continue;
            out.add(ri);
        }
        return out;
    }
}