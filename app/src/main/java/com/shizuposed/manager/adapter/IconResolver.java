package com.shizuposed.manager.adapter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import com.shizuposed.manager.R;

import java.io.File;

/**
 * IconResolver
 *
 * Resolves the real launcher icon for a module, using the same three
 * strategies ModuleAdapter already uses internally. Shared so that the
 * module list and the module detail sheet show the same icon.
 *
 * Strategies, in order:
 *   1. Installed package icon (pm.getApplicationInfo(pkg).loadIcon)
 *   2. Icon extracted from the APK file at apkPath
 *   3. Icon extracted from the cached dex copy (which is a whole-APK copy)
 *
 * Falls back to R.drawable.ic_module if all three fail.
 *
 * Results are cached in a small LruCache.
 */
public final class IconResolver {

    private static final int CACHE_SIZE = 64;
    private static final LruCache<String, Drawable> CACHE = new LruCache<>(CACHE_SIZE);

    private IconResolver() {}

    /** Resolve the icon for a module. Never returns null — falls back to ic_module. */
    public static Drawable resolve(Context context,
                                   String packageName,
                                   String apkPath) {
        if (context == null || packageName == null) {
            return context != null
                ? context.getResources().getDrawable(R.drawable.ic_module, null)
                : null;
        }

        Drawable cached = CACHE.get(packageName);
        if (cached != null) return cached;

        PackageManager pm = context.getPackageManager();
        Drawable icon = null;

        // Path 1: installed package
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            icon = ai.loadIcon(pm);
        } catch (Throwable ignored) {}

        // Path 2: APK file
        if (icon == null && apkPath != null && new File(apkPath).exists()) {
            icon = loadIconFromApk(pm, apkPath);
        }

        // Path 3: cached dex (a whole-APK copy)
        if (icon == null) {
            try {
                File cachedDex = new File(
                    context.getFilesDir(),
                    ".syscall_cache/" + packageName + ".dex");
                if (cachedDex.exists()) {
                    icon = loadIconFromApk(pm, cachedDex.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }

        if (icon == null) {
            icon = context.getResources().getDrawable(R.drawable.ic_module, null);
        }
        if (icon != null) CACHE.put(packageName, icon);
        return icon;
    }

    private static Drawable loadIconFromApk(PackageManager pm, String apkPath) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apkPath, 0);
            if (pi == null || pi.applicationInfo == null) return null;
            // Both sourceDir and publicSourceDir must be set before loadIcon,
            // otherwise it returns null on most Android versions.
            pi.applicationInfo.sourceDir = apkPath;
            pi.applicationInfo.publicSourceDir = apkPath;
            return pi.applicationInfo.loadIcon(pm);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Clear the cache. Called when modules are added/removed. */
    public static void clearCache() {
        CACHE.evictAll();
    }
}