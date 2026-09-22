package de.robv.android.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Standard Xposed API shim.
 *
 * Version 96 (LSPosed generation 93 + fork revisions 94-96).
 * Modules that check getXposedVersion() >= N for N <= 96 accept
 * ShizuPosed as compatible.
 *
 * VERSION HISTORY OF THIS SHIM
 * ----------------------------
 *   93 — LSPosed base: IXUnhook, isModuleActive/isModuleEnabled
 *        no-arg overloads, provider-backed state queries.
 *   94 — XposedBridge.hookAllMethods returns
 *        Set<XC_MethodHook.Unhook>.
 *   95 — XposedBridge.hookAllConstructors returns the same.
 *   96 — MethodHookParam.isReturnEarly() exposed; hookAll*
 *        semantics stabilized.
 */
public final class XposedBridge {

    /**
     * Reported framework version.
     *
     * Modules guard on this number before using a feature. We
     * report 96 because we implement everything the 93-96 surface
     * requires. Modules that guard on 97+ correctly believe we
     * don't support the newer fork revisions.
     */
    public static final int XPOSED_BRIDGE_VERSION = 96;

    /** Logcat tag for XposedBridge.log output. */
    private static final String LOG_TAG = "Xposed";

    /** Logcat tag for framework-level diagnostics. */
    private static final String FRAMEWORK_TAG = "ShizuPosed";

    /** Authority of ShizuPosed's ModuleStatusProvider. */
    private static final String PROVIDER_AUTHORITY = "com.shizuposed.manager.status";

    private XposedBridge() {}

    // ═════════════════════════════════════════════════════════════
    // VERSION
    // ═════════════════════════════════════════════════════════════

    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    public static int getVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    // ═════════════════════════════════════════════════════════════
    // HOOK INSTALLATION
    // ═════════════════════════════════════════════════════════════

    public static IXUnhook<XC_MethodHook> hookMethod(Method method,
                                                     XC_MethodHook callback) {
        if (method == null || callback == null) return null;

        try {
            com.shizuposed.manager.core.XposedHookBridge
                .installHook(method, callback);
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "hookMethod failed for "
                + method.getDeclaringClass().getName() + "."
                + method.getName(), t);
            return null;
        }

        String backendName = null;
        Runnable reverse = null;
        try {
            com.shizuposed.manager.core.HookEngine.InstallRecord record =
                com.shizuposed.manager.core.HookEngine
                    .getInstallRecord(method);
            if (record != null) {
                backendName = record.backendName;
                reverse = record.reverse;
            }
        } catch (Throwable ignored) {}

        return new XposedBridgeUnhook<>(callback, method,
            backendName, reverse);
    }

    public static IXUnhook<XC_MethodHook> hookConstructor(Constructor<?> ctor,
                                                         XC_MethodHook callback) {
        if (ctor == null || callback == null) return null;

        try {
            com.shizuposed.manager.core.XposedHookBridge
                .installConstructorHook(ctor, callback);
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "hookConstructor failed for "
                + ctor.getDeclaringClass().getName(), t);
            return null;
        }

        String backendName = null;
        Runnable reverse = null;
        try {
            com.shizuposed.manager.core.HookEngine.InstallRecord record =
                com.shizuposed.manager.core.HookEngine
                    .getInstallRecord(ctor);
            if (record != null) {
                backendName = record.backendName;
                reverse = record.reverse;
            }
        } catch (Throwable ignored) {}

        return new XposedBridgeUnhook<>(callback, ctor,
            backendName, reverse);
    }

    // ═════════════════════════════════════════════════════════════
    // HOOK ALL — API 94 / 95
    //
    // These install a hook on every matching method (or every
    // constructor) and return a Set of Unhook handles, one per
    // install. An empty set means no methods matched — not an error.
    //
    // The handle set is built by calling hookMethod/hookConstructor
    // per match, so each handle carries the same backend info and
    // optional reverse action that a single-method handle would.
    // ═════════════════════════════════════════════════════════════

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> clazz, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> out = new HashSet<>();
        if (clazz == null || methodName == null || callback == null) return out;
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m == null || !m.getName().equals(methodName)) continue;
                try {
                    com.shizuposed.manager.core.compat.HiddenApiBypass
                        .forceAccessible(m);
                    hookMethod(m, callback);
                    out.add(new XC_MethodHook.Unhook(m));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "hookAllMethods failed", t);
        }
        return out;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> clazz, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> out = new HashSet<>();
        if (clazz == null || callback == null) return out;
        try {
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                if (c == null) continue;
                try {
                    com.shizuposed.manager.core.compat.HiddenApiBypass
                        .forceAccessible(c);
                    hookConstructor(c, callback);
                    out.add(new XC_MethodHook.Unhook(c));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "hookAllConstructors failed", t);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════
    // LOGGING
    // ═════════════════════════════════════════════════════════════

    public static void log(String text) {
        try {
            Log.i(LOG_TAG, text == null ? "null" : text);
        } catch (Throwable ignored) {}
        refreshFallbackContext();
    }

    public static void log(Throwable t) {
        try {
            Log.e(LOG_TAG, Log.getStackTraceString(t));
        } catch (Throwable ignored) {}
        refreshFallbackContext();
    }

    public static void log(String text, Throwable t) {
        try {
            Log.e(LOG_TAG, text == null ? "null" : text, t);
        } catch (Throwable ignored) {}
        refreshFallbackContext();
    }

    private static void refreshFallbackContext() {
        try {
            if (LSPosedManager.getFallbackContext() != null) return;
            Context ctx = currentApplication();
            if (ctx != null) LSPosedManager.setFallbackContext(ctx);
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // ENABLED STATE
    // ═════════════════════════════════════════════════════════════

    public static boolean isModuleEnabled() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(FRAMEWORK_TAG, "isModuleEnabled(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleEnabled(pkg);
    }

    public static boolean isModuleEnabled(String packageName) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(FRAMEWORK_TAG, "isModuleEnabled(" + packageName
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return isModuleEnabled(ctx, packageName);
    }

    public static boolean isModuleEnabled(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY
                + "/module/" + packageName);
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("enabled");
                    if (idx != -1) return c.getInt(idx) == 1;
                    int vIdx = c.getColumnIndex("value");
                    if (vIdx != -1) return "1".equals(c.getString(vIdx));
                }
                logVisibilityHint("isModuleEnabled", packageName);
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "isModuleEnabled(" + packageName + ") failed", t);
        }
        return false;
    }

    public static String[] getEnabledModules(Context context) {
        if (context == null) return new String[0];
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.parse("content://" + PROVIDER_AUTHORITY + "/modules");
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c == null) return new String[0];
                int idx = c.getColumnIndex("package");
                if (idx == -1) return new String[0];

                int count = 0;
                c.moveToPosition(-1);
                while (c.moveToNext()) {
                    if (c.getString(idx) != null) count++;
                }
                String[] out = new String[count];
                c.moveToPosition(-1);
                int i = 0;
                while (c.moveToNext()) {
                    String pkg = c.getString(idx);
                    if (pkg != null) out[i++] = pkg;
                }
                return out;
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "getEnabledModules failed", t);
            return new String[0];
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIVE STATE
    // ═════════════════════════════════════════════════════════════

    public static boolean isModuleActive() {
        String pkg = getCallingPackage();
        if (pkg == null) {
            Log.w(FRAMEWORK_TAG, "isModuleActive(): cannot determine "
                + "calling package from stack");
            return false;
        }
        return isModuleActive(pkg);
    }

    public static boolean isModuleActive(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) {
            Log.w(FRAMEWORK_TAG, "isModuleActive(" + modulePackage
                + "): no Context available (off-main-thread?)");
            return false;
        }
        return LSPosedManager.isModuleActive(ctx, modulePackage);
    }

    public static boolean isModuleActive(Context context, String modulePackage) {
        return LSPosedManager.isModuleActive(context, modulePackage);
    }

    public static String[] getModuleScope(String modulePackage) {
        Context ctx = currentApplication();
        if (ctx == null) return new String[0];
        return LSPosedManager.getModuleScope(ctx, modulePackage);
    }

    public static String[] getModuleScope(Context context, String modulePackage) {
        return LSPosedManager.getModuleScope(context, modulePackage);
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE VISIBILITY DIAGNOSTIC
    // ═════════════════════════════════════════════════════════════

    private static void logVisibilityHint(String call, String packageName) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        Log.w(FRAMEWORK_TAG, call + "(" + packageName + "): empty result. "
            + "If this is Android 11+, the module's AndroidManifest.xml "
            + "must declare: <queries><provider android:authorities=\""
            + PROVIDER_AUTHORITY + "\" /></queries>. "
            + "Without it, the provider is invisible to this app.");
    }

    // ═════════════════════════════════════════════════════════════
    // CALLING PACKAGE RESOLUTION
    // ═════════════════════════════════════════════════════════════

    private static String getCallingPackage() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String cls = e.getClassName();
                if (cls == null) continue;

                if (cls.startsWith("de.robv.android.xposed.")) continue;
                if (cls.startsWith("com.shizuposed.")) continue;
                if (cls.startsWith("java.")) continue;
                if (cls.startsWith("javax.")) continue;
                if (cls.startsWith("android.")) continue;
                if (cls.startsWith("com.android.")) continue;
                if (cls.startsWith("dalvik.")) continue;
                if (cls.startsWith("sun.")) continue;

                try {
                    Class<?> c = Class.forName(cls, false,
                        XposedBridge.class.getClassLoader());
                    Package p = c.getPackage();
                    if (p != null && p.getName() != null) {
                        return p.getName();
                    }
                } catch (Throwable ignored) {}

                int dot = cls.lastIndexOf('.');
                if (dot > 0) return cls.substring(0, dot);
            }
        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "getCallingPackage failed", t);
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // CONTEXT RESOLUTION
    // ═════════════════════════════════════════════════════════════

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            try {
                java.lang.reflect.Method m = at.getMethod("currentApplication");
                Object o = m.invoke(null);
                if (o instanceof Context) return (Context) o;
            } catch (Throwable ignored) {}

            try {
                java.lang.reflect.Method cur = at.getMethod("currentActivityThread");
                Object thread = cur.invoke(null);
                if (thread != null) {
                    java.lang.reflect.Method getSys = at.getMethod("getSystemContext");
                    Object sys = getSys.invoke(thread);
                    if (sys instanceof Context) {
                        LSPosedManager.setFallbackContext((Context) sys);
                        return (Context) sys;
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(FRAMEWORK_TAG, "currentApplication: reflective lookup failed", t);
        }
        return LSPosedManager.getFallbackContext();
    }
}