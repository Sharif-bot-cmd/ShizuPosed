package com.shizuposed.manager.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModuleInfo implements Serializable {
    public String packageName;
    public String name;
    public String version;
    public String xposedInit;
    public String nativeInit;
    public String apkPath;
    public String cachedDexPath;
    public String description;
    public boolean enabled;
    public boolean autoDetect;
    public long installTime;
    public long lastUpdated;

    // ============================================================
    // LSPOSED-STYLE: Track which apps this module hooks
    // ============================================================
    public Set<String> hookedApps;          // Package names of apps to hook
    public boolean hookAllApps;             // If true, hook all apps
    public boolean hookSystemApps;          // If true, hook system apps

    // ============================================================
    // RECOMMENDED SCOPE
    // ============================================================
    public Set<String> recommendedApps;

    public boolean hasXposedInit;
    public boolean hasNativeInit;

    public boolean hasUi = false;

    // ============================================================
    // MANUAL ADD PROTECTION (R-6.5+)
    // ============================================================
    public boolean manuallyAdded = false;

    // ============================================================
    // XPOSED API VERSION (LSPosed-style)
    //
    // apiVersion       — the module's declared xposedminversion
    //                    (from AndroidManifest meta-data, or
    //                    minApiVersion in META-INF/xposed/module.prop).
    // targetApiVersion — the module's declared target API, if any.
    //
    // -1 means "not declared / unknown." The row and detail sheet
    // hide the badge when -1.
    //
    // Values above 100 (ShizuPosed's supported ceiling) render as
    // "API N (unsupported)" so the user can see at a glance why a
    // module refuses to activate.
    // ============================================================
    public int apiVersion = -1;
    public int targetApiVersion = -1;
    public int xposedMinVersion = -1;

    /** Ceiling of what ShizuPosed's Xposed API shim advertises. */
    public static final int SHIZUPOSED_API_MAX = 100;

    public ModuleInfo() {
        this.enabled = true;
        this.autoDetect = true;
        this.installTime = System.currentTimeMillis();
        this.lastUpdated = System.currentTimeMillis();
        this.hasXposedInit = false;
        this.hasNativeInit = false;
        this.hookedApps = new HashSet<>();
        this.hookAllApps = false;
        this.hookSystemApps = false;
        this.hasUi = false;
        this.recommendedApps = new HashSet<>();
        this.manuallyAdded = false;
        this.apiVersion = -1;
        this.targetApiVersion = -1;
        this.xposedMinVersion = -1;
    }

    public ModuleInfo(String packageName, String name) {
        this();
        this.packageName = packageName;
        this.name = name;
    }

    public boolean hasXposedInit() {
        return hasXposedInit && xposedInit != null && !xposedInit.isEmpty();
    }

    public boolean hasNativeInit() {
        return hasNativeInit && nativeInit != null && !nativeInit.isEmpty();
    }

    public String getEntryPoint() {
        if (hasXposedInit()) {
            return xposedInit;
        } else if (hasNativeInit()) {
            return nativeInit;
        }
        return "auto-detect";
    }

    public void addHookedApp(String packageName) {
        if (hookedApps == null) {
            hookedApps = new HashSet<>();
        }
        hookedApps.add(packageName);
    }

    public void removeHookedApp(String packageName) {
        if (hookedApps != null) {
            hookedApps.remove(packageName);
        }
    }

    public boolean isAppHooked(String packageName) {
        if (hookAllApps) return true;
        if (hookSystemApps && isSystemApp(packageName)) return true;
        return hookedApps != null && hookedApps.contains(packageName);
    }

    private boolean isSystemApp(String packageName) {
        return packageName.startsWith("android.") ||
               packageName.startsWith("com.android.") ||
               packageName.startsWith("com.google.android.");
    }

    public int getHookedAppCount() {
        if (hookAllApps) return -1; // All apps
        return hookedApps != null ? hookedApps.size() : 0;
    }

    // ============================================================
    // RECOMMENDED SCOPE HELPERS
    // ============================================================

    public boolean isRecommended(String packageName) {
        return packageName != null
            && recommendedApps != null
            && recommendedApps.contains(packageName);
    }

    public int getRecommendedAppCount() {
        return recommendedApps != null ? recommendedApps.size() : 0;
    }

    public boolean hasRecommendedScope() {
        return recommendedApps != null && !recommendedApps.isEmpty();
    }

    // ============================================================
    // API VERSION HELPERS
    // ============================================================

    /**
     * The most specific declared API version, in priority order:
     *   1. apiVersion (manifest meta-data: xposedminversion)
     *   2. xposedMinVersion (module.prop: minApiVersion)
     *   3. targetApiVersion (manifest meta-data: xposedtargetversion)
     *
     * Returns -1 if nothing was declared.
     */
    public int getDisplayApiVersion() {
        if (apiVersion > 0) return apiVersion;
        if (xposedMinVersion > 0) return xposedMinVersion;
        if (targetApiVersion > 0) return targetApiVersion;
        return -1;
    }

    /** True if we have any API version to show. */
    public boolean hasApiVersion() {
        return getDisplayApiVersion() > 0;
    }

    @Override
    public String toString() {
        return "ModuleInfo{" +
                "packageName='" + packageName + '\'' +
                ", name='" + name + '\'' +
                ", version='" + version + '\'' +
                ", xposedInit='" + xposedInit + '\'' +
                ", enabled=" + enabled +
                ", hookedApps=" + (hookedApps != null ? hookedApps.size() : 0) +
                ", recommendedApps=" + (recommendedApps != null ? recommendedApps.size() : 0) +
                ", hookAllApps=" + hookAllApps +
                ", hasXposedInit=" + hasXposedInit +
                ", hasUi=" + hasUi +
                ", manuallyAdded=" + manuallyAdded +
                ", apiVersion=" + apiVersion +
                ", targetApiVersion=" + targetApiVersion +
                '}';
    }
}