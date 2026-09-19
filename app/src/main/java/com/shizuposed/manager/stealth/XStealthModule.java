package com.shizuposed.manager.stealth;

import com.shizuposed.manager.stealth.checks.AdbCheck;import com.shizuposed.manager.stealth.checks.ApiProtectionCheck;
import com.shizuposed.manager.stealth.checks.DevOptionsCheck;
import com.shizuposed.manager.stealth.checks.PackageCheck;
import com.shizuposed.manager.stealth.checks.RunningProcessCheck;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XStealthModule
 *
 * Built-in Xposed module that hides ShizuPosed's presence from a
 * target app. Loaded by the shell-side XposedHook exactly like any
 * other module.
 *
 * ACTIVATION
 * ----------
 * The master toggle is the only gate. When it's off, nothing runs
 * — the check flags are read but ignored because the module
 * returns at the top of handleLoadPackage. When it's on, every
 * enabled check installs itself, and the native engine loads if
 * /proc hiding is on.
 *
 * XStealth Next is a separate opt-in that activates the aggressive
 * native engine on top of the primary one. It's controlled by its
 * own toggle and has no effect when the master toggle is off.
 */
public class XStealthModule implements IXposedHookLoadPackage {

    public static final String PACKAGE = "com.shizuposed.manager.xstealth";
    public static final String ENTRY  = "com.shizuposed.manager.stealth.XStealthModule";

    private static final String TAG = "XStealth";

    private static volatile boolean sActive = false;

    public static boolean isActive() { return sActive; }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;
        if (lpparam.classLoader == null) return;

        XStealthConfig config = XStealthConfig.load();
        if (config == null || !config.enabled) {
            XposedBridge.log(TAG + ": disabled, skipping " + lpparam.packageName);
            return;
        }

        XposedBridge.log(TAG + ": activating for " + lpparam.packageName
            + (config.nextEnabled ? " (Next)" : ""));

        if (config.hideDevOptions) {
            try { DevOptionsCheck.install(lpparam); }
            catch (Throwable t) { XposedBridge.log(TAG + ": DevOptions hook failed: " + t); }
        }

        if (config.hideAdb) {
            try { AdbCheck.install(lpparam); }
            catch (Throwable t) { XposedBridge.log(TAG + ": ADB hook failed: " + t); }
        }

        if (config.hideShizukuPackage || config.hideShizuPosedPackage) {
            try { PackageCheck.install(lpparam, config); }
            catch (Throwable t) { XposedBridge.log(TAG + ": Package hook failed: " + t); }
        }

        if (config.hideRunningProcesses) {
            try { RunningProcessCheck.install(lpparam); }
            catch (Throwable t) { XposedBridge.log(TAG + ": Process hook failed: " + t); }
        }

        if (config.apiProtection) {
            try { ApiProtectionCheck.install(lpparam); }
            catch (Throwable t) { XposedBridge.log(TAG + ": ApiProtectionCheck failed: " + t); }
        }

        // ─── Primary native engine ───────────────────────────────
        // The native engine covers /proc reads and libc file access.
        // It only loads when hideProcFs is on. When it's off, there
        // is no reason to pay the load cost or the per-call cost of
        // the interposition.
        if (config.hideProcFs) {
            try {
                String libDir = System.getProperty("shizuposed.shell.libs",
                    "/data/user/0/com.android.shell/files/libs");
                if (XStealthNative.load(libDir)) {
                    XStealthNative.setActive(true);
                    XposedBridge.log(TAG + ": native hiding active");
                } else {
                    XposedBridge.log(TAG + ": native hiding unavailable");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": native hiding failed: " + t.getMessage());
            }
        }

        // ─── Next engine (opt-in) ────────────────────────────────
        // Patches libc syscall stubs, which is stronger than symbol
        // interposition but also more fragile. Only activates when
        // the user has turned Next on. If it fails to install any
        // patches it deactivates itself and the primary engine
        // continues alone.
        if (config.nextEnabled) {
            try {
                String libDir = System.getProperty("shizuposed.shell.libs",
                    "/data/user/0/com.android.shell/files/libs");
                if (XStealthNativeNext.load(libDir)) {
                    XStealthNativeNext.setActive(true);
                    if (XStealthNativeNext.isActive()) {
                        XposedBridge.log(TAG + ": Next hiding active");
                    } else {
                        XposedBridge.log(TAG + ": Next hiding failed to install");
                    }
                } else {
                    XposedBridge.log(TAG + ": Next library unavailable");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Next hiding failed: " + t.getMessage());
            }
        }

        if (XStealthRegistry.count() > 0) {
            sActive = true;
        }

        XposedBridge.log(TAG + ": active in " + lpparam.packageName
            + " (" + XStealthRegistry.count() + " checks)");
    }
}