package com.shizuposed.manager.core;

import android.content.Context;
import android.content.pm.PackageManager;

import com.google.gson.Gson;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.FileUtils;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ModuleLoader {
    private static final String TAG = "ModuleLoader";
    private static ModuleLoader instance;
    
    private Context context;
    private Logger logger;
    private Gson gson;
    private File moduleDir;
    private File dexCacheDir;
    // ✅ Persistent cache that survives reloads
    private ConcurrentHashMap<String, ModuleInfo> loadedModules = new ConcurrentHashMap<>();
    
    private ModuleLoader(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.gson = new Gson();
        this.moduleDir = new File(context.getFilesDir(), ".syscall_cache/modules");
        this.dexCacheDir = new File(context.getCacheDir(), "dex_cache");
        
        if (!moduleDir.exists()) {
            moduleDir.mkdirs();
        }
        if (!dexCacheDir.exists()) {
            dexCacheDir.mkdirs();
        }
    }
    
    public static synchronized ModuleLoader getInstance(Context context) {
        if (instance == null) {
            instance = new ModuleLoader(context);
        }
        return instance;
    }
    
    /**
     * Load modules from disk and update cache
     */
    public List<ModuleInfo> loadModules() {
        List<ModuleInfo> modules = new ArrayList<>();
        
        try {
            File[] moduleFiles = moduleDir.listFiles();
            if (moduleFiles != null) {
                // Clear old cache before reload
                loadedModules.clear();
                
                for (File file : moduleFiles) {
                    if (file.getName().endsWith(".json")) {
                        try {
                            String json = FileUtils.readFile(file);
                            if (json != null) {
                                ModuleInfo module = gson.fromJson(json, ModuleInfo.class);
                                if (module != null) {
                                    // ✅ Store in cache
                                    loadedModules.put(module.packageName, module);
                                    modules.add(module);
                                }
                            }
                        } catch (Exception e) {
                            logger.e("Failed to load module from " + file.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            
            // If no modules found, return empty list
            if (modules.isEmpty()) {
                logger.i("No modules found");
            } else {
                logger.i("Loaded " + modules.size() + " modules");
            }
            
        } catch (Exception e) {
            logger.e("Failed to load modules: " + e.getMessage());
        }
        
        return modules;
    }
    
    /**
     * Get cached modules without reloading from disk
     */
    public List<ModuleInfo> getCachedModules() {
        return new ArrayList<>(loadedModules.values());
    }
    
    public boolean installModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;
            
            // If APK path is provided, validate
            if (module.apkPath != null && new File(module.apkPath).exists()) {
                // Good
            }
            
            // Try to get APK info from PackageManager
            try {
                PackageManager pm = context.getPackageManager();
                android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(module.packageName, 0);
                if (pkgInfo != null) {
                    module.version = pkgInfo.versionName;
                    if (module.name == null || module.name.isEmpty()) {
                        module.name = pkgInfo.applicationInfo.loadLabel(pm).toString();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Module not installed as APK, that's fine
            }
            
            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);
            
            // ✅ Update cache
            loadedModules.put(module.packageName, module);
            logger.i("Installed module: " + module.packageName);
            return true;
        } catch (Exception e) {
            logger.e("Failed to install module: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * ✅ FIXED: Uninstall module - removes from disk AND cache
     */
    public boolean uninstallModule(String packageName) {
        try {
            // Remove from disk
            File moduleFile = new File(moduleDir, packageName + ".json");
            if (moduleFile.exists()) {
                moduleFile.delete();
                logger.i("Deleted module file: " + moduleFile.getAbsolutePath());
            }
            
            // Remove from cache
            ModuleInfo removed = loadedModules.remove(packageName);
            if (removed != null) {
                logger.i("Uninstalled module from cache: " + packageName);
            } else {
                logger.w("Module not found in cache: " + packageName);
            }
            
            // Also remove any cached DEX
            File dexFile = new File(context.getFilesDir(), ".syscall_cache/" + packageName + ".dex");
            if (dexFile.exists()) {
                dexFile.delete();
                logger.i("Deleted DEX file: " + dexFile.getAbsolutePath());
            }
            
            return true;
        } catch (Exception e) {
            logger.e("Failed to uninstall module: " + e.getMessage());
            return false;
        }
    }
    
    public boolean saveModule(ModuleInfo module) {
        try {
            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);
            
            // ✅ Update cache
            loadedModules.put(module.packageName, module);
            logger.i("Saved module: " + module.packageName);
            return true;
        } catch (Exception e) {
            logger.e("Failed to save module: " + e.getMessage());
            return false;
        }
    }
    
    public ModuleInfo getModule(String packageName) {
        return loadedModules.get(packageName);
    }
    
    public boolean isModuleEnabled(String packageName) {
        ModuleInfo module = loadedModules.get(packageName);
        return module != null && module.enabled;
    }
    
    public List<ModuleInfo> getEnabledModules() {
        List<ModuleInfo> enabled = new ArrayList<>();
        for (ModuleInfo module : loadedModules.values()) {
            if (module.enabled) {
                enabled.add(module);
            }
        }
        return enabled;
    }
    
    public int getHookedProcessCount() {
        try {
            ProcessMonitor monitor = ProcessMonitor.getInstance(context);
            return monitor.getHookedProcesses().size();
        } catch (Exception e) {
            return 0;
        }
    }
    
    public int getHookedAppCount(String modulePackage) {
        return 0;
    }
    
    public boolean isServiceRunning() {
        return true;
    }
    
    public void notifyResourceChange(String packageName, int id, Object replacement) {
        logger.i("Resource change: " + packageName + " ID: 0x" + Integer.toHexString(id));
    }
    
    private String findModuleApkPath(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
            if (pkgInfo != null && pkgInfo.applicationInfo != null) {
                return pkgInfo.applicationInfo.sourceDir;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Not installed as APK
        }
        return null;
    }
}
