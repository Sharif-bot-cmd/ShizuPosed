package de.robv.android.xposed;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;

import com.shizuposed.manager.core.ResourceHooking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.callbacks.XC_LayoutInflated;

/**
 * Resource replacement façade.
 *
 * Modules call setReplacement(...) on this object from
 * handleInitPackageResources. Replacements are stored in a map, and
 * the framework installs hooks on Resources methods that consult the
 * map first.
 *
 * The map is keyed by resource ID. A single replacement per ID per
 * package is supported; last write wins.
 *
 * LAYOUT HOOKS
 * ------------
 * Modules can also register layout callbacks via hookLayout(). These
 * fire when LayoutInflater.inflate() produces a view whose resource
 * ID matches a registration. This is the XC_LayoutInflated API,
 * matching upstream Xposed.
 *
 * Dispatch requires a global hook on LayoutInflater.inflate(). That
 * hook is installed by XposedHook.installGlobalLayoutHook() during
 * bootstrap. If that hook is not installed for a given target,
 * registered callbacks will not fire. To make that failure visible,
 * hookLayout() logs a warning at registration time naming the
 * situation, rather than swallowing the registration silently.
 */
public final class XResources {

    private static final String TAG = "XResources";

    /** The original Resources object for the target package. */
    private final Resources resources;

    /** The package these resources belong to. */
    private final String packageName;

    /** id → replacement value. */
    private final ConcurrentHashMap<Integer, Object> replacements =
        new ConcurrentHashMap<>();

    /** name → replacement value (for by-name lookups). */
    private final ConcurrentHashMap<String, Object> nameReplacements =
        new ConcurrentHashMap<>();

    // ── CHANGE: layout-hook registries.
    // Keyed by resource ID and by resource name, mirroring the
    // setReplacement maps. A single layout can have multiple
    // registered callbacks — upstream allows it, and a module that
    // registers twice should see both fire. CopyOnWriteArrayList
    // because registration happens on the module-loading thread
    // while dispatch may happen on any thread that inflates a view.
    private final ConcurrentHashMap<Integer, List<XC_LayoutInflated>>
        layoutCallbacksById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<XC_LayoutInflated>>
        layoutCallbacksByName = new ConcurrentHashMap<>();

    public XResources(Resources resources, String packageName) {
        this.resources = resources;
        this.packageName = packageName;
    }

    public Resources getResources() {
        return resources;
    }

    public String getPackageName() {
        return packageName;
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API — what modules call
    // ═════════════════════════════════════════════════════════════

    public void setReplacement(int resId, Object replacement) {
        if (replacement == null) {
            replacements.remove(resId);
        } else {
            replacements.put(resId, replacement);
        }
    }

    public void setReplacement(String resourceName, Object replacement) {
        if (replacement == null) {
            nameReplacements.remove(resourceName);
        } else {
            nameReplacements.put(resourceName, replacement);
        }
    }

    public Object getReplacement(int resId) {
        return replacements.get(resId);
    }

    public Object getReplacement(String resourceName) {
        return nameReplacements.get(resourceName);
    }

    public boolean hasReplacement(int resId) {
        return replacements.containsKey(resId);
    }

    /** Snapshot for debugging. */
    public int replacementCount() {
        return replacements.size() + nameReplacements.size();
    }

    // ═════════════════════════════════════════════════════════════
    // LAYOUT HOOKS — XC_LayoutInflated API
    // ═════════════════════════════════════════════════════════════

    /**
     * Register a callback that fires when a layout with the given
     * resource ID is inflated.
     *
     * Registration is cheap and thread-safe. Dispatch happens from
     * the global LayoutInflater.inflate hook; see dispatchLayoutInflated.
     *
     * If that hook is not installed for the current target — which
     * happens in post-application mode, or if XposedHook failed to
     * install it — the callback will not fire. This method logs a
     * warning in that case so the module author has a signal.
     *
     * @param resId     the layout resource ID, e.g. R.layout.main
     * @param callback  the callback to fire. Must not be null.
     */
    // ── CHANGE: new method.
    public void hookLayout(int resId, XC_LayoutInflated callback) {
        if (callback == null) {
            Log.w(TAG, "hookLayout(" + packageName + ", 0x"
                + Integer.toHexString(resId) + "): null callback ignored");
            return;
        }

        layoutCallbacksById
            .computeIfAbsent(resId, k -> new CopyOnWriteArrayList<>())
            .add(callback);

        boolean dispatchWired = ResourceHooking.isLayoutDispatchWired(packageName);
        if (dispatchWired) {
            Log.i(TAG, "hookLayout registered: pkg=" + packageName
                + " resId=0x" + Integer.toHexString(resId)
                + " callback=" + callback.getClass().getName());
        } else {
            // The honest case. Registration succeeded but nothing
            // will fire. Say so, loudly, once per registration.
            Log.w(TAG, "hookLayout registered but dispatch is NOT wired"
                + " for pkg=" + packageName
                + " resId=0x" + Integer.toHexString(resId)
                + " callback=" + callback.getClass().getName()
                + " — the callback will not fire");
        }
    }

    /**
     * Register a callback by resource name. The name is matched
     * against the layout's resource name without package or type
     * qualifiers, e.g. "main" for R.layout.main.
     *
     * Same dispatch caveat as hookLayout(int, XC_LayoutInflated).
     */
    // ── CHANGE: new method.
    public void hookLayout(String resName, XC_LayoutInflated callback) {
        if (resName == null || resName.isEmpty()) {
            Log.w(TAG, "hookLayout(" + packageName
                + ", null/empty name): ignored");
            return;
        }
        if (callback == null) {
            Log.w(TAG, "hookLayout(" + packageName + ", " + resName
                + "): null callback ignored");
            return;
        }

        layoutCallbacksByName
            .computeIfAbsent(resName, k -> new CopyOnWriteArrayList<>())
            .add(callback);

        boolean dispatchWired = ResourceHooking.isLayoutDispatchWired(packageName);
        if (dispatchWired) {
            Log.i(TAG, "hookLayout registered: pkg=" + packageName
                + " name=" + resName
                + " callback=" + callback.getClass().getName());
        } else {
            Log.w(TAG, "hookLayout registered but dispatch is NOT wired"
                + " for pkg=" + packageName
                + " name=" + resName
                + " callback=" + callback.getClass().getName()
                + " — the callback will not fire");
        }
    }

    /**
     * Total number of registered layout callbacks, by ID and by name.
     * For diagnostics.
     */
    // ── CHANGE: new method.
    public int layoutCallbackCount() {
        int n = 0;
        for (List<XC_LayoutInflated> list : layoutCallbacksById.values()) {
            n += list.size();
        }
        for (List<XC_LayoutInflated> list : layoutCallbacksByName.values()) {
            n += list.size();
        }
        return n;
    }

    // ═════════════════════════════════════════════════════════════
    // DISPATCH — called by the framework's inflate hook
    // ═════════════════════════════════════════════════════════════

    /**
     * Fire every callback registered for this view's resource ID or
     * name. Called from XposedHook's global LayoutInflater.inflate
     * hook after a view has been inflated.
     *
     * Callbacks are invoked in registration order. A callback that
     * throws is logged and the next one fires — one bad callback
     * must not prevent the rest.
     *
     * Never throws. Dispatch is called from the inflate path; an
     * exception here would surface inside the target app's layout
     * pass, which is unacceptable.
     *
     * @param view      the inflated view. Null is silently ignored.
     * @param resId     the resource ID passed to inflate(). Zero
     *                  means "unknown" and skips ID matching.
     * @param resName   the resource name without package or type
     *                  qualifier, e.g. "main". Null skips name
     *                  matching.
     * @param variant   the resource variant qualifier, e.g. "land".
     *                  Null when the resource has no variant.
     * @param res       the Resources instance that inflated the view.
     */
    // ── CHANGE: new method.
    public void dispatchLayoutInflated(View view, int resId,
                                       String resName, String variant,
                                       Resources res) {
        if (view == null) return;

        List<XC_LayoutInflated> byId = (resId != 0)
            ? layoutCallbacksById.get(resId)
            : null;
        List<XC_LayoutInflated> byName = (resName != null)
            ? layoutCallbacksByName.get(resName)
            : null;

        if ((byId == null || byId.isEmpty())
                && (byName == null || byName.isEmpty())) {
            return;
        }

        // Build the parameter object once. It is shared across every
        // callback for this inflation; callbacks that mutate param
        // mutate it for everyone downstream. Upstream behaves the
        // same way.
        XC_LayoutInflated.LayoutInflatedParam param =
            new XC_LayoutInflated.LayoutInflatedParam();
        param.view    = view;
        param.resId   = resId;
        param.resName = resName;
        param.variant = variant;
        param.res     = res;

        if (byId != null) {
            for (XC_LayoutInflated cb : byId) {
                if (cb == null) continue;
                try {
                    cb.handleLayoutInflated(param);
                } catch (Throwable t) {
                    Log.w(TAG, "layout callback threw (byId): pkg="
                        + packageName + " resId=0x"
                        + Integer.toHexString(resId) + " cb="
                        + cb.getClass().getName() + ": " + t);
                }
            }
        }

        if (byName != null) {
            for (XC_LayoutInflated cb : byName) {
                if (cb == null) continue;
                try {
                    cb.handleLayoutInflated(param);
                } catch (Throwable t) {
                    Log.w(TAG, "layout callback threw (byName): pkg="
                        + packageName + " name=" + resName + " cb="
                        + cb.getClass().getName() + ": " + t);
                }
            }
        }
    }
}