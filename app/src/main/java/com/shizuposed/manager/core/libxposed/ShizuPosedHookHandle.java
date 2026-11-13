package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;

/**
 * ShizuPosedHookHandle
 *
 * Implements the full XposedInterface.HookHandle surface, including
 * the API 102 additions.
 *
 * getId()
 *   Returns the id passed to HookBuilder.setId(), or null if no id
 *   was set. A 101 module that never calls setId() gets null, which
 *   is the correct answer for a hook that has no framework-assigned
 *   id.
 *
 * replaceHook(Hooker)
 *   Swaps the live callback on the same Executable without
 *   reinstalling the native stub. The method entry point already
 *   points at the stub; the stub reads the callback from the
 *   dispatcher's per-method list. Replacing the callback in that
 *   list is a Java-side swap — no native reinstall, no race with
 *   threads currently executing the old stub.
 *
 *   Returns a new HookHandle with the same id and the new callback.
 *   The old handle still resolves to the same Executable and the
 *   same backend, but its callback reference is the old one — a
 *   subsequent unhook() on either handle removes the *current*
 *   install (they share an InstallRecord).
 */
final class ShizuPosedHookHandle implements XposedInterface.HookHandle {

    private final String modulePackage;
    private final Executable executable;
    private final String id;
    private final LibXposedHookerAdapter adapter;
    private volatile boolean unhooked;

    ShizuPosedHookHandle(String modulePackage,
                         Executable executable,
                         @Nullable String id,
                         @NonNull LibXposedHookerAdapter adapter) {
        this.modulePackage = modulePackage;
        this.executable = executable;
        this.id = id;
        this.adapter = adapter;
    }

    @NonNull
    @Override
    public Executable getExecutable() {
        return executable;
    }

    @Override
    public void unhook() {
        if (unhooked) return;
        unhooked = true;

        try {
            com.shizuposed.manager.core.HookEngine.InstallRecord record =
                com.shizuposed.manager.core.HookEngine
                    .getInstallRecord(executable);
            if (record != null && record.reverse != null) {
                record.reverse.run();
            } else {
                android.util.Log.w("ShizuPosed",
                    "libxposed unhook: backend "
                    + (record != null ? record.backendName : "unknown")
                    + " cannot reverse; hook remains installed ("
                    + modulePackage + "." + executable.getName() + ")");
            }
        } catch (Throwable t) {
            android.util.Log.w("ShizuPosed",
                "libxposed unhook threw for " + modulePackage
                + "." + executable.getName() + ": " + t);
        } finally {
            HookRegistry.unregister(modulePackage, this);
        }
    }

    @Nullable
    @Override
    public String getId() {
        return id;
    }

    /**
     * API 102. Replace the live hooker on the same Executable.
     *
     * The swap is performed by mutating the LibXposedHookerAdapter
     * the dispatcher already holds. No native reinstatement, no
     * re-entry through the backend chain. The stub keeps pointing
     * at the adapter; the adapter forwards to whatever hooker it
     * currently holds.
     *
     * @param hooker the new hooker (must not be null)
     * @return a new HookHandle that shares this handle's executable,
     *         id, and backend install, but points at the new
     *         callback. Unhooking either handle removes the live
     *         install.
     */
    @NonNull
    @Override
    public XposedInterface.HookHandle replaceHook(
            @NonNull XposedInterface.Hooker hooker) {
        if (hooker == null) {
            throw new IllegalArgumentException("hooker is null");
        }

        adapter.setHooker(hooker);

        ShizuPosedHookHandle replacement =
            new ShizuPosedHookHandle(modulePackage, executable, id, adapter);
        HookRegistry.replace(modulePackage, this, replacement, adapter);
        return replacement;
    }
}