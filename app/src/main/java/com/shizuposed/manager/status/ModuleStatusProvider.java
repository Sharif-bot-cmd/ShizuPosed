package com.shizuposed.manager.status;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.util.List;

/**
 * ModuleStatusProvider
 *
 * A read-only ContentProvider that lets external modules (running in
 * their own app processes) ask whether the framework considers them
 * enabled.
 *
 * Modules query:
 *   content://com.shizuposed.manager.status/module/<packageName>
 *   content://com.shizuposed.manager.status/modules
 *   content://com.shizuposed.manager.status/info
 */
public class ModuleStatusProvider extends ContentProvider {

    public static final String AUTHORITY = "com.shizuposed.manager.status";

    public static final Uri BASE_URI    = Uri.parse("content://" + AUTHORITY);
    public static final Uri MODULES_URI = Uri.parse("content://" + AUTHORITY + "/modules");
    public static final Uri INFO_URI    = Uri.parse("content://" + AUTHORITY + "/info");

    public static Uri moduleUri(String packageName) {
        return Uri.parse("content://" + AUTHORITY + "/module/" + packageName);
    }

    private Logger logger;

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

    /**
     * The provider may be started by a module's query before the manager
     * app has ever been launched. In that case the in-memory cache is
     * empty — force a disk load so the response is accurate.
     */
    private void ensureModulesLoaded() {
        try {
            ModuleLoader loader = ModuleLoader.getInstance(getContext());
            if (loader.getCachedModules().isEmpty()) {
                loader.loadModules();
            }
        } catch (Throwable ignored) {}
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

        if (getContext() == null) return null;

        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");

        if (segments.length >= 3 && "module".equals(segments[1])) {
            return queryModule(segments[2]);
        }
        if (segments.length >= 2 && "modules".equals(segments[1])) {
            return queryModules();
        }
        if (segments.length >= 2 && "info".equals(segments[1])) {
            return queryInfo();
        }

        return new MatrixCursor(new String[]{"value"});
    }

    private Cursor queryModule(String pkg) {
        ensureModulesLoaded();

        ModuleInfo m = null;
        try {
            m = ModuleLoader.getInstance(getContext()).getModule(pkg);
        } catch (Throwable ignored) {}

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
                c.addRow(new Object[]{m.packageName});
            }
        } catch (Throwable ignored) {}
        return c;
    }

    private Cursor queryInfo() {
        ensureModulesLoaded();

        int count = 0;
        try {
            count = ModuleLoader.getInstance(getContext()).getEnabledModules().size();
        } catch (Throwable ignored) {}

        MatrixCursor c = new MatrixCursor(
            new String[]{"framework", "version", "count", "enabled"});
        c.addRow(new Object[]{"ShizuPosed", 1, count, 1});
        return c;
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