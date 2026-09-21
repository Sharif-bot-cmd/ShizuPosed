package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XResources;

/**
 * Parameter passed to IXposedHookInitPackageResources.
 *
 * Extends XCallback and implements XCallback.Param so the type can
 * be used wherever upstream Xposed accepts an XCallback.
 *
 * Field names and types mirror upstream. Do not rename or narrow
 * any of them.
 */
public class XC_InitPackageResources {

    /**
     * Parameter passed to
     * IXposedHookInitPackageResources.handleInitPackageResources().
     */
    public static class InitPackageResourcesParam extends XCallback
            implements XCallback.Param {

        /** The package whose resources are being initialized. */
        public String packageName;

        /** The XResources wrapper the module calls setReplacement on. */
        public XResources res;
    }
}