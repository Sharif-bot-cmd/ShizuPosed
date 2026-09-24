package de.robv.android.xposed.callbacks;

import android.content.res.Resources;
import android.view.View;

/**
 * Callback type for layout-inflation hooks.
 *
 * Modules register an XC_LayoutInflated via XResources.hookLayout().
 * When the framework inflates a layout whose resource ID matches the
 * registration, it calls handleLayoutInflated() with the inflated
 * view and the resource metadata.
 *
 * STATUS IN SHIZUPOSED
 * --------------------
 * The type is shipped for link compatibility. A module that imports
 * or extends it loads correctly. Whether handleLayoutInflated()
 * actually fires depends on whether ShizuPosed has installed the
 * global LayoutInflater.inflate hook — see XResources.hookLayout().
 *
 * If layout hooks are not yet wired in your build, register-your-
 * callback calls are logged and stored but not dispatched. Do not
 * silently swallow them: a module author who sees no callback fire
 * cannot distinguish "framework not wired" from "my callback is
 * broken". The XResources.hookLayout() implementation must log.
 *
 * Extends XCallback and implements Param on the parameter type,
 * matching upstream. Do not change the inheritance.
 */
public abstract class XC_LayoutInflated extends XCallback {

    /**
     * Called when a registered layout has been inflated.
     *
     * @param param  the inflated view plus its resource metadata
     * @throws Throwable  caught and logged by the dispatcher. A
     *                    throwing callback does not prevent other
     *                    registered callbacks from firing.
     */
    public abstract void handleLayoutInflated(LayoutInflatedParam param)
            throws Throwable;

    /**
     * Parameter passed to handleLayoutInflated.
     *
     * Field names and types mirror upstream Xposed. Do not rename
     * or narrow any of them — modules read these fields by name,
     * and a rename is a NoSuchFieldError at callback time.
     *
     * Extends XCallback.Param, matching upstream. Modules sometimes
     * type a parameter as XCallback.Param in helper methods.
     */
    public static class LayoutInflatedParam implements XCallback.Param {

        /**
         * The fully inflated view. This is the root of the layout
         * that was inflated, not a child. Mutating this view's
         * hierarchy at this point is safe: inflation has completed.
         */
        public View view;

        /**
         * The name of the layout resource, e.g. "main_activity" for
         * R.layout.main_activity. Null when the framework could not
         * resolve the name (rare, usually a sign of a synthesized
         * resource ID).
         */
        public String resName;

        /**
         * The variant of the resource, e.g. "land" or "sw600dp".
         * Null when the resource has no variant qualifier.
         */
        public String variant;

        /**
         * The Resources instance that inflated the layout. Useful
         * for looking up sibling resources by name.
         */
        public Resources res;

        /**
         * The resource ID of the layout, as passed to inflate().
         * Non-zero when the callback fired for a matching ID.
         */
        public int resId;
    }
}