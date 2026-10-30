package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shizuposed.manager.model.ModuleInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;

/**
 * ShizuPosedHookBuilder
 *
 * Implements XposedInterface.HookBuilder for the libxposed API.
 *
 * Bridges a libxposed hooker (Hooker.intercept(Chain)) into the
 * classic XC_MethodHook shape that XposedHookBridge already
 * understands, so the hook lands in the same backend chain
 * (Pine -> Amiru -> Native -> ...) as a classic hook.
 *
 * The Chain returned to the hooker wraps the classic
 * MethodHookParam, giving the hooker libxposed semantics
 * (proceed() / proceedWith()) while the underlying framework
 * invokes the original method through the same code path.
 */
final class ShizuPosedHookBuilder implements XposedInterface.HookBuilder {

    private final ModuleInfo module;
    private final Executable executable;
    private final ClassLoader moduleClassLoader;

    private int priority = XposedInterface.PRIORITY_DEFAULT;
    private XposedInterface.ExceptionMode exceptionMode =
        XposedInterface.ExceptionMode.DEFAULT;
    private String id;

    ShizuPosedHookBuilder(ModuleInfo module,
                          Executable executable,
                          ClassLoader moduleClassLoader) {
        this.module = module;
        this.executable = executable;
        this.moduleClassLoader = moduleClassLoader;
    }

    // ═════════════════════════════════════════════════════════════
    // BUILDER
    // ═════════════════════════════════════════════════════════════

    @Override
    public XposedInterface.HookBuilder setPriority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public XposedInterface.HookBuilder setExceptionMode(
            @NonNull XposedInterface.ExceptionMode mode) {
        this.exceptionMode = mode;
        return this;
    }

    @Override
    public XposedInterface.HookBuilder setId(@Nullable String id) {
        this.id = id;
        return this;
    }

    @NonNull
    @Override
    public XposedInterface.HookHandle intercept(
            @NonNull XposedInterface.Hooker hooker) {

        if (hooker == null) {
            throw new HookFailedError("hooker is null");
        }
        if (executable == null) {
            throw new HookFailedError(
                "Cannot hook: executable is null (class has no "
                + "static initializer, or the method was not resolved)");
        }

        // Wrap the libxposed hooker into the classic XC_MethodHook
        // the framework already handles. The executable is passed
        // explicitly so the adapter's Chain can return it without
        // depending on MethodHookParam.method being populated by
        // the dispatcher.
        LibXposedHookerAdapter classic =
            new LibXposedHookerAdapter(
                module, executable, hooker, exceptionMode, priority);

        try {
            if (executable instanceof Method) {
                com.shizuposed.manager.core.XposedHookBridge
                    .installHook((Method) executable, classic);
            } else if (executable instanceof Constructor) {
                com.shizuposed.manager.core.XposedHookBridge
                    .installConstructorHook((Constructor<?>) executable, classic);
            } else {
                throw new HookFailedError(
                    "Unsupported Executable type: "
                    + executable.getClass().getName());
            }
        } catch (HookFailedError e) {
            throw e;
        } catch (Throwable t) {
            throw new HookFailedError(
                "Hook install failed for "
                + executable.getDeclaringClass().getName()
                + "." + executable.getName(), t);
        }

        ShizuPosedHookHandle handle =
            new ShizuPosedHookHandle(module.packageName, executable, id);

        // Register the handle so HookRegistry can unhook it on
        // detach(), hot-reload teardown, or module uninstall.
        HookRegistry.register(module.packageName, handle);

        return handle;
    }
}