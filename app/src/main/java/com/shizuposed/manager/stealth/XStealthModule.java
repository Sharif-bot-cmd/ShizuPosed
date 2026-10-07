package com.shizuposed.manager.stealth;

import com.shizuposed.manager.core.ClassLoadingBridge;
import com.shizuposed.manager.core.backends.ApplicationThreadBackend;
import com.shizuposed.manager.stealth.checks.AccessibilityCheck;
import com.shizuposed.manager.stealth.checks.AdbCheck;
import com.shizuposed.manager.stealth.checks.ApiProtectionCheck;
import com.shizuposed.manager.stealth.checks.BuildCheck;
import com.shizuposed.manager.stealth.checks.CachedValueCheck;
import com.shizuposed.manager.stealth.checks.DevOptionsCheck;
import com.shizuposed.manager.stealth.checks.PackageCheck;
import com.shizuposed.manager.stealth.checks.PropertyScrubCheck;
import com.shizuposed.manager.stealth.checks.RunningProcessCheck;
import com.shizuposed.manager.stealth.checks.SettingsFileCheck;
import com.shizuposed.manager.stealth.checks.SocketCheck;
import com.shizuposed.manager.stealth.checks.SystemPropertiesCheck;

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
 *      skips this process. An empty scope means "all apps".
 *   3. Per-check toggles. Each check installs only if its toggle is
 *      on.
 *
 * XStealth Next is a separate opt-in that activates the aggressive
 * native engine on top of the primary one. It's controlled by its
 * own toggle and has no effect when the master toggle is off.
 *
 * CHECK LAYERS
 * ------------
 *   Settings-based
 *     DevOptionsCheck       — Settings.Global + ContentResolver.query
 *                             + Binder IContentProvider (9.0)
 *     AdbCheck              — Settings.Secure + Settings.Global + query
 *                             + Binder IContentProvider (9.0)
 *     CachedValueCheck      — SharedPreferences reads (opt-in)
 *
 *   Accessibility
 *     AccessibilityCheck    — AccessibilityManager list enumeration
 *                             + Binder IAccessibilityManager (9.0)
 *
 *   Subprocess
 *     SettingsFileCheck     — Runtime.exec + ProcessBuilder
 *
 *   Socket
 *     SocketCheck           — LocalSocket (off by default)
 *
 *   Package / process
 *     PackageCheck          — PackageManager lookups + Binder
 *                             IPackageManager + Binder DUMP (9.0)
 *     RunningProcessCheck   — ActivityManager lookups
 *
 *   Reflection / API
 *     ApiProtectionCheck    — reflection + fingerprint baselines
 *
 *   System / properties
 *     SystemPropertiesCheck — android.os.SystemProperties reads
 *     BuildCheck            — android.os.Build fields
 *     PropertyScrubCheck    — shizuposed.* system properties
 *
 *   Native
 *     XStealthNative        — libc interposition (file reads)
 *     XStealthNativeNext    — runtime syscall stub scanner (9.0)
 *
 * BRIDGES
 * -------
 *   ClassLoadingBridge       — class-load observation + component
 *                              pre-load + dynamic loader discovery
 *   ApplicationThreadBackend — binder-level lifecycle observation
 *
 * Both are gated on apiProtection. They observe; they don't block
 * or rewrite. XStealth's hiding is at the API layer, not at the
 * binder layer — blocking a binder call would be a behavior the
 * target notices, which is the opposite of what XStealth wants.
 */
public class XStealthModule implements IXposedHookLoadPackage {

    public static final String PACKAGE = "com.shizuposed.manager.xstealth";
    public static final String ENTRY  = "com.shizuposed.manager.stealth.XStealthModule";

    private static final String TAG = "XStealth";

    private static volatile boolean sActive = false;
    private static volatile boolean sBridgesRegistered = false;

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

        if (!config.shouldApplyTo(lpparam.packageName)) {
            XposedBridge.log(TAG + ": out of scope, skipping "
                + lpparam.packageName
                + " (scope size=" + config.scope.size() + ")");
            return;
        }

        XposedBridge.log(TAG + ": activating for " + lpparam.packageName
            + (config.nextEnabled ? " (Next)" : "")
            + (config.isScopeAllApps() ? " (all apps)" : ""));

        // ─── Settings-based checks ───────────────────────────────

        if (config.hideDevOptions) {
            try { DevOptionsCheck.install(lpparam, config); }
            catch (Throwable t) { XposedBridge.log(TAG + ": DevOptions hook failed: " + t); }
        }

        if (config.hideAdb) {
            try { AdbCheck.install(lpparam, config); }
            catch (Throwable t) { XposedBridge.log(TAG + ": ADB hook failed: " + t); }
        }

        // ─── Accessibility service enumeration ───────────────────
        //
        // Anti-fraud SDKs commonly enumerate accessibility services
        // to detect automation, screen readers, and instrumentation
        // tools. The check filters the list by package name. It
        // installs whenever the master is on; there's no per-check
        // toggle for it yet.
        try { AccessibilityCheck.install(lpparam, config); }
        catch (Throwable t) {
            XposedBridge.log(TAG + ": AccessibilityCheck failed: " + t);
        }

        // ─── Cached value check (opt-in, allow-list) ─────────────
        //
        // Installed after the live-read checks so the canonical
        // hidden-key list (DevOptionsCheck.hidesKey) is in place
        // before any cached read is sanitized.

        if (config.hideCachedDetectionValues) {
            try { CachedValueCheck.install(lpparam, config); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": CachedValueCheck failed: " + t);
            }
        }

        // ─── Subprocess reads ────────────────────────────────────

        if (config.hideSettingsFileReads) {
            try { SettingsFileCheck.install(lpparam); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": SettingsFileCheck failed: " + t);
            }
        }

        // ─── Socket shim ─────────────────────────────────────────

        if (config.hideSocketDaemons) {
            try { SocketCheck.install(lpparam, config); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": SocketCheck failed: " + t);
            }
        }

        // ─── Package / process ───────────────────────────────────
        //
        // PackageCheck now installs whenever the master is on,
        // because it does more than package hiding: it hooks the
        // IPackageManager descriptor for the Binder path, handles
        // DUMP_TRANSACTION for dumpsys package, and installs the
        // ProxyTransactListener for diagnostics. The per-target
        // filter (hideShizukuPackage / hideShizuPosedPackage) is
        // consulted inside the class.

        try { PackageCheck.install(lpparam, config); }
        catch (Throwable t) {
            XposedBridge.log(TAG + ": PackageCheck failed: " + t);
        }

        if (config.hideRunningProcesses) {
            try { RunningProcessCheck.install(lpparam); }
            catch (Throwable t) { XposedBridge.log(TAG + ": Process hook failed: " + t); }
        }

        if (config.apiProtection) {
            try { ApiProtectionCheck.install(lpparam); }
            catch (Throwable t) { XposedBridge.log(TAG + ": ApiProtectionCheck failed: " + t); }
        }

        // ─── SystemProperties, Build, property scrub ─────────────

        if (config.hideSystemProperties) {
            try { SystemPropertiesCheck.install(lpparam); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": SystemPropertiesCheck failed: " + t);
            }
        }

        if (config.hideBuildFields) {
            try { BuildCheck.install(lpparam); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": BuildCheck failed: " + t);
            }
        }

        if (config.scrubShizuPosedProperties) {
            try { PropertyScrubCheck.install(lpparam); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": PropertyScrubCheck failed: " + t);
            }
        }

        if (config.apiProtection && !sBridgesRegistered) {
            sBridgesRegistered = true;
            registerBridgeListeners();
        }

        // ─── Native layer ────────────────────────────────────────

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

    // ═════════════════════════════════════════════════════════════
    // BRIDGE LISTENER REGISTRATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Register XStealth's own listeners with the class-loading and
     * ApplicationThread bridges.
     *
     * These are observational only. XStealth's job is to hide the
     * framework's presence, not to interfere with what the target
     * loads or how AMS drives it. A listener that blocked a class
     * load or a binder call would create a behavior the target
     * could detect, which is exactly what XStealth exists to
     * prevent.
     *
     * What the listeners actually do:
     *
     *   • ClassLoadListener: if the target loads a class in the
     *     Xposed shim namespace, log it. ApiProtectionCheck already
     *     refuses Class.forName for that namespace; this is the
     *     loadClass path, which ApiProtectionCheck does not hook.
     *     Observing here tells the user when a target is trying to
     *     probe for Xposed by reflection.
     *
     *   • LoaderCreatedListener: log when the target constructs a
     *     dynamic loader at runtime. Diagnostic only.
     *
     *   • ApplicationThreadBackend.Listener: observe lifecycle
     *     binder calls for diagnostics. Always returns true (never
     *     blocks), because blocking would change the target's
     *     behavior.
     */
    private static void registerBridgeListeners() {
        // ── ClassLoadListener ────────────────────────────────────
        try {
            ClassLoadingBridge.registerClassLoadListener(
                (className, loader) -> {
                    if (className == null) return;
                    if (className.startsWith("de.robv.android.xposed.")) {
                        XposedBridge.log(TAG
                            + ": target is loading Xposed shim class: "
                            + className);
                    }
                });
            XposedBridge.log(TAG + ": ClassLoadListener registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ClassLoadListener registration failed: "
                + t.getMessage());
        }

        // ── LoaderCreatedListener ────────────────────────────────
        try {
            ClassLoadingBridge.registerLoaderListener(
                loader -> {
                    XposedBridge.log(TAG
                        + ": dynamic loader created: "
                        + loader.getClass().getName());
                });
            XposedBridge.log(TAG + ": LoaderCreatedListener registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LoaderCreatedListener registration failed: "
                + t.getMessage());
        }

        // ── ApplicationThread BinderListener ─────────────────────
        try {
            ApplicationThreadBackend.registerListener(
                (methodName, args) -> {
                    // Observational only. Return true to let the
                    // call through. A false return would block the
                    // lifecycle event, which the target would
                    // notice (the service never starts, the
                    // activity never launches) and could use as a
                    // detection signal.
                    if (methodName != null
                            && methodName.startsWith("schedule")) {
                        // Debug-level log. Kept behind a check so
                        // verbose logging stays opt-in via
                        // XStealthConfig if you later add a
                        // "bridgeVerbose" toggle.
                        // XposedBridge.log(TAG + ": binder " + methodName);
                    }
                    return true;
                });
            XposedBridge.log(TAG + ": ApplicationThread listener registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ApplicationThread listener registration failed: "
                + t.getMessage());
        }
    }
}