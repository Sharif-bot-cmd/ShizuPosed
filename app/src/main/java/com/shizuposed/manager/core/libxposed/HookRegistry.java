package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HookRegistry
 *
 * Per-module set of installed libxposed HookHandles, plus a
 * side-table that maps each handle to the live
 * LibXposedHookerAdapter that backs it.
 *
 * Exists so a module can be fully unhooked when:
 *   • its detach runnable runs (XposedInterfaceWrapper.detach()),
 *   • its target process is torn down,
 *   • the user removes the module.
 *
 * Keyed by module package name. Values are ShizuPosedHookHandle
 * instances whose unhook() is idempotent (see ShizuPosedHookHandle).
 *
 * THE ADAPTER MAP (API 102)
 * -------------------------
 * HookHandle.replaceHook(Hooker) is a Java-side swap: the native
 * stub is not reinstalled, so the dispatcher's per-method list
 * still holds the original LibXposedHookerAdapter. Replacing the
 * callback means mutating that adapter's hooker field. To find
 * the adapter from a HookHandle, the registry keeps a
 * ConcurrentHashMap<HookHandle, LibXposedHookerAdapter>.
 *
 * The adapter map is purely additive. The handle set (for
 * unhookAllForModule) and the adapter map (for replaceHook) are
 * independent — one is not derivable from the other, and neither
 * is authoritative over the other.
 *
 * Not intended for the classic API — classic modules hold their
 * own IXUnhook / XC_MethodHook.Unhook handles. This registry is
 * only needed because libxposed's wrapper has a single detach()
 * entry point, not a per-hook list.
 */
public final class HookRegistry {

    private static final Map<String, Set<ShizuPosedHookHandle>> HANDLES =
        new ConcurrentHashMap<>();

    /**
     * Handle -> adapter map. Populated at register() time and
     * updated by replace(). Entries are removed on unregister()
     * and on unhookAllForModule().
     */
    private static final Map<ShizuPosedHookHandle, LibXposedHookerAdapter>
        ADAPTERS = new ConcurrentHashMap<>();

    private HookRegistry() {}

    // ═════════════════════════════════════════════════════════════
    // REGISTRATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Register a handle with its backing adapter.
     *
     * The adapter is required: without it, replaceHook() has no
     * way to find the live callback. Pass null only for
     * diagnostic / test registrations that will never be
     * replaced; a production hook always has an adapter.
     */
    static void register(@Nullable String modulePackage,
                         @Nullable ShizuPosedHookHandle handle,
                         @Nullable LibXposedHookerAdapter adapter) {
        if (modulePackage == null || handle == null) return;

        HANDLES.computeIfAbsent(modulePackage,
                k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(handle);

        if (adapter != null) {
            ADAPTERS.put(handle, adapter);
        }
    }

    /**
     * Back-compat overload: register without an adapter. The
     * handle is still unhookable via unhookAllForModule, but
     * replaceHook() will throw for it (no adapter to mutate).
     *
     * @deprecated prefer the three-arg overload. Existing callers
     *             that don't use replaceHook() can keep using this.
     */
    @Deprecated
    static void register(@Nullable String modulePackage,
                         @Nullable ShizuPosedHookHandle handle) {
        register(modulePackage, handle, null);
    }

    static void unregister(@Nullable String modulePackage,
                           @Nullable ShizuPosedHookHandle handle) {
        if (handle == null) return;

        // Remove from the adapter map regardless of modulePackage,
        // so a handle whose package was already removed doesn't
        // leave a dangling adapter reference.
        ADAPTERS.remove(handle);

        if (modulePackage == null) return;
        Set<ShizuPosedHookHandle> set = HANDLES.get(modulePackage);
        if (set != null) {
            set.remove(handle);
            if (set.isEmpty()) HANDLES.remove(modulePackage);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // REPLACE (API 102)
    // ═════════════════════════════════════════════════════════════

    /**
     * Swap `old` for `replacement` in the registry, keeping the
     * same adapter.
     *
     * Called by ShizuPosedHookHandle.replaceHook() after it has
     * already mutated the adapter's hooker field. The registry
     * update is purely bookkeeping: the old handle is removed
     * from both maps, the replacement is added to both maps with
     * the same adapter.
     *
     * The adapter is shared between old and replacement, which is
     * correct — there is exactly one live adapter for the method,
     * and both handles point at it. Unhooking either handle
     * removes the live install (they share an InstallRecord).
     */
    static void replace(@Nullable String modulePackage,
                        @Nullable ShizuPosedHookHandle oldHandle,
                        @Nullable ShizuPosedHookHandle replacement,
                        @Nullable LibXposedHookerAdapter adapter) {
        if (oldHandle != null) {
            ADAPTERS.remove(oldHandle);
            if (modulePackage != null) {
                Set<ShizuPosedHookHandle> set = HANDLES.get(modulePackage);
                if (set != null) {
                    set.remove(oldHandle);
                    if (set.isEmpty()) HANDLES.remove(modulePackage);
                }
            }
        }
        if (replacement != null) {
            register(modulePackage, replacement, adapter);
        }
    }

    /**
     * The adapter backing a handle, or null if the handle was
     * registered without one (deprecated two-arg register) or
     * has already been unregistered.
     */
    @Nullable
    static LibXposedHookerAdapter adapterFor(
            @Nullable ShizuPosedHookHandle handle) {
        return handle != null ? ADAPTERS.get(handle) : null;
    }

    // ═════════════════════════════════════════════════════════════
    // BULK UNHOOK
    // ═════════════════════════════════════════════════════════════

    /**
     * Unhook every libxposed hook installed by this module and
     * clear the registry entry.
     *
     * Both maps are cleared: the handle set for the module, and
     * every adapter entry pointing at one of those handles.
     * Iterating the handle set and letting each unhook() call
     * unregister() would also clear the adapters (unregister()
     * removes from both maps), but removing the set up front is
     * both faster and makes the operation atomic with respect to
     * a concurrent register() for the same module.
     */
    public static void unhookAllForModule(@Nullable String modulePackage) {
        if (modulePackage == null) return;

        Set<ShizuPosedHookHandle> set = HANDLES.remove(modulePackage);
        if (set == null || set.isEmpty()) return;

        // Drop the adapter entries first so a concurrent
        // replaceHook() can't resurrect a handle we're about to
        // unhook.
        for (ShizuPosedHookHandle h : set) {
            ADAPTERS.remove(h);
        }

        int ok = 0, failed = 0;
        for (ShizuPosedHookHandle h : set) {
            try {
                h.unhook();
                ok++;
            } catch (Throwable t) {
                failed++;
                android.util.Log.w("ShizuPosed",
                    "unhookAllForModule(" + modulePackage + "): "
                    + t.getMessage());
            }
        }
        android.util.Log.i("ShizuPosed",
            "unhookAllForModule(" + modulePackage + "): "
            + ok + " unhooked, " + failed + " failed");
    }

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    /** Diagnostic: how many handles are registered for this module. */
    public static int countForModule(@Nullable String modulePackage) {
        if (modulePackage == null) return 0;
        Set<ShizuPosedHookHandle> set = HANDLES.get(modulePackage);
        return set == null ? 0 : set.size();
    }

    /** Diagnostic: how many adapters the registry is tracking. */
    public static int adapterCount() {
        return ADAPTERS.size();
    }

    /** Diagnostic: how many distinct modules have live handles. */
    public static int moduleCount() {
        return HANDLES.size();
    }
}