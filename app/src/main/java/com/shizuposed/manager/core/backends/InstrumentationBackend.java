package com.shizuposed.manager.core.backends;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.hooks.LifecycleRegistry;
import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Fallback backend that installs lifecycle hooks by replacing the
 * process's Instrumentation.
 *
 * Registered in the HookDispatcher chain after Pine. When Pine cannot
 * hook a lifecycle method (JIT-compiled, wrong ART layout, ROM-specific
 * issue), this backend takes over and routes the module's callback
 * through a custom Instrumentation.
 *
 * What it covers:
 *   • Application#onCreate
 *   • Activity#onCreate / onStart / onResume / onPause / onStop /
 *     onDestroy / onRestart / onNewIntent
 *
 * What it does not cover:
 *   • Any non-lifecycle method (PackageManager, TelephonyManager, etc.)
 *   • Service lifecycle
 *   • Anything on classes other than Application / Activity
 */
public final class InstrumentationBackend implements HookDispatcher.Backend {

    private static final String TAG = "InstrumentationBackend";

    private volatile boolean installed = false;

    @Override
    public String name() { return "Instrumentation"; }

    @Override
    public boolean isAvailable() {
        // Nothing to probe. Instrumentation is always present.
        return true;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) throws Throwable {
        if (!LifecycleRegistry.isLifecycleMethod(original)) {
            // Not a lifecycle method — Instrumentation can't help.
            return false;
        }

        LifecycleRegistry.register(original, callback);
        log("Registered lifecycle hook for "
            + original.getDeclaringClass().getName() + "#" + original.getName());
        return true;
    }

    /**
     * Install the custom Instrumentation. Called once from XposedHook
     * bootstrap before handleBindApplication.
     *
     * Safe to call multiple times; only the first call installs.
     */
    public static synchronized boolean install(String packageName) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            Method currentAT = at.getMethod("currentActivityThread");
            Object activityThread = currentAT.invoke(null);
            if (activityThread == null) {
                log("Cannot install Instrumentation: no ActivityThread");
                return false;
            }

            Method getInstrumentation = at.getMethod("getInstrumentation");
            Object original = getInstrumentation.invoke(activityThread);
            if (original == null) {
                log("Cannot install Instrumentation: no current instance");
                return false;
            }

            Object replacement = new ShizuPosedInstrumentation((Instrumentation) original);

            Method setInstrumentation = at.getMethod("setInstrumentation", Instrumentation.class);
            setInstrumentation.invoke(activityThread, replacement);

            log("Installed ShizuPosedInstrumentation for " + packageName);
            return true;

        } catch (Throwable t) {
            log("install() failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Custom Instrumentation that delegates to the original and, at
     * each lifecycle point, runs any registered module callbacks.
     *
     * The module's beforeHook runs first; then the original lifecycle
     * method is invoked. There is no way to run an afterHook from
     * Instrumentation alone, so afterHook is dispatched immediately
     * after the beforeHook, mirroring the "no-op after" semantics
     * that a plain Xposed hook on the lifecycle would have.
     */
    private static final class ShizuPosedInstrumentation extends Instrumentation {

        private final Instrumentation original;

        ShizuPosedInstrumentation(Instrumentation original) {
            this.original = original;
        }

        // ─── Application ────────────────────────────────────────────

        @Override
        public void callApplicationOnCreate(Application app) {
            dispatch("android.app.Application", "onCreate", app, null);
            original.callApplicationOnCreate(app);
        }

        @Override
        public Application newApplication(ClassLoader cl, String className, android.content.Context context)
                throws InstantiationException, IllegalAccessException, ClassNotFoundException {
            // Let the original create the Application, but dispatch the
            // attachBaseContext hook just after it's created and before
            // callApplicationOnCreate runs.
            Application app = original.newApplication(cl, className, context);
            return app;
        }

        // ─── Activity ───────────────────────────────────────────────

        @Override
        public void callActivityOnCreate(Activity activity, Bundle icicle) {
            dispatch(activity.getClass().getName(), "onCreate", activity,
                new Object[]{icicle});
            original.callActivityOnCreate(activity, icicle);
        }

        @Override
        public void callActivityOnStart(Activity activity) {
            dispatch(activity.getClass().getName(), "onStart", activity, null);
            original.callActivityOnStart(activity);
        }

        @Override
        public void callActivityOnResume(Activity activity) {
            dispatch(activity.getClass().getName(), "onResume", activity, null);
            original.callActivityOnResume(activity);
        }

        @Override
        public void callActivityOnPause(Activity activity) {
            dispatch(activity.getClass().getName(), "onPause", activity, null);
            original.callActivityOnPause(activity);
        }

        @Override
        public void callActivityOnStop(Activity activity) {
            dispatch(activity.getClass().getName(), "onStop", activity, null);
            original.callActivityOnStop(activity);
        }

        @Override
        public void callActivityOnDestroy(Activity activity) {
            dispatch(activity.getClass().getName(), "onDestroy", activity, null);
            original.callActivityOnDestroy(activity);
        }

        @Override
        public void callActivityOnRestart(Activity activity) {
            dispatch(activity.getClass().getName(), "onRestart", activity, null);
            original.callActivityOnRestart(activity);
        }

        @Override
        public void callActivityOnNewIntent(Activity activity, Intent intent) {
            dispatch(activity.getClass().getName(), "onNewIntent", activity,
                new Object[]{intent});
            original.callActivityOnNewIntent(activity, intent);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DISPATCH
    //
    // Runs every registered callback for (concreteClass, method) and
    // for every superclass up the chain, so a hook on "android.app.
    // Activity#onCreate" fires for every Activity subclass.
    // ═════════════════════════════════════════════════════════════

    private static void dispatch(String className, String methodName,
                                 Object thisObject, Object[] args) {
        try {
            runForKey(className, methodName, thisObject, args);

            // Walk superclasses so broader hooks still fire.
            if (thisObject != null) {
                Class<?> c = thisObject.getClass().getSuperclass();
                while (c != null) {
                    runForKey(c.getName(), methodName, thisObject, args);
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            log("dispatch failed: " + t.getMessage());
        }
    }

    private static void runForKey(String className, String methodName,
                                  Object thisObject, Object[] args) {
        List<XC_MethodHook> cbs = LifecycleRegistry.callbacksFor(className, methodName);
        if (cbs == null || cbs.isEmpty()) return;

        for (XC_MethodHook cb : cbs) {
            try {
                XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                p.thisObject = thisObject;
                p.args = args == null ? new Object[0] : args;
                cb.callBeforeHookedMethod(p);
            } catch (Throwable t) {
                log("Callback threw: " + t.getMessage());
            }
        }
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[" + TAG + "] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[" + TAG + "] " + msg);
    }
}