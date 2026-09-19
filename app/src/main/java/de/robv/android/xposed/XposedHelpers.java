package de.robv.android.xposed;

import android.content.Context;

/**
 * Standard Xposed API shim.
 *
 * Modules link against this class name. Every call is forwarded into
 * com.shizuposed.manager.core.XposedHelpersImpl, which routes hook
 * installation through XposedHookBridge → HookDispatcher.
 *
 * Version and state queries forward to XposedBridge so both call
 * sites (XposedHelpers.getXposedVersion() and
 * XposedBridge.getXposedVersion()) return the same value.
 */
public final class XposedHelpers {

    // ═════════════════════════════════════════════════════════════
    // REFLECTION HELPERS
    // ═════════════════════════════════════════════════════════════

    public static Class<?> findClass(String className, ClassLoader cl) {
        return com.shizuposed.manager.core.XposedHelpersImpl.findClass(className, cl);
    }

    public static Class<?> findClass(String className) {
        ClassLoader cl = XposedHelpers.class.getClassLoader();
        return com.shizuposed.manager.core.XposedHelpersImpl.findClass(className, cl);
    }

    /**
     * Like {@link #findClass(String, ClassLoader)} but returns null
     * instead of throwing when the class cannot be loaded.
     *
     * Modules use this to probe for optional classes — classes that
     * exist on some Android versions or some OEM ROMs but not others
     * — without wrapping every lookup in a try/catch.
     *
     * @param className   fully-qualified name, e.g.
     *                    "android.provider.Settings$Global"
     * @param classLoader loader to resolve against; if null, the
     *                    caller's own classloader is used
     * @return the Class, or null if it cannot be found
     */
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        if (className == null) return null;
        try {
            if (classLoader != null) {
                return Class.forName(className, false, classLoader);
            }
            // Fall back to the shim's own loader. In practice this is
            // the same classloader the module was loaded with, so the
            // lookup succeeds against the same set of classes the
            // module can see.
            return Class.forName(className, false, XposedHelpers.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Convenience overload that uses the shim's own classloader. */
    public static Class<?> findClassIfExists(String className) {
        return findClassIfExists(className, null);
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return com.shizuposed.manager.core.XposedHelpersImpl.newInstance(clazz, args);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return com.shizuposed.manager.core.XposedHelpersImpl.getObjectField(obj, fieldName);
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        com.shizuposed.manager.core.XposedHelpersImpl.setObjectField(obj, fieldName, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return com.shizuposed.manager.core.XposedHelpersImpl.getStaticObjectField(clazz, fieldName);
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        com.shizuposed.manager.core.XposedHelpersImpl.setStaticObjectField(clazz, fieldName, value);
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return com.shizuposed.manager.core.XposedHelpersImpl.callMethod(obj, methodName, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return com.shizuposed.manager.core.XposedHelpersImpl.callStaticMethod(clazz, methodName, args);
    }

    /**
     * Find a field on a class hierarchy, walking up superclasses if
     * the field is not declared on the class itself. Returns the
     * Field or null.
     */
    public static java.lang.reflect.Field findFieldIfExists(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField(fieldName);
                com.shizuposed.manager.core.compat.HiddenApiBypass.forceAccessible(f);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * Find a method on a class hierarchy with the given parameter
     * types. Returns the Method or null.
     */
    public static java.lang.reflect.Method findMethodIfExists(
            Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        if (clazz == null || methodName == null) return null;
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                java.lang.reflect.Method m = cur.getDeclaredMethod(
                    methodName, parameterTypes);
                com.shizuposed.manager.core.compat.HiddenApiBypass.forceAccessible(m);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return null;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * Find a constructor with the given parameter types. Returns the
     * Constructor or null.
     */
    public static java.lang.reflect.Constructor<?> findConstructorIfExists(
            Class<?> clazz, Class<?>... parameterTypes) {
        if (clazz == null) return null;
        try {
            java.lang.reflect.Constructor<?> c = clazz.getDeclaredConstructor(parameterTypes);
            com.shizuposed.manager.core.compat.HiddenApiBypass.forceAccessible(c);
            return c;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HOOK INSTALLATION
    // ═════════════════════════════════════════════════════════════

    public static void findAndHookMethod(Class<?> clazz, String methodName,
                                         Object... parameterTypesAndCallback) {
        com.shizuposed.manager.core.XposedHelpersImpl.findAndHookMethod(
            clazz, null, methodName, parameterTypesAndCallback);
    }

    public static void findAndHookMethod(String className, ClassLoader cl, String methodName,
                                         Object... parameterTypesAndCallback) {
        com.shizuposed.manager.core.XposedHelpersImpl.findAndHookMethod(
            className, cl, methodName, parameterTypesAndCallback);
    }

    public static void hookAllMethods(Class<?> clazz, String methodName,
                                      XC_MethodHook callback) {
        com.shizuposed.manager.core.XposedHelpersImpl.hookAllMethods(
            clazz, methodName, callback);
    }

    public static void hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        com.shizuposed.manager.core.XposedHelpersImpl.hookAllConstructors(clazz, callback);
    }

    // ═════════════════════════════════════════════════════════════
    // VERSION + STATE FORWARDS
    // ═════════════════════════════════════════════════════════════

    public static int getXposedVersion() {
        return XposedBridge.getXposedVersion();
    }

    /** No-arg form: "is the framework active?". */
    public static boolean isModuleEnabled() {
        return XposedBridge.isModuleEnabled();
    }

    /** Per-package form: "is <pkg> turned on in the manager?". */
    public static boolean isModuleEnabled(String packageName) {
        return XposedBridge.isModuleEnabled(packageName);
    }

    /** Context overload for modules that already have a Context. */
    public static boolean isModuleEnabled(Context context, String packageName) {
        return XposedBridge.isModuleEnabled(context, packageName);
    }

    /** Per-package form: "has <pkg> loaded into a target at least once?". */
    public static boolean isModuleActive(String modulePackage) {
        return XposedBridge.isModuleActive(modulePackage);
    }

    public static boolean isModuleActive(Context context, String modulePackage) {
        return XposedBridge.isModuleActive(context, modulePackage);
    }

    /** Which packages has the module loaded into? */
    public static String[] getModuleScope(String modulePackage) {
        return XposedBridge.getModuleScope(modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        return XposedBridge.getModuleScope(context, modulePackage);
    }
}