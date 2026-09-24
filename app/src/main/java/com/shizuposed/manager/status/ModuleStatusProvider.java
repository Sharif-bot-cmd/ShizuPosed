package com.shizuposed.manager.status;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.core.MarkerCache;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ModuleStatusProvider
 *
 * A read-only ContentProvider that lets external modules (running in
 * their own app processes) ask questions about their state in
 * ShizuPosed.
 *
 * The "active" and "scope" endpoints read the shell-side hooked
 * markers written by XposedHook. Since R-5.4 those markers are read
 * from a local mirror maintained by MarkerCache, not queried from
 * ShizuPosed's shell process directly. This makes activation queries
 * fast, and immune to Shizuku being killed or restarted between a
 * marker write and a module UI query.
 *
 * MARKER SCAN CACHING
 * -------------------
 * The local mirror is refreshed by ProcessMonitor every 5 seconds.
 * On top of that, the provider caches the parsed scan for
 * MARKER_CACHE_TTL_MS so that repeated queries within a short window
 * do not re-read the same files.
 *
 * If the mirror is empty when a query arrives — which happens on a
 * cold start before the monitor has run — the provider asks
 * MarkerCache to refresh once, synchronously. If Shizuku is not
 * available, the query returns an empty result rather than blocking.
 */
public class ModuleStatusProvider extends ContentProvider {

    public static final String AUTHORITY = "com.shizuposed.manager.status";

    public static final Uri BASE_URI    = Uri.parse("content://" + AUTHORITY);
    public static final Uri MODULES_URI = Uri.parse("content://" + AUTHORITY + "/modules");
    public static final Uri INFO_URI    = Uri.parse("content://" + AUTHORITY + "/info");

    private static final String TAG = "ShizuPosedProvider";

    /** How long a marker scan stays valid. */
    private static final long MARKER_CACHE_TTL_MS = 3000L;

    public static Uri moduleUri(String packageName) {
        return Uri.parse("content://" + AUTHORITY + "/module/" + packageName);
    }

    public static Uri activeUri(String modulePackage) {
        return Uri.parse("content://" + AUTHORITY + "/active/" + modulePackage);
    }

    public static Uri scopeUri(String modulePackage) {
        return Uri.parse("content://" + AUTHORITY + "/scope/" + modulePackage);
    }

    private Logger logger;

    // ── In-memory cache of the parsed mirror ────────────────────
    // Key: target package name. Value: the marker JSON body.
    private volatile Map<String, String> markerCache =
        Collections.emptyMap();
    private volatile long markerCacheAt = 0L;
    private final Object markerLock = new Object();
    private final Object moduleLock = new Object();

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            logger = Logger.getInstance(getContext());
            if (logger != null) logger.i("ModuleStatusProvider created");
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // LAZY LOAD
    // ─────────────────────────────────────────────────────────────

    private void ensureModulesLoaded() {
        if (getContext() == null) return;
        synchronized (moduleLock) {
            try {
                ModuleLoader loader = ModuleLoader.getInstance(getContext());
                if (loader.getCachedModules().isEmpty()) {
                    loader.loadModules();
                }
            } catch (Throwable t) {
                Log.e(TAG, "ensureModulesLoaded failed", t);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // QUERY
    // ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri,
                        @Nullable String[] projection,
                        @Nullable String selection,
                        @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {

        if (getContext() == null) {
            Log.w(TAG, "query with null context: " + uri);
            return null;
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");

        if (segments.length >= 3 && "module".equals(segments[1])) {
            return queryModule(segments[2]);
        }
        if (segments.length >= 3 && "active".equals(segments[1])) {
            return queryModuleActive(segments[2]);
        }
        if (segments.length >= 3 && "scope".equals(segments[1])) {
            return queryModuleScope(segments[2]);
        }
        if (segments.length >= 2 && "modules".equals(segments[1])) {
            return queryModules();
        }
        if (segments.length >= 2 && "info".equals(segments[1])) {
            return queryInfo();
        }

        Log.w(TAG, "query: unknown path " + path);
        return new MatrixCursor(new String[]{"value"});
    }

    private Cursor queryModule(String pkg) {
        ensureModulesLoaded();

        ModuleInfo m = null;
        try {
            m = ModuleLoader.getInstance(getContext()).getModule(pkg);
        } catch (Throwable t) {
            Log.e(TAG, "queryModule(" + pkg + ") failed", t);
        }

        boolean enabled = m != null && m.enabled;

        MatrixCursor c = new MatrixCursor(
            new String[]{"package", "enabled", "value"});
        c.addRow(new Object[]{
            pkg,
            enabled ? 1 : 0,
            enabled ? "1" : "0"
        });
        return c;
    }

    private Cursor queryModules() {
        ensureModulesLoaded();

        MatrixCursor c = new MatrixCursor(new String[]{"package"});
        try {
            List<ModuleInfo> list = ModuleLoader.getInstance(getContext())
                .getEnabledModules();
            for (ModuleInfo m : list) {
                if (m != null && m.packageName != null) {
                    c.addRow(new Object[]{m.packageName});
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "queryModules failed", t);
        }
        return c;
    }

    private Cursor queryInfo() {
        ensureModulesLoaded();

        int count = 0;
        try {
            count = ModuleLoader.getInstance(getContext()).getEnabledModules().size();
        } catch (Throwable t) {
            Log.e(TAG, "queryInfo failed", t);
        }

        MatrixCursor c = new MatrixCursor(
            new String[]{"framework", "version", "count", "enabled"});
        c.addRow(new Object[]{"ShizuPosed", 1, count, 1});
        return c;
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIVE / SCOPE — read the local marker mirror
    // ═════════════════════════════════════════════════════════════

    private Cursor queryModuleActive(String modulePkg) {
        boolean active = false;
        try {
            Map<String, String> markers = getMarkers();
            for (Map.Entry<String, String> e : markers.entrySet()) {
                if (markerContainsModule(e.getValue(), modulePkg)) {
                    active = true;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "queryModuleActive(" + modulePkg + ") failed", t);
        }

        MatrixCursor c = new MatrixCursor(
            new String[]{"package", "active", "value"});
        c.addRow(new Object[]{
            modulePkg,
            active ? 1 : 0,
            active ? "1" : "0"
        });
        return c;
    }

    private Cursor queryModuleScope(String modulePkg) {
        MatrixCursor c = new MatrixCursor(new String[]{"package"});
        try {
            Map<String, String> markers = getMarkers();
            for (Map.Entry<String, String> e : markers.entrySet()) {
                if (markerContainsModule(e.getValue(), modulePkg)) {
                    c.addRow(new Object[]{e.getKey()});
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "queryModuleScope(" + modulePkg + ") failed", t);
        }
        return c;
    }

    private boolean markerContainsModule(String markerJson, String modulePkg) {
        if (markerJson == null || modulePkg == null) return false;
        try {
            JSONObject obj = new JSONObject(markerJson);
            JSONArray arr = obj.optJSONArray("moduleList");
            if (arr == null) return false;
            for (int i = 0; i < arr.length(); i++) {
                if (modulePkg.equals(arr.optString(i))) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "bad marker json: " + t.getMessage());
        }
        return false;
    }

    /**
     * Return the parsed marker set. Reads from the local mirror via
     * MarkerCache — never queries Shizuku directly.
     *
     * If the mirror is empty (cold start, or ProcessMonitor has not
     * run its first refresh), asks MarkerCache to refresh once,
     * synchronously. If Shizuku is unavailable, the refresh is a
     * no-op and we return the empty map.
     */
    // ── FIX: no more Shizuku in the query path.
    private Map<String, String> getMarkers() {
        long now = System.currentTimeMillis();
        Map<String, String> cached = markerCache;
        if (now - markerCacheAt < MARKER_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (markerLock) {
            if (System.currentTimeMillis() - markerCacheAt < MARKER_CACHE_TTL_MS) {
                return markerCache;
            }
            Map<String, String> fresh = readLocalMirror();
            markerCache = fresh;
            markerCacheAt = System.currentTimeMillis();
            return fresh;
        }
    }

    /**
     * Read the local mirror. If empty, ask MarkerCache to refresh
     * once from the shell side, then read again.
     *
     * Never throws. A failure to refresh is not a failure to answer:
     * we return whatever the mirror currently holds, which may be an
     * older but still valid snapshot.
     */
    private Map<String, String> readLocalMirror() {
        Map<String, String> out = new HashMap<>();
        try {
            if (getContext() == null) return out;

            out = MarkerCache.read(getContext());

            if (out.isEmpty()) {
                // Cold cache. Ask for one synchronous refresh. The
                // refresh is a no-op if Shizuku is unavailable, in
                // which case out stays empty and we return that.
                int n = MarkerCache.refresh(getContext());
                if (n > 0) {
                    out = MarkerCache.read(getContext());
                }
                if (logger != null) {
                    logger.i("readLocalMirror: cold cache, refresh returned "
                        + n + ", mirrored " + out.size() + " marker(s)");
                }
            } else if (logger != null) {
                logger.d("readLocalMirror: " + out.size() + " marker(s) from cache");
            }
        } catch (Throwable t) {
            Log.e(TAG, "readLocalMirror failed", t);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────
    // CALL — modern modules sometimes use ContentResolver.call()
    // ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public Bundle call(@NonNull String method,
                       @Nullable String arg,
                       @Nullable Bundle extras) {
        Bundle b = new Bundle();
        try {
            String path = method == null ? "" : method;
            if ("active".equals(path) && arg != null) {
                Cursor c = queryModuleActive(arg);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("active");
                    b.putBoolean("active", idx != -1 && c.getInt(idx) == 1);
                    b.putString("value", idx != -1 && c.getInt(idx) == 1 ? "1" : "0");
                    c.close();
                }
            } else if ("enabled".equals(path) && arg != null) {
                Cursor c = queryModule(arg);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("enabled");
                    b.putBoolean("enabled", idx != -1 && c.getInt(idx) == 1);
                    c.close();
                }
            } else if ("scope".equals(path) && arg != null) {
                Cursor c = queryModuleScope(arg);
                if (c != null) {
                    ArrayList<String> pkgs = new ArrayList<>();
                    int idx = c.getColumnIndex("package");
                    while (c.moveToNext()) {
                        String p = idx != -1 ? c.getString(idx) : null;
                        if (p != null) pkgs.add(p);
                    }
                    c.close();
                    b.putStringArrayList("scope", pkgs);
                }
            } else if ("info".equals(path)) {
                b.putString("framework", "ShizuPosed");
                b.putInt("version", 1);
            }
        } catch (Throwable t) {
            Log.e(TAG, "call(" + method + ") failed", t);
        }
        return b;
    }

    // ─────────────────────────────────────────────────────────────
    // READ-ONLY
    // ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.item/vnd.shizuposed.status";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("ModuleStatusProvider is read-only");
    }

    @Override
    public int delete(@NonNull Uri uri,
                      @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("ModuleStatusProvider is read-only");
    }

    @Override
    public int update(@NonNull Uri uri,
                      @Nullable ContentValues values,
                      @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("ModuleStatusProvider is read-only");
    }
}