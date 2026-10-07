package com.shizuposed.manager.core.compat;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * HiddenApiBypass
 *
 * On API 28+ hidden APIs (like Method.artMethod) are inaccessible by
 * default. This class tries several strategies to unblock them:
 *
 *   1. VMRuntime.setHiddenApiExemptions(new String[]{"L"}) — the modern
 *      approach. Exempts every class whose name starts with "L" (i.e.
 *      every class), disabling hidden-API enforcement process-wide.
 *
 *   2. AccessibleObject.setAccessible(true) — works in app_process
 *      running as shell uid, since hidden-API restrictions apply to
 *      the app domain, not the shell domain.
 *
 *   3. Flip the "override" / "flag" boolean via reflection — the
 *      classic API 28-30 approach. On API 31+ the field was removed;
 *      Strategy 1 covers those versions.
 *
 * The strategies are now gated on the runtime probes in AndroidCompat,
 * not on the SDK number. If a future Android version reintroduces
 * VMRuntime exemptions or the override field, the probes will pick
 * it up and the strategy will run without any code change here.
 *
 * Callers invoke forceAccessible(member) before reading a hidden
 * member. It never throws; it returns false if all strategies fail.
 */
public final class HiddenApiBypass {

    private static final String TAG = "HiddenApiBypass";

    private static final int STRATEGY_NONE = 0;
    private static final int STRATEGY_VMRUNTIME = 1;
    private static final int STRATEGY_SETACCESSIBLE = 2;
    private static final int STRATEGY_OVERRIDE = 3;

    private static volatile int bestStrategy = STRATEGY_NONE;

    /**
     * Cached reflection handle to the override/flag field. Resolved
     * on first use, using the field name reported by
     * AndroidCompat.ACCESSIBLE_OVERRIDE_FIELD_NAME.
     */
    private static volatile Field overrideField;
    private static volatile boolean overrideFieldResolved = false;

    private HiddenApiBypass() {}

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════

    public static boolean forceAccessible(AccessibleObject member) {
        if (member == null) return false;

        // Fast path: once a strategy has worked, trust it.
        int s = bestStrategy;
        if (s == STRATEGY_VMRUNTIME || s == STRATEGY_SETACCESSIBLE) {
            if (trySetAccessible(member)) return true;
        }

        // Strategy 1: process-wide hidden-API exemption.
        // Gated on the runtime probe, not the SDK number.
        if ((s == STRATEGY_NONE || s == STRATEGY_VMRUNTIME)
                && AndroidCompat.HAS_VMRUNTIME_EXEMPTIONS) {
            if (applyVmRuntimeExemptions()) {
                bestStrategy = STRATEGY_VMRUNTIME;
                if (trySetAccessible(member)) return true;
            }
        }

        // Strategy 2: plain setAccessible.
        // This works in app_process running as shell uid, where
        // hidden-API enforcement is not applied.
        if (trySetAccessible(member)) {
            if (bestStrategy == STRATEGY_NONE) {
                bestStrategy = STRATEGY_SETACCESSIBLE;
            }
            return true;
        }

        // Strategy 3: flip the override flag via reflection.
        // Gated on the runtime probe — if the field exists, this
        // works regardless of SDK number.
        if ((s == STRATEGY_NONE || s == STRATEGY_OVERRIDE)
                && AndroidCompat.HAS_ACCESSIBLE_OBJECT_OVERRIDE) {
            if (flipOverride(member)) {
                bestStrategy = STRATEGY_OVERRIDE;
                return true;
            }
        }

        return false;
    }

    public static Object getFieldValue(Field field, Object target) {
        if (field == null) return null;
        if (!forceAccessible(field)) return null;
        try {
            return field.get(target);
        } catch (Throwable t) {
            CompatLog.w(TAG, "getFieldValue(" + field.getName() + ") failed", t);
            return null;
        }
    }

    public static Object invokeMethod(Method method, Object target, Object... args) {
        if (method == null) return null;
        if (!forceAccessible(method)) return null;
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ite) {
            CompatLog.w(TAG, "invokeMethod(" + method.getName()
                    + ") target threw", ite.getCause());
            return null;
        } catch (Throwable t) {
            CompatLog.w(TAG, "invokeMethod(" + method.getName() + ") failed", t);
            return null;
        }
    }

    public static Field findHiddenField(Class<?> clazz, String name) {
        return findHiddenField(clazz, name, false);
    }

    public static Field findHiddenField(Class<?> clazz, String name, boolean allowStatic) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                if (!allowStatic && Modifier.isStatic(f.getModifiers())) {
                    // Skip static unless explicitly requested.
                } else if (forceAccessible(f)) {
                    return f;
                }
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    public static Method findHiddenMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                if (forceAccessible(m)) return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    public static Constructor<?> findHiddenConstructor(Class<?> clazz, Class<?>... params) {
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(params);
            if (forceAccessible(ctor)) return ctor;
        } catch (Throwable ignored) {}
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // STRATEGIES
    // ═════════════════════════════════════════════════════════════

    /**
     * The modern bypass: exempt every class from hidden-API
     * enforcement. The prefix "L" matches every JVM class
     * descriptor, so this effectively disables enforcement for the
     * process.
     *
     * Returns true if the call succeeded.
     */
    private static boolean applyVmRuntimeExemptions() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            if (runtime == null) return false;

            Method setExemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, (Object) new String[]{ "L" });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean trySetAccessible(AccessibleObject member) {
        try {
            member.setAccessible(true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean flipOverride(AccessibleObject member) {
        Field f = resolveOverrideField();
        if (f == null) return false;
        try {
            f.setBoolean(member, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolve the override/flag field on AccessibleObject. Uses the
     * field name discovered by AndroidCompat's probe pass, so we
     * don't have to guess or re-probe here.
     */
    private static Field resolveOverrideField() {
        if (overrideFieldResolved) return overrideField;
        synchronized (HiddenApiBypass.class) {
            if (overrideFieldResolved) return overrideField;
            overrideFieldResolved = true;

            String name = AndroidCompat.ACCESSIBLE_OVERRIDE_FIELD_NAME;
            if (name == null) {
                CompatLog.d(TAG, "override field not present on this runtime");
                return null;
            }
            try {
                Field f = AccessibleObject.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == boolean.class) {
                    CompatLog.d(TAG, "override field resolved: " + name);
                    overrideField = f;
                    return f;
                }
            } catch (Throwable t) {
                CompatLog.d(TAG, "override field lookup failed: "
                    + t.getMessage());
            }
            return null;
        }
    }
}