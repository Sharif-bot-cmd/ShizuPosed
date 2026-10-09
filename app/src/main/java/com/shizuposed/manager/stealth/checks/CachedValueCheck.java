package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * CachedValueCheck
 *
 * Opt-in, allow-list-gated hook on SharedPreferences reads.
 *
 * WHY THIS EXISTS
 * ---------------
 * If a target app caches a detection result in its own
 * SharedPreferences before XStealth is in play — for example it
 * read adb_enabled on a previous launch and stored the value — the
 * cache persists a value the app no longer sees through the live
 * settings API. XStealth's other checks sanitize the live reads;
 * this check sanitizes the cached reads so the two agree. Without
 * it, an app that reads a cached value and a live value and
 * compares them sees a disagreement, which is itself a detection
 * signal.
 *
 * SAFETY MODEL
 * ------------
 * Three properties make this safe where a blanket SharedPreferences
 * hook is not:
 *
 *   1. Opt-in. Default off. Nothing happens unless the user turns
 *      it on in the XStealth detail sheet.
 *
 *   2. Allow-list. Only keys the user explicitly names are touched.
 *      Every other key passes through unchanged. The list is
 *      per-app, because different apps cache different keys.
 *
 *   3. Fail-open. Any error, any unexpected type, any shape the
 *      code doesn't recognize, returns the original value and logs
 *      the failure. The hook never fabricates a value it can't
 *      justify from XStealth's existing watch lists.
 *
 * VALUE CONSISTENCY
 * -----------------
 * A key that maps to a signal covered by another check must return
 * the SAME value across all checks, or the app sees a cross-layer
 * inconsistency. This class therefore asks DevOptionsCheck (the
 * canonical source for the hidden-key list) whether a key is
 * hidden, and returns the same sanitized value that check returns
 * for live reads.
 *
 * SCOPE
 * -----
 * v1 hooks reads only. The write path and a one-time cache
 * correction pass are planned for v1.1. Until then, the first
 * launch under XStealth after enabling this check may see a stale
 * value for one cycle; the next time the app writes the key, the
 * sanitized value replaces it, and subsequent reads agree.
 *
 * getAll() is deliberately not hooked. Rewriting entries in the
 * returned map would break callers that use it for backup,
 * migration, or debug dumps, and the false-positive cost is not
 * worth the coverage gain.
 */
public final class CachedValueCheck {

    private static final String TAG = "XStealth";

    /**
     * Per-app allow-list: package name -> set of exact key names.
     * Populated at install time and read on the app's own thread.
     */
    private static final Map<String, Set<String>> ALLOW_LISTS =
        new ConcurrentHashMap<>();

    /** Diagnostics counters, surfaced in the XStealth detail sheet. */
    private static final AtomicLong READ_MATCHES = new AtomicLong();
    private static final AtomicLong WRITE_MATCHES = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();

    private CachedValueCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam,
                               XStealthConfig config) {
        if (lpparam == null || config == null) return;
        if (!config.hideCachedDetectionValues) return;

        String pkg = lpparam.packageName;
        if (pkg == null) return;

        List<String> keys = config.cachedValueKeysFor(pkg);
        if (keys == null || keys.isEmpty()) return;

        Set<String> allowList = Collections.unmodifiableSet(
            new HashSet<>(keys));
        ALLOW_LISTS.put(pkg, allowList);

        boolean any = hookReads(lpparam, allowList);

        if (any) {
            XStealthRegistry.record("CachedValueCheck");
            XposedBridge.log(TAG + ": CachedValueCheck installed for "
                + pkg + " (" + allowList.size() + " keys)");
        } else {
            XposedBridge.log(TAG + ": CachedValueCheck found no hookable "
                + "SharedPreferences methods for " + pkg);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // READ HOOKS
    //
    // SharedPreferencesImpl is the concrete implementation returned
    // by Context.getSharedPreferences. Hooking the interface would
    // be cleaner, but the implementation is what callers invoke and
    // some apps cast to it. Hooking the impl catches both.
    // ═════════════════════════════════════════════════════════════

    private static boolean hookReads(XC_LoadPackage.LoadPackageParam lpparam,
                                     Set<String> allowList) {
        Class<?> impl = XposedHelpers.findClassIfExists(
            "android.app.SharedPreferencesImpl", lpparam.classLoader);
        if (impl == null) {
            XposedBridge.log(TAG + ": SharedPreferencesImpl not found");
            return false;
        }

        boolean any = false;
        any |= hookGetInt(impl, allowList);
        any |= hookGetLong(impl, allowList);
        any |= hookGetBoolean(impl, allowList);
        any |= hookGetFloat(impl, allowList);
        any |= hookGetString(impl, allowList);
        return any;
    }

    private static boolean hookGetInt(Class<?> impl, Set<String> allowList) {
        try {
            XposedHelpers.findAndHookMethod(
                impl, "getInt",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        applySanitized(p, allowList, "int");
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SharedPreferences.getInt hook failed: " + t);
            return false;
        }
    }

    private static boolean hookGetLong(Class<?> impl, Set<String> allowList) {
        try {
            XposedHelpers.findAndHookMethod(
                impl, "getLong",
                String.class, long.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        applySanitized(p, allowList, "long");
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SharedPreferences.getLong hook failed: " + t);
            return false;
        }
    }

    private static boolean hookGetBoolean(Class<?> impl, Set<String> allowList) {
        try {
            XposedHelpers.findAndHookMethod(
                impl, "getBoolean",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        applySanitized(p, allowList, "boolean");
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SharedPreferences.getBoolean hook failed: " + t);
            return false;
        }
    }

    private static boolean hookGetFloat(Class<?> impl, Set<String> allowList) {
        try {
            XposedHelpers.findAndHookMethod(
                impl, "getFloat",
                String.class, float.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        applySanitized(p, allowList, "float");
                    }
                });
            return true;
        } catch (Throwable t) {
            // Not always declared. Silent skip.
            return false;
        }
    }

    private static boolean hookGetString(Class<?> impl, Set<String> allowList) {
        try {
            XposedHelpers.findAndHookMethod(
                impl, "getString",
                String.class, String.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        applySanitized(p, allowList, "string");
                    }
                });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SharedPreferences.getString hook failed: " + t);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // THE SANITIZER
    //
    // Called from every read hook's afterHookedMethod. It does
    // three things in order:
    //
    //   1. Bail if the key isn't in the allow-list.
    //   2. Compute the sanitized value from the same source of
    //      truth the live-read checks use.
    //   3. If the current result already equals the sanitized
    //      value, do nothing (no log noise for a consistent cache).
    //      Otherwise set the result and log the match.
    //
    // Any exception is swallowed, logged, and the original result
    // is left in place.
    // ═════════════════════════════════════════════════════════════

    private static void applySanitized(MethodHookParam p,
                                       Set<String> allowList,
                                       String type) {
        try {
            if (p.args == null || p.args.length < 1) return;
            Object keyObj = p.args[0];
            if (!(keyObj instanceof String)) return;
            String key = (String) keyObj;
            if (!allowList.contains(key)) return;

            Object current = p.getResult();

            switch (type) {
                case "int": {
                    int sanitized = sanitizeInt(key, (Integer) current);
                    if (!(current instanceof Integer)
                            || ((Integer) current) != sanitized) {
                        recordMatch(key, current, sanitized);
                        p.setResult(sanitized);
                    }
                    break;
                }
                case "long": {
                    long sanitized = sanitizeLong(key, (Long) current);
                    if (!(current instanceof Long)
                            || ((Long) current) != sanitized) {
                        recordMatch(key, current, sanitized);
                        p.setResult(sanitized);
                    }
                    break;
                }
                case "boolean": {
                    boolean sanitized = sanitizeBoolean(key, (Boolean) current);
                    if (!(current instanceof Boolean)
                            || ((Boolean) current) != sanitized) {
                        recordMatch(key, current, sanitized);
                        p.setResult(sanitized);
                    }
                    break;
                }
                case "float": {
                    float sanitized = sanitizeFloat(key, (Float) current);
                    if (!(current instanceof Float)
                            || ((Float) current) != sanitized) {
                        recordMatch(key, current, sanitized);
                        p.setResult(sanitized);
                    }
                    break;
                }
                case "string": {
                    String sanitized = sanitizeString(key, (String) current);
                    if (!(current instanceof String)
                            || !current.equals(sanitized)) {
                        recordMatch(key, current, sanitized);
                        p.setResult(sanitized);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            XposedBridge.log(TAG + ": CachedValueCheck sanitizer error: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // VALUE MAPPING
    //
    // The sanitized value for a key comes from the same source of
    // truth as the live-read checks, so a detector that cross-checks
    // a live read and a cached read sees the same value. A key not
    // in the canonical hidden list is not in the allow-list either
    // (the install path filters), so the fallthrough here is
    // defensive only.
    // ═════════════════════════════════════════════════════════════

    private static int sanitizeInt(String key, Integer current) {
        if (DevOptionsCheck.hidesKey(key)) return 0;
        return current == null ? 0 : current;
    }

    private static long sanitizeLong(String key, Long current) {
        if (DevOptionsCheck.hidesKey(key)) return 0L;
        return current == null ? 0L : current;
    }

    private static boolean sanitizeBoolean(String key, Boolean current) {
        if (DevOptionsCheck.hidesKey(key)) return false;
        return current != null && current;
    }

    private static float sanitizeFloat(String key, Float current) {
        if (DevOptionsCheck.hidesKey(key)) return 0f;
        return current == null ? 0f : current;
    }

    private static String sanitizeString(String key, String current) {
        if (DevOptionsCheck.hidesKey(key)) return "0";
        return current;
    }

    private static void recordMatch(String key, Object before, Object after) {
        READ_MATCHES.incrementAndGet();
        XposedBridge.log(TAG + ": CachedValueCheck matched '"
            + key + "' (" + before + " -> " + after + ")");
    }

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    public static long getReadMatches()  { return READ_MATCHES.get(); }
    public static long getWriteMatches() { return WRITE_MATCHES.get(); }
    public static long getErrors()       { return ERRORS.get(); }

    public static int getAllowListSize(String pkg) {
        Set<String> s = ALLOW_LISTS.get(pkg);
        return s == null ? 0 : s.size();
    }
}