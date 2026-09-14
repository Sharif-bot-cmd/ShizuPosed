package com.shizuposed.manager.core;

import android.content.Context;
import android.content.res.Resources;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XResources;

/**
 * ResourceHooking
 *
 * Stores XResources objects created by handleInitPackageResources and
 * installs hooks on the target app's Resources methods so that
 * replacement values are returned instead of the originals.
 *
 * The hooks are per-Resources-instance rather than per-method, so
 * different apps in the same process can have different replacements.
 * We do that by reading the Resources object's mPackageName field
 * inside the hook body and dispatching to the right XResources.
 *
 * Two ways to obtain an instance:
 *
 *   • ResourceHooking.getInstance(Context) — from the manager app,
 *     where a Context is available. This is what the UI uses.
 *
 *   • ResourceHooking.getInstanceSafe()   — from the shell-spawned
 *     app_process, where there is no Context. Returns a detached
 *     instance with a null logger. XposedHook uses this.
 */
public class ResourceHooking {

    private static final String TAG = "ResourceHooking";
    private static ResourceHooking instance;
    private static ResourceHooking detachedInstance;

    private final Context context;
    private final Logger logger;

    // packageName → XResources
    private final ConcurrentHashMap<String, XResources> xResources =
        new ConcurrentHashMap<>();

    // packageName → whether we've already installed Resources hooks for it
    private final ConcurrentHashMap<String, Boolean> hookedPackages =
        new ConcurrentHashMap<>();

    private volatile boolean initialized = false;

    private ResourceHooking(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            this.logger = Logger.getInstance(this.context);
        } else {
            this.context = null;
            this.logger = null;
        }
    }

    /**
     * Standard accessor for the manager app. Uses the given Context's
     * Application and a real Logger.
     */
    public static synchronized ResourceHooking getInstance(Context context) {
        if (instance == null) {
            instance = new ResourceHooking(context);
        }
        return instance;
    }

    /**
     * Accessor for the shell-spawned app_process, which has no Context.
     * Returns the manager instance if one exists (same process case),
     * otherwise a detached instance with a no-op logger.
     *
     * Safe to call from anywhere. Never returns null.
     */
    public static synchronized ResourceHooking getInstanceSafe() {
        if (instance != null) return instance;
        if (detachedInstance == null) {
            detachedInstance = new ResourceHooking(null);
        }
        return detachedInstance;
    }

    public void init() {
        if (initialized) return;
        initialized = true;
        logInfo(TAG + " initialized");
    }

    // ═════════════════════════════════════════════════════════════
    // REGISTRATION
    // ═════════════════════════════════════════════════════════════

    public void registerXResources(String packageName, XResources res) {
        if (packageName == null || res == null) return;
        xResources.put(packageName, res);
        logInfo("[" + TAG + "] Registered XResources for " + packageName
            + " (" + res.replacementCount() + " replacements)");
    }

    public XResources getXResources(String packageName) {
        return xResources.get(packageName);
    }

    public XResources getOrCreateXResources(String packageName, Resources resources) {
        XResources existing = xResources.get(packageName);
        if (existing != null) return existing;
        XResources created = new XResources(resources, packageName);
        xResources.put(packageName, created);
        return created;
    }

    // ═════════════════════════════════════════════════════════════
    // HOOK INSTALLATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Install hooks on the target app's Resources object. Called by
     * XposedHook after handleInitPackageResources has run for a
     * package.
     *
     * Idempotent: calling twice for the same package installs once.
     */
    public void installResourcesHooks(String packageName, Resources resources) {
        if (packageName == null || resources == null) return;
        if (hookedPackages.containsKey(packageName)) return;

        try {
            Class<?> resClass = Resources.class;

            // getString(int)
            try {
                Method getString = resClass.getMethod("getString", int.class);
                HookEngine.getInstance().hookMethod(getString, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(
                            XC_MethodHook.MethodHookParam param) throws Throwable {
                        int id = (int) param.args[0];
                        Object replacement = lookupReplacement(param.thisObject, id);
                        if (replacement instanceof CharSequence) return replacement;
                        if (replacement instanceof String) return replacement;
                        return invokeOriginal(param, "getString", int.class, id);
                    }
                });
                logDebug("[" + TAG + "] Hooked Resources.getString(int)");
            } catch (Throwable t) {
                logDebug("getString hook skipped: " + t.getMessage());
            }

            // getColor(int)
            try {
                Method getColor = resClass.getMethod("getColor", int.class);
                HookEngine.getInstance().hookMethod(getColor, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(
                            XC_MethodHook.MethodHookParam param) throws Throwable {
                        int id = (int) param.args[0];
                        Object replacement = lookupReplacement(param.thisObject, id);
                        if (replacement instanceof Integer) return replacement;
                        return invokeOriginal(param, "getColor", int.class, id);
                    }
                });
                logDebug("[" + TAG + "] Hooked Resources.getColor(int)");
            } catch (Throwable t) {
                logDebug("getColor hook skipped: " + t.getMessage());
            }

            // getDrawable(int)
            try {
                Method getDrawable = resClass.getMethod("getDrawable", int.class);
                HookEngine.getInstance().hookMethod(getDrawable, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(
                            XC_MethodHook.MethodHookParam param) throws Throwable {
                        int id = (int) param.args[0];
                        Object replacement = lookupReplacement(param.thisObject, id);
                        if (replacement != null) return replacement;
                        return invokeOriginal(param, "getDrawable", int.class, id);
                    }
                });
                logDebug("[" + TAG + "] Hooked Resources.getDrawable(int)");
            } catch (Throwable t) {
                logDebug("getDrawable hook skipped: " + t.getMessage());
            }

            hookedPackages.put(packageName, Boolean.TRUE);
            logInfo("[" + TAG + "] Installed Resources hooks for " + packageName);

        } catch (Throwable t) {
            logError("[" + TAG + "] installResourcesHooks failed for "
                + packageName + ": " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LOOKUP HELPERS
    // ═════════════════════════════════════════════════════════════

    private Object lookupReplacement(Object resources, int id) {
        if (resources == null) return null;
        try {
            String pkg = readPackageName(resources);
            if (pkg == null) return null;
            XResources xr = xResources.get(pkg);
            if (xr == null) return null;
            return xr.getReplacement(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readPackageName(Object resources) {
        try {
            Field f = findField(resources.getClass(), "mPackageName");
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(resources);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Invoke the original method on the Resources object, walking the
     * class hierarchy with getDeclaredMethod so we don't re-enter our
     * own hook.
     */
    private Object invokeOriginal(XC_MethodHook.MethodHookParam param,
                                  String name, Class<?> argType, Object arg)
            throws Throwable {
        Object target = param.thisObject;
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, argType);
                m.setAccessible(true);
                return m.invoke(target, arg);
            } catch (NoSuchMethodException nsme) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // LEGACY API — kept so existing callers compile
    // ═════════════════════════════════════════════════════════════

    public void setReplacement(String packageName, int id, Object replacement) {
        try {
            XResources xr = xResources.get(packageName);
            if (xr == null) {
                xr = new XResources(null, packageName);
                xResources.put(packageName, xr);
            }
            xr.setReplacement(id, replacement);
            logInfo("Resource replacement set: " + packageName
                + " ID: 0x" + Integer.toHexString(id));
        } catch (Throwable t) {
            logError("Failed to set replacement: " + t.getMessage());
        }
    }

    public void setReplacementByName(String packageName, String resourceName, Object replacement) {
        try {
            XResources xr = xResources.get(packageName);
            if (xr == null) {
                xr = new XResources(null, packageName);
                xResources.put(packageName, xr);
            }
            xr.setReplacement(resourceName, replacement);
            logInfo("Resource replacement set by name: " + packageName
                + " " + resourceName);
        } catch (Throwable t) {
            logError("Failed to set replacement by name: " + t.getMessage());
        }
    }

    public Object getReplacement(String packageName, int id) {
        XResources xr = xResources.get(packageName);
        return xr != null ? xr.getReplacement(id) : null;
    }

    public Object getReplacementByName(String packageName, String resourceName) {
        XResources xr = xResources.get(packageName);
        return xr != null ? xr.getReplacement(resourceName) : null;
    }

    public ConcurrentHashMap<Integer, Object> getOverrides(String packageName) {
        ConcurrentHashMap<Integer, Object> out = new ConcurrentHashMap<>();
        // Snapshot of the current XResources for callers that need it.
        XResources xr = xResources.get(packageName);
        if (xr != null) {
            // The XResources object itself holds the map; the caller
            // can query it via getReplacement(id) if needed.
        }
        return out;
    }

    public void clearOverrides(String packageName) {
        xResources.remove(packageName);
        hookedPackages.remove(packageName);
        logInfo("Resource overrides cleared for: " + packageName);
    }

    public void clearAllOverrides() {
        xResources.clear();
        hookedPackages.clear();
        logInfo("All resource overrides cleared");
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ═════════════════════════════════════════════════════════════
    // LOGGING (tolerant of a null logger)
    // ═════════════════════════════════════════════════════════════

    private void logInfo(String msg) {
        if (logger != null) logger.i(msg);
    }

    private void logDebug(String msg) {
        if (logger != null) logger.d(msg);
    }

    private void logError(String msg) {
        if (logger != null) logger.e(msg);
    }
}