package com.shizuposed.manager.core.compat;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * HiddenApiBypass
 *
 * On API 28+, hidden APIs (like Method.artMethod) are inaccessible by
 * default. This class tries several strategies to unblock them:
 *
 *   1. AccessibleObject.setAccessible(true) — works in app_process
 *      running as shell uid, since hidden-API restrictions apply to
 *      the app domain, not the shell domain.
 *
 *   2. Force the "override" / "flag" boolean on AccessibleObject via
 *      Unsafe — the classic Android 9 approach.
 *
 *   3. (Future) VM-level bypass via reflection into the runtime.
 *
 * Callers invoke forceAccessible(member) before reading a hidden
 * member, and it just works or silently no-ops. It never throws.
 */
public final class HiddenApiBypass {

    private static final String TAG = "HiddenApiBypass";

    private static volatile Field overrideField;
    private static volatile boolean overrideFieldTried = false;

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

        // Strategy 1: just call setAccessible(true)
        try {
            member.setAccessible(true);
            if (member.isAccessible()) return true;
        } catch (Throwable ignored) {}

        // Strategy 2: flip the "override" / "flag" boolean via Unsafe
        try {
            if (!overrideFieldTried) {
                overrideFieldTried = true;
                overrideField = findOverrideField();
            }
            if (overrideField != null) {
                overrideField.setBoolean(member, true);
                return true;
            }
        } catch (Throwable t) {
            log("override flip failed: " + t.getMessage());
        }

        // Strategy 3: try again with setAccessible as a last attempt
        // (some ROMs enforce access on a per-call basis)
        try {
            member.setAccessible(true);
            return member.isAccessible();
        } catch (Throwable t) {
            return false;
        }
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
            return null;
        }
    }

    /**
     * Convenience: fetch a hidden method's return value, force-accessing
     * it first. Returns null on any failure.
     */
    public static Object invokeMethod(Method method, Object target, Object... args) {
        if (method == null) return null;
        if (!forceAccessible(method)) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Look up a hidden field on a class hierarchy. Returns null if not
     * found anywhere up the chain.
     */
    public static Field findHiddenField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                if (forceAccessible(f)) return f;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // INTERNALS
    // ═════════════════════════════════════════════════════════════

    /**
     * The "override" field was renamed to "flag" in some Android
     * versions. Try both, plus a couple of future guesses.
     */
    private static Field findOverrideField() {
        String[] names = {
            "override",     // API 24–30
            "flag",         // API 21–23, and some ROM variants
            "accessFlags",  // future guess
            "accessible"    // future guess
        };
        for (String name : names) {
            try {
                Field f = AccessibleObject.class.getDeclaredField(name);
                f.setAccessible(true);
                // Sanity-check: it should be a boolean
                if (f.getType() == boolean.class) {
                    log("override field found: " + name);
                    return f;
                }
            } catch (Throwable ignored) {}
        }
        log("override field not found on this Android version");
        return null;
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.d("[" + TAG + "] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[" + TAG + "] " + msg);
    }
}