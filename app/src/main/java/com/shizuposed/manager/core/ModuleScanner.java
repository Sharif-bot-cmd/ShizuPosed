package com.shizuposed.manager.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.Collections;
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
 * ships one of the canonical Xposed module markers. Modules found
 * this way are registered with ModuleLoader so they appear in the
 * Modules tab without the user having to add them manually.
 *
 * DETECTION
 * ---------
 * Only three markers are checked, all static, all inside the APK
 * zip:
 *
 *   • assets/xposed_init        — the canonical marker
 *   • assets/xposed_module      — older convention
 *   • META-INF/xposed/module.prop — EdXposed-era
 *
 * A package that ships any of these is a module. A package that
 * ships none of them is not. No heuristics, no class loading, no
 * dex enumeration, no application-class scanning.
 *
 * This matches LSPosed's detection model: LSPosed does not guess.
 * A module declares itself by shipping one of these markers, and
 * a package that doesn't ship one isn't a module — even if its
 * application class happens to implement a hook interface, or if
 * its manifest happens to have xposedminversion meta-data.
 *
 * System packages are NOT filtered. If a system-installed app
 * genuinely ships a marker, it is a module, and the user should
 * see it. Some OEM builds pre-install Xposed-compatible hooks;
 * hiding those from the Modules tab would be wrong.
 *
 * MANUAL ADD PROTECTION
 * ---------------------
 * The purge pass skips any module whose ModuleInfo.manuallyAdded
 * flag is set. A module the user added by hand is never subject
 * to automatic cleanup.
 *
 * "NEWLY REGISTERED" IS PERSISTENT
 * --------------------------------
 * The scanner tracks which module packages it has ever reported in
 * a SharedPreferences file, so a cold-start scan does not treat
 * every installed module as newly discovered. Without this, the
 * UI showed "N new modules detected" every time the app was
 * opened, because the in-memory "already registered" set was
 * empty at process start.
 */
public final class ModuleScanner {

    private static final String TAG = "ModuleScanner";

    private static final int MISSED_THRESHOLD = 2;

    private static final int MIN_PLAUSIBLE_PACKAGE_COUNT = 30;

    private static final long PURGE_GRACE_PERIOD_MS = 60_000L;

    private static final String PREFS_NAME = "module_scanner_state";
    private static final String KEY_SEEN_PACKAGES = "seen_packages";

    /** Detection source labels, recorded on ModuleInfo.detectedVia. */
    public static final String DETECTED_XPOSED_INIT   = "assets/xposed_init";
    public static final String DETECTED_XPOSED_MODULE = "assets/xposed_module";
    public static final String DETECTED_MODULE_PROP   = "META-INF/xposed/module.prop";

    private static final ConcurrentHashMap<String, Integer> missedCounts =
        new ConcurrentHashMap<>();

    private ModuleScanner() {}

    public static final class ScanResult {
        public int installedPackages = 0;
        public int xposedModulesFound = 0;
        public int newlyRegistered = 0;
        public int purgedCount = 0;
        public int refreshedPaths = 0;
        public int purgeDeferred = 0;
        public int alreadyKnown = 0;

        public final List<String> newlyRegisteredPackages = new ArrayList<>();
        public final List<String> purgedPackages = new ArrayList<>();
        public final Set<String> installedPackagesSeen = new HashSet<>();
    }

    // ═════════════════════════════════════════════════════════════
    // SEEN-PACKAGE PERSISTENCE
    // ═════════════════════════════════════════════════════════════

    private static Set<String> loadSeenPackages(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> stored = prefs.getStringSet(
                KEY_SEEN_PACKAGES, Collections.<String>emptySet());
            return new HashSet<>(stored);
        } catch (Throwable t) {
            return new HashSet<>();
        }
    }

    private static void saveSeenPackages(Context context, Set<String> updated) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                 .putStringSet(KEY_SEEN_PACKAGES, new HashSet<>(updated))
                 .commit();
        } catch (Throwable ignored) {}
    }

    public static void resetSeenPackages(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_SEEN_PACKAGES).commit();
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // MAIN SCAN
    // ═════════════════════════════════════════════════════════════

    public static ScanResult scanInstalledModules(Context context) {
        ScanResult result = new ScanResult();
        Logger logger = Logger.getInstance(context);
        PackageManager pm = context.getPackageManager();
        ModuleLoader loader = ModuleLoader.getInstance(context);
        String self = context.getPackageName();

        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
            );
        } catch (Throwable t) {
            logger.e("[" + TAG + "] getInstalledApplications failed: " + t.getMessage());
            installed = null;
        }

        if (installed == null) {
            logger.w("[" + TAG + "] Skipping scan: getInstalledApplications "
                + "returned null");
            return result;
        }

        Set<String> alreadyRegistered = new HashSet<>();
        for (ModuleInfo m : loader.getCachedModules()) {
            if (m != null && m.packageName != null) {
                alreadyRegistered.add(m.packageName);
            }
        }
        Set<String> seenPackages = loadSeenPackages(context);
        Set<String> presentThisScan = new HashSet<>();

        for (ApplicationInfo ai : installed) {
            if (ai == null || ai.packageName == null) continue;

            result.installedPackagesSeen.add(ai.packageName);

            if (self.equals(ai.packageName)) continue;

            result.installedPackages++;

            try {
                String apkPath = ai.sourceDir;
                if (apkPath == null) continue;

                resetMissedCount(ai.packageName);

                if (alreadyRegistered.contains(ai.packageName)) {
                    ModuleInfo existing = loader.getModule(ai.packageName);
                    if (existing != null) {
                        boolean pathMatches = existing.apkPath != null
                                && existing.apkPath.equals(apkPath);
                        if (pathMatches) {
                            presentThisScan.add(ai.packageName);
                            if (seenPackages.contains(ai.packageName)) {
                                result.alreadyKnown++;
                            }
                            continue;
                        }
                        logger.i("[" + TAG + "] Refreshing apkPath for "
                            + ai.packageName + ": "
                            + existing.apkPath + " -> " + apkPath);
                        existing.apkPath = apkPath;
                        loader.saveModule(existing);
                        result.refreshedPaths++;
                        presentThisScan.add(ai.packageName);
                        if (seenPackages.contains(ai.packageName)) {
                            result.alreadyKnown++;
                        }
                        continue;
                    }
                    logger.w("[" + TAG + "] " + ai.packageName
                        + " is registered but missing from the loader cache");
                }

                Detection det = detectModule(apkPath);
                if (det == null) continue;

                result.xposedModulesFound++;
                presentThisScan.add(ai.packageName);

                boolean isFirstTimeReporting = !seenPackages.contains(ai.packageName);

                ModuleInfo module = new ModuleInfo();
                module.packageName = ai.packageName;
                module.name = ai.loadLabel(pm).toString();
                module.version = readVersion(pm, ai.packageName);
                module.xposedInit = det.entryClass;
                module.apkPath = apkPath;
                module.enabled = existingOrEnabled(loader, ai.packageName);
                module.hookedApps = preserveScope(loader, ai.packageName);
                module.recommendedApps = preserveRecommended(loader, ai.packageName);
                module.hasUi = hasLauncherActivity(context, ai.packageName);
                module.manuallyAdded = existingManuallyAdded(loader, ai.packageName);
                module.detectedVia = det.source;

                boolean ok = loader.installModule(module);
                if (ok) {
                    if (isFirstTimeReporting) {
                        result.newlyRegistered++;
                        result.newlyRegisteredPackages.add(ai.packageName);
                        logger.i("[" + TAG + "] Auto-detected module: "
                            + ai.packageName
                            + " (source=" + det.source
                            + ", entry=" + (det.entryClass == null
                                ? "<auto-detect>" : det.entryClass)
                            + ", hasUi=" + module.hasUi
                            + ", recommended=" + module.getRecommendedAppCount() + ")");
                    } else {
                        result.alreadyKnown++;
                        logger.d("[" + TAG + "] Module still present: "
                            + ai.packageName);
                    }
                }

            } catch (Throwable t) {
                logger.d("[" + TAG + "] Skipping " + ai.packageName
                    + ": " + t.getMessage());
            }
        }

        Set<String> updatedSeen = new HashSet<>(seenPackages);
        updatedSeen.addAll(presentThisScan);
        if (!updatedSeen.equals(seenPackages)) {
            saveSeenPackages(context, updatedSeen);
        }

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
            + result.alreadyKnown + " already known, "
            + result.refreshedPaths + " path(s) refreshed, "
            + result.purgedCount + " purged, "
            + result.purgeDeferred + " purge(s) deferred");

        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // DETECTION
    // ═════════════════════════════════════════════════════════════

    private static final class Detection {
        final String source;
        final String entryClass;

        Detection(String source, String entryClass) {
            this.source = source;
            this.entryClass = entryClass;
        }
    }

    /**
     * Check whether the APK ships one of the canonical Xposed
     * module markers.
     *
     * The three markers, in order:
     *   • assets/xposed_init         — canonical
     *   • assets/xposed_module       — older convention
     *   • META-INF/xposed/module.prop — EdXposed-era
     *
     * If none is present, the package is not a module. No
     * fallback heuristics, no class loading, no dex enumeration.
     *
     * The entry class is read from the first marker that exists.
     * If the marker exists but the entry is empty (e.g.,
     * assets/native_init-style marker with no class), the
     * detection still succeeds — the module will be registered
     * with entryClass == null, and the module's own code will
     * self-identify at load time.
     */
    private static Detection detectModule(String apkPath) {
        try (ZipFile zip = new ZipFile(apkPath)) {
            String entry = readFirstMeaningfulLine(zip, "assets/xposed_init");
            if (entry != null && !entry.isEmpty()) {
                return new Detection(DETECTED_XPOSED_INIT, entry);
            }

            entry = readFirstMeaningfulLine(zip, "assets/xposed_module");
            if (entry != null && !entry.isEmpty()) {
                return new Detection(DETECTED_XPOSED_MODULE, entry);
            }

            String propEntry = readModulePropEntry(zip);
            if (propEntry != null && !propEntry.isEmpty()) {
                return new Detection(DETECTED_MODULE_PROP, propEntry);
            }
        } catch (Throwable t) {
            // ZipFile can fail on a corrupt or permission-denied
            // APK. Not a module.
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
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
    // PURGE
    // ═════════════════════════════════════════════════════════════

    public static int purgeUninstalledModules(Context context,
                                              List<String> purgedOut) {
        return purgeUninstalledModules(context, purgedOut, null);
    }

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

            if (m.manuallyAdded) {
                resetMissedCount(m.packageName);
                continue;
            }

            if (recentlySeenPackages != null
                    && recentlySeenPackages.contains(m.packageName)) {
                resetMissedCount(m.packageName);
                continue;
            }

            if (m.lastUpdated > 0) {
                long age = System.currentTimeMillis() - m.lastUpdated;
                if (age < PURGE_GRACE_PERIOD_MS) {
                    logger.i("[" + TAG + "] Purge deferred for "
                        + m.packageName
                        + " (age=" + (age / 1000) + "s, grace="
                        + (PURGE_GRACE_PERIOD_MS / 1000) + "s)");
                    resetMissedCount(m.packageName);
                    continue;
                }
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
            } catch (Throwable t) {
                packageInstalled = true;
                resetMissedCount(m.packageName);
                logger.d("[" + TAG + "] PackageManager error for "
                    + m.packageName + ": " + t.getMessage()
                    + " — treating as installed");
            }

            logger.d("[" + TAG + "] Purge check: " + m.packageName
                + " apkExists=" + apkExists
                + " packageInstalled=" + packageInstalled
                + " apkPath=" + m.apkPath);

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
    // STATIC MARKER READERS
    // ═════════════════════════════════════════════════════════════

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
    // MODULE INFO HELPERS
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

    private static boolean existingManuallyAdded(ModuleLoader loader, String pkg) {
        ModuleInfo existing = loader.getModule(pkg);
        return existing != null && existing.manuallyAdded;
    }
}