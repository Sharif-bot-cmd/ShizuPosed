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
    //
    // Package names the module declares as its recommended scope,
    // read from assets/scope.list in the module APK. This is the
    // LSPosed convention: the module author lists the packages the
    // module is designed for, and the scope editor surfaces them
    // with a badge and a "Recommended" quick-select chip.
    //
    // Distinct from hookedApps, which is the user's actual
    // selection. A package can be recommended without being
    // selected, and selected without being recommended.
    //
    // Empty when the module declares no scope.list. Never null.
    // Set by ModuleLoader.readRecommendedScope() at install/load
    // time, and preserved across ModuleScanner re-registration.
    // ============================================================
    // ── CHANGE: new field.
    public Set<String> recommendedApps;

    public boolean hasXposedInit;
    public boolean hasNativeInit;

    /**
     * True if the module APK declares a launcher activity.
     *
     * Used only by the scope editor: a module with a UI can select
     * its own package in its own scope, so it can be launched under
     * ShizuPosed and hook itself. Headless modules do not see their
     * own package, matching R-3.6 behavior.
     *
     * Set by ModuleScanner at install/scan time. Migrated on load
     * for records written before this field existed.
     */
    public boolean hasUi = false;

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
        // ── CHANGE: initialize recommended set so it is never null.
        this.recommendedApps = new HashSet<>();
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

    /**
     * Is this package in the module's recommended scope?
     * Null-safe on both the set and the argument.
     */
    // ── CHANGE: new helper.
    public boolean isRecommended(String packageName) {
        return packageName != null
            && recommendedApps != null
            && recommendedApps.contains(packageName);
    }

    /**
     * How many packages the module recommends. Zero when the
     * module declares no scope.list.
     */
    // ── CHANGE: new helper.
    public int getRecommendedAppCount() {
        return recommendedApps != null ? recommendedApps.size() : 0;
    }

    /**
     * Does this module declare a recommended scope at all? Used by
     * the scope editor to decide whether to show the Recommended
     * chip. An always-visible chip that does nothing is worse than
     * no chip.
     */
    // ── CHANGE: new helper.
    public boolean hasRecommendedScope() {
        return recommendedApps != null && !recommendedApps.isEmpty();
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
                '}';
    }
}