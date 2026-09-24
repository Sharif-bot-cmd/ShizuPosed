package com.shizuposed.manager.core;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 *   • Reading assets/scope.list to populate ModuleInfo.recommendedApps,
 *     the LSPosed-convention recommended scope used by the scope editor.
 *
 * CACHE COHERENCE
 * ---------------
 * loadModules() clears and repopulates the cache in place. Multiple
 * callers invoke it — the Modules fragment, the Repo fragment, the
 * service's worker thread. If one caller is mid-load while another
 * reads getCachedModules(), the reader sees a partial or empty map.
 *
 * ModuleScanner reads the cache to decide which modules are already
 * registered. A partial cache makes it think freshly-registered
 * modules are new, which surfaces as a spurious "N modules detected"
 * notification on app open.
 *
 * The fix is a ReentrantReadWriteLock. loadModules() holds the write
 * lock while it clears and repopulates. Every reader holds the read
 * lock. Readers never observe a load in progress, and two loads never
 * interleave.
 *
 * XStealth
 * --------
 * XStealth is a built-in module: it has no APK, no cached dex, and
 * no JSON descriptor on disk. It's synthesized into the module list
 * on every loadModules() call, with its enabled state and scope
 * read from XStealthPrefs. Because it has no on-disk representation,
 * installModule/saveModule/uninstallModule all refuse to touch it.
 *
 * RECOMMENDED SCOPE
 * -----------------
 * recommendedApps is always sourced from the APK, never trusted from
 * the JSON descriptor alone. installModule() and loadModules() both
 * call readRecommendedScope() so a module that adds or changes its
 * scope.list in an update picks up the change without a re-install.
 * When the APK read yields an empty set, the JSON's existing value
 * is preserved — a transient zip read failure must not wipe a
 * previously-known scope.
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

    // ── FIX: cache coherence lock.
    //
    // loadModules() takes the write lock and holds it across the
    // clear + repopulate sequence. Every reader takes the read lock
    // for the duration of its read. This guarantees that no reader
    // observes a partially-populated cache, and that two concurrent
    // loads do not interleave their clear/repopulate passes.
    //
    // Fairness is left off (default). Read-heavy workloads benefit;
    // a write waiting on a read will eventually get its turn because
    // ReentrantReadWriteLock does not starve writers indefinitely.
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

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
        // ── FIX: hold the write lock across the whole clear +
        // repopulate sequence. A reader that calls getCachedModules()
        // during this method blocks until the load completes, so it
        // sees either the old cache or the new one — never a partial
        // or empty intermediate state.
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

                            // Never trust a JSON that claims to be XStealth.
                            // The built-in entry below is the only source.
                            if (XStealthModule.PACKAGE.equals(module.packageName)) continue;

                            // Defensive: Gson leaves the field null if the
                            // JSON has no recommendedApps key.
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

                            // Refresh recommended scope from the APK on
                            // every load. Cheap — one zip entry read. Only
                            // overwrites the JSON value when the APK read
                            // produced a non-empty set, so a transient read
                            // failure cannot wipe a previously-known scope.
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

                // Synthesize the built-in XStealth entry from XStealthPrefs.
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
        // ── FIX: read lock. Blocks while a load is in progress.
        cacheLock.readLock().lock();
        try {
            return new ArrayList<>(loadedModules.values());
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Build the synthetic XStealth ModuleInfo from current prefs.
     * Appended to the list and inserted into the cache. Always
     * present, regardless of whether the toggle is on — the row
     * needs to exist so users can turn it on.
     */
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

            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);

            if (!moduleFile.exists() || moduleFile.length() == 0) {
                logger.e("installModule: write produced no file at "
                    + moduleFile.getAbsolutePath());
                return false;
            }

            // ── FIX: write lock for the cache mutation.
            cacheLock.writeLock().lock();
            try {
                loadedModules.put(module.packageName, module);
            } finally {
                cacheLock.writeLock().unlock();
            }

            logger.i("Installed module: " + module.packageName
                + " (dex=" + module.cachedDexPath + ", entry=" + module.xposedInit
                + ", hasUi=" + module.hasUi
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

            File moduleFile = new File(moduleDir, module.packageName + ".json");
            String json = gson.toJson(module);
            FileUtils.writeFile(moduleFile, json);

            // ── FIX: write lock.
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

    public boolean uninstallModule(String packageName) {
        try {
            if (XStealthModule.PACKAGE.equals(packageName)) {
                if (logger != null) {
                    logger.w("uninstallModule: XStealth is built-in, use XStealthPrefs");
                }
                return false;
            }

            File moduleFile = new File(moduleDir, packageName + ".json");
            if (moduleFile.exists() && moduleFile.delete()) {
                logger.i("Deleted module file: " + moduleFile.getAbsolutePath());
            }

            // ── FIX: write lock.
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
        // ── FIX: read lock.
        cacheLock.readLock().lock();
        try {
            return loadedModules.get(packageName);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public boolean isModuleEnabled(String packageName) {
        // ── FIX: read lock.
        cacheLock.readLock().lock();
        try {
            ModuleInfo m = loadedModules.get(packageName);
            return m != null && m.enabled;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public List<ModuleInfo> getEnabledModules() {
        // ── FIX: read lock.
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
        // ── FIX: read lock.
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

    /**
     * Read assets/scope.list from a module APK.
     *
     * LSPosed convention: one package name per line. Blank lines and
     * lines beginning with '#' are ignored. Returns an empty set if
     * the entry is absent, the APK is missing, or the read fails —
     * never null.
     */
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