# ShizuPosed

**An Xposed-compatible hook framework that doesn't need root — it runs on `app_process`.**

ShizuPosed lets Xposed-API modules run inside target apps without root. No bootloader unlock, no system partition modification. Shizuku calls `app_process` as the shell UID, a Java runtime spins up, and method hooks get installed through a dispatcher that tries several backends until one sticks. Modules built for LSPosed and classic Xposed keep working.

**Version 1.0.1.** A patch release on top of 1.0.0. The API surface is still version 100 and unchanged; the timing model, backend chain, and XStealth layer structure are frozen for 1.x. 1.0.1 makes monitor-triggered injection real (the `injectProcess` path was previously a stub), and hides protected-UID packages from the scope editor.

---

## What this is, and what it isn't

This section comes first because everything else follows from it.

ShizuPosed is **not** a drop-in replacement for LSPosed. It doesn't try to be. It uses a different injection primitive — `app_process` into a target it starts — because that's the only injection path that reliably works without root on modern Android. That choice is what makes ShizuPosed possible, and it's also what sets the ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or cross-process hooks, use LSPosed. ShizuPosed is for people who can't root or won't, and who still want LSPosed-API modules to work in the apps it can reach.

The rest of this document is honest about where that line falls.

---

## Why `app_process`

It helps to understand what ShizuPosed _can't_ do before looking at what it can.

Without root, the injection primitives on Android 10+ are narrow. `ptrace` is blocked by SELinux for non-root UIDs. `LD_PRELOAD` needs a writable, executable path the target will load from, and app data directories are mounted `noexec`. Zygote fork requires being inside zygote, which means root or being the ROM. `Runtime.exec` runs as the caller's UID, not the target's. `am instrument` gives you an `Instrumentation` handle only after `Application.onCreate` — too late for bootstrap hooks. ContentProvider hijack works only if the target already declares a provider you can take over.

`app_process` is the one that works. It's a platform binary that exists on every Android device. Shizuku can invoke it as shell (UID 2000). It starts a Java runtime, so you can load arbitrary dex into it. And Shizuku can pass the target package and UID as arguments.

That's the entire mechanism. Everything else in ShizuPosed is a consequence of it.

### What the shell UID can and cannot assume

`app_process` runs as whichever UID the shell passes it. The shell UID is 2000. It can `setuid` to ordinary app UIDs (>= 10000), which is the normal `app_process` target range. It **cannot** `setuid` to `SYSTEM_UID` (1000) or to the reserved system UID pool (1001–1999) — those are protected.

This means:

  * A preinstalled app with its own UID in the 10000+ range is a normal target. It can be hooked.
  * `com.android.systemui`, `com.android.settings`, `com.android.phone`, `com.android.bluetooth`, `com.android.nfc`, `com.android.shell`, and `system_server` all run under protected UIDs. They cannot be hooked by ShizuPosed, and they are not offered as scope targets.

The scope editor hides protected-UID packages, so what you see in the picker is what the framework can actually reach.

---

## The timing model

LSPosed is a zygote-timing framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a bootstrap-timing framework: it is `app_process`-launched into the target, installs hooks, then drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

Here's what ShizuPosed can reach in bootstrap mode:

  * `Application.attachBaseContext` — hookable
  * `Application.onCreate` — hookable
  * `ContentProvider.onCreate` — hookable, and provider classes are pre-loaded so the hook is live before the first provider is created
  * `Activity.onCreate` — hookable via Pine, Amiru, Native, or Instrumentation
  * `LayoutInflater.inflate` — hookable, drives `XC_LayoutInflated` callbacks
  * `Service.onCreate` — hookable via `ApplicationThread.scheduleCreateService` (8.6)
  * `BroadcastReceiver.onReceive` — hookable, and receiver classes are pre-loaded so the hook is live before the first broadcast
  * Classes loaded at runtime — hookable before their static initializer runs, via the `ClassLoader.loadClass` bridge (8.6)
  * Dynamic code (plugin/split APKs loaded via `DexClassLoader`) — reachable via the loader-construction listener (8.6)
  * `system_server` — not hookable

The `system_server` gap is structural. There's no fallback because there's no mechanism. If you need it, LSPosed is the answer.

`Service.onCreate` used to be on that list too. It isn't anymore — the `ApplicationThread` binder interface that `ActivityManagerService` calls into is reachable from the bootstrapped process, so the lifecycle events AMS schedules arrive before `ActivityThread` or `Instrumentation` sees them. 8.6 hooks that interface, and 1.0.x keeps it.

### How module rows respond to touch

The Modules tab row supports four gestures, each doing one thing:

Gesture | Action
---|---
Tap the row body | Opens the scope editor
Tap the enable switch | Toggles the module
Tap the icon | Opens the detail sheet
Long-press the row | Opens the module's UI through ShizuPosed

Uninstall lives in the detail sheet's `Uninstall` button. That's deliberate: opening a module's UI is a much more common action than removing it, and long-press is a reasonable gesture for it.

### XStealth's row behaves differently

XStealth's row keeps its special treatment. No enable switch on the row (its toggle lives in the detail sheet), and long-press opens the detail sheet rather than the UI, because XStealth has no launchable UI.

---

## Architecture

    ShizuPosed Manager (APK)
            │
            │  Shizuku AIDL
            ▼
    Shizuku (shell, UID 2000)
            │
            │  app_process
            ▼
    Target process
      ├── XposedHook.main()          bootstrap entry
      ├── ClassLoadingBridge         loadClass + loader discovery + pre-load
      ├── HookEngine                 backend selection + fingerprint tracking
      ├── HookDispatcher             per-method backend chain
      ├── DexLoadingBridge           dex load fallback ladder
      ├── ModuleLoader               dex load + entry invocation
      ├── ResourceHooking            resource + layout hooks
      ├── ApplicationThreadBackend   binder-level lifecycle hooks
      ├── XStealthModule             built-in privacy module
      │     ├── DevOptionsCheck      Settings.Global + resolver + Binder
      │     ├── AdbCheck             Settings.Secure + Global + resolver + Binder
      │     ├── CachedValueCheck     SharedPreferences reads (opt-in)
      │     ├── SettingsFileCheck    Runtime.exec + ProcessBuilder
      │     ├── SystemPropertiesCheck SystemProperties reads
      │     ├── BuildCheck           Build.TAGS/TYPE sanitization
      │     ├── PropertyScrubCheck   System.getProperties() scrubbing
      │     ├── SocketCheck          LocalSocket shim (opt-in)
      │     ├── PackageCheck         PackageManager lookups + Binder
      │     ├── RunningProcessCheck  ActivityManager lookups
      │     ├── ApiProtectionCheck   reflection + baselines
      │     ├── XStealthNative       libc symbol interposition
      │     ├── XStealthNativeNext   libc syscall stub patching (strategy ladder)
      │     ├── XStealthBridge       enumeration hiding
      │     └── ThreadNameScrub      thread renaming
      └── markers written to         <shell-base>/hooked/

    Manager process
      ├── ProcessMonitor             /proc scan + marker mirror refresh
      ├── MarkerCache                local mirror of <shell-base>/hooked/
      ├── IconResolver               LruCache for app icons
      ├── RuntimePrefs               scan interval + hook delay storage
      └── ModuleStatusProvider       reads the mirror, never Shizuku

The manager never touches the target process directly. Everything passes through Shizuku.

---

## The two injection paths

ShizuPosed reaches a target in one of two ways. Both use the same `app_process` primitive; they differ in whether the target's UI is opened after the spawn.

### Routed launch (user-triggered)

The "Launch App under ShizuPosed" button, the module row's long-press, and the detail sheet's "Open module app" entry all go through this path. It has two steps:

  1. Boot the target process with hooks installed:

         unset CLASSPATH; unset BOOTCLASSPATH;
         <prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]

     This starts the process and installs hooks during bootstrap.

  2. Open the target's UI in that already-running process:

         am start --user current -n <resolved-component>

Step 2 is required for this path: `app_process` booting a process does not start any activity, and without step 2 the process runs idle and the user sees nothing.

This is the path a **self-hooking module** needs. The module's own app is a normal, cooperative package with a launcher activity, so spawning it under ShizuPosed and then `am start`-ing its UI puts the module's own UI inside the process where its hook is installed. Its self-check then passes. That's why the README tells you to launch a module's UI through ShizuPosed — long-press the module row, or tap "Open module app" in the detail sheet.

### Monitor-triggered injection

`ProcessMonitor` scans `/proc` on a timer (default interval from Settings). When it sees a newly-started app process that at least one enabled module scopes, it calls `injectProcess`. That method performs a **spawn-only** routed launch for the package — it runs step 1 above but **not** step 2.

The spawn-only form matters: the user already opened the app themselves, and Android routes that launch into the bootstrapped process. Running `am start` on top of that would pop the app to the foreground a second time, on top of the user's own tap. So the monitor installs hooks and then leaves the UI alone.

Four guards keep this from misbehaving:

  * **UID.** Packages with `uid < 10000` are refused. The shell cannot `setuid` to protected UIDs, so a monitor-triggered launch for System UI, Settings, Phone, and the rest would fail or spawn a process as the wrong UID. They are skipped.
  * **Scope.** Only packages a module actually scopes are launched. An unscoped app the user opens is ignored, even if modules exist.
  * **Dedup.** A package that already has a shell-side hooked marker, or whose launch is already in flight, is skipped. This prevents the scan interval from queuing a second launch for an app whose first launch hasn't finished.
  * **In-flight set.** A package currently being spawned is skipped until that spawn completes.

### What neither path can do

Neither path can inject into a process someone else started. If you launch Facebook from the launcher, the process that comes up is not the one ShizuPosed will hook. The monitor notices it, but the only injection it can perform is a spawn-only routed launch — which starts a fresh process for the same package, with hooks installed during bootstrap. For apps that cooperate with that, it works. For apps that manage their own process lifecycle aggressively, it may not.

The distinction between the two paths is exactly the `openUi` flag on `launchAppUnderShizuPosed`:

  * `openUi=true` (default, button path) — spawn, then `am start`. Required for a module's own UI to come up with hooks live.
  * `openUi=false` (monitor path) — spawn only. The user's own tap opens the UI; hooks are already in the process when it does.

### What neither path can do

Neither path can inject into a process someone else started. If you launch Facebook from the launcher, the process that comes up is not the one ShizuPosed will hook. The monitor notices it, but the only injection it can perform is a routed launch — which starts a fresh process for the same package, with hooks installed during bootstrap. For apps that cooperate with that, it works. For apps that manage their own process lifecycle aggressively, it may not.

---

## The lifecycle bridges (8.6, stable in 1.0.x)

Two bridges extend what the framework can reach without changing the injection model.

**`ApplicationThreadBackend`** hooks the binder interface `ActivityThread` exposes to `system_server` (its `mAppThread` field). Everything `ActivityManagerService` schedules — activity launches, service creates, receiver broadcasts — arrives on this interface before `ActivityThread` or `Instrumentation` sees it. Hooking it closes the `Service.onCreate` gap (`scheduleCreateService`) and gives modules a place to observe or rewrite binder-level lifecycle events.

Methods hooked: `scheduleCreateService`, `scheduleBindService`, `scheduleUnbindService`, `scheduleStopService`, `scheduleServiceArgs`, `scheduleTransaction` (Android 9+). Each is a fail-open hook: if a signature differs on a ROM, that method is skipped and the others still install.

**`ClassLoadingBridge`** does three things:

  1. **`ClassLoader.loadClass` hook** — fires listeners *before* a class is defined, so a module can install hooks on a class before its static initializer runs.
  2. **`DexClassLoader` / `PathClassLoader` constructor hook** — when a target constructs a new loader at runtime, a listener is notified with the new loader.
  3. **Component pre-load** — asks `PackageManager` for the target's receivers and providers, then forces each class to load.

Both bridges are installed by `XposedHook` during bootstrap, before `handleLoadPackage` runs for any module. Modules register listeners via the bridge classes. XStealth registers its own listeners when `Protect Xposed API calls` is on, and those listeners **observe only** — they never block a class load or a binder call.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

`HookEngine` records the declaring class of every method or constructor it attempts to hook. That record feeds `ApiProtectionCheck`'s fingerprint baselines.

### What each backend can actually hook

| Backend | Primitive args | Object args | Primitive returns | Object returns | `thisObject` |
|---|---|---|---|---|---|
| Pine AUTO | yes | yes | yes | yes | yes |
| Pine REPLACEMENT | yes | yes | yes | yes | yes |
| Amiru | yes | **null** | yes | **not replaceable** | **null** |
| CallSite | **n/a** | **n/a** | **n/a** | **n/a** | **n/a** |
| Native (`libshizuposed`) | yes | **null** | yes | **not replaceable** | **null** |
| Instrumentation | yes | yes | n/a | n/a | yes |
| Proxy | yes | yes | yes | yes | yes |

The two rows in bold are the important ones. A module that hooks `void foo(String s)` and reads `s` from `beforeHookedMethod` receives `null` if the hook landed on Amiru or Native.

### CallSite backend status

CallSite is present in the dispatcher chain, but it is **not functional in 1.0.x**. It patches the ART interpreter handler table but does not yet reconstruct Java-level arguments from the interpreter's `ShadowFrame`. No callback is ever invoked through this path.

Since 8.9 the backend has been honest about this: `install_hook_impl` refuses every install with a logged reason, so `CallSiteBackend.hook` returns `false` and the dispatcher falls through to the next backend.

---

## The dex-loading fallback ladder

Loading a module's dex into a target process can fail for several reasons. `DexLoadingBridge` tries three strategies in order:

  1. **`InMemoryDexClassLoader`** (Android 8+).
  2. **`DexClassLoader`.**
  3. **`BaseDexClassLoader` injection.**

Each strategy logs its outcome.

### Spawn-tool fallback

At startup, ShizuPosed runs a single shell probe that checks for `setsid`, `nohup`, and the `disown` shell builtin. Detection prefers `command -v` and falls back to `type`. The result is cached in static fields.

| Condition | Spawn prefix | Disown appended |
|---|---|---|
| `setsid` and `nohup` present | `/system/bin/setsid /system/bin/nohup` | Yes, if supported |
| `setsid` missing, `nohup` present | `/system/bin/nohup` | Yes, if supported |
| `setsid` present, `nohup` missing | `/system/bin/setsid` | Yes, if supported |
| neither present | bare spawn | Yes, if supported |

### ART policy flags

Two ART flags are passed to every spawned `app_process`:

    -Xhidden-api-policy:enabled
    -Xcore-platform-api-policy:enabled

Setting both flags to `enabled` relaxes hidden-API enforcement for the process regardless of UID. They are safe: they only affect the process we spawned.

### Post-spawn verification

After the `BOOTSTRAP_DELAY_MS` wait (1500ms), ShizuPosed scans `/proc/*/cmdline` for the target package name as a **negative** signal. If the process is not found, the launch logs a warning and proceeds to `am start` anyway.

### Launch-result timeout

The manager's UI waits for a result broadcast from `ShizuPosedService` after dispatching a launch. A timeout (`LAUNCH_TIMEOUT_MS`, 20s) guards against a lost broadcast.

### Timing

The gap between step 1 and step 2 is `BOOTSTRAP_DELAY_MS`, defaulted to 1500ms.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

XStealth is organized in four layers, each independently toggleable:

  * **Java-layer checks** — hook Android APIs inside the target's own process.
  * **Binder-layer checks** — inspect `IBinder.transact` calls before they leave the process.
  * **Native layer** — interpose libc symbols and patch libc syscall stubs.
  * **Bridge layer** — hide from native enumeration paths.

All layers fail open. A bug in a check means the target gets the real API result, not a crash.

### Java-layer checks

**Developer Options and ADB.** `DevOptionsCheck` and `AdbCheck` hook every settings read path.

**Cached detection values.** `CachedValueCheck` hooks `SharedPreferences` reads for a per-app allow-list of keys. Opt-in, off by default, fail-open.

**Subprocess reads of the settings XML.** `SettingsFileCheck` hooks `Runtime.exec` (all five overloads) and `ProcessBuilder.start()`.

**System properties.** `SystemPropertiesCheck` hooks `android.os.SystemProperties.get`, `getBoolean`, `getInt`, and `getLong` for a watched key list.

**Build fields.** `BuildCheck` sets `Build.TAGS` to `release-keys`, `Build.TYPE` to `user`, `Build.HOST` and `Build.USER` to `android-build`.

**Framework properties.** `PropertyScrubCheck` removes `shizuposed.*` entries from `System.getProperties()`.

**Shizuku and ShizuPosed packages.** `PackageCheck` intercepts the standard `PackageManager` lookup methods.

**Running processes.** Filters `ActivityManager` process and service listings.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` when the caller isn't a loaded module.

**Method-count fingerprinting.** Pins a baseline for every framework class the framework has actually hooked.

### Binder-layer checks

The Binder-layer checks intercept the calls the Java APIs make *internally*, and the calls a target can make *directly* by skipping the Java API.

**`BinderProxy.transact` hook.** Every Binder call the target makes through the Java `IBinder` API goes through `BinderProxy.transact(int code, Parcel data, Parcel reply, int flags)`.

Three classes install it: `PackageCheck` covers `IPackageManager`, `AdbCheck` and `DevOptionsCheck` cover `IContentProvider`.

**`DUMP_TRANSACTION` handling.** `Binder.dump(fd, args)` calls also route through `transact`, with the fixed code `IBinder.DUMP_TRANSACTION`.

**`Binder.ProxyTransactListener`.** `PackageCheck` installs one for observation. It cannot refuse — the listener fires for every call, including ones the `transact` hook's parcel parser can't handle.

### Native layer

**`libxstealth.so`** — the primary native engine. Interposes the file, process, and exec-family libc symbols.

**`libxstealth_next.so`** — the aggressive engine. Inline-patches libc syscall stubs. Uses a **strategy ladder**: tries per-stub inline patching first, then falls back to patching the shared `syscall()` dispatcher.

**Stub-shape verification.** Both `libxstealth.so` and the backends that build hook stubs coordinate around a captured prologue template. At boot, `libxstealth.so` reads the entry-point bytes of two probe methods, computes their common prefix, and runs a verifier over it.

### Bridge layer

**`libxstealth_bridge.so`** — the enumeration-hiding library.

**Thread name scrubbing.** `ThreadNameScrub` renames ShizuPosed's own threads to innocuous patterns.

### What each layer covers

| Detection path | Java | Binder | Native | Bridge |
|---|---|---|---|---|
| `Settings.Global.getInt` in-process | ✅ | — | — | — |
| `ContentResolver.query` in-process | ✅ | — | — | — |
| Direct `IContentProvider.query` over Binder | — | ✅ | — | — |
| `cat settings_global.xml` via `Runtime.exec` | ✅ | — | ✅ | — |
| `open()` of settings XML | — | — | ✅ | — |
| `dumpsys settings get global <key>` via Binder | — | ✅ | — | — |
| Direct `IPackageManager` lookup over Binder | — | ✅ | — | — |
| `dumpsys package <pkg>` via Binder | — | ✅ | — | — |
| `PackageManager.getInstalledPackages` | ✅ | — | — | — |
| `dl_iterate_phdr` loaded-library walk | — | — | — | ✅ |
| `/proc/self/maps` scan for `.so` names | — | — | ✅ | — |
| `/proc/self/fd` enumeration | — | — | ✅ | ✅ |
| `/proc/self/task` enumeration | — | — | ✅ | ✅ |
| `getppid()` | — | — | ✅ | — |
| `PPid:` line in `/proc/self/status` | — | — | ✅ | — |
| `SystemProperties` reads (API) | ✅ | — | ✅ | — |
| `Build.TAGS`/`Build.TYPE` | ✅ | — | — | — |
| Hook-stub entry-point byte pattern | — | — | ✅ | — |
| **Second process the target spawned** | — | — | — | — |
| **Native `/dev/binder` direct** | — | — | — | — |
| **Property shared-memory area** | — | — | — | — |
| **Hardware attestation** | — | — | — | — |

The bottom four rows are the structural gaps — the ones no userspace framework closes.

### What XStealth doesn't hide

Hardware attestation (Play Integrity `MEETS_STRONG_INTEGRITY`). Root-empowered inspection. Server-side cross-reference. Native reads of the settings database that bypass the exec family entirely. Method-list reconstruction. Exotic reflection via `Unsafe` or JNI-level private methods. Kernel-level monitors. Signals unrelated to ShizuPosed.

### XStealth's practical ceiling

XStealth raises the bar significantly but it cannot make a ShizuPosed process genuinely indistinguishable from a normal app. The kernel's task struct, the dynamic linker's internal state, cross-process observation by a privileged reader, and hardware attestation are all beyond the reach of a userspace library.

---

## The subprocess gap

A common detection pattern is to bypass the Java settings API entirely and shell out.

XStealth closes this in two layers: `SettingsFileCheck` at the Java level, and the exec-family interposers in `libxstealth.so` at the native level.

**Still not covered:** native `execve` called before the `.so` is loaded, or from a process that loaded its own copy of libc.

---

## Socket shim — opt-in, off by default

`SocketCheck` is **off by default** and installs **no hooks** unless a specific daemon is configured. This is infrastructure, not a working feature.

---

## API surface — version 100

ShizuPosed reports Xposed API version **100** (LSPosed generation 93, plus fork revisions 94 through 100).

  * **93** — LSPosed base. `IXUnhook`, `isModuleActive` and `isModuleEnabled` with and without arguments, provider-backed state queries.
  * **94** — `hookAllMethods` returns `Set<XC_MethodHook.Unhook>`.
  * **95** — `hookAllConstructors` returns the same.
  * **96** — `MethodHookParam.isReturnEarly()` exposed.
  * **97** — Instance field accessors on `XposedBridge`: `getObjectField`, `setObjectField`.
  * **98** — Static field accessors on `XposedBridge`, and `XposedHelpers.getSurroundingThis`.
  * **99** — Method invokers on `XposedBridge`: `callMethod`, `callStaticMethod`.
  * **100** — ShizuPosed-specific extensions: `getShizuPosedApiMin()`, `getShizuPosedApiMax()`, `supportsApi(int)`.

Every revision past 93 is additive. A module compiled against API 93 loads and behaves the same way under this shim, because the shim still exposes the earlier surface. The version number is the ceiling, not the floor.

Modules that guard on 101 or higher correctly believe ShizuPosed doesn't support those fork revisions.

The API version is unchanged in 1.0.1.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

  1. `assets/xposed_init` — the canonical marker.
  2. `assets/xposed_module` — an older convention.
  3. `META-INF/xposed/module.prop` — EdXposed-era.
  4. `assets/native_init` — a weak signal.

### Recommended scope

Modules can declare which apps they're _intended_ for by shipping an `assets/scope.list` file inside the APK.

### The scope editor

The scope editor lists the apps a module can be applied to. Not every installed package appears:

  * **Protected UIDs are hidden.** A package whose UID is below 10000 — `SYSTEM_UID` (1000) and the reserved system UID pool (1001–1999) — is not offered. Those are the packages the shell UID cannot `setuid` to, and a scope entry for them would never fire. As of 1.0.1 they are filtered out of the picker.
  * **The manager itself is hidden.** Scoping ShizuPosed is meaningless; it's the framework, not a target.
  * **Everything else is shown.** Ordinary apps (`uid >= 10000`) and preinstalled apps with a dedicated UID in that range appear and can be selected.

A package whose UID is reported as 0 during package scanning falls back to a name-prefix check (`android.`, `com.android.`, `com.google.android.`) so framework packages are still excluded and preinstalled apps are still shown.

---

## Activation state — how "Activated" actually works

Two distinct questions. "Am I enabled?" comes from `XposedBridge.isModuleEnabled(pkg)`. "Am I active?" comes from `XposedBridge.isModuleActive(pkg)`.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per hooked target to `<shell-base>/hooked/<pkg>.json` after installing hooks. The manager mirrors the markers into its own files directory.

### The query chain

    Module UI process
      → XposedBridge.isModuleActive(pkg)
        → LSPosedManager.isModuleActive(pkg)
          → ContentResolver.query(content://com.shizuposed.manager.status/active/<pkg>)
            → ModuleStatusProvider
              → reads <manager files>/.markers/*.json
              → returns active=1 or active=0

### The Android 11+ package visibility requirement

On Android 11 and later, a module that queries the status provider from its own UI must declare the provider's authority in the module's own `AndroidManifest.xml`:

    <queries>
        <provider android:authorities="com.shizuposed.manager.status" />
    </queries>

### Modules that check their own activation state

Some modules don't query the framework's provider. They hook one of their own UI methods and use the hook's presence as the activation signal. Under ShizuPosed that means launching the module's UI through ShizuPosed.

**How to do it:**

  * Long-press the module's row in the Modules tab.
  * Or tap "Open module app" in the detail sheet.

### Modules that don't self-hook

Many modules don't use the self-hook pattern. Some are pure system-hook modules — CorePatch, for example, only does work when `packageName == "android"`. Those modules require LSPosed.

---

## Module development

Standard Xposed API.

    public class MainHook implements IXposedHookLoadPackage {
        @Override
        public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
            XposedBridge.log("Hooking " + lpparam.packageName);
            XposedHelpers.findAndHookMethod(
                "com.example.target.MainActivity", lpparam.classLoader,
                "onCreate", android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        // ...
                    }
                });
        }
    }

### Using the lifecycle bridges

A module that needs to act on a class *before* its static initializer runs:

    ClassLoadingBridge.registerClassLoadListener((name, loader) -> {
        if ("com.example.target.SecretDetector".equals(name)) {
            XposedHelpers.findAndHookMethod(name, loader, "check", ...);
        }
    });

A module that needs to intercept a service before it is created:

    ApplicationThreadBackend.registerListener((methodName, args) -> {
        if ("scheduleCreateService".equals(methodName)) {
            // ...
        }
        return true;
    });

### Reporting state in a module UI

    boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
    boolean active  = XposedBridge.isModuleActive(getPackageName());
    String[] scope  = XposedBridge.getModuleScope(getPackageName());

### Resource replacement

    public class Res implements IXposedHookInitPackageResources {
        @Override
        public void handleInitPackageResources(
                XC_InitPackageResources.InitPackageResourcesParam resparam) {
            resparam.res.setReplacement(R.string.some_string, "replacement");
        }
    }

### Layout hooks

    resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(LayoutInflatedParam param) {
            // param.view, param.resId, param.resName are set
        }
    });

### Writing a hook that works on every backend

Because Amiru and Native can't carry object arguments or object returns, a module that needs those should either rely on Pine succeeding, avoid object arguments entirely, or accept that the hook may not fire with the values it expects.

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is **Shizuku-Next** by rushiranpise at `github.com/rushiranpise/Shizuku-Next`. About 200 MB free storage. No root required.

For building the app: JDK 25 (with a Gradle toolchain), Gradle 9.8, Android SDK 37. Native libraries rebuild with `clang` alone on Termux; no NDK is required if you're building on-device. Building with the Android NDK (r25+) works and is what CI uses.

Upstream Shizuku works. Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (Shizuku-Next recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Install the ShizuPosed Manager APK:

    adb install -r app-release.apk

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to — protected-UID packages won't appear in the picker. Then either tap **Launch App under ShizuPosed** and pick a scoped app, or just open a scoped app normally; the process monitor will trigger a routed launch for it.

Activation isn't retroactive for a process already running. The monitor catches a scoped app when it starts; an app that was already running before the module was scoped is not retroactively hooked.

---

## The manager

Five tabs.

**Home** shows the status card, the counter tiles, and the Framework Info card.

**Modules** lists installed modules with XStealth always at the top.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has the runtime toggles, scan interval and hook delay sliders, the Maintenance section, and config export.

### XStealth detail sheet

Fifteen toggles, organized by layer:

**Master toggles**
  * **Enable XStealth** — the master switch
  * **Enable XStealth Next** — the aggressive native engine
  * **Use bridge** — the enumeration-hiding library

**Settings-based checks**
  * **Hide Developer Options**
  * **Hide ADB enabled**

**System properties and Build fields**
  * **Hide system properties**
  * **Sanitize Build fields**

**Framework properties and threads**
  * **Scrub framework properties**
  * **Rename framework threads**

**Package and process**
  * **Hide Shizuku package**
  * **Hide ShizuPosed package**
  * **Hide running processes**
  * **Hide /proc entries**

**API protection and dex**
  * **Protect Xposed API calls**
  * **Optimize module dex**

The status line at the top of the sheet summarizes which layers are armed.

Note: `Hide Developer Options` and `Hide ADB enabled` change what a target *reads*, not what the device actually has set.

---

## Provenance and verification

ShizuPosed is built from sources in this repository. The APK is reproducible from a clean checkout with the documented toolchain.

**Signed builds.** The manager APK is signed with the project's release key.

**Native library provenance.** Each `.so` is built from the `.c` file with the same name in the `native/` directory of this repository.

**What to look for in a release.** The release notes list the NDK version used, the SDK version, the Gradle and JDK versions, and a SHA-256 of each native library.

---

## Building

### The app

    export ANDROID_HOME=$HOME/Android/Sdk
    git clone <repo>
    cd ShizuPosed
    ./gradlew clean :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

### The native libraries

The five native libraries and `libcallsite.so` build with the Android NDK. On Termux, an ARM64 Android NDK is required. Build for API 29 or higher.

### The build checks

The `app/build.gradle` includes six verification tasks. Skip all six with:

    ./gradlew assembleRelease -PskipNativeCheck=true

### Configuration cache

The build is configuration-cache-compatible with Gradle 9.8.

---

## Reading the launch log

Every routed launch writes stderr and stdout from `app_process` to `<shell-base>/launch.log`:

    cat /data/user/0/com.android.shell/files/.syscall_cache/launch.log

The spawn-tool probe result is logged once at startup. XStealth's native preload happens first. The stub-template capture and verification logs its outcome. Each Binder-hooking class logs its refusals. The two lifecycle bridges log their own install. Each XStealth layer logs its own status.

Monitor-triggered launches log through the same path. Each call to `injectProcess` logs which guard it passed, which it failed, and whether the routed launch succeeded.

---

## Changelog

### 1.0.1

**Monitor-triggered injection is now real.** `ShizuPosedService.injectProcess` was a stub that logged `"Cannot hook externally-launched process"` and returned `false`. It now performs a routed launch for scoped packages the process monitor detects. The stub was the reason a scoped app could be opened normally and never get hooks — the trigger fired and the injection refused.

  * Guarded by UID: packages with `uid < 10000` are refused. The shell cannot `setuid` to `SYSTEM_UID` (1000) or the reserved pool (1001–1999).
  * Guarded by scope: only packages at least one enabled module scopes are launched.
  * Guarded by dedup: a package with an existing shell-side marker, or an in-flight launch, is skipped.
  * The pid the monitor detected is used for logging and dedup only. Nothing non-root can attach to it. Hooks land in the process the routed launch creates.

**Monitor-triggered injection is spawn-only.** The monitor path now calls `launchAppUnderShizuPosed(pkg, sent, false)`. That flag makes the routed launch stop after step 1 — the `app_process` spawn that installs hooks — and skip step 2, the `am start` that opens the UI.

This was a bug in the first cut of the injection fix: the monitor would spawn a bootstrapped process *and* `am start` the app, popping it to the foreground on top of the user's own launch. The user is the one who opens the app; the monitor's job is only to have hooks live by the time that happens. The `openUi` split encodes that.

  * `launchAppUnderShizuPosed(pkg, sent)` — the button path. Spawn + `am start`. Unchanged behavior.
  * `launchAppUnderShizuPosed(pkg, sent, false)` — the monitor path. Spawn only. No `am start`.

Both paths still go through `ensurePayloadReady`, module push, and the spawn-tool probe. Only step 2 is conditional.

**Scope editor hides protected-UID packages.** `AppSelectionAdapter` now filters the installed-app list it displays:

  * Packages with `uid < 10000` are not offered. This covers System UI, Settings, Phone, Bluetooth, NFC, Shell, and `system_server` — all the packages the shell cannot reach.
  * ShizuPosed's own package is not offered.
  * Preinstalled apps with a dedicated UID in the 10000+ range are still shown, because they are ordinary userspace targets from the shell's point of view.
  * A package whose UID reads as 0 during scanning falls back to a framework name-prefix check so framework packages stay excluded and preinstalled apps stay visible.

**Documentation.** The README now describes the two injection paths, states that the monitor path is spawn-only and never opens the UI, states the shell UID's `setuid` limits explicitly, and clarifies that activation is not retroactive for a process already running when the scope is set.

No API changes. Modules compiled against API 100 behave identically.

### 1.0.0

**API surface 96 → 100**

The shim now reports Xposed API version 100. Four revisions were added since 96, all additive:

  * **97** — `XposedBridge.getObjectField`, `XposedBridge.setObjectField`.
  * **98** — `XposedBridge.getStaticObjectField`, `XposedBridge.setStaticObjectField`, `XposedHelpers.getSurroundingThis`.
  * **99** — `XposedBridge.callMethod`, `XposedBridge.callStaticMethod`.
  * **100** — ShizuPosed-specific extensions: `getShizuPosedApiMin()`, `getShizuPosedApiMax()`, `supportsApi(int)` on both `XposedBridge` and `XposedHelpers`.

Every existing signature is preserved. A module compiled against API 93, 94, 95, or 96 loads and behaves exactly as before. A module compiled against 97–100 finds the surface it expects.

**STABILIZATION**

This is the first stable release. The timing model, the backend chain, the routed launch protocol, and the XStealth layer structure are frozen for 1.x. Patch releases within 1.x will be backward-compatible. The next breaking change is 2.0.

**CONSOLIDATED**

  * The 9.x series finalized the Binder-layer hiding, the stub-shape verification, the lifecycle bridges, and the four XStealth layers. 1.0.0 folds all of that into a single stable release.
  * The `LSPosedManager.API_VERSION` constant is now 100, matching `XposedBridge.XPOSED_BRIDGE_VERSION`.
  * Documentation was rewritten to reflect the current architecture.

### 9.3

Masking the process zygote by hooking `/proc/self/fd/3` via XStealth to avoid correlation.

### 9.2

Covered accessibilityservice for hiding via XStealth.

### 9.1

Improve `libxstealth` and its `libxstealth_next`.

### 9.0

**NEW — Binder-level hiding in `PackageCheck`, `AdbCheck`, and `DevOptionsCheck`.** `BinderProxy.transact` hooks for `IPackageManager` and `IContentProvider`. `DUMP_TRANSACTION` handling for `dumpsys`. `Binder.ProxyTransactListener` install in `PackageCheck` for observation.

### 8.9

**FIXED** — `prologue_info` struct size. **FIXED** — `dl_iterate_phdr` wrapper wiring. **FIXED** — `read_proc_comm` recursion. **FIXED** — `libxstealth_next.c` SVC-first patch offset. **FIXED** — `openat` mode vararg. **FIXED** — `libamiru.c` arg-signature storage collision. **IMPROVED** — stub pools no longer stay RWX. **IMPROVED** — CallSite refuses installs.

### 8.8

**NEW** — entry-point shape matching for hook stubs. **NEW** — debug logging toggle. **NEW** — describe and dump diagnostics.

### 8.6

**NEW** — `ApplicationThreadBackend` binder-level lifecycle hooks. **NEW** — `ClassLoadingBridge`. **IMPROVED** — `preloadXStealthNative()`. **IMPROVED** — XStealth uses both bridges for diagnostics.

### 8.4

**NEW** — `CachedValueCheck`. **IMPROVED** — `AdbCheck` and `DevOptionsCheck` honor toggles. **FIXED** — `DevOptionsCheck` selection handling.

### 8.3

**NEW** — Hiding `getppid()`, `PPid` line, sysfs USB state, `/proc/net/unix`. **KNOWN INCOMPATIBILITY** — BioCatch-based banking apps.

### 8.2

**IMPROVED** — system property scrubbing. **NEW** — `__system_property_get` interposition. **NEW** — `/data/property/persistent_properties` blocking. **FIXED** — duplicate native declarations.

### 8.1

Bug fixes.

### 8.0

**IMPROVED** — the compatibility layer now figures things out at runtime. **NEW** — diagnostics.

### 7.9

**IMPROVED** — XStealth coverage extended to the enumeration layer. **NEW** — `libxstealth_bridge.so`. **IMPROVED** — strategy ladder, diagnostics.

### 7.3

**FIXED** — module auto-reopen. **IMPROVED** — explicit-component `am start`, `CLASSPATH` removed, presence check.

### 7.2

**REMOVED** — `IXposedHookCmdInit`. **NEW** — spawn-tool fallback ladder.

### 7.1

**FIXED** — routed launch now opens the UI. **NEW** — `launch.log`.

### 7.0

**NEW** — subprocess hiding, socket shim, native library reconstruction.

### Earlier

See the release notes on the Releases page.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. Cross-process hooks aren't possible. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework.

**Protected UIDs are unreachable.** The shell UID cannot `setuid` to `SYSTEM_UID` (1000) or the reserved system UID pool (1001–1999). System UI, Settings, Phone, Bluetooth, NFC, Shell, and `system_server` cannot be hooked by any path. The scope editor hides them so they can't be selected. Ordinary apps (`uid >= 10000`), including preinstalled apps with a dedicated UID, are reachable.

**Zygote-timing APIs.** Modules that implement `IXposedHookZygoteInit` expect a callback that only fires under a zygote-based framework. ShizuPosed declares the interface for link compatibility but cannot call it. `IXposedHookCmdInit` was removed in 7.2.

**Service.onCreate.** Reachable in the process ShizuPosed launched, via the `ApplicationThread` binder interface.

**Monitor-triggered injection doesn't attach to the detected pid.** Nothing non-root can. When the monitor sees a scoped app start, it can only trigger a routed launch, which creates a new process for that package. For apps that cooperate with that, hooks install during bootstrap. For apps that manage their own process lifecycle aggressively, the routed launch may not produce a usable process — the same ceiling as the button-triggered path.

**Object arguments and object returns on the native backends.** Amiru and Native pass object arguments to Java callbacks as `null`, and don't accept object return values. `thisObject` is also `null` on both. Pine, Instrumentation, and Proxy handle objects correctly.

**Entry-point shape matching has a ceiling.** The stub's prologue matches what ART emits for the first five or six instructions. A detector that reads past the prologue, or that disassembles the whole stub, still sees the difference.

**XStealth sanitizes reads, not values.** The underlying value of `development_settings_enabled` in the settings database is unchanged by XStealth.

**XStealth's practical ceiling.** XStealth covers the common detection paths. It doesn't cover hardware attestation, native reads of the settings database through the property shared-memory area, method-list reconstruction, exotic reflection, kernel-level watchers, full-stub disassembly, or signals unrelated to ShizuPosed.

**Bridge layer caveats.** The bridge library filters `dl_iterate_phdr` process-wide. `getenv` scrubbing only covers the API path.

**Subprocess hiding has a load-order window.** Since 8.6, the native engines load at the top of `XposedHook.main()`, so the window is a few milliseconds.

**Socket shim is inert by default.** `SocketCheck` installs no hooks unless a daemon is configured.

**CallSite is non-functional.** The backend patches ART's interpreter dispatch table but does not yet reconstruct Java-level arguments from the interpreter's `ShadowFrame`. Since 8.9, it refuses every install.

**Modules that require LSPosed.** Some modules do their work in `system_server`.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**Binder-level hiding has structural gaps.** The `BinderProxy.transact` hooks only see calls that go through the Java `IBinder.transact` method in *this process*. They do not see:

  * **Calls from a second process the target spawned.**
  * **Native code that opens `/dev/binder` directly.**
  * **Calls made before the hooks install.**

**Banking apps that ship native runtime-integrity SDKs (BioCatch and friends).** Some banking apps come bundled with third-party fraud-detection SDKs that check the app's own runtime state. The one you'll run into most often is **BioCatch** — HSBC Philippines and several other regional banks use it.

XStealth shuts down the file and property channels BioCatch reads through libc, and closes the `IPackageManager` and `IContentProvider` Binder channels for package and settings queries. What it can't shut down:

  * The **USB Binder channel** — BioCatch calls `IUsbManager.getCurrentFunctions()` directly.
  * The **ART-integrity channel** — shape matching closes the byte-pattern check on the entry point's first instructions; it doesn't close a check that disassembles the whole stub.
  * **Behavioral signals** — tap timing and swipe pressure aren't reads the framework can intercept.

The only workaround that holds up:

  1. Turn off Developer Options completely.
  2. Turn off USB debugging.
  3. Reboot.
  4. Open the banking app.

If it still flags you after all that, the detection isn't coming from local device state. It's coming from a hardware attestation signal, a server-side fingerprint, or a check on where the app was installed from.

This isn't a ShizuPosed bug. Without root, no framework is going to beat BioCatch's runtime-integrity checks.

---

## License

    Copyright 2024-2026 ShizuPosed Contributors

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

---

## Acknowledgements

Rikka, for Shizuku — the primitive that makes all of this possible. canyie, for Pine, the primary ART hooking engine. LSPosed, for the API generation ShizuPosed targets, and for the design of the module status provider contract.

And every module author who kept the `de.robv.android.xposed.*` API alive long enough for an alternative to matter.