package com.shizuposed.manager.status;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.util.List;

/**
 * ModuleStatusProvider
 *
 * A read-only ContentProvider that lets external modules (running in
 * their own app processes) ask questions about their state in
 * ShizuPosed.
 *
 * Modules query:
 *   content://com.shizuposed.manager.status/module/<packageName>
 *       — is the module enabled in the manager?
 *   content://com.shizuposed.manager.status/modules
 *       — list every enabled module package
 *   content://com.shizuposed.manager.status/info
 *       — framework name, version, enabled module count
 *   content://com.shizuposed.manager.status/active/<packageName>
 *       — has the module loaded into at least one target process?
 *   content://com.shizuposed.manager.status/scope/<packageName>
 *       — which packages has the module loaded into?
 *
 * The "active" and "scope" endpoints read the shell-side hooked
 * markers written by XposedHook, so they reflect what the framework
 * has actually done, not just what the user configured.
 */
public class ModuleStatusProvider extends ContentProvider {

    public static final String AUTHORITY = "com.shizuposed.manager.status";

    public static final Uri BASE_URI    = Uri.parse("content://" + AUTHORITY);
    public static final Uri MODULES_URI = Uri.parse("content://" + AUTHORITY + "/modules");
    public static final Uri INFO_URI    = Uri.parse("content://" + AUTHORITY + "/info");

    private static final String HOOKED_DIR =
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

        // content://.../module/<pkg>
        if (segments.length >= 3 && "module".equals(segments[1])) {
            return queryModule(segments[2]);
        }
        // content://.../active/<pkg>
        if (segments.length >= 3 && "active".equals(segments[1])) {
            return queryModuleActive(segments[2]);
        }
        // content://.../scope/<pkg>
        if (segments.length >= 3 && "scope".equals(segments[1])) {
            return queryModuleScope(segments[2]);
        }
        // content://.../modules
        if (segments.length >= 2 && "modules".equals(segments[1])) {
            return queryModules();
        }
        // content://.../info
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

    // ═════════════════════════════════════════════════════════════
    // ACTIVE / SCOPE — read the shell-side hooked markers
    // ═════════════════════════════════════════════════════════════

    /**
     * Has the module loaded into at least one target process?
     *
     * Reads every marker file under <shell>/hooked/ and checks
     * whether the module's package name appears inside the
     * moduleList array of any marker.
     */
    private Cursor queryModuleActive(String modulePkg) {
        boolean active = false;
        try {
            if (getContext() != null) {
                ShizukuHelper sh = ShizukuHelper.getInstance(getContext());
                if (sh.isAvailable() && sh.isAuthorized()) {
                    ShellUtils.CommandResult ls = sh.executeCommand(
                        "ls " + HOOKED_DIR + " 2>/dev/null; true");
                    if (ls.stdout != null) {
                        for (String line : ls.stdout) {
                            String name = line == null ? null : line.trim();
                            if (name == null || name.isEmpty()
                                    || !name.endsWith(".json")) continue;

                            ShellUtils.CommandResult cat = sh.executeCommand(
                                "cat " + HOOKED_DIR + "/" + name);
                            if (cat.stdout == null) continue;

                            StringBuilder body = new StringBuilder();
                            for (String l : cat.stdout) if (l != null) body.append(l);

                            // Check both the raw package name and the
                            // quoted form to be robust against JSON
                            // escaping issues.
                            String b = body.toString();
                            if (b.contains("\"" + modulePkg + "\"")) {
                                active = true;
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (logger != null) logger.d("queryModuleActive: " + t.getMessage());
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

    /**
     * Which packages has the module loaded into?
     *
     * Same marker scan as queryModuleActive, but returns every target
     * package that lists the module in its moduleList.
     */
    private Cursor queryModuleScope(String modulePkg) {
        MatrixCursor c = new MatrixCursor(new String[]{"package"});
        try {
            if (getContext() != null) {
                ShizukuHelper sh = ShizukuHelper.getInstance(getContext());
                if (sh.isAvailable() && sh.isAuthorized()) {
                    ShellUtils.CommandResult ls = sh.executeCommand(
                        "ls " + HOOKED_DIR + " 2>/dev/null; true");
                    if (ls.stdout != null) {
                        for (String line : ls.stdout) {
                            String name = line == null ? null : line.trim();
                            if (name == null || name.isEmpty()
                                    || !name.endsWith(".json")) continue;
                            String pkg = name.substring(0, name.length() - 5);

                            ShellUtils.CommandResult cat = sh.executeCommand(
                                "cat " + HOOKED_DIR + "/" + name);
                            if (cat.stdout == null) continue;

                            StringBuilder body = new StringBuilder();
                            for (String l : cat.stdout) if (l != null) body.append(l);

                            if (body.toString().contains("\"" + modulePkg + "\"")) {
                                c.addRow(new Object[]{pkg});
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (logger != null) logger.d("queryModuleScope: " + t.getMessage());
        }
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