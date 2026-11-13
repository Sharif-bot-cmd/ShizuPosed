package com.shizuposed.manager.core.libxposed;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HookRegistry
 *
 * Per-module set of installed libxposed HookHandles.
 *
 * Exists so a module can be fully unhooked when:
 *   • its detach runnable runs (XposedInterfaceWrapper.detach()),
 *   • its target process is torn down,
 *   • the user removes the module.
 *
 * Keyed by module package name. Values are HookHandle instances
 * whose unhook() is idempotent (see ShizuPosedHookHandle).
 *
 * Not intended for the classic API — classic modules hold their own
 * IXUnhook / XC_MethodHook.Unhook handles. This registry is only
 * needed because libxposed's wrapper has a single detach() entry
 * point, not a per-hook list.
 */
public final class HookRegistry {

    private static final Map<String, Set<ShizuPosedHookHandle>> HANDLES =
        new ConcurrentHashMap<>();

    private HookRegistry() {}

    static void register(String modulePackage, ShizuPosedHookHandle handle) {
        if (modulePackage == null || handle == null) return;
        HANDLES.computeIfAbsent(modulePackage,
                k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(handle);
    }

    static void unregister(String modulePackage, ShizuPosedHookHandle handle) {
        if (modulePackage == null || handle == null) return;
        Set<ShizuPosedHookHandle> set = HANDLES.get(modulePackage);
        if (set != null) {
            set.remove(handle);
            if (set.isEmpty()) HANDLES.remove(modulePackage);
        }
    }

    /**
     * Unhook every libxposed hook installed by this module and
     * clear the registry entry.
     */
    public static void unhookAllForModule(String modulePackage) {
        if (modulePackage == null) return;
        Set<ShizuPosedHookHandle> set = HANDLES.remove(modulePackage);
        if (set == null || set.isEmpty()) return;

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

    /** Diagnostic: how many handles are registered for this module. */
    public static int countForModule(String modulePackage) {
        Set<ShizuPosedHookHandle> set = HANDLES.get(modulePackage);
        return set == null ? 0 : set.size();
    }
}