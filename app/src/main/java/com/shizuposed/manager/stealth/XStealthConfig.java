package com.shizuposed.manager.stealth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XStealthConfig
 *
 * Shell-side view of XStealth's settings. Read once per process
 * from <shell-base>/modules/xstealth.json.
 *
 * SMART FOREGROUND (Design B)
 * ---------------------------
 * There is no dedicated master toggle for smart foreground hiding.
 * The feature is active when both of these are true:
 *
 *   1. At least one of hideDevOptions / hideAdb is on. Those are
 *      the same toggles that gate XStealth's read interception;
 *      there is no second switch. Turning both off disables both
 *      the read interception and the state change.
 *
 *   2. smartForegroundScope is non-empty. The scope names the
 *      packages whose foreground triggers the state change. An
 *      empty scope means the state change never runs, even if
 *      the signal toggles are on.
 *
 * The user opts into the state change by adding apps to the
 * scope, not by flipping a separate switch. This keeps the
 * detail sheet to one control per signal, which is how it worked
 * before this feature existed.
 *
 * STEALTH BUILD
 * -------------
 * Two fields carry the actual package name and status provider
 * authority of the running manager. They are written by
 * XStealthStatusWriter from the manager process, and read here by
 * PackageCheck and RunningProcessCheck to decide which names to
 * hide.
 *
 * In the standard build these are "com.shizuposed.manager" and
 * "com.shizuposed.manager.status". In the stealth build they are
 * the scrambled com.<x>.<y> equivalents. Reading them from the
 * config instead of hardcoding means the same check code hides
 * whichever build is actually installed.
 */
public final class XStealthConfig {

    public final boolean enabled;
    public final boolean nextEnabled;
    public final boolean hideDevOptions;
    public final boolean hideAdb;
    public final boolean hideSettingsFileReads;
    public final boolean hideSocketDaemons;
    public final Set<String> socketDaemons;
    public final boolean hideShizukuPackage;
    public final boolean hideShizuPosedPackage;
    public final boolean hideRunningProcesses;
    public final boolean hideProcFs;
    public final boolean apiProtection;
    public final boolean dexOptimize;

    public final boolean hideSystemProperties;
    public final boolean hideBuildFields;
    public final boolean scrubShizuPosedProperties;
    public final boolean scrubThreadNames;

    public final boolean bridgeEnabled;

    public final boolean methodBaseline;
    public final boolean unsafeGate;

    public final boolean hideCachedDetectionValues;

    public final Map<String, List<String>> cachedValueKeys;

    /**
     * Packages whose foreground should trigger a state change to
     * Developer Options / ADB. Empty by default. Non-empty turns
     * on the state-change path when a signal toggle is also on.
     */
    public final Set<String> smartForegroundScope;

    public final Set<String> scope;

    /**
     * Actual package name of the installed manager. Set by
     * XStealthStatusWriter from the running manager's
     * getPackageName(). Fallback is the standard name so an old
     * config file that predates this field still hides the
     * standard package.
     */
    public final String shizuPosedPackageName;

    /**
     * Actual status provider authority of the installed manager.
     * Set by XStealthStatusWriter from the running manager's
     * manifest. Fallback is the standard authority.
     */
    public final String statusAuthority;

    private XStealthConfig(JSONObject o) {
        enabled               = o.optBoolean("enabled", false);
        nextEnabled           = o.optBoolean("nextEnabled", false);
        hideDevOptions        = o.optBoolean("hideDevOptions", true);
        hideAdb               = o.optBoolean("hideAdb", true);
        hideSettingsFileReads = o.optBoolean("hideSettingsFileReads", true);
        hideSocketDaemons     = o.optBoolean("hideSocketDaemons", false);
        hideShizukuPackage    = o.optBoolean("hideShizukuPackage", true);
        hideShizuPosedPackage = o.optBoolean("hideShizuPosedPackage", true);
        hideRunningProcesses  = o.optBoolean("hideRunningProcesses", true);
        hideProcFs            = o.optBoolean("hideProcFs", true);
        apiProtection = o.optBoolean("apiProtection", false);
        dexOptimize   = o.optBoolean("dexOptimize", false);

        hideSystemProperties      = o.optBoolean("hideSystemProperties", true);
        hideBuildFields           = o.optBoolean("hideBuildFields", true);
        scrubShizuPosedProperties = o.optBoolean("scrubShizuPosedProperties", true);
        scrubThreadNames          = o.optBoolean("scrubThreadNames", true);
        bridgeEnabled = o.optBoolean("bridgeEnabled", false);
        methodBaseline = o.optBoolean("methodBaseline", true);
        unsafeGate     = o.optBoolean("unsafeGate", true);

        hideCachedDetectionValues =
            o.optBoolean("hideCachedDetectionValues", false);

        shizuPosedPackageName = o.optString(
            "shizuPosedPackageName", "com.shizuposed.manager");
        statusAuthority = o.optString(
            "statusAuthority", "com.shizuposed.manager.status");

        Map<String, List<String>> cvk = new HashMap<>();
        JSONObject cvkObj = o.optJSONObject("cachedValueKeys");
        if (cvkObj != null) {
            Iterator<String> it = cvkObj.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                if (pkg == null || pkg.isEmpty()) continue;
                JSONArray keys = cvkObj.optJSONArray(pkg);
                if (keys == null) continue;
                List<String> list = new ArrayList<>(keys.length());
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.optString(i, null);
                    if (k != null && !k.isEmpty()) list.add(k);
                }
                if (!list.isEmpty()) {
                    cvk.put(pkg, Collections.unmodifiableList(list));
                }
            }
        }
        cachedValueKeys = Collections.unmodifiableMap(cvk);

        Set<String> sfg = new HashSet<>();
        JSONArray sfarr = o.optJSONArray("smartForegroundScope");
        if (sfarr != null) {
            for (int i = 0; i < sfarr.length(); i++) {
                String p = sfarr.optString(i, null);
                if (p != null && !p.isEmpty()) sfg.add(p);
            }
        }
        smartForegroundScope = Collections.unmodifiableSet(sfg);

        Set<String> s = new HashSet<>();
        JSONArray arr = o.optJSONArray("scope");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, null);
                if (p != null && !p.isEmpty()) s.add(p);
            }
        }
        scope = Collections.unmodifiableSet(s);

        Set<String> d = new HashSet<>();
        JSONArray darr = o.optJSONArray("socketDaemons");
        if (darr != null) {
            for (int i = 0; i < darr.length(); i++) {
                String name = darr.optString(i, null);
                if (name != null && !name.isEmpty()) d.add(name);
            }
        }
        socketDaemons = Collections.unmodifiableSet(d);
    }

    public boolean shouldApplyTo(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (scope.isEmpty()) return true;
        return scope.contains(pkg);
    }

    public boolean isScopeAllApps() {
        return scope.isEmpty();
    }

    public List<String> cachedValueKeysFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return Collections.emptyList();
        List<String> list = cachedValueKeys.get(pkg);
        return list == null ? Collections.emptyList() : list;
    }

    public boolean isSmartForegroundScoped(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return smartForegroundScope.contains(pkg);
    }

    /**
     * True when the smart-foreground state change is enabled: at
     * least one of the signal toggles is on and at least one app
     * is in the smart-foreground scope.
     *
     * Callers that want to know whether it's worth starting the
     * foreground monitor should check this.
     */
    public boolean isSmartForegroundActive() {
        if (!hideDevOptions && !hideAdb) return false;
        return !smartForegroundScope.isEmpty();
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