package com.shizuposed.manager.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

/**
 * ModuleActivityResolver
 *
 * Finds a launchable Activity for a module package. This exists
 * because a meaningful subset of Xposed modules — Core Patch,
 * several of the "system tweak" family, and various utility
 * modules — do not declare a LAUNCHER category on their settings
 * activity, and sometimes do not export it either.
 *
 * The launcher only shows apps with a LAUNCHER activity, so those
 * modules appear to "have no UI" even though they do. This resolver
 * walks the module's manifest and finds something worth launching.
 *
 * Order of preference:
 *   1. Standard launcher intent (respects user-disabled state)
 *   2. Launcher intent including disabled components
 *   3. Any activity declaring ACTION_MAIN, without LAUNCHER
 *   4. Any exported activity in the manifest
 *   5. Any activity at all
 *
 * The Result carries a reason string for logging and for the UI to
 * decide whether to hint at a fallback path.
 */
public final class ModuleActivityResolver {

    private static final String TAG = "ModuleActivity";

    private ModuleActivityResolver() {}

    /** Resolution result. */
    public static final class Result {
        public final ComponentName component;
        public final boolean isExported;
        public final String reason;

        Result(ComponentName component, boolean isExported, String reason) {
            this.component = component;
            this.isExported = isExported;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "Result{component=" + component
                + ", exported=" + isExported
                + ", reason=" + reason + "}";
        }
    }

    /**
     * Resolve the best launchable activity for the given package.
     * Returns null if the package has no activity in its manifest at all.
     */
    public static Result resolve(Context ctx, String packageName) {
        if (ctx == null || packageName == null) return null;
        PackageManager pm = ctx.getPackageManager();

        // 1. Standard launcher intent.
        try {
            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null && launch.getComponent() != null) {
                ComponentName cn = launch.getComponent();
                boolean exported = isExported(pm, cn);
                Log.i(TAG, "resolved for " + packageName + ": "
                    + cn.flattenToString() + " (launcher, exported="
                    + exported + ")");
                return new Result(cn, exported, "launcher");
            }
        } catch (Throwable t) {
            Log.w(TAG, "launcher intent failed for " + packageName, t);
        }

        // 2. Launcher intent including disabled components. Covers
        // modules with a disabled activity-alias in front of the
        // real settings activity.
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
            List<ResolveInfo> list = pm.queryIntentActivities(probe, flags);
            if (list != null && !list.isEmpty()) {
                ActivityInfo ai = list.get(0).activityInfo;
                ComponentName cn = new ComponentName(ai.packageName, ai.name);
                Log.i(TAG, "resolved for " + packageName + ": "
                    + cn.flattenToString() + " (disabled-launcher, exported="
                    + ai.exported + ")");
                return new Result(cn, ai.exported, "launcher-disabled-ok");
            }
        } catch (Throwable t) {
            Log.w(TAG, "disabled launcher query failed for " + packageName, t);
        }

        // 3. Any activity declaring ACTION_MAIN without LAUNCHER.
        //    This is the Core Patch case.
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN).setPackage(packageName);
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_ALL;
            List<ResolveInfo> list = pm.queryIntentActivities(probe, flags);
            if (list != null && !list.isEmpty()) {
                ActivityInfo ai = list.get(0).activityInfo;
                ComponentName cn = new ComponentName(ai.packageName, ai.name);
                Log.i(TAG, "resolved for " + packageName + ": "
                    + cn.flattenToString() + " (MAIN-no-launcher, exported="
                    + ai.exported + ")");
                return new Result(cn, ai.exported, "main-no-launcher");
            }
        } catch (Throwable t) {
            Log.w(TAG, "MAIN query failed for " + packageName, t);
        }

        // 4 & 5. Scan the manifest for any activity.
        try {
            PackageInfo pi = pm.getPackageInfo(packageName,
                PackageManager.GET_ACTIVITIES
                    | PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS);
            if (pi != null && pi.activities != null && pi.activities.length > 0) {
                // Prefer exported.
                for (ActivityInfo ai : pi.activities) {
                    if (ai != null && ai.exported) {
                        ComponentName cn = new ComponentName(ai.packageName, ai.name);
                        Log.i(TAG, "resolved for " + packageName + ": "
                            + cn.flattenToString() + " (first-exported)");
                        return new Result(cn, true, "first-exported");
                    }
                }
                // Then any.
                ActivityInfo ai = pi.activities[0];
                if (ai != null) {
                    ComponentName cn = new ComponentName(ai.packageName, ai.name);
                    Log.i(TAG, "resolved for " + packageName + ": "
                        + cn.flattenToString() + " (first-activity, exported="
                        + ai.exported + ")");
                    return new Result(cn, ai.exported, "first-activity");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "activity scan failed for " + packageName, t);
        }

        Log.w(TAG, "no launchable activity for " + packageName);
        return null;
    }

    private static boolean isExported(PackageManager pm, ComponentName cn) {
        try {
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            return ai != null && ai.exported;
        } catch (Throwable ignored) {
            return false;
        }
    }
}