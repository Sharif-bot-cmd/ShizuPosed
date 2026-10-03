# ShizuPosed

**An Xposed-compatible hook framework that doesn't need root — it runs on `app_process`.**

ShizuPosed lets Xposed-API modules run inside apps that it launches. No root, no bootloader unlock, no messing with the system partition. Shizuku calls `app_process` as the shell UID, a Java runtime spins up inside the target's process, and method hooks get installed through a dispatcher that tries several backends until one sticks. Modules built for LSPosed and classic Xposed keep working.

This is version 8.7.

---

## What this is, and what it isn't

This section comes first because everything else follows from it.

ShizuPosed is **not** a drop-in replacement for LSPosed. It doesn't try to be. It uses a different injection primitive — `app_process` into a target that ShizuPosed launches — because that's the only injection path that reliably works without root on modern Android. That choice is what makes ShizuPosed possible, and it's also what sets the ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or cross-process hooks, use LSPosed. ShizuPosed is for people who can't root or won't, and who still want LSPosed-API modules to work in the apps ShizuPosed launches.

The rest of this document is honest about where that line falls.

---

## Why `app_process`

It helps to understand what ShizuPosed _can't_ do before looking at what it can.

Without root, the injection primitives on Android 10+ are narrow. `ptrace` is blocked by SELinux for non-root UIDs. `LD_PRELOAD` needs a writable, executable path the target will load from, and app data directories are mounted `noexec`. Zygote fork requires being inside zygote, which means root or being the ROM. `Runtime.exec` runs as the caller's UID, not the target's. `am instrument` gives you an `Instrumentation` handle only after `Application.onCreate` — too late for bootstrap hooks. ContentProvider hijack works only if the target already declares a provider you can take over.

`app_process` is the one that works. It's a platform binary that exists on every Android device. Shizuku can invoke it as shell (UID 2000). It starts a Java runtime, so you can load arbitrary dex into it. And Shizuku can pass the target package and UID as arguments.

That's the entire mechanism. Everything else in ShizuPosed is a consequence of it.

---

## The timing model

LSPosed is a zygote-timing framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a bootstrap-timing framework: it's `app_process`-launched into the target, installs hooks, then drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

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

`Service.onCreate` used to be on that list too. It isn't anymore — the `ApplicationThread` binder interface that `ActivityManagerService` calls into is reachable from the bootstrapped process, so the lifecycle events AMS schedules arrive before `ActivityThread` or `Instrumentation` sees them. 8.6 hooks that interface.

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
      │     ├── DevOptionsCheck      Settings.Global + resolver hooks
      │     ├── AdbCheck             Settings.Secure + Global + resolver
      │     ├── CachedValueCheck     SharedPreferences reads (opt-in)
      │     ├── SettingsFileCheck    Runtime.exec + ProcessBuilder
      │     ├── SystemPropertiesCheck SystemProperties reads (7.9)
      │     ├── BuildCheck           Build.TAGS/TYPE sanitization (7.9)
      │     ├── PropertyScrubCheck   System.getProperties() scrubbing (7.9)
      │     ├── SocketCheck          LocalSocket shim (opt-in)
      │     ├── PackageCheck         PackageManager lookups
      │     ├── RunningProcessCheck  ActivityManager lookups
      │     ├── ApiProtectionCheck   reflection + baselines
      │     ├── XStealthNative       libc symbol interposition
      │     │     └── includes exec-family interposers
      │     ├── XStealthNativeNext   libc syscall stub patching (strategy ladder)
      │     ├── XStealthBridge       enumeration hiding (7.9)
      │     └── ThreadNameScrub      thread renaming (7.9)
      └── markers written to         <shell-base>/hooked/

    Manager process
      ├── ProcessMonitor             /proc scan + marker mirror refresh
      ├── MarkerCache                local mirror of <shell-base>/hooked/
      ├── IconResolver               LruCache for app icons
      ├── RuntimePrefs               scan interval + hook delay storage
      └── ModuleStatusProvider       reads the mirror, never Shizuku

The manager never touches the target process directly. Everything passes through Shizuku.

---

## The lifecycle bridges (8.6)

Two new bridges extend what the framework can reach without changing the injection model.

**`ApplicationThreadBackend`** hooks the binder interface `ActivityThread` exposes to `system_server` (its `mAppThread` field). Everything `ActivityManagerService` schedules — activity launches, service creates, receiver broadcasts — arrives on this interface before `ActivityThread` or `Instrumentation` sees it. Hooking it closes the `Service.onCreate` gap (`scheduleCreateService`) and gives modules a place to observe or rewrite binder-level lifecycle events.

Methods hooked: `scheduleCreateService`, `scheduleBindService`, `scheduleUnbindService`, `scheduleStopService`, `scheduleServiceArgs`, `scheduleTransaction` (Android 9+). Each is a fail-open hook: if a signature differs on a ROM, that method is skipped and the others still install.

**`ClassLoadingBridge`** does three things:

  1. **`ClassLoader.loadClass` hook** — fires listeners *before* a class is defined, so a module can install hooks on a class before its static initializer runs. Without it, `<clinit>` executes first, and any irreversible work it does has already happened by the time a hook could be placed.
  2. **`DexClassLoader` / `PathClassLoader` constructor hook** — when a target constructs a new loader at runtime (plugin systems, split APKs, hot-reload), a listener is notified with the new loader so the module can hook classes inside it. Without it, dynamic code is entirely invisible.
  3. **Component pre-load** — asks `PackageManager` for the target's receivers and providers, then forces each class to load. This makes a module's hooks on those classes live before the first broadcast or the first `ContentProvider.onCreate`. Before this, a receiver hook could miss the first broadcast if the class hadn't been loaded for some other reason.

Both bridges are installed by `XposedHook` during bootstrap, before `handleLoadPackage` runs for any module. Modules register listeners via the bridge classes. XStealth registers its own listeners when `Protect Xposed API calls` is on, and those listeners **observe only** — they never block a class load or a binder call, because doing so would be a behavior the target notices, which is the opposite of what XStealth wants.

Pre-loading uses `PackageManager`, not a hand-parsed `AndroidManifest.xml`. The manifest-parsing path used hidden `AssetManager` reflection that varied by OEM ROM and crashed on some; the `PackageManager` path is a public API and works everywhere.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

`HookEngine` records the declaring class of every method or constructor it attempts to hook. That record feeds `ApiProtectionCheck`'s fingerprint baselines.

---

## The dex-loading fallback ladder

Loading a module's dex into a target process can fail for several reasons. `DexLoadingBridge` tries three strategies in order:

  1. **`InMemoryDexClassLoader`** (Android 8+). Reads the dex into a `ByteBuffer` and loads it without any filesystem write.
  2. **`DexClassLoader`.** The classic path. Reads the dex from a file and uses an optimization directory for ART's compiled output.
  3. **`BaseDexClassLoader` injection.** Appends the module's dex to the target's existing classloader by reflection into its `DexPathList`.

Each strategy logs its outcome.

---

## The two-step routed launch

A routed launch isn't a single operation. It's two, in order, and both are required.

### Step 1 — boot the process

    unset CLASSPATH; unset BOOTCLASSPATH;
    <spawn-prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]

This starts `app_process` as the target's UID. `XposedHook.main()` runs inside that process, installs hooks during bootstrap, and leaves the process idle with hooks live. `stderr`/`stdout` are redirected to `<shell-base>/launch.log` so bootstrap failures are visible.

The `unset CLASSPATH; unset BOOTCLASSPATH;` prefix is required. Some Shizuku implementations (notably Shevery) export `CLASSPATH=<their APK>` into the shell environment. `app_process` reads `CLASSPATH` before the Java runtime starts and treats it as a boot-classpath override, so `com.android.internal.os.RuntimeInit` becomes unreachable and the process aborts. Unsetting both forces `app_process` to use its compiled-in defaults.

`<spawn-prefix>` is chosen at runtime from the spawn tools the device provides, and `disown` is appended when the shell supports it. See "Spawn-tool fallback" below.

### Step 2 — open the UI

    am start --user current -n <resolved-component>

This tells `ActivityManager` to launch the target's UI. The activity runs in the process we booted in step 1, so the hooks from step 1 are live when the UI opens.

**Step 2 is required.** `app_process` booting a process does not start any activity. Without step 2, the process runs idle and the user sees nothing.

Step 2 prefers an **explicit component** (`-n package/class`) over an implicit launcher intent (`-a MAIN -c LAUNCHER -p <pkg>`). Explicit-component starts are resolved ahead of time via `cmd package resolve-activity --brief -a MAIN -c LAUNCHER <pkg>`, with a fallback to `-a MAIN` alone for modules whose UI has `MAIN` but not `LAUNCHER`. The implicit form is used only if resolution fails entirely.

Explicit-component starts avoid an auto-reopen problem: implicit launcher intents against a package name can be re-delivered by `ActivityManager` after the activity exits, causing the module to reopen without user action. Explicit starts don't have that behavior.

### Spawn-tool fallback

Step 1 depends on `setsid` and `nohup` to detach the spawned process from the shell that launches it. At startup, ShizuPosed runs a single shell probe that checks for `setsid`, `nohup`, and the `disown` shell builtin. Detection prefers `command -v` and falls back to `type`. The result is cached in static fields so the probe runs once per process.

The spawn prefix is then chosen as follows:

| Condition | Spawn prefix | Disown appended |
|---|---|---|
| `setsid` and `nohup` present | `/system/bin/setsid /system/bin/nohup` | Yes, if the shell supports `disown` |
| `setsid` missing, `nohup` present | `/system/bin/nohup` | Yes, if the shell supports `disown` |
| `setsid` present, `nohup` missing | `/system/bin/setsid` | Yes, if the shell supports `disown` |
| neither present | bare spawn (no prefix) | Yes, if the shell supports `disown` |

The spawn command uses absolute paths (`/system/bin/sh`, `/system/bin/setsid`, `/system/bin/nohup`) so the launch does not depend on `PATH`.

### ART policy flags

Two ART flags are passed to every spawned `app_process`:

    -Xhidden-api-policy:enabled
    -Xcore-platform-api-policy:enabled

The target's process runs as the target's UID, so by default it is subject to the hidden-API enforcement added in Android 9. `XposedHook` needs to reach into `ActivityThread`, `ApplicationInfo`, and other non-SDK internals to install hooks. Without these flags, those calls throw `NoSuchMethodError` / `NoSuchFieldError` even though the framework's reflection would otherwise succeed.

Setting both flags to `enabled` relaxes enforcement for the process regardless of UID. They are safe: they only affect the process we spawned, and they do not bypass SELinux or the Binder permission model.

### Post-spawn verification

After the `BOOTSTRAP_DELAY_MS` wait (1500ms), ShizuPosed scans `/proc/*/cmdline` for the target package name as a **negative** signal. If the process is not found, the launch logs a warning and proceeds to `am start` anyway. `am start` is the real arbiter of whether the UI opens.

### Launch-result timeout

The manager's UI waits for a result broadcast from `ShizuPosedService` after dispatching a launch. A timeout (`LAUNCH_TIMEOUT_MS`, 20s) guards against a lost broadcast. When the broadcast arrives, the timeout is cancelled. When it doesn't, the timeout logs a warning and shows an informational toast — it does **not** re-launch the app, because that caused an auto-reopen bug.

### Timing

The gap between step 1 and step 2 is `BOOTSTRAP_DELAY_MS`, defaulted to 1500ms.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

XStealth is organized in three layers, each independently toggleable:

  * **Java-layer checks** — hook Android APIs inside the target's own process.
  * **Native layer** — interpose libc symbols and patch libc syscall stubs.
  * **Bridge layer** — hide from native enumeration paths.

All layers fail open. A bug in a check means the target gets the real API result, not a crash.

### Java-layer checks

**Developer Options and ADB.** Hooks every settings read path: `Settings.Global` and `Settings.Secure` static getters, the `ForUser` variants, and direct `ContentResolver.query` calls against `content://settings/{global,secure}/<key>`.

**Cached detection values.** `CachedValueCheck` (8.4) hooks `SharedPreferences` reads for a per-app allow-list of keys. If an app cached a detection result before XStealth was in play, the cached read returns the same sanitized value the live-read checks return, so the two agree. Opt-in, off by default, fail-open.

**Subprocess reads of the settings XML.** `SettingsFileCheck` hooks `Runtime.exec` (all five overloads) and `ProcessBuilder.start()`. When a command references a settings XML path or a watched shell-side path, it returns a synthetic `Process` whose stdout is a scrubbed XML document, with exit code 0. The subprocess never spawns.

**System properties.** `SystemPropertiesCheck` (7.9) hooks `android.os.SystemProperties.get`, `getBoolean`, `getInt`, and `getLong` for a watched key list. Reads of `ro.debuggable`, `ro.secure`, `ro.boot.verifiedbootstate`, `ro.boot.flash.locked`, `ro.build.type`, `ro.build.tags`, and similar return sanitized production values.

**Build fields.** `BuildCheck` (7.9) sets `Build.TAGS` to `release-keys`, `Build.TYPE` to `user`, `Build.HOST` and `Build.USER` to `android-build`. This closes the most common custom-ROM detection vector.

**Framework properties.** `PropertyScrubCheck` (7.9) removes `shizuposed.*` entries from `System.getProperties()` and `System.getProperty(String)`.

**Shizuku and ShizuPosed packages.** Intercepts `PackageManager.getPackageInfo`, `getApplicationInfo`, `getInstalledPackages`, and `getInstalledApplications` — the int overloads and the newer `PackageInfoFlags` / `ApplicationInfoFlags` overloads. Lookups by name return the same values the platform would return for a genuinely absent package: `NameNotFoundException` where the platform throws, `-1` for `getPackageUid`, `null` for `getInstallerPackageName`.

**Running processes.** Filters `ActivityManager.getRunningAppProcesses`, `getRunningServices`, and `getRunningTasks`.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` when the caller isn't a loaded module. It also filters reflection walks on shim classes and hooks resource/package lookups for shim paths.

**Method-count fingerprinting.** Pins a baseline for every framework class the framework has actually hooked.

### Native layer

**`libxstealth.so`** — the primary native engine. Interposes `open`, `openat`, `fopen`, `access`, `faccessat`, `stat`, `lstat`, `fstatat`, `readlink`, `read`, `execve`, `posix_spawn`, `posix_spawnp`, `popen`, and `system` via symbol preemption. Scrubs `/proc/self/maps`, `smaps`, `cmdline`, `status`, `mountinfo`, `task`, `exe`, `environ` (7.9), and `cmdline` (7.9, preserving the process name).

**`libxstealth_next.so`** — the aggressive engine. Inline-patches libc syscall stubs so syscalls invoked directly also go through XStealth's filtering. Uses a **strategy ladder** (7.9): tries per-stub inline patching first, then falls back to patching the shared `syscall()` dispatcher if the stubs can't be classified. This makes it survivable across bionic changes. The active strategy is exposed to the Java layer.

### Bridge layer (7.9)

**`libxstealth_bridge.so`** — the enumeration-hiding library. Where the primary and Next engines hide ShizuPosed from the API and syscall layers, the bridge hides it from native enumeration:

  * **`dl_iterate_phdr`** — filters ShizuPosed libraries from the loaded-library walk.
  * **`/proc/self/fd`** — filters file descriptors that point to ShizuPosed paths when the fd directory is walked.
  * **`/proc/self/task`** — filters ShizuPosed thread names when the task directory is walked.
  * **`getenv`** — returns NULL for `shizuposed.*` environment variables.
  * **`readlink`** — blocks fd-to-path resolutions that reveal ShizuPosed.

**Thread name scrubbing** (7.9). `ThreadNameScrub` renames ShizuPosed's own threads to innocuous `pool-N-thread-M` patterns before any module loads.

### XStealth's use of the lifecycle bridges (8.6)

When `Protect Xposed API calls` is on, XStealth registers listeners with both bridges:

  * A **`ClassLoadListener`** logs when the target tries to load a class in the `de.robv.android.xposed.*` namespace. `ApiProtectionCheck` already refuses `Class.forName` for that namespace; this listener records the `loadClass` path, which `ApiProtectionCheck` does not cover. Observational only.
  * A **`LoaderCreatedListener`** logs when the target constructs a `DexClassLoader` or `PathClassLoader`. Observational only.
  * An **`ApplicationThreadBackend` listener** observes lifecycle binder calls. It always returns `true` (never blocks), because blocking would change the target's behavior in a way the target notices — which is exactly the signal XStealth exists to avoid.

The listeners are registered once per process. They exist so the diagnostics in the detail sheet have data, and so a user debugging a detection issue can see whether the target is probing the framework's API surface.

### What XStealth hides

A summary of what's covered across the three layers:

  * Developer Options / ADB settings reads (Java + subprocess + native exec)
  * Cached detection values in the target's own `SharedPreferences` (opt-in, allow-list, 8.4)
  * Shizuku and ShizuPosed package presence (PackageManager)
  * Running processes (ActivityManager)
  * `/proc` entries (native /proc scrubbing + bridge enumeration)
  * `ro.debuggable`, `ro.secure`, `ro.boot.verifiedbootstate` (SystemProperties)
  * `Build.TAGS`, `Build.TYPE` (Build fields)
  * `shizuposed.*` system properties (property scrub)
  * ShizuPosed's own thread names (ThreadNameScrub)
  * Loaded-library enumeration (bridge)
  * File-descriptor enumeration (bridge)
  * Reflection walks on shim classes (ApiProtectionCheck)
  * Method-count fingerprinting (HookEngine baselines)

### What XStealth doesn't hide

Hardware attestation (Play Integrity `MEETS_STRONG_INTEGRITY`). Root-empowered inspection. Server-side cross-reference. Native reads of the settings database that bypass the exec family entirely. Method-list reconstruction. Exotic reflection via `Unsafe` or JNI-level private methods. Kernel-level monitors. Signals unrelated to ShizuPosed: keyboard, accessibility services, overlay permissions, bootloader state, custom ROM, screen recorders.

A banking app that shows a warning may be failing on any of these, not just the ones XStealth hides.

### XStealth's practical ceiling

XStealth raises the bar significantly — the common detection paths are covered across three layers — but it cannot make a ShizuPosed process genuinely indistinguishable from a normal app. The kernel's task struct (real cmdline, real parent pid), the dynamic linker's internal state, cross-process observation by a privileged reader, and hardware attestation are all beyond the reach of a userspace library. No non-root approach can close those.

---

## The subprocess gap

A common detection pattern is to bypass the Java settings API entirely and shell out:

    Runtime.getRuntime().exec(new String[]{
        "sh", "-c",
        "cat /data/system/users/0/settings_global.xml"});

XStealth closes this in two layers:

**Java layer — `SettingsFileCheck`.** Hooks `Runtime.exec` and `ProcessBuilder.start()`. If a command references a watched path, it returns a synthetic `Process` serving scrubbed XML. No subprocess spawns.

**Native layer — `libxstealth.so` exec interposers.** Hooks `execve`, `posix_spawn`, `posix_spawnp`, `popen`, and `system`. This catches callers that go through JNI instead of the Java API.

Both layers use the same narrow match rule: only commands that reference a watched path or a watched key alongside a file-reading verb are affected. `logcat -d`, `getprop`, and other unrelated shell-outs pass through unchanged.

**Still not covered:** native `execve` called before the `.so` is loaded, or from a process that loaded its own copy of libc. That's a load-order limitation, not a design gap.

---

## Socket shim — opt-in, off by default

Some detection kits talk to a privileged daemon over a unix domain `LocalSocket` instead of through Binder. To intercept that traffic, XStealth includes `SocketCheck`.

`SocketCheck` is **off by default** and installs **no hooks** unless a specific daemon is configured. Here's why:

  * `LocalSocket` is used by crash reporters, analytics SDKs, media pipelines, and custom app IPC. Blanket-hooking it breaks apps.
  * Intercepting a daemon's traffic requires knowing that daemon's wire protocol. The protocol isn't guessable; it has to be reversed.
  * Without a protocol implementation, interception is a no-op that costs CPU and adds risk.

This is infrastructure, not a working feature.

---

## API surface — version 96

ShizuPosed reports Xposed API version **96** (LSPosed generation 93, plus fork revisions 94, 95, and 96).

  * **93** — LSPosed base. `IXUnhook`, `isModuleActive` and `isModuleEnabled` with and without arguments, provider-backed state queries.
  * **94** — `hookAllMethods` returns `Set<XC_MethodHook.Unhook>`.
  * **95** — `hookAllConstructors` returns the same.
  * **96** — `MethodHookParam.isReturnEarly()` exposed.

Modules that guard on 97 or higher correctly believe ShizuPosed doesn't support those fork revisions.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

  1. `assets/xposed_init` — the canonical marker, contains the entry class.
  2. `assets/xposed_module` — an older convention, same content.
  3. `META-INF/xposed/module.prop` — EdXposed-era, key=value format.
  4. `assets/native_init` — a weak signal. The module ships native hooks and may have no Java entry point.

Once detected, the descriptor and dex go to the shell side. `XposedHook` reads the module JSON, checks the target against the module's scope, and loads the dex through `DexLoadingBridge`.

Built-in modules skip the dex load. The marker file is the source of truth for activation state.

### Recommended scope

Modules can declare which apps they're _intended_ for by shipping an `assets/scope.list` file inside the APK. ShizuPosed reads that file and surfaces the packages as **recommended**: a chip in the scope editor that selects them, a badge on their rows, and a count on the module's row.

---

## Activation state — how "Activated" actually works

Two distinct questions. "Am I enabled?" comes from `XposedBridge.isModuleEnabled(pkg)`, sourced from the manager's preference store. "Am I active?" comes from `XposedBridge.isModuleActive(pkg)`, sourced from the shell-side marker files.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per hooked target to `<shell-base>/hooked/<pkg>.json` after installing hooks. The manager mirrors the markers into its own files directory. `ProcessMonitor` runs one compound shell command every five seconds and writes each marker into `<manager files>/.markers/<pkg>.json`. `ModuleStatusProvider` reads from that mirror.

  * **Queries are local file reads.** No Shizuku in the query path.
  * **Activation state survives Shizuku outages.**
  * **The shell cost is bounded.**

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

Without it, the query returns null regardless of the provider being exported.

### Modules that check their own activation state

Some modules don't query the framework's provider. They hook one of their own UI methods and use the hook's presence as the activation signal. The pattern is:

    if (MODULE_PACKAGE.equals(lpparam.packageName)) {
        XposedHelpers.findAndHookMethod(
            MainActivity.class.getName(),
            lpparam.classLoader,
            "isXposedEnabled",
            XC_MethodReplacement.returnConstant(true));
        return;
    }

For that hook to install, the module's own UI process needs hooks. Under ShizuPosed that means launching the module's UI through ShizuPosed.

**How to do it:**

  * Long-press the module's row in the Modules tab.
  * Or tap "Open module app" in the detail sheet.

Either route opens the module's UI through ShizuPosed. The self-hook installs. The UI shows "Enabled."

If the user launches the module's UI from the launcher instead, the hook doesn't install and the UI shows the original value — usually "Disabled." This is a consequence of the injection model, not a bug.

### Modules that don't self-hook

Many modules don't use the self-hook pattern. Some are pure system-hook modules — CorePatch, for example, only does work when `packageName == "android"` (i.e. inside `system_server`). For those:

  * Opening the module's UI does not change the module's activation state
  * The UI may be a plain settings screen with no Xposed indicator
  * The module's functionality lives wherever its `handleLoadPackage` actually fires, which may be nowhere under ShizuPosed

This is a module property, not a ShizuPosed bug. Those modules require LSPosed.

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

### Using the lifecycle bridges (8.6)

A module that needs to act on a class *before* its static initializer runs:

    ClassLoadingBridge.registerClassLoadListener((name, loader) -> {
        if ("com.example.target.SecretDetector".equals(name)) {
            XposedHelpers.findAndHookMethod(name, loader, "check", ...);
        }
    });

A module that needs to reach code loaded at runtime through a `DexClassLoader`:

    ClassLoadingBridge.registerLoaderListener(loader -> {
        XposedHelpers.findAndHookMethod(
            "com.example.plugin.Entry", loader, "run", ...);
    });

A module that needs to intercept a service before it is created:

    ApplicationThreadBackend.registerListener((methodName, args) -> {
        if ("scheduleCreateService".equals(methodName)) {
            ServiceInfo info = (ServiceInfo) args[1];
            if ("com.example.target.SecretService".equals(info.name)) {
                XposedHelpers.findAndHookMethod(
                    info.name, targetLoader, "onCreate", ...);
            }
        }
        return true;   // never block; blocking changes the target's behavior
    });

Both bridges are installed by `XposedHook` during bootstrap; the module just registers listeners.

### Reporting state in a module UI

    boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
    boolean active  = XposedBridge.isModuleActive(getPackageName());
    String[] scope  = XposedBridge.getModuleScope(getPackageName());

For the module UI to reach the provider on Android 11+, the module's own `AndroidManifest.xml` needs the `<queries>` declaration above.

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

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is **Shizuku-Next** by rushiranpise at `github.com/rushiranpise/Shizuku-Next`. About 200 MB free storage. No root required.

For building the app: JDK 21 (with a Gradle toolchain targeting Java 21), Gradle 9.8, Android SDK 37. Native libraries rebuild with `clang` alone on Termux; no NDK is required if you're building on-device.

Shizuku-Next continues the fork by thedjchi (whose maintenance is paused) and adds an in-app shell, an app-ops manager, batch permission management, and — relevant here — an "ADB without Developer options" switch that turns the Developer options flag off at the settings level. That switch is complementary to XStealth: it changes the state, where XStealth changes the reads.

Upstream Shizuku works. Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (Shizuku-Next recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Install the ShizuPosed Manager APK:

    adb install -r ShizuPosed-R-8.6.apk

Or just tap the APK to install it. ADB is not required for the manager itself — only for starting Shizuku.

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to. Then tap **Launch App under ShizuPosed** and pick a scoped app.

Activation isn't retroactive. A module's UI will show "Activated" only after at least one scoped app has been launched through ShizuPosed.

---

## The manager

Five tabs.

**Home** shows the status card (Activated with a green check, or Not Activated with a red cross), the counter tiles, and the Framework Info card.

**Modules** lists installed modules with XStealth always at the top. Four gestures on each row: tap body for scope, tap switch for enable/disable, tap icon for detail sheet, long-press to open the module's UI through ShizuPosed.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has the runtime toggles, scan interval and hook delay sliders, the Maintenance section (clear logs, clean all caches), and config export.

### XStealth detail sheet

The XStealth detail sheet is where every XStealth toggle lives. Sixteen toggles, organized by layer:

**Master toggles**
  * **Enable XStealth** — the master switch
  * **Enable XStealth Next** — the aggressive native engine
  * **Use bridge** — the enumeration-hiding library

**Settings-based checks**
  * **Hide Developer Options**
  * **Hide ADB enabled**

**System properties and Build fields**
  * **Hide system properties** — sanitizes `SystemProperties` reads
  * **Sanitize Build fields** — sets `Build.TAGS`, `Build.TYPE`, etc.

**Framework properties and threads**
  * **Scrub framework properties** — removes `shizuposed.*` from `System.getProperties()`
  * **Rename framework threads** — renames ShizuPosed's own threads

**Package and process**
  * **Hide Shizuku package**
  * **Hide ShizuPosed package**
  * **Hide running processes**
  * **Hide /proc entries**

**API protection and dex**
  * **Protect Xposed API calls** — reflection guards, plus the bridge listeners (8.6)
  * **Optimize module dex** — dex2oat pass on module dex files

**Cached detection values (8.4)**
  * **Hide cached detection values** — off by default. When on, `CachedValueCheck` hooks `SharedPreferences` reads for the per-app allow-list below.
  * **Cached value keys** — per-app list of exact `SharedPreferences` key names. The "Test keys" screen reads a target's `shared_prefs` via Shizuku so the user can see which keys exist.

The status line at the top of the sheet summarizes which layers are armed (e.g., `Active (Next + Bridge) — running in at least one target`) and whether XStealth is currently active in any launched app.

---

## Building

### The app

    export ANDROID_HOME=$HOME/Android/Sdk
    git clone <repo>
    cd ShizuPosed
    ./gradlew clean :app:assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

The build uses Gradle 9.8, JDK 21 (via toolchain), and Android SDK 37 with compileSdk 37.

### The native libraries

On Termux, from the source directory:

    pkg install clang binutils

    clang -shared -fPIC -O2 -Wall -Wextra \
        -o libxstealth.so \
        libxstealth.c \
        -llog -ldl -lpthread

    clang -shared -fPIC -O2 -Wall -Wextra \
        -o libxstealth_next.so \
        libxstealth_next.c \
        -llog -ldl -lpthread

    clang -shared -fPIC -O2 -Wall -Wextra \
        -o libxstealth_bridge.so \
        libxstealth_bridge.c \
        -llog -ldl -lpthread

No NDK, no target triple, no cross-compilation for the on-device build. If any header is missing:

    pkg install ndk-sysroot

Deploy:

    adb push libxstealth.so libxstealth_next.so libxstealth_bridge.so /data/local/tmp/
    adb shell "cp /data/local/tmp/libxstealth*.so \
        /data/user/0/com.android.shell/files/libs/"
    adb shell "chmod 644 \
        /data/user/0/com.android.shell/files/libs/libxstealth*.so"

### The build checks

The `app/build.gradle` includes six verification tasks that check for the native libraries before the jniLibs merge:

  * `checkNativeShim` — required. `libshizuposed.so` must be present and non-trivial. The build fails if it's missing.
  * `checkCallSite` — optional. Warns if `libcallsite.so` is missing; the CallSite backend reports unavailable.
  * `checkAmiru` — optional. Warns if `libamiru.so` is missing; the Amiru backend reports unavailable.
  * `checkXStealth` — optional. Warns if `libxstealth.so` is missing.
  * `checkXStealthNext` — optional. Warns if `libxstealth_next.so` is missing.
  * `checkXStealthBridge` — optional. Warns if `libxstealth_bridge.so` is missing.

Skip all six with:

    ./gradlew assembleRelease -PskipNativeCheck=true

When skipped, the corresponding backends are still attempted at runtime; they just report unavailable if the libraries aren't present.

### Configuration cache

The build is configuration-cache-compatible with Gradle 9.8. All task actions capture their inputs at configuration time and reference only local variables in `doLast` blocks — no `project`, `configurations`, or other Gradle script objects.

If you need to disable the configuration cache for debugging:

    ./gradlew assembleRelease --no-configuration-cache

Or set `org.gradle.configuration-cache=false` in `gradle.properties`.

---

## Reading the launch log

Every routed launch writes stderr and stdout from `app_process` to `<shell-base>/launch.log`:

    cat /data/user/0/com.android.shell/files/.syscall_cache/launch.log

This is where bootstrap failures surface:

  * Missing class → `NoClassDefFoundError: com.shizuposed.manager.core.XposedHook`
  * Dex path wrong → `Cannot open self ...`
  * `CLASSPATH` set → `Native registration unable to find class 'com/android/internal/os/RuntimeInit'; aborting...`
  * Missing dependency → Java stack trace from `XposedHook.main`
  * Process exited before producing output → file is empty

The manager also copies the tail of this log to the Logs tab when a launch is diagnosed as failing.

The spawn-tool probe result is logged once at startup:

    Spawn tools: setsid=true nohup=true disown=true

XStealth's native preload happens first, before anything else in the process:

    Preloading XStealth native engines from: /data/user/0/com.android.shell/files/libs
    Preloaded libxstealth.so
    Preloaded libxstealth_next.so
    Preloaded libxstealth_bridge.so

If any of those three `Preloaded` lines is missing, that library failed to load on this device — the corresponding layer is not running.

The two lifecycle bridges log their own install:

    ClassLoadingBridge: loadClass hook installed
    ClassLoadingBridge: DexClassLoader constructor hooked
    ClassLoadingBridge: ClassLoadingBridge installed
    ClassLoadingBridge: preloaded com.example.target.MyReceiver
    ClassLoadingBridge: preloaded com.example.target.MyProvider
    ClassLoadingBridge: preloadComponentClasses: loaded N component class(es) for com.example.target

    ApplicationThreadBackend: scheduleCreateService hooked
    ApplicationThreadBackend: ApplicationThreadBackend installed

Each XStealth layer logs its own status:

    XStealth native: initialized (5/5 key symbols resolved, 15 total)
    install_all_patches: trying strategy stub-inline
    patch_stub(openat): patched at 0x... (resolved=openat, shape=mov-w8)
    install_all_patches: strategy stub-inline applied 6 patch(es)
    XStealth Next: active (strategy=stub-inline)
    XStealthBridge: initialized (6/6 symbols resolved)
    XStealthBridge: active=1

If a layer reports `active=0` or `0 patches`, the reason is logged alongside. That's the difference between "silently doing nothing" and "telling you why."

---

## Changelog

### 8.7

Add ShizukuBinderWatcher.

### 8.6

**NEW — `ApplicationThreadBackend`: binder-level lifecycle hooks**

The binder interface `ActivityThread` exposes to `system_server` (its `mAppThread` field) carries every lifecycle event `ActivityManagerService` schedules — activity launches, service creates, receiver dispatches — before `ActivityThread` or `Instrumentation` sees them. 8.6 hooks it.

Methods hooked: `scheduleCreateService`, `scheduleBindService`, `scheduleUnbindService`, `scheduleStopService`, `scheduleServiceArgs`, `scheduleTransaction` (Android 9+). Each hook is fail-open; if a signature differs on a ROM, that method is skipped and the others still install.

**This closes the `Service.onCreate` gap.** `scheduleCreateService` is the binder method AMS calls to tell the target process to create a service. A module can now hook a `Service`'s `onCreate` before the `Service` is instantiated. The README previously described this as structurally impossible; it wasn't — it just wasn't implemented. It is now.

It also gives modules a place to observe or rewrite binder-level lifecycle events that `Instrumentation` never sees, because `Instrumentation` is downstream of the binder interface.

**NEW — `ClassLoadingBridge`: class-load observation, dynamic loader discovery, component pre-load**

Three capabilities in one bridge, all fail-open:

  * **`ClassLoader.loadClass` hook.** Listeners fire *before* a class is defined, so a module can install hooks on a class before its static initializer runs. Without this, `<clinit>` executes first, and any irreversible work it does has already happened by the time a hook could be placed.
  * **`DexClassLoader` / `PathClassLoader` constructor hook.** When a target constructs a new loader at runtime (plugin systems, split APKs, hot-reload frameworks), a listener is notified with the new loader so the module can hook classes inside it. Before this, dynamic code was entirely invisible.
  * **Component pre-load.** Asks `PackageManager` for the target's receivers and providers, then forces each class to load. This makes a module's hooks on those classes live before the first broadcast arrives or the first `ContentProvider.onCreate` runs. Before this, a receiver hook could miss the first broadcast if the class hadn't been loaded for another reason.

The pre-load path uses `PackageManager`, not a hand-parsed `AndroidManifest.xml`. An early implementation read the manifest through hidden `AssetManager` reflection, which varied by OEM ROM and crashed on some. `PackageManager` is a public API that returns the same data and works everywhere; it's slower on the first call because the platform parses the manifest internally, but reliable.

**IMPROVED — `preloadXStealthNative()`: native engines load at process boot**

The XStealth native libraries (`libxstealth.so`, `libxstealth_next.so`, `libxstealth_bridge.so`) now load at the top of `XposedHook.main()`, before argument parsing, before `HookEngine` setup, before module dex load.

Previously they loaded inside `XStealthModule.handleLoadPackage`, which runs after bootstrap has already started resolving `ApplicationInfo` and hooking into `ActivityThread`. That left a window of roughly 100–400ms where the process ran with an unhooked libc, and a target whose early detection reads through libc during that window saw the real values.

With the preload, the window shrinks to a few milliseconds. The launch log now shows the three `Preloaded` lines at the very top, so a failed library load is the first thing you see rather than something buried in the log.

**IMPROVED — XStealth uses both bridges for diagnostics**

When `Protect Xposed API calls` is on, XStealth registers listeners with both bridges:

  * A `ClassLoadListener` logs when the target tries to load a class in the `de.robv.android.xposed.*` namespace. `ApiProtectionCheck` already refuses `Class.forName` for that namespace; this covers the `loadClass` path.
  * A `LoaderCreatedListener` logs dynamic loader construction.
  * An `ApplicationThreadBackend` listener observes lifecycle binder calls.

All three are **observational only**. They never block a class load or a binder call, because doing so would be a behavior the target notices — the opposite of what XStealth is for.

**NOTES**

  * `Service.onCreate` is now hookable. Cross-process `Service.onCreate` — a service in a process ShizuPosed did not launch — is still out of reach, because the `ApplicationThread` interface only exists in the process ShizuPosed booted.
  * Receiver and provider pre-load runs in bootstrap mode and in post-application mode. In post-application mode the Application is already running, so classes that were loaded before `handleLoadPackage` ran are not covered; the pre-load still helps for classes that haven't been touched yet.
  * The `ApplicationThreadBackend` hooks install on the binder interface `ActivityThread.mAppThread`. If a future Android version renames that field or moves the interface, the backend logs and installs no hooks; the framework continues without it.

### 8.4

**NEW — `CachedValueCheck`: opt-in SharedPreferences hiding**

A target app that cached a detection result in its own `SharedPreferences` before XStealth was in play could keep reading that stale value even after XStealth sanitized the live reads. 8.4 closes that gap with a new check that hooks `SharedPreferences` reads, gated behind an allow-list the user controls.

Three properties make it safe:

  * **Opt-in.** Off by default. Nothing is hooked until the user turns it on.
  * **Allow-list.** Only keys the user explicitly names are affected. The list is per-app, because different apps cache different keys. Every other key passes through unchanged.
  * **Fail-open.** Any error, any unexpected type, any shape the code doesn't recognize, returns the original value and logs the failure.

The value returned for a matched key comes from the same canonical source the live-read checks use (`DevOptionsCheck.hidesKey`), so a detector that cross-checks a live read and a cached read sees the same answer. That consistency is the entire point — a mismatch between the two is itself a detection signal.

`getAll()` is deliberately not hooked. Rewriting entries in the returned map would break callers that use it for backup, migration, or debug dumps, and the false-positive cost is not worth the coverage gain.

**IMPROVED — `AdbCheck` and `DevOptionsCheck` now honor their toggles**

Both checks previously installed unconditionally, ignoring the `hideAdb` and `hideDevOptions` settings. Turning the toggle off in the detail sheet had no effect. Both now accept `XStealthConfig` and respect the toggle the same way `PackageCheck` already did.

**IMPROVED — one canonical hidden-key list**

`AdbCheck` had its own `HIDDEN_KEYS` array that duplicated `DevOptionsCheck`'s. Both now delegate to `DevOptionsCheck.hidesKey`, which is package-visible and is the single source of truth. This is what lets `CachedValueCheck` return a value consistent with the live reads — there's only one list to drift from, and it can't.

**FIXED — `DevOptionsCheck` selection handling**

The `ContentResolver.query` hook checked `selection.contains("name")`, which is true for every settings query, making the "the caller asked for a different key" branch dead code. The check now inspects the bound selection args (`selectionArgs`), which is where the key name actually lives in a `name=?` query. A query that filters on a different key now correctly returns an empty cursor instead of a fake row for a key the caller never asked for.

**IMPROVED — `PackageCheck` returns platform-consistent values**

Several `PackageManager` lookups were throwing `NameNotFoundException` or returning values the platform wouldn't:

  * **`getPackageUid`** now returns `-1` for a hidden package. The platform returns `-1` for a package it cannot find; throwing is louder than the platform's own behavior, and a caller that handles the two cases differently sees the wrong thing.
  * **`getInstallerPackageName`** now returns `null` instead of spoofing Play Store. `null` is what the platform returns for a sideloaded app, which is exactly what Shizuku looks like. Play Store is a positive claim the platform would not have made.
  * **`getInstallSourceInfo`** constructs an `InstallSourceInfo` with `null` installer fields for the same reason.
  * **`filterModules`** tries several candidate field names before giving up, so a `ModuleInfo` field rename on an OEM ROM doesn't silently let the entry through. It still fails open if none match.

These changes don't alter *what* is hidden — only the *manner*, so it matches what the platform would report for a genuinely absent package. A detector comparing "Shizuku installed" against "what a normal missing package looks like" should now see the same answers from both paths.

**NOTES**

  * `CachedValueCheck` v1 hooks reads only. The write path and a one-time cache-correction pass are planned for 8.4.1. Until then, the first launch under XStealth after enabling the check may see a stale value for one cycle; the next time the app writes the key, the sanitized value replaces it.
  * The manager-side UI for `CachedValueCheck` (toggle + per-app key editor) is not in this release. The shell-side check is complete and will activate as soon as `hideCachedDetectionValues` and `cachedValueKeys` are present in `xstealth.json`. A "Test keys" screen that reads a target's `shared_prefs` via Shizuku is planned for 8.4.1.
  * An existing `xstealth.json` without the new fields parses cleanly: `hideCachedDetectionValues` defaults to `false` and `cachedValueKeys` to an empty map, so nothing changes for users who don't enable the feature.

### 8.3

**NEW — Hiding the real parent PID (`getppid()`)**

When an app launches normally, `getppid()` tells it its parent is `1` (init). But when ShizuPosed launches `app_process`, the parent is the shell instead, because the process came from the shell rather than from zygote. That mismatch is a dead giveaway that the process wasn't started the usual way — any detector can call `getppid()` and figure out something's off.

8.3 fixes this by intercepting `getppid()` and handing back `1` to untrusted callers. ShizuPosed's own code and the framework's cleaned-up threads still get the real number, so nothing inside the framework breaks.

**NEW — Cleaning the `PPid` line in `/proc/self/status`**

The same tell shows up in `/proc/self/status`. The `PPid:` line there reports the shell's PID instead of init's. In 8.3, when an untrusted caller reads that file, the line gets rewritten to `PPid:\t1`.

**NEW — Blocking sysfs USB state reads**

Reads of `/sys/class/android_usb/*` and `/config/usb_gadget/*` now come back as `ENOENT`. On a device that isn't plugged into anything, those files usually say `not attached` or something similar. Returning `ENOENT` mimics a device where the USB gadget was never registered at all — exactly what a normal app sees when you're not connected to a computer.

**NEW — Scrubbing `/proc/net/unix`**

The ADB socket appears in `/proc/net/unix` as a line containing `adb`. Detection libraries read this file to see if ADB is running. 8.3 strips out any lines containing `adb`, so a parser still sees the same number of lines — just no ADB socket path.

**IMPROVED — counters and diagnostics**

`nativeDescribe()` now reports `sysfsBlocked`, `getppidIntercepted`, and their counts alongside the existing ones. New JNI accessors expose the same values on the Java side.

**WHAT THIS CLOSES**

The `getppid()` and `PPid` signals were two of the strongest non-file clues that a process wasn't forked normally. Shutting them down removes a whole category of detection that doesn't rely on reading the settings database or any system property.

**WHAT THIS DOESN'T CLOSE**

Binder calls to `IUsbManager`, netlink broadcasts from the USB daemon, and runtime ART structure integrity are still out of reach. A detector using any of those can still see the truth. See "Known limitations" for the full list.

**KNOWN INCOMPATIBILITY — BioCatch-based banking apps**

8.3 also adds a note to "Known limitations" about BioCatch, the fraud-detection SDK used by HSBC Philippines and other regional banks. BioCatch's native library reads kernel interfaces that userspace frameworks can't hook — Binder service state, ART method entry points, and cross-referenced behavioral signals. Devices with Developer Options or USB debugging turned on will get flagged by BioCatch-based apps no matter what XStealth does. The documentation explains why and what the workaround is.

### 8.2

**IMPROVED — system property scrubbing is now complete**

The 7.9 release added `SystemPropertiesCheck`, which hooks `android.os.SystemProperties.get` for a small list of build-state properties. That list turned out to be incomplete: it didn't cover the runtime USB properties that detectors actually read to check whether USB debugging is enabled.

The `getprop` output on a device with USB debugging on shows:

    [sys.usb.adb.disabled]: [0]
    [sys.usb.config]: [midi,adb]
    [sys.usb.state]: [midi,adb]

Any detector reading `sys.usb.config` sees `adb` in the value and knows USB debugging is on — regardless of what the settings database says. The old watch list covered `persist.sys.usb.config` and `service.adb.root`, but not the runtime properties above.

8.2 extends the watch list to cover:

  * `sys.usb.adb.disabled`, `sys.usb.config`, `sys.usb.state`
  * `sys.usb.configfs`, `sys.usb.controller`
  * The `persist.sys.usb.*` family, including OEM-specific variants
  * `service.adb.tcp.port`, `persist.adb.tcp.port`
  * `sys.oem_unlock_allowed`, `ro.oem_unlock_supported`
  * `ro.bootmode`, `ro.boot.mode`, `ro.boot.dm_verity`

All reads of these keys return sanitized values that match a stock device with developer options off and USB in MTP mode.

**NEW — `__system_property_get` interposition**

The Java-layer `SystemPropertiesCheck` only covers calls that go through `android.os.SystemProperties.get`. A native library that reads properties via `__system_property_get` bypasses it entirely.

8.2 adds a native interposer for `__system_property_get` in `libxstealth.so`. It returns the same sanitized values as the Java layer for the same watch list. A native caller reading `sys.usb.config` now sees `"mtp"` instead of `"midi,adb"`.

The watch list on the native side is kept in sync with `SystemPropertiesCheck.WATCHED` on the Java side, so both paths answer consistently.

**NEW — `/data/property/persistent_properties` blocking**

The persistent properties file is the backing store for `persist.*` keys. Reading it directly bypasses `__system_property_get` entirely — a native library that opens the file and parses it sees the raw values.

8.2 adds the path to the blocked-path list in `libxstealth.so`. Reads of the file return `ENOENT`, which matches what a stock app sees: SELinux denies it for non-system UIDs, so a stock app can't read it either. Blocking it is safe because only `init` and the framework read the file, and neither runs in a spawned `app_process`.

**IMPROVED — extended blocked-path handling in `read` interposer**

The `read` interposer now serves a scrubbed buffer for reads of the persistent-properties file, in addition to the settings XML files. This is a belt-and-braces path: the `open` interposers block the open entirely, but if a caller reaches the read stage by some other route, the buffer is still scrubbed.

**FIXED — duplicate native declarations in `XStealthNative` and `XStealthNativeNext`**

The Java classes declared several of the pread, libart, and mmap natives twice — once near the top and once near the bottom. Java rejects duplicate method declarations, so the files didn't compile. 8.2 consolidates all native declarations into a single block at the bottom of each class, and adds `sAvailable` guards on every native call so a failed library load doesn't produce `UnsatisfiedLinkError`.

**IMPROVED — `XStealthNativeNext` API surface**

`XStealthNativeNext` now exposes `getStrategy()`, `getPatchCount()`, and `isEffectivelyActive()`, matching the JNI methods the native side has exposed since 0.7.0. These let callers check which patching strategy succeeded and whether the engine is actually doing work.

**WHAT THIS CLOSES**

A detector reading USB/ADB state through any of these paths now sees sanitized values:

  * `android.os.SystemProperties.get` from Java
  * `__system_property_get` from native code
  * `open` / `read` of `/data/property/persistent_properties`
  * `/data/system/users/0/settings_*.xml` (from 7.9)

**WHAT THIS DOESN'T CLOSE**

The property shared-memory area. A native library that reads the `__system_property_area__` mapping directly — bypassing the `__system_property_get` API and parsing the raw table — still sees the real values. Closing this would require writing to the property area (denied by SELinux for non-system UIDs) or remapping it (which crashes the process). Neither is achievable in userspace.

Play Integrity's hardware-backed attestation also remains out of reach. Apps that use `MEETS_STRONG_INTEGRITY` will detect the device state regardless of any property or file scrub.

Both gaps are documented in "Known limitations."

### 8.1

Bug Fixes.

### 8.0

**IMPROVED — the compatibility layer now figures things out at runtime**

`AndroidCompat`, `HiddenApiBypass`, and `ReflectionUnsafe` used to make decisions based on hardcoded SDK numbers. Now they check what the runtime actually supports when the class loads, and they act on what they find.

Here's what that looks like in practice:

  * **`AndroidCompat`** reads `Build.VERSION.SDK_INT`, then runs a series of capability checks for:
    - Whether `VMRuntime.setHiddenApiExemptions` exists
    - Whether `AccessibleObject` has an override/flag field (and what it's actually called)
    - Whether `Method.artMethod` exists
    - `ArtMethod` accessor symbols
    - The interpreter symbol anchor
    - The interpreter handler table
    - Whether `sun.misc.Unsafe` or `jdk.internal.misc.Unsafe` is present
    - Whether `InMemoryDexClassLoader` is present

    The ART family assignment now comes from those checks when they give a clear answer. The SDK number is only used as a hint when the checks can't narrow things down enough — and the diagnostics report which path was taken (`[probed]` vs `[sdk-hint]`).

  * **`HiddenApiBypass`** now decides which strategies to use based on the check results instead of API ranges. If a future Android version brings back a mechanism, the bypass picks it up without any code changes here.

  * **`ReflectionUnsafe`** finds the `Unsafe` class, the singleton accessor, and every accessor method at runtime instead of hardcoding `sun.misc.Unsafe`, `theUnsafe`, and `getLong`. It reads the class name from `AndroidCompat`'s check, walks every declared static field and method to find the singleton, and matches accessors by name and return type rather than exact parameter lists.

**WHY THIS MATTERS**

The old version was correct for the Android releases it was written against. But every new Android release — and every OEM ROM that diverges from AOSP — was a potential mismatch. The new version:

  * Handles new Android versions that keep the previous layout (the checks match, the family stays the same, no rewrite needed).
  * Handles OEM ROMs that use a different layout than the SDK number would predict.
  * Handles pre-release builds where `SDK_INT` lags behind the actual layout.
  * Reports why it fell back to a version-based guess when the checks are inconclusive, so bug reports say exactly what the runtime was missing.

**NEW — diagnostics**

Every compatibility class now has a `describeFull()` method that reports every check result and every fallback decision. The XStealth detail sheet's Framework Info card pulls from this. Bug reports that include the output tell you at a glance what the device's runtime is actually exposing.

**IMPROVED — `ReflectionUnsafe` resilience**

The class now:

  * Reads the `Unsafe` class name from `AndroidCompat.UNSAFE_CLASS_NAME`, which is populated by the runtime check. If the class was renamed, the check finds the new name.
  * Discovers the singleton accessor by walking every declared static field and method whose type is the class itself. That matches `theUnsafe`, `THE_ONE`, `getUnsafe`, and any future equivalent without naming any of them.
  * Matches accessor methods by name and return type, with exact-signature matching as a first pass and a looser match as fallback.
  * Logs every check attempt so a failed initialization is diagnosable rather than silent.

**IMPROVED — `HiddenApiBypass` strategy gates**

Strategy 1 (`VMRuntime.setHiddenApiExemptions`), Strategy 2 (`setAccessible`), and Strategy 3 (flip the override flag) now check the probe results rather than `SDK >= 28` and `SDK <= 30`. On a future Android version that reintroduces either mechanism, the strategies run without changes here.

**UNCHANGED**

  * The public API of all three classes. Callers that used `AndroidCompat.isAtLeast(N)`, `HiddenApiBypass.forceAccessible(...)`, `ReflectionUnsafe.getLong(...)`, etc. continue to work unchanged.
  * The ART family constants (`ART_10_11`, `ART_12`, ..., `ART_16_PLUS`). Their values are the same; only the way they're assigned changed.
  * Everything in XStealth that isn't in the compat layer. The 7.9 features work the same.

### 7.9

**IMPROVED — XStealth coverage extended to the enumeration layer**

XStealth now covers the common detection paths across three layers: Java (API hooks), native (libc interposition and syscall patching), and bridge (native enumeration hiding). The new checks are:

  * **`SystemPropertiesCheck`** — hooks `android.os.SystemProperties.get/getBoolean/getInt/getLong` for a watched key list (`ro.debuggable`, `ro.secure`, `ro.boot.verifiedbootstate`, `ro.boot.flash.locked`, `ro.build.type`, `ro.build.tags`, etc.).
  * **`BuildCheck`** — sets `Build.TAGS`, `Build.TYPE`, `Build.HOST`, `Build.USER` to production defaults; guards reflection-based field reads.
  * **`PropertyScrubCheck`** — removes `shizuposed.*` entries from `System.getProperties()` and `System.getProperty(String)`.
  * **`ThreadNameScrub`** — renames ShizuPosed's own threads to `pool-N-thread-M` before any module loads.

**NEW — `libxstealth_bridge.so`**

The bridge library hides ShizuPosed from native enumeration paths:

  * `dl_iterate_phdr` — filters ShizuPosed libraries from the loaded-library walk
  * `/proc/self/fd` — filters file descriptors that point to ShizuPosed paths
  * `/proc/self/task` — filters ShizuPosed thread names
  * `getenv` — returns NULL for `shizuposed.*` environment variables
  * `readlink` — blocks fd-to-path resolutions that reveal ShizuPosed

The library is loaded independently from the primary and Next engines. It has its own toggle in the XStealth detail sheet ("Use bridge").

**IMPROVED — `/proc/self/environ` and `/proc/self/cmdline` scrubbing**

`libxstealth.so` now scrubs `/proc/self/environ` and `/proc/self/cmdline` reads. The cmdline scrub preserves the first NUL-terminated token (the process name, which framework code reads legitimately) and scrubs the rest, replacing the ShizuPosed flags and paths with spaces.

**IMPROVED — XStealth Next strategy ladder**

`libxstealth_next.so` now tries multiple patching strategies in order and accepts the first one that succeeds:

  1. **stub-inline** — per-symbol syscall stub patching (the original mechanism).
  2. **syscall-dispatch** — patches the shared `syscall()` dispatcher, catching every syscall regardless of stub shape. Survives bionic changes that break the per-stub classifier.
  3. **plt-got** — not implemented; logged as a placeholder.

The active strategy is exposed to the Java layer via `nativeGetStrategy()`, and each strategy logs its own attempt and success count. This is what makes the aggressive engine survivable across Android versions where bionic changes the stub layout.

**IMPROVED — diagnostics across all three libraries**

Every native library now logs:

  * API level on `JNI_OnLoad`
  * Symbol resolution counts
  * Patch counts (Next)
  * Which strategy succeeded (Next)
  * Whether the engine is "effectively active" (loaded AND did something)

`nativeIsEffectivelyActive()` is a new JNI method that returns true only if the library actually installed its checks. `nativeGetStrategy()` (Next), `nativeGetPatchCount()` (Next), `nativeGetResolvedCount()` (XStealth), and `nativeGetCoverage()` (bridge) expose the details.

**IMPROVED — thread name scrubbing and property scrubbing**

ShizuPosed's own threads are renamed before any module loads, closing the "enumerate threads for suspicious names" detection path. `shizuposed.*` system properties are removed from `System.getProperties()` reads.

**IMPROVED — XStealth detail sheet**

The sheet now has fifteen toggles organized by layer, with the "Use bridge" toggle, the four new hiding toggles, and a status line that summarizes which layers are armed.

**IMPROVED — build**

Gradle 9.8 with configuration cache. Six native library check tasks before the jniLibs merge. The `makeDex` task now handles AAR extraction without the deprecated `copy { }` DSL.

### 7.3

**FIXED — module auto-reopen after exit**

A module launched through "Open module app" would sometimes reopen itself when the user exited it. The cause was the launch-result timeout handler in the manager UI. The fix: cancel the timeout when the broadcast arrives, and remove the `fallbackToDirectLaunch` call from the timeout handler.

**IMPROVED — explicit-component `am start`**

Step 2 of the routed launch now prefers an explicit component (`am start -n package/class`) over an implicit launcher intent. Explicit starts avoid the implicit-intent replay behavior that could re-deliver the launcher intent after the activity exits.

**IMPROVED — `CLASSPATH` removed from the spawn command**

The spawn command previously set `CLASSPATH=<dexPath>` in addition to `-Djava.class.path`. On some ROMs this caused `app_process` to treat `CLASSPATH` as a boot-classpath override. The spawn now uses `unset CLASSPATH; unset BOOTCLASSPATH;` to force `app_process` to use its compiled-in defaults.

**IMPROVED — presence check**

The post-spawn process check now scans `/proc/*/cmdline` for the target package name instead of running `pidof <pkg>`, which gave false negatives during bootstrap.

### 7.2

**REMOVED — `IXposedHookCmdInit`**

The `IXposedHookCmdInit` interface has been removed entirely. It was declared in earlier versions for link compatibility, but its `initCmdProcess()` callback had no window to fire in under ShizuPosed's bootstrap-timing model. Modules that implement it will now fail to link; move any initialization into `handleLoadPackage`.

**NEW — spawn-tool fallback ladder**

The routed launch previously hardcoded `setsid nohup`. The spawn-tool probe and fallback ladder handle devices where `setsid` is unavailable.

**IMPROVED — absolute paths for spawn tools**

`/system/bin/sh`, `/system/bin/setsid`, `/system/bin/nohup`.

### 7.1

**FIXED — routed launch now opens the UI**

Two-step launch: boot the process with hooks, then `am start` the UI.

**NEW — `launch.log`**

Bootstrap failures are visible.

**IMPROVED — module push cache, dedicated launch executor.**

### 7.0

**NEW — subprocess hiding** (`SettingsFileCheck`, `libxstealth.so` exec interposers). **NEW — socket shim (opt-in).** **NEW — native library reconstruction.**

### Earlier

See the release notes on the Releases page.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. Cross-process hooks aren't possible — a component in a process ShizuPosed didn't launch has no hooks. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework. Modules that require zygote-wide timing won't work. Hooks only reach processes ShizuPosed launches.

**Zygote-timing APIs.** Modules that implement `IXposedHookZygoteInit` expect a callback that only fires under a zygote-based framework. ShizuPosed declares the interface for link compatibility but cannot call it.

`IXposedHookCmdInit` was removed in 7.2. Modules that implemented it will fail to link; move any initialization into `handleLoadPackage`.

**Service.onCreate.** Reachable in the process ShizuPosed launched, via the `ApplicationThread` binder interface (8.6). Not reachable for a service in a process ShizuPosed did not launch, because the `ApplicationThread` interface only exists in the process that owns the app's `ActivityThread`.

**Receiver and provider hooks.** Live from the moment bootstrap completes, because their classes are pre-loaded. A receiver or provider class that was already loaded before bootstrap (rare in the ShizuPosed model, since the process starts fresh) would have its hooks install at `handleLoadPackage` time rather than before.

**Dynamic loader hooks.** A `DexClassLoader` or `PathClassLoader` constructed before `ClassLoadingBridge` installs is not visible to the loader listener. In practice this doesn't happen, because the bridge installs during bootstrap, before the target's own code runs.

**Activation reporting in module UIs.** Module UIs that check their own activation state by hooking one of their own methods report the original value unless their UI is launched through ShizuPosed. Modules that query the status provider via `XposedModule.isModuleActive()` report correctly regardless.

**`unhook()` on most backends is a no-op.** Pine, Amiru, Native, and Instrumentation don't expose a reverse. CallSite, Proxy, and Noop do.

**XStealth's practical ceiling.** XStealth covers the common detection paths for Developer Options, ADB, package presence, running processes, `/proc` reads, `Runtime.exec` subprocess reads, system properties, Build fields, thread names, cached detection values (opt-in), and native enumeration. It doesn't cover hardware attestation, native reads of the settings database that bypass the exec family entirely, method-list reconstruction, exotic reflection, kernel-level watchers, or signals unrelated to ShizuPosed.

A banking app that shows a warning may be failing on any of these, not just the ones XStealth hides.

**Bridge layer caveats.** The bridge library filters `dl_iterate_phdr` process-wide, so framework components that use the API for legitimate reasons also see the filtered list. In practice those don't care about the specific libraries filtered, so the filtering is transparent — but it's worth knowing it's not target-specific. Similarly, `getenv` scrubbing only covers the API path; a caller reading the `environ` global directly still sees the variables (though the `/proc/self/environ` scrub in `libxstealth.so` covers the file path).

**Subprocess hiding has a load-order window.** The exec-family interposers in `libxstealth.so` only catch subprocess spawns that happen after the library is loaded. Since 8.6, the library loads at the top of `XposedHook.main()`, so the window is a few milliseconds at process start rather than a few hundred. Spawns in that window may slip through.

**Socket shim is inert by default.** `SocketCheck` installs no hooks unless a daemon is configured and a `Responder` is registered for it.

**Modules that require LSPosed.** Some modules (CorePatch, signature spoofers, system-level tweaks) do their work in `system_server`. ShizuPosed cannot load them there. Opening their UI under ShizuPosed may still work, but their actual functionality will not.

**Modules with no launchable UI.** Modules that ship no activity at all cannot have their UI opened because there is none. The launch reports success with a note; no window appears. This is correct behavior.

**Android 11+.** Module UIs that want to report Activated via the status provider must declare ShizuPosed's provider authority in their own `<queries>` block.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, JIT-inlined callers aren't invalidated, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**Native reconstruction caveat.** The reconstructed `libxstealth_next.c` handlers call the resolved real symbol directly rather than executing the saved stub prologue as a trampoline. Behaviorally equivalent for path hiding, but does not chain through pre-existing patches on the same stub.

**Spawn-tool probe depends on the shell.** The probe uses `command -v` and falls back to `type`. On a shell that implements neither — essentially nonexistent on Android — `setsid` and `nohup` report as unavailable and the launch falls back to a bare spawn.

**Module-side auto-reopen.** The manager-side auto-reopen was fixed in 7.3. A module can still reopen itself from its own code if its hooks call `startActivity` on a lifecycle event. This is a module property, not a ShizuPosed bug. Check the manager log: if no `Launching <pkg> under ShizuPosed` line appears when the reopen happens, the module is re-opening itself.

**Next strategy ladder gaps.** The `stub-inline` strategy survives stub-shape changes. The `syscall-dispatch` fallback catches syscalls when stubs can't be classified. If both fail — bionic changes the dispatch mechanism entirely, or W^X blocks the writes — the engine logs `no strategy succeeded` and stays inert. The `plt-got` strategy that would cover some of those cases is not implemented.

**Compatibility layer checks can come up empty.** `AndroidCompat` works out the ART family from runtime checks when it can, and falls back to the SDK number when it can't. That fallback is right for stock AOSP, but it can be wrong on OEM ROMs whose layout doesn't line up with the SDK number. The diagnostic reports which path was taken (`[probed]` vs `[sdk-hint]`), so a caller that needs the exact family can decide whether to trust the answer. `ReflectionUnsafe` and `HiddenApiBypass` come with the same caveat: they're more resilient than hardcoded version gates, but they don't know everything.

**Banking apps that ship native runtime-integrity SDKs (BioCatch and friends).** Some banking apps come bundled with third-party fraud-detection SDKs that check the app's own runtime state, not just your device settings. The one you'll run into most often is **BioCatch** — HSBC Philippines and several other regional banks use it. BioCatch loads a native library that:

  * Pokes around `/sys/class/android_usb/*`, `/proc/net/unix`, and other kernel interfaces to see if USB debugging is on.
  * Talks to Binder services directly — `IUsbManager.getCurrentFunctions()`, `IPackageManager` lookups — skipping the Java-layer PackageManager entirely.
  * Inspects ART method entry points to catch hooking.
  * Feeds all of that into a fraud model that also weighs behavioral signals like tap timing and swipe pressure.

XStealth shuts down the file and property channels BioCatch reads through libc: system properties, `/proc` entries, sysfs USB files, `/proc/net/unix`, `getppid()`, and the settings XML. What it can't shut down are the Binder channel and the ART-integrity channel. Those are structural — Binder calls go through `/dev/binder` ioctl, and the ART method entry point *is* the hook, so there's nothing to hide.

Bottom line: if you have Developer Options or USB debugging enabled, BioCatch-based apps will flag you no matter what XStealth does. The only workaround that actually holds up:

  1. Turn off Developer Options completely.
  2. Turn off USB debugging.
  3. Reboot.
  4. Open the banking app.

If it still flags you after all that, the detection isn't coming from local device state. It's coming from a hardware attestation signal (Play Integrity `MEETS_STRONG_INTEGRITY`), a server-side fingerprint, or a check on where the app was installed from — none of which any userspace framework can touch.

This isn't a ShizuPosed bug. It's just how these SDKs are built. LSPosed with Shamiko plus a hardware-spoofing module can cover some of it, but only with root. Without root, no framework is going to beat BioCatch's runtime-integrity checks.

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
