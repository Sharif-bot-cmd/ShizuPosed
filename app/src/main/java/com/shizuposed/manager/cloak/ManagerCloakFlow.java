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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public final class ManagerCloakFlow {

    private static final String TAG = "ManagerCloakFlow";

    public interface ProgressListener {
        void onMessage(String text);
        void onSuccess(String newPackageName);
        void onError(String message);
    }

    private ManagerCloakFlow() {}

    public static void run(String newPackageName,
                           String newLabel,
                           ProgressListener listener) {
        Context ctx = ShizuPosedManagerApp.getInstance();
        if (ctx == null) {
            listener.onError("No application context");
            return;
        }
        Logger logger = Logger.getInstance(ctx);

        String oldPackage = ctx.getPackageName();
        try {
            if (!PackageNameValidator.isValidPackageName(newPackageName)) {
                listener.onError("Invalid package name");
                return;
            }
            if (!PackageNameValidator.isValidLabel(newLabel)) {
                listener.onError("Invalid app name");
                return;
            }
            if (newPackageName.equals(oldPackage)) {
                listener.onError("Package name is unchanged");
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

            listener.onMessage("Rebuilding manager APK…");
            File cloakedApk = ManagerCloak.cloak(
                newPackageName, migrateZip,
                new File(workDir, "manager-" + newPackageName + ".apk"));

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) {
                listener.onError("Could not resolve shell base");
                return;
            }
            String shellApk = base + "/manager-cloaked.apk";

            listener.onMessage("Staging cloaked APK…");
            ShellUtils.CommandResult stage = helper.executeCommand(
                "cat '" + cloakedApk.getAbsolutePath() + "' > '" + shellApk + "'");
            if (stage == null || !stage.isSuccess()) {
                listener.onError("Could not stage APK: "
                    + (stage != null ? stage.getStderrString() : "null"));
                return;
            }

            listener.onMessage("Installing cloaked manager…");
            ShellUtils.CommandResult install = helper.executeCommand(
                "pm install -r '" + shellApk + "'");
            if (install == null || !install.isSuccess()
                    || install.getStdoutString() == null
                    || !install.getStdoutString().contains("Success")) {
                listener.onError("Install failed: "
                    + (install != null
                        ? install.getStdoutString() + " / " + install.getStderrString()
                        : "null"));
                return;
            }

            // ── VERIFY the new package is launchable ──────────────
            // pm install can return Success even when the APK
            // has no launcher activity (ManifestEditor dropped
            // the targetActivity attribute, the launcher is
            // non-exported, etc.). If we skip this check, we
            // uninstall the old package and leave the user with
            // no working app.
            listener.onMessage("Verifying cloaked package…");
            String launcherComponent = resolveLauncherComponent(
                ctx, newPackageName);
            if (launcherComponent == null) {
                Log.w(TAG, "Cloaked package has no launcher activity; rolling back");
                helper.executeCommand("pm uninstall '" + newPackageName + "'");
                listener.onError(
                    "The rebuilt APK installed but has no launcher "
                    + "activity. The old package is still installed. "
                    + "This usually means the manifest had an "
                    + "<activity-alias> whose targetActivity could "
                    + "not be rewritten. Try a different package "
                    + "name, or report this as a bug.");
                return;
            }

            listener.onMessage("Launching new manager…");
            String startCmd = "am start --user current -n '"
                + launcherComponent + "'";
            ShellUtils.CommandResult start = helper.executeCommand(startCmd);
            if (start == null || !start.isSuccess()) {
                // am start failing is not necessarily fatal —
                // the launcher may still pick the package up
                // from the PACKAGE_ADDED broadcast. But log it
                // so the failure is visible.
                Log.w(TAG, "am start failed for " + launcherComponent
                    + ": " + (start != null ? start.getStderrString() : "null"));
            }

            // Give the launcher a moment to pick up the package.
            // The PACKAGE_ADDED broadcast is asynchronous and the
            // launcher may not have refreshed its list yet. This
            // is best-effort — if the launcher is slow, the user
            // will see the icon appear a few seconds later.
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // ── Only now uninstall the old package ────────────────
            listener.onMessage("Removing old package…");
            ShellUtils.CommandResult uninstall = helper.executeCommand(
                "pm uninstall '" + oldPackage + "'");
            if (uninstall == null || !uninstall.isSuccess()) {
                Log.w(TAG, "Uninstall old package failed: "
                    + (uninstall != null ? uninstall.getStderrString() : "null"));
                // Not fatal — the new package is installed and
                // launchable. The old one staying installed is
                // ugly but recoverable (user can uninstall it
                // manually, or run Unhide).
            }

            deleteRecursively(workDir);
            listener.onSuccess(newPackageName);

            if (logger != null) {
                logger.i("Cloaked manager: " + oldPackage + " -> " + newPackageName);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Cloak failed", t);
            listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    /**
     * Find the launcher component for a package.
     *
     * Returns "pkg/.ActivityName" if the package has an activity
     * matching ACTION_MAIN + CATEGORY_LAUNCHER, or null if it
     * doesn't. This is the same signal the launcher uses — if
     * this returns null, the package will not show in the app
     * drawer.
     *
     * The query is delayed slightly to give PackageManager time
     * to finish indexing the newly installed package. On most
     * devices the query returns immediately; on slower ROMs the
     * first query after an install can return an empty list even
     * though the package has a launcher activity.
     */
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

    /**
     * Revert to the original package name. Downloads the latest
     * standard APK from GitHub, embeds a migrate zip, installs
     * it, and uninstalls the current cloaked package.
     *
     * Same safety property as run(): the current package is
     * uninstalled only after the original one is confirmed
     * launchable.
     */
    public static void revertToOriginal(ProgressListener listener) {
        Context ctx = ShizuPosedManagerApp.getInstance();
        if (ctx == null) {
            listener.onError("No application context");
            return;
        }

        String currentPackage = ctx.getPackageName();
        String original = BuildConfig.ORIGINAL_PACKAGE_NAME;
        try {
            if (currentPackage.equals(original)) {
                listener.onError("Already using the original package name");
                return;
            }
            ShizukuHelper helper = ShizukuHelper.getInstance(ctx);
            if (helper == null || !helper.isAuthorized()) {
                listener.onError("Shizuku is required");
                return;
            }

            File workDir = new File(ctx.getCacheDir(), "cloak-revert");
            deleteRecursively(workDir);
            workDir.mkdirs();

            listener.onMessage("Downloading latest manager from GitHub…");
            File downloaded = new File(workDir, "manager-github.apk");
            download(BuildConfig.DOWNLOAD_URL_STANDARD, downloaded);

            listener.onMessage("Preparing migrate data…");
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Preparing original package APK…");
            File stockApk = ManagerCloak.prepareStockWithMigrate(
                downloaded, migrateZip,
                new File(workDir, "manager-original.apk"));

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) {
                listener.onError("Could not resolve shell base");
                return;
            }
            String shellApk = base + "/manager-original.apk";

            ShellUtils.CommandResult stage = helper.executeCommand(
                "cat '" + stockApk.getAbsolutePath() + "' > '" + shellApk + "'");
            if (stage == null || !stage.isSuccess()) {
                listener.onError("Could not stage APK: "
                    + (stage != null ? stage.getStderrString() : "null"));
                return;
            }

            listener.onMessage("Installing original manager…");
            ShellUtils.CommandResult install = helper.executeCommand(
                "pm install -r '" + shellApk + "'");
            if (install == null || !install.isSuccess()
                    || install.getStdoutString() == null
                    || !install.getStdoutString().contains("Success")) {
                listener.onError("Install failed: "
                    + (install != null ? install.getStderrString() : "null"));
                return;
            }

            // Same verification as run() — don't uninstall the
            // cloaked package unless the original is launchable.
            listener.onMessage("Verifying original package…");
            String launcherComponent = resolveLauncherComponent(ctx, original);
            if (launcherComponent == null) {
                Log.w(TAG, "Original package has no launcher; rolling back");
                helper.executeCommand("pm uninstall '" + original + "'");
                listener.onError(
                    "The downloaded APK installed but has no launcher "
                    + "activity. The current package is still installed. "
                    + "Report this as a bug.");
                return;
            }

            listener.onMessage("Launching original manager…");
            helper.executeCommand(
                "am start --user current -n '" + launcherComponent + "'");

            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            listener.onMessage("Removing cloaked package…");
            helper.executeCommand("pm uninstall '" + currentPackage + "'");

            deleteRecursively(workDir);
            listener.onSuccess(original);
        } catch (Throwable t) {
            Log.e(TAG, "Revert failed", t);
            listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    private static void download(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
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