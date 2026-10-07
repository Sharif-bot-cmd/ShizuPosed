package com.shizuposed.manager.stealth.checks;

import android.app.ActivityManager;
import android.os.Build;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RunningProcessCheck
 *
 * Hides Shizuku, ShizuPosed, and the shell's app_process helper
 * from ActivityManager process and service listings. Detectors use
 * these APIs to check whether a privileged helper is running on
 * the device.
 *
 * Hooks installed:
 *   • getRunningAppProcesses   → filters by name, uid, and pkgList
 *   • getRunningServices       → filters (API 25 and older only)
 *   • getRunningTasks          → filters (deprecated but callable)
 *   • getHistoricalProcessExitReasons → filters (Android 11+)
 *   • getProcessesInErrorState → filters (older API)
 *   • getMyMemoryState         → filters pkgList in the returned struct
 *
 * FILTERING SIGNALS
 * -----------------
 * A process entry can be identified as ShizuPosed-related through
 * three independent signals:
 *
 *   1. processName matches a hidden prefix.
 *   2. uid is 2000 (shell UID) — the UID Shizuku runs under, and
 *      the UID that app_process is spawned as before switching to
 *      the target's UID.
 *   3. pkgList contains a hidden package name — some processes
 *      serve other packages, so the package list can leak the
 *      presence of Shizuku even when the process name doesn't.
 *
 * Filtering on all three closes the case where a detector reads
 * the list and checks for any of these signals rather than only
 * the name.
 */
public final class RunningProcessCheck {

    private static final String TAG = "XStealth";

    private static final String SHIZUKU_PKG    = "moe.shizuku.privileged.api";
    private static final String SHIZUPOSED_PKG = "com.shizuposed.manager";

    /** UID of the shell user. Shizuku runs under this UID. */
    private static final int SHELL_UID = 2000;

    private RunningProcessCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> amClass = android.app.ActivityManager.class;

        hookRunningAppProcesses(amClass);
        hookRunningServices(amClass);
        hookRunningTasks(amClass);
        hookHistoricalProcessExitReasons(amClass);
        hookProcessesInErrorState(amClass);
        hookMyMemoryState(amClass);

        XposedBridge.log(TAG + ": RunningProcessCheck installed");
        XStealthRegistry.record("RunningProcessCheck");
    }

    // ═════════════════════════════════════════════════════════════
    // getRunningAppProcesses
    // ═════════════════════════════════════════════════════════════

    private static void hookRunningAppProcesses(Class<?> amClass) {
        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getRunningAppProcesses",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterProcesses((List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getRunningAppProcesses hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // getRunningServices
    // ═════════════════════════════════════════════════════════════

    private static void hookRunningServices(Class<?> amClass) {
        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getRunningServices", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterServices((List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            // On API 26+ this method is not part of the public API
            // for most callers. Hooking it fails silently, which is
            // fine — it just means the hook isn't installed.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // getRunningTasks
    // ═════════════════════════════════════════════════════════════

    private static void hookRunningTasks(Class<?> amClass) {
        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getRunningTasks", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterTasks((List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            // Deprecated. May not be present on newer ROMs.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // getHistoricalProcessExitReasons (Android 11+)
    //
    // Returns a list of recently-exited processes. A detector that
    // saw Shizuku's process exit (or that killed it) can look at
    // this list and find the exit reason. The list also reveals
    // which processes were recently running, which can be used to
    // infer that Shizuku was present.
    // ═════════════════════════════════════════════════════════════

    private static void hookHistoricalProcessExitReasons(Class<?> amClass) {
        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getHistoricalProcessExitReasons",
                String.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterExitReasons(
                            (List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            // Android 11+ only. Silent skip on older versions.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // getProcessesInErrorState (older API)
    // ═════════════════════════════════════════════════════════════

    private static void hookProcessesInErrorState(Class<?> amClass) {
        try {
            XposedHelpers.findAndHookMethod(
                amClass, "getProcessesInErrorState",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        p.setResult(filterProcesses((List<?>) p.getResult()));
                    }
                });
        } catch (Throwable t) {
            // Not present on all ROMs.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // getMyMemoryState
    //
    // Returns a single RunningAppProcessInfo for the calling
    // process. It carries the pkgList, which can contain hidden
    // packages if the current process serves them.
    // ═════════════════════════════════════════════════════════════

    private static void hookMyMemoryState(Class<?> amClass) {
        try {
            Class<?> infoClass = ActivityManager.RunningAppProcessInfo.class;
            XposedHelpers.findAndHookMethod(
                amClass, "getMyMemoryState", infoClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object result = p.getResult();
                            if (result instanceof
                                    ActivityManager.RunningAppProcessInfo) {
                                scrubPkgList(
                                    (ActivityManager.RunningAppProcessInfo)
                                        result);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable t) {
            // Some ROMs don't expose this method.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FILTERS
    // ═════════════════════════════════════════════════════════════

    private static List<ActivityManager.RunningAppProcessInfo> filterProcesses(
            List<?> in) {
        if (in == null) return null;
        List<ActivityManager.RunningAppProcessInfo> out =
            new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ActivityManager.RunningAppProcessInfo)) continue;
            ActivityManager.RunningAppProcessInfo info =
                (ActivityManager.RunningAppProcessInfo) o;
            if (shouldHideProcess(info)) continue;
            scrubPkgList(info);
            out.add(info);
        }
        return out;
    }

    private static List<ActivityManager.RunningServiceInfo> filterServices(
            List<?> in) {
        if (in == null) return null;
        List<ActivityManager.RunningServiceInfo> out =
            new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ActivityManager.RunningServiceInfo)) continue;
            ActivityManager.RunningServiceInfo info =
                (ActivityManager.RunningServiceInfo) o;

            // Name check.
            if (info.service != null) {
                String pkg = info.service.getPackageName();
                if (shouldHideName(pkg)) continue;
            }

            // UID check — RunningServiceInfo carries a uid field.
            try {
                if (info.uid == SHELL_UID) continue;
            } catch (Throwable ignored) {}

            // Process-name check.
            if (info.process != null && shouldHideName(info.process)) continue;

            out.add(info);
        }
        return out;
    }

    private static List<ActivityManager.RunningTaskInfo> filterTasks(
            List<?> in) {
        if (in == null) return null;
        List<ActivityManager.RunningTaskInfo> out =
            new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ActivityManager.RunningTaskInfo)) continue;
            ActivityManager.RunningTaskInfo info =
                (ActivityManager.RunningTaskInfo) o;

            String pkg = null;
            if (info.topActivity != null) {
                pkg = info.topActivity.getPackageName();
            } else if (info.baseActivity != null) {
                pkg = info.baseActivity.getPackageName();
            }
            if (shouldHideName(pkg)) continue;
            out.add(info);
        }
        return out;
    }

    private static List<ActivityManager.ProcessErrorStateInfo> filterExitReasons(
            List<?> in) {
        if (in == null) return null;
        List<ActivityManager.ProcessErrorStateInfo> out =
            new ArrayList<>(in.size());
        for (Object o : in) {
            if (!(o instanceof ActivityManager.ProcessErrorStateInfo)) continue;
            ActivityManager.ProcessErrorStateInfo info =
                (ActivityManager.ProcessErrorStateInfo) o;
            if (info.processName != null
                    && shouldHideName(info.processName)) continue;
            if (info.uid == SHELL_UID) continue;
            out.add(info);
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════
    // PROCESS IDENTIFICATION
    // ═════════════════════════════════════════════════════════════

    private static boolean shouldHideProcess(
            ActivityManager.RunningAppProcessInfo info) {
        if (info == null) return false;

        // 1. Name check.
        if (shouldHideName(info.processName)) return true;

        // 2. UID check.
        try {
            if (info.uid == SHELL_UID) return true;
        } catch (Throwable ignored) {}

        // 3. pkgList check.
        try {
            if (info.pkgList != null) {
                for (String pkg : info.pkgList) {
                    if (shouldHideName(pkg)) return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private static boolean shouldHideName(String name) {
        if (name == null) return false;
        if (name.startsWith(SHIZUKU_PKG))    return true;
        if (name.startsWith(SHIZUPOSED_PKG)) return true;
        // Note: "app_process" alone is too broad — many legitimate
        // Java processes are named app_process. Only hide it when
        // it's the shell's helper process, which the UID check
        // above catches. So we don't match on "app_process" here.
        return false;
    }

    /**
     * Remove hidden packages from a RunningAppProcessInfo's pkgList
     * field. Modifies the struct in place — XStealth doesn't return
     * copies, since callers may hold references to the original.
     */
    private static void scrubPkgList(
            ActivityManager.RunningAppProcessInfo info) {
        if (info == null || info.pkgList == null) return;
        try {
            String[] original = info.pkgList;
            int kept = 0;
            for (String pkg : original) {
                if (pkg != null && !shouldHideName(pkg)) kept++;
            }
            if (kept == original.length) return;
            String[] filtered = new String[kept];
            int i = 0;
            for (String pkg : original) {
                if (pkg != null && !shouldHideName(pkg)) filtered[i++] = pkg;
            }
            info.pkgList = filtered;
        } catch (Throwable ignored) {}
    }
}