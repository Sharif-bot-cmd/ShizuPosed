package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.core.HookEngine;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
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
 * TWO DEFENSES, TWO CLASSES
 * -------------------------
 * This class and {@link MethodBaselineCheck} both defend reflection.
 * The split:
 *
 *   • ApiProtectionCheck (this class)
 *     - Refuses ClassLoader.loadClass / Class.forName for protected
 *       classes when the caller isn't trusted.
 *     - Refuses Class.getDeclaredMethod / getDeclaredConstructor /
 *       getDeclaredField when the target is a protected class.
 *     - Returns an empty array from Class.getDeclaredClasses for a
 *       protected class.
 *     - Filters protected-class members out of getDeclaredMethods /
 *       getMethods / getDeclaredConstructors / getDeclaredFields
 *       results, regardless of count.
 *     - Refuses resource / package lookups for protected names.
 *
 *   • MethodBaselineCheck
 *     - Freezes the *full* method list (names, signatures,
 *       modifiers) of hooked classes at capture time.
 *     - Filters getDeclaredMethods / getMethods against that
 *       baseline, preserving Method identity for methods that
 *       still exist.
 *
 * Why the split: the count-based trim this class used to do was
 * brittle (trimming to N keeps the first N, not the right N) and
 * conflicted with MethodBaselineCheck's name-and-signature filter.
 * The count trim is gone. MethodBaselineCheck is the only place
 * method-list baselines live now.
 *
 * CAPTURE TIMING
 * --------------
 * Both this class's protected-member filtering and
 * MethodBaselineCheck's baseline filtering depend on the classes
 * the framework has hooked *so far*. That list is populated during
 * each module's entry point (handleLoadPackage for classic,
 * onPackageLoaded for libxposed).
 *
 * The install-time call to captureDynamicBaselines (delegated to
 * MethodBaselineCheck) runs before any module entry point, so it
 * captures nothing. The real capture is per-module, from
 * XposedHook.loadModule, after each entry point returns:
 *
 *   MethodBaselineCheck.captureDynamicBaselines(appLoader);
 *   ApiProtectionCheck.captureDynamicBaselines(appLoader);
 *
 * ApiProtectionCheck's own delegate is kept so the two call sites
 * in XposedHook are symmetric. It does not capture anything itself
 * — it defers entirely to MethodBaselineCheck, because that's the
 * class that owns the baseline data now.
 *
 * LIBXPOSED
 * ---------
 * libxposed needs no change here. The io.github.libxposed.api.*
 * and io.github.libxposed.annotation.* prefixes are in
 * PROTECTED_PREFIXES and TRUSTED_PACKAGES; isTrustedCaller
 * recognizes libxposed call sites; and recordModulePackage is
 * called for libxposed modules through the same loadModule path
 * as classic modules. The new getDeclaredMethod /
 * getDeclaredConstructor / getDeclaredClasses hooks close the
 * "ask for a shim method by name" and "enumerate the module's
 * nested classes" paths that libxposed modules' internals expose.
 *
 * STEALTH BUILD
 * -------------
 * PROTECTED_PREFIXES and TRUSTED_PACKAGES are Java package
 * prefixes, not Android application IDs. The stealth build
 * changes the app's applicationId, not the Java package of its
 * classes, so those prefixes stay valid.
 *
 * recordManagerPackage is called at install time with the running
 * manager's getPackageName(), so any frame that resolves to the
 * manager's Android identity — via ActivityThread, an exported
 * component invoked by the target, or an inter-process call — is
 * treated as trusted regardless of what package name the manager
 * was built under.
 */
public final class ApiProtectionCheck {

    private static final String TAG = "XStealth";

    private static final String[] PROTECTED_PREFIXES = {
        "de.robv.android.xposed.",
        "io.github.libxposed.api.",
        "io.github.libxposed.annotation.",
        "com.shizuposed.manager.stealth.",
        "com.shizuposed.manager.core.",
    };

    private static final Set<String> ALLOWLIST = new HashSet<>();

    private static final Set<String> TRUSTED_PACKAGES =
        ConcurrentHashMap.newKeySet();

    static {
        TRUSTED_PACKAGES.add("de.robv.android.xposed");
        TRUSTED_PACKAGES.add("io.github.libxposed.api");
        TRUSTED_PACKAGES.add("io.github.libxposed.annotation");
        TRUSTED_PACKAGES.add("com.shizuposed.manager.stealth");
        TRUSTED_PACKAGES.add("com.shizuposed.manager.core");
        TRUSTED_PACKAGES.add("com.shizuposed.manager");
    }

    private ApiProtectionCheck() {}

    // ═════════════════════════════════════════════════════════════
    // MODULE PACKAGE REGISTRATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Record a module's package name so class loads and class
     * lookups originating from that package are treated as
     * trusted. Called from the module load path.
     */
    public static void recordModulePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        TRUSTED_PACKAGES.add(pkg);
    }

    /**
     * Record the running manager's Android package name.
     *
     * In the standard build this is "com.shizuposed.manager", which
     * is already in TRUSTED_PACKAGES. In the cloak build it is a
     * scrambled name like "h.sghdt.kihyturd", which is not. Adding
     * it here means any stack frame that carries the manager's
     * Android identity — via ActivityThread, an exported component
     * invoked by the target, or an inter-process call — is treated
     * as trusted.
     */
    public static void recordManagerPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        TRUSTED_PACKAGES.add(pkg);
    }

    // ═════════════════════════════════════════════════════════════
    // INSTALL
    // ═════════════════════════════════════════════════════════════

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) return;

        try {
            // Defer all baseline work to MethodBaselineCheck. The
            // install-time call captures nothing useful (no module
            // entry point has run yet); the per-module call from
            // XposedHook.loadModule is what populates the baseline.
            // Kept here so a caller that only invokes
            // ApiProtectionCheck.install() still triggers the
            // MethodBaselineCheck install path indirectly.
            try {
                captureDynamicBaselines(lpparam.classLoader);
            } catch (Throwable ignored) {}

            hookClassLoaderLoadClass(lpparam);
            hookClassForName(lpparam);
            hookClassLoaderResources(lpparam);
            hookClassLoaderGetPackage(lpparam);
            hookPackageGetPackage(lpparam);
            hookClassReflectionMethods(lpparam);
            hookClassReflectionConstructors(lpparam);
            hookClassReflectionFields(lpparam);

            // NEW: named-lookup and nested-class hooks.
            hookClassDeclaredMethod(lpparam);
            hookClassDeclaredConstructor(lpparam);
            hookClassDeclaredField(lpparam);
            hookClassDeclaredClasses(lpparam);

            XposedBridge.log(TAG + ": ApiProtectionCheck installed (17 hooks)");
            XStealthRegistry.record("ApiProtectionCheck");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ApiProtectionCheck failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // BASELINE DELEGATE
    //
    // This class no longer owns a method-count baseline. The full
    // baseline lives in MethodBaselineCheck. This delegate exists
    // so XposedHook.loadModule can call the two symmetrically:
    //
    //   MethodBaselineCheck.captureDynamicBaselines(appLoader);
    //   ApiProtectionCheck.captureDynamicBaselines(appLoader);
    //
    // Kept as a public method (not removed) because a caller that
    // already invokes ApiProtectionCheck.captureDynamicBaselines
    // shouldn't have to change.
    // ═════════════════════════════════════════════════════════════

    /**
     * Delegate: capture method baselines for every class HookEngine
     * has recorded as hooked.
     *
     * The work is done by {@link MethodBaselineCheck}. This method
     * exists so XposedHook.loadModule can call both classes
     * symmetrically, and so callers that were written against the
     * old ApiProtectionCheck.captureDynamicBaselines API keep
     * working.
     *
     * Calling this at install() time captures nothing useful (no
     * module entry point has run yet). The per-module call from
     * XposedHook.loadModule is the one that matters.
     */
    public static void captureDynamicBaselines(ClassLoader classLoader) {
        try {
            MethodBaselineCheck.captureDynamicBaselines(classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": captureDynamicBaselines delegate "
                + "failed: " + t.getMessage());
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
    // REFLECTION WALKS — list APIs
    //
    // These drop protected-class members from the returned array.
    // No count trimming: MethodBaselineCheck owns the baseline
    // and runs its own filter on getDeclaredMethods / getMethods.
    // Running a count trim here would conflict with that filter
    // (it would trim to a different N before MethodBaselineCheck
    // saw the array).
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
    // NEW — named lookups and nested classes
    //
    // A detector that knows the shim method's name and signature
    // can bypass getDeclaredMethods() by asking for the Method
    // directly. These hooks refuse the lookup on a protected class
    // for a non-module caller, which is the same answer a class
    // without that method would give.
    //
    // getDeclaredClasses() returns the nested interfaces of
    // XposedInterface / XposedModule, which is the full
    // libxposed surface. A non-module caller gets an empty array.
    // ═════════════════════════════════════════════════════════════

    private static void hookClassDeclaredMethod(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredMethod",
                String.class, Class[].class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (isTrustedCaller()) return;
                        Class<?> self = (Class<?>) param.thisObject;
                        if (!isProtectedClass(self)) return;
                        String name = (String) param.args[0];
                        XposedBridge.log(TAG + ": refused getDeclaredMethod("
                            + name + ") on protected class from non-module caller");
                        param.setThrowable(new NoSuchMethodException(name));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredMethod hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getMethod",
                String.class, Class[].class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (isTrustedCaller()) return;
                        Class<?> self = (Class<?>) param.thisObject;
                        if (!isProtectedClass(self)) return;
                        String name = (String) param.args[0];
                        XposedBridge.log(TAG + ": refused getMethod("
                            + name + ") on protected class from non-module caller");
                        param.setThrowable(new NoSuchMethodException(name));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getMethod hook failed: " + t);
        }
    }

    private static void hookClassDeclaredConstructor(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredConstructor",
                Class[].class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (isTrustedCaller()) return;
                        Class<?> self = (Class<?>) param.thisObject;
                        if (!isProtectedClass(self)) return;
                        XposedBridge.log(TAG + ": refused getDeclaredConstructor "
                            + "on protected class from non-module caller");
                        param.setThrowable(new NoSuchMethodException("<init>"));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredConstructor hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getConstructor",
                Class[].class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (isTrustedCaller()) return;
                        Class<?> self = (Class<?>) param.thisObject;
                        if (!isProtectedClass(self)) return;
                        XposedBridge.log(TAG + ": refused getConstructor "
                            + "on protected class from non-module caller");
                        param.setThrowable(new NoSuchMethodException("<init>"));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getConstructor hook failed: " + t);
        }
    }

    private static void hookClassDeclaredField(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredField",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (isTrustedCaller()) return;
                        Class<?> self = (Class<?>) param.thisObject;
                        if (!isProtectedClass(self)) return;
                        String name = (String) param.args[0];
                        XposedBridge.log(TAG + ": refused getDeclaredField("
                            + name + ") on protected class from non-module caller");
                        param.setThrowable(new NoSuchFieldException(name));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredField hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getField",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (isTrustedCaller()) return;
                        Class<?> self = (Class<?>) param.thisObject;
                        if (!isProtectedClass(self)) return;
                        String name = (String) param.args[0];
                        XposedBridge.log(TAG + ": refused getField("
                            + name + ") on protected class from non-module caller");
                        param.setThrowable(new NoSuchFieldException(name));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getField hook failed: " + t);
        }
    }

    private static void hookClassDeclaredClasses(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getDeclaredClasses",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isTrustedCaller()) return;
                            Class<?> self = (Class<?>) param.thisObject;
                            if (!isProtectedClass(self)) return;
                            Class<?>[] original = (Class<?>[]) param.getResult();
                            if (original == null || original.length == 0) return;
                            // For a protected class queried by an
                            // untrusted caller, the right answer is
                            // "no nested classes" — the same answer a
                            // class with none would give. Not "filter
                            // some and keep others": the whole
                            // nested-class list is framework surface.
                            param.setResult(new Class<?>[0]);
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getDeclaredClasses hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                Class.class, "getClasses",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isTrustedCaller()) return;
                            Class<?> self = (Class<?>) param.thisObject;
                            if (!isProtectedClass(self)) return;
                            Class<?>[] original = (Class<?>[]) param.getResult();
                            if (original == null || original.length == 0) return;
                            param.setResult(new Class<?>[0]);
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getClasses hook failed: " + t);
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
                if (cn.startsWith("io.github.libxposed.api.")) return true;
                if (cn.startsWith("io.github.libxposed.annotation.")) return true;
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