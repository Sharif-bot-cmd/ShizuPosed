package com.shizuposed.manager.utils;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;

import java.io.File;

/**
 * DexOptimizeWrapper
 *
 * Runs dex2oat on a module's cached dex before it is pushed to the
 * shell side, so the target process loads a pre-optimized dex instead
 * of triggering compilation on first use.
 *
 * The wrapper is designed to be transparent: if dex2oat succeeds, the
 * optimized output replaces the original dex. If it fails for any
 * reason — dex2oat missing, out of memory, incompatible filters,
 * permission error — the original dex is used unchanged. A broken
 * optimization never breaks module loading.
 *
 * This is a performance improvement, not a security measure. Its
 * value is faster module loading on cold start.
 */
public final class DexOptimizeWrapper {

    private static final String TAG = "DexOptimize";

    /** Compiler filters to try in order. */
    private static final String[] FILTERS = {
        "speed",
        "speed-profile",
        "quicken",
    };

    private DexOptimizeWrapper() {}

    /**
     * Optimize a dex file in place. Returns the path to the dex to
     * use — either the optimized output or the original if
     * optimization didn't run or failed.
     *
     * @param ctx        context for logging
     * @param shizuku    the Shizuku helper to run commands through
     * @param dexPath    absolute path to the cached dex file
     * @param logger     logger, may be null
     * @return the path to use, or the original path on failure
     */
    public static String optimize(Context ctx,
                                  ShizukuHelper shizuku,
                                  String dexPath,
                                  Logger logger) {
        if (dexPath == null) return null;

        File original = new File(dexPath);
        if (!original.exists() || original.length() == 0) {
            if (logger != null) {
                logger.d(TAG + ": nothing to optimize at " + dexPath);
            }
            return dexPath;
        }

        // If we already have a valid optimized output, use it.
        String optimizedPath = dexPath + ".oat";
        File existingOptimized = new File(optimizedPath);
        if (existingOptimized.exists() && existingOptimized.length() > 0
                && existingOptimized.lastModified() >= original.lastModified()) {
            if (logger != null) {
                logger.d(TAG + ": using cached optimized dex at "
                    + optimizedPath);
            }
            return optimizedPath;
        }

        if (shizuku == null || !shizuku.isAuthorized()) {
            if (logger != null) {
                logger.d(TAG + ": Shizuku unavailable, skipping optimize");
            }
            return dexPath;
        }

        // Try each filter in order. The first that succeeds wins.
        // dex2oat writes to a temporary path so a partial run never
        // leaves the optimized file in an inconsistent state.
        String tempPath = optimizedPath + ".tmp";
        for (String filter : FILTERS) {
            if (tryOptimize(shizuku, dexPath, tempPath, filter, logger)) {
                File temp = new File(tempPath);
                File dst = new File(optimizedPath);
                if (dst.exists()) dst.delete();
                if (temp.renameTo(dst)) {
                    dst.setReadable(true, false);
                    if (logger != null) {
                        logger.i(TAG + ": optimized " + dexPath
                            + " with filter " + filter);
                    }
                    return optimizedPath;
                }
            }
        }

        if (logger != null) {
            logger.d(TAG + ": no filter succeeded for " + dexPath
                + ", using original");
        }
        // Clean up any partial output.
        try { new File(tempPath).delete(); } catch (Throwable ignored) {}
        return dexPath;
    }

    private static boolean tryOptimize(ShizukuHelper shizuku,
                                       String dexPath,
                                       String outPath,
                                       String filter,
                                       Logger logger) {
        try {
            // The commands here mirror what the platform's own
            // PackageManager does when it optimizes an APK. They
            // are run via Shizuku because the target shell UID is
            // the one that will eventually load the dex.
            String cmd = "dex2oat"
                + " --dex-file=" + dexPath
                + " --oat-file=" + outPath
                + " --instruction-set=arm64"
                + " --compiler-filter=" + filter
                + " --runtime-arg -Xms64m"
                + " --runtime-arg -Xmx512m"
                + " 2>&1";

            ShellUtils.CommandResult r = shizuku.executeCommand(cmd);
            if (r == null) {
                if (logger != null) {
                    logger.d(TAG + ": " + filter + " produced no result");
                }
                return false;
            }
            // dex2oat is chatty on stderr. Success is when the
            // output file exists and has content, not when exit code
            // is 0.
            File out = new File(outPath);
            if (out.exists() && out.length() > 0) {
                if (logger != null) {
                    logger.d(TAG + ": " + filter + " succeeded ("
                        + out.length() + " bytes)");
                }
                return true;
            }
            if (logger != null) {
                logger.d(TAG + ": " + filter + " failed: "
                    + r.getStderrString());
            }
            return false;
        } catch (Throwable t) {
            if (logger != null) {
                logger.d(TAG + ": " + filter + " threw: " + t.getMessage());
            }
            return false;
        }
    }
}