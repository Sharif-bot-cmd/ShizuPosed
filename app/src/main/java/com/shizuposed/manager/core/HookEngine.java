package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * HookEngine
 *
 * Front door to the hook subsystem. Registers all available backends
 * with HookDispatcher, chooses the primary one, and exposes the same
 * public API as before.
 *
 * Backends (in priority order):
 *   1. Pine (AUTO mode)         — fastest, works on most methods
 *   2. Pine (REPLACEMENT mode)  — slower, catches methods AUTO can't hook
 *   3. Amiru                    — per-method stub engine
 *   4. CallSite                 — interpreter table patching, for
 *                                 targets that detect entry-point
 *                                 patches on Pine/Amiru/Native
 *   5. Native                   — libshizuposed.so shared dispatcher
 *   6. Instrumentation          — Application / Activity lifecycle only
 *   7. Proxy                    — interface methods only, pure Java
 *   8. Noop                     — always succeeds, does nothing
 *
 * The dispatcher tries each in order per hook, so one backend failing
 * on a specific method doesn't take down the whole framework.
 *
 * FINGERPRINT TRACKING
 * --------------------
 * Every class that has had at least one method or constructor hooked
 * in this process is recorded in a static set. ApiProtectionCheck
 * reads this set at install time to pin method-count baselines on
 * every class the framework has touched.
 */
public class HookEngine {

    private static final String TAG = "HookEngine";
    private static HookEngine instance;

    private final ConcurrentHashMap<String, Method> registeredHooks = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean backendsInstalled = false;

    private static final Set<String> sHookedClasses =
        ConcurrentHashMap.newKeySet();

    /**
     * Shell-side libs dir, set once by XposedHook after it resolves
     * the shell base. Used by installBackends() to load libcallsite.so.
     */
    private static volatile String sShellLibsDir = null;

    /**
     * Called by XposedHook before ensureBackendInstalled(), so that
     * libcallsite.so can be loaded from the right directory.
     */
    public static void setShellLibsDir(String dir) {
        sShellLibsDir = dir;
    }

    private static void recordHookedClass(Class<?> declaringClass) {
        if (declaringClass == null) return;
        try {
            String name = declaringClass.getName();
            if (name != null && !name.isEmpty()) {
                sHookedClasses.add(name);
            }
        } catch (Throwable ignored) {}
    }

    public static Set<String> getHookedClassNames() {
        return new HashSet<>(sHookedClasses);
    }

    static void clearHookedClassNames() {
        sHookedClasses.clear();
    }

    private HookEngine() {}

    public static synchronized HookEngine getInstance() {
        if (instance == null) instance = new HookEngine();
        return instance;
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;
        installBackends();

        HookDispatcher d = HookDispatcher.getInstance();
        log("HookEngine initialized (primary="
            + (d.getPrimary() != null ? d.getPrimary().name() : "none") + ")");
    }

    public static void ensureBackendInstalled() {
        getInstance().installBackends();
    }

    // ═════════════════════════════════════════════════════════════════
    // BACKEND REGISTRATION
    // ═════════════════════════════════════════════════════════════════

    private synchronized void installBackends() {
        if (backendsInstalled) return;
        backendsInstalled = true;

        HookDispatcher d = HookDispatcher.getInstance();

        // 1. Pine AUTO mode — fastest when it works
        d.register(new com.shizuposed.manager.core.backends.PineBackend());

        // 2. Pine REPLACEMENT mode — catches methods Pine AUTO rejects
        d.register(new com.shizuposed.manager.core.backends.PineReplaceBackend());

        // 3. Amiru — per-method stub engine
        d.register(new com.shizuposed.manager.core.backends.AmiruBackend());

        // 4. CallSite — interpreter table patching.
        //    Attempt to load libcallsite.so first. If loading fails,
        //    the backend's isAvailable() returns false and the
        //    dispatcher skips it. The load attempt is cheap
        //    (System.load is a no-op if already loaded).
        try {
            String libDir = sShellLibsDir;
            if (libDir == null) {
                libDir = System.getProperty("shizuposed.shell.libs",
                    "/data/user/0/com.android.shell/files/libs");
            }
            com.shizuposed.manager.core.backends.CallSiteBackend
                .loadLibrary(libDir);
        } catch (Throwable t) {
            log("CallSiteBackend load failed: " + t.getMessage());
        }
        d.register(new com.shizuposed.manager.core.backends.CallSiteBackend());

        // 5. Native — libshizuposed.so shared dispatcher
        d.register(new com.shizuposed.manager.core.backends.NativeBackend());

        // 6. Instrumentation — Application / Activity lifecycle hooks
        d.register(new com.shizuposed.manager.core.backends.InstrumentationBackend());

        // 7. Proxy — interface methods only
        d.register(new com.shizuposed.manager.core.backends.ProxyBackend());

        // 8. Noop — always succeeds, never fails the caller
        d.register(new com.shizuposed.manager.core.backends.NoopBackend());

        d.initialize();

        XposedHookBridge.setBackend(new XposedHookBridge.HookBackend() {

            @Override
            public void hook(Method original, XC_MethodHook callback) {
                if (original == null || callback == null) return;

                recordHookedClass(original.getDeclaringClass());
                boolean ok = d.installHook(original, callback);

                if (ok) {
                    String key = original.getDeclaringClass().getName()
                        + "." + original.getName();
                    registeredHooks.put(key, original);
                }
            }

            @Override
            public void hookConstructor(Constructor<?> original, XC_MethodHook callback) {
                if (original == null || callback == null) return;

                recordHookedClass(original.getDeclaringClass());
                boolean ok = d.installConstructorHook(original, callback);
                if (ok) {
                    String key = original.getDeclaringClass().getName()
                        + ".<init>" + original.getParameterCount();
                    log("[dispatcher] hooked constructor " + key);
                }
            }
        });

        HookDispatcher.Backend primary = d.getPrimary();
        log("Backends installed. Primary="
            + (primary != null ? primary.name() : "none")
            + " | CallSite available="
            + com.shizuposed.manager.core.backends.CallSiteBackend.isLibraryLoaded());
    }

    // ═════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════

    public boolean hookMethod(Method originalMethod, XC_MethodHook callback) {
        if (!initialized) init();

        if (originalMethod != null) {
            recordHookedClass(originalMethod.getDeclaringClass());
        }

        try {
            XposedHookBridge.installHook(originalMethod, callback);
            return true;
        } catch (Throwable t) {
            log("hookMethod failed: " + t);
            return false;
        }
    }

    public boolean hookMethod(Method originalMethod, Object callback) {
        if (callback instanceof XC_MethodHook) {
            return hookMethod(originalMethod, (XC_MethodHook) callback);
        }
        log("hookMethod: callback is not XC_MethodHook");
        return false;
    }

    public boolean isHooked(Method method) {
        if (method == null) return false;
        String key = method.getDeclaringClass().getName() + "." + method.getName();
        return registeredHooks.containsKey(key);
    }

    public boolean isInitialized() { return initialized; }
    public int getHookedCount() { return registeredHooks.size(); }

    public HookDispatcher getDispatcher() {
        return HookDispatcher.getInstance();
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[HookEngine] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[HookEngine] " + msg);
    }
}