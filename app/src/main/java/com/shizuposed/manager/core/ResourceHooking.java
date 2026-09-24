package com.shizuposed.manager.core;

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
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
 * LAYOUT DISPATCH
 * ---------------
 * Also installs the global LayoutInflater.inflate hook that drives
 * XC_LayoutInflated callbacks. That hook is installed per-package by
 * installGlobalLayoutHook(), and its success is tracked so
 * XResources.hookLayout() can log an honest warning when a module
 * registers a callback that will not fire.
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

    // ── CHANGE: packages whose global LayoutInflater.inflate hook is
    // confirmed installed. XResources.hookLayout() consults this to
    // decide whether to log a "not wired" warning. Stored per-package
    // because a process may host multiple packages in principle, even
    // though ShizuPosed launches one target at a time.
    private final Set<String> layoutDispatchWired =
        ConcurrentHashMap.newKeySet();

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

    /**
     * Look up the XResources for a package without creating one.
     * Returns null when nothing has been registered.
     *
     * Used by the global inflate hook: creating an XResources on the
     * inflate path would register a package that has no modules
     * scoped, which would be wrong.
     */
    // ── CHANGE: new method.
    public XResources getXResourcesIfPresent(String packageName) {
        if (packageName == null) return null;
        return xResources.get(packageName);
    }

    // ═════════════════════════════════════════════════════════════
    // LAYOUT DISPATCH TRACKING
    // ═════════════════════════════════════════════════════════════

    /**
     * Record that the global LayoutInflater.inflate hook has been
     * installed for this package. Called by installGlobalLayoutHook()
     * after the hook is confirmed.
     *
     * Until this is called, XResources.hookLayout() logs a warning
     * naming the situation, so a module author who registers a
     * callback that will not fire has a signal in logcat.
     */
    // ── CHANGE: new method.
    public void markLayoutDispatchWired(String packageName) {
        if (packageName == null) return;
        layoutDispatchWired.add(packageName);
        logInfo("[" + TAG + "] Layout dispatch marked wired for " + packageName);
    }

    /**
     * Has the global inflate hook been installed for this package?
     * Static so XResources can call it without a Context.
     */
    // ── CHANGE: new static method.
    public static boolean isLayoutDispatchWired(String packageName) {
        if (packageName == null) return false;
        ResourceHooking rh = getInstanceSafe();
        return rh != null && rh.layoutDispatchWired.contains(packageName);
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
    // GLOBAL LAYOUT INFLATE HOOK
    // ═════════════════════════════════════════════════════════════

    /**
     * Install a global hook on LayoutInflater.inflate(int, ViewGroup,
     * boolean) for the given package. When a view is inflated, the
     * hook resolves the resource name from the ID, finds the
     * XResources registered for this package, and calls
     * dispatchLayoutInflated() on it.
     *
     * Idempotent per package. Marks layout dispatch as wired on
     * success, so XResources.hookLayout() stops logging the
     * "not wired" warning.
     *
     * Called from XposedHook before the target's Application is
     * bound in bootstrap mode, and after the Application is available
     * in post-application mode. In post-application mode, layouts
     * inflated before this call are missed — the callback fires only
     * for future inflations.
     *
     * @param packageName  target package
     * @param appLoader    the target's classloader
     */
    // ── CHANGE: new method.
    public void installGlobalLayoutHook(String packageName, ClassLoader appLoader) {
        if (packageName == null || appLoader == null) return;
        if (layoutDispatchWired.contains(packageName)) {
            logDebug("[" + TAG + "] Layout hook already installed for "
                + packageName);
            return;
        }

        try {
            Class<?> inflaterClass = Class.forName(
                "android.view.LayoutInflater", false, appLoader);

            // Try the 3-arg signature first — it's the canonical one
            // and the one most layouts go through.
            Method inflate3 = null;
            try {
                inflate3 = inflaterClass.getMethod("inflate",
                    int.class, ViewGroup.class, boolean.class);
            } catch (NoSuchMethodException e) {
                logDebug("LayoutInflater.inflate(int, ViewGroup, boolean) "
                    + "not found — trying 2-arg");
            }

            if (inflate3 != null) {
                HookEngine.getInstance().hookMethod(inflate3, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        dispatchFromInflate(param, packageName);
                    }
                });
                logInfo("[" + TAG + "] Hooked LayoutInflater.inflate(int, ViewGroup, boolean)");
            } else {
                // Fallback: the 2-arg overload. Some inflate calls go
                // through this one when attachToRoot isn't specified.
                try {
                    Method inflate2 = inflaterClass.getMethod("inflate",
                        int.class, ViewGroup.class);
                    HookEngine.getInstance().hookMethod(inflate2, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param)
                                throws Throwable {
                            dispatchFromInflate(param, packageName);
                        }
                    });
                    logInfo("[" + TAG + "] Hooked LayoutInflater.inflate(int, ViewGroup)");
                } catch (NoSuchMethodException e) {
                    logError("[" + TAG + "] No LayoutInflater.inflate(int, ...) "
                        + "signature found — layout hooks disabled for "
                        + packageName);
                    return;
                }
            }

            markLayoutDispatchWired(packageName);

        } catch (Throwable t) {
            logError("[" + TAG + "] installGlobalLayoutHook failed for "
                + packageName + ": " + t.getMessage());
        }
    }

    /**
     * Shared body for the inflate hook. Extracts the resource ID from
     * the method arguments, resolves the resource name, and dispatches
     * to the XResources registered for this package.
     *
     * Never throws — the inflate path must not be perturbed by a
     * callback misbehaving or a lookup failing.
     */
    // ── CHANGE: new method.
    private void dispatchFromInflate(XC_MethodHook.MethodHookParam param,
                                     String packageName) {
        try {
            View view = (View) param.getResult();
            if (view == null) return;

            // args[0] is the resource ID for both signatures.
            if (param.args == null || param.args.length < 1) return;
            Object arg0 = param.args[0];
            if (!(arg0 instanceof Integer)) return;
            int resId = (Integer) arg0;
            if (resId == 0) return;

            XResources xr = getXResourcesIfPresent(packageName);
            if (xr == null) return;

            // Resolve the resource name from the ID. The name is
            // "pkg:type/name", and we strip everything through the
            // last slash so callbacks see just "main", "activity_main",
            // etc., matching upstream.
            String resName = null;
            String variant = null;
            Resources res = null;
            try {
                res = view.getResources();
                if (res != null) {
                    String full = res.getResourceName(resId);
                    int slash = full.lastIndexOf('/');
                    if (slash >= 0 && slash < full.length() - 1) {
                        resName = full.substring(slash + 1);
                    }
                    // Variant lookup: ResourceName has no variant
                    // segment. The variant qualifier is not directly
                    // available from a resource ID; upstream derives
                    // it from the configuration. Leaving it null is
                    // safe — modules that need it can read it from
                    // res.getConfiguration().
                }
            } catch (Throwable ignored) {
                // Resource name lookup can fail for synthesized IDs.
                // Not fatal — name-based matching just won't fire.
            }

            xr.dispatchLayoutInflated(view, resId, resName, variant, res);

        } catch (Throwable t) {
            // Must not propagate. Logging here uses logDebug because
            // this runs on the inflate path and a per-inflation log
            // line would be noisy.
            logDebug("[" + TAG + "] dispatchFromInflate failed: " + t.getMessage());
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
        // ── CHANGE: clear layout-dispatch tracking too, so a
        // subsequent install for the same package reinstalls the
        // inflate hook rather than short-circuiting.
        layoutDispatchWired.remove(packageName);
        logInfo("Resource overrides cleared for: " + packageName);
    }

    public void clearAllOverrides() {
        xResources.clear();
        hookedPackages.clear();
        // ── CHANGE: clear layout-dispatch tracking too.
        layoutDispatchWired.clear();
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