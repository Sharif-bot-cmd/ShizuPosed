package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PropertyScrubCheck
 *
 * Scrubs ShizuPosed's own system properties from Java's view.
 * When ShizuPosed spawns app_process, it passes several -D flags:
 *
 *   -Dshizuposed.shell.base=...
 *   -Dshizuposed.shell.libs=...
 *   -Dshizuposed.hook.delay=...
 *
 * These are visible via System.getProperty() and
 * System.getProperties(). A detection kit that reads them learns
 * ShizuPosed is running.
 *
 * This check hooks System.getProperty(String),
 * System.getProperty(String, String), and System.getProperties()
 * to remove the shizuposed.* keys from the returned values.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS COVERS
 * ─────────────────────────────────────────────────────────────
 *
 *   • System.getProperty(String)
 *   • System.getProperty(String, String)
 *   • System.getProperties()
 *   • System.getenv() is not covered — the properties are not
 *     exposed as environment variables.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS DOES NOT COVER
 * ─────────────────────────────────────────────────────────────
 *
 *   • Native reads of the properties. The properties live in the
 *     process's data segment; a native read could still see them.
 *   • Apps that read /proc/self/environ — the properties are not
 *     in the environment, so this is not a concern.
 *   • Apps that read /proc/self/cmdline — the -D flags appear
 *     there. XStealthNext's /proc scrubbing covers this.
 */
public final class PropertyScrubCheck {

    private static final String TAG = "XStealth";

    private static final Set<String> WATCHED_PREFIXES = new HashSet<>();
    static {
        WATCHED_PREFIXES.add("shizuposed.");
    }

    private PropertyScrubCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null) return;

        boolean any = false;
        any |= hookGetPropertyString();
        any |= hookGetPropertyStringWithDefault();
        any |= hookGetProperties();

        if (any) {
            XStealthRegistry.record("PropertyScrubCheck");
            XposedBridge.log(TAG + ": PropertyScrubCheck installed");
        }
    }

    private static boolean isWatched(String key) {
        if (key == null) return false;
        for (String prefix : WATCHED_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean hookGetPropertyString() {
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "getProperty", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 1) return;
                            Object k = p.args[0];
                            if (!(k instanceof String)) return;
                            if (isWatched((String) k)) {
                                p.setResult(null);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": getProperty hook error: " + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getProperty(String) failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookGetPropertyStringWithDefault() {
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "getProperty", String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 1) return;
                            Object k = p.args[0];
                            if (!(k instanceof String)) return;
                            if (isWatched((String) k)) {
                                Object def = (p.args.length >= 2)
                                    ? p.args[1] : null;
                                p.setResult(def);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": getProperty(String,String) hook error: "
                                + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getProperty(String,String) failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookGetProperties() {
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "getProperties",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object result = p.getResult();
                            if (!(result instanceof Properties)) return;
                            Properties props = (Properties) result;

                            // Remove watched keys. Work on a copy
                            // to avoid mutating the framework's
                            // Properties instance if it's shared.
                            Set<Object> toRemove = new HashSet<>();
                            for (Object key : props.keySet()) {
                                if (key instanceof String
                                        && isWatched((String) key)) {
                                    toRemove.add(key);
                                }
                            }
                            for (Object k : toRemove) {
                                props.remove(k);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": getProperties hook error: " + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getProperties() failed: "
                + t.getMessage());
            return false;
        }
    }
}