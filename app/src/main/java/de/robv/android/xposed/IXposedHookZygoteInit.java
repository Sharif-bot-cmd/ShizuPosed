package de.robv.android.xposed;

/**
 * IXposedHookZygoteInit
 *
 * Implemented by modules that want to run initialization code
 * before any package is loaded. Under a zygote-timing framework
 * (LSPosed, EdXposed), initZygote() fires once per zygote fork,
 * before the target's Application object exists.
 *
 * ShizuPosed is not a zygote-timing framework — it drives
 * ActivityThread bootstrap from inside the spawned process. But
 * many modules (including WAEX) implement this interface for
 * one-time setup that must happen before handleLoadPackage runs.
 * ShizuPosed calls initZygote() at the start of every bootstrap,
 * before any module's handleLoadPackage. It fires once per
 * bootstrapped process, not once per zygote fork.
 *
 * StartupParam.modulePath is the installed APK path (not the
 * cached dex path). Modules read their own assets through it.
 * StartupParam.startsSystemServer is always false under
 * ShizuPosed — the framework cannot reach system_server.
 *
 * Do NOT add methods to this interface. Upstream Xposed declares
 * exactly one method (initZygote); a module compiled against
 * upstream would fail verification if this interface gained an
 * abstract method it does not implement.
 */
public interface IXposedHookZygoteInit {

    void initZygote(StartupParam startupParam) throws Throwable;

    /**
     * Parameter passed to initZygote.
     *
     * Field names and types mirror upstream Xposed exactly.
     * Modules read them by name; a rename is a NoSuchFieldError
     * at callback time.
     */
    final class StartupParam {
        /**
         * The installed APK path of the module. Modules use this
         * to open their own assets and resources.
         */
        public String modulePath;

        /**
         * True only if the framework is running inside
         * system_server. Always false under ShizuPosed.
         */
        public boolean startsSystemServer;
    }
}