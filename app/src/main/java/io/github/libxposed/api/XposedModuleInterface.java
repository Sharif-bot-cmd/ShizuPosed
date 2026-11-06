package io.github.libxposed.api;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.List;

import io.github.libxposed.annotation.SinceApi;

/**
 * Lifecycle callbacks a libxposed module may override.
 *
 * Vendored from upstream libxposed. Every method has a default
 * body, so a module that only overrides onPackageLoaded loads
 * and runs correctly.
 */
public interface XposedModuleInterface {

    /** Fires after the module class is loaded, before any package work. */
    interface ModuleLoadedParam {
        boolean isSystemServer();
        @NonNull String getProcessName();
    }

    /** Fires during bootstrap, after classloader is resolved. */
    interface PackageLoadedParam {
        @NonNull String getPackageName();
        @NonNull ApplicationInfo getApplicationInfo();
        boolean isFirstPackage();
        @NonNull @RequiresApi(29) ClassLoader getDefaultClassLoader();
    }

    /** Fires after Application.onCreate. */
    interface PackageReadyParam extends PackageLoadedParam {
        @NonNull ClassLoader getClassLoader();
        @NonNull @RequiresApi(28) AppComponentFactory getAppComponentFactory();
    }

    /** Fires on a framework that can hook system_server. */
    interface SystemServerStartingParam {
        @NonNull ClassLoader getClassLoader();
    }

    /** API 102 — hot-reload pre-callback. */
    @SinceApi(102)
    interface HotReloadingParam {
        @Nullable Bundle getExtras();
        void setSavedInstanceState(@Nullable Object obj);
    }

    /** API 102 — hot-reload post-callback. */
    @SinceApi(102)
    interface HotReloadedParam extends ModuleLoadedParam {
        @Nullable Bundle getExtras();
        @Nullable Object getSavedInstanceState();
        @NonNull List<XposedInterface.HookHandle> getOldHookHandles();
    }

    default void onModuleLoaded(@NonNull ModuleLoadedParam param) {}

    @RequiresApi(29)
    default void onPackageLoaded(@NonNull PackageLoadedParam param) {}

    default void onPackageReady(@NonNull PackageReadyParam param) {}

    default void onSystemServerStarting(@NonNull SystemServerStartingParam param) {}

    @SinceApi(102)
    default boolean onHotReloading(@NonNull HotReloadingParam param) {
        return false;
    }

    @SinceApi(102)
    default void onHotReloaded(@NonNull HotReloadedParam param) {
        param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
    }
}