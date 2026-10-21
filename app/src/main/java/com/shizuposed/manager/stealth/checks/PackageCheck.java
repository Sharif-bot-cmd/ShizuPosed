package com.shizuposed.manager.stealth.checks;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Parcel;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PackageCheck
 *
 * Hides Shizuku and ShizuPosed from PackageManager lookups, at
 * three levels:
 *
 *   Level 1 — PackageManager API hooks
 *     Every Java method on PackageManager / ApplicationPackageManager
 *     that can reveal Shizuku's or ShizuPosed's presence is hooked
 *     and either returns the platform's "absent package" shape or
 *     filters the list.
 *
 *   Level 2 — Binder.transact hook, IPackageManager
 *     A target that talks to the `package` service directly over
 *     Binder is caught here. The hook inspects calls whose
 *     descriptor is android.content.pm.IPackageManager, recovers
 *     the package name and the transaction code, and refuses the
 *     call for hidden packages by throwing RemoteException — the
 *     same shape the platform produces for an absent package.
 *
 *   Level 3 — Binder.transact hook, DUMP_TRANSACTION
 *     A target that shells out `dumpsys package <pkg>` via a
 *     Binder.dump(...) call sends a DUMP_TRANSACTION whose parcel
 *     starts with a file descriptor rather than an interface
 *     descriptor. The parser reads the args array, looks for a
 *     `package` / `packages` service name followed by the hidden
 *     package name, and refuses.
 *
 * A Binder.ProxyTransactListener is also installed for
 * observation. It fires on every proxy transaction and drives the
 * diagnostics counters; it cannot refuse, only observe.
 *
 * SCOPE
 * -----
 * The Binder hooks only see calls that go through the Java
 * IBinder.transact method in *this process*. They do not see:
 *
 *   • Calls from a helper process the target spawned.
 *   • Native code that opens /dev/binder directly.
 *   • Calls made before the hooks are installed.
 *
 * STEALTH BUILD
 * -------------
 * The package name to hide is read from XStealthConfig at install
 * time, not hardcoded. In the standard build it's
 * "com.shizuposed.manager"; in the stealth build it's the
 * scrambled com.<x>.<y>. This lets the same check code work for
 * both builds.
 */
public final class PackageCheck {

    private static final String TAG = "XStealth";

    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    /**
     * Actual package name to hide. Resolved from XStealthConfig
     * at install() time. Falls back to the standard name so an
     * old config that predates this field still hides the
     * standard package.
     */
    private static volatile String sShizuPosedPackage = null;

    private static String shizuPosedPackage() {
        String cached = sShizuPosedPackage;
        if (cached != null) return cached;
        String fromConfig = null;
        try {
            XStealthConfig cfg = XStealthConfig.load();
            if (cfg != null) fromConfig = cfg.shizuPosedPackageName;
        } catch (Throwable ignored) {}
        if (fromConfig != null && !fromConfig.isEmpty()) {
            sShizuPosedPackage = fromConfig;
            return fromConfig;
        }
        sShizuPosedPackage = "com.shizuposed.manager";
        return sShizuPosedPackage;
    }

    /** Binder descriptor for the package manager service. */
    private static final String PM_DESCRIPTOR =
        "android.content.pm.IPackageManager";

    private static final int DUMP_TRANSACTION = 1598311760;

    private static volatile Map<Integer, String> sPmCodes = null;

    private static final AtomicLong sTransactTotal     = new AtomicLong();
    private static final AtomicLong sTransactPmRefused = new AtomicLong();
    private static final AtomicLong sDumpRefused       = new AtomicLong();

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

        // Resolve the actual package name once, before any hooks
        // fire. Doing this at install time means the first query
        // is as correct as every subsequent one.
        shizuPosedPackage();

        hookGetPackageInfoInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationInfoInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledPackagesInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledApplicationsInt(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledModules(pmClass, hideShizuku, hideShizuPosed);

        hookGetPackageInfoFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationInfoFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledPackagesFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstalledApplicationsFlags(pmClass, hideShizuku, hideShizuPosed);

        hookGetPackagesForUid(pmClass, hideShizuku, hideShizuPosed);
        hookGetPackageUid(pmClass, hideShizuku, hideShizuPosed);
        hookGetPackageUidFlags(pmClass, hideShizuku, hideShizuPosed);
        hookGetNamesForUids(pmClass, hideShizuku, hideShizuPosed);

        hookGetInstallerPackageName(pmClass, hideShizuku, hideShizuPosed);
        hookGetInstallSourceInfo(pmClass, hideShizuku, hideShizuPosed);

        hookGetLaunchIntentForPackage(pmClass, hideShizuku, hideShizuPosed);
        hookGetLeanbackLaunchIntentForPackage(pmClass, hideShizuku, hideShizuPosed);
        hookGetApplicationEnabledSetting(pmClass, hideShizuku, hideShizuPosed);

        hookQueryIntentActivities(pmClass, hideShizuku, hideShizuPosed);
        hookResolveActivity(pmClass, hideShizuku, hideShizuPosed);
        hookQueryIntentServices(pmClass, hideShizuku, hideShizuPosed);

        buildPmCodeTable();
        hookBinderTransact(hideShizuku, hideShizuPosed);
        installTransactListener();

        XposedBridge.log(TAG + ": PackageCheck installed ("
            + "pm=API, binder="
            + (sPmCodes != null ? "with-code-table" : "descriptor-only")
            + ", dump=parsed, hidePkg=" + shizuPosedPackage() + ")");
        XStealthRegistry.record("PackageCheck");
    }

    public static String describeBinder() {
        return "transact=" + sTransactTotal.get()
            + " pmRefused=" + sTransactPmRefused.get()
            + " dumpRefused=" + sDumpRefused.get()
            + " pmCodes=" + (sPmCodes != null ? sPmCodes.size() : 0)
            + " hidePkg=" + shizuPosedPackage();
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 2 + 3 — BINDER TRANSACT HOOK
    // ═════════════════════════════════════════════════════════════

    private static void buildPmCodeTable() {
        if (sPmCodes != null) return;

        try {
            Class<?> pmStub = Class.forName(
                "android.content.pm.IPackageManager$Stub");
            if (pmStub == null) return;

            Map<Integer, String> table = new java.util.HashMap<>();
            for (Field f : pmStub.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(mods)) continue;
                if (!java.lang.reflect.Modifier.isFinal(mods)) continue;
                if (f.getType() != int.class) continue;
                try {
                    f.setAccessible(true);
                    int code = f.getInt(null);
                    String name = f.getName();
                    if (name.startsWith("TRANSACTION_")) {
                        name = name.substring("TRANSACTION_".length());
                    }
                    table.put(code, name);
                } catch (Throwable ignored) {}
            }

            if (!table.isEmpty()) {
                sPmCodes = table;
                XposedBridge.log(TAG + ": PM code table built ("
                    + table.size() + " entries)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PM code table build failed: " + t);
        }
    }

    private static void hookBinderTransact(final boolean hideShizuku,
                                            final boolean hideShizuPosed) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.BinderProxy", null, "transact",
                int.class, Parcel.class, Parcel.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        sTransactTotal.incrementAndGet();

                        int code = (Integer) p.args[0];
                        Parcel data = (Parcel) p.args[1];
                        if (data == null) return;

                        if (code == DUMP_TRANSACTION) {
                            checkDump(data, hideShizuku, hideShizuPosed);
                            return;
                        }

                        String descriptor = readInterfaceDescriptor(data);
                        if (descriptor == null
                                || !descriptor.equals(PM_DESCRIPTOR)) {
                            return;
                        }

                        String method = (sPmCodes != null)
                            ? sPmCodes.get(code) : null;

                        if (isLookupByNameMethod(method)) {
                            String pkg = readFirstString(data);
                            if (shouldHide(pkg, hideShizuku, hideShizuPosed)) {
                                sTransactPmRefused.incrementAndGet();
                                XposedBridge.log(TAG + ": Binder "
                                    + "IPackageManager." + (method != null
                                        ? method : ("code=" + code))
                                    + " refused for " + pkg);
                                throw new android.os.RemoteException(
                                    "Package " + pkg + " not found");
                            }
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Binder.transact hook failed: " + t);
        }
    }

    private static void checkDump(Parcel data,
                                   boolean hideShizuku,
                                   boolean hideShizuPosed)
            throws android.os.RemoteException {
        int pos = data.dataPosition();
        try {
            try { data.readString(); } catch (Throwable ignored) {}
            try { data.readFileDescriptor(); } catch (Throwable ignored) {}

            String[] args = null;
            try {
                args = data.createStringArray();
            } catch (Throwable ignored) {}
            if (args == null || args.length == 0) return;

            boolean isPackageDump = false;
            for (String a : args) {
                if (a == null) continue;
                if ("package".equals(a) || "packages".equals(a)
                        || "package_native".equals(a)) {
                    isPackageDump = true;
                    break;
                }
            }
            if (!isPackageDump) return;

            String hit = null;
            for (String a : args) {
                if (a == null) continue;
                if (shouldHide(a, hideShizuku, hideShizuPosed)) {
                    hit = a;
                    break;
                }
            }
            if (hit == null) return;

            sDumpRefused.incrementAndGet();
            XposedBridge.log(TAG + ": Binder DUMP refused for " + hit);
            throw new android.os.RemoteException(
                "package " + hit + " not found");
        } catch (android.os.RemoteException re) {
            throw re;
        } catch (Throwable parseErr) {
        } finally {
            try { data.setDataPosition(pos); }
            catch (Throwable ignored) {}
        }
    }

    private static void installTransactListener() {
        try {
            Class<?> bpClass = Class.forName("android.os.BinderProxy");
            Class<?> listenerClass = Class.forName(
                "android.os.Binder$ProxyTransactListener");
            if (bpClass == null || listenerClass == null) return;

            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{ listenerClass },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onTransactStarted":
                            return new Object();
                        case "onTransactEnded":
                            return null;
                        default:
                            return null;
                    }
                });

            Method setter = bpClass.getDeclaredMethod(
                "setTransactListener", listenerClass);
            setter.setAccessible(true);
            setter.invoke(null, listener);
            XposedBridge.log(TAG + ": ProxyTransactListener installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ProxyTransactListener install failed: "
                + t);
        }
    }

    private static String readInterfaceDescriptor(Parcel data) {
        int pos = data.dataPosition();
        try {
            return data.readString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { data.setDataPosition(pos); } catch (Throwable ignored) {}
        }
    }

    private static String readFirstString(Parcel data) {
        int pos = data.dataPosition();
        try {
            data.readString();
            return data.readString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { data.setDataPosition(pos); } catch (Throwable ignored) {}
        }
    }

    private static boolean isLookupByNameMethod(String method) {
        if (method == null) return false;
        switch (method) {
            case "getPackageInfo":
            case "getPackageInfoVersioned":
            case "getApplicationInfo":
            case "getApplicationInfoVersioned":
            case "getPackageUid":
            case "getPackageUidVersioned":
            case "getInstallerPackageName":
            case "getInstallSourceInfo":
            case "getLaunchIntentForPackage":
            case "getLeanbackLaunchIntentForPackage":
            case "getApplicationEnabledSetting":
            case "getComponentEnabledSetting":
                return true;
            default:
                return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 1 — PACKAGE INFORMATION (INT OVERLOADS)
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
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 1 — FLAGS OVERLOADS (Android 13+)
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
    // LEVEL 1 — UID-BASED LOOKUP
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
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 1 — INSTALL SOURCE
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
        }
    }

    private static void hookGetInstallSourceInfo(Class<?> pmClass,
                                                 boolean hideShizuku,
                                                 boolean hideShizuPosed) {
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
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 1 — LAUNCHABILITY
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
    // LEVEL 1 — INTENT RESOLUTION
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
        if (hideShizuku && SHIZUKU_PKG.equals(pkg)) return true;
        if (hideShizuPosed && shizuPosedPackage().equals(pkg)) return true;
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