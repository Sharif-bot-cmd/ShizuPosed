package de.robv.android.xposed;

/**
 * Marker interface implemented by every Xposed module entry point.
 *
 * Upstream Xposed declares this as the common parent of
 * IXposedHookLoadPackage, IXposedHookInitPackageResources,
 * IXposedHookZygoteInit, IXposedHookCmdInit, and IXposedHookDynamic.
 * Modules sometimes do `instanceof IXposedMod` to identify an entry
 * class without checking each concrete interface, so the type must
 * exist even though it carries no methods.
 *
 * ShizuPosed ships it for link compatibility: a module that
 * references IXposedMod loads correctly against ShizuPosed's shim
 * even if the framework never calls into the module through this
 * type directly.
 *
 * Do not add methods. Upstream carries none, and a module compiled
 * against upstream would fail verification if this interface gained
 * an abstract method it does not implement.
 */
public interface IXposedMod {
}