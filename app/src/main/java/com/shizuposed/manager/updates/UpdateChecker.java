package com.shizuposed.manager.updates;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.shizuposed.manager.R;
import com.shizuposed.manager.utils.Logger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub Releases for a newer ShizuPosed version and posts
 * a notification when one exists.
 *
 * Single HTTP call to the public GitHub API. No push infrastructure,
 * no FCM, no server. Runs on a background thread; never blocks the
 * UI. Fails silently on any error — an offline device, a rate-limit,
 * a malformed response, all just mean "no notification this launch."
 *
 * The check is throttled to once per UPDATE_CHECK_INTERVAL_MS so
 * the manager can call it on every resume without spamming GitHub.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    /** Owner/repo of the releases. Adjust if the repo moves. */
    private static final String REPO = "Sharif-bot-cmd/ShizuPosed";

    /** The releases API endpoint for the latest release. */
    private static final String RELEASES_API =
        "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final String CHANNEL_ID = "shizuposed_updates";
    private static final String CHANNEL_NAME = "ShizuPosed Updates";
    private static final int NOTIFICATION_ID = 2001;

    private static final String PREFS = "shizuposed_update_check";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_LAST_NOTIFIED_TAG = "last_notified_tag";

    /** Don't hit the API more than once per 12 hours. */
    private static final long UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private UpdateChecker() {}

    /**
     * Check for an update in the background. Safe to call on every
     * app start; the internal throttle suppresses redundant calls.
     *
     * @param currentVersion the version string the app is running,
     *                       typically BuildConfig.VERSION_NAME
     */
    public static void checkAsync(Context context, String currentVersion) {
        if (context == null || currentVersion == null) return;

        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                checkBlocking(app, currentVersion);
            } catch (Throwable t) {
                Logger l = Logger.getInstance(app);
                if (l != null) l.d("UpdateChecker: check failed: " + t.getMessage());
            }
        }, "ShizuPosed-update-check").start();
    }

    private static void checkBlocking(Context context, String currentVersion) {
        Logger logger = Logger.getInstance(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Throttle.
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L);
        long now = System.currentTimeMillis();
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS) {
            if (logger != null) {
                logger.d("UpdateChecker: throttled (" 
                    + ((now - lastCheck) / 1000 / 60) + " min since last check)");
            }
            return;
        }

        if (logger != null) logger.i("UpdateChecker: querying " + RELEASES_API);

        String json = httpGet(RELEASES_API);
        if (json == null) {
            if (logger != null) logger.d("UpdateChecker: no response");
            return;
        }

        String tag = null;
        String htmlUrl = null;
        try {
            JSONObject obj = new JSONObject(json);
            tag = obj.optString("tag_name", null);
            htmlUrl = obj.optString("html_url", null);
        } catch (Throwable t) {
            if (logger != null) logger.w("UpdateChecker: parse failed: " + t.getMessage());
            return;
        }

        // Record the check regardless of outcome, so a failed parse
        // doesn't retry on every app start.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();

        if (tag == null || tag.isEmpty()) {
            if (logger != null) logger.d("UpdateChecker: no tag in response");
            return;
        }

        if (logger != null) logger.i("UpdateChecker: latest=" + tag
            + " current=" + currentVersion);

        if (!isNewer(tag, currentVersion)) {
            if (logger != null) logger.d("UpdateChecker: up to date");
            return;
        }

        // Avoid re-notifying for the same tag over and over.
        String lastNotified = prefs.getString(KEY_LAST_NOTIFIED_TAG, null);
        if (tag.equals(lastNotified)) {
            if (logger != null) logger.d("UpdateChecker: already notified for "
                + tag);
            return;
        }

        postNotification(context, tag, htmlUrl);
        prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, tag).apply();
        if (logger != null) logger.i("UpdateChecker: notified for " + tag);
    }

    /**
     * True if `remote` is a newer version than `local`.
     *
     * Strips a leading 'v' from both, splits on '.', compares each
     * numeric segment. Falls back to a plain inequality test if
     * either side isn't parseable — safer than missing an update.
     */
    private static boolean isNewer(String remote, String local) {
        try {
            String r = remote.startsWith("v") || remote.startsWith("V")
                ? remote.substring(1) : remote;
            String l = local.startsWith("v") || local.startsWith("V")
                ? local.substring(1) : local;

            String[] rp = r.split("\\.");
            String[] lp = l.split("\\.");
            int n = Math.max(rp.length, lp.length);
            for (int i = 0; i < n; i++) {
                int rv = i < rp.length ? parseIntSafe(rp[i]) : 0;
                int lv = i < lp.length ? parseIntSafe(lp[i]) : 0;
                if (rv > lv) return true;
                if (rv < lv) return false;
            }
            return false;
        } catch (Throwable t) {
            return !remote.equals(local);
        }
    }

    private static int parseIntSafe(String s) {
        try {
            // Strip any non-digit suffix (e.g. "1-beta" -> 1).
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') sb.append(c);
                else break;
            }
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Minimal HTTP GET. Returns the body as a string, or null on
     * any error. Uses HttpURLConnection — no OkHttp dependency.
     */
    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "ShizuPosed-UpdateChecker");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            try (InputStream is = conn.getInputStream();
                 BufferedReader r = new BufferedReader(
                     new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void checkNowAsync(Context context,
                                     String currentVersion,
                                     boolean forceNotify) {
        if (context == null || currentVersion == null) return;

        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                SharedPreferences prefs = app.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
                // Clear the throttle so the check runs.
                prefs.edit().putLong(KEY_LAST_CHECK, 0L).apply();
                if (forceNotify) {
                    prefs.edit().remove(KEY_LAST_NOTIFIED_TAG).apply();
                }
                checkBlocking(app, currentVersion);
            } catch (Throwable t) {
                Logger l = Logger.getInstance(app);
                if (l != null) l.d("checkNowAsync: failed: " + t.getMessage());
            }
        }, "ShizuPosed-update-check-manual").start();
    }

    private static void postNotification(Context context,
                                          String tag,
                                          String htmlUrl) {
        try {
            // Ensure channel exists.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = context.getSystemService(
                    NotificationManager.class);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                    ch.setDescription(
                        "Notifications about new ShizuPosed releases");
                    nm.createNotificationChannel(ch);
                }
            }

            // Tap opens the release page in a browser.
            Intent open = new Intent(Intent.ACTION_VIEW);
            if (htmlUrl != null && !htmlUrl.isEmpty()) {
                open.setData(Uri.parse(htmlUrl));
            } else {
                open.setData(Uri.parse(
                    "https://github.com/" + REPO + "/releases"));
            }
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            PendingIntent pi = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT
                    | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("ShizuPosed " + tag + " is available")
                .setContentText("Tap to open the release page")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Logger l = Logger.getInstance(context);
            if (l != null) l.w("UpdateChecker: postNotification failed: "
                + t.getMessage());
        }
    }
}