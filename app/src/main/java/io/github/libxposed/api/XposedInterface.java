package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.runtime.ObjectMethods;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.annotation.SinceApi;

/**
 * The libxposed API surface, vendored for ShizuPosed.
 *
 * Reports 101 (not 102). The 102 surface — hot reload,
 * framework-assigned HookHandle ids, replaceHook — is not
 * implemented. Declaring 101 means a module that guards on
 * getApiVersion() >= 102 correctly falls back.
 *
 * CHANGES FROM UPSTREAM
 * ---------------------
 *   • getApiVersion() is declared ABSTRACT, not default. Upstream
 *     provides a default body returning 102 — that's the version
 *     of upstream's interface, not of the framework implementing
 *     it. ShizuPosedXposedInterface overrides it to 101.
 *     XposedInterfaceWrapper delegates to the attached framework,
 *     so a module reading getApiVersion() from its `this` sees
 *     the framework's answer, not a hardcoded 102.
 *
 *   • No other changes. Every other method signature matches
 *     upstream exactly.
 */
public interface XposedInterface {

    // ── API revision constants ─────────────────────────────────────
    int API_101 = 101;
    int API_102 = 102;
    int LIB_API = 102;

    // ── Framework property bitmask ─────────────────────────────────
    long PROP_CAP_SYSTEM       = 1;
    long PROP_CAP_REMOTE       = 2;
    long PROP_RT_API_PROTECTION = 4;

    // ── Priorities ─────────────────────────────────────────────────
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST  = Integer.MIN_VALUE;
    int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    // ═════════════════════════════════════════════════════════════
    // Sub-interfaces
    // ═════════════════════════════════════════════════════════════

    /**
     * The invocation context a hooker receives.
     *
     * proceed() runs the original method and returns its result.
     * proceedWith(...) does the same but substitutes arguments or
     * `this`.
     */
    interface Chain {
        @NonNull Executable getExecutable();
        Object getThisObject();
        @NonNull List<Object> getArgs();
        Object getArg(int i) throws IndexOutOfBoundsException, ClassCastException;
        Object proceed() throws Throwable;
        Object proceed(@NonNull Object[] args) throws Throwable;
        Object proceedWith(@NonNull Object thisObject) throws Throwable;
        Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args)
            throws Throwable;
    }

    /** Invokes a constructor. */
    interface CtorInvoker<T> extends Invoker<CtorInvoker<T>, Constructor<T>> {
        @NonNull T newInstance(Object... args)
            throws IllegalAccessException, InstantiationException,
                   IllegalArgumentException, InvocationTargetException;

        @NonNull <U> U newInstanceSpecial(@NonNull Class<U> cls, Object... args)
            throws IllegalAccessException, InstantiationException,
                   IllegalArgumentException, InvocationTargetException;
    }

    /** What a hooker does when it throws. */
    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH
    }

    /** Fluent builder returned by hook(...). */
    interface HookBuilder {
        HookBuilder setPriority(int i);
        HookBuilder setExceptionMode(@NonNull ExceptionMode exceptionMode);
        @NonNull HookHandle intercept(@NonNull Hooker hooker);
        @SinceApi(102)
        HookBuilder setId(@Nullable String id);
    }

    /** Handle for an installed hook. */
    interface HookHandle {
        @NonNull Executable getExecutable();
        void unhook();
        @Nullable @SinceApi(102) String getId();
        @NonNull @SinceApi(102) HookHandle replaceHook(@NonNull Hooker hooker);
    }

    /** The intercept callback. */
    interface Hooker {
        Object intercept(@NonNull Chain chain) throws Throwable;
    }

    // ═════════════════════════════════════════════════════════════
    // Identity
    // ═════════════════════════════════════════════════════════════

    @NonNull String getFrameworkName();
    @NonNull String getFrameworkVersion();
    long getFrameworkVersionCode();
    long getFrameworkProperties();

    // ═════════════════════════════════════════════════════════════
    // Hooking
    // ═════════════════════════════════════════════════════════════

    @NonNull HookBuilder hook(@NonNull Executable executable);
    @NonNull HookBuilder hookClassInitializer(@NonNull Class<?> cls);
    boolean deoptimize(@NonNull Executable executable);

    // ═════════════════════════════════════════════════════════════
    // Invokers
    // ═════════════════════════════════════════════════════════════

    @NonNull Invoker<?, Method> getInvoker(@NonNull Method method);
    @NonNull <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor);

    // ═════════════════════════════════════════════════════════════
    // Logging / module info / IPC
    // ═════════════════════════════════════════════════════════════

    void log(int priority, @Nullable String tag, @NonNull String message);
    void log(int priority, @Nullable String tag, @NonNull String message,
             @Nullable Throwable throwable);

    @NonNull ApplicationInfo getModuleApplicationInfo();
    @NonNull SharedPreferences getRemotePreferences(@NonNull String name);
    @NonNull String[] listRemoteFiles();
    @NonNull ParcelFileDescriptor openRemoteFile(@NonNull String name)
        throws FileNotFoundException;

    // ═════════════════════════════════════════════════════════════
    // Invoker base
    // ═════════════════════════════════════════════════════════════

    interface Invoker<T extends Invoker<T, U>, U extends Executable> {
        T setType(@NonNull Type type);
        Object invoke(Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException,
                   InvocationTargetException;
        Object invokeSpecial(@NonNull Object obj, Object... args)
            throws IllegalAccessException, IllegalArgumentException,
                   InvocationTargetException;

        interface Type {
            Type ORIGIN = new Origin();
            Type CHAIN  = new ChainType(PRIORITY_HIGHEST);

            final class Origin implements Type {
                @Override public final String toString() { return "Origin"; }
                @Override public final int hashCode() { return 0; }
                @Override public final boolean equals(Object o) {
                    return o instanceof Origin;
                }
            }

            final class ChainType implements Type {
                private final int maxPriority;
                public ChainType(int maxPriority) { this.maxPriority = maxPriority; }
                public int maxPriority() { return maxPriority; }
                @Override public final String toString() {
                    return "Chain(maxPriority=" + maxPriority + ")";
                }
                @Override public final int hashCode() { return maxPriority; }
                @Override public final boolean equals(Object o) {
                    return o instanceof ChainType
                        && ((ChainType) o).maxPriority == maxPriority;
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Version — abstract, overridden by the implementation
    // ═════════════════════════════════════════════════════════════

    /**
     * The libxposed API revision this framework implements.
     *
     * ShizuPosed returns 101. Upstream libxposed has a default
     * body here returning 102; that's the interface's own
     * revision, not the framework's. Vendoring it with a default
     * would make an unmodified XposedInterfaceWrapper return 102
     * and mislead modules into using 102 features.
     */
    @SinceApi(101)
    int getApiVersion();
}