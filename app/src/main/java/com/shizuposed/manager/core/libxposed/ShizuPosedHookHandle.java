package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;

/**
 * ShizuPosedHookHandle
 *
 * Implements the API 101 subset of XposedInterface.HookHandle.
 *
 * getId() and replaceHook() are API 102 additions. Since this
 * framework reports API 101, a well-behaved module will never call
 * them. Their implementations are conservative: getId() returns the
 * id passed to HookBuilder.setId() (or null), and replaceHook()
 * throws UnsupportedOperationException rather than silently doing
 * the wrong thing.
 */
final class ShizuPosedHookHandle implements XposedInterface.HookHandle {

    private final String modulePackage;
    private final Executable executable;
    private final String id;
    private volatile boolean unhooked;

    ShizuPosedHookHandle(String modulePackage,
                         Executable executable,
                         @Nullable String id) {
        this.modulePackage = modulePackage;
        this.executable = executable;
        this.id = id;
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

    @NonNull
    @Override
    public XposedInterface.HookHandle replaceHook(
            @NonNull XposedInterface.Hooker hooker) {
        throw new UnsupportedOperationException(
            "ShizuPosed reports libxposed API 101; replaceHook is a "
            + "102-only feature. Use unhook() and re-install if you "
            + "need to swap a hooker.");
    }
}