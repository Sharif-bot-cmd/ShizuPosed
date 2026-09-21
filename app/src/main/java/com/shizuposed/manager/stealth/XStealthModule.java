package com.shizuposed.manager.stealth;

import com.shizuposed.manager.stealth.checks.AdbCheck;
import com.shizuposed.manager.stealth.checks.ApiProtectionCheck;
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
 * Three gates, checked in order:
 *
 *   1. Master toggle. If off, nothing runs at all.
 *   2. Scope. If the target package is not in the scope, the module
 *      skips this process. An empty scope means "all apps" — the
 *      module applies everywhere.
 *   3. Per-check toggles. Each check installs only if its toggle is
 *      on.
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

        // ─── Scope gate ──────────────────────────────────────────
        // An empty scope means "all apps" and this passes for every
        // target. A non-empty scope is a whitelist; anything not in
        // it is skipped.
        if (!config.shouldApplyTo(lpparam.packageName)) {
            XposedBridge.log(TAG + ": out of scope, skipping "
                + lpparam.packageName
                + " (scope size=" + config.scope.size() + ")");
            return;
        }

        XposedBridge.log(TAG + ": activating for " + lpparam.packageName
            + (config.nextEnabled ? " (Next)" : "")
            + (config.isScopeAllApps() ? " (all apps)" : ""));

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