package com.shizuposed.manager.core.backends;

import android.util.Log;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.NativeBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;

/**
 * AmiruBackend
 *
 * Dispatcher-level backend that routes a hook request through the
 * Amiru native engine (libamiru.so). Amiru is a per-method stub
 * generator: for each hooked method it emits a small ARM64 stub
 * that saves the incoming registers, calls a shared C dispatcher,
 * and either tail-branches to the original entry point or returns
 * a replacement value.
 *
 * PLACEMENT
 * ---------
 * Amiru sits between the two Pine backends and NativeBackend in the
 * dispatcher chain. On a device where Pine works, Amiru never runs.
 * On a device where Pine's layout assumptions fail, Amiru provides
 * a second dynamic engine. On a device where Amiru also fails, the
 * existing NativeBackend takes over.
 *
 * SLOT BOOKKEEPING
 * ----------------
 * The native side allocates a slot for each hooked method and
 * returns it. The slot -> (Method, XC_MethodHook) association is
 * held by NativeBridge, not by this class, so the native-side slot
 * table and the Java-side callback table are updated together under
 * a single lock. The dispatcher resolves slots through
 * NativeBridge.amiruGetCallback / amiruGetMethod.
 *
 * CAPABILITIES (v0.5)
 * -------------------
 *   ✓ Static methods
 *   ✓ Instance methods (thisObject populated from x0)
 *   ✓ Primitive arguments (boxed by the C side)
 *   ✓ Object arguments (handle passed through as-is)
 *   ✓ Primitive return-value replacement
 *   ✓ Object return-value replacement (jobject handle in x0)
 *   ✓ Before-hooks
 *   ✗ Constructors (stub shape assumes a method entry, not a
 *     constructor entry; not supported)
 *   ✗ After-hooks (the stub doesn't run an after-callback; the
 *     framework's dispatch path is before-only)
 *   ✗ JIT invalidation (deopt is implicit on entry patch, not a
 *     separate API)
 *
 * The backend declines any method it cannot handle, so the
 * dispatcher falls through to the next backend.
 *
 * OBJECT ARGUMENTS
 * ----------------
 * The C side's box_arg() treats 'L' and '[' shorty characters as
 * "this register value is a jobject handle, pass it through
 * as-is" and everything else as "box the raw bits in the
 * corresponding wrapper." The buildShorty() helper below must
 * therefore emit 'L' for any non-primitive parameter and '[' for
 * arrays. The previous primitive-only gate is gone.
 *
 * OBJECT RETURNS
 * --------------
 * For an object-return method, the value passed to
 * NativeBridge.setAmiruResult is the jobject handle cast to long.
 * The stub returns that value directly in x0, which is the
 * correct register for an object return under the ARM64 ABI.
 * AmiruDispatcher.jobjectHandle() is the token source; it is an
 * ART-only heuristic (identity hash) and works for the vast
 * majority of cases.
 */
public final class AmiruBackend implements HookDispatcher.Backend {

    private static final String TAG = "ShizuPosedAmiru";

    // ─── Instance-level state (required by the Backend interface) ───

    /** Per-instance availability cache, populated on first isAvailable(). */
    private volatile Boolean availableCache = null;

    // ─── Class-level state (the native side is process-global) ──────

    private static volatile boolean sProbed = false;
    private static volatile boolean sAvailable = false;
    private static volatile String sStatus = "Amiru: not probed";

    public AmiruBackend() {}

    // ═════════════════════════════════════════════════════════════
    // BACKEND INTERFACE
    // ═════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return "Amiru";
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = availableCache;
        if (cached != null) return cached;
        boolean ok = probeOnce();
        availableCache = ok;
        return ok;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) throws Throwable {
        return tryHook(original, callback);
    }

    @Override
    public boolean hookConstructor(Constructor<?> original,
                                   XC_MethodHook callback) throws Throwable {
        // Constructors are not supported in v0.5: the stub shape
        // assumes a method entry point, not a constructor entry
        // point. The fallback chain will route constructor hooks
        // to the next backend.
        return false;
    }

    @Override
    public void shutdown() {
        // Slot cleanup happens through NativeBridge on unhook. There
        // is no per-backend state to drop here; the class-level probe
        // flags are process-global on purpose.
        Log.i(TAG, "shutdown");
    }

    // ═════════════════════════════════════════════════════════════
    // AVAILABILITY — class-level probe
    // ═════════════════════════════════════════════════════════════

    /**
     * Static availability query. Runs the probe once and caches the
     * result for the process lifetime.
     */
    public static boolean isAmiruAvailable() {
        return probeOnce();
    }

    /** Human-readable status for the manager UI and logs. */
    public static String describe() {
        probeOnce();
        return sStatus;
    }

    private static synchronized boolean probeOnce() {
        if (sProbed) return sAvailable;
        sProbed = true;

        try {
            boolean loaded = NativeBridge.amiruLoadLibrary();
            if (!loaded) {
                sAvailable = false;
                sStatus = "Amiru: library unavailable";
                return false;
            }

            // Public wrapper: probeAmiruLayout() calls the
            // JNI-native amiruProbeLayout() under the hood.
            boolean ok = NativeBridge.probeAmiruLayout();
            if (!ok) {
                sAvailable = false;
                sStatus = "Amiru: layout probe failed";
                return false;
            }

            sAvailable = true;
            // Public wrapper: describeAmiruLayout() calls the
            // JNI-native amiruDescribeLayout().
            sStatus = "Amiru: " + NativeBridge.describeAmiruLayout();
            Log.i(TAG, "probe: " + sStatus);
            return true;

        } catch (Throwable t) {
            sAvailable = false;
            sStatus = "Amiru: probe threw " + t.getClass().getSimpleName();
            Log.e(TAG, "probe failed", t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HOOK INSTALL
    // ═════════════════════════════════════════════════════════════

    private static boolean tryHook(Method method, XC_MethodHook callback) {
        if (!probeOnce()) return false;
        if (method == null || callback == null) return false;

        Class<?>[] params = method.getParameterTypes();
        // Primitive-only gate removed: Amiru 0.5 handles object
        // args via box_arg(). The only unsupported parameter shape
        // would be something the C side can't classify, but every
        // Java type maps to a shorty character, so the gate is
        // gone. The buildShorty() helper emits '[' for arrays and
        // 'L' for objects.
        //
        // The isStatic gate is also gone: thisObject is now
        // populated from reg_frame[0] by amiru_dispatch().

        try {
            String shorty = buildShorty(params);
            boolean isStatic = Modifier.isStatic(method.getModifiers());

            // NativeBridge.amiruHookMethod performs three things in
            // order: (1) calls the native side, which allocates a
            // slot, builds the stub, and patches the ArtMethod entry
            // point; (2) registers the slot -> callback mapping; (3)
            // registers the slot -> Method mapping. If any step
            // fails, the slot is not returned and the caller falls
            // through to the next backend.
            int slot = NativeBridge.amiruHookMethod(
                method,
                shorty,
                isStatic,
                callback);

            if (slot < 0) {
                Log.w(TAG, "native hook install rejected for " + method);
                return false;
            }

            Log.i(TAG, "installed hook on " + method + " (slot " + slot
                + ", shorty " + shorty + ", static=" + isStatic + ")");
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "hook failed for " + method, t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * JNI shorty string, one character per parameter.
     *
     * Primitives map to their JNI shorty letter. Arrays map to '['.
     * Everything else (classes, interfaces) maps to 'L'. The C
     * side's box_arg() treats 'L' and '[' as "raw jobject handle"
     * and the rest as "box the bits in the matching wrapper."
     *
     * The string is exactly method.getParameterTypes().length
     * characters long. The C side reads it positionally.
     */
    private static String buildShorty(Class<?>[] params) {
        StringBuilder sb = new StringBuilder(params.length);
        for (Class<?> p : params) {
            if (p == null) {
                sb.append('L');
                continue;
            }
            if (p == boolean.class)   sb.append('Z');
            else if (p == byte.class) sb.append('B');
            else if (p == char.class) sb.append('C');
            else if (p == short.class) sb.append('S');
            else if (p == int.class)  sb.append('I');
            else if (p == long.class) sb.append('J');
            else if (p == float.class) sb.append('F');
            else if (p == double.class) sb.append('D');
            else if (p.isArray())     sb.append('[');
            else                      sb.append('L');
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    public static String summary() {
        return "AmiruBackend{available=" + probeOnce()
            + ", slots=" + NativeBridge.amiruRegisteredCount()
            + ", status=" + sStatus + "}";
    }
}