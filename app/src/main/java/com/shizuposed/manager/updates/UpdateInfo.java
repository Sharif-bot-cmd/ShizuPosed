package com.shizuposed.manager.updates;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The latest release's version, release page, and APK asset URL.
 *
 * Cached in SharedPreferences so the UpdateInstallActivity can
 * look it up without re-querying the GitHub API, and so the
 * notification action can pass just the tag and have the
 * activity resolve the URL from storage.
 */
public final class UpdateInfo {
    private static final String PREFS = "shizuposed_update_info";
    private static final String KEY_TAG = "tag";
    private static final String KEY_HTML_URL = "html_url";
    private static final String KEY_APK_URL = "apk_url";
    private static final String KEY_APK_SIZE = "apk_size";

    public final String tag;
    public final String htmlUrl;
    public final String apkUrl;
    public final long apkSize;

    public UpdateInfo(String tag, String htmlUrl, String apkUrl, long apkSize) {
        this.tag = tag;
        this.htmlUrl = htmlUrl;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
    }

    public boolean hasApk() {
        return apkUrl != null && !apkUrl.isEmpty();
    }

    public static void save(Context ctx, UpdateInfo info) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
            .putString(KEY_TAG, info.tag)
            .putString(KEY_HTML_URL, info.htmlUrl)
            .putString(KEY_APK_URL, info.apkUrl)
            .putLong(KEY_APK_SIZE, info.apkSize)
            .apply();
    }

    public static UpdateInfo load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tag = p.getString(KEY_TAG, null);
        if (tag == null) return null;
        return new UpdateInfo(
            tag,
            p.getString(KEY_HTML_URL, null),
            p.getString(KEY_APK_URL, null),
            p.getLong(KEY_APK_SIZE, 0L));
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}