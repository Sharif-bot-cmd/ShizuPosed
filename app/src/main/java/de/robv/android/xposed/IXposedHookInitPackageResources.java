package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;

/**
 * Implemented by modules that want to replace resources for a package.
 *
 * Xposed/LSPosed call handleInitPackageResources when a package's
 * Resources object is created, before the app's code reads any
 * resource. Modules then call resparam.res.setReplacement(...) to
 * install replacements.
 *
 * ShizuPosed's timing model can't intercept Resource creation as
 * early as LSPosed, so handleInitPackageResources runs when the
 * framework installs hooks — after Application.onCreate, or during
 * bootstrap between module loading and handleBindApplication. See
 * the README for what this means for replacements that need to be
 * visible during app startup.
 */
public interface IXposedHookInitPackageResources {
    void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam)
        throws Throwable;
}