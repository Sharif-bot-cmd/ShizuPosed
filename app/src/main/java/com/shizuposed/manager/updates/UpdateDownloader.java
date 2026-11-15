package com.shizuposed.manager.updates;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads a GitHub release asset to the manager's cache dir.
 *
 * Single HTTP call. No dependency on OkHttp. Reports progress
 * via a callback. Verifies the file size against the size GitHub
 * reported for the asset, which catches truncation without a
 * full checksum. Also verifies the ZIP magic ("PK") so a
 * captive-portal HTML page served in place of the APK is
 * rejected before it reaches pm install.
 *
 * GitHub serves release assets via redirects to
 * objects.githubusercontent.com. HttpURLConnection follows
 * redirects by default; the size and magic checks are the
 * safety net.
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";

    public interface Listener {
        void onProgress(long bytesDownloaded, long totalBytes);
        void onSuccess(File apk);
        void onError(String message);
    }

    private UpdateDownloader() {}

    public static void downloadAsync(Context context,
                                     String apkUrl,
                                     long expectedSize,
                                     Listener listener) {
        if (context == null || apkUrl == null || listener == null) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                download(app, apkUrl, expectedSize, listener);
            } catch (Throwable t) {
                Log.w(TAG, "download failed", t);
                listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }, "ShizuPosed-update-download").start();
    }

    private static void download(Context app,
                                 String apkUrl,
                                 long expectedSize,
                                 Listener listener) throws Exception {
        File cacheDir = new File(app.getCacheDir(), "updates");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File out = new File(cacheDir, "ShizuPosed-update.apk");
        if (out.exists() && !out.delete()) {
            throw new IllegalStateException("Could not delete stale APK");
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(apkUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ShizuPosed-UpdateDownloader");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code + " from " + apkUrl);
            }

            long total = conn.getContentLengthLong();
            if (total <= 0) total = expectedSize;
            long downloaded = 0;

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                long lastProgress = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastProgress > 200) {
                        listener.onProgress(downloaded, total);
                        lastProgress = now;
                    }
                }
                fos.flush();
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }

            listener.onProgress(downloaded, total);

            if (expectedSize > 0 && out.length() != expectedSize) {
                out.delete();
                throw new IllegalStateException(
                    "Size mismatch: expected " + expectedSize
                    + " got " + out.length());
            }

            if (out.length() < 4) {
                out.delete();
                throw new IllegalStateException("Downloaded APK is empty");
            }
            byte[] magic = new byte[2];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(out)) {
                if (fis.read(magic) != 2) {
                    out.delete();
                    throw new IllegalStateException("Downloaded APK is too short");
                }
            }
            if (magic[0] != 0x50 || magic[1] != 0x4B) {
                out.delete();
                throw new IllegalStateException(
                    "Downloaded file is not an APK (missing ZIP magic)");
            }

            listener.onSuccess(out);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}