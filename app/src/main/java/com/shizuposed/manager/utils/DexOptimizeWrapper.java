package com.shizuposed.manager.utils;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;

import java.io.File;

/**
 * DexOptimizeWrapper
 *
 * Returns the dex path the caller should use. The current
 * implementation does not perform optimization; see WHY below.
 *
 * WHY THIS IS A NO-OP
 * -------------------
 * The original implementation shelled out to `dex2oat` with
 * `--dex-file=<in> --oat-file=<out>`, then copied `<out>` to
 * `<pkg>.dex` on the shell side, where XposedHook loaded it with
 * `DexClassLoader`.
 *
 * That does not produce a faster-loading dex, for three reasons:
 *
 *   1. `dex2oat` writes an OAT container to `--oat-file`. A
 *      `DexClassLoader` does not load OAT containers; it loads
 *      `.dex` files, or `.odex` files produced under specific
 *      conditions that ART validates against the current boot
 *      image hash, ISA, and other runtime configuration.
 *
 *   2. The ART runtime validates the OAT header on load. If the
 *      file was produced by a shell-side dex2oat whose boot image
 *      hash or ISA doesn't match the target process's runtime,
 *      ART refuses it and the DexClassLoader falls back to
 *      interpreting the original dex. Silent failure — no
 *      speedup, no error the caller can see.
 *
 *   3. dex2oat produced the file as shell-UID ownership. Even
 *      with mode 0644, the target's SELinux domain sometimes
 *      can't read it. Same silent failure.
 *
 * The safe alternative is to let DexClassLoader handle
 * optimization itself, in the target process, using the target's
 * own dex2oat and runtime configuration. That produces a
 * correctly-configured odex in the target's data directory. It's
 * slower on first load (the first load has to compile), but it
 * works.
 *
 * This wrapper therefore returns the input path unchanged. Callers
 * that depend on the previous behavior should drop the
 * dexOptimize pref — it was already a no-op in effect, and
 * removing it makes that explicit.
 *
 * KEPT AS A STABLE API
 * --------------------
 * The class is retained so callers that reference it (e.g.
 * ShizuPosedService.pushModulesToShellDir, gated on
 * XStealthPrefs.isDexOptimizeEnabled) don't have to change. The
 * toggle still works, it just has no measurable effect.
 */
public final class DexOptimizeWrapper {

    private static final String TAG = "DexOptimize";

    private DexOptimizeWrapper() {}

    /**
     * Returns the dex path the caller should use. Currently a
     * pass-through; see the class doc for why.
     *
     * @param ctx        unused; kept for signature compatibility
     * @param shizuku    unused; kept for signature compatibility
     * @param dexPath    absolute path to the cached dex file
     * @param logger     logger, may be null
     * @return the input path unchanged
     */
    public static String optimize(Context ctx,
                                  ShizukuHelper shizuku,
                                  String dexPath,
                                  Logger logger) {
        if (dexPath == null) return null;

        // If a previous run left an .oat or .odex file lying
        // around, the caller's caller (pushModulesToShellDir) may
        // have its own expectations about which path to copy. The
        // safest thing is to leave everything as-is and return
        // the original dex path. The caller copies that path.
        //
        // Clean up any stale artifacts so disk usage doesn't grow
        // on repeated installs. Best-effort, silent on failure.
        try {
            File oat = new File(dexPath + ".oat");
            if (oat.exists()) oat.delete();
            File oatTmp = new File(dexPath + ".oat.tmp");
            if (oatTmp.exists()) oatTmp.delete();
            File odex = new File(dexPath + ".odex");
            if (odex.exists()) odex.delete();
            File vdex = new File(dexPath + ".vdex");
            if (vdex.exists()) vdex.delete();
        } catch (Throwable ignored) {}

        if (logger != null) {
            logger.d(TAG + ": returning original dex for " + dexPath
                + " (optimization disabled; see class doc)");
        }
        return dexPath;
    }
}