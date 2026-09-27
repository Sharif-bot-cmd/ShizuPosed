package com.shizuposed.manager.runtime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * RuntimePrefs
 *
 * Framework timing tunables, stored in the "runtime" preference file
 * so they're separated from the "shizuposed_settings" file that
 * holds UI-level toggles.
 *
 * Both values are read on every use, not cached, because the
 * Settings UI can change them while the framework is running and
 * the change should take effect on the next cycle without a
 * restart.
 *
 *   • Scan interval — how often ProcessMonitor re-scans /proc.
 *     Applies on the next service start.
 *
 *   • Hook delay — how long XposedHook waits between framework
 *     start and driving the target's Application bootstrap. A
 *     small delay helps on ROMs where the framework races the
 *     app's own initialization. Applies on the next app launch.
 */
public final class RuntimePrefs {

    private static final String PREFS = "runtime";

    private static final String KEY_SCAN_INTERVAL_MS = "scan_interval_ms";
    private static final String KEY_HOOK_DELAY_MS    = "hook_delay_ms";

    /** 5 seconds. Matches the previous hardcoded default. */
    public static final int DEFAULT_SCAN_INTERVAL_MS = 5000;

    /** 0 ms. No artificial delay. */
    public static final int DEFAULT_HOOK_DELAY_MS = 0;

    public static final int MIN_SCAN_INTERVAL_MS = 2000;
    public static final int MAX_SCAN_INTERVAL_MS = 30000;

    public static final int MIN_HOOK_DELAY_MS = 0;
    public static final int MAX_HOOK_DELAY_MS = 2000;

    private RuntimePrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getScanIntervalMs(Context c) {
        int v = prefs(c).getInt(KEY_SCAN_INTERVAL_MS, DEFAULT_SCAN_INTERVAL_MS);
        if (v < MIN_SCAN_INTERVAL_MS) v = MIN_SCAN_INTERVAL_MS;
        if (v > MAX_SCAN_INTERVAL_MS) v = MAX_SCAN_INTERVAL_MS;
        return v;
    }

    public static void setScanIntervalMs(Context c, int v) {
        if (v < MIN_SCAN_INTERVAL_MS) v = MIN_SCAN_INTERVAL_MS;
        if (v > MAX_SCAN_INTERVAL_MS) v = MAX_SCAN_INTERVAL_MS;
        prefs(c).edit().putInt(KEY_SCAN_INTERVAL_MS, v).commit();
    }

    public static int getHookDelayMs(Context c) {
        int v = prefs(c).getInt(KEY_HOOK_DELAY_MS, DEFAULT_HOOK_DELAY_MS);
        if (v < MIN_HOOK_DELAY_MS) v = MIN_HOOK_DELAY_MS;
        if (v > MAX_HOOK_DELAY_MS) v = MAX_HOOK_DELAY_MS;
        return v;
    }

    public static void setHookDelayMs(Context c, int v) {
        if (v < MIN_HOOK_DELAY_MS) v = MIN_HOOK_DELAY_MS;
        if (v > MAX_HOOK_DELAY_MS) v = MAX_HOOK_DELAY_MS;
        prefs(c).edit().putInt(KEY_HOOK_DELAY_MS, v).commit();
    }
}