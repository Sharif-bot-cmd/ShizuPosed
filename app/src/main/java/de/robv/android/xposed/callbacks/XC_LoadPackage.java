package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

public class XC_LoadPackage {

    /**
     * Parameter passed to IXposedHookLoadPackage.handleLoadPackage().
     *
     * Extends XCallback and implements XCallback.Param so the type
     * can be used wherever upstream Xposed accepts an XCallback.
     * Modules that assign a LoadPackageParam to a variable typed
     * XCallback, or pass it to a method declared with that
     * parameter type, rely on this relationship.
     *
     * Field names and types mirror upstream. Do not rename or
     * narrow any of them.
     */
    public static class LoadPackageParam extends XCallback implements XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
        public ApplicationInfo appInfo;
        public Object[] args;
    }
}