package com.shizuposed.manager.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.shizuposed.manager.ShizukuHelper;

/**
 * ModuleActivityLauncher
 *
 * Launches a module's configuration UI, working around modules that
 * have no launcher entry or whose settings activity is not exported.
 *
 * Two paths:
 *   1. Exported activity — started directly via Context.startActivity.
 *      Fast, no Shizuku round-trip.
 *   2. Non-exported activity — the manager is a different UID and
 *      cannot start it directly. Routed through Shizuku with
 *      `am start`, which shell UID is allowed to run against other
 *      apps' components.
 *
 * Callers that already know whether the activity is exported can
 * use ModuleActivityResolver.Result.isExported to choose the fast
 * path themselves and fall back to this class only when needed.
 */
public final class ModuleActivityLauncher {

    private static final String TAG = "ModuleLauncher";

    private ModuleActivityLauncher() {}

    /**
     * Resolve and launch the best activity for the given module.
     * Returns true if the launch command succeeded.
     */
    public static boolean launch(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return false;

        ModuleActivityResolver.Result result =
            ModuleActivityResolver.resolve(ctx, packageName);

        if (result == null || result.component == null) {
            Log.w(TAG, "no launchable activity for " + packageName);
            return false;
        }

        return launchComponent(ctx, result);
    }

    /**
     * Launch a specific component. Tries the direct path for exported
     * activities, then falls back to `am start` via Shizuku.
     */
    public static boolean launchComponent(Context ctx,
                                          ModuleActivityResolver.Result result) {
        if (ctx == null || result == null || result.component == null) return false;

        // 1. Direct path for exported activities.
        if (result.isExported) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(result.component);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                Log.i(TAG, "started directly: "
                    + result.component.flattenToString());
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "direct start failed for "
                    + result.component.flattenToString(), t);
                // Fall through to Shizuku.
            }
        }

        // 2. Shizuku fallback for non-exported or blocked activities.
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(ctx);
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                Log.w(TAG, "Shizuku unavailable or unauthorized");
                return false;
            }

            // `am start --user current -n <pkg>/<class>` runs as shell,
            // which can start non-exported components of other apps.
            String cmd = "am start --user current -n "
                + result.component.flattenToString();
            ShellUtils.CommandResult r = sh.executeCommand(cmd);
            if (r != null && r.isSuccess()) {
                Log.i(TAG, "am start succeeded for "
                    + result.component.flattenToString());
                return true;
            }
            String err = (r != null) ? r.getStderrString() : "(null result)";
            Log.w(TAG, "am start failed for "
                + result.component.flattenToString() + ": " + err);
        } catch (Throwable t) {
            Log.w(TAG, "am start threw for "
                + result.component.flattenToString(), t);
        }

        return false;
    }

    /**
     * Convenience: build the ComponentName string for a package and
     * class name. Useful when a caller already knows the class.
     */
    public static ComponentName component(String pkg, String className) {
        return new ComponentName(pkg, className);
    }
}