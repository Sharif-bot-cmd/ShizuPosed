package de.robv.android.xposed;

/**
 * Composite interface for modules that hook classes loaded at
 * runtime.
 *
 * Upstream declares this as an interface that extends
 * {@link IXposedHookLoadPackage}, {@link IXposedHookZygoteInit},
 * and {@link IXposedHookInitPackageResources}, with no
 * additional methods of its own. The composite is a declaration
 * of intent: "this module hooks dynamically-loaded classes."
 * The framework reads the marker and drives the module through
 * the standard lifecycle — {@code initZygote} fires once,
 * {@code handleLoadPackage} runs per package, and
 * {@code handleInitPackageResources} runs per package if the
 * module also implements that path.
 *
 * A module that wants to hook classes loaded *after* the
 * package is initialized (plugin APKs, dynamic feature
 * modules, split APKs loaded via {@code DexClassLoader})
 * implements this interface and uses
 * {@code ClassLoadingBridge} or a {@code DexClassLoader}
 * construction hook to detect the new loader. The marker
 * itself carries no behavior; it just says "I intend to hook
 * dynamic classes," and the framework's bridges make that
 * possible.
 *
 * SHIZUPOSED STATUS
 * -----------------
 * ShizuPosed ships this composite so a module that implements
 * it links. The lifecycle is fully supported:
 *
 *   • {@code initZygote} fires once per bootstrapped process
 *     (not once per zygote fork — see the note on
 *     {@link IXposedHookZygoteInit}).
 *   • {@code handleLoadPackage} fires per package.
 *   • {@code handleInitPackageResources} fires per package.
 *   • Dynamic loader detection is provided by
 *     {@code ClassLoadingBridge}, which hooks
 *     {@code ClassLoader.loadClass} and the construction of
 *     {@code DexClassLoader} / {@code PathClassLoader}.
 *
 * So a module that implements this interface runs under
 * ShizuPosed, not just links. The only caveat is the same one
 * that applies to {@code IXposedHookZygoteInit}: the process
 * model is bootstrap-timing, not zygote-timing.
 *
 * DO NOT ADD METHODS
 * ------------------
 * Upstream carries no methods on this interface — it is
 * purely a composite of the three parent interfaces. A module
 * compiled against upstream would fail verification if the
 * interface gained an abstract method it does not implement.
 */
public interface IXposedHookDynamic
        extends IXposedHookLoadPackage,
                IXposedHookZygoteInit,
                IXposedHookInitPackageResources {

    // No methods. The composite exists to declare intent; the
    // framework reads the marker and drives the module through
    // the standard lifecycle.
}