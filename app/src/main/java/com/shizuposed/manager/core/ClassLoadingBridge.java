package com.shizuposed.manager.core;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class ClassLoadingBridge {

    private static final String TAG = "XStealth.ClassLoading";

    public interface ClassLoadListener {
        void beforeClassLoaded(String className, ClassLoader loader);
    }

    public interface LoaderCreatedListener {
        void onClassLoaderCreated(ClassLoader loader);
    }

    private static final List<ClassLoadListener> sClassLoadListeners =
        new ArrayList<>();

    private static final List<LoaderCreatedListener> sLoaderListeners =
        new ArrayList<>();

    private static final Map<String, Boolean> sInFlight =
        new ConcurrentHashMap<>();

    private static final AtomicLong sClassesPreloaded = new AtomicLong();
    private static final AtomicLong sLoadersSeen     = new AtomicLong();
    private static final AtomicLong sErrors           = new AtomicLong();

    private static volatile boolean sInstalled = false;

    private ClassLoadingBridge() {}

    public static boolean isInstalled() { return sInstalled; }
    public static long getClassesPreloaded() { return sClassesPreloaded.get(); }
    public static long getLoadersSeen()      { return sLoadersSeen.get(); }
    public static long getErrors()           { return sErrors.get(); }

    public static void registerClassLoadListener(ClassLoadListener l) {
        if (l == null) return;
        synchronized (sClassLoadListeners) {
            if (!sClassLoadListeners.contains(l)) sClassLoadListeners.add(l);
        }
    }

    public static void registerLoaderListener(LoaderCreatedListener l) {
        if (l == null) return;
        synchronized (sLoaderListeners) {
            if (!sLoaderListeners.contains(l)) sLoaderListeners.add(l);
        }
    }

    public static synchronized void install(ClassLoader appLoader) {
        if (sInstalled) return;
        hookLoadClass();
        hookDexClassLoaderConstruction();
        hookPathClassLoaderConstruction();
        sInstalled = true;
        XposedBridge.log(TAG + ": ClassLoadingBridge installed");
    }

    // ═════════════════════════════════════════════════════════════
    // 1. ClassLoader.loadClass
    // ═════════════════════════════════════════════════════════════

    private static void hookLoadClass() {
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "loadClass",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String name = (String) p.args[0];
                            if (name == null || name.isEmpty()) return;
                            if (isFrameworkClass(name)) return;
                            if (sInFlight.putIfAbsent(name, Boolean.TRUE) != null) {
                                return;
                            }
                            try {
                                ClassLoader target = (ClassLoader) p.thisObject;
                                notifyClassLoadListeners(name, target);
                            } finally {
                                sInFlight.remove(name);
                            }
                        } catch (Throwable t) {
                            sErrors.incrementAndGet();
                            XposedBridge.log(TAG
                                + ": loadClass listener error: " + t);
                        }
                    }
                });
            XposedBridge.log(TAG + ": loadClass hook installed");
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(TAG + ": loadClass hook failed: " + t);
        }
    }

    private static boolean isFrameworkClass(String name) {
        return name.startsWith("java.")
            || name.startsWith("javax.")
            || name.startsWith("android.")
            || name.startsWith("androidx.")
            || name.startsWith("kotlin.")
            || name.startsWith("kotlinx.")
            || name.startsWith("dalvik.")
            || name.startsWith("libcore.")
            || name.startsWith("com.android.internal.")
            || name.startsWith("de.robv.android.xposed.")
            || name.startsWith("com.shizuposed.manager.");
    }

    private static void notifyClassLoadListeners(String name,
                                                 ClassLoader loader) {
        ClassLoadListener[] snapshot;
        synchronized (sClassLoadListeners) {
            if (sClassLoadListeners.isEmpty()) return;
            snapshot = sClassLoadListeners.toArray(new ClassLoadListener[0]);
        }
        for (ClassLoadListener l : snapshot) {
            try {
                l.beforeClassLoaded(name, loader);
            } catch (Throwable t) {
                sErrors.incrementAndGet();
                XposedBridge.log(TAG + ": listener threw for "
                    + name + ": " + t);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 2. Loader constructors
    //
    // Uses the shim's findAndHookConstructor. Each form is hooked
    // individually; if a signature is missing on a given ROM, that
    // call returns null and the others still install.
    // ═════════════════════════════════════════════════════════════

    private static void hookDexClassLoaderConstruction() {
        // DexClassLoader(String dexPath, String optimizedDirectory,
        //                 String librarySearchPath, ClassLoader parent)
        try {
            XposedHelpers.findAndHookConstructor(
                DexClassLoader.class,
                String.class, String.class, String.class, ClassLoader.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        notifyLoaderListeners((ClassLoader) p.thisObject);
                    }
                });
            XposedBridge.log(TAG + ": DexClassLoader(4-arg) hooked");
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(TAG + ": DexClassLoader(4-arg) failed: " + t);
        }

        // Some ROMs have a 5-arg form with an extra ClassLoader for
        // the library loader. Hook it separately if it exists.
        try {
            XposedHelpers.findAndHookConstructor(
                DexClassLoader.class,
                String.class, String.class, String.class, ClassLoader.class,
                ClassLoader.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        notifyLoaderListeners((ClassLoader) p.thisObject);
                    }
                });
        } catch (Throwable ignored) {
            // Not all versions declare this form.
        }
    }

    private static void hookPathClassLoaderConstruction() {
        // PathClassLoader(String dexPath, ClassLoader parent)
        try {
            XposedHelpers.findAndHookConstructor(
                PathClassLoader.class,
                String.class, ClassLoader.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        notifyLoaderListeners((ClassLoader) p.thisObject);
                    }
                });
        } catch (Throwable ignored) {
            // Not all versions declare this form.
        }

        // PathClassLoader(String dexPath, String librarySearchPath,
        //                 ClassLoader parent)
        try {
            XposedHelpers.findAndHookConstructor(
                PathClassLoader.class,
                String.class, String.class, ClassLoader.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        notifyLoaderListeners((ClassLoader) p.thisObject);
                    }
                });
        } catch (Throwable ignored) {
            // Silently skip if neither form is available.
        }
    }

    private static void notifyLoaderListeners(ClassLoader loader) {
        if (loader == null) return;
        sLoadersSeen.incrementAndGet();
        LoaderCreatedListener[] snapshot;
        synchronized (sLoaderListeners) {
            if (sLoaderListeners.isEmpty()) return;
            snapshot = sLoaderListeners.toArray(new LoaderCreatedListener[0]);
        }
        for (LoaderCreatedListener l : snapshot) {
            try {
                l.onClassLoaderCreated(loader);
            } catch (Throwable t) {
                sErrors.incrementAndGet();
                XposedBridge.log(TAG + ": loader listener threw: " + t);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 3. Component pre-load
    // ═════════════════════════════════════════════════════════════

    public static int preloadComponentClasses(String pkg,
                                              ApplicationInfo appInfo,
                                              ClassLoader appLoader) {
        if (pkg == null || appInfo == null || appLoader == null) return 0;

        int loaded = 0;
        loaded += preloadReceiverClasses(appInfo, appLoader);
        loaded += preloadProviderClasses(appInfo, appLoader);

        XposedBridge.log(TAG + ": preloadComponentClasses: loaded "
            + loaded + " component class(es) for " + pkg);
        sClassesPreloaded.addAndGet(loaded);
        return loaded;
    }

    private static int preloadReceiverClasses(ApplicationInfo appInfo,
                                              ClassLoader appLoader) {
        int loaded = 0;
        try {
            PackageManager pm = resolvePackageManager();
            if (pm == null) return 0;

            @SuppressWarnings("deprecation")
            int flags = PackageManager.GET_RECEIVERS
                | PackageManager.GET_DISABLED_COMPONENTS;

            PackageInfo pi = pm.getPackageInfo(appInfo.packageName, flags);
            if (pi == null || pi.receivers == null) return 0;

            for (ActivityInfo ai : pi.receivers) {
                if (ai == null || ai.name == null) continue;
                if (loadClassQuietly(ai.name, appLoader)) loaded++;
            }
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(TAG + ": preloadReceiverClasses failed: "
                + t.getMessage());
        }
        return loaded;
    }

    private static int preloadProviderClasses(ApplicationInfo appInfo,
                                              ClassLoader appLoader) {
        int loaded = 0;
        try {
            PackageManager pm = resolvePackageManager();
            if (pm == null) return 0;

            @SuppressWarnings("deprecation")
            int flags = PackageManager.GET_PROVIDERS
                | PackageManager.GET_DISABLED_COMPONENTS;

            PackageInfo pi = pm.getPackageInfo(appInfo.packageName, flags);
            if (pi == null || pi.providers == null) return 0;

            for (ProviderInfo pi2 : pi.providers) {
                if (pi2 == null || pi2.name == null) continue;
                if (loadClassQuietly(pi2.name, appLoader)) loaded++;
            }
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(TAG + ": preloadProviderClasses failed: "
                + t.getMessage());
        }
        return loaded;
    }

    private static PackageManager resolvePackageManager() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method getPM = at.getMethod("getPackageManager");
            Object pm = getPM.invoke(null);
            if (pm instanceof PackageManager) {
                return (PackageManager) pm;
            }
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(TAG + ": resolvePackageManager failed: "
                + t.getMessage());
        }
        return null;
    }

    private static boolean loadClassQuietly(String name, ClassLoader loader) {
        try {
            Class<?> c = loader.loadClass(name);
            if (c != null) {
                XposedBridge.log(TAG + ": preloaded " + name);
                return true;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": preload of " + name
                + " failed: " + t.getMessage());
        }
        return false;
    }
}