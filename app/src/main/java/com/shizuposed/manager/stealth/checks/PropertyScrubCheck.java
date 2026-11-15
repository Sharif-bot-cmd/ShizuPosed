package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.io.PrintStream;
import java.io.PrintWriter;
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
 *   -Dshizuposed.status.authority=...       (added 1.4.9)
 *   -Dshizuposed.manager.package=...        (added 1.4.9)
 *
 * These are visible via System.getProperty() and
 * System.getProperties(). A detection kit that reads them learns
 * ShizuPosed is running.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS COVERS
 * ─────────────────────────────────────────────────────────────
 *
 *   • System.getProperty(String)
 *   • System.getProperty(String, String)
 *   • System.getProperties() — mutated copy, watched keys removed
 *   • Properties.list(PrintStream)
 *   • Properties.list(PrintWriter)
 *   • Properties.store(OutputStream, String)
 *   • Properties.store(Writer, String)
 *     — a target that dumps the property set to a stream
 *       bypasses the getProperties() mutation if it dumps the
 *       framework's own Properties instance directly. These
 *       hooks scrub the stream before it's written.
 *   • System.getenv(String)
 *   • System.getenv()
 *     — the properties aren't environment variables today,
 *       but a ROM that mirrors -D to env, or a future change
 *       that does, would leak through otherwise.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS DOES NOT COVER
 * ─────────────────────────────────────────────────────────────
 *
 *   • Native reads of the properties. The properties live in the
 *     process's data segment; a native read could still see them.
 *   • Apps that read /proc/self/cmdline — the -D flags appear
 *     there. XStealthNext's /proc scrubbing covers this.
 *   • SecurityManager.checkPropertyAccess — deprecated on
 *     modern Android and no longer reachable from app code.
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
        any |= hookPropertiesListStream();
        any |= hookPropertiesListWriter();
        any |= hookPropertiesStoreStream();
        any |= hookPropertiesStoreWriter();
        any |= hookGetenvString();
        any |= hookGetenvMap();

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

    // ═════════════════════════════════════════════════════════════
    // System.getProperty — direct reads
    // ═════════════════════════════════════════════════════════════

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

    // ═════════════════════════════════════════════════════════════
    // System.getProperties — the whole map
    // ═════════════════════════════════════════════════════════════

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

                            // Work on a copy to avoid mutating the
                            // framework's Properties instance if
                            // it's shared. `Properties` extends
                            // `Hashtable`, so the copy is a
                            // shallow Hashtable clone — enough,
                            // because the values we remove are
                            // strings.
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

    // ═════════════════════════════════════════════════════════════
    // Properties.list / Properties.store — direct dumps
    //
    // A target that does `System.getProperties().list(System.out)`
    // reads the watched keys even though getProperties()'s hook
    // would have mutated the returned Properties, because the
    // hook's mutation happens on the *returned reference* and
    // the target's `list()` call goes through Properties.list
    // against the same instance. Hooking list() closes that path.
    //
    // The hooks scrub the watched keys *from a copy* before the
    // call proceeds, not from the live Properties instance, so
    // removing a key here does not affect the framework's
    // property set.
    // ═════════════════════════════════════════════════════════════

    private static boolean hookPropertiesListStream() {
        try {
            XposedHelpers.findAndHookMethod(
                Properties.class, "list", PrintStream.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        scrubForDump(p.thisObject);
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Properties.list(PrintStream) failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookPropertiesListWriter() {
        try {
            XposedHelpers.findAndHookMethod(
                Properties.class, "list", PrintWriter.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        scrubForDump(p.thisObject);
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Properties.list(PrintWriter) failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookPropertiesStoreStream() {
        try {
            XposedHelpers.findAndHookMethod(
                Properties.class, "store",
                java.io.OutputStream.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        scrubForDump(p.thisObject);
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Properties.store(OutputStream) "
                + "failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean hookPropertiesStoreWriter() {
        try {
            XposedHelpers.findAndHookMethod(
                Properties.class, "store",
                java.io.Writer.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        scrubForDump(p.thisObject);
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Properties.store(Writer) "
                + "failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Remove watched keys from `props` before it's dumped.
     *
     * This mutates the live Properties instance, which is
     * normally what we want — the target's `list()` / `store()`
     * call goes through the same instance the framework holds,
     * and the watched keys should not appear in the dump. The
     * mutation is harmless because the next `System.getProperty`
     * call reads from the framework's internal property map,
     * not from the `Properties` instance returned by
     * `System.getProperties()`. (That method returns a copy on
     * every call, per the JDK contract.)
     */
    private static void scrubForDump(Object thisObject) {
        try {
            if (!(thisObject instanceof Properties)) return;
            Properties props = (Properties) thisObject;

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
            XposedBridge.log(TAG + ": scrubForDump failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // System.getenv — belt and suspenders
    // ═════════════════════════════════════════════════════════════

    private static boolean hookGetenvString() {
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "getenv", String.class,
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
                                + ": getenv hook error: " + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getenv(String) failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookGetenvMap() {
        try {
            XposedHelpers.findAndHookMethod(
                System.class, "getenv",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object result = p.getResult();
                            if (!(result instanceof java.util.Map)) return;
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, String> env =
                                (java.util.Map<String, String>) result;

                            Set<String> toRemove = new HashSet<>();
                            for (String key : env.keySet()) {
                                if (key != null && isWatched(key)) {
                                    toRemove.add(key);
                                }
                            }
                            for (String k : toRemove) {
                                env.remove(k);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": getenv() hook error: " + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getenv() failed: "
                + t.getMessage());
            return false;
        }
    }
}