package com.shizuposed.manager.core;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.FileUtils;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ModuleLoader
 *
 * Responsible for:
 *   • Reading installed module descriptors (JSON) from app-internal storage
 *   • Installing / uninstalling / saving modules
 *   • Scanning APKs for entry points (assets/xposed_init) and caching the
 *     module dex so XposedHook can load it from the shell side.
 */
public class ModuleLoader {
    private static final String TAG = "ModuleLoader";
    private static ModuleLoader instance;

    private final Context context;
    private final Logger logger;
    private final Gson gson;
    private final File moduleDir;         // <app>/files/.syscall_cache/modules
    private final File dexCacheDir;       // <app>/files/.syscall_cache (dex next to json)
    private final ConcurrentHashMap<String, ModuleInfo> loadedModules = new ConcurrentHashMap<>();

    private ModuleLoader(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.gson = new Gson();
        this.moduleDir = new File(context.getFilesDir(), ".syscall_cache/modules");
        this.dexCacheDir = new File(context.getFilesDir(), ".syscall_cache");

        if (!moduleDir.exists()) moduleDir.mkdirs();
        if (!dexCacheDir.exists()) dexCacheDir.mkdirs();
    }

    public static synchronized ModuleLoader getInstance(Context context) {
        if (instance == null) {
            instance = new ModuleLoader(context);
        }
        return instance;
    }

    // ═════════════════════════════════════════════════════════════════
    // LOAD
    // ═════════════════════════════════════════════════════════════════

    public List<ModuleInfo> loadModules() {
        List<ModuleInfo> modules = new ArrayList<>();

        try {
            File[] moduleFiles = moduleDir.listFiles();
            if (moduleFiles != null) {
                loadedModules.clear();
                for (File file : moduleFiles) {
                    if (!file.getName().endsWith(".json")) continue;
                    try {
                        String json = FileUtils.readFile(file);
                        if (json == null) continue;
                        ModuleInfo module = gson.fromJson(json, ModuleInfo.class);
                        if (module == null || module.packageName == null) continue;

                        // Backfill missing cachedDexPath if we can find one on disk
                        if (module.cachedDexPath == null || !new File(module.cachedDexPath).exists()) {
                            File fallback = new File(dexCacheDir, module.packageName + ".dex");
                            if (fallback.exists()) module.cachedDexPath = fallback.getAbsolutePath();
                        }

                        loadedModules.put(module.packageName, module);
                        modules.add(module);
                    } catch (Exception e) {
                        logger.e("Failed to load module from " + file.getName() + ": " + e.getMessage());
                    }
                }
            }

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

    public List<ModuleInfo> getCachedModules() {
        return new ArrayList<>(loadedModules.values());
    }

    // ═════════════════════════════════════════════════════════════════
    // INSTALL / SAVE / UNINSTALL
    // ═════════════════════════════════════════════════════════════════

    /**
     * Install a new module.
     *
     * The APK at module.apkPath is scanned for assets/xposed_init and the
     * dex is cached under <files>/.syscall_cache/<pkg>.dex. The JSON is
     * written only after we've populated cachedDexPath, so any downstream
     * consumer (ShizuPosedService.pushModulesToShellDir, XposedHook) sees
     * a usable module.
     */
    public boolean installModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;

            // Fill in metadata from the installed package if possible
            try {
                PackageManager pm = context.getPackageManager();
                android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(module.packageName, 0);
                if (pkgInfo != null) {
                    module.version = pkgInfo.versionName;
                    if (module.name == null || module.name.isEmpty()) {
                        module.name = pkgInfo.applicationInfo.loadLabel(pm).toString();
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // Module isn't installed as a live package — fine, we work from the APK file
            }

            // Ensure apkPath is present
            if (module.apkPath == null || !new File(module.apkPath).exists()) {
                String fromPm = findModuleApkPath(module.packageName);
                if (fromPm != null) module.apkPath = fromPm;
            }

            // Cache the dex + discover entry point BEFORE writing JSON
            if (module.apkPath != null && new File(module.apkPath).exists()) {
                File cachedDex = new File(dexCacheDir, module.packageName + ".dex");
                if (!cachedDex.exists()) {
                    scanModuleForEntryPoints(module);
                } else {
                    module.cachedDexPath = cachedDex.getAbsolutePath();
                }
            }

            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);

            loadedModules.put(module.packageName, module);
            logger.i("Installed module: " + module.packageName
                + " (dex=" + module.cachedDexPath + ", entry=" + module.xposedInit + ")");
            return true;
        } catch (Exception e) {
            logger.e("Failed to install module: " + e.getMessage());
            return false;
        }
    }

    public boolean saveModule(ModuleInfo module) {
        try {
            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);
            loadedModules.put(module.packageName, module);
            logger.i("Saved module: " + module.packageName);
            return true;
        } catch (Exception e) {
            logger.e("Failed to save module: " + e.getMessage());
            return false;
        }
    }

    public boolean uninstallModule(String packageName) {
        try {
            File moduleFile = new File(moduleDir, packageName + ".json");
            if (moduleFile.exists() && moduleFile.delete()) {
                logger.i("Deleted module file: " + moduleFile.getAbsolutePath());
            }

            ModuleInfo removed = loadedModules.remove(packageName);
            if (removed != null) {
                logger.i("Uninstalled module from cache: " + packageName);
            } else {
                logger.w("Module not found in cache: " + packageName);
            }

            File dexFile = new File(dexCacheDir, packageName + ".dex");
            if (dexFile.exists() && dexFile.delete()) {
                logger.i("Deleted cached dex: " + dexFile.getAbsolutePath());
            }

            return true;
        } catch (Exception e) {
            logger.e("Failed to uninstall module: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // QUERIES
    // ═════════════════════════════════════════════════════════════════

    public ModuleInfo getModule(String packageName) {
        return loadedModules.get(packageName);
    }

    public boolean isModuleEnabled(String packageName) {
        ModuleInfo m = loadedModules.get(packageName);
        return m != null && m.enabled;
    }

    public List<ModuleInfo> getEnabledModules() {
        List<ModuleInfo> enabled = new ArrayList<>();
        for (ModuleInfo m : loadedModules.values()) {
            if (m != null && m.enabled) enabled.add(m);
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

    /** Real count of apps selected for a module. */
    public int getHookedAppCount(String modulePackage) {
        ModuleInfo m = loadedModules.get(modulePackage);
        if (m == null || m.hookedApps == null) return 0;
        return m.hookedApps.size();
    }

    /** Delegate to the service's static running flag. */
    public boolean isServiceRunning() {
        try {
            return ShizuPosedService.isServiceRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    public void notifyResourceChange(String packageName, int id, Object replacement) {
        logger.i("Resource change: " + packageName + " ID: 0x" + Integer.toHexString(id));
    }

    // ═════════════════════════════════════════════════════════════════
    // APK SCAN + DEX CACHE
    // ═════════════════════════════════════════════════════════════════

    /**
     * Read the APK at module.apkPath. Looks for:
     *   • assets/xposed_init       → module entry class name
     *   • assets/native_init       → optional native entry (unused for now)
     * Copies the APK bytes to <dexCacheDir>/<pkg>.dex so downstream code
     * has a stable dex path independent of the APK's install location.
     */
    private void scanModuleForEntryPoints(ModuleInfo module) {
        if (module == null || module.apkPath == null) return;
        File apk = new File(module.apkPath);
        if (!apk.exists()) {
            logger.w("scanModuleForEntryPoints: apk missing for " + module.packageName);
            return;
        }

        // 1. Cache the raw APK bytes as a .dex file. This is what the shell
        //    process will load via DexClassLoader; it's technically the whole
        //    APK, which DexClassLoader accepts as a valid dex container.
        try {
            File cached = new File(dexCacheDir, module.packageName + ".dex");
            if (!cached.exists() || cached.length() == 0) {
                try (InputStream is = new java.io.FileInputStream(apk);
                     FileOutputStream os = new FileOutputStream(cached)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.flush();
                }
                cached.setReadable(true, false);
                logger.i("Cached dex: " + cached.getAbsolutePath()
                    + " (" + cached.length() + " bytes)");
            }
            module.cachedDexPath = cached.getAbsolutePath();
        } catch (Exception e) {
            logger.e("Failed to cache dex for " + module.packageName + ": " + e.getMessage());
        }

        // 2. Read assets/xposed_init for the entry class
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals("assets/xposed_init")) {
                    try (InputStream is = zip.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line = reader.readLine();
                        if (line != null && !line.isEmpty()) {
                            module.xposedInit = line.trim();
                            logger.i("Found xposed_init for " + module.packageName
                                + ": " + module.xposedInit);
                        }
                    }
                } else if (name.equals("assets/native_init")) {
                    try (InputStream is = zip.getInputStream(entry);
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                        String line = reader.readLine();
                        if (line != null && !line.isEmpty()) {
                            logger.i("Found native_init for " + module.packageName
                                + ": " + line.trim());
                            // module.nativeInit not part of your model yet; log only
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.e("Failed to scan APK for " + module.packageName + ": " + e.getMessage());
        }
    }

    /**
     * Locate the APK for a package via PackageManager. Public so callers
     * outside this class can use it.
     */
    @Nullable
    public String findModuleApkPath(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
            if (pkgInfo != null && pkgInfo.applicationInfo != null) {
                return pkgInfo.applicationInfo.sourceDir;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        } catch (Exception e) {
            logger.e("findModuleApkPath(" + packageName + "): " + e.getMessage());
        }
        return null;
    }
}