package com.shizuposed.manager.stealth;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * NEW IN THIS VERSION (7.7)
 * -------------------------
 *   • hideSystemProperties — gates SystemPropertiesCheck. Hooks
 *     android.os.SystemProperties reads for ro.debuggable,
 *     ro.secure, ro.boot.verifiedbootstate, etc. Default true.
 *   • hideBuildFields — gates BuildCheck. Sanitizes Build.TAGS,
 *     Build.TYPE, Build.FINGERPRINT, Build.HOST, Build.USER.
 *     Default true.
 *   • scrubShizuPosedProperties — gates PropertyScrubCheck. Hides
 *     shizuposed.* system properties from System.getProperties() /
 *     System.getProperty(). Default true.
 *   • scrubThreadNames — renames ShizuPosed's own threads to
 *     innocuous names. Default true.
 *
 * NEW IN THIS VERSION (8.3+)
 * --------------------------
 *   • hideCachedDetectionValues — gates CachedValueCheck. Opt-in,
 *     default FALSE. When on, hooks SharedPreferences reads for
 *     the keys named in cachedValueKeys, and returns the same
 *     sanitized value the live-read checks return. Closes the
 *     stale-cache gap where an app cached a detection result
 *     before XStealth was in play.
 *   • cachedValueKeys — per-app allow-list. Package name -> list
 *     of exact SharedPreferences key names. Empty by default;
 *     nothing is hooked until the user names keys for a package.
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

    /** Hook SystemProperties reads. Default true. */
    public final boolean hideSystemProperties;

    /** Sanitize Build.* fields. Default true. */
    public final boolean hideBuildFields;

    /** Scrub shizuposed.* system properties. Default true. */
    public final boolean scrubShizuPosedProperties;

    /** Rename ShizuPosed's own threads. Default true. */
    public final boolean scrubThreadNames;

    /** Enable the bridge library. Default false. */
    public final boolean bridgeEnabled;

    public final boolean methodBaseline;
    public final boolean unsafeGate;

    /**
     * Hook SharedPreferences reads against the per-app allow-list.
     * Default FALSE — opt-in. Nothing is hooked until the user
     * turns this on and names keys for a package.
     */
    public final boolean hideCachedDetectionValues;

    /**
     * Per-app allow-list: package name -> exact SharedPreferences
     * key names. Unmodifiable. Empty unless the manager writes it.
     * Never null — a missing or malformed entry yields an empty
     * map, so a lookup miss is unambiguous.
     */
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

        // Opt-in. Default false. A missing field in an older
        // xstealth.json means "not enabled", which is the correct
        // upgrade behavior.
        hideCachedDetectionValues =
            o.optBoolean("hideCachedDetectionValues", false);

        // Per-app allow-list. Shape:
        //
        //   "cachedValueKeys": {
        //     "com.example.bank": [
        //       "adb_enabled",
        //       "development_settings_enabled"
        //     ]
        //   }
        //
        // A missing object, a non-object value, or an entry whose
        // value isn't an array all yield an empty map. Every key
        // in the JSONObject is treated as a package name; the
        // values are stringified so a numeric or boolean entry
        // can't crash the parse.
        Map<String, List<String>> cvk = new HashMap<>();
        JSONObject cvkObj = o.optJSONObject("cachedValueKeys");
        if (cvkObj != null) {
            java.util.Iterator<String> it = cvkObj.keys();
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

    /**
     * Keys to sanitize in SharedPreferences reads for this package.
     * Returns an empty list when the package has no entry, so the
     * caller can treat a miss and an empty list the same way —
     * install nothing.
     */
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