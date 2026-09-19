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
 * contains assets/xposed_init — the canonical marker of an Xposed
 * module. Modules found this way are registered with ModuleLoader so
 * they appear in the Modules tab without the user having to add them
 * manually.
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
 * By running discovery first and building a set of packages the
 * scanner actually observed as installed, the purge pass can refuse
 * to remove anything in that set. A module that the discovery pass
 * just saw is definitionally installed, and no purge decision can
 * contradict that.
 *
 * TWO-STRIKE PURGE
 * ----------------
 * Discovery-before-purge handles the case where a module appears in
 * the installed list in this scan. It does NOT handle the case where
 * PackageManager returns an incomplete list across two scans in a
 * row — which is exactly what happens in the seconds after the
 * device wakes from sleep, while the package cache is being
 * revalidated against storage.
 *
 * The classic symptom: the user sleeps the phone, wakes it, opens
 * the manager, and sees "N new module(s) detected" for modules they
 * never touched. The scanner saw an incomplete world on the first
 * scan, purged the modules, then saw them again on the next scan
 * and re-registered them.
 *
 * The fix is a two-strike rule: a module is only purged after
 * MISSED_THRESHOLD consecutive scans report it as not installed.
 * Any scan that sees the package in the installed list resets the
 * counter. A module that was genuinely uninstalled hits the
 * threshold in two scans; a module that was momentarily invisible
 * during a wake does not.
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
 * RECOMMENDED SCOPE
 * -----------------
 * When a module is re-registered (path changed, or first discovery
 * after a cache clear), preserveRecommended() carries its previously
 * known recommended scope across so the UI doesn't briefly flash an
 * empty recommended count. ModuleLoader.installModule() then refreshes
 * the set from the APK's assets/scope.list, which is the source of
 * truth.
 *
 * Skips:
 *   • Our own package (hooking ShizuPosed from ShizuPosed is useless)
 *   • Packages already in the ModuleLoader cache with the same apkPath
 */
public final class ModuleScanner {

    private static final String TAG = "ModuleScanner";

    // ── FIX: how many consecutive "not installed" scans a module
    // must accumulate before the purge pass removes it. Two is
    // enough to ride out the transient PackageManager failure that
    // occurs right after wake. Three would be more conservative but
    // means a genuinely uninstalled module lingers one extra scan.
    private static final int MISSED_THRESHOLD = 2;

    // ── FIX: per-package consecutive miss counter. In-memory only:
    // if the process dies, the counter resets, which is the safe
    // default (we would rather err toward not purging).
    //
    // Keyed by package name. Value is the number of scans in a row
    // that have reported the package as absent.
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
        // ── FIX: modules whose purge was deferred because this is
        // their first miss. Reported for diagnostics; the UI does
        // not surface it.
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
     *
     * Discovery runs first, then purge, so the purge pass can consult
     * the set of packages the discovery pass actually observed.
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
        // scan. A null list is not evidence that modules are gone;
        // it is evidence that we cannot tell. Purging on that would
        // be destructive.
        if (installed == null) {
            logger.w("[" + TAG + "] Skipping scan: getInstalledApplications "
                + "returned null");
            return result;
        }

        // ── FIX: if the returned list is suspiciously small, treat
        // the scan as unreliable and skip purge entirely. This
        // catches the wake-from-Doze window where PackageManager
        // returns a partial list without throwing.
        //
        // We do this AFTER discovery, using the number of packages
        // we successfully enumerated. A device with fewer than, say,
        // 30 packages is not a real Android phone; something is
        // wrong with the read.
        final int MIN_PLAUSIBLE_PACKAGE_COUNT = 30;

        // Snapshot of currently-registered modules keyed by package name
        Set<String> alreadyRegistered = new HashSet<>();
        for (ModuleInfo m : loader.getCachedModules()) {
            if (m != null && m.packageName != null) {
                alreadyRegistered.add(m.packageName);
            }
        }

        if (installed != null) {
            for (ApplicationInfo ai : installed) {
                if (ai == null || ai.packageName == null) continue;

                // Record every package we see, so the purge pass
                // below can consult this set.
                result.installedPackagesSeen.add(ai.packageName);

                if (self.equals(ai.packageName)) continue;

                result.installedPackages++;

                try {
                    String apkPath = ai.sourceDir;
                    if (apkPath == null) continue;

                    // ── FIX: any package the discovery pass sees
                    // counts as "alive" and clears its miss counter.
                    // Do this before the alreadyRegistered check so
                    // even modules we skipped get their counter
                    // reset.
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
                    String entry = readXposedInit(apkPath);
                    if (entry == null) continue;

                    result.xposedModulesFound++;

                    ModuleInfo module = new ModuleInfo();
                    module.packageName = ai.packageName;
                    module.name = ai.loadLabel(pm).toString();
                    module.version = readVersion(pm, ai.packageName);
                    module.xposedInit = entry;
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
                            + ai.packageName + " (" + entry
                            + ", hasUi=" + module.hasUi
                            + ", recommended=" + module.getRecommendedAppCount() + ")");
                    }

                } catch (Throwable t) {
                    logger.d("[" + TAG + "] Skipping " + ai.packageName
                        + ": " + t.getMessage());
                }
            }
        }

        // ── 2. Purge stale entries ──────────────────────────────────
        // Runs after discovery. The purge pass consults the set of
        // packages the discovery pass observed as installed, so a
        // module that discovery just saw cannot be purged — that
        // would cause it to be re-registered on the next scan and
        // produce a spurious notification.
        //
        // ── FIX: if the discovery pass saw fewer packages than a
        // plausible device would have, skip the purge entirely. The
        // scan is unreliable and any purge decision would be
        // destructive.
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
            + result.xposedModulesFound + " new Xposed modules found, "
            + result.newlyRegistered + " registered, "
            + result.refreshedPaths + " path(s) refreshed, "
            + result.purgedCount + " purged, "
            + result.purgeDeferred + " purge(s) deferred");

        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // MISS COUNTER
    // ═════════════════════════════════════════════════════════════

    /**
     * Clear the consecutive-miss counter for a package. Called from
     * the discovery pass whenever the package is observed in the
     * installed list.
     */
    // ── FIX: new method.
    private static void resetMissedCount(String pkg) {
        if (pkg == null) return;
        missedCounts.remove(pkg);
    }

    /**
     * Increment the consecutive-miss counter and return the new
     * value. Called from the purge pass when a module looks
     * uninstalled.
     */
    // ── FIX: new method.
    private static int incrementMissedCount(String pkg) {
        if (pkg == null) return 0;
        Integer cur = missedCounts.get(pkg);
        int next = (cur == null ? 0 : cur) + 1;
        missedCounts.put(pkg, next);
        return next;
    }

    /**
     * Current miss count for a package, or 0 if none.
     */
    // ── FIX: new method.
    private static int getMissedCount(String pkg) {
        if (pkg == null) return 0;
        Integer cur = missedCounts.get(pkg);
        return cur == null ? 0 : cur;
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
     *
     * @param recentlySeenPackages  package names the caller has just
     *                              observed as installed. Any module
     *                              in this set is never purged and
     *                              its miss counter is reset,
     *                              regardless of what the APK path or
     *                              PackageManager check reports.
     *                              Pass null to skip the check.
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

            // A module the caller just saw in the installed list is
            // by definition installed. Do not purge it, and clear
            // any accumulated misses.
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
                // A transient PackageManager error should not cause
                // a module to be purged. Also reset the counter,
                // because we cannot make a reliable determination.
                packageInstalled = true;
                resetMissedCount(m.packageName);
                logger.d("[" + TAG + "] PackageManager error for "
                    + m.packageName + ": " + t.getMessage()
                    + " — treating as installed");
            }

            if (apkExists || packageInstalled) {
                // APK on disk or package in PM: module is alive.
                // Reset the miss counter. This is the case that
                // fires when getPackageInfo succeeds but the
                // discovery pass didn't see the package.
                resetMissedCount(m.packageName);
                continue;
            }

            // ── FIX: two-strike rule. Increment the miss counter
            // and only purge once the threshold is reached. The
            // first miss is a candidate; the second confirms.
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
            logger.i("[" + TAG + "] Purge complete: removed " + removed + " module(s)");
        }
        return removed;
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    private static String readXposedInit(String apkPath) {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("assets/xposed_init");
            if (entry == null) return null;

            try (InputStream is = zip.getInputStream(entry);
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