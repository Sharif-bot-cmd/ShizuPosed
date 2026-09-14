package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.NativeBridge;
import com.shizuposed.manager.core.compat.CompatLog;
import com.shizuposed.manager.core.compat.HiddenApiBypass;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Native ART backend for HookDispatcher.
 *
 * Wraps libshizuposed.so, which patches an ArtMethod's quick-compiled
 * entry point in-process. This is the same primitive Pine uses, but
 * our own code — no third-party engine required.
 *
 * Availability:
 *   The .so must load, the runtime must probe a valid ArtMethod
 *   layout, and the shared dispatcher symbol must be resolvable.
 *   If any of those fail, hook() returns false and the dispatcher
 *   falls through to Instrumentation / Proxy / Noop.
 *
 * Scope:
 *   In-process only. NativeBackend cannot hook methods in processes
 *   ShizuPosed did not launch. It is complementary to Pine, not a
 *   replacement — Pine handles some ART versions this shim doesn't,
 *   and this shim keeps working if Pine's .so is missing or blocked.
 *
 * Hook semantics (native path):
 *   - Before-hooks run. The C dispatcher invokes the registered
 *     XC_MethodHook before the original method executes.
 *   - After-hooks do NOT run. The dispatcher tail-branches to the
 *     original trampoline and never regains control. Modules that
 *     need after-hooks should rely on Pine.
 *   - Argument marshaling is not implemented. The callback sees an
 *     empty args array. Hooks that only observe the call, or replace
 *     the return value outright, work.
 *   - setResult() from a before-hook is not yet honored by the C
 *     dispatcher (no typed result slot to write into). The return
 *     value of the original is what callers see.
 *
 * Trampolines:
 *   hookArtMethodRouted returns the trampoline address for the
 *   original entry point. We cache it per Method so a future
 *   after-hook path can call through.
 */
public final class NativeBackend implements HookDispatcher.Backend {

    private static final String TAG = "NativeBackend";

    /** Shared instance so the availability cache is not per-call. */
    static final NativeBackend INSTANCE = new NativeBackend();

    /** Method -> native trampoline address. */
    private final Map<Method, Long> trampolines = new ConcurrentHashMap<>();

    private volatile boolean available;
    private volatile boolean checked;

    @Override
    public String name() { return "Native"; }

    @Override
    public boolean isAvailable() {
        if (checked) return available;
        synchronized (this) {
            if (checked) return available;
            checked = true;
            try {
                if (!NativeBridge.isLoaded()) {
                    CompatLog.d(TAG, "libshizuposed.so not loaded");
                    available = false;
                    return false;
                }
                if (!NativeBridge.isAvailable()) {
                    CompatLog.w(TAG,
                            "libshizuposed.so loaded but no ART layout probed",
                            null);
                    available = false;
                    return false;
                }
                if (NativeBridge.dispatchStubAddress() == 0L) {
                    CompatLog.w(TAG,
                            "szp_dispatch_entry not resolvable; native hooks "
                                    + "cannot be routed", null);
                    available = false;
                    return false;
                }
                CompatLog.d(TAG, "Native ART hooker available: "
                        + NativeBridge.layoutInfo());
                available = true;
            } catch (Throwable t) {
                CompatLog.w(TAG, "NativeBackend availability probe threw", t);
                available = false;
            }
            return available;
        }
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;
        if (!isAvailable()) return false;

        if (trampolines.containsKey(original)) {
            CompatLog.w(TAG, "Already hooked: "
                    + original.getDeclaringClass().getName() + "."
                    + original.getName(), null);
            return false;
        }

        // The native layer writes to the ArtMethod entry field, which
        // on API 28+ is hidden. forceAccessible is effectively a no-op
        // for the Java Method object here (we never reflect into it),
        // but it keeps the contract parallel to PineBackend: if we
        // cannot touch the method, we do not claim success.
        if (!HiddenApiBypass.forceAccessible(original)) {
            CompatLog.w(TAG, "Cannot make "
                    + original.getDeclaringClass().getName() + "."
                    + original.getName() + " accessible", null);
            return false;
        }

        // One call does register + hook + rollback-on-failure, so the
        // C-side callback table never leaks an entry if the ArtMethod
        // patch fails.
        long trampoline = NativeBridge.hookArtMethodRouted(original, callback);
        if (trampoline == 0L) {
            CompatLog.w(TAG, "hookArtMethodRouted failed for "
                    + original.getDeclaringClass().getName() + "."
                    + original.getName(), null);
            return false;
        }

        trampolines.put(original, trampoline);
        CompatLog.d(TAG, "Hooked " + original.getDeclaringClass().getName()
                + "." + original.getName() + " via native ART (tramp=0x"
                + Long.toHexString(trampoline) + ")");
        return true;
    }

    /**
     * Reverse of hook(). Restores the original ArtMethod entry point
     * and drops the C-side callback registration. Safe to call on a
     * method that was never hooked (returns false).
     */
    public boolean unhook(Method method) {
        if (method == null) return false;
        if (!trampolines.containsKey(method)) return false;
        if (!NativeBridge.isAvailable()) return false;

        boolean ok = NativeBridge.unhookArtMethodRouted(method);
        if (ok) {
            trampolines.remove(method);
            CompatLog.d(TAG, "Unhooked "
                    + method.getDeclaringClass().getName() + "."
                    + method.getName());
        } else {
            CompatLog.w(TAG, "unhookArtMethodRouted failed for "
                    + method.getDeclaringClass().getName() + "."
                    + method.getName(), null);
        }
        return ok;
    }

    /**
     * Trampoline address for a hooked method, or 0 if not hooked.
     * Callers that want to invoke the original from native code can
     * cast this to a function pointer.
     */
    public long trampolineFor(Method method) {
        if (method == null) return 0L;
        Long v = trampolines.get(method);
        return v == null ? 0L : v;
    }

    /** True if the given method has a native hook installed by us. */
    public boolean isHooked(Method method) {
        return method != null && trampolines.containsKey(method);
    }

    /** Snapshot of currently hooked methods. */
    public Set<Method> hookedMethods() {
        return Collections.unmodifiableSet(trampolines.keySet());
    }

    /**
     * Drop all hooks installed through this backend. Called during
     * framework shutdown or when a module is disabled. Iterates a
     * snapshot so concurrent hook() calls do not corrupt the map.
     */
    public void unhookAll() {
        Method[] snapshot = trampolines.keySet().toArray(new Method[0]);
        for (Method m : snapshot) {
            unhook(m);
        }
    }
}