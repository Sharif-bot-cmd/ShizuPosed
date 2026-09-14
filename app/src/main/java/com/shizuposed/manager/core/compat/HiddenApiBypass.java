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
 *   3. Flip the "override" boolean via Unsafe — the classic API 28-30
 *      approach. On API 31+ the field was removed; Strategy 1 covers
 *      those versions.
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

    /** Best strategy that has worked so far. */
    private static volatile int bestStrategy = STRATEGY_NONE;

    /** The AccessibleObject.override / flag field, if it exists. */
    private static volatile Field overrideField;
    private static volatile boolean overrideFieldResolved = false;

    private HiddenApiBypass() {}

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════

    /**
     * Try to make the given member usable. Returns true if the caller
     * can proceed to call get()/set()/invoke() on it.
     */
    public static boolean forceAccessible(AccessibleObject member) {
        if (member == null) return false;

        // Fast path: once a strategy has worked, trust it.
        int s = bestStrategy;
        if (s == STRATEGY_VMRUNTIME || s == STRATEGY_SETACCESSIBLE) {
            if (trySetAccessible(member)) return true;
        }

        // Strategy 1: process-wide hidden-API exemption.
        if (s == STRATEGY_NONE || s == STRATEGY_VMRUNTIME) {
            if (applyVmRuntimeExemptions()) {
                bestStrategy = STRATEGY_VMRUNTIME;
                if (trySetAccessible(member)) return true;
            }
        }

        // Strategy 2: plain setAccessible.
        if (trySetAccessible(member)) {
            if (bestStrategy == STRATEGY_NONE) bestStrategy = STRATEGY_SETACCESSIBLE;
            return true;
        }

        // Strategy 3: flip the override flag via Unsafe (API 28-30).
        if (s == STRATEGY_NONE || s == STRATEGY_OVERRIDE) {
            if (flipOverride(member)) {
                bestStrategy = STRATEGY_OVERRIDE;
                return true;
            }
        }

        return false;
    }

    /**
     * Convenience: fetch a hidden field's value, force-accessing it
     * first. Returns null on any failure.
     */
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

    /**
     * Convenience: fetch a hidden method's return value, force-accessing
     * it first. Returns null on any failure. Unwraps and logs the cause
     * of InvocationTargetException so the caller can see what the target
     * actually threw.
     */
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

    /**
     * Look up a hidden field on a class hierarchy. Returns null if not
     * found anywhere up the chain. Only returns non-static fields,
     * because static fields need field.get(null) and the caller can't
     * tell from the returned Field alone — use
     * {@link #findHiddenField(Class, String, boolean)} if you need
     * static fields.
     */
    public static Field findHiddenField(Class<?> clazz, String name) {
        return findHiddenField(clazz, name, false);
    }

    /**
     * Look up a hidden field. If {@code allowStatic} is true, static
     * fields are also returned.
     */
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

    /** Look up a hidden method on a class hierarchy, trying every parameter list. */
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

    /** Look up a hidden constructor. */
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
     * The modern bypass: exempt every class from hidden-API enforcement.
     * The prefix "L" matches every JVM class descriptor, so this
     * effectively disables enforcement for the process.
     *
     * Returns true if the call succeeded (or was already applied).
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
            // Not available before API 28 or on locked-down ROMs.
            return false;
        }
    }

    private static boolean trySetAccessible(AccessibleObject member) {
        try {
            member.setAccessible(true);
            // NOTE: isAccessible() is unreliable on API 28+; it returns
            // the "override" flag, which setAccessible just wrote. We
            // trust the call itself rather than the getter.
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
     * The "override" field was renamed to "flag" in some Android
     * versions and removed entirely in API 31+. We probe once and
     * cache the result.
     */
    private static Field resolveOverrideField() {
        if (overrideFieldResolved) return overrideField;
        synchronized (HiddenApiBypass.class) {
            if (overrideFieldResolved) return overrideField;
            overrideFieldResolved = true;

            String[] names = { "override", "flag" };
            for (String name : names) {
                try {
                    Field f = AccessibleObject.class.getDeclaredField(name);
                    f.setAccessible(true);
                    if (f.getType() == boolean.class) {
                        CompatLog.d(TAG, "override field found: " + name);
                        overrideField = f;
                        return f;
                    }
                } catch (Throwable ignored) {}
            }
            CompatLog.d(TAG, "override field not found on this Android version");
            return null;
        }
    }
}