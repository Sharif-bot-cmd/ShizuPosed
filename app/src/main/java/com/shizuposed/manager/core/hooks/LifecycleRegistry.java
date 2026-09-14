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
 * Keyed by (Class, methodName). Multiple callbacks per key are allowed
 * and run in registration order. Registration is idempotent per
 * (key, callback) pair, so a module that re-runs handleLoadPackage in
 * the same process does not double-register.
 */
public final class LifecycleRegistry {

    // Value type is CopyOnWriteArrayList, not List, so addIfAbsent is
    // visible without a cast.
    private static final Map<Key, CopyOnWriteArrayList<XC_MethodHook>> HOOKS =
            new ConcurrentHashMap<>();

    /** Cache of "is this class an Activity or subclass?" */
    private static final Map<Class<?>, Boolean> ACTIVITY_CACHE =
            new ConcurrentHashMap<>();

    private LifecycleRegistry() {}

    // ─── Registration ────────────────────────────────────────────

    public static void register(Method method, XC_MethodHook callback) {
        if (method == null || callback == null) return;
        register(method.getDeclaringClass(), method.getName(), callback);
    }

    public static void register(Class<?> declaringClass, String methodName,
                                XC_MethodHook callback) {
        if (declaringClass == null || methodName == null || callback == null) return;
        Key key = new Key(declaringClass, methodName);
        CopyOnWriteArrayList<XC_MethodHook> list =
                HOOKS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.addIfAbsent(callback);
    }

    public static boolean unregister(XC_MethodHook callback) {
        if (callback == null) return false;
        boolean removed = false;
        for (CopyOnWriteArrayList<XC_MethodHook> list : HOOKS.values()) {
            if (list.remove(callback)) removed = true;
        }
        return removed;
    }

    public static void clear() {
        HOOKS.clear();
    }

    /** Callbacks registered for exactly this class + method. */
    public static List<XC_MethodHook> callbacksFor(Class<?> declaringClass,
                                                   String methodName) {
        if (declaringClass == null || methodName == null) return null;
        return HOOKS.get(new Key(declaringClass, methodName));
    }

    // ─── Lifecycle detection ─────────────────────────────────────

    public static boolean isLifecycleMethod(Method method) {
        if (method == null) return false;
        Class<?> dc = method.getDeclaringClass();
        String name = method.getName();

        if (isApplicationOrSubclass(dc)) {
            return "onCreate".equals(name) || "attachBaseContext".equals(name);
        }
        if (isActivityOrSubclass(dc)) {
            switch (name) {
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

    private static boolean isApplicationOrSubclass(Class<?> clazz) {
        return walkForClass(clazz, "android.app.Application");
    }

    private static boolean isActivityOrSubclass(Class<?> clazz) {
        Boolean cached = ACTIVITY_CACHE.get(clazz);
        if (cached != null) return cached;
        boolean result = walkForClass(clazz, "android.app.Activity");
        ACTIVITY_CACHE.put(clazz, result);
        return result;
    }

    private static boolean walkForClass(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            if (name.equals(c.getName())) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    // ─── Key ─────────────────────────────────────────────────────

    private static final class Key {
        final Class<?> clazz;
        final String method;
        final int hash;

        Key(Class<?> clazz, String method) {
            this.clazz = clazz;
            this.method = method;
            this.hash = 31 * clazz.hashCode() + method.hashCode();
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return clazz == k.clazz && method.equals(k.method);
        }

        @Override public int hashCode() { return hash; }

        @Override public String toString() {
            return clazz.getName() + "#" + method;
        }
    }
}