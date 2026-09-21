package com.shizuposed.manager.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * XStealthPrefs
 *
 * Manager-side preferences for XStealth. This is the single source
 * of truth. The shell-side config (XStealthConfig) is derived from
 * these values and written to <shell-base>/modules/xstealth.json
 * before each deployment.
 *
 * WRITES USE commit(), NOT apply()
 * ---------------------------------
 * apply() flushes to disk on a background thread. On ROMs with
 * aggressive background-process killers — MIUI, ColorOS, Funtouch,
 * and some Samsung builds — the process can be killed before the
 * flush completes, which loses the write. commit() writes
 * synchronously and guarantees the value survives process death.
 *
 * SCOPE
 * -----
 * XStealth has an app scope, like any other module. The scope is
 * stored as a Set<String> of package names under key "scope".
 *
 * An EMPTY scope means "apply to every app ShizuPosed launches."
 * This matches XStealth's historical behavior before scope existed,
 * so upgrading users see no change. A user who wants to narrow the
 * hiding to specific apps adds them to the scope; from that point
 * on, only those apps are affected.
 *
 * The effective check is:
 *   • scope.isEmpty()                     → applies everywhere
 *   • scope.contains(targetPackage)       → applies to that target
 *   • otherwise                           → does not apply
 */
public final class XStealthPrefs {

    private static final String PREFS = "xstealth";

    private static final String KEY_ENABLED           = "enabled";
    private static final String KEY_NEXT_ENABLED      = "next_enabled";
    private static final String KEY_HIDE_DEV          = "hideDevOptions";
    private static final String KEY_HIDE_ADB          = "hideAdb";
    private static final String KEY_HIDE_SHIZUKU      = "hideShizukuPackage";
    private static final String KEY_HIDE_SHIZUPOSED   = "hideShizuPosedPackage";
    private static final String KEY_HIDE_PROC         = "hideRunningProcesses";
    private static final String KEY_HIDE_PROCFS       = "hideProcFs";
    private static final String KEY_API_PROTECTION    = "api_protection";
    private static final String KEY_DEX_OPTIMIZE      = "dex_optimize";

    /** Package names XStealth applies to. Empty = all apps. */
    private static final String KEY_SCOPE             = "scope";

    /** True once we've checked that the legacy "scope" key was migrated. */
    private static final String KEY_SCOPE_LEGACY      = "scope_legacy";
    private static final String KEY_SCOPE_MIGRATION   = "scope_migrated_5_5";

    private XStealthPrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * One-time cleanup for a pre-5.5 "scope" key that some very old
     * builds wrote but never read. Safe to call repeatedly.
     */
    private static void migrateIfNeeded(Context c) {
        try {
            SharedPreferences p = prefs(c);
            if (p.getBoolean(KEY_SCOPE_MIGRATION, false)) return;
            // Remove the legacy key only if it's a String (the old
            // format). A Set<String> under "scope" is the new format
            // and must be preserved.
            Object legacy = p.getAll().get(KEY_SCOPE_LEGACY);
            SharedPreferences.Editor e = p.edit();
            if (legacy != null) e.remove(KEY_SCOPE_LEGACY);
            e.putBoolean(KEY_SCOPE_MIGRATION, true);
            e.commit();
        } catch (Throwable ignored) {}
    }

    // ─── Master toggle ────────────────────────────────────────────

    public static boolean isEnabled(Context c) {
        migrateIfNeeded(c);
        return prefs(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_ENABLED, v).commit();
    }

    // ─── XStealth Next ────────────────────────────────────────────

    public static boolean isNextEnabled(Context c) {
        return prefs(c).getBoolean(KEY_NEXT_ENABLED, false);
    }

    public static void setNextEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_NEXT_ENABLED, v).commit();
    }

    // ─── Per-check toggles ────────────────────────────────────────

    public static boolean isHideDevOptions(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_DEV, true);
    }

    public static void setHideDevOptions(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_HIDE_DEV, v).commit();
    }

    public static boolean isHideAdb(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_ADB, true);
    }

    public static void setHideAdb(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_HIDE_ADB, v).commit();
    }

    public static boolean isHideShizukuPackage(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_SHIZUKU, true);
    }

    public static void setHideShizukuPackage(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_HIDE_SHIZUKU, v).commit();
    }

    public static boolean isHideShizuPosedPackage(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_SHIZUPOSED, true);
    }

    public static void setHideShizuPosedPackage(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_HIDE_SHIZUPOSED, v).commit();
    }

    public static boolean isHideRunningProcesses(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_PROC, true);
    }

    public static void setHideRunningProcesses(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_HIDE_PROC, v).commit();
    }

    public static boolean isHideProcFs(Context c) {
        return prefs(c).getBoolean(KEY_HIDE_PROCFS, true);
    }

    public static void setHideProcFs(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_HIDE_PROCFS, v).commit();
    }

    public static boolean isApiProtectionEnabled(Context c) {
        return prefs(c).getBoolean(KEY_API_PROTECTION, false);
    }

    public static void setApiProtectionEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_API_PROTECTION, v).commit();
    }

    public static boolean isDexOptimizeEnabled(Context c) {
        return prefs(c).getBoolean(KEY_DEX_OPTIMIZE, false);
    }

    public static void setDexOptimizeEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_DEX_OPTIMIZE, v).commit();
    }

    // ─── Scope ────────────────────────────────────────────────────

    /**
     * Return the current scope. Never null. The returned set is a
     * copy, safe to mutate without affecting the stored value.
     *
     * An empty set means "all apps." See the class javadoc.
     */
    public static Set<String> getScope(Context c) {
        migrateIfNeeded(c);
        try {
            Set<String> stored = prefs(c).getStringSet(KEY_SCOPE, null);
            if (stored == null) return new HashSet<>();
            return new HashSet<>(stored);
        } catch (Throwable t) {
            // Older APIs or a corrupted prefs file. Treat as empty
            // so the module falls back to the historical behavior.
            return new HashSet<>();
        }
    }

    /**
     * Replace the scope. An empty set restores "all apps."
     * Uses commit() for the same reason the toggles do.
     */
    public static void setScope(Context c, Set<String> scope) {
        HashSet<String> copy = (scope == null)
            ? new HashSet<>()
            : new HashSet<>(scope);
        prefs(c).edit().putStringSet(KEY_SCOPE, copy).commit();
    }

    /**
     * Does XStealth apply to this target package?
     *
     *   • empty scope  → true for any package
     *   • non-empty    → true only if the package is in the scope
     *
     * Never null. A null package name returns false — there's no
     * "target" to apply to.
     */
    public static boolean isInScope(Context c, String pkg) {
        if (pkg == null) return false;
        Set<String> scope = getScope(c);
        if (scope.isEmpty()) return true;
        return scope.contains(pkg);
    }

    /**
     * True when the scope is empty — meaning XStealth applies to
     * every app ShizuPosed launches. For UI labels.
     */
    public static boolean isScopeAllApps(Context c) {
        return getScope(c).isEmpty();
    }

    /** Number of explicitly-scoped apps. Zero means "all apps." */
    public static int getScopeCount(Context c) {
        return getScope(c).size();
    }

    /** Convenience for removing one package from the scope. */
    public static void removeFromScope(Context c, String pkg) {
        if (pkg == null) return;
        Set<String> scope = getScope(c);
        if (scope.remove(pkg)) {
            setScope(c, scope);
        }
    }

    /** Convenience for adding one package to the scope. */
    public static void addToScope(Context c, String pkg) {
        if (pkg == null) return;
        Set<String> scope = getScope(c);
        if (scope.add(pkg)) {
            setScope(c, scope);
        }
    }

    /** Restore "all apps" by clearing the scope. */
    public static void clearScope(Context c) {
        prefs(c).edit().remove(KEY_SCOPE).commit();
    }

    /**
     * Snapshot for diagnostics. Returns an unmodifiable view.
     */
    public static Set<String> getScopeReadonly(Context c) {
        return Collections.unmodifiableSet(getScope(c));
    }
}