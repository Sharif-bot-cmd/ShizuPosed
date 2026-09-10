package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * HookEngine — Pine 0.3.0 backend, exposed via XposedHookBridge.
 */
public class HookEngine {

    private static final String TAG = "HookEngine";
    private static HookEngine instance;

    private final ConcurrentHashMap<String, Method> registeredHooks = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean pineInstalled = false;

    private HookEngine() {}

    public static synchronized HookEngine getInstance() {
        if (instance == null) instance = new HookEngine();
        return instance;
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;
        installPineBackend();
        log("HookEngine initialized (backend="
            + XposedHookBridge.getBackend().getClass().getSimpleName() + ")");
    }

    public static void ensureBackendInstalled() {
        getInstance().installPineBackend();
    }

    // ─── Pine backend ────────────────────────────────────────────────

    private synchronized void installPineBackend() {
        if (pineInstalled) return;

        XposedHookBridge.setBackend(new XposedHookBridge.HookBackend() {

            @Override
            public void hook(Method original, XC_MethodHook callback) {
                if (original == null || callback == null) return;

                try { original.setAccessible(true); } catch (Throwable ignored) {}

                try {
                    Pine.hook(original, new MethodHook() {

                        @Override
                        public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                            XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();
                            mp.thisObject = callFrame.thisObject;
                            mp.args = callFrame.args;

                            callback.callBeforeHookedMethod(mp);

                            if (mp.hasThrowable) {
                                throw mp.getThrowable();
                            }
                            if (mp.hasResult) {
                                callFrame.setResult(mp.getResult());
                            }
                        }

                        @Override
                        public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                            XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();
                            mp.thisObject = callFrame.thisObject;
                            mp.args = callFrame.args;
                            mp.setResult(callFrame.getResult());

                            callback.callAfterHookedMethod(mp);

                            if (mp.hasThrowable) {
                                throw mp.getThrowable();
                            }
                            if (mp.hasResult) {
                                callFrame.setResult(mp.getResult());
                            }
                        }
                    });

                    String key = original.getDeclaringClass().getName()
                        + "." + original.getName();
                    registeredHooks.put(key, original);
                    log("[Pine] hooked " + key);

                } catch (Throwable t) {
                    log("[Pine] hook FAILED on "
                        + original.getDeclaringClass().getName() + "."
                        + original.getName() + " — " + t);
                }
            }

            @Override
            public void hookConstructor(Constructor<?> original, XC_MethodHook callback) {
                if (original == null || callback == null) return;

                try { original.setAccessible(true); } catch (Throwable ignored) {}

                try {
                    Pine.hook(original, new MethodHook() {

                        @Override
                        public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                            XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();
                            mp.thisObject = callFrame.thisObject;
                            mp.args = callFrame.args;

                            callback.callBeforeHookedMethod(mp);

                            if (mp.hasThrowable) throw mp.getThrowable();
                            if (mp.hasResult) callFrame.setResult(mp.getResult());
                        }

                        @Override
                        public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                            XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();
                            mp.thisObject = callFrame.thisObject;
                            mp.args = callFrame.args;
                            mp.setResult(callFrame.getResult());

                            callback.callAfterHookedMethod(mp);

                            if (mp.hasThrowable) throw mp.getThrowable();
                            if (mp.hasResult) callFrame.setResult(mp.getResult());
                        }
                    });

                    String key = original.getDeclaringClass().getName()
                        + ".<init>" + original.getParameterCount();
                    log("[Pine] hooked constructor " + key);

                } catch (Throwable t) {
                    log("[Pine] ctor hook FAILED on "
                        + original.getDeclaringClass().getName() + " — " + t);
                }
            }
        });

        pineInstalled = true;
    }

    // ─── public API (kept stable) ────────────────────────────────────

    public boolean hookMethod(Method originalMethod, XC_MethodHook callback) {
        if (!initialized) init();
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

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[HookEngine] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[HookEngine] " + msg);
    }
}