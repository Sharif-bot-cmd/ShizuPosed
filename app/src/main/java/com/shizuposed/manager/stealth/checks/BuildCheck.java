package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import android.os.Build;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BuildCheck
 *
 * Sanitizes android.os.Build static fields after the class loads.
 * Detection kits read these fields directly (they're public and
 * non-final) to look for tell-tale values that indicate a custom
 * ROM, an engineering build, or a modified system.
 *
 * ─────────────────────────────────────────────────────────────
 * WHY THIS EXISTS
 * ─────────────────────────────────────────────────────────────
 *
 * android.os.Build has static String fields that are populated
 * from system properties at class-initialization time:
 *
 *   Build.TAGS     → "release-keys" (normal) or "test-keys"
 *   Build.TYPE     → "user" (normal), "userdebug", "eng"
 *   Build.FINGERPRINT → composite string
 *   Build.HOST     → build host
 *   Build.USER     → build user
 *
 * Detection code:
 *
 *   if (Build.TAGS.contains("test-keys")) { suspicious(); }
 *   if (Build.TYPE.equals("userdebug")) { suspicious(); }
 *
 * Because these are static fields and not methods, hooking reads
 * is harder than hooking methods. The pragmatic approach is to
 * hook the class initializer (<clinit>) and replace the field
 * values once the class is loaded.
 *
 * This runs after the target's classloader loads Build. It does
 * not affect system_server or other processes.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS COVERS
 * ─────────────────────────────────────────────────────────────
 *
 *   • Build.TAGS     → "release-keys"
 *   • Build.TYPE     → "user"
 *   • Build.HOST     → "android-build"
 *   • Build.USER     → "android-build"
 *   • Build.FINGERPRINT → left as-is (composite; changing it is
 *     more likely to break apps that check it against the
 *     framework's value than to help)
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS DOES NOT COVER
 * ─────────────────────────────────────────────────────────────
 *
 *   • Native reads of the underlying system properties via
 *     __system_property_get. SystemPropertiesCheck handles the
 *     Java-level reads; a native interposer would be needed for
 *     JNI-level reads.
 *   • Field reads that happen before the <clinit> hook runs. On
 *     a warm app process, Build may already be loaded. If the
 *     app reads Build fields during its own <clinit> and that
 *     runs before XStealthModule.handleLoadPackage, this check
 *     can't help. Rare.
 */
public final class BuildCheck {

    private static final String TAG = "XStealth";

    private BuildCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) return;

        try {
            Class<?> buildClass = Class.forName("android.os.Build", false,
                lpparam.classLoader);

            // Ensure the class is initialized so its <clinit> has
            // already run (and fields are populated).
            try {
                Class.forName("android.os.Build", true,
                    lpparam.classLoader);
            } catch (Throwable ignored) {}

            boolean applied = false;
            applied |= setField(buildClass, "TAGS", "release-keys");
            applied |= setField(buildClass, "TYPE", "user");
            applied |= setField(buildClass, "HOST", "android-build");
            applied |= setField(buildClass, "USER", "android-build");

            if (applied) {
                XStealthRegistry.record("BuildCheck");
                XposedBridge.log(TAG + ": BuildCheck installed "
                    + "(TAGS=release-keys, TYPE=user)");
            } else {
                XposedBridge.log(TAG + ": BuildCheck could not set any fields");
            }

            // Additional hardening: hook any subsequent reads via
            // the reflection path. This covers apps that read the
            // field via Field.get() rather than direct access.
            try {
                XposedHelpers.findAndHookMethod(
                    Class.class, "getField", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                java.lang.reflect.Field f =
                                    (java.lang.reflect.Field) p.getResult();
                                if (f == null) return;
                                if (f.getDeclaringClass() != buildClass) return;
                                String name = f.getName();
                                if ("TAGS".equals(name)) {
                                    f.set(null, "release-keys");
                                } else if ("TYPE".equals(name)) {
                                    f.set(null, "user");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": BuildCheck failed: " + t.getMessage());
        }
    }

    private static boolean setField(Class<?> clazz, String name, String value) {
        try {
            java.lang.reflect.Field f = clazz.getField(name);
            f.setAccessible(true);
            Object current = f.get(null);
            if (current instanceof String && value.equals(current)) {
                return true; // already correct
            }
            f.set(null, value);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Build." + name + " set failed: "
                + t.getMessage());
            return false;
        }
    }
}