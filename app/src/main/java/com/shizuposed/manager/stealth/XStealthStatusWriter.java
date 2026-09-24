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
 * The config now includes the scope. An absent or empty "scope"
 * array means "all apps" — the shell side treats it the same way
 * the manager does.
 */
public final class XStealthStatusWriter {

    private static final String TAG = "XStealthWriter";
    private static final String FILENAME = "xstealth.json";

    private XStealthStatusWriter() {}

    /**
     * Serialize prefs to JSON. Public so the detail sheet can show
     * exactly what will be written.
     */
    public static JSONObject buildConfig(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("enabled",              XStealthPrefs.isEnabled(c));
        o.put("nextEnabled",          XStealthPrefs.isNextEnabled(c));
        o.put("hideDevOptions",       XStealthPrefs.isHideDevOptions(c));
        o.put("hideAdb",              XStealthPrefs.isHideAdb(c));
        o.put("hideShizukuPackage",   XStealthPrefs.isHideShizukuPackage(c));
        o.put("hideShizuPosedPackage",XStealthPrefs.isHideShizuPosedPackage(c));
        o.put("hideRunningProcesses", XStealthPrefs.isHideRunningProcesses(c));
        o.put("hideProcFs",           XStealthPrefs.isHideProcFs(c));
        o.put("apiProtection",        XStealthPrefs.isApiProtectionEnabled(c));
        o.put("dexOptimize",          XStealthPrefs.isDexOptimizeEnabled(c));

        // Scope. Always present so the shell side can distinguish
        // "no scope file" (old config) from "empty scope" (all apps).
        JSONArray scopeArr = new JSONArray();
        Set<String> scope = XStealthPrefs.getScope(c);
        if (scope != null) {
            for (String p : scope) {
                if (p != null && !p.isEmpty()) scopeArr.put(p);
            }
        }
        o.put("scope", scopeArr);

        return o;
    }

    /**
     * Write the config to a local file in external app storage.
     * Returns the local file, or null on failure.
     */
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

    /**
     * Write locally, then push to the shell side via Shizuku.
     */
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