package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;

public final class XposedHelpersImpl {

    private XposedHelpersImpl() {}

    // ─── reflection helpers ──────────────────────────────────────────

    public static Class<?> findClass(String className, ClassLoader cl) {
        try {
            if (cl != null) return Class.forName(className, false, cl);
            return Class.forName(className);
        } catch (Throwable t) {
            log("findClass failed: " + className + " — " + t.getMessage());
            return null;
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        if (clazz == null) return null;
        try {
            Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            for (Constructor<?> ctor : ctors) {
                ctor.setAccessible(true);
                try {
                    return ctor.newInstance(args);
                } catch (Throwable ignore) { /* try next */ }
            }
        } catch (Throwable t) {
            log("newInstance failed on " + clazz.getName() + " — " + t.getMessage());
        }
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        if (obj == null) return;
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {}
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        if (clazz == null) return null;
        try {
            Field f = findField(clazz, fieldName);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        if (clazz == null) return;
        try {
            Field f = findField(clazz, fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {}
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;
        try {
            Method m = findMethod(obj.getClass(), methodName, args);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Throwable t) {
            log("callMethod failed: " + methodName + " — " + t.getMessage());
            return null;
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        if (clazz == null) return null;
        try {
            Method m = findMethod(clazz, methodName, args);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            log("callStaticMethod failed: " + methodName + " — " + t.getMessage());
            return null;
        }
    }

    // ─── hook entry points ───────────────────────────────────────────

    /** The standard Xposed findAndHookMethod signature the modules call. */
    public static void findAndHookMethod(
            Object classOrName,
            ClassLoader cl,
            String methodName,
            Object... parameterTypesAndCallback) {

        if (parameterTypesAndCallback.length < 1) {
            log("findAndHookMethod: missing callback for " + methodName);
            return;
        }

        try {
            Class<?> clazz = (classOrName instanceof Class)
                ? (Class<?>) classOrName
                : Class.forName((String) classOrName, false, cl);

            int paramCount = parameterTypesAndCallback.length - 1;
            Class<?>[] paramTypes = new Class<?>[paramCount];
            for (int i = 0; i < paramCount; i++) {
                paramTypes[i] = (Class<?>) parameterTypesAndCallback[i];
            }

            Object last = parameterTypesAndCallback[paramCount];
            if (!(last instanceof XC_MethodHook)) {
                log("findAndHookMethod: last arg is not XC_MethodHook: "
                    + (last == null ? "null" : last.getClass().getName()));
                return;
            }

            Method target = clazz.getDeclaredMethod(methodName, paramTypes);
            XposedHookBridge.installHook(target, (XC_MethodHook) last);

        } catch (Throwable t) {
            log("findAndHookMethod failed: " + methodName + " — " + t.getMessage());
        }
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        if (clazz == null || callback == null) return;
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    XposedHookBridge.installHook(m, callback);
                }
            }
        } catch (Throwable t) {
            log("hookAllMethods failed: " + methodName + " — " + t.getMessage());
        }
    }

    public static void hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        if (clazz == null || callback == null) return;
        try {
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                XposedHookBridge.installConstructorHook(c, callback);
            }
        } catch (Throwable t) {
            log("hookAllConstructors failed: " + t.getMessage());
        }
    }

    // ─── internals ───────────────────────────────────────────────────

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Object[] args) {
        outer:
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types.length != args.length) continue;
                for (int i = 0; i < types.length; i++) {
                    if (args[i] == null) continue;
                    if (!types[i].isAssignableFrom(args[i].getClass())) continue outer;
                }
                return m;
            }
        }
        return null;
    }

    private static void log(String msg) {
        try { Logger.getInstance(null).i("[Helpers] " + msg); }
        catch (Throwable ignored) {}
    }

    // suppress unused warning
    @SuppressWarnings("unused")
    private static void unused() { int m = Modifier.PUBLIC; }
}