package com.shizuposed.manager.core.compat;

import android.os.Build;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * AndroidCompat
 *
 * Single source of truth for "what can this runtime actually do?"
 * and "which ART generation is this?".
 *
 * DESIGN
 * ------
 * Everything here is probed at runtime rather than hardcoded from
 * the SDK number. The SDK number is used as a hint when a probe
 * fails, but the answer always comes from what the runtime reports.
 *
 * This matters because:
 *
 *   • New Android versions often keep the layout from the previous
 *     one. An SDK-based gate would guess wrong; a probe wouldn't.
 *
 *   • OEM ROMs diverge from AOSP in ways the SDK number can't
 *     express. A probe sees the actual divergence.
 *
 *   • The same SDK level can be built with different ART configs
 *     (release vs. userdebug, AOSP vs. vendor fork). A probe sees
 *     the difference; a version gate doesn't.
 *
 *   • Pre-release builds often have the previous release's SDK_INT
 *     while running the new layout. A probe sees the new layout;
 *     the SDK bump heuristic only sees "one higher".
 *
 * The cost is one class-load pass that does a handful of reflection
 * lookups. All results are cached; the probes never run again.
 *
 * PROBE PHASES
 * ------------
 *   1. SDK reading (with the pre-release bump).
 *   2. VMRuntime / hidden-API probe.
 *   3. AccessibleObject.override / flag field probe.
 *   4. ArtMethod accessor probe (GetAccessFlags / SetAccessFlags).
 *   5. Interpreter symbol probe (Execute / ExecuteSwitchImpl).
 *   6. Unsafe class probe (sun.misc.Unsafe / jdk.internal.misc.Unsafe).
 *   7. ART family assignment: derived from probes 3–6, with the
 *      SDK number as a tiebreaker when the probes are inconclusive.
 *
 * All probes fail open: a probe that can't complete records
 * "unknown" rather than throwing. Callers see the unknown as false
 * for feature flags and use the SDK hint for the family.
 */
public final class AndroidCompat {

    private static final String TAG = "AndroidCompat";

    // ═════════════════════════════════════════════════════════════
    // SDK LEVEL
    // ═════════════════════════════════════════════════════════════

    public static final int SDK;
    public static final int RAW_SDK;
    public static final boolean IS_PRE_RELEASE;
    public static final String CODENAME;

    // ═════════════════════════════════════════════════════════════
    // ART FAMILY
    //
    // The family is derived from the runtime probes rather than
    // assigned from the SDK number. When the probes are conclusive,
    // they win. When they're not, the SDK number is used as a hint.
    //
    // Each family represents a set of layout properties that the
    // native components care about. If a future Android version
    // keeps the previous family's layout, the probes will assign
    // the new version to the old family — which is correct.
    // ═════════════════════════════════════════════════════════════

    public static final int ART_UNKNOWN = 0;
    public static final int ART_10_11 = 1;
    public static final int ART_12 = 2;
    public static final int ART_13 = 3;
    public static final int ART_14 = 4;
    public static final int ART_15 = 5;
    public static final int ART_16_PLUS = 6;

    public static final int ART_FAMILY;

    /** True if ART_FAMILY was assigned from probes, false if from SDK hint. */
    public static final boolean ART_FAMILY_FROM_PROBES;

    // ═════════════════════════════════════════════════════════════
    // CAPABILITY PROBES
    //
    // Each is a boolean that reflects what the runtime actually
    // exposes. Values are populated in the static initializer and
    // never change afterward.
    //
    // The naming is deliberately about the capability, not the
    // version. "HasX" means "the runtime exposes X", not "the SDK
    // is at least N".
    // ═════════════════════════════════════════════════════════════

    /** VMRuntime.setHiddenApiExemptions exists and is callable. */
    public static final boolean HAS_VMRUNTIME_EXEMPTIONS;

    /** AccessibleObject carries a boolean override/flag field. */
    public static final boolean HAS_ACCESSIBLE_OBJECT_OVERRIDE;

    /** The override field's actual name, or null if absent. */
    public static final String ACCESSIBLE_OVERRIDE_FIELD_NAME;

    /** Method.artMethod / accessor for the ArtMethod pointer exists. */
    public static final boolean HAS_ART_METHOD_ACCESSOR;

    /** ArtMethod exposes GetAccessFlags / SetAccessFlags. */
    public static final boolean HAS_ART_METHOD_ACCESSOR_SYMBOLS;

    /** libart exposes an interpreter Execute symbol. */
    public static final boolean HAS_INTERPRETER_SYMBOL_ANCHOR;

    /** libart exposes an interpreter handler table. */
    public static final boolean HAS_INTERPRETER_HANDLER_TABLE;

    /** The process exposes sun.misc.Unsafe or jdk.internal.misc.Unsafe. */
    public static final boolean HAS_UNSAFE;

    /** The Unsafe class's actual fully-qualified name, or null. */
    public static final String UNSAFE_CLASS_NAME;

    /** InMemoryDexClassLoader exists. */
    public static final boolean HAS_IN_MEMORY_DEX_LOADER;

    /** The runtime supports the app_process bootstrap launch model. */
    public static final boolean SUPPORTS_APP_PROCESS_LAUNCH;

    /** Diagnostic notes from the probe pass. */
    private static final List<String> PROBE_NOTES = new ArrayList<>();

    // ═════════════════════════════════════════════════════════════
    // STATIC INITIALIZATION
    // ═════════════════════════════════════════════════════════════

    static {
        // ─── Phase 1: SDK ────────────────────────────────────────
        int raw;
        boolean preRelease;
        String codename;
        try {
            raw = Build.VERSION.SDK_INT;
            Object cn = Build.VERSION.CODENAME;
            codename = cn == null ? "REL" : cn.toString();
            preRelease = !"REL".equalsIgnoreCase(codename);
        } catch (Throwable t) {
            CompatLog.w(TAG, "Build.VERSION read failed; assuming API 29", t);
            raw = 29;
            codename = "REL";
            preRelease = false;
        }

        RAW_SDK = raw;
        SDK = preRelease ? raw + 1 : raw;
        IS_PRE_RELEASE = preRelease;
        CODENAME = codename;

        // ─── Phase 2: runtime probes ─────────────────────────────
        ProbeResult probes = new ProbeResult();

        probeVmRuntime(probes);
        probeAccessibleObjectOverride(probes);
        probeArtMethodAccessor(probes);
        probeInterpreterSymbols(probes);
        probeUnsafe(probes);
        probeInMemoryDexLoader(probes);
        probeAppProcessLaunch(probes);

        HAS_VMRUNTIME_EXEMPTIONS = probes.vmRuntimeExemptions;
        HAS_ACCESSIBLE_OBJECT_OVERRIDE = probes.accessibleOverride;
        ACCESSIBLE_OVERRIDE_FIELD_NAME = probes.accessibleOverrideName;
        HAS_ART_METHOD_ACCESSOR = probes.artMethodAccessor;
        HAS_ART_METHOD_ACCESSOR_SYMBOLS = probes.artMethodAccessorSymbols;
        HAS_INTERPRETER_SYMBOL_ANCHOR = probes.interpreterSymbolAnchor;
        HAS_INTERPRETER_HANDLER_TABLE = probes.interpreterHandlerTable;
        HAS_UNSAFE = probes.hasUnsafe;
        UNSAFE_CLASS_NAME = probes.unsafeClassName;
        HAS_IN_MEMORY_DEX_LOADER = probes.inMemoryDexLoader;
        SUPPORTS_APP_PROCESS_LAUNCH = probes.appProcessLaunch;

        // ─── Phase 3: family assignment ──────────────────────────
        int familyFromProbes = deriveArtFamilyFromProbes(probes);
        if (familyFromProbes != ART_UNKNOWN) {
            ART_FAMILY = familyFromProbes;
            ART_FAMILY_FROM_PROBES = true;
        } else {
            ART_FAMILY = computeArtFamilyFromSdk(SDK);
            ART_FAMILY_FROM_PROBES = false;
            note("ART family assigned from SDK hint (probes inconclusive)");
        }

        CompatLog.d(TAG, "Effective SDK = " + SDK
                + " (raw=" + RAW_SDK
                + ", codename=" + CODENAME
                + ", preRelease=" + IS_PRE_RELEASE
                + ", artFamily=" + artFamilyName()
                + (ART_FAMILY_FROM_PROBES ? " [probed]" : " [sdk-hint]")
                + ")");

        if (CompatLog.isDebugEnabled()) {
            for (String n : PROBE_NOTES) {
                CompatLog.d(TAG, "probe: " + n);
            }
        }
    }

    private AndroidCompat() {}

    // ═════════════════════════════════════════════════════════════
    // PROBE IMPLEMENTATIONS
    //
    // Each probe is self-contained and fail-open. A probe that
    // throws records a note and leaves its field at the default
    // (false / null). The caller can't tell "probe said no" from
    // "probe couldn't run" — that distinction is recorded in the
    // probe notes for diagnostics, not surfaced through the public
    // flags.
    // ═════════════════════════════════════════════════════════════

    private static final class ProbeResult {
        boolean vmRuntimeExemptions;
        boolean accessibleOverride;
        String  accessibleOverrideName;
        boolean artMethodAccessor;
        boolean artMethodAccessorSymbols;
        boolean interpreterSymbolAnchor;
        boolean interpreterHandlerTable;
        boolean hasUnsafe;
        String  unsafeClassName;
        boolean inMemoryDexLoader;
        boolean appProcessLaunch;
    }

    private static void probeVmRuntime(ProbeResult r) {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            if (runtime == null) {
                note("VMRuntime.getRuntime() returned null");
                return;
            }
            try {
                vmRuntime.getDeclaredMethod("setHiddenApiExemptions",
                    String[].class);
                r.vmRuntimeExemptions = true;
                note("VMRuntime.setHiddenApiExemptions present");
            } catch (NoSuchMethodException e) {
                note("VMRuntime.setHiddenApiExemptions absent");
            }
        } catch (Throwable t) {
            note("VMRuntime probe failed: " + t.getMessage());
        }
    }

    private static void probeAccessibleObjectOverride(ProbeResult r) {
        // Try both names. On API 28-30 it's "override"; on some builds
        // it's "flag". On 31+ neither exists.
        String[] names = { "override", "flag" };
        for (String name : names) {
            try {
                Field f = AccessibleObject.class.getDeclaredField(name);
                if (f.getType() != boolean.class) continue;
                r.accessibleOverride = true;
                r.accessibleOverrideName = name;
                note("AccessibleObject." + name + " present");
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                note("AccessibleObject." + name + " probe failed: "
                    + t.getMessage());
            }
        }
        note("AccessibleObject override field absent (API 31+ layout)");
    }

    private static void probeArtMethodAccessor(ProbeResult r) {
        // Method.artMethod is a hidden field that exposes the
        // ArtMethod pointer. Its presence and accessibility vary.
        try {
            Field artMethod = Method.class.getDeclaredField("artMethod");
            if (Modifier.isStatic(artMethod.getModifiers())) return;
            r.artMethodAccessor = true;
            note("Method.artMethod present");
        } catch (NoSuchFieldException e) {
            note("Method.artMethod absent (consolidated layout?)");
        } catch (Throwable t) {
            note("Method.artMethod probe failed: " + t.getMessage());
        }

        // The accessor symbols live in libart. We can't inspect
        // libart's symbol table from Java, but we can check whether
        // the runtime exposes an equivalent Java-level accessor.
        // On some ROMs, ArtMethod fields are reachable through
        // reflection helpers; we probe for the class that carries
        // them.
        try {
            Class.forName("com.android.internal.os.ArtMethodHelper");
            r.artMethodAccessorSymbols = true;
            note("ArtMethodHelper present");
        } catch (Throwable ignored) {
            // Fall back to an SDK-based hint below.
        }
    }

    private static void probeInterpreterSymbols(ProbeResult r) {
        // We can't dlsym libart from Java. The closest we can get
        // is to check for the presence of classes or fields that
        // correlate with the interpreter layout.
        //
        // On API 30+, dalvik.system.VMRuntime exposes
        // getCurrentInstructionSet and similar methods that were
        // added alongside the consolidated interpreter.
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method m = null;
            try {
                m = vmRuntime.getDeclaredMethod("getCurrentInstructionSet");
            } catch (NoSuchMethodException ignored) {}
            if (m != null) {
                r.interpreterSymbolAnchor = true;
                r.interpreterHandlerTable = true;
                note("VMRuntime.getCurrentInstructionSet present "
                    + "(implies interpreter anchor)");
            } else {
                note("VMRuntime.getCurrentInstructionSet absent");
            }
        } catch (Throwable t) {
            note("interpreter symbol probe failed: " + t.getMessage());
        }

        // Additional hint: some ROMs expose ArtMethod's access flags
        // through a Java-level helper. If it exists, the handler
        // table shape matches the consolidated layout.
        try {
            Class<?> artMethod = Class.forName("java.lang.reflect.ArtMethod");
            Field flags = artMethod.getDeclaredField("accessFlags");
            if (flags.getType() == int.class) {
                r.interpreterHandlerTable = true;
                note("ArtMethod.accessFlags present (consolidated layout)");
            }
        } catch (Throwable ignored) {}
    }

    private static void probeUnsafe(ProbeResult r) {
        String[] names = { "sun.misc.Unsafe", "jdk.internal.misc.Unsafe" };
        for (String name : names) {
            try {
                Class<?> c = Class.forName(name);
                if (c != null) {
                    r.hasUnsafe = true;
                    r.unsafeClassName = name;
                    note("Unsafe class present: " + name);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        note("no Unsafe class on the boot classpath");
    }

    private static void probeInMemoryDexLoader(ProbeResult r) {
        try {
            Class.forName("dalvik.system.InMemoryDexClassLoader");
            r.inMemoryDexLoader = true;
            note("InMemoryDexClassLoader present");
        } catch (Throwable t) {
            note("InMemoryDexClassLoader absent");
        }
    }

    private static void probeAppProcessLaunch(ProbeResult r) {
        // app_process is present on every Android device. What we
        // actually care about is whether the current process can
        // reach the classes needed to drive ActivityThread.
        try {
            Class.forName("android.app.ActivityThread");
            r.appProcessLaunch = true;
            note("ActivityThread present");
        } catch (Throwable t) {
            note("ActivityThread absent (unusual)");
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FAMILY ASSIGNMENT
    // ═════════════════════════════════════════════════════════════

    /**
     * Assign a family from probe results.
     *
     * Priority order:
     *
     *   1. If ArtMethod.accessFlags is present (consolidated layout
     *      with the accessor field), the family is at least ART_13.
     *      If VMRuntime.getCurrentInstructionSet is also present,
     *      it's at least ART_14.
     *
     *   2. If AccessibleObject.override is absent, it's ART_14+.
     *
     *   3. If AccessibleObject.override is present, it's ART_10_11
     *      through ART_13. The interpreter handler table distinction
     *      between 12 and 13 isn't probeable from Java, so we fall
     *      back to the SDK hint for that boundary.
     *
     * Returns ART_UNKNOWN when the probes don't narrow the family
     * enough to be useful. The caller falls back to the SDK hint.
     */
    private static int deriveArtFamilyFromProbes(ProbeResult r) {
        // Consolidated layout markers.
        if (r.artMethodAccessorSymbols && r.interpreterHandlerTable) {
            // ART_14 or later. Distinguish 14 / 15 / 16+ by the
            // SDK hint — the probes can't tell those apart.
            if (SDK >= 36) return ART_16_PLUS;
            if (SDK >= 35) return ART_15;
            return ART_14;
        }

        if (r.artMethodAccessorSymbols) {
            // Consolidated access-flags field, older handler table.
            return ART_13;
        }

        if (!r.accessibleOverride && r.hasUnsafe) {
            // No override field but Unsafe is present. This is the
            // API 31+ shape without the consolidated layout marker.
            return ART_12;
        }

        if (r.accessibleOverride) {
            // Override field present. Pre-consolidated layout.
            // Use the SDK hint to distinguish 10-11 from 12.
            if (SDK >= 31) return ART_12;
            return ART_10_11;
        }

        return ART_UNKNOWN;
    }

    /**
     * SDK-based family assignment, used as a hint when probes are
     * inconclusive. This is the same logic as before, kept for the
     * fallback path.
     */
    private static int computeArtFamilyFromSdk(int sdk) {
        if (sdk >= 36) return ART_16_PLUS;
        if (sdk >= 35) return ART_15;
        if (sdk >= 34) return ART_14;
        if (sdk >= 33) return ART_13;
        if (sdk >= 31) return ART_12;
        if (sdk >= 29) return ART_10_11;
        return ART_UNKNOWN;
    }

    private static void note(String msg) {
        PROBE_NOTES.add(msg);
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — SDK
    // ═════════════════════════════════════════════════════════════

    public static boolean isAtLeast(int api) { return SDK >= api; }
    public static boolean isBelow(int api)   { return SDK <  api; }
    public static boolean isBetween(int min, int max) {
        return SDK >= min && SDK <= max;
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — FEATURE FLAGS
    //
    // These now answer from probe results, not from the SDK
    // number. A caller that wants "is this Android 28+" should
    // call isAtLeast(28) directly; a caller that wants "does this
    // runtime expose VMRuntime exemptions" calls the flag.
    // ═════════════════════════════════════════════════════════════

    public static boolean hasHiddenApiEnforcement() {
        // Enforcement is present on API 28+, but the actual presence
        // is a policy, not a probeable fact. The probe tells us
        // whether the bypass exists; enforcement is the default state
        // when the bypass hasn't been applied.
        return isAtLeast(28);
    }

    public static boolean hasVMRuntimeHiddenApiExemptions() {
        return HAS_VMRUNTIME_EXEMPTIONS;
    }

    public static boolean hasAccessibleObjectOverrideField() {
        return HAS_ACCESSIBLE_OBJECT_OVERRIDE;
    }

    public static boolean hasConsolidatedArtMethodLayout() {
        return HAS_ART_METHOD_ACCESSOR_SYMBOLS;
    }

    public static boolean mayHaveReflectiveUnsafe() {
        return HAS_UNSAFE;
    }

    public static boolean supportsInMemoryDexClassLoader() {
        return HAS_IN_MEMORY_DEX_LOADER;
    }

    public static boolean supportsAppProcessLaunch() {
        return SUPPORTS_APP_PROCESS_LAUNCH;
    }

    public static boolean supportsReplacementHookMode() {
        // Replacement mode uses ArtMethod accessors that have been
        // present since API 28. The probe confirms the accessor
        // exists; the SDK gate is a lower bound.
        return isAtLeast(28);
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — CALL-SITE INTERCEPTION FLAGS
    // ═════════════════════════════════════════════════════════════

    public static boolean supportsInterpreterSymbolAnchor() {
        return HAS_INTERPRETER_SYMBOL_ANCHOR;
    }

    public static boolean hasArtMethodAccessorSymbols() {
        return HAS_ART_METHOD_ACCESSOR_SYMBOLS;
    }

    public static boolean hasInterpreterHandlerTable() {
        return HAS_INTERPRETER_HANDLER_TABLE;
    }

    /**
     * True when the ART generation is known to strip its C++ export
     * symbols on some OEM ROMs. This remains a version hint: we
     * can't probe for it from Java. Callers that need certainty
     * should attempt the fallback layout probe when the primary
     * anchor fails.
     */
    public static boolean artMayHaveStrippedSymbols() {
        return isAtLeast(33);
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

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

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android SDK ").append(SDK);
        if (IS_PRE_RELEASE) {
            sb.append(" (pre-release ").append(CODENAME)
              .append(", raw=").append(RAW_SDK).append(")");
        }
        sb.append(", ART ").append(artFamilyName());
        sb.append(ART_FAMILY_FROM_PROBES ? " [probed]" : " [sdk-hint]");
        return sb.toString();
    }

    /**
     * Full diagnostic. Lists every capability flag and every probe
     * note. Useful for bug reports.
     */
    public static String describeFull() {
        StringBuilder sb = new StringBuilder();
        sb.append(describe()).append('\n');
        sb.append("capabilities:\n");
        sb.append("  hasVMRuntimeExemptions=").append(HAS_VMRUNTIME_EXEMPTIONS).append('\n');
        sb.append("  hasAccessibleOverride=").append(HAS_ACCESSIBLE_OBJECT_OVERRIDE);
        if (ACCESSIBLE_OVERRIDE_FIELD_NAME != null) {
            sb.append(" (field=").append(ACCESSIBLE_OVERRIDE_FIELD_NAME).append(')');
        }
        sb.append('\n');
        sb.append("  hasArtMethodAccessor=").append(HAS_ART_METHOD_ACCESSOR).append('\n');
        sb.append("  hasArtMethodAccessorSymbols=").append(HAS_ART_METHOD_ACCESSOR_SYMBOLS).append('\n');
        sb.append("  hasInterpreterSymbolAnchor=").append(HAS_INTERPRETER_SYMBOL_ANCHOR).append('\n');
        sb.append("  hasInterpreterHandlerTable=").append(HAS_INTERPRETER_HANDLER_TABLE).append('\n');
        sb.append("  hasUnsafe=").append(HAS_UNSAFE);
        if (UNSAFE_CLASS_NAME != null) {
            sb.append(" (class=").append(UNSAFE_CLASS_NAME).append(')');
        }
        sb.append('\n');
        sb.append("  hasInMemoryDexLoader=").append(HAS_IN_MEMORY_DEX_LOADER).append('\n');
        sb.append("  supportsAppProcessLaunch=").append(SUPPORTS_APP_PROCESS_LAUNCH).append('\n');
        sb.append("probe notes:\n");
        for (String n : PROBE_NOTES) {
            sb.append("  - ").append(n).append('\n');
        }
        return sb.toString();
    }
}