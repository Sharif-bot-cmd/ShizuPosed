package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemPropertiesCheck
 *
 * Sanitizes reads of android.os.SystemProperties for a set of keys
 * that reveal the device's build type, verified boot state, and
 * USB/ADB configuration.
 *
 * v0.2.0 changes:
 *   • Extended the USB/ADB property watch list. The previous list
 *     covered persist.sys.usb.config and service.adb.root but not
 *     the runtime properties sys.usb.config, sys.usb.state, or
 *     sys.usb.adb.disabled — which are what most detection kits
 *     actually read.
 *
 *   • Added ro.bootmode, ro.boot.mode, and sys.oem_unlock_allowed
 *     to the build-state watch list.
 *
 * The watch list is the canonical set of keys a detector can read
 * to infer that developer options or USB debugging is enabled. It
 * is not exhaustive — new ROMs add new keys — but it covers the
 * ones that appear in real detection code.
 */
public final class SystemPropertiesCheck {

    private static final String TAG = "XStealth";

    private static final String SYSTEM_PROPERTIES_CLASS =
        "android.os.SystemProperties";

    /**
     * Watched keys and the sanitized values returned for them.
     *
     * The values are chosen to match what a stock device with
     * developer options off and USB set to MTP would report. They
     * are internally consistent: persist.sys.usb.config and
     * sys.usb.config both report the same value, so a detector
     * that cross-checks them sees no mismatch.
     */
    private static final Map<String, String> WATCHED;
    static {
        Map<String, String> m = new ConcurrentHashMap<>();

        // ── Build state ─────────────────────────────────────────
        m.put("ro.debuggable", "0");
        m.put("ro.secure", "1");
        m.put("ro.adb.secure", "1");
        m.put("ro.build.type", "user");
        m.put("ro.build.tags", "release-keys");
        m.put("ro.build.selinux", "1");

        // ── Verified boot / bootloader ──────────────────────────
        m.put("ro.boot.verifiedbootstate", "green");
        m.put("ro.boot.flash.locked", "1");
        m.put("ro.boot.veritymode", "enforcing");
        m.put("ro.boot.warranty_bit", "0");
        m.put("ro.warranty_bit", "0");
        m.put("ro.boot.secureboot", "1");
        m.put("ro.boot.dm_verity", "enforcing");

        // ── Emulator ────────────────────────────────────────────
        m.put("ro.kernel.qemu", "0");

        // ── USB / ADB — the ones that leaked on the OP5B16L1 ────
        // These were missing from the previous watch list. The
        // device shows sys.usb.config = "midi,adb", which is what
        // a detector reads to see that ADB is enabled.
        m.put("sys.usb.adb.disabled", "1");      // NEW
        m.put("sys.usb.config", "mtp");           // NEW
        m.put("sys.usb.state", "mtp");            // NEW
        m.put("sys.usb.configfs", "0");           // NEW
        m.put("sys.usb.controller", "");          // NEW

        // ── Persistent USB config ───────────────────────────────
        // The sanitized value matches sys.usb.config so a detector
        // cross-checking them sees consistency.
        m.put("persist.sys.usb.config", "mtp");
        m.put("persist.sys.usb.qmmi.func", "mtp");
        m.put("persist.sys.usb.reboot.config", "mtp");
        m.put("persist.sys.usb.reboot.func", "mtp");
        m.put("persist.vendor.usb.config", "mtp");
        m.put("vendor.usb.config", "mtp");

        // ── ADB ─────────────────────────────────────────────────
        m.put("service.adb.root", "0");
        m.put("service.adb.tcp.port", "-1");
        m.put("persist.adb.tcp.port", "-1");

        // ── OEM unlock ──────────────────────────────────────────
        m.put("sys.oem_unlock_allowed", "0");
        m.put("ro.oem_unlock_supported", "0");
        m.put("ro.bootmode", "normal");
        m.put("ro.boot.mode", "normal");

        WATCHED = Collections.unmodifiableMap(m);
    }

    private SystemPropertiesCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) return;

        Class<?> sp;
        try {
            sp = Class.forName(SYSTEM_PROPERTIES_CLASS, false,
                lpparam.classLoader);
        } catch (Throwable t) {
            try {
                sp = Class.forName(SYSTEM_PROPERTIES_CLASS);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": SystemProperties class not "
                    + "available: " + t2.getMessage());
                return;
            }
        }
        if (sp == null) return;

        boolean any = false;
        any |= hookStringGet(sp, "get",
            new Class<?>[]{ String.class });
        any |= hookStringGet(sp, "get",
            new Class<?>[]{ String.class, String.class });
        any |= hookBooleanGet(sp, "getBoolean",
            new Class<?>[]{ String.class, boolean.class });
        any |= hookIntGet(sp, "getInt",
            new Class<?>[]{ String.class, int.class });
        any |= hookLongGet(sp, "getLong",
            new Class<?>[]{ String.class, long.class });

        if (any) {
            XStealthRegistry.record("SystemPropertiesCheck");
            XposedBridge.log(TAG + ": SystemPropertiesCheck installed ("
                + WATCHED.size() + " watched keys)");
        } else {
            XposedBridge.log(TAG + ": SystemPropertiesCheck found no "
                + "hookable methods");
        }
    }

    private static boolean hookStringGet(Class<?> clazz, String name,
                                          Class<?>[] sig) {
        try {
            XposedHelpers.findAndHookMethod(clazz, name, sig,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 1) return;
                            Object keyObj = p.args[0];
                            if (!(keyObj instanceof String)) return;
                            String key = (String) keyObj;
                            String sanitized = WATCHED.get(key);
                            if (sanitized == null) return;
                            p.setResult(sanitized);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": SystemProperties.get hook error: " + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + name + "(" + sig.length
                + " args) failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean hookBooleanGet(Class<?> clazz, String name,
                                           Class<?>[] sig) {
        try {
            XposedHelpers.findAndHookMethod(clazz, name, sig,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 1) return;
                            Object keyObj = p.args[0];
                            if (!(keyObj instanceof String)) return;
                            String key = (String) keyObj;
                            String sanitized = WATCHED.get(key);
                            if (sanitized == null) return;
                            boolean v = "1".equals(sanitized)
                                || "true".equalsIgnoreCase(sanitized);
                            p.setResult(v);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": SystemProperties.getBoolean hook error: "
                                + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + name + " failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookIntGet(Class<?> clazz, String name,
                                       Class<?>[] sig) {
        try {
            XposedHelpers.findAndHookMethod(clazz, name, sig,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 1) return;
                            Object keyObj = p.args[0];
                            if (!(keyObj instanceof String)) return;
                            String key = (String) keyObj;
                            String sanitized = WATCHED.get(key);
                            if (sanitized == null) return;
                            try {
                                p.setResult(Integer.parseInt(sanitized));
                            } catch (NumberFormatException nfe) {
                                p.setResult(0);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": SystemProperties.getInt hook error: "
                                + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + name + " failed: "
                + t.getMessage());
            return false;
        }
    }

    private static boolean hookLongGet(Class<?> clazz, String name,
                                        Class<?>[] sig) {
        try {
            XposedHelpers.findAndHookMethod(clazz, name, sig,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 1) return;
                            Object keyObj = p.args[0];
                            if (!(keyObj instanceof String)) return;
                            String key = (String) keyObj;
                            String sanitized = WATCHED.get(key);
                            if (sanitized == null) return;
                            try {
                                p.setResult(Long.parseLong(sanitized));
                            } catch (NumberFormatException nfe) {
                                p.setResult(0L);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": SystemProperties.getLong hook error: "
                                + t);
                        }
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + name + " failed: "
                + t.getMessage());
            return false;
        }
    }
}