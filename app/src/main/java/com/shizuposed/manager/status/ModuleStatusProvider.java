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
 * markers written by XposedHook. The marker directory is resolved
 * from ShizuPosedService.getResolvedShellBase(), so the provider
 * reads markers from whichever directory the shell process actually
 * wrote them to. On most ROMs that is the primary shell files path;
 * on hardened ROMs it may be /data/local/tmp/shizuposed.
 *
 * MARKER SCAN CACHING
 * -------------------
 * Reading the markers requires a Shizuku round-trip (ls + cat per
 * file). Module UIs frequently poll isModuleActive on every screen
 * refresh, and the manager's Modules tab refreshes on resume.
 * Without caching, a single screen refresh with 5 modules and 30
 * hooked targets causes 150+ shell commands.
 *
 * The scan is therefore cached for MARKER_CACHE_TTL_MS. Fresh reads
 * happen only when the cache is older than the TTL or was never
 * populated.
 */
public class ModuleStatusProvider extends ContentProvider {

    public static final String AUTHORITY = "com.shizuposed.manager.status";

    public static final Uri BASE_URI    = Uri.parse("content://" + AUTHORITY);
    public static final Uri MODULES_URI = Uri.parse("content://" + AUTHORITY + "/modules");
    public static final Uri INFO_URI    = Uri.parse("content://" + AUTHORITY + "/info");

    private static final String TAG = "ShizuPosedProvider";

    /** How long a marker scan stays valid. */
    private static final long MARKER_CACHE_TTL_MS = 3000L;

    /** Fallback if ShizuPosedService cannot answer. */
    private static final String HOOKED_DIR_FALLBACK =
        "/data/user/0/com.android.shell/files/.syscall_cache/hooked";

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

    // ── Marker cache ────────────────────────────────────────────
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
    // SHELL BASE RESOLUTION
    // ─────────────────────────────────────────────────────────────

    /**
     * Ask the service for the shell base it resolved. Falls back to
     * the primary path if the service hasn't been started or the
     * accessor throws.
     *
     * This is the critical link that keeps the provider's view of
     * the marker directory in sync with the shell process. If they
     * disagree, activation reporting silently fails on ROMs where
     * the fallback path was chosen.
     */
    private String getHookedDir() {
        try {
            String base = ShizuPosedService.getResolvedShellBase();
            if (base != null && !base.isEmpty()) {
                return base + "/hooked";
            }
        } catch (Throwable t) {
            Log.w(TAG, "getHookedDir: service unavailable: " + t.getMessage());
        }
        return HOOKED_DIR_FALLBACK;
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
    // ACTIVE / SCOPE — read the shell-side hooked markers
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
            Map<String, String> fresh = scanMarkers();
            markerCache = fresh;
            markerCacheAt = System.currentTimeMillis();
            return fresh;
        }
    }

    private Map<String, String> scanMarkers() {
        Map<String, String> out = new HashMap<>();
        try {
            if (getContext() == null) return out;

            ShizukuHelper sh = ShizukuHelper.getInstance(getContext());
            if (!sh.isAvailable() || !sh.isAuthorized()) {
                Log.w(TAG, "scanMarkers: Shizuku unavailable or unauthorized");
                return out;
            }

            String hookedDir = getHookedDir();
            Log.d(TAG, "scanMarkers: scanning " + hookedDir);

            ShellUtils.CommandResult ls = sh.executeCommand(
                "ls " + hookedDir + " 2>/dev/null; true");
            if (ls == null || ls.stdout == null) {
                Log.w(TAG, "scanMarkers: ls returned no output");
                return out;
            }

            for (String line : ls.stdout) {
                String name = line == null ? null : line.trim();
                if (name == null || name.isEmpty()) continue;
                if (!name.endsWith(".json")) continue;

                String pkg = name.substring(0, name.length() - 5);
                if (pkg.isEmpty()) continue;

                ShellUtils.CommandResult cat = sh.executeCommand(
                    "cat " + hookedDir + "/" + name);
                if (cat == null || cat.stdout == null) continue;

                StringBuilder body = new StringBuilder();
                for (String l : cat.stdout) if (l != null) body.append(l);
                if (body.length() == 0) continue;

                out.put(pkg, body.toString());
            }

            Log.i(TAG, "scanMarkers: " + out.size() + " marker(s) at " + hookedDir);
        } catch (Throwable t) {
            Log.e(TAG, "scanMarkers failed", t);
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