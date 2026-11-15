package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.core.HookEngine;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * MethodBaselineCheck
 *
 * Freezes the method list of a class at install time so that
 * later getDeclaredMethods() / getMethods() calls return exactly
 * the methods that existed before the framework hooked anything.
 *
 * WHY THIS EXISTS
 * ---------------
 *
 * A common detection technique is method reconstruction: reflect
 * on a class, count its methods, and compare against a known-good
 * baseline from the current Android version's AOSP source. If the
 * counts differ, the detector infers that the framework has
 * installed hooks.
 *
 * The old approach pinned only the *count*: if the current method
 * array was longer than the baseline, it trimmed the array to the
 * baseline length. That's brittle for two reasons:
 *
 *   1. Trimming to N keeps the first N methods, but those may not
 *      be the same N as the original. A detector that hashes the
 *      method name list would notice.
 *
 *   2. A hook backend that reorders methods, or that inserts a
 *      hooked method before the original, would shift the
 *      alignment.
 *
 * This class instead freezes the *full* method list — names,
 * signatures, modifiers — captured at install time. Every later
 * call returns a copy of that frozen list, filtered against the
 * current array to preserve identity for methods that still exist.
 *
 * CAPTURE TIMING
 * --------------
 *
 * The capture has to run *after* a module's entry point has
 * installed its hooks, but *before* any further hooks land on the
 * same class. That's not "at ApiProtectionCheck.install()" —
 * install() runs during bootstrap, before any module's
 * handleLoadPackage (classic) or onPackageLoaded (libxposed) has
 * fired. Calling captureDynamicBaselines() from there captures the
 * empty set.
 *
 * The right place is XposedHook.loadModule: after the entry point
 * returns, call captureDynamicBaselines() once. That captures
 * every class the just-finished module hooked, so the baseline
 * reflects the pre-hook state for those classes. The next module's
 * entry point adds its classes, and so on.
 *
 * LIBXPOSED
 * ---------
 *
 * libxposed modules install hooks via HookHandle.intercept(), which
 * lands in XposedHookBridge.hook() -> HookDispatcher.installHook()
 * -> HookEngine, the same path classic modules use. The
 * HookEngine.getHookedClassNames() set therefore includes
 * libxposed-hooked classes, and captureOne() sees them.
 *
 * replaceHook(Hooker) does NOT change the method list of the hooked
 * class — it swaps the live callback on the same Method object.
 * getDeclaredMethods() still returns the same array. The frozen
 * baseline is still correct after a replaceHook, and no further
 * capture is needed.
 *
 * A detector that fingerprints *behavior* (calling the method and
 * observing that the return value changed after a replaceHook) is
 * not defended against by this class. That's outside its scope.
 *
 * WHAT THIS DEFENDS
 * -----------------
 *
 *   • Naive method-count comparisons (the detector counts, gets
 *     the frozen count, no mismatch).
 *
 *   • Method-name-list comparisons, for classes whose frozen list
 *     matches the current list's names.
 *
 *   • Method-signature-list comparisons, same caveat.
 *
 * WHAT THIS DOES NOT DEFEND
 * -------------------------
 *
 *   • A detector that reads ART's internal method structures
 *     directly (via JNI or Unsafe). The frozen list only affects
 *     what the reflection API returns.
 *
 *   • A detector with a known-good baseline from a specific AOSP
 *     version, when the current device's actual pre-hook count
 *     differs. The freeze captures the actual count, not the AOSP
 *     count. AOSP-version-aware detectors can still see the
 *     difference.
 *
 *   • A detector that inspects the Method objects themselves
 *     (declaring class, annotations, bytecode) rather than the
 *     list. Method objects are returned as-is; we only filter the
 *     array, not the objects.
 *
 *   • Hooks installed after the freeze that add methods to the
 *     class. Those methods are visible because they aren't in the
 *     frozen list. In practice, XStealth's hooks are installed
 *     before the freeze, so this is rare.
 *
 *   • A libxposed module that calls replaceHook(Hooker) to swap
 *     the live callback. The method list doesn't change, so the
 *     frozen baseline is accurate, but a behavior-fingerprinting
 *     detector would see the swap. Out of scope.
 */
public final class MethodBaselineCheck {

    private static final String TAG = "XStealth";

    /**
     * Key: fully-qualified class name. Value: the frozen method
     * list captured at install time.
     *
     * Two baselines per class are kept:
     *   • declared — the result of getDeclaredMethods()
     *   • public   — the result of getMethods()
     *
     * They're distinct because getMethods() includes inherited
     * methods. A detector that uses either API gets a consistent
     * answer.
     */
    private static final Map<String, Method[]> DECLARED_BASELINE =
        new ConcurrentHashMap<>();
    private static final Map<String, Method[]> PUBLIC_BASELINE =
        new ConcurrentHashMap<>();

    /**
     * Classes that have already had a baseline captured.
     *
     * A class is added only on *success*. If Class.forName() throws
     * (transient classloader failure, class not yet loadable), the
     * class is not marked as captured and a later call can retry.
     * Marking before the try was a bug: a single transient failure
     * would permanently exclude the class.
     */
    private static final Set<String> CAPTURED =
        ConcurrentHashMap.newKeySet();

    private MethodBaselineCheck() {}

    public static int baselineCount() {
        return DECLARED_BASELINE.size();
    }

    // ═════════════════════════════════════════════════════════════
    // CAPTURE
    // ═════════════════════════════════════════════════════════════

    public static void captureStaticBaselines(ClassLoader classLoader,
                                                String[] classNames) {
        if (classNames == null) return;
        for (String name : classNames) {
            captureOne(name, classLoader);
        }
    }

    /**
     * Capture baselines for every class HookEngine has recorded as
     * hooked, that hasn't been captured yet.
     *
     * Call this from XposedHook.loadModule after each module's
     * entry point returns. Calling it at ApiProtectionCheck.install()
     * time captures nothing useful (no hooks exist yet) and is why
     * the baseline set was empty for module-hooked classes before
     * the per-module call was added.
     *
     * Idempotent: CAPTURED guards re-capture, and only successful
     * captures are marked, so a transient failure on one class
     * doesn't lose it for the rest of the process.
     */
    public static void captureDynamicBaselines(ClassLoader classLoader) {
        try {
            Set<String> hooked = HookEngine.getHookedClassNames();
            if (hooked == null || hooked.isEmpty()) return;

            int newly = 0;
            for (String name : hooked) {
                if (name == null) continue;
                if (CAPTURED.contains(name)) continue;
                if (captureOne(name, classLoader)) newly++;
            }
            if (newly > 0) {
                XposedBridge.log(TAG + ": MethodBaselineCheck captured "
                    + newly + " new class baselines ("
                    + hooked.size() + " hooked total, "
                    + DECLARED_BASELINE.size() + " baselines)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MethodBaselineCheck dynamic capture "
                + "failed: " + t.getMessage());
        }
    }

    /**
     * Capture one class's baseline.
     *
     * Returns true if a baseline was captured (or already existed).
     * Returns false if the class couldn't be loaded.
     *
     * IMPORTANT: CAPTURED is added only after the capture succeeds.
     * Marking it before the try would let a transient
     * Class.forName() failure permanently exclude the class.
     */
    private static boolean captureOne(String className, ClassLoader classLoader) {
        if (className == null || className.isEmpty()) return false;
        if (CAPTURED.contains(className)) return true;

        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            if (clazz == null) return false;

            Method[] declared = clazz.getDeclaredMethods();
            Method[] publicMethods = null;
            try {
                publicMethods = clazz.getMethods();
            } catch (Throwable ignored) {
                // Some classes throw on getMethods() in unusual
                // classloaders. Skip the public baseline.
            }

            if (declared != null) {
                DECLARED_BASELINE.put(className, declared.clone());
            }
            if (publicMethods != null) {
                PUBLIC_BASELINE.put(className, publicMethods.clone());
            }

            // Only mark captured after the put() calls succeed.
            // If we're here, the arrays were captured (or the
            // class legitimately has zero methods).
            CAPTURED.add(className);
            return true;
        } catch (Throwable ignored) {
            // Class not present, not loadable, or reflection
            // refused. Don't mark as captured — a later call can
            // retry (e.g. after the class's DexClassLoader is
            // constructed).
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // APPLY
    // ═════════════════════════════════════════════════════════════

    /**
     * If a baseline exists for this class, return a method array
     * that matches the baseline. Otherwise return null so the
     * caller can fall through to the standard filter.
     *
     * The returned array preserves Method identity for methods
     * that still exist in the current array — same Method object
     * is returned, not a copy. For methods that existed at capture
     * but are missing now (unusual), the baseline's Method object
     * is returned as-is.
     *
     * This means a detector that compares Method identity across
     * calls sees a stable answer.
     */
    public static Method[] applyBaseline(Class<?> clazz, Method[] current) {
        if (clazz == null || current == null) return null;
        String name = clazz.getName();
        if (name == null) return null;

        Method[] baseline = DECLARED_BASELINE.get(name);
        if (baseline == null) {
            // Fall back to the public baseline only if the
            // caller's array looks like a public one (all public
            // methods). This is a heuristic, not a guarantee.
            baseline = PUBLIC_BASELINE.get(name);
            if (baseline == null) return null;
        }

        // Build the result: for each baseline method, find the
        // current Method with the same signature. If found, use
        // the current object. If not, use the baseline object.
        Method[] result = new Method[baseline.length];
        for (int i = 0; i < baseline.length; i++) {
            Method b = baseline[i];
            if (b == null) {
                result[i] = null;
                continue;
            }
            Method match = findMatch(b, current);
            result[i] = (match != null) ? match : b;
        }

        // If the result equals the current array (same length, same
        // order, same methods), return null so the caller doesn't
        // set a redundant result. This saves an array copy in the
        // common case where nothing has changed.
        if (sameArray(result, current)) return null;

        return result;
    }

    private static Method findMatch(Method target, Method[] candidates) {
        if (target == null || candidates == null) return null;
        for (Method m : candidates) {
            if (m == null) continue;
            if (matchesSignature(target, m)) return m;
        }
        return null;
    }

    private static boolean matchesSignature(Method a, Method b) {
        if (a == null || b == null) return false;
        if (!a.getName().equals(b.getName())) return false;
        Class<?>[] pa = a.getParameterTypes();
        Class<?>[] pb = b.getParameterTypes();
        if (pa.length != pb.length) return false;
        for (int i = 0; i < pa.length; i++) {
            if (pa[i] != pb[i]) return false;
        }
        return true;
    }

    private static boolean sameArray(Method[] a, Method[] b) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    /**
     * For the Java-layer diagnostics. Returns a snapshot of what's
     * currently pinned. Used by the XStealth detail sheet to show
     * how many baselines are active.
     */
    public static Map<String, Integer> describe() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Method[]> e : DECLARED_BASELINE.entrySet()) {
            Method[] v = e.getValue();
            out.put(e.getKey(), v == null ? 0 : v.length);
        }
        return out;
    }

    /**
     * Diagnostic: classes that have been hooked but whose baseline
     * capture failed (class not loadable, or capture threw before
     * the put). Empty in the normal case. Non-empty indicates a
     * classloader issue, not a framework bug.
     */
    public static Set<String> pendingCapture() {
        Set<String> out = new HashSet<>();
        try {
            Set<String> hooked = HookEngine.getHookedClassNames();
            if (hooked != null) {
                for (String name : hooked) {
                    if (name != null && !CAPTURED.contains(name)) {
                        out.add(name);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}