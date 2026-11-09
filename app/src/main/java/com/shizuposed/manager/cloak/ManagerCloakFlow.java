package com.shizuposed.manager.cloak;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.shizuposed.manager.BuildConfig;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * ManagerCloakFlow
 *
 * The 9-step orchestration for the Hide ShizuPosed app flow.
 *
 *   1. Validate the package name / label
 *   2. Stage the manager APK to <shell-base>/manager.apk
 *   3. Export SharedPreferences + SQLite + keystore to a migrate zip
 *   4. Rebuild + re-sign the APK with the new package name
 *   5. Stage the rebuilt APK to <shell-base>/manager-cloaked.apk
 *   6. pm install via Shizuku
 *   7. am start the new package
 *   8. (In the new package on first launch) import the migrate zip
 *   9. pm uninstall <old.package.name> via Shizuku
 *
 * All shell work goes through ShizukuHelper.executeCommand(). All
 * progress is reported to a ProgressListener. Failures short-
 * circuit; the old package is never uninstalled until the new
 * one has been installed and launched successfully.
 */
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

            listener.onMessage("Preparing migrate data\u2026");
            File workDir = new File(ctx.getCacheDir(), "cloak");
            deleteRecursively(workDir);
            workDir.mkdirs();
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Rebuilding manager APK\u2026");
            File cloakedApk = ManagerCloak.cloak(
                newPackageName, migrateZip,
                new File(workDir, "manager-" + newPackageName + ".apk"));

            String base = ShizuPosedService.getResolvedShellBase();
            if (base == null) {
                listener.onError("Could not resolve shell base");
                return;
            }
            String shellApk = base + "/manager-cloaked.apk";

            listener.onMessage("Staging cloaked APK\u2026");
            ShellUtils.CommandResult stage = helper.executeCommand(
                "cat '" + cloakedApk.getAbsolutePath() + "' > '" + shellApk + "'");
            if (stage == null || !stage.isSuccess()) {
                listener.onError("Could not stage APK: "
                    + (stage != null ? stage.getStderrString() : "null"));
                return;
            }

            listener.onMessage("Installing cloaked manager\u2026");
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

            listener.onMessage("Launching new manager\u2026");
            helper.executeCommand(
                "am start -n '" + newPackageName + "/.ShizuPosedActivity'");

            listener.onMessage("Removing old package\u2026");
            ShellUtils.CommandResult uninstall = helper.executeCommand(
                "pm uninstall '" + oldPackage + "'");
            if (uninstall == null || !uninstall.isSuccess()) {
                Log.w(TAG, "Uninstall old package failed: "
                    + (uninstall != null ? uninstall.getStderrString() : "null"));
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
     * Restore the original package name. Downloads the latest
     * standard APK from GitHub, embeds a migrate zip, installs
     * it, and uninstalls the current cloaked package.
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

            listener.onMessage("Downloading latest manager from GitHub\u2026");
            File downloaded = new File(workDir, "manager-github.apk");
            download(BuildConfig.DOWNLOAD_URL_STANDARD, downloaded);

            listener.onMessage("Preparing migrate data\u2026");
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Preparing original package APK\u2026");
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

            listener.onMessage("Installing original manager\u2026");
            ShellUtils.CommandResult install = helper.executeCommand(
                "pm install -r '" + shellApk + "'");
            if (install == null || !install.isSuccess()
                    || install.getStdoutString() == null
                    || !install.getStdoutString().contains("Success")) {
                listener.onError("Install failed: "
                    + (install != null ? install.getStderrString() : "null"));
                return;
            }

            listener.onMessage("Launching original manager\u2026");
            helper.executeCommand(
                "am start -n '" + original + "/.ShizuPosedActivity'");

            listener.onMessage("Removing cloaked package\u2026");
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