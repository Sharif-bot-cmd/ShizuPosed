package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XResources;

/**
 * Parameter passed to IXposedHookInitPackageResources.
 */
public class XC_InitPackageResources {

    public static class InitPackageResourcesParam {
        /** The package whose resources are being initialized. */
        public String packageName;

        /** The XResources wrapper the module calls setReplacement on. */
        public XResources res;
    }
}