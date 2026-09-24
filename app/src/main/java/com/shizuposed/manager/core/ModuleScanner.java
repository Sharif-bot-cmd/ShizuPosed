package com.shizuposed.manager.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ModuleScanner
 *
 * Enumerates every installed package and checks whether its APK
 * looks like an Xposed module. Modules found this way are registered
 * with ModuleLoader so they appear in the Modules tab without the
 * user having to add them manually.
 *
 * DETECTION MARKERS
 * -----------------
 * assets/xposed_init is the canonical marker, and every mainstream
 * module ships one. But a handful of modules — mostly older ones,
 * or ones that only ship native hooks — use a different convention.
 * The scanner checks four markers in order:
 *
 *   1. assets/xposed_init       — canonical. Contains the entry class.
 *   2. assets/xposed_module     — older convention, same content.
 *   3. META-INF/xposed/module.prop
 *                               — EdXposed-era, key=value format.
 *                                 Looked for "mainClass=" or a bare
 *                                 class name.
 *   4. assets/native_init       — weak signal. The module ships
 *                                 native hooks and may have no Java
 *                                 entry point. Treated as detected
 *                                 with an "auto-detect" entry, so
 *                                 XposedHook.guessEntryPoint() runs
 *                                 at hook time.
 *
 * A module that ships none of these is not detected. That matches
 * LSPosed and classic Xposed, which also only look at xposed_init.
 *
 * PASS ORDERING
 * -------------
 * Discovery runs BEFORE purge. The reason: if purge runs first and
 * decides a module is "not installed" for any reason — a transient
 * PackageManager error, a scoped-storage restriction on its APK
 * path, a race with an in-flight app update — it removes the module
 * from the cache. The discovery pass that follows then finds the
 * module again (it is, after all, installed) and re-registers it.
 * The UI sees the re-registration and shows a "1 module detected"
 * notification.
 *
 * TWO-STRIKE PURGE
 * ----------------
 * A module is only purged after MISSED_THRESHOLD consecutive scans
 * report it as not installed. Any scan that sees the package in the
 * installed list resets the counter. This rides out the transient
 * PackageManager failure that occurs in the seconds after wake from
 * sleep, which otherwise produces spurious "N modules detected"
 * notifications.
 *
 * Additionally, the purge pass is skipped entirely when the
 * discovery pass enumerates fewer than MIN_PLAUSIBLE_PACKAGE_COUNT
 * packages, on the theory that a real Android device has more than
 * that, and a smaller list means PackageManager is mid-revalidation.
 *
 * STALE APKPATH
 * -------------
 * On modern Android, ApplicationInfo.sourceDir changes when an app
 * is updated or reinstalled: the path lives under /data/app/~~<hash>/
 * and the <hash> values differ after each install. If a module was
 * registered under one path and later reinstalled under another,
 * the JSON descriptor holds a stale path. The discovery pass detects
 * the mismatch and updates the path in place rather than
 * re-registering the module.
 *
 * Skips:
 *   • Our own package (hooking ShizuPosed from ShizuPosed is useless)
 *   • Packages already in the ModuleLoader cache with the same apkPath
 */
public final class ModuleScanner {

    private static final String TAG = "ModuleScanner";

    // ── FIX (previous round): two-strike purge threshold.
    private static final int MISSED_THRESHOLD = 2;

    // ── FIX (previous round): plausibility threshold for a
    // PackageManager enumeration. Below this, the purge pass is
    // skipped entirely.
    private static final int MIN_PLAUSIBLE_PACKAGE_COUNT = 30;

    // ── FIX: per-package consecutive miss counter.
    private static final ConcurrentHashMap<String, Integer> missedCounts =
        new ConcurrentHashMap<>();

    private ModuleScanner() {}

    /** Result of a scan. */
    public static final class ScanResult {
        public int installedPackages = 0;
        public int xposedModulesFound = 0;
        public int newlyRegistered = 0;
        public int purgedCount = 0;
        public int refreshedPaths = 0;
        public int purgeDeferred = 0;
        public final List<String> newlyRegisteredPackages = new ArrayList<>();
        public final List<String> purgedPackages = new ArrayList<>();
        /** Package names the discovery pass observed as installed. */
        public final Set<String> installedPackagesSeen = new HashSet<>();
    }

    /**
     * Run a scan. Discovers newly installed Xposed modules, refreshes
     * stale paths, and removes modules whose packages are no longer
     * installed.
     */
    public static ScanResult scanInstalledModules(Context context) {
        ScanResult result = new ScanResult();
        Logger logger = Logger.getInstance(context);
        PackageManager pm = context.getPackageManager();
        ModuleLoader loader = ModuleLoader.getInstance(context);
        String self = context.getPackageName();

        // ── 1. Discovery pass over installed packages ───────────────
        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
            );
        } catch (Throwable t) {
            logger.e("[" + TAG + "] getInstalledApplications failed: " + t.getMessage());
            installed = null;
        }

        // ── FIX: if PackageManager failed outright, skip the whole
        // scan. A null list is not evidence that modules are gone.
        if (installed == null) {
            logger.w("[" + TAG + "] Skipping scan: getInstalledApplications "
                + "returned null");
            return result;
        }

        // Snapshot of currently-registered modules keyed by package name
        Set<String> alreadyRegistered = new HashSet<>();
        for (ModuleInfo m : loader.getCachedModules()) {
            if (m != null && m.packageName != null) {
                alreadyRegistered.add(m.packageName);
            }
        }

        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null) continue;

            // Record every package we see, so the purge pass below
            // can consult this set.
            result.installedPackagesSeen.add(ai.packageName);

            if (self.equals(ai.packageName)) continue;

            result.installedPackages++;

            try {
                String apkPath = ai.sourceDir;
                if (apkPath == null) continue;

                // ── FIX: any package the discovery pass sees counts
                // as "alive" and clears its miss counter.
                resetMissedCount(ai.packageName);

                // ── Already registered? ─────────────────────────
                if (alreadyRegistered.contains(ai.packageName)) {
                    ModuleInfo existing = loader.getModule(ai.packageName);
                    if (existing != null) {
                        boolean pathMatches = existing.apkPath != null
                                && existing.apkPath.equals(apkPath);
                        if (pathMatches) {
                            continue;
                        }
                        // Path changed. Refresh in place — do not
                        // re-register, do not count as new.
                        logger.i("[" + TAG + "] Refreshing apkPath for "
                            + ai.packageName + ": "
                            + existing.apkPath + " -> " + apkPath);
                        existing.apkPath = apkPath;
                        loader.saveModule(existing);
                        result.refreshedPaths++;
                        continue;
                    }
                    logger.w("[" + TAG + "] " + ai.packageName
                        + " is registered but missing from the loader cache");
                }

                // ── Not registered: check if it's an Xposed module ──
                // ── FIX: detectModuleEntry() checks four markers in
                // preference order and returns:
                //   • a non-empty string  → the entry class name
                //   • an empty string     → module detected, but no
                //                           entry point named; the
                //                           shell side will guess
                //   • null                → not a module
                String entry = detectModuleEntry(apkPath);
                if (entry == null) continue;

                result.xposedModulesFound++;

                ModuleInfo module = new ModuleInfo();
                module.packageName = ai.packageName;
                module.name = ai.loadLabel(pm).toString();
                module.version = readVersion(pm, ai.packageName);
                // ── FIX: an empty string means "detected, no entry
                // point named." Store null so the shell side falls
                // through to guessEntryPoint().
                module.xposedInit = entry.isEmpty() ? null : entry;
                module.apkPath = apkPath;
                module.enabled = existingOrEnabled(loader, ai.packageName);
                module.hookedApps = preserveScope(loader, ai.packageName);
                module.recommendedApps = preserveRecommended(loader, ai.packageName);
                module.hasUi = hasLauncherActivity(context, ai.packageName);

                boolean ok = loader.installModule(module);
                if (ok) {
                    result.newlyRegistered++;
                    result.newlyRegisteredPackages.add(ai.packageName);
                    logger.i("[" + TAG + "] Auto-detected module: "
                        + ai.packageName
                        + " (entry=" + (entry.isEmpty()
                            ? "<auto-detect>" : entry)
                        + ", hasUi=" + module.hasUi
                        + ", recommended=" + module.getRecommendedAppCount() + ")");
                }

            } catch (Throwable t) {
                logger.d("[" + TAG + "] Skipping " + ai.packageName
                    + ": " + t.getMessage());
            }
        }

        // ── 2. Purge stale entries ──────────────────────────────────
        if (result.installedPackages < MIN_PLAUSIBLE_PACKAGE_COUNT) {
            logger.w("[" + TAG + "] Skipping purge: only "
                + result.installedPackages + " packages enumerated, "
                + "which is below the plausibility threshold ("
                + MIN_PLAUSIBLE_PACKAGE_COUNT + "). "
                + "PackageManager may be revalidating.");
        } else {
            try {
                result.purgedCount = purgeUninstalledModules(
                    context, result.purgedPackages, result.installedPackagesSeen);
            } catch (Throwable t) {
                logger.w("[" + TAG + "] Purge pass failed: " + t.getMessage());
            }
        }

        logger.i("[" + TAG + "] Scan complete: "
            + result.installedPackages + " installed packages checked, "
            + result.xposedModulesFound + " Xposed modules found, "
            + result.newlyRegistered + " registered, "
            + result.refreshedPaths + " path(s) refreshed, "
            + result.purgedCount + " purged, "
            + result.purgeDeferred + " purge(s) deferred");

        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // MISS COUNTER
    // ═════════════════════════════════════════════════════════════

    private static void resetMissedCount(String pkg) {
        if (pkg == null) return;
        missedCounts.remove(pkg);
    }

    private static int incrementMissedCount(String pkg) {
        if (pkg == null) return 0;
        Integer cur = missedCounts.get(pkg);
        int next = (cur == null ? 0 : cur) + 1;
        missedCounts.put(pkg, next);
        return next;
    }

    // ═════════════════════════════════════════════════════════════
    // UI DETECTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Does the given package declare a launcher activity?
     *
     * Two-step check:
     *   1. queryIntentActivities with MATCH_DEFAULT_ONLY.
     *   2. getLaunchIntentForPackage — catches aliases and leanback.
     *
     * Returns false on any error. Conservative default is "headless".
     */
    public static boolean hasLauncherActivity(Context context, String pkg) {
        if (context == null || pkg == null) return false;
        try {
            PackageManager pm = context.getPackageManager();

            Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(pkg);
            List<ResolveInfo> activities = pm.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (activities != null && !activities.isEmpty()) return true;

            Intent launch = pm.getLaunchIntentForPackage(pkg);
            return launch != null && launch.getComponent() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PURGE STALE MODULES
    // ═════════════════════════════════════════════════════════════

    public static int purgeUninstalledModules(Context context,
                                              List<String> purgedOut) {
        return purgeUninstalledModules(context, purgedOut, null);
    }

    /**
     * Purge modules whose packages are no longer installed.
     *
     * Two-strike rule: a module is only removed after
     * MISSED_THRESHOLD consecutive scans have reported it as
     * uninstalled. Any scan that sees the package in the installed
     * list resets its counter.
     */
    public static int purgeUninstalledModules(Context context,
                                              List<String> purgedOut,
                                              Set<String> recentlySeenPackages) {
        Logger logger = Logger.getInstance(context);
        PackageManager pm = context.getPackageManager();
        ModuleLoader loader = ModuleLoader.getInstance(context);

        int removed = 0;
        List<ModuleInfo> snapshot = new ArrayList<>(loader.getCachedModules());

        for (ModuleInfo m : snapshot) {
            if (m == null || m.packageName == null) continue;

            if (recentlySeenPackages != null
                    && recentlySeenPackages.contains(m.packageName)) {
                resetMissedCount(m.packageName);
                continue;
            }

            boolean apkExists = false;
            if (m.apkPath != null) {
                try {
                    apkExists = new File(m.apkPath).exists();
                } catch (Throwable ignored) {}
            }

            boolean packageInstalled = false;
            try {
                pm.getPackageInfo(m.packageName, 0);
                packageInstalled = true;
            } catch (PackageManager.NameNotFoundException ignored) {
                // The specific case we care about: not installed.
            } catch (Throwable t) {
                // Any other error: treat as installed to be safe.
                packageInstalled = true;
                resetMissedCount(m.packageName);
                logger.d("[" + TAG + "] PackageManager error for "
                    + m.packageName + ": " + t.getMessage()
                    + " — treating as installed");
            }

            if (apkExists || packageInstalled) {
                resetMissedCount(m.packageName);
                continue;
            }

            int misses = incrementMissedCount(m.packageName);
            if (misses < MISSED_THRESHOLD) {
                logger.i("[" + TAG + "] Purge deferred for " + m.packageName
                    + " (missedCount=" + misses + "/" + MISSED_THRESHOLD + ")");
                continue;
            }

            try {
                boolean ok = loader.uninstallModule(m.packageName);
                if (ok) {
                    removed++;
                    if (purgedOut != null) purgedOut.add(m.packageName);
                    missedCounts.remove(m.packageName);
                    logger.i("[" + TAG + "] Purged uninstalled module: "
                        + m.packageName + " (after " + misses
                        + " consecutive misses)");
                }
            } catch (Throwable t) {
                logger.w("[" + TAG + "] Failed to purge " + m.packageName
                    + ": " + t.getMessage());
            }
        }

        if (removed > 0) {
            logger.i("[" + TAG + "] Purged " + removed + " module(s)");
        }
        return removed;
    }

    // ═════════════════════════════════════════════════════════════
    // DETECTION
    // ═════════════════════════════════════════════════════════════

    /**
     * Detect whether an APK is an Xposed module and, if so, its
     * entry class.
     *
     * Returns:
     *   • non-empty string  — the entry class name (from a marker
     *                         that named one)
     *   • empty string      — module detected, no entry point named.
     *                         The shell side falls through to
     *                         XposedHook.guessEntryPoint() at hook
     *                         time.
     *   • null              — not a module
     *
     * Checks four markers in preference order:
     *   1. assets/xposed_init
     *   2. assets/xposed_module
     *   3. META-INF/xposed/module.prop
     *   4. assets/native_init (weak — no Java entry named)
     *
     * The first marker that yields a usable entry wins. If a marker
     * exists but yields no entry (empty file, all comments), the
     * scan continues to the next marker.
     */
    // ── FIX: new method replacing readXposedInit().
    private static String detectModuleEntry(String apkPath) {
        if (apkPath == null) return null;
        try (ZipFile zip = new ZipFile(apkPath)) {

            // 1. Canonical: assets/xposed_init
            String entry = readFirstMeaningfulLine(zip, "assets/xposed_init");
            if (entry != null && !entry.isEmpty()) return entry;

            // 2. Older convention: assets/xposed_module
            entry = readFirstMeaningfulLine(zip, "assets/xposed_module");
            if (entry != null && !entry.isEmpty()) return entry;

            // 3. EdXposed-era: META-INF/xposed/module.prop
            //    key=value format; look for mainClass= or a bare
            //    class name.
            entry = readModulePropEntry(zip);
            if (entry != null && !entry.isEmpty()) return entry;

            // 4. Weak signal: native_init present. No Java entry
            //    named, but this is a module. Return "" so the
            //    caller records an "auto-detect" entry and the
            //    shell side guesses at hook time.
            if (zip.getEntry("assets/native_init") != null) {
                return "";
            }

        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Read the first meaningful line from a zip entry. Skips blanks
     * and #-prefixed comments. Returns null if the entry is missing
     * or the file has no usable content.
     */
    // ── FIX: helper for the marker readers.
    private static String readFirstMeaningfulLine(ZipFile zip, String entryName) {
        try {
            ZipEntry e = zip.getEntry(entryName);
            if (e == null) return null;
            try (InputStream is = zip.getInputStream(e);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        return trimmed;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Parse META-INF/xposed/module.prop looking for the entry class.
     *
     * Two formats in the wild:
     *   • key=value, with "mainClass=" or "entry=" naming the class
     *   • a bare class name on its own line (very old)
     *
     * Returns the class name, or null if none found.
     */
    // ── FIX: helper for the EdXposed-era marker.
    private static String readModulePropEntry(ZipFile zip) {
        try {
            ZipEntry e = zip.getEntry("META-INF/xposed/module.prop");
            if (e == null) return null;

            String mainClass = null;
            String firstBareLine = null;

            try (InputStream is = zip.getInputStream(e);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String key = trimmed.substring(0, eq).trim();
                        String value = trimmed.substring(eq + 1).trim();
                        if (value.isEmpty()) continue;
                        if ("mainClass".equalsIgnoreCase(key)
                                || "entry".equalsIgnoreCase(key)
                                || "class".equalsIgnoreCase(key)) {
                            mainClass = value;
                            break;
                        }
                    } else if (firstBareLine == null) {
                        // Looks like a bare class name.
                        firstBareLine = trimmed;
                    }
                }
            }

            if (mainClass != null) return mainClass;
            return firstBareLine;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    private static String readVersion(PackageManager pm, String pkg) {
        try {
            android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0);
            return pi != null ? pi.versionName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean existingOrEnabled(ModuleLoader loader, String pkg) {
        ModuleInfo existing = loader.getModule(pkg);
        if (existing != null) return existing.enabled;
        return true;
    }

    private static Set<String> preserveScope(ModuleLoader loader, String pkg) {
        ModuleInfo existing = loader.getModule(pkg);
        if (existing != null && existing.hookedApps != null) {
            return new HashSet<>(existing.hookedApps);
        }
        return new HashSet<>();
    }

    private static Set<String> preserveRecommended(ModuleLoader loader, String pkg) {
        ModuleInfo existing = loader.getModule(pkg);
        if (existing != null && existing.recommendedApps != null) {
            return new HashSet<>(existing.recommendedApps);
        }
        return new HashSet<>();
    }
}