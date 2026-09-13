package com.shizuposed.manager.core.compat;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * AndroidCompat
 *
 * Single source of truth for "what SDK level are we really on?"
 * and "does this Android version have feature X?".
 *
 * Two reasons this exists:
 *
 *   1. Build.VERSION.SDK_INT lags behind pre-release builds. When
 *      Android N is in beta, SDK_INT reports the previous released
 *      version (e.g. 34 for Android 15 beta). We bump it up by one if
 *      Build.VERSION.CODENAME is not "REL".
 *
 *   2. Feature flags. Whether hidden API is enforced, whether
 *      sun.misc.Unsafe still exists, whether the ArtMethod layout
 *      changed — all of it can live in one place instead of being
 *      scattered across the codebase.
 */
public final class AndroidCompat {

    private static final String TAG = "AndroidCompat";

    /** Effective SDK level: Build.VERSION.SDK_INT, bumped for pre-releases. */
    public static final int SDK;

    /** True if Build.VERSION.CODENAME is not "REL". */
    public static final boolean IS_PRE_RELEASE;

    /** Codename string, or "REL" if released. */
    public static final String CODENAME;

    static {
        int sdk = 0;
        boolean preRelease = false;
        String codename = "REL";

        try {
            Class<?> bv = Class.forName("android.os.Build$VERSION");
            Field sdkField = bv.getField("SDK_INT");
            sdk = sdkField.getInt(null);

            Field codenameField = bv.getField("CODENAME");
            Object cn = codenameField.get(null);
            if (cn instanceof String) {
                codename = (String) cn;
                preRelease = !"REL".equalsIgnoreCase(codename);
            }
        } catch (Throwable t) {
            log("Failed to read Build.VERSION: " + t.getMessage());
        }

        SDK = preRelease ? sdk + 1 : sdk;
        IS_PRE_RELEASE = preRelease;
        CODENAME = codename;

        log("Effective SDK = " + SDK
            + " (codename=" + CODENAME + ", preRelease=" + IS_PRE_RELEASE + ")");
    }

    private AndroidCompat() {}

    // ═════════════════════════════════════════════════════════════
    // VERSION CHECKS
    // ═════════════════════════════════════════════════════════════

    /** True if the effective SDK is at least the given API level. */
    public static boolean isAtLeast(int api) {
        return SDK >= api;
    }

    /** True if the effective SDK is below the given API level. */
    public static boolean isBelow(int api) {
        return SDK < api;
    }

    /** True if the effective SDK is in [min, max] inclusive. */
    public static boolean isBetween(int min, int max) {
        return SDK >= min && SDK <= max;
    }

    // ═════════════════════════════════════════════════════════════
    // FEATURE FLAGS
    // ═════════════════════════════════════════════════════════════

    /**
     * True if we should assume sun.misc.Unsafe is available.
     * Everything up to and including Android 15 exposes it. Future
     * versions may not; ReflectionUnsafe handles that case gracefully.
     */
    public static boolean hasReflectiveUnsafe() {
        // We don't gate on SDK here; ReflectionUnsafe probes at runtime.
        return true;
    }

    /**
     * True if hidden API enforcement is a factor (API 28+).
     * Before that, getDeclaredField("artMethod") etc. just work.
     */
    public static boolean hasHiddenApiEnforcement() {
        return isAtLeast(28);
    }

    /**
     * True if the pre-Application startup path via app_process is
     * available. It always is; this exists so future versions can
     * disable it if a new sandbox blocks the technique.
     */
    public static boolean supportsAppProcessLaunch() {
        return true;
    }

    /**
     * True if Pine's REPLACEMENT hook mode is likely to work.
     * REPLACEMENT has been stable since about API 28; on very old
     * versions it may need inline mode instead.
     */
    public static boolean supportsReplacementHookMode() {
        return isAtLeast(26);
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    /** Human-readable summary for logs. */
    public static String describe() {
        return "Android SDK " + SDK
            + (IS_PRE_RELEASE ? " (pre-release " + CODENAME + ")" : "");
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.d("[" + TAG + "] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[" + TAG + "] " + msg);
    }
}