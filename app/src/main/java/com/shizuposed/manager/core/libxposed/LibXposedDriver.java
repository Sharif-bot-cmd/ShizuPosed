package com.shizuposed.manager.core.libxposed;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.shizuposed.manager.model.ModuleInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LibXposedDriver
 *
 * The libxposed counterpart to XposedHook's classic module loader.
 *
 * Called from XposedHook.loadModule() when isLibXposedModule()
 * returns true. Does not require any compile-time dependency on the
 * libxposed API — all references are by name, resolved through the
 * module's own classloader, so a ShizuPosed build that does not
 * ship the libxposed interfaces still compiles and links.
 *
 * LIFECYCLE
 * ---------
 * onModuleLoaded    — fires inside driveModule(), right after attach.
 * onPackageLoaded   — fires inside driveModule(), after the module
 *                     classloader is known.
 * onPackageReady    — fires via firePackageReady(), called by
 *                     XposedHook AFTER bindApplication succeeds.
 *
 * Do not fire onPackageReady from driveModule — at that point the
 * Application object does not exist yet, and modules that read from
 * it (e.g. Application.getResources()) would NPE.
 */
public final class LibXposedDriver {

    private static final String TAG = "ShizuPosed";

    private LibXposedDriver() {}

    // ═════════════════════════════════════════════════════════════
    // DETECTION
    // ═════════════════════════════════════════════════════════════

    /**
     * True if `moduleClass` extends io.github.libxposed.api.XposedModule.
     *
     * Fail-open: if the libxposed API isn't present on this build's
     * classpath, returns false so a classic module still takes the
     * classic path.
     */
    public static boolean isLibXposedModule(Class<?> moduleClass) {
        if (moduleClass == null) return false;
        try {
            Class<?> base = Class.forName(
                "io.github.libxposed.api.XposedModule",
                false,
                moduleClass.getClassLoader());
            return base.isAssignableFrom(moduleClass);
        } catch (Throwable t) {
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DRIVE — onModuleLoaded + onPackageLoaded
    // ═════════════════════════════════════════════════════════════

    /**
     * Attach the framework to `instance`, fire onModuleLoaded and
     * onPackageLoaded, and register the instance in the caller's
     * module list.
     *
     * Returns true if the module loaded without throwing.
     *
     * `runtimeInfo` is the runtime ModuleInfo from XposedHook
     * (packageName, xposedInit, cachedDexPath, enabled, hookedApps,
     * hookAllApps, hookSystemApps). It is converted internally into
     * com.shizuposed.manager.model.ModuleInfo, which is what the
     * concrete XposedInterface expects.
     */
    public static boolean driveModule(
            Object instance,
            Class<?> moduleClass,
            ClassLoader moduleClassLoader,
            ClassLoader appLoader,
            LoadPackageParam classicParam,
            Object runtimeInfo) {

        if (instance == null || moduleClass == null) {
            log("driveModule: null instance or class");
            return false;
        }

        try {
            ModuleInfo modelModule = toModelModuleInfo(runtimeInfo);

            // 1. Build the XposedInterface implementation this
            //    module will be attached to.
            ShizuPosedXposedInterface framework =
                new ShizuPosedXposedInterface(modelModule, moduleClassLoader);

            // 2. attachFramework. XposedModule extends
            //    XposedInterfaceWrapper, which exposes
            //    attachFramework(XposedInterface, Runnable).
            Runnable detach = () -> {
                try {
                    HookRegistry.unhookAllForModule(modelModule.packageName);
                } catch (Throwable t) {
                    log("detach for " + modelModule.packageName
                        + " failed: " + t.getMessage());
                }
            };

            Class<?> wrapperClass = Class.forName(
                "io.github.libxposed.api.XposedInterfaceWrapper",
                false,
                moduleClass.getClassLoader());
            Method attach = wrapperClass.getMethod("attachFramework",
                Class.forName("io.github.libxposed.api.XposedInterface",
                    false, moduleClass.getClassLoader()),
                Runnable.class);
            attach.setAccessible(true);
            attach.invoke(instance, framework, detach);
            log("attachFramework OK for " + modelModule.packageName);

            // 3. onModuleLoaded(ModuleLoadedParam)
            Object moduleLoadedParam = buildModuleLoadedParam(
                runtimeProcessName(classicParam),
                false /* ShizuPosed cannot hook system_server */);
            invokeLifecycle(instance, "onModuleLoaded", moduleLoadedParam);

            // 4. onPackageLoaded(PackageLoadedParam) — API 29+
            if (Build.VERSION.SDK_INT >= 29) {
                Object pkgLoadedParam = buildPackageLoadedParam(
                    classicParam.packageName,
                    classicParam.appInfo,
                    classicParam.isFirstApplication,
                    moduleClassLoader);
                invokeLifecycle(instance, "onPackageLoaded", pkgLoadedParam);
            } else {
                log("onPackageLoaded skipped (SDK "
                    + Build.VERSION.SDK_INT + " < 29)");
            }

            return true;

        } catch (Throwable t) {
            log("driveModule failed for "
                + (moduleClass != null ? moduleClass.getName() : "null")
                + ": " + t);
            t.printStackTrace();
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE READY — fires after Application.onCreate
    // ═════════════════════════════════════════════════════════════

    /**
     * Fire onPackageReady(PackageReadyParam) on a module instance.
     *
     * Must be called AFTER the Application has been created. In
     * bootstrap mode, that means after bindApplication succeeds. In
     * post-application mode, after currentApplication() returns
     * non-null.
     *
     * The AppComponentFactory is obtained from the Application via
     * reflectively reading the modern accessor. When unavailable,
     * onPackageReady still fires with a null factory; modules that
     * do not use getAppComponentFactory() are unaffected.
     */
    public static void firePackageReady(
            Object instance,
            Class<?> moduleClass,
            ClassLoader moduleClassLoader,
            String packageName,
            ApplicationInfo appInfo,
            boolean isFirstApplication,
            Application app,
            ClassLoader appLoader) {

        if (instance == null) return;
        if (Build.VERSION.SDK_INT < 28) {
            log("onPackageReady skipped (SDK "
                + Build.VERSION.SDK_INT + " < 28)");
            return;
        }

        try {
            Object factory = null;
            if (app != null) {
                try {
                    Method getACF = app.getClass()
                        .getMethod("getAppComponentFactory");
                    getACF.setAccessible(true);
                    factory = getACF.invoke(app);
                } catch (NoSuchMethodException ignored) {
                    // Pre-API-28 ROM. Interface is @RequiresApi(28)
                    // so this path is only reachable on unusual
                    // devices; the null factory is acceptable.
                }
            }

            Object param = buildPackageReadyParam(
                packageName, appInfo, isFirstApplication,
                moduleClassLoader, factory);
            invokeLifecycle(instance, "onPackageReady", param);

            log("onPackageReady OK for " + packageName
                + " (instance=" + moduleClass.getName() + ")");
        } catch (Throwable t) {
            log("firePackageReady failed for " + packageName + ": " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PARAM BUILDERS
    // ═════════════════════════════════════════════════════════════

    private static Object buildModuleLoadedParam(final String processName,
                                                 final boolean isSystemServer) {
        return new io.github.libxposed.api.XposedModuleInterface
            .ModuleLoadedParam() {
                @Override public boolean isSystemServer() { return isSystemServer; }
                @androidx.annotation.NonNull
                @Override public String getProcessName() {
                    return processName != null ? processName : "";
                }
            };
    }

    private static Object buildPackageLoadedParam(
            final String packageName,
            final ApplicationInfo appInfo,
            final boolean isFirst,
            final ClassLoader classLoader) {

        return new io.github.libxposed.api.XposedModuleInterface
            .PackageLoadedParam() {
                @androidx.annotation.NonNull
                @Override public String getPackageName() { return packageName; }
                @androidx.annotation.NonNull
                @Override public ApplicationInfo getApplicationInfo() {
                    return appInfo != null ? appInfo : new ApplicationInfo();
                }
                @Override public boolean isFirstPackage() { return isFirst; }
                @androidx.annotation.NonNull
                @Override public ClassLoader getDefaultClassLoader() {
                    return classLoader;
                }
            };
    }

    private static Object buildPackageReadyParam(
            final String packageName,
            final ApplicationInfo appInfo,
            final boolean isFirst,
            final ClassLoader classLoader,
            final Object appComponentFactory) {

        return new io.github.libxposed.api.XposedModuleInterface
            .PackageReadyParam() {
                @androidx.annotation.NonNull
                @Override public String getPackageName() { return packageName; }
                @androidx.annotation.NonNull
                @Override public ApplicationInfo getApplicationInfo() {
                    return appInfo != null ? appInfo : new ApplicationInfo();
                }
                @Override public boolean isFirstPackage() { return isFirst; }
                @androidx.annotation.NonNull
                @Override public ClassLoader getDefaultClassLoader() {
                    return classLoader;
                }
                @androidx.annotation.NonNull
                @Override public ClassLoader getClassLoader() {
                    return classLoader;
                }
                @androidx.annotation.NonNull
                @Override public android.app.AppComponentFactory
                        getAppComponentFactory() {
                    return (android.app.AppComponentFactory) appComponentFactory;
                }
            };
    }

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE INVOCATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Invoke one of the XposedModuleInterface default methods on
     * `instance`, by name.
     *
     * getMethods() is used (not getDeclaredMethods()) so the
     * interface's default implementation is reachable when the
     * module has not overridden the method.
     */
    private static void invokeLifecycle(Object instance,
                                        String methodName,
                                        Object param) {
        if (instance == null) return;
        try {
            for (Method m : instance.getClass().getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != 1) continue;
                m.setAccessible(true);
                m.invoke(instance, param);
                return;
            }
            // No match — module doesn't override it, and getMethods()
            // should have returned the default. Fall through with a
            // log entry: this is a diagnostic, not an error.
            log("invokeLifecycle: " + methodName
                + " not found on " + instance.getClass().getName());
        } catch (Throwable t) {
            log("invokeLifecycle(" + methodName + ") threw: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    private static String runtimeProcessName(LoadPackageParam p) {
        if (p == null) return "";
        if (p.processName != null && !p.processName.isEmpty()) {
            return p.processName;
        }
        return p.packageName != null ? p.packageName : "";
    }

    /**
     * Convert XposedHook's private runtime ModuleInfo into the
     * public com.shizuposed.manager.model.ModuleInfo. Reflection is
     * used so this file compiles even in builds where the runtime
     * ModuleInfo is not on the compile classpath (e.g. if XposedHook
     * is stripped from the manager module for size).
     */
    private static ModuleInfo toModelModuleInfo(Object runtimeInfo) {
        ModuleInfo out = new ModuleInfo();
        if (runtimeInfo == null) return out;

        try {
            Class<?> rt = runtimeInfo.getClass();

            out.packageName = getStringField(rt, runtimeInfo, "packageName");
            out.name        = getStringField(rt, runtimeInfo, "name");
            out.xposedInit  = getStringField(rt, runtimeInfo, "xposedInit");
            out.cachedDexPath = getStringField(rt, runtimeInfo, "cachedDexPath");

            try {
                java.lang.reflect.Field f = rt.getField("enabled");
                f.setAccessible(true);
                out.enabled = f.getBoolean(runtimeInfo);
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Field f = rt.getField("hookAllApps");
                f.setAccessible(true);
                out.hookAllApps = f.getBoolean(runtimeInfo);
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Field f = rt.getField("hookSystemApps");
                f.setAccessible(true);
                out.hookSystemApps = f.getBoolean(runtimeInfo);
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Field f = rt.getField("hookedApps");
                f.setAccessible(true);
                Object v = f.get(runtimeInfo);
                if (v instanceof java.util.Set) {
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> s = (java.util.Set<String>) v;
                    out.hookedApps = new java.util.HashSet<>(s);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            log("toModelModuleInfo failed: " + t.getMessage());
        }
        return out;
    }

    private static String getStringField(Class<?> rt, Object instance,
                                          String name) {
        try {
            java.lang.reflect.Field f = rt.getField(name);
            f.setAccessible(true);
            Object v = f.get(instance);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
        // XposedHook's log() also writes to the launch log file.
        // Calling it reflectively keeps this class compilable even
        // if XposedHook's private log is later renamed.
        try {
            Class<?> xh = Class.forName(
                "com.shizuposed.manager.core.XposedHook",
                false, LibXposedDriver.class.getClassLoader());
            Method log = xh.getDeclaredMethod("log", String.class);
            log.setAccessible(true);
            log.invoke(null, msg);
        } catch (Throwable ignored) {}
    }
}