package com.shizuposed.manager.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.File;

/**
 * Download an update APK and install it via Shizuku's pm install.
 *
 * Launched from the "Install update" notification action, or
 * from a Settings menu item. The flow:
 *
 *   1. Read the cached UpdateInfo (tag, apk URL, size).
 *   2. Show a progress dialog while UpdateDownloader fetches
 *      the APK.
 *   3. On success, prompt "Install update?" with a button.
 *   4. On confirm, stage the APK to a shell-readable path and
 *      run pm install -r --user 0 via Shizuku.
 *   5. On success, relaunch the manager so the new version is
 *      visible without a manual restart.
 *
 * Uses UpdateProgressDialog instead of the deprecated
 * ProgressDialog. The API is the same for the calls this class
 * makes: setTitle, setMessage, setMax, setProgress, show,
 * dismiss, isShowing.
 */
public class UpdateInstallActivity extends Activity {

    public static final String EXTRA_TAG = "tag";

    private static final String TAG = "UpdateInstallActivity";

    private String tag;
    private UpdateProgressDialog progress;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tag = getIntent() != null
            ? getIntent().getStringExtra(EXTRA_TAG) : null;

        UpdateInfo info = UpdateInfo.load(this);
        if (info == null || !info.hasApk()) {
            Toast.makeText(this, "No update information available",
                Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (tag != null && !tag.equals(info.tag)) {
            Toast.makeText(this, "Update information changed; retry",
                Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        startDownload(info);
    }

    private void startDownload(UpdateInfo info) {
        progress = new UpdateProgressDialog(this);
        progress.setTitle("Downloading " + info.tag);
        progress.setMessage("Starting…");
        progress.setMax(100);
        progress.setProgress(0);
        progress.show();

        UpdateDownloader.downloadAsync(this, info.apkUrl, info.apkSize,
            new UpdateDownloader.Listener() {
                @Override public void onProgress(long done, long total) {
                    main.post(() -> {
                        if (progress == null || !progress.isShowing()) return;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            progress.setProgress(pct);
                            progress.setMessage(
                                (done / 1024 / 1024) + " / "
                                + (total / 1024 / 1024) + " MB");
                        } else {
                            progress.setMessage(
                                (done / 1024 / 1024) + " MB");
                        }
                    });
                }

                @Override public void onSuccess(File apk) {
                    main.post(() -> onDownloaded(info, apk));
                }

                @Override public void onError(String message) {
                    main.post(() -> {
                        dismissProgress();
                        Toast.makeText(UpdateInstallActivity.this,
                            "Download failed: " + message,
                            Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            });
    }

    private void onDownloaded(UpdateInfo info, File apk) {
        dismissProgress();

        String currentPackage = getPackageName();
        String downloadedPackage = com.shizuposed.manager.cloak.ManagerCloak
            .readPackageName(apk);
        if (downloadedPackage != null
                && !currentPackage.equals(downloadedPackage)) {
            new AlertDialog.Builder(this)
                .setTitle("Cloaked install")
                .setMessage("This manager is cloaked ("
                    + currentPackage + "). The downloaded update is "
                    + "for " + downloadedPackage + ".\n\n"
                    + "Unhide first, then update, then Hide again.")
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Install " + info.tag + "?")
            .setMessage("The update will replace the current ShizuPosed "
                + "install. Your settings, modules, and cloak key are "
                + "preserved.")
            .setPositiveButton("Install", (d, w) -> installViaShizuku(apk))
            .setNegativeButton("Later", (d, w) -> finish())
            .setCancelable(false)
            .show();
    }

    private void installViaShizuku(File apk) {
        ShizukuHelper helper = ShizukuHelper.getInstance(this);
        if (helper == null || !helper.isAuthorized()) {
            Toast.makeText(this, "Shizuku is required to install updates",
                Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        progress = new UpdateProgressDialog(this);
        progress.setTitle("Installing update");
        progress.setMessage("Staging APK…");
        progress.show();

        new Thread(() -> {
            try {
                String base = ShizuPosedService.getResolvedShellBase();
                if (base == null) {
                    throw new IllegalStateException("Could not resolve shell base");
                }
                String shellApk = base + "/update.apk";

                ShellUtils.CommandResult stage = helper.executeCommand(
                    "cat '" + apk.getAbsolutePath() + "' > '" + shellApk + "'");
                if (stage == null || !stage.isSuccess()) {
                    throw new IllegalStateException("Could not stage APK: "
                        + (stage != null ? stage.getStdoutString() : "null"));
                }

                main.post(() -> {
                    if (progress != null && progress.isShowing()) {
                        progress.setMessage("Running pm install…");
                    }
                });

                ShellUtils.CommandResult install = helper.executeCommand(
                    "pm install -r --user 0 '" + shellApk + "'");
                String out = install != null ? install.getStdoutString() : null;
                boolean ok = install != null && install.isSuccess()
                    && out != null && out.contains("Success");

                if (!ok) {
                    throw new IllegalStateException("Install failed: " + out);
                }

                main.post(() -> {
                    dismissProgress();
                    Toast.makeText(UpdateInstallActivity.this,
                        "Update installed", Toast.LENGTH_SHORT).show();
                    Intent launch = getPackageManager()
                        .getLaunchIntentForPackage(getPackageName());
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(launch);
                    }
                    finish();
                });

            } catch (Throwable t) {
                Logger l = Logger.getInstance(UpdateInstallActivity.this);
                if (l != null) l.e("Update install failed: " + t.getMessage());
                main.post(() -> {
                    dismissProgress();
                    Toast.makeText(UpdateInstallActivity.this,
                        "Install failed: " + t.getMessage(),
                        Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "ShizuPosed-update-install").start();
    }

    private void dismissProgress() {
        if (progress != null && progress.isShowing()) {
            try { progress.dismiss(); } catch (Throwable ignored) {}
        }
        progress = null;
    }

    @Override
    protected void onDestroy() {
        dismissProgress();
        super.onDestroy();
    }
}