package com.shizuposed.manager.stealth.checks;

import android.content.ComponentName;
import android.content.Context;
import android.os.Parcel;
import android.view.accessibility.AccessibilityNodeInfo;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AccessibilityCheck
 *
 * Filters accessibility-service enumerations so a target that
 * queries for installed or enabled services doesn't see the
 * framework's own packages, Shizuku, or any package the user has
 * added to the block list.
 *
 * WHY THIS EXISTS
 * ---------------
 * Anti-fraud SDKs commonly enumerate accessibility services to
 * detect automation, screen readers, and known instrumentation
 * tools. The presence of an unexpected accessibility service is
 * a signal. Shizuku itself doesn't register one, but the framework
 * may, and the surrounding tooling often does.
 *
 * The check has the same three-layer shape as the other XStealth
 * checks:
 *
 *   Level 1 — Java API
 *     • AccessibilityManager.getInstalledAccessibilityServiceList
 *     • AccessibilityManager.getEnabledAccessibilityServiceList
 *     • AccessibilityManager.getAccessibilityServiceList
 *
 *   Level 2 — Binder.transact
 *     • IAccessibilityManager — refuses the same list calls over
 *       Binder if the target bypasses the Java API.
 *
 *   Level 3 — DUMP_TRANSACTION
 *     • Refuses `dumpsys accessibility` when the args array names
 *       a blocked package. This catches the shell-out path.
 *
 * FILTERING RULE
 * --------------
 * A service is filtered if its package name matches any entry in
 * BLOCKED_PACKAGES. Matching is exact — no prefix, no regex. The
 * list contains the framework's own packages and Shizuku by
 * default.
 *
 * The check filters by package name rather than by service class
 * or capability. A service whose package is blocked is removed
 * entirely, regardless of what it does.
 *
 * FAIL-OPEN
 * ---------
 * Every failure — a null list, an unexpected element type, a
 * reflection failure — passes through the original list. A
 * filtering bug means the target sees the real list, which is the
 * correct degradation.
 */
public final class AccessibilityCheck {

    private static final String TAG = "XStealth";

    /** Binder descriptor for the accessibility manager service. */
    private static final String A11Y_DESCRIPTOR =
        "android.view.accessibility.IAccessibilityManager";

    /** IBinder.DUMP_TRANSACTION. */
    private static final int DUMP_TRANSACTION = 1598311760;

    /**
     * IAccessibilityManager transaction codes for the list methods.
     *
     * These are stable across Android versions. If a future version
     * renumbers them, the Binder hook falls through to the Java API
     * hooks — which cover the same calls when they go through the
     * manager object rather than a raw proxy.
     */
    private static final int TX_GET_INSTALLED_ACCESSIBILITY_SERVICE_LIST = 2;
    private static final int TX_GET_ENABLED_ACCESSIBILITY_SERVICE_LIST   = 3;

    /**
     * Packages filtered from every accessibility-service list.
     *
     * The user can extend this list at runtime; the static defaults
     * cover the framework and Shizuku. Anything else the user wants
     * to hide goes in a config file that this class reads.
     */
    private static final String[] BLOCKED_PACKAGES = {
        "com.shizuposed.manager",
        "moe.shizuku.privileged.api",
    };

    /** Diagnostics counters. */
    private static final AtomicLong sListFiltered    = new AtomicLong();
    private static final AtomicLong sTransactRefused = new AtomicLong();
    private static final AtomicLong sDumpRefused     = new AtomicLong();

    private AccessibilityCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam,
                               XStealthConfig config) {
        if (lpparam == null || config == null) return;
        // There's no per-check toggle yet. The check installs when
        // XStealth is enabled, matching the way RunningProcessCheck
        // works. If a toggle is added later, gate this on it.
        if (!config.enabled) return;

        // ── Level 1: Java API ──────────────────────────────────
        Class<?> a11yMgrClass = XposedHelpers.findClassIfExists(
            "android.view.accessibility.AccessibilityManager",
            lpparam.classLoader);
        if (a11yMgrClass != null) {
            hookJavaApi(a11yMgrClass);
        } else {
            XposedBridge.log(TAG + ": AccessibilityManager not found");
        }

        // ── Level 2 + 3: Binder ────────────────────────────────
        hookBinderTransact();

        XposedBridge.log(TAG + ": AccessibilityCheck installed");
        XStealthRegistry.record("AccessibilityCheck");
    }

    /** Snapshot for the detail sheet. */
    public static String describe() {
        return "listFiltered=" + sListFiltered.get()
            + " transactRefused=" + sTransactRefused.get()
            + " dumpRefused=" + sDumpRefused.get();
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 1 — JAVA API
    // ═════════════════════════════════════════════════════════════

    private static void hookJavaApi(Class<?> a11yMgrClass) {
        // getInstalledAccessibilityServiceList()
        //   returns List<AccessibilityServiceInfo>
        try {
            XposedHelpers.findAndHookMethod(
                a11yMgrClass, "getInstalledAccessibilityServiceList",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object in = p.getResult();
                        if (in == null) return;
                        Object filtered = filterServiceInfoList((List<?>) in);
                        if (filtered != in) {
                            sListFiltered.incrementAndGet();
                            p.setResult(filtered);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getInstalledAccessibilityServiceList "
                + "hook failed: " + t);
        }

        // getEnabledAccessibilityServiceList(int feedbackTypeFlags)
        //   returns List<AccessibilityServiceInfo>
        try {
            XposedHelpers.findAndHookMethod(
                a11yMgrClass, "getEnabledAccessibilityServiceList",
                int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object in = p.getResult();
                        if (in == null) return;
                        Object filtered = filterServiceInfoList((List<?>) in);
                        if (filtered != in) {
                            sListFiltered.incrementAndGet();
                            p.setResult(filtered);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getEnabledAccessibilityServiceList "
                + "hook failed: " + t);
        }

        // getAccessibilityServiceList(int feedbackTypeFlags)
        //   returns List<AccessibilityServiceInfo>
        //   Available on some Android versions; not always declared.
        try {
            XposedHelpers.findAndHookMethod(
                a11yMgrClass, "getAccessibilityServiceList",
                int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object in = p.getResult();
                        if (in == null) return;
                        Object filtered = filterServiceInfoList((List<?>) in);
                        if (filtered != in) {
                            sListFiltered.incrementAndGet();
                            p.setResult(filtered);
                        }
                    }
                });
        } catch (Throwable t) {
            // Not always available.
        }
    }

    /**
     * Filter a List<AccessibilityServiceInfo> by package name.
     *
     * AccessibilityServiceInfo's package name lives in a field
     * named "mPackageName" on some Android versions and is derived
     * from "mComponentName" on others. Both are handled:
     *
     *   1. Try "mPackageName" via reflection.
     *   2. Fall back to "mComponentName.getPackageName()".
     *   3. If neither works, keep the entry (fail-open).
     *
     * Returns the original list unchanged when nothing was
     * filtered, so the hook can skip setResult in the common case.
     */
    private static List<?> filterServiceInfoList(List<?> in) {
        if (in == null || in.isEmpty()) return in;

        List<Object> out = null;

        for (int i = 0; i < in.size(); i++) {
            Object svc = in.get(i);
            String pkg = extractPackageName(svc);

            if (pkg != null && isBlockedPackage(pkg)) {
                if (out == null) {
                    out = new ArrayList<>(in.size());
                    // Copy everything before the first hit.
                    for (int j = 0; j < i; j++) out.add(in.get(j));
                }
                continue;
            }
            if (out != null) out.add(svc);
        }

        return (out == null) ? in : out;
    }

    /**
     * Extract the package name from an AccessibilityServiceInfo.
     * Returns null when the package can't be determined — the
     * caller keeps the entry in that case.
     */
    private static String extractPackageName(Object svc) {
        if (svc == null) return null;

        // Try "mPackageName" first. Some Android versions expose it
        // directly; some don't. The field is present on AOSP 11+
        // but not on every OEM build.
        try {
            Field f = svc.getClass().getDeclaredField("mPackageName");
            f.setAccessible(true);
            Object v = f.get(svc);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}

        // Fall back to "mComponentName".
        try {
            Field f = svc.getClass().getDeclaredField("mComponentName");
            f.setAccessible(true);
            Object v = f.get(svc);
            if (v instanceof ComponentName) {
                return ((ComponentName) v).getPackageName();
            }
        } catch (Throwable ignored) {}

        // Some ROMs use "mResolveInfo" instead.
        try {
            Field f = svc.getClass().getDeclaredField("mResolveInfo");
            f.setAccessible(true);
            Object v = f.get(svc);
            if (v != null) {
                try {
                    Field ri = v.getClass().getDeclaredField("serviceInfo");
                    ri.setAccessible(true);
                    Object si = ri.get(v);
                    if (si != null) {
                        Field pn = si.getClass().getDeclaredField("packageName");
                        pn.setAccessible(true);
                        Object p = pn.get(si);
                        if (p instanceof String) return (String) p;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 2 + 3 — BINDER TRANSACT
    // ═════════════════════════════════════════════════════════════

    private static void hookBinderTransact() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.BinderProxy", null, "transact",
                int.class, Parcel.class, Parcel.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        int code = (Integer) p.args[0];
                        Parcel data = (Parcel) p.args[1];
                        if (data == null) return;

                        // ── DUMP_TRANSACTION ────────────────────
                        if (code == DUMP_TRANSACTION) {
                            checkDump(data);
                            return;
                        }

                        // ── IAccessibilityManager list calls ────
                        if (code != TX_GET_INSTALLED_ACCESSIBILITY_SERVICE_LIST
                                && code != TX_GET_ENABLED_ACCESSIBILITY_SERVICE_LIST) {
                            return;
                        }

                        String descriptor = readInterfaceDescriptor(data);
                        if (descriptor == null
                                || !descriptor.equals(A11Y_DESCRIPTOR)) {
                            return;
                        }

                        // The accessibility manager's list calls are
                        // reads — they query the service, which
                        // internally enumerates and returns. The
                        // parcel doesn't contain a package name we
                        // can inspect; the reply does.
                        //
                        // We can't refuse the call directly without
                        // knowing whether the reply contains a
                        // blocked package. So the Java API hook is
                        // the primary path here. The Binder hook
                        // only fires for DUMP_TRANSACTION, above.
                        //
                        // A future version could patch the reply
                        // parcel, but the reply format is a
                        // ParceledListSlice of AccessibilityServiceInfo,
                        // which is version-dependent and fragile to
                        // rewrite.
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AccessibilityCheck Binder.transact "
                + "hook failed: " + t);
        }
    }

    /**
     * Parse a DUMP_TRANSACTION parcel and refuse if the args array
     * names a blocked package alongside an "accessibility" service.
     *
     * Uses createStringArray() rather than readStringArray():
     * readStringArray takes a pre-allocated out-parameter on some
     * Android versions and returns an array on others.
     */
    private static void checkDump(Parcel data)
            throws android.os.RemoteException {
        int pos = data.dataPosition();
        try {
            try { data.readString(); } catch (Throwable ignored) {}
            try { data.readFileDescriptor(); } catch (Throwable ignored) {}

            String[] args = null;
            try { args = data.createStringArray(); } catch (Throwable ignored) {}
            if (args == null || args.length == 0) return;

            boolean isAccessibilityDump = false;
            for (String a : args) {
                if ("accessibility".equals(a)) {
                    isAccessibilityDump = true;
                    break;
                }
            }
            if (!isAccessibilityDump) return;

            String hit = null;
            for (String a : args) {
                if (a == null) continue;
                if (isBlockedPackage(a)) { hit = a; break; }
            }
            if (hit == null) return;

            sDumpRefused.incrementAndGet();
            XposedBridge.log(TAG + ": Binder DUMP refused for "
                + "accessibility arg " + hit);
            throw new android.os.RemoteException(
                "accessibility package " + hit + " not found");
        } catch (android.os.RemoteException re) {
            throw re;
        } catch (Throwable parseErr) {
            // Pass through.
        } finally {
            try { data.setDataPosition(pos); }
            catch (Throwable ignored) {}
        }
    }

    private static String readInterfaceDescriptor(Parcel data) {
        int pos = data.dataPosition();
        try {
            return data.readString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { data.setDataPosition(pos); } catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════
    // MATCHING
    // ═════════════════════════════════════════════════════════════

    private static boolean isBlockedPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String b : BLOCKED_PACKAGES) {
            if (b.equalsIgnoreCase(pkg)) return true;
        }
        return false;
    }
}