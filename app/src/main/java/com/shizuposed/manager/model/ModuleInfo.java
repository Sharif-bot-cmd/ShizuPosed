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
    
    public boolean hasXposedInit;
    public boolean hasNativeInit;
    
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
        // Simplified - in production check PackageManager
        return packageName.startsWith("android.") || 
               packageName.startsWith("com.android.") ||
               packageName.startsWith("com.google.android.");
    }
    
    public int getHookedAppCount() {
        if (hookAllApps) return -1; // All apps
        return hookedApps != null ? hookedApps.size() : 0;
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
                ", hookAllApps=" + hookAllApps +
                ", hasXposedInit=" + hasXposedInit +
                '}';
    }
}
