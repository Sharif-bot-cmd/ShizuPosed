package com.shizuposed.manager.core.backends;

import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ApplicationThreadBackend
 *
 * Hooks the binder interface ActivityThread exposes to
 * system_server (ActivityManagerService). This is the layer
 * between AMS and the target's own code: everything AMS schedules
 * — activity launches, service creates, receiver broadcasts —
 * arrives on this binder interface before ActivityThread and
 * Instrumentation see it.
 *
 * WHY THIS EXISTS
 * ---------------
 * InstrumentationBackend hooks the Instrumentation object, which
 * is DOWNSTREAM of ApplicationThread. A module that needs to:
 *
 *   • rewrite an Intent before the target sees it
 *   • block a component start
 *   • catch Service.onCreate (which Instrumentation does not cover)
 *   • observe receiver dispatch before the receiver runs
 *
 * ...cannot do any of that from Instrumentation. It has to sit on
 * ApplicationThread.
 *
 * SERVICE.ONCREATE
 * ----------------
 * scheduleCreateService is the binder method AMS calls to tell the
 * target process to create a service. Hooking it closes the
 * Service.onCreate gap the README used to describe as structural.
 * The actual onCreate still runs on the service's own thread; the
 * hook fires before the Service object is instantiated.
 *
 * FAIL-OPEN
 * ---------
 * If any hook fails to install — because the method was renamed,
 * its signature changed, or the field doesn't exist — the backend
 * logs and continues. No hook, no crash.
 *
 * COMPILE-TIME NOTE
 * -----------------
 * android.app.servertransaction.ClientTransaction is hidden from
 * the SDK stubs, so it cannot be referenced by name at compile
 * time. scheduleTransaction is hooked by name with no parameter
 * type checks; any signature mismatch on a given ROM is caught and
 * logged by the fail-open wrapper.
 *
 * The internal listener-dispatch method is named notifyListeners
 * rather than notify, because Object.notify() is final and an
 * unqualified call to notify(String, Object[]) would bind to it
 * and fail to compile.
 */
public final class ApplicationThreadBackend {

    private static final String TAG = "XStealth.AppThread";

    /** Listeners notified before a binder method runs. */
    public interface BinderListener {
        /**
         * @param methodName the ApplicationThread method about to run
         * @param args       the method's arguments
         * @return true to allow the call, false to block it
         */
        boolean beforeBinderCall(String methodName, Object[] args);
    }

    private static final List<BinderListener> sListeners = new ArrayList<>();

    private static volatile boolean sInstalled = false;

    private ApplicationThreadBackend() {}

    public static boolean isInstalled() { return sInstalled; }

    public static void registerListener(BinderListener l) {
        if (l == null) return;
        synchronized (sListeners) {
            if (!sListeners.contains(l)) sListeners.add(l);
        }
    }

    /**
     * Install hooks on the ApplicationThread binder interface.
     * Called from bootstrap after ActivityThread is obtained.
     *
     * @param activityThread the ActivityThread instance
     * @return true if at least one hook installed
     */
    public static boolean install(Object activityThread) {
        if (activityThread == null) return false;
        if (sInstalled) return true;

        Object appThread = resolveAppThread(activityThread);
        if (appThread == null) {
            XposedBridge.log(TAG + ": mAppThread not found on ActivityThread");
            return false;
        }

        Class<?> clazz = appThread.getClass();
        XposedBridge.log(TAG + ": ApplicationThread class is "
            + clazz.getName());

        boolean any = false;
        any |= hookScheduleCreateService(clazz);
        any |= hookScheduleBindService(clazz);
        any |= hookScheduleUnbindService(clazz);
        any |= hookScheduleStopService(clazz);
        any |= hookScheduleServiceArgs(clazz);
        any |= hookScheduleTransaction(clazz);

        if (any) {
            sInstalled = true;
            XposedBridge.log(TAG + ": ApplicationThreadBackend installed");
        } else {
            XposedBridge.log(TAG
                + ": ApplicationThreadBackend installed no hooks");
        }
        return any;
    }

    private static Object resolveAppThread(Object activityThread) {
        try {
            Class<?> c = activityThread.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("mAppThread");
                    f.setAccessible(true);
                    return f.get(activityThread);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resolveAppThread failed: " + t);
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // HOOKS
    // ═════════════════════════════════════════════════════════════

    private static boolean hookScheduleCreateService(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "scheduleCreateService",
                IBinder.class,
                ServiceInfo.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String blocked = notifyListeners(
                            "scheduleCreateService", p.args);
                        if (blocked != null) {
                            XposedBridge.log(TAG + ": blocked by " + blocked);
                            p.setResult(null);
                        }
                    }
                });
            XposedBridge.log(TAG + ": scheduleCreateService hooked");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": scheduleCreateService hook failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookScheduleBindService(Class<?> clazz) {
        // Try the seven-arg form first, then the older five-arg form.
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "scheduleBindService",
                IBinder.class,
                Intent.class,
                boolean.class,
                int.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String blocked = notifyListeners(
                            "scheduleBindService", p.args);
                        if (blocked != null) {
                            p.setResult(null);
                        }
                    }
                });
            return true;
        } catch (Throwable ignored) {
            // Fall through to the older signature.
        }
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "scheduleBindService",
                IBinder.class,
                Intent.class,
                boolean.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String blocked = notifyListeners(
                            "scheduleBindService", p.args);
                        if (blocked != null) p.setResult(null);
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": scheduleBindService hook failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookScheduleUnbindService(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "scheduleUnbindService",
                IBinder.class,
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String blocked = notifyListeners(
                            "scheduleUnbindService", p.args);
                        if (blocked != null) p.setResult(null);
                    }
                });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hookScheduleStopService(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "scheduleStopService",
                IBinder.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String blocked = notifyListeners(
                            "scheduleStopService", p.args);
                        if (blocked != null) p.setResult(null);
                    }
                });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hookScheduleServiceArgs(Class<?> clazz) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "scheduleServiceArgs",
                IBinder.class,
                boolean.class,
                int.class,
                int.class,
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        String blocked = notifyListeners(
                            "scheduleServiceArgs", p.args);
                        if (blocked != null) p.setResult(null);
                    }
                });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * scheduleTransaction — the generic binder transaction that
     * carries activity lifecycle work on Android 9+.
     *
     * ClientTransaction is hidden from the SDK stubs, so it cannot
     * be referenced by name here. Instead, find the method by name
     * and match any single-argument signature. The hook just
     * observes; it does not set a result, because a transaction
     * with a null result would break the activity lifecycle.
     */
    private static boolean hookScheduleTransaction(Class<?> clazz) {
        try {
            // Walk declared methods to find scheduleTransaction with
            // exactly one parameter of any type. This avoids naming
            // ClientTransaction at compile time.
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (!"scheduleTransaction".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                target = m;
                break;
            }
            if (target == null) return false;

            final Class<?> paramType = target.getParameterTypes()[0];

            XposedHelpers.findAndHookMethod(
                clazz, "scheduleTransaction",
                paramType,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        notifyListeners("scheduleTransaction", p.args);
                    }
                });
            XposedBridge.log(TAG + ": scheduleTransaction hooked");
            return true;
        } catch (Throwable t) {
            // ClientTransaction is Android 9+. Silent skip on older.
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LISTENER DISPATCH
    // ═════════════════════════════════════════════════════════════

    /**
     * Notify listeners. Returns the name of the listener that
     * blocked the call, or null if none did.
     *
     * Named notifyListeners, not notify, because Object.notify() is
     * final and would shadow any same-named overload inside this
     * class.
     */
    private static String notifyListeners(String methodName, Object[] args) {
        BinderListener[] snapshot;
        synchronized (sListeners) {
            if (sListeners.isEmpty()) return null;
            snapshot = sListeners.toArray(new BinderListener[0]);
        }
        for (BinderListener l : snapshot) {
            try {
                if (!l.beforeBinderCall(methodName, args)) {
                    return l.getClass().getSimpleName();
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": listener threw for "
                    + methodName + ": " + t);
            }
        }
        return null;
    }
}