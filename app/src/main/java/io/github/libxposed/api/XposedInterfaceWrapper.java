package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.annotation.InternalApi;
import io.github.libxposed.annotation.SinceApi;

/**
 * The concrete base that modules extend.
 *
 * Vendored from upstream libxposed. Holds the attached framework
 * reference and forwards every call to it. A module gets a valid
 * `this` only after the framework calls attachFramework(...);
 * before that, every method throws IllegalStateException.
 *
 * getApiVersion() delegates to mBase rather than calling super,
 * because XposedInterface declares it abstract (see the header
 * of XposedInterface for why).
 */
public class XposedInterfaceWrapper implements XposedInterface {

    private XposedInterface mBase;
    private Runnable mDetachImpl;

    @InternalApi
    public final void attachFramework(@NonNull XposedInterface base,
                                      @NonNull Runnable detachImpl) {
        if (mBase != null) {
            throw new IllegalStateException("Framework already attached");
        }
        this.mBase = base;
        this.mDetachImpl = detachImpl;
    }

    private void ensureAttached() {
        if (mBase == null) {
            throw new IllegalStateException("Framework not attached");
        }
    }

    @SinceApi(102)
    public final void detach() {
        ensureAttached();
        mDetachImpl.run();
    }

    @Override
    public final int getApiVersion() {
        ensureAttached();
        return mBase.getApiVersion();
    }

    @NonNull @Override
    public final String getFrameworkName() {
        ensureAttached();
        return mBase.getFrameworkName();
    }

    @NonNull @Override
    public final String getFrameworkVersion() {
        ensureAttached();
        return mBase.getFrameworkVersion();
    }

    @Override
    public final long getFrameworkVersionCode() {
        ensureAttached();
        return mBase.getFrameworkVersionCode();
    }

    @Override
    public final long getFrameworkProperties() {
        ensureAttached();
        return mBase.getFrameworkProperties();
    }

    @NonNull @Override
    public final HookBuilder hook(@NonNull Executable origin) {
        ensureAttached();
        return mBase.hook(origin);
    }

    @NonNull @Override
    public final HookBuilder hookClassInitializer(@NonNull Class<?> origin) {
        ensureAttached();
        return mBase.hookClassInitializer(origin);
    }

    @Override
    public final boolean deoptimize(@NonNull Executable executable) {
        ensureAttached();
        return mBase.deoptimize(executable);
    }

    @NonNull @Override
    public final Invoker<?, Method> getInvoker(@NonNull Method method) {
        ensureAttached();
        return mBase.getInvoker(method);
    }

    @NonNull @Override
    public final <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        ensureAttached();
        return mBase.getInvoker(constructor);
    }

    @Override
    public final void log(int priority, @Nullable String tag, @NonNull String msg) {
        ensureAttached();
        mBase.log(priority, tag, msg);
    }

    @Override
    public final void log(int priority, @Nullable String tag, @NonNull String msg,
                          @Nullable Throwable tr) {
        ensureAttached();
        mBase.log(priority, tag, msg, tr);
    }

    @NonNull @Override
    public final SharedPreferences getRemotePreferences(@NonNull String name) {
        ensureAttached();
        return mBase.getRemotePreferences(name);
    }

    @NonNull @Override
    public final ApplicationInfo getModuleApplicationInfo() {
        ensureAttached();
        return mBase.getModuleApplicationInfo();
    }

    @NonNull @Override
    public final String[] listRemoteFiles() {
        ensureAttached();
        return mBase.listRemoteFiles();
    }

    @NonNull @Override
    public final ParcelFileDescriptor openRemoteFile(@NonNull String name)
            throws FileNotFoundException {
        ensureAttached();
        return mBase.openRemoteFile(name);
    }
}