package com.shizuposed.manager.core.compat;

import android.os.Build;

/**
 * AndroidCompat
 *
 * Single source of truth for "what SDK level are we really on?" and
 * "does this Android version have feature X?".
 *
 * Two reasons this exists:
 *
 *   1. Build.VERSION.SDK_INT lags behind pre-release builds. When
 *      Android N is in beta, SDK_INT reports the previous released
 *      version (e.g. 34 for Android 15 beta). We bump it up by one if
 *      Build.VERSION.CODENAME is not "REL".
 *
 *      This is a heuristic, not a guarantee. If a release candidate
 *      ships with a bumped SDK_INT and a non-REL codename, the bump
 *      over-counts by one. Callers that must be exact should check
 *      {@link #IS_PRE_RELEASE} and decide for themselves.
 *
 *   2. Feature flags. Whether hidden API is enforced, whether
 *      sun.misc.Unsafe still exists, whether the ArtMethod layout
 *      changed — all of it can live in one place instead of being
 *      scattered across the codebase.
 */
public final class AndroidCompat {

    private static final String TAG = "AndroidCompat";

    /** Effective SDK level: SDK_INT, bumped for pre-releases. */
    public static final int SDK;

    /** Raw SDK_INT as reported by the platform. */
    public static final int RAW_SDK;

    /** True if Build.VERSION.CODENAME is not "REL". */
    public static final boolean IS_PRE_RELEASE;

    /** Codename string, or "REL" if released. */
    public static final String CODENAME;

    static {
        int raw;
        boolean preRelease;
        String codename;
        try {
            raw = Build.VERSION.SDK_INT;
            Object cn = Build.VERSION.CODENAME;
            codename = cn == null ? "REL" : cn.toString();
            preRelease = !"REL".equalsIgnoreCase(codename);
        } catch (Throwable t) {
            CompatLog.w(TAG, "Failed to read Build.VERSION; assuming API 29", t);
            raw = 29;
            codename = "REL";
            preRelease = false;
        }

        RAW_SDK = raw;
        SDK = preRelease ? raw + 1 : raw;
        IS_PRE_RELEASE = preRelease;
        CODENAME = codename;

        CompatLog.d(TAG, "Effective SDK = " + SDK
                + " (raw=" + RAW_SDK
                + ", codename=" + CODENAME
                + ", preRelease=" + IS_PRE_RELEASE + ")");
    }

    private AndroidCompat() {}

    // ═════════════════════════════════════════════════════════════
    // VERSION CHECKS
    // ═════════════════════════════════════════════════════════════

    public static boolean isAtLeast(int api) { return SDK >= api; }
    public static boolean isBelow(int api)   { return SDK <  api; }
    public static boolean isBetween(int min, int max) {
        return SDK >= min && SDK <= max;
    }

    // ═════════════════════════════════════════════════════════════
    // FEATURE FLAGS
    // ═════════════════════════════════════════════════════════════

    /**
     * True if sun.misc.Unsafe is *known* to be available on this
     * Android generation. This is a generation-level fact; the runtime
     * probe is {@link ReflectionUnsafe#isAvailable()}.
     */
    public static boolean mayHaveReflectiveUnsafe() {
        // sun.misc.Unsafe has shipped on every Android release so far.
        // We don't gate on SDK; ReflectionUnsafe probes at runtime and
        // degrades gracefully. This method exists so callers can ask
        // the question without depending on the probe's side effects.
        return true;
    }

    /** True if hidden-API enforcement is a factor (API 28+). */
    public static boolean hasHiddenApiEnforcement() {
        return isAtLeast(28);
    }

    /**
     * True if the AccessibleObject.override field exists, so the
     * classic Unsafe-flip strategy can work. Removed in API 31.
     */
    public static boolean hasAccessibleObjectOverrideField() {
        return isBetween(28, 30);
    }

    /**
     * True if VMRuntime.setHiddenApiExemptions is available, so the
     * modern process-wide bypass can be used. Added in API 28.
     */
    public static boolean hasVMRuntimeHiddenApiExemptions() {
        return isAtLeast(28);
    }

    /**
     * True if the pre-Application startup path via app_process is
     * available. Always true in practice; kept as a flag so a future
     * sandbox that blocks the technique can be detected here.
     */
    public static boolean supportsAppProcessLaunch() {
        return true;
    }

    /**
     * True if Pine's REPLACEMENT hook mode is likely to work.
     * REPLACEMENT requires the ART Instrumentation::Replace path,
     * which stabilised in API 28.
     */
    public static boolean supportsReplacementHookMode() {
        return isAtLeast(28);
    }

    /**
     * True if the ArtMethod layout is the consolidated post-Android-11
     * layout, where access flags live in a separate field rather than
     * packed into the method pointer.
     */
    public static boolean hasConsolidatedArtMethodLayout() {
        return isAtLeast(30); // Android 11
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    public static String describe() {
        if (IS_PRE_RELEASE) {
            return "Android SDK " + SDK
                    + " (pre-release " + CODENAME + ", raw=" + RAW_SDK + ")";
        }
        return "Android SDK " + SDK;
    }
}