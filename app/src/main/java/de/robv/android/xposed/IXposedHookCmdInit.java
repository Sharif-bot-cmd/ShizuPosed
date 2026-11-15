package de.robv.android.xposed;

/**
 * Implemented by modules that want to run code in the {@code cmd}
 * process. Under a zygote-timing framework (LSPosed, EdXposed),
 * the framework forks the {@code cmd} process from zygote and
 * calls {@link #initCmdProcess(StartupParam)} before any command
 * runs.
 *
 * SHIZUPOSED STATUS
 * -----------------
 * ShizuPosed ships the interface for link compatibility. The
 * {@code cmd} process runs under a protected UID (2000, shell)
 * and cannot be bootstrapped by the framework — the shell UID
 * cannot {@code setuid} to itself from a spawned process, and
 * the process model the framework uses ({@code app_process}
 * spawned as a target UID) does not apply to a system process.
 *
 * So {@code initCmdProcess()} is <strong>never called</strong>.
 * This is the same pattern used for {@code onSystemServerStarting}
 * in the libxposed API: the callback is declared so a module
 * that implements it links at class load time, and the
 * documentation says it will not fire.
 *
 * WHY NOT THROW
 * -------------
 * A module that implements this interface and is compiled
 * against upstream Xposed will link against the class. If the
 * interface is missing, the module fails at class load with
 * {@code NoClassDefFoundError} — a silent failure (no
 * {@code handleLoadPackage} call, no log entry unless the
 * manager is watching for it). Shipping the interface turns
 * that silent failure into a diagnosable one: the module loads,
 * {@code initCmdProcess} is not called, and the user can see
 * from the diagnostic that the callback was never invoked.
 *
 * DO NOT ADD METHODS
 * ------------------
 * Upstream Xposed declares exactly one method
 * ({@code initCmdProcess}) and one nested class
 * ({@code StartupParam}) on this interface. A module compiled
 * against upstream would fail verification if the interface
 * gained an abstract method it does not implement. Add methods
 * only if upstream also has them.
 */
public interface IXposedHookCmdInit {

    /**
     * Called once per {@code cmd} process, before any command
     * runs, so the module can install hooks that affect
     * {@code cmd} command dispatch.
     *
     * <strong>Never called under ShizuPosed.</strong> See the
     * class javadoc for why.
     *
     * @param startupParam the framework startup parameters
     * @throws Throwable any exception the module wants to
     *                   propagate; the framework logs it and
     *                   continues
     */
    void initCmdProcess(StartupParam startupParam) throws Throwable;

    /**
     * Parameter passed to {@link #initCmdProcess(StartupParam)}.
     *
     * Field names and types mirror upstream Xposed exactly.
     * Modules read them by name; a rename is a
     * {@code NoSuchFieldError} at callback time (if the
     * callback were ever invoked — it is not, under ShizuPosed).
     *
     * Do not rename or narrow any field.
     */
    final class StartupParam {

        /**
         * The installed APK path of the module. Upstream exposes
         * this so a module can open its own assets and resources
         * from the {@code cmd} process.
         *
         * Under ShizuPosed this field is populated as if the
         * callback would fire — the {@code XposedHook} bootstrap
         * fills it — but since the callback is never called,
         * no module reads it.
         */
        public String modulePath;
    }
}