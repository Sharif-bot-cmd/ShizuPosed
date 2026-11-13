package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import de.robv.android.xposed.XposedBridge;

/**
 * ShizuPosedInvoker
 *
 * API 101 Invoker<Method> implementation.
 *
 * ORIGIN — invokes the original method, bypassing the hook chain.
 * CHAIN  — invokes through the hook chain up to maxPriority.
 *
 * For v1, both route through XposedBridge.invokeOriginalMethod
 * because the dispatcher does not expose a "call the hook chain up
 * to N" primitive yet. When the dispatcher grows one, route CHAIN
 * here.
 */
final class ShizuPosedInvoker
        implements XposedInterface.Invoker<ShizuPosedInvoker, Method> {

    private final Method method;

    ShizuPosedInvoker(@NonNull Method method) {
        this.method = method;
    }

    @NonNull
    @Override
    public ShizuPosedInvoker setType(
            @NonNull XposedInterface.Invoker.Type type) {
        return this;
    }

    @Override
    public Object invoke(Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException,
                   InvocationTargetException {
        try {
            return XposedBridge.invokeOriginalMethod(method, obj, args);
        } catch (InvocationTargetException e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    @Override
    public Object invokeSpecial(@NonNull Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException,
                   InvocationTargetException {
        // invokeSpecial is meant to bypass virtual dispatch (i.e.
        // super.method()). The dispatcher does not expose that
        // primitive today; falling back to invoke() keeps modules
        // that call invokeSpecial in a non-virtual context working.
        return invoke(obj, args);
    }
}