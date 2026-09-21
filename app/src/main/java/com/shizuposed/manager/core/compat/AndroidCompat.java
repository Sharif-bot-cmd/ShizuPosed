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
 *
 * ART FAMILY
 * ----------
 * Some native components (libcallsite.so especially) need to know
 * which ART generation they're on, not just the SDK number. The
 * layout of ArtMethod, the names of interpreter entry symbols, and
 * the shape of the dispatch table all changed across releases. The
 * ART_FAMILY constants below group SDK levels into the generations
 * that share a layout.
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

    // ═════════════════════════════════════════════════════════════
    // ART FAMILY
    //
    // Grouping of Android releases by ArtMethod layout and
    // interpreter dispatch shape. Native components use these
    // instead of the raw SDK number when the actual relevant
    // difference is the ART generation, not the OS version.
    //
    // The boundaries are approximate. When ART changes mid-family
    // (as it did between 13 and 14 for the interpreter), a new
    // family is defined.
    // ═════════════════════════════════════════════════════════════

    /** Unknown / unmapped. */
    public static final int ART_UNKNOWN = 0;
    /** Android 10–11 (SDK 29–30). Pre-consolidated ArtMethod. */
    public static final int ART_10_11 = 1;
    /** Android 12 (SDK 31). Interim layout. */
    public static final int ART_12 = 2;
    /** Android 13 (SDK 33). Consolidated layout, old interpreter. */
    public static final int ART_13 = 3;
    /** Android 14 (SDK 34). New interpreter, exposed accessors. */
    public static final int ART_14 = 4;
    /** Android 15 (SDK 35). Minor changes to the interpreter. */
    public static final int ART_15 = 5;
    /** Android 16+ (SDK 36+). */
    public static final int ART_16_PLUS = 6;

    /** The ART family this process is running on. */
    public static final int ART_FAMILY;

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

        ART_FAMILY = computeArtFamily(SDK);

        CompatLog.d(TAG, "Effective SDK = " + SDK
                + " (raw=" + RAW_SDK
                + ", codename=" + CODENAME
                + ", preRelease=" + IS_PRE_RELEASE
                + ", artFamily=" + artFamilyName() + ")");
    }

    private AndroidCompat() {}

    private static int computeArtFamily(int sdk) {
        if (sdk >= 36) return ART_16_PLUS;
        if (sdk >= 35) return ART_15;
        if (sdk >= 34) return ART_14;
        if (sdk >= 33) return ART_13;
        if (sdk >= 31) return ART_12;
        if (sdk >= 29) return ART_10_11;
        return ART_UNKNOWN;
    }

    public static String artFamilyName() {
        switch (ART_FAMILY) {
            case ART_10_11:   return "10-11";
            case ART_12:      return "12";
            case ART_13:      return "13";
            case ART_14:      return "14";
            case ART_15:      return "15";
            case ART_16_PLUS: return "16+";
            default:          return "unknown";
        }
    }

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

    public static boolean mayHaveReflectiveUnsafe() {
        return true;
    }

    public static boolean hasHiddenApiEnforcement() {
        return isAtLeast(28);
    }

    public static boolean hasAccessibleObjectOverrideField() {
        return isBetween(28, 30);
    }

    public static boolean hasVMRuntimeHiddenApiExemptions() {
        return isAtLeast(28);
    }

    public static boolean supportsAppProcessLaunch() {
        return true;
    }

    public static boolean supportsReplacementHookMode() {
        return isAtLeast(28);
    }

    public static boolean hasConsolidatedArtMethodLayout() {
        return isAtLeast(30);
    }

    // ═════════════════════════════════════════════════════════════
    // CALL-SITE INTERCEPTION FLAGS
    // ═════════════════════════════════════════════════════════════

    /**
     * True if the ART generation is one where the interpreter
     * dispatch table is reachable through an exported
     * interpreter::Execute symbol. This is the precondition for
     * CallSiteBackend to work at all.
     *
     * All observed Android versions from 10 to 16 satisfy this.
     * The flag exists so a future version that removes the
     * symbols can be detected here instead of failing silently.
     */
    public static boolean supportsInterpreterSymbolAnchor() {
        return isAtLeast(29);
    }

    /**
     * True if the ART generation has the consolidated access_flags_
     * field on ArtMethod, accessible through GetAccessFlags() and
     * SetAccessFlags() rather than through raw struct offsets.
     */
    public static boolean hasArtMethodAccessorSymbols() {
        return isAtLeast(30);
    }

    /**
     * True if the interpreter dispatch uses a per-invoke-type table
     * rather than the older per-method handler scheme. This is the
     * shape CallSiteBackend's patching logic assumes.
     */
    public static boolean hasInterpreterHandlerTable() {
        return isAtLeast(30);
    }

    /**
     * True if the ART generation is known to strip its C++ export
     * symbols on some OEM ROMs. When this is true, CallSiteBackend
     * should also try the fallback layout probe.
     */
    public static boolean artMayHaveStrippedSymbols() {
        // OEM ROMs on 13-15 are the main offenders. Stock AOSP
        // is fine on all versions.
        return isAtLeast(33);
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    public static String describe() {
        if (IS_PRE_RELEASE) {
            return "Android SDK " + SDK
                    + " (pre-release " + CODENAME + ", raw=" + RAW_SDK
                    + ", ART " + artFamilyName() + ")";
        }
        return "Android SDK " + SDK + " (ART " + artFamilyName() + ")";
    }
}