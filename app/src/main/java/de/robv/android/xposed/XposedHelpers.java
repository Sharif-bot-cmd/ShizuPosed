package de.robv.android.xposed;

public final class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader cl) {
        return com.shizuposed.manager.core.XposedHelpersImpl.findClass(className, cl);
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

    public static int getXposedVersion() {
        return XposedBridge.getXposedVersion();
    }

    public static boolean isModuleEnabled(String packageName) {
        return XposedBridge.isModuleEnabled(packageName);
    }

    public static void hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        com.shizuposed.manager.core.XposedHelpersImpl.hookAllConstructors(clazz, callback);
    }
}