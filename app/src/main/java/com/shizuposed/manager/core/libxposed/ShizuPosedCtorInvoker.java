package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import io.github.libxposed.api.XposedInterface;

/**
 * ShizuPosedCtorInvoker
 *
 * API 101 CtorInvoker implementation. The type parameter T is the
 * type the constructor produces, matching XposedInterface.CtorInvoker.
 */
final class ShizuPosedCtorInvoker<T>
        implements XposedInterface.CtorInvoker<T> {

    private final Constructor<T> constructor;

    ShizuPosedCtorInvoker(@NonNull Constructor<T> constructor) {
        this.constructor = constructor;
    }

    @NonNull
    @Override
    public XposedInterface.CtorInvoker<T> setType(
            @NonNull XposedInterface.Invoker.Type type) {
        return this;
    }

    @NonNull
    @Override
    public T newInstance(Object... args)
            throws IllegalAccessException, InstantiationException,
                   IllegalArgumentException, InvocationTargetException {
        return constructor.newInstance(args);
    }

    @NonNull
    @Override
    public <U> U newInstanceSpecial(@NonNull Class<U> cls, Object... args)
            throws IllegalAccessException, InstantiationException,
                   IllegalArgumentException, InvocationTargetException {
        Object o = constructor.newInstance(args);
        if (!cls.isInstance(o)) {
            throw new ClassCastException(
                "newInstanceSpecial: " + o.getClass().getName()
                + " is not assignable to " + cls.getName());
        }
        @SuppressWarnings("unchecked")
        U u = (U) o;
        return u;
    }

    @Override
    public Object invoke(Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException,
                   InvocationTargetException {
        try {
            return newInstance(args);
        } catch (InstantiationException e) {
            throw new InvocationTargetException(e);
        }
    }

    @Override
    public Object invokeSpecial(@NonNull Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException,
                   InvocationTargetException {
        throw new UnsupportedOperationException(
            "invokeSpecial is not meaningful on a constructor invoker");
    }
}