package com.shizuposed.manager.stealth;

import android.content.Context;
import android.content.SharedPreferences;

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
 * MASTER TOGGLE LOCATION
 * ----------------------
 * The XStealth master toggle lives only in the XStealth detail
 * sheet (accessed by tapping the XStealth row in the Modules tab).
 * There is no mirror in Settings. XStealth Next is a separate
 * toggle in the same sheet.
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

    private static final String KEY_SCOPE_LEGACY = "scope";
    private static final String KEY_SCOPE_MIGRATION_DONE = "scope_migrated_4_3";
    private static final String KEY_API_PROTECTION   = "api_protection";
    private static final String KEY_DEX_OPTIMIZE     = "dex_optimize";

    private XStealthPrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void migrateIfNeeded(Context c) {
        try {
            SharedPreferences p = prefs(c);
            if (p.getBoolean(KEY_SCOPE_MIGRATION_DONE, false)) return;
            p.edit()
                .remove(KEY_SCOPE_LEGACY)
                .putBoolean(KEY_SCOPE_MIGRATION_DONE, true)
                .commit();
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
    }   // ← ADDED: closing brace for setHideProcFs()

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
}