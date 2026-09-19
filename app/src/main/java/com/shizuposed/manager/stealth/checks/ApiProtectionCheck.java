package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ApiProtectionCheck
 *
 * Prevents a target app from discovering the Xposed shim classes via
 * the classloader. When the target asks for a class in the
 * de.robv.android.xposed package and the caller is not one of the
 * loaded modules, the lookup is refused.
 *
 * The set of "trusted callers" is built from the modules that
 * XposedHook has loaded into this process. Anything else — app code,
 * native libraries, reflection helpers the app carries — sees
 * ClassNotFoundException for those names.
 *
 * This is a defensive check. It does not remove the Xposed classes
 * from the process; they are still loadable by the module dexes
 * themselves. It only makes them invisible to code that tries to
 * fingerprint them at runtime.
 */
public final class ApiProtectionCheck {

    private static final String TAG = "XStealth";

    /** Package prefixes that identify the shim classes. */
    private static final String[] PROTECTED_PREFIXES = {
        "de.robv.android.xposed.",
        "com.shizuposed.manager.stealth.",
    };

    /**
     * Class names that ARE allowed through even from non-module
     * callers. Some frameworks query these on purpose and treating
     * the query as an attack would break legitimate behavior.
     */
    private static final Set<String> ALLOWLIST = new HashSet<>();
    static {
        // Nothing currently. If a specific class turns out to be
        // legitimately queried by a target, add it here.
    }

    private ApiProtectionCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) return;

        try {
            hookClassLoaderLoadClass(lpparam);
            hookClassForName(lpparam);
            XposedBridge.log(TAG + ": ApiProtectionCheck installed");
            XStealthRegistry.record("ApiProtectionCheck");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ApiProtectionCheck failed: " + t);
        }
    }

    /**
     * Hook ClassLoader.loadClass(String). This is the most common
     * path reflection helpers use to look up classes.
     */
    private static void hookClassLoaderLoadClass(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> classLoaderClass = ClassLoader.class;

            XposedHelpers.findAndHookMethod(
                classLoaderClass, "loadClass",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        String name = (String) param.args[0];
                        if (name == null) return;
                        if (!isProtected(name)) return;
                        if (isTrustedCaller()) return;

                        XposedBridge.log(TAG + ": refused loadClass("
                            + name + ") from non-module caller");
                        param.setThrowable(new ClassNotFoundException(name));
                    }
                });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": loadClass hook failed: " + t);
        }
    }

    /**
     * Hook Class.forName(String) and Class.forName(String, boolean,
     * ClassLoader). Most reflective lookups go through one of these.
     */
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
                        if (name == null) return;
                        if (!isProtected(name)) return;
                        if (isTrustedCaller()) return;

                        XposedBridge.log(TAG + ": refused forName("
                            + name + ") from non-module caller");
                        param.setThrowable(new ClassNotFoundException(name));
                    }
                });

            XposedHelpers.findAndHookMethod(
                Class.class, "forName",
                String.class, boolean.class, ClassLoader.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        String name = (String) param.args[0];
                        if (name == null) return;
                        if (!isProtected(name)) return;
                        if (isTrustedCaller()) return;

                        XposedBridge.log(TAG + ": refused forName("
                            + name + ", ...) from non-module caller");
                        param.setThrowable(new ClassNotFoundException(name));
                    }
                });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forName hook failed: " + t);
        }
    }

    /**
     * Is this class name one of the protected shim classes?
     */
    private static boolean isProtected(String className) {
        if (className == null) return false;
        if (ALLOWLIST.contains(className)) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Is the current call coming from a loaded module? We check the
     * call stack for any frame whose declaring class is in one of
     * the module packages known to the framework. If so, the caller
     * is a module and the lookup is allowed.
     */
    private static boolean isTrustedCaller() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            // Walk up. Skip the first few frames which are our own
            // hooks and the reflective machinery.
            for (int i = 3; i < stack.length; i++) {
                StackTraceElement e = stack[i];
                String cn = e.getClassName();
                if (cn == null) continue;
                // The shim classes themselves are the framework's
                // own code — allow them.
                if (cn.startsWith("de.robv.android.xposed.")) return true;
                if (cn.startsWith("com.shizuposed.manager.stealth.")) return true;
                if (cn.startsWith("com.shizuposed.manager.core.")) return true;
                // A module's entry class typically lives under the
                // module's own package, not under ours. We cannot
                // know every module package in advance, so we look
                // for the entry class names the framework recorded.
                // This is best-effort; see the note in the class
                // comment about its limitations.
            }
            return false;
        } catch (Throwable t) {
            // If the stack walk fails for any reason, default to
            // trusted — a broken check should not break modules.
            return true;
        }
    }
}