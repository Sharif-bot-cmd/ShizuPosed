package com.shizuposed.manager.core;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.stealth.XStealthPrefs;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 *   • Reading assets/scope.list to populate ModuleInfo.recommendedApps.
 *
 * UNINSTALL SEMANTICS
 * -------------------
 * Two distinct uninstall operations:
 *
 *   • uninstallModule(pkg) — legacy deregister-only path. Removes
 *     the JSON descriptor and cached dex, leaves the APK installed.
 *     Used by the scanner's automatic purge pass.
 *
 *   • uninstallModuleCompletely(ctx, pkg, callback) — full uninstall.
 *     Deregisters AND removes the APK. The APK removal goes through
 *     Shizuku's `pm uninstall` when Shizuku is authorized, which
 *     does not require user interaction. If Shizuku is not
 *     available, falls back to an ACTION_DELETE intent — the user
 *     gets the system dialog and confirms.
 *
 *     The pm uninstall call is synchronous: it blocks until the
 *     shell command returns. This is what makes the removal stick
 *     without a race — if the callback fired before pm uninstall
 *     finished, a subsequent loadModules() could re-discover the
 *     still-installed APK and re-register it.
 */
public class ModuleLoader {
    private static final String TAG = "ModuleLoader";
    private static ModuleLoader instance;

    private final Context context;
    private final Logger logger;
    private final Gson gson;

    private final File moduleDir;
    private final File dexCacheDir;
    private final File legacyDexCacheDir;

    private final ConcurrentHashMap<String, ModuleInfo> loadedModules = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

    /** Result codes for uninstallModuleCompletely(). */
    public static final int UNINSTALL_OK = 0;
    public static final int UNINSTALL_DEREGISTERED_ONLY = 1;
    public static final int UNINSTALL_BUILTIN = 2;
    public static final int UNINSTALL_FAILED = 3;

    public interface UninstallCallback {
        void onResult(int code, String message);
    }

    private ModuleLoader(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.gson = new Gson();

        this.moduleDir = new File(context.getFilesDir(), ".syscall_cache/modules");

        File external = context.getExternalFilesDir(null);
        File dexBase = external != null ? external : context.getFilesDir();
        this.dexCacheDir = new File(dexBase, ".syscall_cache");

        this.legacyDexCacheDir = new File(context.getFilesDir(), ".syscall_cache");

        ensureDir(moduleDir);
        ensureDir(dexCacheDir);

        migrateLegacyDexFiles();
    }

    public static synchronized ModuleLoader getInstance(Context context) {
        if (instance == null) {
            instance = new ModuleLoader(context);
        }
        return instance;
    }

    private void ensureDir(File dir) {
        if (dir == null) return;
        if (dir.exists() && dir.isDirectory()) return;
        if (dir.exists() && !dir.isDirectory()) {
            logger.w("ensureDir: " + dir.getAbsolutePath()
                + " exists as a file, deleting");
            try { dir.delete(); } catch (Throwable ignored) {}
        }
        boolean ok = dir.mkdirs();
        if (logger != null) {
            logger.i("ensureDir: " + dir.getAbsolutePath()
                + " created=" + ok
                + " exists=" + dir.exists()
                + " isDir=" + dir.isDirectory());
        }
    }

    private void migrateLegacyDexFiles() {
        try {
            if (legacyDexCacheDir.equals(dexCacheDir)) return;
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
        cacheLock.writeLock().lock();
        try {
            List<ModuleInfo> modules = new ArrayList<>();

            try {
                if (!moduleDir.exists() || !moduleDir.isDirectory()) {
                    logger.w("loadModules: module dir missing, recreating: "
                        + moduleDir.getAbsolutePath());
                    ensureDir(moduleDir);
                }

                File[] moduleFiles = moduleDir.listFiles();

                if (logger != null) {
                    logger.i("loadModules: dir=" + moduleDir.getAbsolutePath()
                        + " exists=" + moduleDir.exists()
                        + " isDir=" + moduleDir.isDirectory()
                        + " fileCount=" + (moduleFiles == null
                            ? "null" : String.valueOf(moduleFiles.length)));
                }

                if (moduleFiles != null) {
                    loadedModules.clear();
                    for (File file : moduleFiles) {
                        if (!file.getName().endsWith(".json")) continue;
                        try {
                            String json = FileUtils.readFile(file);
                            if (json == null) continue;
                            ModuleInfo module = gson.fromJson(json, ModuleInfo.class);
                            if (module == null || module.packageName == null) continue;

                            if (XStealthModule.PACKAGE.equals(module.packageName)) continue;

                            if (module.recommendedApps == null) {
                                module.recommendedApps = new HashSet<>();
                            }

                            boolean currentHasUi = module.hasUi;
                            if (!jsonHasField(json, "hasUi") || !currentHasUi) {
                                module.hasUi = ModuleScanner.hasLauncherActivity(
                                    context, module.packageName);
                                if (module.hasUi != currentHasUi) {
                                    try {
                                        FileUtils.writeFile(file, gson.toJson(module));
                                    } catch (Throwable t) {
                                        logger.w("hasUi writeback failed for "
                                            + module.packageName + ": " + t.getMessage());
                                    }
                                    logger.i("Corrected hasUi for "
                                        + module.packageName + " -> " + module.hasUi);
                                }
                            }

                            if (module.apkPath != null) {
                                try {
                                    Set<String> fresh = readRecommendedScope(module.apkPath);
                                    if (fresh != null && !fresh.isEmpty()) {
                                        module.recommendedApps = fresh;
                                    }
                                } catch (Throwable t) {
                                    logger.w("recommended scope refresh failed for "
                                        + module.packageName + ": " + t.getMessage());
                                }
                            }

                            if (module.cachedDexPath == null
                                    || !new File(module.cachedDexPath).exists()) {

                                File externalDex = new File(dexCacheDir,
                                    module.packageName + ".dex");
                                if (externalDex.exists()) {
                                    module.cachedDexPath = externalDex.getAbsolutePath();
                                } else {
                                    File legacyDex = new File(legacyDexCacheDir,
                                        module.packageName + ".dex");
                                    if (legacyDex.exists()) {
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
                            logger.e("Failed to load module from " + file.getName()
                                + ": " + e.getMessage());
                        }
                    }
                }

                registerXStealth(modules);

                if (modules.isEmpty()) {
                    logger.i("No modules found");
                } else {
                    logger.i("Loaded " + modules.size() + " modules");
                }
            } catch (Exception e) {
                logger.e("Failed to load modules: " + e.getMessage());
            }
            return modules;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    public List<ModuleInfo> getCachedModules() {
        cacheLock.readLock().lock();
        try {
            return new ArrayList<>(loadedModules.values());
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    private void registerXStealth(List<ModuleInfo> out) {
        try {
            ModuleInfo x = new ModuleInfo();
            x.packageName   = XStealthModule.PACKAGE;
            x.name          = "XStealth";
            x.xposedInit    = XStealthModule.ENTRY;
            x.apkPath       = null;
            x.cachedDexPath = null;
            x.enabled       = XStealthPrefs.isEnabled(context);
            x.hasUi         = true;
            x.hookedApps    = new HashSet<>();
            x.recommendedApps = new HashSet<>();

            loadedModules.put(x.packageName, x);
            out.add(x);

            if (logger != null) {
                logger.i("XStealth registered: enabled=" + x.enabled);
            }
        } catch (Throwable t) {
            if (logger != null) logger.w("registerXStealth failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // INSTALL / SAVE / UNINSTALL
    // ═════════════════════════════════════════════════════════════════

    public boolean installModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;

            if (XStealthModule.PACKAGE.equals(module.packageName)) {
                if (logger != null) {
                    logger.w("installModule: refusing to persist built-in XStealth");
                }
                return false;
            }

            if (!moduleDir.exists() || !moduleDir.isDirectory()) {
                ensureDir(moduleDir);
            }

            try {
                PackageManager pm = context.getPackageManager();
                android.content.pm.PackageInfo pkgInfo =
                    pm.getPackageInfo(module.packageName, 0);
                if (pkgInfo != null) {
                    module.version = pkgInfo.versionName;
                    if (module.name == null || module.name.isEmpty()) {
                        module.name = pkgInfo.applicationInfo.loadLabel(pm).toString();
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            String installedApkPath = findModuleApkPath(module.packageName);
            if (installedApkPath != null && !installedApkPath.isEmpty()) {
                module.apkPath = installedApkPath;
            } else {
                String callerPath = module.apkPath;
                boolean callerPathValid = callerPath != null
                    && new File(callerPath).exists();

                if (callerPathValid && isInCacheDir(callerPath)) {
                    String durable = copyApkToPrivateStorage(
                        callerPath, module.packageName);
                    if (durable != null) {
                        module.apkPath = durable;
                        if (logger != null) {
                            logger.i("installModule: copied transient APK "
                                + "to durable storage: " + durable);
                        }
                    } else {
                        if (logger != null) {
                            logger.w("installModule: could not copy APK "
                                + "to durable storage for " + module.packageName);
                        }
                    }
                } else if (!callerPathValid) {
                    if (logger != null) {
                        logger.w("installModule: no resolvable apkPath for "
                            + module.packageName + " (caller path="
                            + callerPath + ")");
                    }
                }
            }

            try {
                module.hasUi = ModuleScanner.hasLauncherActivity(
                    context, module.packageName);
            } catch (Throwable ignored) {}

            if (module.apkPath != null && new File(module.apkPath).exists()) {
                File cachedDex = new File(dexCacheDir, module.packageName + ".dex");
                if (!cachedDex.exists() || cachedDex.length() == 0) {
                    scanModuleForEntryPoints(module);
                } else {
                    module.cachedDexPath = cachedDex.getAbsolutePath();
                    cachedDex.setReadable(true, false);
                }

                try {
                    Set<String> fresh = readRecommendedScope(module.apkPath);
                    if (fresh != null && !fresh.isEmpty()) {
                        module.recommendedApps = fresh;
                    }
                } catch (Throwable t) {
                    logger.w("installModule: scope.list read failed for "
                        + module.packageName + ": " + t.getMessage());
                }
            }

            if (module.recommendedApps == null) {
                module.recommendedApps = new HashSet<>();
            }

            module.lastUpdated = System.currentTimeMillis();

            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);

            if (!moduleFile.exists() || moduleFile.length() == 0) {
                logger.e("installModule: write produced no file at "
                    + moduleFile.getAbsolutePath());
                return false;
            }

            cacheLock.writeLock().lock();
            try {
                loadedModules.put(module.packageName, module);
            } finally {
                cacheLock.writeLock().unlock();
            }

            logger.i("Installed module: " + module.packageName
                + " (dex=" + module.cachedDexPath + ", entry=" + module.xposedInit
                + ", hasUi=" + module.hasUi
                + ", manual=" + module.manuallyAdded
                + ", apkPath=" + module.apkPath
                + ", recommended=" + module.getRecommendedAppCount() + ")");
            return true;
        } catch (Exception e) {
            logger.e("Failed to install module: " + e.getMessage());
            return false;
        }
    }

    public boolean saveModule(ModuleInfo module) {
        try {
            if (module == null || module.packageName == null) return false;

            if (XStealthModule.PACKAGE.equals(module.packageName)) {
                if (logger != null) {
                    logger.d("saveModule: XStealth state is in XStealthPrefs, ignoring");
                }
                cacheLock.writeLock().lock();
                try {
                    loadedModules.put(module.packageName, module);
                } finally {
                    cacheLock.writeLock().unlock();
                }
                return true;
            }

            if (!moduleDir.exists() || !moduleDir.isDirectory()) {
                ensureDir(moduleDir);
            }

            if (module.recommendedApps == null) {
                module.recommendedApps = new HashSet<>();
            }

            module.lastUpdated = System.currentTimeMillis();

            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);

            cacheLock.writeLock().lock();
            try {
                loadedModules.put(module.packageName, module);
            } finally {
                cacheLock.writeLock().unlock();
            }

            logger.i("Saved module: " + module.packageName);
            return true;
        } catch (Exception e) {
            logger.e("Failed to save module: " + e.getMessage());
            return false;
        }
    }

    /**
     * Legacy deregister-only uninstall.
     *
     * Removes the module's descriptor, its dex cache (including any
     * ART-optimized artifacts), its shell-side mirror, and any
     * activation marker left by a previous launch. Leaves the APK
     * installed on the device.
     *
     * The dex-optimization step (DexOptimizeWrapper) writes output
     * alongside the plain dex. Depending on which optimizer ran and
     * how it was invoked, that output lands in one of two places:
     *
     *   • A sibling file (<pkg>.odex, <pkg>.vdex) next to <pkg>.dex.
     *   • An oat/ subdirectory (oat/arm64/<pkg>.odex, .vdex).
     *
     * Both are cleaned. The plain dex is also cleaned. The shell-side
     * mirror is cleaned via Shizuku, because a stale descriptor there
     * would keep the module loading into newly-bootstrapped targets
     * even after the user removed it.
     */
    public boolean uninstallModule(String packageName) {
        try {
            if (XStealthModule.PACKAGE.equals(packageName)) {
                if (logger != null) {
                    logger.w("uninstallModule: XStealth is built-in, use XStealthPrefs");
                }
                return false;
            }

            // ── 1. Descriptor ────────────────────────────────
            File moduleFile = new File(moduleDir, packageName + ".json");
            if (moduleFile.exists() && moduleFile.delete()) {
                logger.i("Deleted module file: " + moduleFile.getAbsolutePath());
            }

            // ── 2. In-memory cache ───────────────────────────
            ModuleInfo removed;
            cacheLock.writeLock().lock();
            try {
                removed = loadedModules.remove(packageName);
            } finally {
                cacheLock.writeLock().unlock();
            }

            if (removed != null) {
                logger.i("Uninstalled module from cache: " + packageName);
            } else {
                logger.w("Module not found in cache: " + packageName);
            }

            // ── 3. Local dex and any optimized derivatives ───
            deleteDexArtifacts(dexCacheDir, packageName);
            deleteDexArtifacts(legacyDexCacheDir, packageName);

            // ── 4. Staged copies on external storage ─────────
            // The staging dir mirrors what pushModulesToShellDir
            // writes locally before copying to the shell side.
            try {
                File external = context.getExternalFilesDir(null);
                if (external != null) {
                    File stagingModules = new File(external, "modules");
                    File stagedJson = new File(stagingModules,
                        packageName + ".json");
                    File stagedDex = new File(stagingModules,
                        packageName + ".dex");
                    if (stagedJson.exists() && stagedJson.delete()) {
                        logger.i("Deleted staged json: "
                            + stagedJson.getAbsolutePath());
                    }
                    if (stagedDex.exists() && stagedDex.delete()) {
                        logger.i("Deleted staged dex: "
                            + stagedDex.getAbsolutePath());
                    }
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("uninstallModule: staged cleanup failed: "
                        + t.getMessage());
                }
            }

            // ── 5. Imported APK (manually-added modules) ─────
            try {
                File importedApk = new File(
                    new File(context.getFilesDir(), "imported_modules"),
                    packageName + ".apk");
                if (importedApk.exists() && importedApk.delete()) {
                    logger.i("Deleted imported APK: "
                        + importedApk.getAbsolutePath());
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("uninstallModule: imported APK cleanup failed: "
                        + t.getMessage());
                }
            }

            // ── 6. Shell-side mirror and marker ──────────────
            cleanupShellSide(packageName);

            return true;
        } catch (Exception e) {
            logger.e("Failed to uninstall module: " + e.getMessage());
            return false;
        }
    }

    /**
     * Delete the plain dex and every optimized derivative for this
     * package under the given base directory.
     *
     * Covers:
     *   • <base>/<pkg>.dex          (plain copy of the APK-as-dex)
     *   • <base>/<pkg>.odex         (DexOptimizeWrapper output)
     *   • <base>/<pkg>.vdex         (ART verification output)
     *   • <base>/oat/arm64/<pkg>.odex, .vdex
     *   • <base>/oat/arm/<pkg>.odex, .vdex
     *
     * The oat subdirectory layout is what the Android dexopt tools
     * use when they're invoked in their default mode; the sibling
     * layout is what DexOptimizeWrapper writes when it runs the
     * optimizer directly. Both are cleaned.
     */
    private void deleteDexArtifacts(File baseDir, String packageName) {
        if (baseDir == null || packageName == null) return;
        if (!baseDir.exists() || !baseDir.isDirectory()) return;

        String[] suffixes = { ".dex", ".odex", ".vdex" };

        for (String suffix : suffixes) {
            File f = new File(baseDir, packageName + suffix);
            if (f.exists() && f.delete()) {
                if (logger != null) {
                    logger.i("Deleted " + f.getAbsolutePath());
                }
            }
        }

        // oat/<arch>/<pkg>.odex and .vdex
        String[] archDirs = { "arm64", "arm" };
        for (String arch : archDirs) {
            File oatDir = new File(baseDir, "oat/" + arch);
            if (!oatDir.exists() || !oatDir.isDirectory()) continue;

            for (String suffix : new String[]{ ".odex", ".vdex" }) {
                File f = new File(oatDir, packageName + suffix);
                if (f.exists() && f.delete()) {
                    if (logger != null) {
                        logger.i("Deleted " + f.getAbsolutePath());
                    }
                }
            }
        }
    }

    private void cleanupShellSide(String packageName) {
        if (packageName == null) return;
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(context);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                if (logger != null) {
                    logger.d("cleanupShellSide: Shizuku not authorized, "
                        + "skipping shell-side cleanup for " + packageName);
                }
                return;
            }

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null || base.isEmpty()) return;

            String modulesDir = base + "/modules";
            String hookedDir  = base + "/hooked";

            StringBuilder script = new StringBuilder();
            script.append("rm -f \"")
                  .append(modulesDir).append("/").append(packageName)
                  .append(".json\" \"")
                  .append(modulesDir).append("/").append(packageName)
                  .append(".dex\" \"")
                  .append(modulesDir).append("/").append(packageName)
                  .append(".odex\" \"")
                  .append(modulesDir).append("/").append(packageName)
                  .append(".vdex\" \"")
                  .append(hookedDir).append("/").append(packageName)
                  .append(".json\"; ");
            script.append("echo CLEANED");

            String cmd = "/system/bin/sh -c '"
                + script.toString().replace("'", "'\\''") + "'";

            com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                sh.executeCommand(cmd);

            if (r != null && r.isSuccess()
                    && r.getStdoutString() != null
                    && r.getStdoutString().contains("CLEANED")) {
                if (logger != null) {
                    logger.i("cleanupShellSide: removed shell-side artifacts for "
                        + packageName);
                }
            } else if (logger != null) {
                logger.w("cleanupShellSide: rm returned non-success for "
                    + packageName
                    + " stdout=[" + (r != null ? r.getStdoutString() : "null")
                    + "] stderr=[" + (r != null ? r.getStderrString() : "null")
                    + "]");
            }

            // Refresh the marker mirror so the status provider stops
            // seeing the removed marker immediately.
            try {
                MarkerCache.refresh(context);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.d("cleanupShellSide: MarkerCache.refresh failed: "
                        + t.getMessage());
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("cleanupShellSide failed for " + packageName
                    + ": " + t.getMessage());
            }
        }
    }

    public boolean uninstallModuleCompletely(Context activityContext,
                                             String packageName,
                                             UninstallCallback callback) {
        if (packageName == null) {
            if (callback != null) {
                callback.onResult(UNINSTALL_FAILED, "null package name");
            }
            return false;
        }

        if (XStealthModule.PACKAGE.equals(packageName)) {
            if (logger != null) {
                logger.w("uninstallModuleCompletely: XStealth is built-in, "
                    + "cannot be uninstalled");
            }
            if (callback != null) {
                callback.onResult(UNINSTALL_BUILTIN,
                    "XStealth is a built-in module and cannot be uninstalled");
            }
            return false;
        }

        boolean deregistered = uninstallModule(packageName);
        if (!deregistered) {
            if (logger != null) {
                logger.w("uninstallModuleCompletely: deregistration failed for "
                    + packageName);
            }
            if (callback != null) {
                callback.onResult(UNINSTALL_FAILED,
                    "Could not deregister module");
            }
            return false;
        }

        boolean installed = isPackageInstalled(packageName);
        if (!installed) {
            if (logger != null) {
                logger.i("uninstallModuleCompletely: " + packageName
                    + " was not installed — deregistered only");
            }
            if (callback != null) {
                callback.onResult(UNINSTALL_DEREGISTERED_ONLY,
                    "Module removed (its APK was not installed)");
            }
            return true;
        }

        // ── Primary path: pm uninstall via Shizuku ────────────
        // The shell UID can remove packages without user interaction.
        // This is the only path that works for headless modules,
        // and it's the only path that produces a result the caller
        // can rely on synchronously.
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(context);
            if (sh != null && sh.isAvailable() && sh.isAuthorized()) {
                String cmd = "pm uninstall " + packageName;
                if (logger != null) {
                    logger.i("uninstallModuleCompletely: running `" + cmd + "`");
                }

                com.shizuposed.manager.utils.ShellUtils.CommandResult r =
                    sh.executeCommand(cmd);

                boolean success = r != null
                    && r.isSuccess()
                    && r.getStdoutString() != null
                    && r.getStdoutString().contains("Success");

                if (success) {
                    if (logger != null) {
                        logger.i("uninstallModuleCompletely: pm uninstall "
                            + "succeeded for " + packageName);
                    }
                    if (callback != null) {
                        callback.onResult(UNINSTALL_OK,
                            "Module removed");
                    }
                    return true;
                }

                if (logger != null) {
                    logger.w("uninstallModuleCompletely: pm uninstall "
                        + "returned non-success for " + packageName
                        + " stdout=[" + (r != null ? r.getStdoutString() : "null")
                        + "] stderr=[" + (r != null ? r.getStderrString() : "null")
                        + "]");
                }
                // Fall through to the ACTION_DELETE fallback.
            } else if (logger != null) {
                logger.i("uninstallModuleCompletely: Shizuku not authorized "
                    + "— using ACTION_DELETE fallback for " + packageName);
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("uninstallModuleCompletely: pm uninstall threw for "
                    + packageName + ": " + t.getMessage());
            }
            // Fall through to the ACTION_DELETE fallback.
        }

        // ── Fallback path: ACTION_DELETE intent ───────────────
        // Only reached if Shizuku is unavailable or pm uninstall
        // failed. Requires an Activity context — the system dialog
        // is hosted by the activity the intent is dispatched from.
        // Headless modules won't have a usable dialog; if this
        // fallback is reached for one, the user will see the dialog
        // but the module's package may be a system app that can't
        // be removed.
        if (activityContext == null) {
            if (logger != null) {
                logger.w("uninstallModuleCompletely: no activity context, "
                    + "cannot dispatch ACTION_DELETE for " + packageName);
            }
            if (callback != null) {
                callback.onResult(UNINSTALL_DEREGISTERED_ONLY,
                    "Module removed from ShizuPosed, but the APK could not "
                    + "be removed (no activity context)");
            }
            return true;
        }

        try {
            android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_DELETE);
            intent.setData(android.net.Uri.parse("package:" + packageName));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            activityContext.startActivity(intent);

            if (logger != null) {
                logger.i("uninstallModuleCompletely: dispatched "
                    + "ACTION_DELETE for " + packageName);
            }
            if (callback != null) {
                callback.onResult(UNINSTALL_DEREGISTERED_ONLY,
                    "Module removed from ShizuPosed. Confirm the system "
                    + "dialog to remove the APK.");
            }
            return true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("uninstallModuleCompletely: ACTION_DELETE failed "
                    + "for " + packageName + ": " + t.getMessage());
            }
            if (callback != null) {
                callback.onResult(UNINSTALL_DEREGISTERED_ONLY,
                    "Module removed from ShizuPosed, but the APK could not "
                    + "be removed: " + t.getMessage());
            }
            return true;
        }
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // QUERIES
    // ═════════════════════════════════════════════════════════════════

    public ModuleInfo getModule(String packageName) {
        cacheLock.readLock().lock();
        try {
            return loadedModules.get(packageName);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public boolean isModuleEnabled(String packageName) {
        cacheLock.readLock().lock();
        try {
            ModuleInfo m = loadedModules.get(packageName);
            return m != null && m.enabled;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public boolean ensureCachedDex(ModuleInfo module) {
        if (module == null || module.packageName == null) return false;

        if (module.cachedDexPath != null
                && new File(module.cachedDexPath).exists()) {
            return true;
        }

        File externalDex = new File(dexCacheDir, module.packageName + ".dex");
        if (externalDex.exists() && externalDex.length() > 0) {
            module.cachedDexPath = externalDex.getAbsolutePath();
            externalDex.setReadable(true, false);
            saveModule(module);
            if (logger != null) {
                logger.i("ensureCachedDex: repaired via external cache for "
                    + module.packageName + " -> " + module.cachedDexPath);
            }
            return true;
        }

        File legacyDex = new File(legacyDexCacheDir, module.packageName + ".dex");
        if (legacyDex.exists() && legacyDex.length() > 0) {
            File dst = new File(dexCacheDir, module.packageName + ".dex");
            try (FileInputStream in = new FileInputStream(legacyDex);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("ensureCachedDex: legacy copy failed for "
                        + module.packageName + ": " + t.getMessage());
                }
                return false;
            }
            dst.setReadable(true, false);
            module.cachedDexPath = dst.getAbsolutePath();
            saveModule(module);
            if (logger != null) {
                logger.i("ensureCachedDex: repaired via legacy cache for "
                    + module.packageName + " -> " + module.cachedDexPath);
            }
            return true;
        }

        if (module.apkPath != null && new File(module.apkPath).exists()) {
            try {
                scanModuleForEntryPoints(module);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("ensureCachedDex: re-scan failed for "
                        + module.packageName + ": " + t.getMessage());
                }
            }
            if (module.cachedDexPath != null
                    && new File(module.cachedDexPath).exists()) {
                saveModule(module);
                if (logger != null) {
                    logger.i("ensureCachedDex: re-derived from APK for "
                        + module.packageName + " -> " + module.cachedDexPath);
                }
                return true;
            }
        }

        if (logger != null) {
            logger.w("ensureCachedDex: no dex available for "
                + module.packageName
                + " (apkPath=" + module.apkPath + ")");
        }
        return false;
    }

    public List<ModuleInfo> getEnabledModules() {
        cacheLock.readLock().lock();
        try {
            List<ModuleInfo> enabled = new ArrayList<>();
            for (ModuleInfo m : loadedModules.values()) {
                if (m != null && m.enabled) enabled.add(m);
            }
            return enabled;
        } finally {
            cacheLock.readLock().unlock();
        }
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
        cacheLock.readLock().lock();
        try {
            ModuleInfo m = loadedModules.get(modulePackage);
            if (m == null || m.hookedApps == null) return 0;
            return m.hookedApps.size();
        } finally {
            cacheLock.readLock().unlock();
        }
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
    // MANUAL ADD PROTECTION HELPERS
    // ═════════════════════════════════════════════════════════════════

    private boolean isInCacheDir(String absPath) {
        if (absPath == null) return false;
        try {
            String cacheBase = context.getCacheDir().getAbsolutePath();
            return absPath.startsWith(cacheBase);
        } catch (Throwable t) {
            return false;
        }
    }

    private String copyApkToPrivateStorage(String srcPath, String packageName) {
        if (srcPath == null || packageName == null) return null;
        try {
            File src = new File(srcPath);
            if (!src.exists() || !src.isFile()) return null;

            File importedDir = new File(context.getFilesDir(), "imported_modules");
            if (!importedDir.exists() && !importedDir.mkdirs()) {
                if (logger != null) {
                    logger.w("copyApkToPrivateStorage: could not create "
                        + importedDir.getAbsolutePath());
                }
                return null;
            }

            File dst = new File(importedDir, packageName + ".apk");

            if (dst.exists() && dst.length() == src.length()) {
                return dst.getAbsolutePath();
            }

            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }

            dst.setReadable(true, true);
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("copyApkToPrivateStorage failed: " + t.getMessage());
            }
            return null;
        }
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

    // ═════════════════════════════════════════════════════════════════
    // RECOMMENDED SCOPE
    // ═════════════════════════════════════════════════════════════════

    private Set<String> readRecommendedScope(String apkPath) {
        Set<String> scope = new HashSet<>();
        if (apkPath == null) return scope;
        File apk = new File(apkPath);
        if (!apk.exists()) return scope;

        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("assets/scope.list");
            if (entry == null) return scope;

            try (InputStream is = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        scope.add(trimmed);
                    }
                }
            }

            if (logger != null) {
                logger.i("scope.list for " + apk.getName()
                    + ": " + scope.size() + " package(s)");
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("readRecommendedScope(" + apkPath + "): " + t.getMessage());
            }
        }
        return scope;
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

    // ═════════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ═════════════════════════════════════════════════════════════════

    private static boolean jsonHasField(String json, String field) {
        if (json == null || field == null) return false;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return obj.has(field);
        } catch (Throwable t) {
            return false;
        }
    }
}