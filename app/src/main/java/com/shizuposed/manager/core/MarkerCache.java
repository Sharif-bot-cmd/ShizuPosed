package com.shizuposed.manager.core;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.FileUtils;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Local, on-disk mirror of the shell-side hooked markers.
 *
 * WHY THIS EXISTS
 * ---------------
 * The shell-side XposedHook writes one JSON marker per hooked target
 * package to <shell-base>/hooked/<pkg>.json. ModuleStatusProvider
 * needs to read those markers to answer "is this module active?"
 *
 * Reading them directly requires Shizuku: ls the directory, cat each
 * file. That's N+1 binder round-trips per query, and it depends on
 * Shizuku being authorized at the moment a module UI happens to
 * poll. If Shizuku has been killed or restarted, the query silently
 * returns "not active" even though the shell-side markers are sitting
 * on disk unchanged.
 *
 * This class mirrors the markers into the manager's own filesDir
 * once per ProcessMonitor cycle, using a single compound shell
 * command. ModuleStatusProvider then reads from the local mirror,
 * which is fast and independent of Shizuku's state at query time.
 *
 * LIFECYCLE
 * ---------
 *   • refresh(context)  — pull the markers once. Called by
 *                         ProcessMonitor on a timer and by
 *                         ModuleStatusProvider on a cold cache.
 *   • read(context)     — return a snapshot of the local mirror.
 *                         No Shizuku, no file writes.
 *
 * The mirror is not cleaned up eagerly. If a marker is deleted on
 * the shell side (target uninstalled or module removed), the stale
 * file remains until the next refresh overwrites the directory.
 * That's acceptable: a stale marker causes isModuleActive to return
 * true for a module that was recently active, which is what a user
 * would expect during the small window after a scope change.
 */
public final class MarkerCache {

    private static final String TAG = "MarkerCache";

    /** Directory name under filesDir. */
    public static final String CACHE_DIR_NAME = ".markers";

    /**
     * Delimiter between markers in the compound shell output. Chosen
     * so it cannot collide with anything inside a real marker JSON:
     * the JSON body is a single line, and the delimiter is on its
     * own line.
     */
    private static final String DELIM_BEGIN = "=== BEGIN ";
    private static final String DELIM_END   = "=== END";

    /** Serializes refresh() calls so two refreshes don't interleave. */
    private static final Object refreshLock = new Object();

    private MarkerCache() {}

    /** Returns the cache directory, creating it if needed. */
    public static File getCacheDir(Context context) {
        File dir = new File(context.getFilesDir(), CACHE_DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Pull the shell-side markers into the local mirror. Safe to
     * call from any thread; serialized internally. Returns the
     * number of markers written, or -1 if Shizuku wasn't available.
     *
     * Failure is non-destructive: if the shell command fails, the
     * existing mirror is left untouched so a stale but valid cache
     * continues to answer queries.
     */
    public static int refresh(Context context) {
        if (context == null) return -1;

        synchronized (refreshLock) {
            Logger logger = Logger.getInstance(context);
            ShizukuHelper sh = ShizukuHelper.getInstance(context);

            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                if (logger != null) {
                    logger.d("[" + TAG + "] refresh skipped: Shizuku unavailable");
                }
                return -1;
            }

            String hookedDir;
            try {
                String base = ShizuPosedService.getResolvedShellBase();
                hookedDir = (base != null && !base.isEmpty())
                    ? base + "/hooked"
                    : null;
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("[" + TAG + "] getResolvedShellBase failed: "
                        + t.getMessage());
                }
                return -1;
            }
            if (hookedDir == null) return -1;

            // Single compound command. `cd` may fail if the directory
            // has been removed; `|| exit 0` turns that into a clean
            // no-op instead of a shell error.
            //
            // `for f in *.json` with no matches expands to the literal
            // pattern in some shells, so the `[ -f "$f" ] || continue`
            // guard is required.
            String cmd =
                "cd " + hookedDir + " 2>/dev/null || exit 0; "
                + "for f in *.json; do "
                + "  [ -f \"$f\" ] || continue; "
                + "  echo '" + DELIM_BEGIN + "'\"$f\"; "
                + "  cat \"$f\"; "
                + "  echo; "
                + "  echo '" + DELIM_END + "'; "
                + "done";

            ShellUtils.CommandResult r = sh.executeCommand(cmd);
            if (r == null) {
                if (logger != null) {
                    logger.w("[" + TAG + "] refresh: shell returned null");
                }
                return -1;
            }

            Map<String, String> parsed = parseMarkers(r.stdout);
            writeMirror(context, parsed);

            if (logger != null) {
                logger.i("[" + TAG + "] refresh: " + parsed.size()
                    + " marker(s) mirrored from " + hookedDir);
            }
            return parsed.size();
        }
    }

    /**
     * Return a snapshot of the local mirror. This is what
     * ModuleStatusProvider uses. Never calls Shizuku.
     *
     * @return map from target package name to raw marker JSON.
     */
    public static Map<String, String> read(Context context) {
        Map<String, String> out = new HashMap<>();
        if (context == null) return out;

        File dir = getCacheDir(context);
        File[] files = dir.listFiles();
        if (files == null) return out;

        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String pkg = name.substring(0, name.length() - 5);
            if (pkg.isEmpty()) continue;

            String body = FileUtils.readFile(f);
            if (body != null && !body.isEmpty()) {
                // Trim trailing newlines added by the shell echo, but
                // keep the JSON body's own whitespace.
                out.put(pkg, body.trim());
            }
        }
        return out;
    }

    /** Remove every file in the mirror. Used by cache-clear actions. */
    public static void clear(Context context) {
        if (context == null) return;
        File dir = getCacheDir(context);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PARSING
    // ═════════════════════════════════════════════════════════════

    /**
     * Parse the compound shell output into a map of pkg → JSON body.
     *
     * Format per marker:
     *
     *   === BEGIN <pkg>.json
     *   {"pkg":"<pkg>","moduleList":[...], ...}
     *
     *   === END
     *
     * The blank line before === END is the extra echo inserted by
     * the shell command, which cleanly separates the JSON body from
     * the end delimiter even if the JSON is single-line (which it is).
     */
    private static Map<String, String> parseMarkers(java.util.List<String> stdout) {
        Map<String, String> out = new HashMap<>();
        if (stdout == null) return out;

        String currentPkg = null;
        StringBuilder body = new StringBuilder();

        for (String raw : stdout) {
            if (raw == null) continue;

            if (raw.startsWith(DELIM_BEGIN)) {
                // Flush previous.
                if (currentPkg != null) {
                    String trimmed = body.toString().trim();
                    if (!trimmed.isEmpty()) out.put(currentPkg, trimmed);
                }
                String name = raw.substring(DELIM_BEGIN.length()).trim();
                // Strip trailing .json if present.
                if (name.endsWith(".json")) {
                    name = name.substring(0, name.length() - 5);
                }
                currentPkg = name.isEmpty() ? null : name;
                body.setLength(0);
                continue;
            }

            if (raw.trim().equals(DELIM_END)) {
                if (currentPkg != null) {
                    String trimmed = body.toString().trim();
                    if (!trimmed.isEmpty()) out.put(currentPkg, trimmed);
                }
                currentPkg = null;
                body.setLength(0);
                continue;
            }

            if (currentPkg != null) {
                body.append(raw).append('\n');
            }
        }

        // Flush trailing marker if the last === END never arrived.
        if (currentPkg != null) {
            String trimmed = body.toString().trim();
            if (!trimmed.isEmpty()) out.put(currentPkg, trimmed);
        }

        return out;
    }

    // ═════════════════════════════════════════════════════════════
    // WRITING
    // ═════════════════════════════════════════════════════════════

    /**
     * Write the parsed markers to the local mirror. Existing files
     * whose package is not in the new set are left alone — see the
     * class javadoc for why. Files whose package IS in the new set
     * are overwritten atomically (write to .tmp, then rename).
     */
    private static void writeMirror(Context context, Map<String, String> markers) {
        Logger logger = Logger.getInstance(context);
        File dir = getCacheDir(context);

        for (Map.Entry<String, String> e : markers.entrySet()) {
            String pkg = e.getKey();
            String body = e.getValue();
            if (pkg == null || body == null) continue;

            File dst = new File(dir, pkg + ".json");
            File tmp = new File(dir, pkg + ".json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(body.getBytes("UTF-8"));
                fos.flush();
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("[" + TAG + "] write mirror failed for "
                        + pkg + ": " + t.getMessage());
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                continue;
            }

            if (!tmp.renameTo(dst)) {
                // Some filesystems reject rename onto an existing
                // file. Delete then rename as a fallback.
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    if (logger != null) {
                        logger.w("[" + TAG + "] rename failed for " + pkg);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
    }
}