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
import java.io.FileInputStream;
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
 *   • Reading installed module descriptors (JSON)
 *   • Installing / uninstalling / saving modules
 *   • Scanning APKs for entry points (assets/xposed_init) and caching the
 *     module dex so XposedHook can load it from the shell side.
 *
 * Storage layout note:
 *
 *   The module JSONs live in app-internal storage (getFilesDir) because
 *   only the manager reads them.
 *
 *   The module .dex copies live in EXTERNAL app storage
 *   (getExternalFilesDir) because the shell uid has to read them when
 *   pushing to /data/user/0/com.android.shell/files/. Shell cannot read
 *   anything under /data/user/0/<our-pkg>/files/ — SELinux forbids it.
 */
public class ModuleLoader {
    private static final String TAG = "ModuleLoader";
    private static ModuleLoader instance;

    private final Context context;
    private final Logger logger;
    private final Gson gson;

    // Module descriptors — internal storage, only the manager reads these
    private final File moduleDir;

    // Module .dex copies — external app storage so shell can read them
    private final File dexCacheDir;

    // Old internal dex cache — kept so we can migrate existing modules
    private final File legacyDexCacheDir;

    private final ConcurrentHashMap<String, ModuleInfo> loadedModules = new ConcurrentHashMap<>();

    private ModuleLoader(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.gson = new Gson();

        // Internal: module JSON descriptors
        this.moduleDir = new File(context.getFilesDir(), ".syscall_cache/modules");

        // External: module .dex copies (shell-readable)
        File external = context.getExternalFilesDir(null);
        File dexBase = external != null ? external : context.getFilesDir();
        this.dexCacheDir = new File(dexBase, ".syscall_cache");

        // Old internal dex location — used only for one-time migration
        this.legacyDexCacheDir = new File(context.getFilesDir(), ".syscall_cache");

        if (!moduleDir.exists()) moduleDir.mkdirs();
        if (!dexCacheDir.exists()) dexCacheDir.mkdirs();

        migrateLegacyDexFiles();
    }

    public static synchronized ModuleLoader getInstance(Context context) {
        if (instance == null) {
            instance = new ModuleLoader(context);
        }
        return instance;
    }

    /**
     * Copy any .dex files sitting under the old internal path into
     * external app storage so shell can read them. Runs once at startup;
     * cheap because it only touches .dex files that exist.
     */
    private void migrateLegacyDexFiles() {
        try {
            if (legacyDexCacheDir.equals(dexCacheDir)) return;   // no-op if external unavailable
            File[] files = legacyDexCacheDir.listFiles();
            if (files == null) return;

            int migrated = 0;
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".dex")) continue;
                File dst = new File(dexCacheDir, f.getName());
                if (dst.exists() && dst.length() == f.length()) continue;

                try (FileInputStream in = new FileInputStream(f);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
                dst.setReadable(true, false);
                migrated++;
            }
            if (migrated > 0) {
                logger.i("Migrated " + migrated + " dex file(s) to external storage");
            }
        } catch (Throwable t) {
            logger.w("migrateLegacyDexFiles: " + t.getMessage());
        }
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

                        // If cachedDexPath is missing or points at a file that
                        // doesn't exist, look for one under the external cache
                        // directory, then under the legacy internal one.
                        if (module.cachedDexPath == null
                                || !new File(module.cachedDexPath).exists()) {

                            File externalDex = new File(dexCacheDir, module.packageName + ".dex");
                            if (externalDex.exists()) {
                                module.cachedDexPath = externalDex.getAbsolutePath();
                            } else {
                                File legacyDex = new File(legacyDexCacheDir,
                                    module.packageName + ".dex");
                                if (legacyDex.exists()) {
                                    // Re-stage to external
                                    File dst = new File(dexCacheDir,
                                        module.packageName + ".dex");
                                    try (FileInputStream in = new FileInputStream(legacyDex);
                                         FileOutputStream out = new FileOutputStream(dst)) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                        out.flush();
                                    }
                                    dst.setReadable(true, false);
                                    module.cachedDexPath = dst.getAbsolutePath();
                                }
                            }
                        }

                        // Also patch cachedDexPath if it points at an internal
                        // file that no longer exists but an external copy does.
                        if (module.cachedDexPath != null) {
                            String cp = module.cachedDexPath;
                            String internalBase = context.getFilesDir().getAbsolutePath();
                            if (cp.startsWith(internalBase)) {
                                File externalDex = new File(dexCacheDir,
                                    module.packageName + ".dex");
                                if (externalDex.exists()) {
                                    module.cachedDexPath = externalDex.getAbsolutePath();
                                }
                            }
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

    public boolean installModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;

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
            }

            if (module.apkPath == null || !new File(module.apkPath).exists()) {
                String fromPm = findModuleApkPath(module.packageName);
                if (fromPm != null) module.apkPath = fromPm;
            }

            if (module.apkPath != null && new File(module.apkPath).exists()) {
                File cachedDex = new File(dexCacheDir, module.packageName + ".dex");
                if (!cachedDex.exists() || cachedDex.length() == 0) {
                    scanModuleForEntryPoints(module);
                } else {
                    module.cachedDexPath = cachedDex.getAbsolutePath();
                    // Make sure the external copy is shell-readable
                    cachedDex.setReadable(true, false);
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

            // Delete both possible dex copies (external and legacy internal)
            File externalDex = new File(dexCacheDir, packageName + ".dex");
            if (externalDex.exists() && externalDex.delete()) {
                logger.i("Deleted cached dex: " + externalDex.getAbsolutePath());
            }
            File legacyDex = new File(legacyDexCacheDir, packageName + ".dex");
            if (legacyDex.exists() && legacyDex.delete()) {
                logger.i("Deleted legacy dex: " + legacyDex.getAbsolutePath());
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

    public int getHookedAppCount(String modulePackage) {
        ModuleInfo m = loadedModules.get(modulePackage);
        if (m == null || m.hookedApps == null) return 0;
        return m.hookedApps.size();
    }

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

    private void scanModuleForEntryPoints(ModuleInfo module) {
        if (module == null || module.apkPath == null) return;
        File apk = new File(module.apkPath);
        if (!apk.exists()) {
            logger.w("scanModuleForEntryPoints: apk missing for " + module.packageName);
            return;
        }

        // Copy APK bytes into the external dex cache. This is what the
        // shell process will load via DexClassLoader; the whole APK is
        // a valid dex container, so we don't need to extract classes.dex.
        try {
            File cached = new File(dexCacheDir, module.packageName + ".dex");
            if (!cached.exists() || cached.length() == 0) {
                try (FileInputStream is = new FileInputStream(apk);
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
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.e("Failed to scan APK for " + module.packageName + ": " + e.getMessage());
        }
    }

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