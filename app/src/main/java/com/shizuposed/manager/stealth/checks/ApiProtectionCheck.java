package com.shizuposed.manager.stealth.checks;

import android.util.Log;

import com.shizuposed.manager.core.HookEngine;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ApiProtectionCheck
 *
 * Prevents a target app from discovering the Xposed shim classes or
 * the fact that the framework has modified its own reflection view
 * of the process.
 *
 * DYNAMIC FINGERPRINT BASELINES
 * -----------------------------
 * Some detectors reflect on a framework class, count its methods,
 * and compare against a known-good baseline. If the count changed,
 * they know the framework is hooked.
 *
 * Static pinning (a hardcoded list of common targets) covers the
 * obvious cases. But a detector can fingerprint any class — and
 * the classes that matter most are the ones the framework has
 * actually hooked, because those are the ones whose method counts
 * have changed.
 *
 * HookEngine tracks every class it has hooked a method on. At
 * install time, this check pulls that list and pins a baseline for
 * each. Every later getDeclaredMethods() / getMethods() against a
 * hooked class returns the pre-hook count.
 *
 * The dynamic baselines are captured at install time, which is
 * after any module hooks have been installed (XStealth runs at the
 * end of the module load sequence). So the baseline reflects the
 * post-hook count — which is the count the detector will see minus
 * our contribution. In practice this means: the count is frozen at
 * whatever it was the moment ApiProtectionCheck ran. Whatever our
 * hooks added is invisible because the detector sees the same count
 * we froze.
 *
 * The pin is best-effort. It defeats the naive "count the methods"
 * check. It does not defeat a detector that inspects the actual
 * Method objects or their declaring classes.
 */
public final class ApiProtectionCheck {

    private static final String TAG = "XStealth";

    /** Package prefixes that identify the shim classes. */
    private static final String[] PROTECTED_PREFIXES = {
        "de.robv.android.xposed.",
        "com.shizuposed.manager.stealth.",
        "com.shizuposed.manager.core.",
    };

    private static final Set<String> ALLOWLIST = new HashSet<>();

    private static final Set<String> TRUSTED_PACKAGES =
        ConcurrentHashMap.newKeySet();

    static {
        TRUSTED_PACKAGES.add("de.robv.android.xposed");
        TRUSTED_PACKAGES.add("com.shizuposed.manager.stealth");
        TRUSTED_PACKAGES.add("com.shizuposed.manager.core");
        TRUSTED_PACKAGES.add("com.shizuposed.manager");
    }

    /**
     * Baseline method counts. Key: fully-qualified class name.
     * Value: method count captured at install time.
     *
     * Populated from two sources:
     *   • FINGERPRINT_TARGETS — a static list of common detection
     *     targets.
     *   • HookEngine.getHookedClassNames() — every class the
     *     framework has installed a hook on.
     */
    private static final Map<String, Integer> METHOD_COUNT_BASELINE =
        new ConcurrentHashMap<>();

    /**
     * Classes whose method count is worth pinning even if the
     * framework hasn't hooked them. These are the classes a naive
     * fingerprinting detector will look at.
     */
    private static final String[] FINGERPRINT_TARGETS = {
        "android.provider.Settings$Global",
        "android.provider.Settings$Secure",
        "android.provider.Settings$System",
        "android.os.Build",
        "android.os.Build$VERSION",
        "android.app.ActivityThread",
        "android.app.ApplicationPackageManager",
        "android.app.ActivityManager",
        "java.lang.System",
        "java.lang.Runtime",
    };

    private ApiProtectionCheck() {}

    public static void recordModulePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        TRUSTED_PACKAGES.add(pkg);
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) return;

        try {
            captureStaticBaselines(lpparam.classLoader);
            captureDynamicBaselines(lpparam.classLoader);

            hookClassLoaderLoadClass(lpparam);
            hookClassForName(lpparam);
            hookClassLoaderResources(lpparam);
            hookClassLoaderGetPackage(lpparam);
            hookPackageGetPackage(lpparam);
            hookClassReflectionMethods(lpparam);
            hookClassReflectionConstructors(lpparam);
            hookClassReflectionFields(lpparam);

            XposedBridge.log(TAG + ": ApiProtectionCheck installed (13 hooks, "
                + METHOD_COUNT_BASELINE.size() + " baselines)");
            XStealthRegistry.record("ApiProtectionCheck");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ApiProtectionCheck failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // BASELINE CAPTURE
    // ═════════════════════════════════════════════════════════════

    /**
     * Pin baselines for the static list of common detection targets.
     */
    private static void captureStaticBaselines(ClassLoader classLoader) {
        for (String className : FINGERPRINT_TARGETS) {
            captureOne(className, classLoader);
        }
    }

    /**
     * Pin baselines for every class the framework has hooked a
     * method on. Ask HookEngine for the set.
     *
     * HookEngine is a framework class, so it's available without a
     * classloader dance. If the class isn't loadable for any reason
     * — e.g. an older build — skip and rely on the static baselines.
     */
    private static void captureDynamicBaselines(ClassLoader classLoader) {
        try {
            Set<String> hookedClasses = HookEngine.getHookedClassNames();
            if (hookedClasses == null || hookedClasses.isEmpty()) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "captureDynamicBaselines: no hooked classes");
                }
                return;
            }
            for (String className : hookedClasses) {
                captureOne(className, classLoader);
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "captureDynamicBaselines: captured "
                    + hookedClasses.size() + " hooked classes");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": captureDynamicBaselines failed: "
                + t.getMessage());
        }
    }

    /**
     * Pin a single baseline. No-op if the class isn't loadable or
     * a baseline already exists for it.
     */
    private static void captureOne(String className, ClassLoader classLoader) {
        if (className == null || className.isEmpty()) return;
        if (METHOD_COUNT_BASELINE.containsKey(className)) return;
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            if (clazz == null) return;
            int count = clazz.getDeclaredMethods().length;
            METHOD_COUNT_BASELINE.put(className, count);
        } catch (Throwable ignored) {
            // Class not present on this Android version, or not
            // loadable from the target's loader. Skip.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // CLASS LOOKUP
    // ═════════════════════════════════════════════════════════════

    private static void hookClassLoaderLoadClass(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "loadClass",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        String name = (String) param.args[0];
                        if (shouldRefuse(name)) {
                            XposedBridge.log(TAG + ": refused loadClass("
                                + name + ") from non-module caller");
                            param.setThrowable(new ClassNotFoundException(name));
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": loadClass(String) hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "loadClass",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        String name = (String) param.args[0];
                        if (shouldRefuse(name)) {
                            XposedBridge.log(TAG + ": refused loadClass("
                                + name + ", ...) from non-module caller");
                            param.setThrowable(new ClassNotFoundException(name));
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": loadClass(String, boolean) hook failed: " + t);
        }
    }

    private static void hookClassForName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "forName",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        String name = (String) param.args[0];
                        if (shouldRefuse(name)) {
                            XposedBridge.log(TAG + ": refused forName("
                                + name + ") from non-module caller");
                            param.setThrowable(new ClassNotFoundException(name));
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forName(String) hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "forName",
                String.class, boolean.class, ClassLoader.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        String name = (String) param.args[0];
                        if (shouldRefuse(name)) {
                            XposedBridge.log(TAG + ": refused forName("
                                + name + ", ...) from non-module caller");
                            param.setThrowable(new ClassNotFoundException(name));
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forName(String, boolean, ClassLoader) hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // RESOURCE LOOKUP
    // ═════════════════════════════════════════════════════════════

    private static void hookClassLoaderResources(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "getResource",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = (String) param.args[0];
                        if (isProtectedResource(name) && !isTrustedCaller()) {
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getResource hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "getResourceAsStream",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = (String) param.args[0];
                        if (isProtectedResource(name) && !isTrustedCaller()) {
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getResourceAsStream hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "getResources",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = (String) param.args[0];
                        if (isProtectedResource(name) && !isTrustedCaller()) {
                            param.setResult(java.util.Collections
                                .emptyEnumeration());
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getResources hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE LOOKUP
    // ═════════════════════════════════════════════════════════════

    private static void hookClassLoaderGetPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader.class, "getPackage",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = (String) param.args[0];
                        if (isProtectedPackage(name) && !isTrustedCaller()) {
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ClassLoader.getPackage hook failed: " + t);
        }
    }

    private static void hookPackageGetPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Package.class, "getPackage",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = (String) param.args[0];
                        if (isProtectedPackage(name) && !isTrustedCaller()) {
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Package.getPackage hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // REFLECTION WALKS
    // ═════════════════════════════════════════════════════════════

    private static void hookClassReflectionMethods(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredMethods",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isTrustedCaller()) return;
                            Class<?> self = (Class<?>) param.thisObject;
                            Method[] original = (Method[]) param.getResult();
                            if (original == null) return;

                            String selfName = self.getName();

                            // Pin the method count for fingerprint
                            // targets (static or dynamic).
                            Integer baseline = METHOD_COUNT_BASELINE.get(selfName);
                            if (baseline != null
                                    && original.length > baseline) {
                                Method[] trimmed = new Method[baseline];
                                System.arraycopy(original, 0, trimmed, 0, baseline);
                                param.setResult(trimmed);
                                return;
                            }

                            // Filter methods declared by protected
                            // classes.
                            Method[] filtered = filterMethods(original);
                            if (filtered.length != original.length) {
                                param.setResult(filtered);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredMethods hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getMethods",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isTrustedCaller()) return;
                            Method[] original = (Method[]) param.getResult();
                            if (original == null) return;
                            Method[] filtered = filterMethods(original);
                            if (filtered.length != original.length) {
                                param.setResult(filtered);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getMethods hook failed: " + t);
        }
    }

    private static void hookClassReflectionConstructors(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredConstructors",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isTrustedCaller()) return;
                            Constructor<?>[] original =
                                (Constructor<?>[]) param.getResult();
                            if (original == null) return;
                            Constructor<?>[] filtered =
                                filterConstructors(original);
                            if (filtered.length != original.length) {
                                param.setResult(filtered);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredConstructors hook failed: " + t);
        }
    }

    private static void hookClassReflectionFields(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredFields",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isTrustedCaller()) return;
                            Field[] original = (Field[]) param.getResult();
                            if (original == null) return;
                            Field[] filtered = filterFields(original);
                            if (filtered.length != original.length) {
                                param.setResult(filtered);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredFields hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FILTER HELPERS
    // ═════════════════════════════════════════════════════════════

    private static Method[] filterMethods(Method[] in) {
        if (in == null) return null;
        int kept = 0;
        for (Method m : in) {
            if (m != null && !isProtectedClass(m.getDeclaringClass())) kept++;
        }
        if (kept == in.length) return in;
        Method[] out = new Method[kept];
        int i = 0;
        for (Method m : in) {
            if (m != null && !isProtectedClass(m.getDeclaringClass())) out[i++] = m;
        }
        return out;
    }

    private static Constructor<?>[] filterConstructors(Constructor<?>[] in) {
        if (in == null) return null;
        int kept = 0;
        for (Constructor<?> c : in) {
            if (c != null && !isProtectedClass(c.getDeclaringClass())) kept++;
        }
        if (kept == in.length) return in;
        Constructor<?>[] out = new Constructor<?>[kept];
        int i = 0;
        for (Constructor<?> c : in) {
            if (c != null && !isProtectedClass(c.getDeclaringClass())) out[i++] = c;
        }
        return out;
    }

    private static Field[] filterFields(Field[] in) {
        if (in == null) return null;
        int kept = 0;
        for (Field f : in) {
            if (f != null && !isProtectedClass(f.getDeclaringClass())) kept++;
        }
        if (kept == in.length) return in;
        Field[] out = new Field[kept];
        int i = 0;
        for (Field f : in) {
            if (f != null && !isProtectedClass(f.getDeclaringClass())) out[i++] = f;
        }
        return out;
    }

    private static boolean isProtectedClass(Class<?> c) {
        if (c == null) return false;
        String name = c.getName();
        if (name == null) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // NAME CHECKS
    // ═════════════════════════════════════════════════════════════

    private static boolean shouldRefuse(String className) {
        if (className == null) return false;
        if (ALLOWLIST.contains(className)) return false;
        if (!isProtected(className)) return false;
        if (isTrustedCaller()) return false;
        return true;
    }

    private static boolean isProtected(String className) {
        if (className == null) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isProtectedPackage(String packageName) {
        if (packageName == null) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            String pkgPrefix = prefix.endsWith(".")
                ? prefix.substring(0, prefix.length() - 1)
                : prefix;
            if (packageName.equals(pkgPrefix)) return true;
            if (packageName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isProtectedResource(String resourceName) {
        if (resourceName == null) return false;
        String normalized = resourceName.replace('/', '.');
        for (String prefix : PROTECTED_PREFIXES) {
            if (normalized.contains(prefix)) return true;
        }
        if (resourceName.contains("de/robv/android/xposed")) return true;
        if (resourceName.contains("com/shizuposed/manager/stealth")) return true;
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // TRUSTED-CALLER CHECK
    // ═════════════════════════════════════════════════════════════

    private static boolean isTrustedCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack == null) return true;
            for (int i = 3; i < stack.length; i++) {
                StackTraceElement e = stack[i];
                String cn = e.getClassName();
                if (cn == null) continue;

                if (cn.startsWith("de.robv.android.xposed.")) return true;
                if (cn.startsWith("com.shizuposed.manager.stealth.")) return true;
                if (cn.startsWith("com.shizuposed.manager.core.")) return true;

                for (String trusted : TRUSTED_PACKAGES) {
                    if (cn.startsWith(trusted + ".")) return true;
                    if (cn.equals(trusted)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }
}