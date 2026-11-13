package com.shizuposed.manager.core.libxposed;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shizuposed.manager.model.ModuleInfo;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * ShizuPosedXposedInterface
 *
 * The concrete XposedInterface implementation handed to a libxposed
 * module via XposedInterfaceWrapper.attachFramework().
 *
 * REPORTS API 102.
 * ----------------
 * The libxposed API surface this framework targets is 102. The
 * 102-only features — HookBuilder.setId, HookHandle.getId,
 * HookHandle.replaceHook, onHotReloading, onHotReloaded — are all
 * implemented as of 1.4.9, EXCEPT onHotReloading / onHotReloaded,
 * which are declared for source compatibility but never invoked
 * because ShizuPosed does not implement hot reload. That matches
 * the pattern already used for onSystemServerStarting: the callback
 * exists on the interface, the framework doesn't call it, and the
 * release notes say so.
 *
 * A module that guards on getApiVersion() >= 102 now takes the 102
 * branch. A module written against 101 that doesn't touch a 102
 * entry point works unchanged — none of the 101 surface moved.
 *
 * CAPABILITY BITS.
 * ----------------
 * getFrameworkProperties() reports:
 *   PROP_CAP_SYSTEM        = 0  (no system_server — structural)
 *   PROP_CAP_REMOTE        = 0  (getRemotePreferences not wired)
 *   PROP_RT_API_PROTECTION = 1  (XStealth's ApiProtectionCheck is on)
 */
public final class ShizuPosedXposedInterface implements XposedInterface {

    private static final String TAG = "ShizuPosed";

    /** The libxposed API level this implementation reports. */
    public static final int REPORTED_API = 102;

    /** The classic Xposed API level ShizuPosed also speaks. */
    public static final int CLASSIC_API = 101;

    private final ModuleInfo module;
    private final ClassLoader moduleClassLoader;

    public ShizuPosedXposedInterface(@NonNull ModuleInfo module,
                                     @NonNull ClassLoader moduleClassLoader) {
        this.module = module;
        this.moduleClassLoader = moduleClassLoader;
    }

    // ═════════════════════════════════════════════════════════════
    // IDENTITY
    // ═════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public String getFrameworkName() {
        return "ShizuPosed";
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return "1.4.9";
    }

    @Override
    public long getFrameworkVersionCode() {
        return REPORTED_API;
    }

    @Override
    public long getFrameworkProperties() {
        return XposedInterface.PROP_RT_API_PROTECTION;
    }

    @Override
    public int getApiVersion() {
        return REPORTED_API;
    }

    // ═════════════════════════════════════════════════════════════
    // HOOKING
    // ═════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public HookBuilder hook(@NonNull Executable executable) {
        return new ShizuPosedHookBuilder(module, executable, moduleClassLoader);
    }

    @NonNull
    @Override
    public HookBuilder hookClassInitializer(@NonNull Class<?> cls) {
        try {
            Method clinit = cls.getDeclaredMethod("<clinit>");
            clinit.setAccessible(true);
            return new ShizuPosedHookBuilder(module, clinit, moduleClassLoader);
        } catch (NoSuchMethodException e) {
            return new ShizuPosedHookBuilder(module, null, moduleClassLoader);
        }
    }

    @Override
    public boolean deoptimize(@NonNull Executable executable) {
        // ShizuPosed's Pine / Amiru backends deopt implicitly as part
        // of installing a hook. There is no standalone deopt API.
        // Report honestly: false.
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // INVOKERS
    // ═════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public Invoker<?, Method> getInvoker(@NonNull Method method) {
        return new ShizuPosedInvoker(method);
    }

    @NonNull
    @Override
    public <T> CtorInvoker<T> getInvoker(@NonNull Constructor<T> constructor) {
        return new ShizuPosedCtorInvoker<>(constructor);
    }

    // ═════════════════════════════════════════════════════════════
    // LOGGING
    // ═════════════════════════════════════════════════════════════

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg) {
        String t = (tag != null) ? tag : (module.name != null ? module.name : TAG);
        try {
            Log.println(priority, t, msg);
        } catch (Throwable ignored) {}
    }

    @Override
    public void log(int priority, @Nullable String tag, @NonNull String msg,
                    @Nullable Throwable tr) {
        String t = (tag != null) ? tag : (module.name != null ? module.name : TAG);
        try {
            Log.println(priority, t,
                msg + (tr != null ? "\n" + Log.getStackTraceString(tr) : ""));
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // MODULE INFO
    // ═════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        try {
            android.content.Context ctx = LibXposedContext.peek();
            if (ctx != null) {
                return ctx.getPackageManager()
                    .getApplicationInfo(module.packageName, 0);
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method getPM = at.getMethod("getPackageManager");
            Object pm = getPM.invoke(null);
            Method getInfo = pm.getClass().getMethod(
                "getApplicationInfo", String.class, int.class);
            Object info = getInfo.invoke(pm, module.packageName, 0);
            if (info instanceof ApplicationInfo) return (ApplicationInfo) info;
        } catch (Throwable ignored) {}

        throw new IllegalStateException(
            "getModuleApplicationInfo: unable to resolve "
            + module.packageName + " through PackageManager");
    }

    // ═════════════════════════════════════════════════════════════
    // REMOTE IPC — NOT WIRED (PROP_CAP_REMOTE = 0)
    // ═════════════════════════════════════════════════════════════

    @NonNull
    @Override
    public SharedPreferences getRemotePreferences(@NonNull String name) {
        throw new UnsupportedOperationException(
            "ShizuPosed reports PROP_CAP_REMOTE=0; remote preferences "
            + "are not implemented. Use XSharedPreferences for the "
            + "classic path, or check getFrameworkProperties() before "
            + "calling this method.");
    }

    @NonNull
    @Override
    public String[] listRemoteFiles() {
        return new String[0];
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openRemoteFile(@NonNull String name)
            throws FileNotFoundException {
        throw new FileNotFoundException(
            "ShizuPosed reports PROP_CAP_REMOTE=0; remote files are "
            + "not implemented");
    }
}