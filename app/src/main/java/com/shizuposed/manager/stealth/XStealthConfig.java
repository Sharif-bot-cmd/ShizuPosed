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
 * from <shell-base>/modules/xstealth.json, which the manager
 * writes before launching a target under ShizuPosed.
 *
 * SCOPE
 * -----
 * The scope is a Set<String> of target package names. An empty set
 * means "all apps" — the module applies to every package. A
 * non-empty set means only those packages are affected.
 *
 * The decision is made in shouldApplyTo(pkg). XStealthModule calls
 * it at the top of handleLoadPackage().
 *
 * CACHED DETECTION VALUES
 * -----------------------
 * hideCachedDetectionValues — gates CachedValueCheck. Opt-in,
 * default FALSE. When on, hooks SharedPreferences reads for the
 * keys named in cachedValueKeys, and returns the same sanitized
 * value the live-read checks return.
 *
 * cachedValueKeys — per-app allow-list. Package name -> list of
 * exact SharedPreferences key names. Empty by default.
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

    public final Set<String> scope;

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