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
 * CACHING
 * -------
 * Two LruCaches, keyed differently:
 *
 *   • ICON_CACHE is keyed by package name. It holds the fully
 *     resolved icon for a package, whether it came from Path 1, 2,
 *     or 3. Every successful resolve() call for a given package
 *     hits this cache.
 *
 *   • APK_CACHE is keyed by APK path. It holds icons extracted
 *     directly from an APK file. Two packages that ship the same
 *     APK (split installs, shared test fixtures) share an entry.
 *
 * NEGATIVE CACHING
 * ----------------
 * The static fallback icon (ic_module) is cached under the package
 * name too. A package whose icon can't be resolved doesn't re-hit
 * PackageManager on every row bind. Without this, a scroll through
 * a list with a handful of iconless modules fires dozens of
 * getApplicationInfo() calls that all fail.
 *
 * CALLER CONTRACT
 * ---------------
 * resolve() never returns null when context is non-null. It falls
 * back to ic_module. Callers can use the returned Drawable directly.
 *
 * If context is null, resolve() returns null. This is defensive —
 * callers shouldn't pass null, but they sometimes do in early
 * lifecycle. Return null rather than NPE.
 *
 * THREAD-SAFETY
 * -------------
 * LruCache is internally synchronized. resolve() may be called from
 * any thread. The PackageManager lookups it performs are themselves
 * thread-safe.
 */
public final class IconResolver {

    /** Cache of resolved icons, keyed by package name. */
    private static final int ICON_CACHE_SIZE = 128;
    private static final LruCache<String, Drawable> ICON_CACHE =
        new LruCache<>(ICON_CACHE_SIZE);

    /** Cache of APK-extracted icons, keyed by APK path. */
    private static final int APK_CACHE_SIZE = 64;
    private static final LruCache<String, Drawable> APK_CACHE =
        new LruCache<>(APK_CACHE_SIZE);

    private IconResolver() {}

    /**
     * Resolve the icon for a module. Never returns null when context
     * is non-null — falls back to ic_module.
     */
    public static Drawable resolve(Context context,
                                   String packageName,
                                   String apkPath) {
        if (context == null) return null;

        // Fast path: cached by package name.
        if (packageName != null && !packageName.isEmpty()) {
            Drawable cached = ICON_CACHE.get(packageName);
            if (cached != null) return cached;
        }

        PackageManager pm = context.getPackageManager();
        Drawable icon = null;

        // ── Path 1: installed package ─────────────────────────────
        if (packageName != null && !packageName.isEmpty() && pm != null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                if (ai != null) {
                    icon = ai.loadIcon(pm);
                }
            } catch (Throwable ignored) {
                // Package not installed, or loadIcon threw. Fall
                // through to the APK paths.
            }
        }

        // ── Path 2: APK file ──────────────────────────────────────
        if (icon == null && apkPath != null && !apkPath.isEmpty()) {
            File apk = new File(apkPath);
            if (apk.exists()) {
                icon = loadIconFromApkCached(pm, apkPath);
            }
        }

        // ── Path 3: cached dex copy (a whole-APK copy) ────────────
        if (icon == null && packageName != null && !packageName.isEmpty()) {
            try {
                File cachedDex = new File(
                    context.getFilesDir(),
                    ".syscall_cache/" + packageName + ".dex");
                if (cachedDex.exists()) {
                    icon = loadIconFromApkCached(pm, cachedDex.getAbsolutePath());
                }
            } catch (Throwable ignored) {}
        }

        // ── Fallback ──────────────────────────────────────────────
        if (icon == null) {
            try {
                icon = context.getResources().getDrawable(R.drawable.ic_module, null);
            } catch (Throwable ignored) {
                // Even the fallback failed. Return null; caller
                // should have a secondary default.
                return null;
            }
        }

        // Cache the result, including the fallback. This is what
        // stops repeated PackageManager lookups for a module whose
        // icon can't be resolved.
        if (packageName != null && !packageName.isEmpty() && icon != null) {
            ICON_CACHE.put(packageName, icon);
        }
        return icon;
    }

    /**
     * Load an icon from an APK, using the APK-path cache.
     */
    private static Drawable loadIconFromApkCached(PackageManager pm, String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) return null;

        Drawable cached = APK_CACHE.get(apkPath);
        if (cached != null) return cached;

        Drawable loaded = loadIconFromApk(pm, apkPath);
        if (loaded != null) {
            APK_CACHE.put(apkPath, loaded);
        }
        return loaded;
    }

    private static Drawable loadIconFromApk(PackageManager pm, String apkPath) {
        if (pm == null) return null;
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(apkPath, 0);
            if (pi == null || pi.applicationInfo == null) return null;
            // Both sourceDir and publicSourceDir must be set before
            // loadIcon, otherwise it returns null on most Android
            // versions.
            pi.applicationInfo.sourceDir = apkPath;
            pi.applicationInfo.publicSourceDir = apkPath;
            return pi.applicationInfo.loadIcon(pm);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Clear both caches. Called when modules are added or removed,
     * or when the app set changes.
     */
    public static void clearCache() {
        ICON_CACHE.evictAll();
        APK_CACHE.evictAll();
    }

    /**
     * Evict a single entry by package name. Useful when a specific
     * module's APK is known to have been replaced.
     */
    public static void invalidate(String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            ICON_CACHE.remove(packageName);
        }
    }
}