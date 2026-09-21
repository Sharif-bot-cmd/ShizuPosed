package de.robv.android.xposed;

/**
 * Implement this interface to hook command-line tools launched via
 * app_process.
 *
 * Under ShizuPosed, initCmdProcess() fires from XposedHook.main()
 * after native engines are loaded and after the backend chain is
 * installed, but before the target's Application object exists.
 * This is the earliest hook ShizuPosed offers.
 *
 * The classLoader field of InitCmdProcessParam is the framework's
 * own loader, not the target's — the target's loader is not resolved
 * until XposedHook.runBootstrapMode(). This matches upstream
 * semantics: IXposedHookCmdInit is about the command-line wrapper,
 * not the app process. Modules that need the target's classloader
 * must implement IXposedHookLoadPackage instead.
 *
 * Failure policy: if initCmdProcess() throws, XposedHook logs the
 * throwable and skips the module. The launch continues. A module
 * that throws here does not prevent the target app from starting.
 *
 * Compatibility notes:
 *   • Field names on InitCmdProcessParam are load-bearing. They
 *     must match upstream Xposed exactly. Renaming them is a
 *     binary-incompatible change that produces NoSuchFieldError at
 *     runtime in modules compiled against upstream.
 *   • InitCmdProcessParam is a nested class of this interface, not
 *     a top-level class. Modules that import
 *     de.robv.android.xposed.IXposedHookCmdInit.InitCmdProcessParam
 *     rely on the nesting. Do not hoist it.
 *   • IXposedHookCmdInit extends IXposedMod. Both types must exist
 *     for the class to verify.
 */
public interface IXposedHookCmdInit extends IXposedMod {

    /**
     * Called once per command-line process launch.
     *
     * @param param  launch parameters
     * @throws Throwable  any throwable is caught and logged by the
     *                    framework. The launch is not aborted.
     */
    void initCmdProcess(InitCmdProcessParam param) throws Throwable;

    /**
     * Parameters passed to initCmdProcess.
     *
     * Field names mirror upstream Xposed. Do not rename them.
     * Modules compiled against upstream read these fields by name,
     * and a rename is a NoSuchFieldError at module load time.
     */
    class InitCmdProcessParam {

        /**
         * Name of the process. Under ShizuPosed this is the target
         * package name.
         *
         * Mirrors upstream.
         */
        public String processName;

        /**
         * Full command line as invoked. Under ShizuPosed this is
         * the argv joined by spaces, exactly as Shizuku passed it to
         * app_process.
         */
        public String cmdline;

        /**
         * Argument vector, excluding argv[0]. Under ShizuPosed this
         * is the raw args array XposedHook.main() received. Never
         * null.
         */
        public String[] argv;

        /**
         * The classloader of the command-line tool.
         *
         * Under ShizuPosed, this is XposedHook's own classloader, not
         * the target's. See the interface javadoc.
         */
        public ClassLoader classLoader;

        /** Conventional default constructor. */
        public InitCmdProcessParam() {}
    }
}