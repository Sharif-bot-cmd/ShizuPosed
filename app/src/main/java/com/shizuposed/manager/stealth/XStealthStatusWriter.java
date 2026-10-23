package com.shizuposed.manager.stealth;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Set;

/**
 * XStealthStatusWriter
 *
 * Serializes XStealthPrefs into the JSON format that the shell-side
 * XStealthConfig expects, then pushes it to
 * <shell-base>/modules/xstealth.json.
 *
 * Called by ShizuPosedService on every module repush so the shell
 * side is never out of date.
 *
 * SMART FOREGROUND
 * ----------------
 * The manager's smart-foreground scope is a Set<String> in
 * XStealthPrefs. It serializes to the "smartForegroundScope" JSON
 * array. The shell-side config reads it from there. If the
 * preference is empty, the array is empty; a missing array on the
 * shell side is treated the same as an empty one.
 *
 * STEALTH BUILD
 * -------------
 * The config also carries the actual package name and status
 * provider authority of the installed manager. This is how the
 * shell-side checks learn which name to hide, since the stealth
 * build's package name is not known at compile time.
 */
public final class XStealthStatusWriter {

    private static final String TAG = "XStealthWriter";
    private static final String FILENAME = "xstealth.json";

    private XStealthStatusWriter() {}

    public static JSONObject buildConfig(Context c) throws Exception {
        JSONObject o = new JSONObject();

        o.put("enabled",              XStealthPrefs.isEnabled(c));
        o.put("nextEnabled",          XStealthPrefs.isNextEnabled(c));
        o.put("bridgeEnabled",        XStealthPrefs.isBridgeEnabled(c));

        o.put("hideDevOptions",       XStealthPrefs.isHideDevOptions(c));
        o.put("hideAdb",              XStealthPrefs.isHideAdb(c));
        o.put("hideShizukuPackage",   XStealthPrefs.isHideShizukuPackage(c));
        o.put("hideShizuPosedPackage",XStealthPrefs.isHideShizuPosedPackage(c));
        o.put("hideRunningProcesses", XStealthPrefs.isHideRunningProcesses(c));
        o.put("hideProcFs",           XStealthPrefs.isHideProcFs(c));

        o.put("hideSystemProperties",       XStealthPrefs.isHideSystemProperties(c));
        o.put("hideBuildFields",            XStealthPrefs.isHideBuildFields(c));
        o.put("scrubShizuPosedProperties",  XStealthPrefs.isScrubShizuPosedProperties(c));
        o.put("scrubThreadNames",           XStealthPrefs.isScrubThreadNames(c));
        o.put("methodBaseline", XStealthPrefs.isMethodBaselineEnabled(c));
        o.put("unsafeGate",     XStealthPrefs.isUnsafeGateEnabled(c));

        o.put("apiProtection",        XStealthPrefs.isApiProtectionEnabled(c));
        o.put("dexOptimize",          XStealthPrefs.isDexOptimizeEnabled(c));

        // Actual package name and status authority of the running
        // manager. In the standard build these are the well-known
        // com.shizuposed.manager names; in the stealth build they
        // are the scrambled com.<x>.<y> equivalents. The
        // shell-side checks read them from here instead of
        // hardcoding, so the same code hides whichever build is
        // installed.
        o.put("shizuPosedPackageName", c.getPackageName());
        o.put("statusAuthority",       statusAuthorityOf(c));

        JSONArray scopeArr = new JSONArray();
        Set<String> scope = XStealthPrefs.getScope(c);
        if (scope != null) {
            for (String p : scope) {
                if (p != null && !p.isEmpty()) scopeArr.put(p);
            }
        }
        o.put("scope", scopeArr);

        JSONArray sfScopeArr = new JSONArray();
        Set<String> sfScope = XStealthPrefs.getSmartForegroundScope(c);
        if (sfScope != null) {
            for (String p : sfScope) {
                if (p != null && !p.isEmpty()) sfScopeArr.put(p);
            }
        }
        o.put("smartForegroundScope", sfScopeArr);

        return o;
    }

    /**
     * Resolve the status provider authority declared in this build's
     * manifest.
     *
     * The standard build's authority is "com.shizuposed.manager.status".
     * The stealth build's authority is "com.<x>.<y>.status". Reading
     * it from PackageManager means the value is always correct for
     * whichever build is running.
     *
     * Fallback: the standard authority, for the case where the
     * provider isn't found (shouldn't happen, but doesn't break
     * anything if it does).
     */
    private static String statusAuthorityOf(Context c) {
        try {
            android.content.pm.PackageInfo info = c.getPackageManager()
                .getPackageInfo(c.getPackageName(),
                    android.content.pm.PackageManager.GET_PROVIDERS);
            if (info.providers != null) {
                for (android.content.pm.ProviderInfo pi : info.providers) {
                    if (pi.name != null
                            && pi.name.endsWith("ModuleStatusProvider")) {
                        return pi.authority;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.getInstance(c).d(TAG + ": statusAuthorityOf failed: "
                + t.getMessage());
        }
        return "com.shizuposed.manager.status";
    }

    public static File writeLocal(Context c) {
        try {
            File external = c.getExternalFilesDir(null);
            if (external == null) external = c.getFilesDir();
            File modulesDir = new File(external, "modules");
            if (!modulesDir.exists()) modulesDir.mkdirs();

            File out = new File(modulesDir, FILENAME);
            String json = buildConfig(c).toString();
            try (FileWriter w = new FileWriter(out, false)) {
                w.write(json);
            }
            out.setReadable(true, false);
            return out;
        } catch (Throwable t) {
            Logger.getInstance(c).e(TAG + ": writeLocal failed: " + t.getMessage());
            return null;
        }
    }

    public static boolean push(Context c, String shellBase,
                               ShizukuHelper shizuku, Logger logger) {
        File local = writeLocal(c);
        if (local == null) return false;

        if (shellBase == null || shellBase.isEmpty()) {
            if (logger != null) logger.w(TAG + ": no shell base, skipping push");
            return false;
        }
        if (shizuku == null || !shizuku.isAuthorized()) {
            if (logger != null) logger.w(TAG + ": Shizuku not authorized");
            return false;
        }

        try {
            String modulesDir = shellBase + "/modules";
            String dstPath = modulesDir + "/" + FILENAME;

            shizuku.executeCommand("mkdir -p " + modulesDir);
            shizuku.executeCommand("chmod 755 " + shellBase + " " + modulesDir);

            String copy = "sh -c 'cat \"" + local.getAbsolutePath()
                + "\" > \"" + dstPath + "\"'";
            ShellUtils.CommandResult r = shizuku.executeCommand(copy);
            if (!r.isSuccess()) {
                if (logger != null) logger.w(TAG + ": push failed: "
                    + r.getStderrString());
                return false;
            }

            shizuku.executeCommand("chmod 644 " + dstPath);
            if (logger != null) logger.i(TAG + ": pushed " + dstPath);
            return true;
        } catch (Throwable t) {
            if (logger != null) logger.e(TAG + ": push failed: " + t.getMessage());
            return false;
        }
    }
}