package com.shizuposed.manager.stealth;

import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;

/**
 * XStealthConfig
 *
 * Shell-side view of XStealth's settings. Read once per process
 * from <shell-base>/modules/xstealth.json, which the manager
 * writes before launching a target under ShizuPosed.
 */
public final class XStealthConfig {

    public final boolean enabled;
    public final boolean nextEnabled;
    public final boolean hideDevOptions;
    public final boolean hideAdb;
    public final boolean hideShizukuPackage;
    public final boolean hideShizuPosedPackage;
    public final boolean hideRunningProcesses;
    public final boolean hideProcFs;
    public final boolean apiProtection;
    public final boolean dexOptimize;

    private XStealthConfig(JSONObject o) {
        enabled               = o.optBoolean("enabled", false);
        nextEnabled           = o.optBoolean("nextEnabled", false);
        hideDevOptions        = o.optBoolean("hideDevOptions", true);
        hideAdb               = o.optBoolean("hideAdb", true);
        hideShizukuPackage    = o.optBoolean("hideShizukuPackage", true);
        hideShizuPosedPackage = o.optBoolean("hideShizuPosedPackage", true);
        hideRunningProcesses  = o.optBoolean("hideRunningProcesses", true);
        hideProcFs            = o.optBoolean("hideProcFs", true);
        apiProtection = o.optBoolean("apiProtection", false);
        dexOptimize   = o.optBoolean("dexOptimize", false);
    }

    private static volatile XStealthConfig sCached;
    private static volatile boolean sAttempted = false;

    public static XStealthConfig load() {
        if (sAttempted) return sCached;
        sAttempted = true;

        try {
            String base = System.getProperty("shizuposed.shell.base",
                "/data/user/0/com.android.shell/files/.syscall_cache");
            File f = new File(base, "modules/xstealth.json");
            if (!f.exists()) {
                sCached = defaults();
                return sCached;
            }
            StringBuilder sb = new StringBuilder();
            try (FileReader r = new FileReader(f)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            sCached = new XStealthConfig(new JSONObject(sb.toString()));
            return sCached;
        } catch (Throwable t) {
            sCached = defaults();
            return sCached;
        }
    }

    public static void invalidate() {
        sAttempted = false;
        sCached = null;
    }

    private static XStealthConfig defaults() {
        return new XStealthConfig(new JSONObject());
    }
}