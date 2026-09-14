package com.shizuposed.manager.core.backends;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.CompatLog;
import com.shizuposed.manager.core.hooks.LifecycleRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Fallback backend that installs lifecycle hooks by replacing the
 * process's Instrumentation instance.
 *
 * Installation: ActivityThread has no public setInstrumentation().
 * We reflect the private mInstrumentation field and write a custom
 * Instrumentation into it. This works because app_process runs as
 * shell uid (2000), which is exempt from the hidden-API restrictions
 * that would block this on a normal app.
 *
 * Coverage:
 *   • Application#attachBaseContext, Application#onCreate
 *   • Activity#onCreate / onStart / onResume / onPause / onStop /
 *     onDestroy / onRestart / onNewIntent
 *
 * Not covered:
 *   • Non-lifecycle methods (PackageManager, TelephonyManager, etc.)
 *   • Service lifecycle
 *   • Anything not on Application or Activity
 *
 * After-hook semantics: Instrumentation has no post-call hook point
 * for lifecycle methods (it *is* the dispatcher). Registered callbacks
 * run as before-hooks only. afterHookedMethod is never invoked from
 * this backend. Callers that need after-hooks must rely on Pine.
 */
public final class InstrumentationBackend implements HookDispatcher.Backend {

    private static final String TAG = "InstrumentationBackend";

    @Override
    public String name() { return "Instrumentation"; }

    @Override
    public boolean isAvailable() {
        // Instrumentation is always present. Whether it can be
        // *installed* depends on whether we can write the field, which
        // is checked at install() time.
        return true;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;
        if (!LifecycleRegistry.isLifecycleMethod(original)) {
            return false;
        }
        LifecycleRegistry.register(original, callback);
        CompatLog.d(TAG, "Registered lifecycle hook for "
                + original.getDeclaringClass().getName() + "#" + original.getName());
        return true;
    }

    /**
     * Install the custom Instrumentation into the current process.
     *
     * Must be called from bootstrap, after ActivityThread.currentActivityThread()
     * returns non-null and before handleBindApplication creates the
     * Application. Safe to call more than once; only the first call
     * installs. Returns true if the process now has a ShizuPosed
     * Instrumentation (either installed by us or already present).
     */
    public static boolean install(String packageName) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentAT = at.getMethod("currentActivityThread");
            Object activityThread = currentAT.invoke(null);
            if (activityThread == null) {
                CompatLog.w(TAG, "install: no ActivityThread yet", null);
                return false;
            }

            Field field = at.getDeclaredField("mInstrumentation");
            field.setAccessible(true);
            Object current = field.get(activityThread);

            if (current instanceof ShizuPosedInstrumentation) {
                CompatLog.d(TAG, "install: already installed");
                return true;
            }
            if (!(current instanceof Instrumentation)) {
                CompatLog.w(TAG,
                        "install: mInstrumentation is not an Instrumentation: "
                                + (current == null ? "null" : current.getClass().getName()),
                        null);
                return false;
            }

            ShizuPosedInstrumentation replacement =
                    new ShizuPosedInstrumentation((Instrumentation) current);
            field.set(activityThread, replacement);

            // Verify the write actually took.
            Object check = field.get(activityThread);
            if (check != replacement) {
                CompatLog.w(TAG, "install: field write did not stick", null);
                return false;
            }

            CompatLog.d(TAG, "Installed ShizuPosedInstrumentation for " + packageName);
            return true;

        } catch (NoSuchFieldException nsfe) {
            CompatLog.w(TAG,
                    "install: ActivityThread.mInstrumentation not found "
                            + "(layout changed on this ROM)", nsfe);
            return false;
        } catch (Throwable t) {
            CompatLog.w(TAG, "install failed", t);
            return false;
        }
    }

    /**
     * True if the given Instrumentation is one of ours. Useful for
     * callers that want to know whether install() has already run.
     */
    public static boolean isInstalled(Object instrumentation) {
        return instrumentation instanceof ShizuPosedInstrumentation;
    }

    // ─── Custom Instrumentation ──────────────────────────────────

    private static final class ShizuPosedInstrumentation extends Instrumentation {

        private final Instrumentation original;

        ShizuPosedInstrumentation(Instrumentation original) {
            this.original = original;
        }

        // Application lifecycle

        @Override
        public Application newApplication(ClassLoader cl, String className, Context context)
                throws InstantiationException, IllegalAccessException,
                       ClassNotFoundException {
            Application app = original.newApplication(cl, className, context);
            // attachBaseContext runs inside original.newApplication()'s
            // callee chain on modern Android; we cannot intercept it
            // here. It is only reachable via a real hook on Application.
            return app;
        }

        @Override
        public void callApplicationOnCreate(Application app) {
            dispatch(app, "onCreate", null);
            original.callApplicationOnCreate(app);
        }

        // Activity lifecycle

        @Override
        public void callActivityOnCreate(Activity activity, Bundle icicle) {
            dispatch(activity, "onCreate", new Object[]{ icicle });
            original.callActivityOnCreate(activity, icicle);
        }

        @Override
        public void callActivityOnStart(Activity activity) {
            dispatch(activity, "onStart", null);
            original.callActivityOnStart(activity);
        }

        @Override
        public void callActivityOnResume(Activity activity) {
            dispatch(activity, "onResume", null);
            original.callActivityOnResume(activity);
        }

        @Override
        public void callActivityOnPause(Activity activity) {
            dispatch(activity, "onPause", null);
            original.callActivityOnPause(activity);
        }

        @Override
        public void callActivityOnStop(Activity activity) {
            dispatch(activity, "onStop", null);
            original.callActivityOnStop(activity);
        }

        @Override
        public void callActivityOnDestroy(Activity activity) {
            dispatch(activity, "onDestroy", null);
            original.callActivityOnDestroy(activity);
        }

        @Override
        public void callActivityOnRestart(Activity activity) {
            dispatch(activity, "onRestart", null);
            original.callActivityOnRestart(activity);
        }

        @Override
        public void callActivityOnNewIntent(Activity activity, Intent intent) {
            dispatch(activity, "onNewIntent", new Object[]{ intent });
            original.callActivityOnNewIntent(activity, intent);
        }

        // Dispatch

        private void dispatch(Object target, String methodName, Object[] args) {
            if (target == null) return;
            try {
                // Concrete class first, then each superclass, so a hook
                // on "android.app.Activity#onCreate" fires for every
                // Activity subclass while a hook on the concrete class
                // fires only for that class.
                Class<?> c = target.getClass();
                while (c != null && c != Object.class) {
                    runForKey(c, methodName, target, args);
                    c = c.getSuperclass();
                }
            } catch (Throwable t) {
                CompatLog.w(TAG, "dispatch failed for " + methodName, t);
            }
        }

        private void runForKey(Class<?> clazz, String methodName,
                               Object thisObject, Object[] args) {
            List<XC_MethodHook> cbs =
                    LifecycleRegistry.callbacksFor(clazz, methodName);
            if (cbs == null || cbs.isEmpty()) return;

            // One shared MethodHookParam per (class, method) dispatch,
            // matching Xposed semantics: callbacks chained on the same
            // hook see each other's setResult / setThrowable.
            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.thisObject = thisObject;
            p.args = args == null ? new Object[0] : args;

            for (XC_MethodHook cb : cbs) {
                try {
                    cb.callBeforeHookedMethod(p);
                } catch (Throwable t) {
                    CompatLog.w(TAG, "callback threw for "
                            + clazz.getName() + "#" + methodName, t);
                }
            }
        }
    }
}