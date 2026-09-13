package com.shizuposed.manager.core.hooks;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Registry for lifecycle hooks installed via Instrumentation.
 *
 * Keyed by "<class>#<method>" (e.g. "android.app.Application#onCreate").
 * Multiple callbacks per key are allowed; they run in registration order.
 *
 * Because Instrumentation dispatches the actual lifecycle call at a
 * single point, this registry is the routing table that tells the
 * dispatcher which module callbacks to run when a lifecycle method
 * fires.
 */
public final class LifecycleRegistry {

    private static final Map<String, List<XC_MethodHook>> HOOKS = new ConcurrentHashMap<>();

    private LifecycleRegistry() {}

    public static void register(Method method, XC_MethodHook callback) {
        if (method == null || callback == null) return;
        String key = keyOf(method.getDeclaringClass().getName(), method.getName());
        HOOKS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    public static List<XC_MethodHook> callbacksFor(String className, String methodName) {
        return HOOKS.get(keyOf(className, methodName));
    }

    /**
     * True if the given method is one Instrumentation can intercept.
     * Only Application and Activity lifecycle methods qualify.
     */
    public static boolean isLifecycleMethod(Method method) {
        if (method == null) return false;

        String className = method.getDeclaringClass().getName();
        String methodName = method.getName();

        // Application lifecycle
        if ("android.app.Application".equals(className)) {
            return "onCreate".equals(methodName)
                || "attachBaseContext".equals(methodName);
        }

        // Activity lifecycle — any class that descends from Activity.
        if (isActivityOrSubclass(method.getDeclaringClass())) {
            switch (methodName) {
                case "onCreate":
                case "onStart":
                case "onResume":
                case "onPause":
                case "onStop":
                case "onDestroy":
                case "onRestart":
                case "onNewIntent":
                    return true;
            }
        }

        return false;
    }

    private static boolean isActivityOrSubclass(Class<?> clazz) {
        // The class itself might be Activity, or a subclass of it.
        // Walking the hierarchy is safe because Activity is in the
        // framework bootclasspath.
        Class<?> c = clazz;
        while (c != null) {
            if ("android.app.Activity".equals(c.getName())) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static String keyOf(String className, String methodName) {
        return className + "#" + methodName;
    }
}