package com.shizuposed.manager.cloak;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.shizuposed.manager.BuildConfig;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.File;
import java.util.List;

/**
 * ManagerCloakFlow
 *
 * Two flows: Hide and Unhide. Both use the same sequence:
 *
 *   1. Extract the target APK from assets (stealth or standard)
 *   2. Add the migrate zip, sign with the bundled keystore
 *   3. pm install the target APK
 *   4. Verify the target package is launchable
 *   5. am start the target package
 *   6. pm uninstall the current package
 *
 * The target package name is read from the bundled APK's
 * manifest, not from a BuildConfig hint. This means the flow
 * works even if the build was produced with a different
 * -PstealthSeed than the one currently configured.
 *
 * If step 3 or 4 fails, the flow rolls back (uninstalls the
 * target package if partially installed) and reports the error.
 * The current package is untouched.
 */
public final class ManagerCloakFlow {

    private static final String TAG = "ManagerCloakFlow";

    public interface ProgressListener {
        void onMessage(String text);
        void onSuccess(String newPackageName);
        void onError(String message);
    }

    private ManagerCloakFlow() {}

    // ═════════════════════════════════════════════════════════════
    // HIDE (standard -> stealth)
    // ═════════════════════════════════════════════════════════════

    public static void hideShizuPosed(ProgressListener listener) {
        Context ctx = ShizuPosedManagerApp.getInstance();
        if (ctx == null) { listener.onError("No application context"); return; }
        Logger logger = Logger.getInstance(ctx);

        String oldPackage = ctx.getPackageName();
        try {
            if (BuildConfig.STEALTH_APK_ASSET == null
                    || BuildConfig.STEALTH_APK_ASSET.isEmpty()) {
                listener.onError(
                    "This build does not bundle a stealth APK. "
                    + "Use assembleBothReleases to produce a build "
                    + "with the stealth APK included.");
                return;
            }

            // Authoritative package name — read from the
            // bundled APK, not from BuildConfig.
            String newPackage = ManagerCloak.readBundledPackageName(
                BuildConfig.STEALTH_APK_ASSET);
            if (newPackage == null || newPackage.isEmpty()) {
                listener.onError(
                    "Could not read the stealth package name from "
                    + "the bundled APK. The asset may be missing or "
                    + "corrupt.");
                return;
            }
            Log.i(TAG, "Hide: " + oldPackage + " -> " + newPackage);
            if (newPackage.equals(oldPackage)) {
                listener.onError("Already running as the stealth package");
                return;
            }

            ShizukuHelper helper = ShizukuHelper.getInstance(ctx);
            if (helper == null || !helper.isAuthorized()) {
                listener.onError("Shizuku is required");
                return;
            }

            listener.onMessage("Preparing migrate data…");
            File workDir = new File(ctx.getCacheDir(), "cloak");
            deleteRecursively(workDir);
            workDir.mkdirs();
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Preparing stealth APK…");
            File targetApk = ManagerCloak.buildCloakedApk(
                migrateZip, new File(workDir, "stealth-manager-signed.apk"));

            installAndSwitch(ctx, helper, targetApk, oldPackage,
                newPackage, listener);

            if (logger != null) {
                logger.i("Hid ShizuPosed: " + oldPackage + " -> " + newPackage);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hide failed", t);
            listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // UNHIDE (stealth -> standard)
    // ═════════════════════════════════════════════════════════════

    public static void unhideShizuPosed(ProgressListener listener) {
        Context ctx = ShizuPosedManagerApp.getInstance();
        if (ctx == null) { listener.onError("No application context"); return; }
        Logger logger = Logger.getInstance(ctx);

        String oldPackage = ctx.getPackageName();
        try {
            if (BuildConfig.STANDARD_APK_ASSET == null
                    || BuildConfig.STANDARD_APK_ASSET.isEmpty()) {
                listener.onError(
                    "This build does not bundle a standard APK. "
                    + "Use assembleBothReleases to produce a build "
                    + "with the standard APK included.");
                return;
            }

            String newPackage = ManagerCloak.readBundledPackageName(
                BuildConfig.STANDARD_APK_ASSET);
            if (newPackage == null || newPackage.isEmpty()) {
                listener.onError(
                    "Could not read the standard package name from "
                    + "the bundled APK.");
                return;
            }
            Log.i(TAG, "Unhide: " + oldPackage + " -> " + newPackage);
            if (newPackage.equals(oldPackage)) {
                listener.onError("Already running as the standard package");
                return;
            }

            ShizukuHelper helper = ShizukuHelper.getInstance(ctx);
            if (helper == null || !helper.isAuthorized()) {
                listener.onError("Shizuku is required");
                return;
            }

            listener.onMessage("Preparing migrate data…");
            File workDir = new File(ctx.getCacheDir(), "cloak-revert");
            deleteRecursively(workDir);
            workDir.mkdirs();
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Preparing standard APK…");
            File targetApk = ManagerCloak.buildStandardApk(
                migrateZip, new File(workDir, "standard-manager-signed.apk"));

            installAndSwitch(ctx, helper, targetApk, oldPackage,
                newPackage, listener);

            if (logger != null) {
                logger.i("Unhid ShizuPosed: " + oldPackage + " -> " + newPackage);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unhide failed", t);
            listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SHARED INSTALL SEQUENCE
    // ═════════════════════════════════════════════════════════════

    private static void installAndSwitch(Context ctx,
                                         ShizukuHelper helper,
                                         File targetApk,
                                         String oldPackage,
                                         String newPackage,
                                         ProgressListener listener) throws Exception {
        String base = ShizuPosedService.getResolvedShellBase();
        if (base == null) {
            listener.onError("Could not resolve shell base");
            return;
        }
        String shellApk = base + "/manager-target.apk";

        listener.onMessage("Staging APK…");
        ShellUtils.CommandResult stage = helper.executeCommand(
            "cat '" + targetApk.getAbsolutePath() + "' > '" + shellApk + "'");
        if (stage == null || !stage.isSuccess()) {
            listener.onError("Could not stage APK: "
                + (stage != null ? stage.getStderrString() : "null"));
            return;
        }

        listener.onMessage("Installing " + newPackage + "…");
        ShellUtils.CommandResult install = helper.executeCommand(
            "pm install -r '" + shellApk + "'");
        if (install == null || !install.isSuccess()
                || install.getStdoutString() == null
                || !install.getStdoutString().contains("Success")) {
            String err = (install != null
                ? install.getStdoutString() + " / " + install.getStderrString()
                : "null");
            helper.executeCommand("pm uninstall '" + newPackage + "'");
            listener.onError("Install failed: " + err);
            return;
        }

        listener.onMessage("Verifying " + newPackage + "…");
        String launcherComponent = resolveLauncherComponent(ctx, newPackage);
        if (launcherComponent == null) {
            Log.w(TAG, "Installed package has no launcher; rolling back");
            helper.executeCommand("pm uninstall '" + newPackage + "'");
            listener.onError(
                "The installed APK has no launcher activity. "
                + "The current package is still installed.");
            return;
        }

        listener.onMessage("Launching " + newPackage + "…");
        helper.executeCommand(
            "am start --user current -n '" + launcherComponent + "'");

        try {
            Thread.sleep(1500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        listener.onMessage("Removing " + oldPackage + "…");
        ShellUtils.CommandResult uninstall = helper.executeCommand(
            "pm uninstall '" + oldPackage + "'");
        if (uninstall == null || !uninstall.isSuccess()) {
            Log.w(TAG, "Uninstall old package failed: "
                + (uninstall != null ? uninstall.getStderrString() : "null"));
        }

        listener.onSuccess(newPackage);
    }

    private static String resolveLauncherComponent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(pkg);

        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                List<ResolveInfo> ris = pm.queryIntentActivities(intent, 0);
                if (ris != null && !ris.isEmpty()) {
                    ResolveInfo ri = ris.get(0);
                    if (ri.activityInfo != null
                            && ri.activityInfo.name != null) {
                        return ri.activityInfo.packageName + "/"
                            + ri.activityInfo.name;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "resolveLauncherComponent attempt "
                    + attempt + " failed: " + t.getMessage());
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }
}