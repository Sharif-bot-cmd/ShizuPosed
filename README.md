# ShizuPosed

**An Xposed-compatible hook framework that doesn't need root — it runs on `app_process`.**

ShizuPosed runs Xposed-API modules inside target apps without root. No bootloader unlock, no system partition changes. Shizuku calls `app_process` as the shell UID, a Java runtime spins up, and hooks get installed through a dispatcher that tries several backends until one sticks. Modules built for LSPosed and classic Xposed keep working, and modules built against libxposed API 101 (the modern `io.github.libxposed.*` surface) are supported as well.

---

## Two builds

ShizuPosed ships as two APKs with the same code and the same signature:

**Standard** — the normal build. Package name `com.shizuposed.manager`. Its updates work like any other app's.

**Stealth** — a separate build with a scrambled package name of the form `<letter>.<letters>.<letters>`, like `h.sghdt.kihyturd`. Nothing in that name hints at ShizuPosed. Its status provider authority is scrambled the same way.

Pick the standard build unless you have a reason not to. Pick the stealth build if a target app checks for `com.shizuposed.manager` by name, or if you want the launcher to show something other than "ShizuPosed". The stealth build is a separate install — it cannot update the standard one, and vice versa. Switching between them means uninstalling the one you have, installing the other, and setting it up from scratch.

Details are in [Installation](#installation) and [The stealth build](#the-stealth-build).

---

## What this is, and what it isn't

ShizuPosed is **not** a drop-in replacement for LSPosed. It uses a different injection primitive — `app_process` into a target it starts — because that's the only injection path that reliably works without root on modern Android. That choice is what makes ShizuPosed possible, and it's also what sets the ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or cross-process hooks, use LSPosed. ShizuPosed is for people who can't root or won't, and who still want LSPosed-API and libxposed-API modules to work in the apps it can reach.

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
  * `Service.onCreate` — hookable via `ApplicationThread.scheduleCreateService`
  * `BroadcastReceiver.onReceive` — hookable, and receiver classes are pre-loaded so the hook is live before the first broadcast
  * Classes loaded at runtime — hookable before their static initializer runs, via the `ClassLoader.loadClass` bridge
  * Dynamic code (plugin/split APKs loaded via `DexClassLoader`) — reachable via the loader-construction listener
  * `system_server` — not hookable

The `system_server` gap is structural. There's no fallback because there's no mechanism. If you need it, LSPosed is the answer.

`Service.onCreate` used to be on that list too. It isn't anymore — the `ApplicationThread` binder interface that `ActivityManagerService` calls into is reachable from the bootstrapped process, so the lifecycle events AMS schedules arrive before `ActivityThread` or `Instrumentation` sees them.

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

### The API badge

Every third-party module row shows an API badge when the module declares one. The badge has two forms:

  * **`API 93`**, **`API 94`**, … — the module declared a classic Xposed API level (via `xposedminversion` meta-data or `minApiVersion` in `META-INF/xposed/module.prop`).
  * **`libxposed 101`** — the module extends `io.github.libxposed.api.XposedModule`, i.e. it was built against the modern API.

A classic-API badge with a value above 100 is rendered as **`API N (unsupported)`**, because the classic shim caps at 100. A libxposed badge with a value above 101 renders the same way. See [API surface — classic (version 100)](#api-surface--classic-version-100) and [API surface — libxposed (version 101)](#api-surface--libxposed-version-101).

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
      ├── LibXposedDriver            libxposed API 101 driver
      ├── ShizuPosedXposedInterface  XposedInterface impl (reports 101)
      ├── LibXposedHookerAdapter     Hooker.intercept(Chain) → XC_MethodHook
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
      │     ├── XStealthNative       libc symbol interposition (libxstealth.so)
      │     ├── XStealthNativeNext   libc syscall stub patching (libxstealth_next.so)
      │     ├── SyskuNative          syscall() + getdents64() interposition (libsysku.so)
      │     ├── XStealthBridge       enumeration hiding
      │     └── ThreadNameScrub      thread renaming
      └── markers written to         <shell-base>/hooked/

    Manager process
      ├── ProcessMonitor             /proc scan + marker mirror refresh
      ├── MarkerCache                local mirror of <shell-base>/hooked/
      ├── IconResolver               LruCache for app icons
      ├── RuntimePrefs               scan interval + hook delay storage
      ├── SyskuController            state / heartbeat / diagnostics
      ├── ModuleStatusProvider       reads the mirror, never Shizuku
      └── ModuleDiagnostics          activation-state report for a module

The manager never touches the target process directly. Everything passes through Shizuku.

---

## The two injection paths

ShizuPosed reaches a target in one of two ways. Both use the same `app_process` primitive; they differ in whether the target's UI is opened after the spawn.

### Routed launch (user-triggered)

The **Launch App under ShizuPosed** button, the module row's long-press, and the detail sheet's "Open module app" entry all go through this path. It has two steps:

  1. Boot the target process with hooks installed:

         unset CLASSPATH; unset BOOTCLASSPATH;
         <prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]

     This starts the process and installs hooks during bootstrap.

  2. Open the target's UI in that already-running process:

         am start --user current -n <resolved-component>

Step 2 is required for this path. `app_process` booting a process does not start any activity, and without step 2 the process runs idle and the user sees nothing.

This is the path a **self-hooking module** needs. The module's own app is a normal, cooperative package with a launcher activity, so spawning it under ShizuPosed and then `am start`-ing its UI puts the module's own UI inside the process where its hook is installed. Its self-check then passes. That's why the README tells you to launch a module's UI through ShizuPosed — long-press the module row, or tap "Open module app" in the detail sheet.

### Monitor-triggered injection

`ProcessMonitor` scans `/proc` on a timer (default interval from Settings). When it sees a newly-started app process that at least one enabled module scopes, it calls `injectProcess`. That method performs a **spawn-only** routed launch for the package — it runs step 1 above but **not** step 2.

The spawn-only form matters: the user already opened the app themselves, and Android routes that launch into the bootstrapped process. Running `am start` on top of that would pop the app to the foreground a second time, on top of the user's own tap. So the monitor installs hooks and then leaves the UI alone.

The monitor path is also silent. It does not broadcast a launch result, so the manager UI never raises a popup for an app you opened yourself. The Logs tab records everything the service does; that is where to look.

Four guards keep the monitor from misbehaving:

  * **UID.** Packages with `uid < 10000` are refused. The shell cannot `setuid` to protected UIDs, so a monitor-triggered launch for System UI, Settings, Phone, and the rest would fail or spawn a process as the wrong UID. They are skipped.
  * **Scope.** Only packages a module actually scopes are launched. An unscoped app the user opens is ignored, even if modules exist.
  * **Dedup.** A package that already has a shell-side hooked marker, or whose launch is already in flight, is skipped. This prevents the scan interval from queuing a second launch for an app whose first launch hasn't finished.
  * **In-flight set.** A package currently being spawned is skipped until that spawn completes.

### What neither path can do

Neither path can inject into a process someone else started. If you launch Facebook from the launcher, the process that comes up is not the one ShizuPosed will hook. The monitor notices it, but the only injection it can perform is a spawn-only routed launch — which starts a fresh process for the same package, with hooks installed during bootstrap. For apps that cooperate with that, it works. For apps that manage their own process lifecycle aggressively, it may not.

The two paths differ only in two flags on `launchAppUnderShizuPosed`:

  * `openUi` — whether to run step 2 (`am start`) after the spawn.
  * `notifyUi` — whether to broadcast `ACTION_LAUNCH_RESULT`.

The button path uses `openUi=true, notifyUi=true`. The monitor path uses `openUi=false, notifyUi=false`.

### Spawn construction

The spawn command is wrapped in a subshell whose stdout is redirected to `/dev/null`, and the whole thing is backgrounded:

    unset CLASSPATH; unset BOOTCLASSPATH; \
    ( <prefix> app_process ... </dev/null >>launch.log 2>&1 & ) \
    >/dev/null 2>&1 < /dev/null &

The outer redirect is what makes the manager's shell call return immediately. Without it, `app_process` inherits the shell's stdout pipe, the manager's reader never sees EOF, and the shell command blocks for the lifetime of the target process. The `<prefix>` is `setsid -d` when available, `nohup` otherwise. `setsid -d` is the toybox detach flag — the util-linux `--fork` flag does not exist on Android.

The launch then waits `BOOTSTRAP_DELAY_MS` (default 1500 ms) before checking whether the target process came up. That check is a single `ps -A` call, not a shell loop over `/proc`, so it returns in a few hundred milliseconds rather than tens of seconds.

---

## The lifecycle bridges

Two bridges extend what the framework can reach without changing the injection model.

**`ApplicationThreadBackend`** hooks the binder interface `ActivityThread` exposes to `system_server` (its `mAppThread` field). Everything `ActivityManagerService` schedules — activity launches, service creates, receiver broadcasts — arrives on this interface before `ActivityThread` or `Instrumentation` sees it. Hooking it closes the `Service.onCreate` gap (`scheduleCreateService`) and gives modules a place to observe or rewrite binder-level lifecycle events.

Methods hooked: `scheduleCreateService`, `scheduleBindService`, `scheduleUnbindService`, `scheduleStopService`, `scheduleServiceArgs`, `scheduleTransaction` (Android 9+). Each is a fail-open hook: if a signature differs on a ROM, that method is skipped and the others still install.

**`ClassLoadingBridge`** does three things:

  1. **`ClassLoader.loadClass` hook** — fires listeners *before* a class is defined, so a module can install hooks on a class before its static initializer runs.
  2. **`DexClassLoader` / `PathClassLoader` constructor hook** — when a target constructs a new loader at runtime, a listener is notified with the new loader.
  3. **Component pre-load** — asks `PackageManager` for the target's receivers and providers, then forces each class to load.

Both bridges are installed by `XposedHook` during bootstrap, before `handleLoadPackage` (or a libxposed `onPackageLoaded`) runs for any module. Modules register listeners via the bridge classes. XStealth registers its own listeners when `Protect Xposed API calls` is on, and those listeners observe only — they never block a class load or a binder call.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

`HookEngine` records the declaring class of every method or constructor it attempts to hook. That record feeds `ApiProtectionCheck`'s fingerprint baselines.

Both classic and libxposed hooks land in the same dispatcher. A libxposed `HookBuilder.intercept(...)` is converted into the classic `XC_MethodHook` shape by `LibXposedHookerAdapter` and routed through `XposedHookBridge.installHook(...)` — the same entry point a classic `XposedHelpers.findAndHookMethod` uses. The two APIs share backends.

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

The two rows in bold are the important ones. A module — classic or libxposed — that hooks `void foo(String s)` and reads `s` from the hook receives `null` if the hook landed on Amiru or Native.

### CallSite backend status

CallSite is present in the dispatcher chain, but it is **not functional**. It patches the ART interpreter handler table but does not yet reconstruct Java-level arguments from the interpreter's `ShadowFrame`. No callback is ever invoked through this path.

Since 8.9 the backend has been honest about this: `install_hook_impl` refuses every install with a logged reason, so `CallSiteBackend.hook` returns `false` and the dispatcher falls through to the next backend.

### Disabling a backend

`HookDispatcher.setBackendEnabled(name, on)` toggles a backend by name. A disabled backend keeps its slot in the ordering — re-enabling it restores its original priority — but is skipped during dispatch. The stats string shows `[disabled]` next to the name, and a `skippedDisabled` counter tracks how many hooks were bypassed. This is how Sysku is shipped: the library loads, but stays inert until the user enables it.

---

## The dex-loading fallback ladder

Loading a module's dex into a target process can fail for several reasons. `DexLoadingBridge` tries three strategies in order:

  1. **`InMemoryDexClassLoader`** (Android 8+).
  2. **`DexClassLoader`.**
  3. **`BaseDexClassLoader` injection.**

Each strategy logs its outcome.

### Spawn-tool fallback

At startup, ShizuPosed runs a single shell probe that checks for `setsid`, `nohup`, and the `disown` shell builtin. Detection prefers `command -v` and falls back to `type`. The result is cached in static fields.

| Condition | Spawn prefix |
|---|---|
| `setsid` present | `/system/bin/setsid -d` |
| `setsid` missing, `nohup` present | `/system/bin/nohup` |
| neither present | bare spawn |

The command is always wrapped in a subshell with the outer stdout redirected to `/dev/null`, so the shell returns immediately regardless of which prefix is used. The child process is detached via `setsid -d` (toybox's detach flag) so it survives the shell exit.

### ART policy flags

Two ART flags are passed to every spawned `app_process`:

    -Xhidden-api-policy:enabled
    -Xcore-platform-api-policy:enabled

Setting both flags to `enabled` relaxes hidden-API enforcement for the process regardless of UID. They are safe: they only affect the process we spawned.

### Post-spawn verification

After the `BOOTSTRAP_DELAY_MS` wait (1500ms), ShizuPosed runs a single `ps -A -o ARGS` call and greps for the target package name. If the process is not found, the launch logs a warning and proceeds to `am start` anyway.

### Launch-result timeout

The manager's UI waits for a result broadcast from `ShizuPosedService` after dispatching a button-path launch. A timeout (`LAUNCH_TIMEOUT_MS`, 20s) guards against a lost broadcast. The monitor path does not broadcast, so it never waits on this.

### Timing

The gap between step 1 and step 2 is `BOOTSTRAP_DELAY_MS`, defaulted to 1500ms. The post-spawn check that runs after it is a single `ps` invocation, not a shell loop.

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

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` and `io.github.libxposed.api.*` when the caller isn't a loaded module.

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

**`libsysku.so`** — the syscall-level interposer. Adds two paths the named libc interposers cannot reach: `syscall()` (a caller that bypasses the named wrappers via `syscall(SYS_openat, ...)`) and `getdents64()` (directory enumeration of `/proc/self/fd` and `/proc/self/task`). See [Sysku](#sysku--the-syscall-level-interposer) below.

**Stub-shape verification.** Both `libxstealth.so` and the backends that build hook stubs coordinate around a captured prologue template. At boot, `libxstealth.so` reads the entry-point bytes of two probe methods, computes their common prefix, and runs a verifier over it.

### Bridge layer

**`libxstealth_bridge.so`** — the enumeration-hiding library.

**Thread name scrubbing.** `ThreadNameScrub` renames ShizuPosed's own threads to innocuous patterns.

### Smart foreground hiding (opt-in)

XStealth's Java and native checks intercept reads of Developer Options and ADB state. They cover every read path the target app takes through libc, through the settings API, and through the content resolver.

They do not cover two paths: a Binder call directly to `ISettingsProvider` (which bypasses the Java API and the content resolver), and a native read of the settings database's backing store (which bypasses the file interposers by using SQLite directly).

Smart foreground hiding closes those paths by changing the *underlying state* rather than intercepting the read. When a scoped app comes to the foreground, the manager writes `adb_wifi_enabled` and `adb_enabled` to 0 through Shizuku. When the app leaves, the manager restores the prior values.

Because the settings are genuinely off during that window, every reader sees 0 — including the paths XStealth's read interception doesn't cover.

#### The cost

Shizuku requires Developer Options to be enabled. When the settings are off, Shizuku's server stops. ShizuPosed's service loses its binder and shuts down. The framework is idle for the duration of the window.

When the scoped app exits, the settings are restored, Shizuku's server comes back, and `ShizuBinderWatcher` restarts ShizuPosed's service. This is the same trade-off Shizuku-Next's "ADB without Developer options" switch makes.

#### What you lose

  * **Apps launched during the window are not hooked.** If the user opens a second scoped app while the first is still in the foreground, the second app is not launched through ShizuPosed, and modules scoped to it do not load.
  * **The foreground-detection race.** The monitor detects the transition using `UsageStatsManager`, which polls. The poll interval is 500 ms. An app that reads its settings in `Application.attachBaseContext` before the monitor's next poll sees the previous value. XStealth's read interception does not have this race — the hooks install during bootstrap — so the two mechanisms are complementary: read interception covers the early reads, the state change covers the late ones.
  * **`PACKAGE_USAGE_STATS`.** The monitor uses `UsageStatsManager`, which requires the user to grant the permission through Settings → Special app access → Usage access. Without it, the monitor does not start.

#### When to use it

Enable it when a scoped app is known to read the settings through a path XStealth does not intercept. The usual tell: XStealth is on, the app's scope is set, but the app still reports Developer Options as enabled.

Do not enable it by default. XStealth's read interception covers the common cases, and the state change's cost — Shizuku and the service going down for the duration — is not worth paying unless it's necessary.

Shizuku-Next's own "Hide Developer options and ADB settings" mode covers the same territory from the Shizuku side. Enabling both is redundant for the window when XStealth's Smart foreground hiding is active; pick one, not both.

### What each layer covers

| Detection path | Java | Binder | Native (`libxstealth`) | **Sysku (`libsysku`)** | Bridge |
|---|---|---|---|---|---|
| `Settings.Global.getInt` in-process | ✅ | — | — | — | — |
| `ContentResolver.query` in-process | ✅ | — | — | — | — |
| Direct `IContentProvider.query` over Binder | — | ✅ | — | — | — |
| `cat settings_global.xml` via `Runtime.exec` | ✅ | — | ✅ | — | — |
| `open()` of settings XML | — | — | ✅ | — | — |
| **`syscall(SYS_openat, ...)` of settings XML** | — | — | — | ✅ | — |
| `dumpsys settings get global <key>` via Binder | — | ✅ | — | — | — |
| Direct `IPackageManager` lookup over Binder | — | ✅ | — | — | — |
| `dumpsys package <pkg>` via Binder | — | ✅ | — | — | — |
| `PackageManager.getInstalledPackages` | ✅ | — | — | — | — |
| `dl_iterate_phdr` loaded-library walk | — | — | — | — | ✅ |
| `/proc/self/maps` scan for `.so` names | — | — | ✅ | — | — |
| **`syscall(SYS_openat, ...)` of `/proc/self/maps`** | — | — | — | ✅ | — |
| `/proc/self/fd` enumeration (read) | — | — | ✅ | ✅ | ✅ |
| **`getdents64` of `/proc/self/fd`** | — | — | — | ✅ | — |
| **`getdents64` of `/proc/self/task`** | — | — | — | ✅ | — |
| `getppid()` | — | — | ✅ | — | — |
| `PPid:` line in `/proc/self/status` | — | — | ✅ | — | — |
| `SystemProperties` reads (API) | ✅ | — | ✅ | — | — |
| `Build.TAGS`/`Build.TYPE` | ✅ | — | — | — | — |
| Hook-stub entry-point byte pattern | — | — | ✅ | — | — |
| **Second process the target spawned** | — | — | — | — | — |
| **Native `/dev/binder` direct** | — | — | — | — | — |
| **Property shared-memory area** | — | — | — | — | — |
| **Hardware attestation** | — | — | — | — | — |

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

## Sysku — the syscall-level interposer

Sysku (`libsysku.so`) is an **optional** companion to `libxstealth.so` that closes two detection paths the named libc interposers cannot reach:

  1. **`syscall()` interposition.** A caller that bypasses the named wrapper — `syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY)` instead of `open("/proc/self/maps", O_RDONLY)` — goes through libc's `syscall()` trampoline, not `openat()`. Some detection SDKs use this specifically to avoid exactly the interposition `libxstealth.so` does. `libsysku.so` filters the raw syscall number for `SYS_openat`, `SYS_openat2`, `SYS_newfstatat`/`SYS_fstatat`, `SYS_statx`, `SYS_faccessat`, `SYS_faccessat2`, `SYS_readlinkat`, and the guarded aliases `SYS_open`, `SYS_stat`, `SYS_lstat`, `SYS_access`, `SYS_readlink` where the NDK exposes them. A caller that resolves a path XStealth wants hidden gets `-1` with `errno = ENOENT`, matching what `open()` returns for the same path.

  2. **`getdents64()` interposition.** `libxstealth.so` scrubs the *contents* of reads, but cannot hide *entries* in a directory. A detector that opens `/proc/self/fd` as a directory and counts descriptors sees ShizuPosed's fds regardless. `libsysku.so` walks the `linux_dirent64` records and drops any whose name matches a framework-owned pattern (`libxstealth`, `libshizuposed`, `libamiru`, `libsysku`, `libcallsite`, `libxstealth_next`, `libxstealth_bridge`, `syscall_cache`, `shizuposed`, `shizuku`). The caller sees fewer entries, which is indistinguishable from a process that closed more fds or spawned fewer threads.

Nothing else. `libsysku.so` does **not** redefine `open`, `openat`, `stat`, `read`, `execve`, `ioctl`, `recvmsg`, `sendmsg`, `getsockname`, `pread`, `pread64`, `dlsym`, `ptrace`, `getppid`, `__system_property_get`, `popen`, or `system` — those all belong to `libxstealth.so`. Redefining them would race at PLT resolution: the second library loaded would replace the first's interposer, and the loser's protections would silently disappear.

### Load order

`libxstealth.so` must load **before** `libsysku.so`. Sysku depends on four exports from `libxstealth.so` v0.18.0+:

  * `xstealth_path_is_blocked(const char *)` — is this path one XStealth would hide?
  * `xstealth_fd_resolves_to_hidden(int)` — does this fd resolve to a hidden path?
  * `xstealth_is_active(void)` — is XStealth currently enabled?
  * `xstealth_set_active(int)` — set the shared activation flag.

If `libxstealth.so` is missing, `libsysku.so` loads but stays inert — every interposer passes through. `SyskuNative.nativeInit()` returns `0` in that case, and the manager reports the state as `Enabled (load failed)`. That is the fail-open contract.

`XposedHook.main()` enforces the order:

    XStealthNative.load(libDir);         // libxstealth.so
    XStealthNative.setActive(true);
    XStealthNativeNext.load(libDir);     // libxstealth_next.so
    XStealthBridge.load(libDir);         // libxstealth_bridge.so

    // Sysku depends on libxstealth.so's xstealth_* exports.
    // Loading it after XStealth guarantees the exports resolve.
    if (SyskuNative.load(libDir)) {
        SyskuNative.nativeInit(shellBase);
    }

### Activation

Sysku shares XStealth's master switch. There is no independent toggle: turning on Sysku turns on every XStealth layer, and turning off XStealth turns off Sysku's interposers too. This is deliberate — Sysku is a privacy layer, and enabling it without XStealth's other layers would leave a partial block, which is a bigger tell than no block.

Sysku's state is tracked by `SyskuController`, which reads two files in `<shell-base>/sysku/`:

| File | Direction | Written by | Contents |
|---|---|---|---|
| `state.json` | manager → Sysku | `SyskuController.setEnabled()`, `reportLoadResultFromShell()`, the ADB module's WebUI | `{ schema, enabled, preferred, updated_at, updated_by, min_sysku_version, last_load_ok? }` |
| `heartbeat.json` | Sysku → manager | The Sysku ADB module's `service.sh`, every 60 s | `{ schema, ts, version, lib_loaded, state, boot_count, pid }` |

The filesystem is the IPC. Both sides have read/write access to `<shell-base>/sysku/` (the manager via Shizuku, the ADB module's `service.sh` via its own shell UID). No Binder, no ContentProvider, no broadcast.

### Settings tab

The Settings tab's Status card shows a `Sysku:` row alongside `Shizuku:` and `Service:`. The row's state comes from `SyskuController.getState()`, which reads SharedPreferences, `state.json`, and `heartbeat.json`. The possible states:

| State | Meaning |
|---|---|
| `Disabled` | The user has not enabled Sysku. |
| `Not installed` | `libsysku.so` is not present on the shell side. |
| `Enabled (stale)` | Sysku is on, but the heartbeat is missing or older than 5 minutes. |
| `Enabled (load failed)` | Sysku is on, `libsysku.so` is present, but the last bootstrap failed to initialize it (usually a missing `libxstealth.so` export). |
| `Enabled` | Sysku is on, `libsysku.so` loaded, and the heartbeat is fresh. |

The Settings card's `Sysku` toggle writes both SharedPreferences and `state.json`. The WebUI toggle in the Sysku ADB module writes only `state.json`, because the ADB module cannot reach ShizuPosed's private storage. `SyskuController.getState()` reconciles the two: when `state.json` and SharedPreferences disagree, `state.json` wins, because it's the newer write. So a WebUI toggle shows up in the Settings tab within one poll cycle.

### The Sysku ADB module

Sysku ships with an optional ADB module (`Sysku.zip`) for Shizuku-Next. It is **not required** for Sysku to function — once `libsysku.so` is deployed, the Settings tab's toggle writes `state.json` and Sysku activates on the next bootstrapped launch. The ADB module adds three things:

  * A **heartbeat loop** (`service.sh`) that writes `heartbeat.json` every 60 s, which flips the Settings tab's state from `Enabled (stale)` to `Enabled`.
  * A **WebUI** with a toggle that writes `state.json`, useful for users who want to flip Sysku from the Shizuku-Next module screen without opening ShizuPosed.
  * An **Action button** that prints a diagnostic report of everything ShizuPosed derives the state from: `libsysku.so` presence, `state.json` contents, heartbeat age, and recent `launch.log` lines.

The ADB module does not load or execute `libsysku.so`. It only coordinates its state. The library itself is deployed by the ShizuPosed APK (it lives in `jniLibs/arm64-v8a/`) and loaded by `XposedHook.main()` during bootstrap.

### Building `libsysku.so`

One `clang` command, no NDK required if building on-device with Termux:

    clang -shared -fPIC -O2 -Wall -Wextra -s \
        -target aarch64-linux-android29 \
        -o libsysku.so libsysku.c \
        -llog -ldl

Save it to `app/src/main/jniLibs/arm64-v8a/libsysku.so`. The Gradle build's `checkSysku` task verifies its presence, warns if it's missing or if `libxstealth.so` isn't also present (they have a runtime dependency), and packs it into the APK. On first launch after installation, the manager deploys it to `<shell-base>/libs/libsysku.so`, where `XposedHook.main()` picks it up.

If you build without `libsysku.so`, everything else works. Sysku simply reports `Not installed` in the Settings tab, and no syscall or getdents64 interposition happens. The build does not fail.

---

## Socket shim — opt-in, off by default

`SocketCheck` is **off by default** and installs **no hooks** unless a specific daemon is configured. This is infrastructure, not a working feature.

---

## API surface — classic (version 100)

ShizuPosed reports classic Xposed API version **100** (LSPosed generation 93, plus fork revisions 94 through 100).

  * **93** — LSPosed base. `IXUnhook`, `isModuleActive` and `isModuleEnabled` with and without arguments, provider-backed state queries.
  * **94** — `hookAllMethods` returns `Set<XC_MethodHook.Unhook>`.
  * **95** — `hookAllConstructors` returns the same.
  * **96** — `MethodHookParam.isReturnEarly()` exposed.
  * **97** — Instance field accessors on `XposedBridge`: `getObjectField`, `setObjectField`.
  * **98** — Static field accessors on `XposedBridge`, and `XposedHelpers.getSurroundingThis`.
  * **99** — Method invokers on `XposedBridge`: `callMethod`, `callStaticMethod`.
  * **100** — ShizuPosed-specific extensions: `getShizuPosedApiMin()`, `getShizuPosedApiMax()`, `supportsApi(int)`, and the `isModuleActiveAnywhere(...)` overloads.

Every revision past 93 is additive. A module compiled against API 93 loads and behaves the same way under this shim, because the shim still exposes the earlier surface. The version number is the ceiling, not the floor.

Classic modules that guard on 101 or higher correctly believe ShizuPosed doesn't support those fork revisions. If a module wants the modern API, it should target libxposed (see below), not a classic-API revision above 100.

### Active-state queries

Two distinct questions. `isModuleActive(pkg)` asks "is this module loaded into the process for `pkg`?" `isModuleActiveAnywhere(pkg)` asks "is this module loaded into any process ShizuPosed has bootstrapped?"

Under LSPosed the two questions have the same answer, because LSPosed injects into every process. Under ShizuPosed they differ. A module that scopes itself to a target app and calls `isModuleActive(getPackageName())` sees `true` under LSPosed as soon as the framework has loaded it anywhere. Under ShizuPosed that query is narrower, so `isModuleActiveAnywhere(getPackageName())` exists to answer the broader question directly.

Both are available on `XposedBridge` and `LSPosedManager`.

---

## API surface — libxposed (version 101)

ShizuPosed vendors the modern `io.github.libxposed.api.*` surface and reports **API 101**. Modules built against that surface load and run without the classic shim in the picture.

  * **`XposedInterface`** — `hook(Executable)` → `HookBuilder` → `intercept(Hooker)` → `HookHandle`, `hookClassInitializer(Class)`, `deoptimize(Executable)`, `getInvoker(Method)` / `getInvoker(Constructor)`, `log(...)`, `getFrameworkName()` / `getFrameworkVersion()` / `getFrameworkVersionCode()` / `getFrameworkProperties()`, `getModuleApplicationInfo()`.
  * **`XposedModule`** — the abstract class a libxposed module extends. It carries `attachFramework(XposedInterface, Runnable)` internally, which the framework calls before any lifecycle callback.
  * **`XposedModuleInterface`** — the lifecycle callbacks a module may override: `onModuleLoaded`, `onPackageLoaded` (Android 10+), `onPackageReady`, `onSystemServerStarting`.
  * **`Chain`** — the hook callback context: `getExecutable()`, `getThisObject()`, `getArgs()`, `getArg(i)`, `proceed()`, `proceed(Object[])`, `proceedWith(Object)`, `proceedWith(Object, Object[])`.

### What ShizuPosed reports for libxposed

  * `getApiVersion()` returns **101**.
  * `getFrameworkName()` returns **`"ShizuPosed"`**.
  * `getFrameworkVersion()` returns the app's version string.
  * `getFrameworkVersionCode()` returns **100**, matching the classic API ceiling.
  * `getFrameworkProperties()` returns the capability bitmask described below.

### Capability bits

`getFrameworkProperties()` returns a bitmask. The three bits that matter:

| Bit | Constant | Value reported | Meaning |
|---|---|---|---|
| system-server hooking | `PROP_CAP_SYSTEM` | **0** | ShizuPosed cannot hook `system_server`. |
| remote IPC | `PROP_CAP_REMOTE` | **0** | `getRemotePreferences` / `openRemoteFile` are not implemented. |
| runtime API protection | `PROP_RT_API_PROTECTION` | **1** | XStealth's `ApiProtectionCheck` is active. |

A libxposed module that reads these bits before using the corresponding path gets an accurate answer. A module that ignores the bits and calls `getRemotePreferences` gets an `UnsupportedOperationException` explaining the situation.

### What libxposed API 101 does *not* include

The 102 surface adds hot-reload, `HookHandle.getId()`, and `HookHandle.replaceHook()`. ShizuPosed reports 101 specifically so a module that guards on `getApiVersion() >= 102` falls back to non-hot-reload behavior instead of hitting a stub. Modules that need 102 should use a framework that reports it.

### Hooking from a libxposed module

    public class MainModule extends XposedModule {
        @Override
        public void onPackageLoaded(PackageLoadedParam param) {
            try {
                Method m = param.getDefaultClassLoader()
                    .loadClass("com.example.target.MainActivity")
                    .getDeclaredMethod("onCreate", Bundle.class);
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    // ... post-processing ...
                    return r;
                });
            } catch (Throwable t) {
                log(Log.ERROR, "example", "onPackageLoaded failed: " + t);
            }
        }
    }

The `onPackageLoaded` callback fires during bootstrap, before the target's `Application.onCreate`, so hooks installed there are live for the rest of the process. `onPackageReady` fires after `Application.onCreate`, and is the right place to hook anything that reads the Application object.

### What libxposed modules share with classic ones

  * **Backends.** Both APIs route hooks through `XposedHookBridge.installHook(...)` → `HookDispatcher` → `HookEngine`. The same backend chain, with the same per-backend limitations (Amiru and Native still can't carry object arguments or object returns).
  * **Scope.** The scope editor doesn't care which API a module speaks. A libxposed module's scoped apps and recommended-scope list work exactly as for a classic module.
  * **Markers and status provider.** A libxposed module shows up in the marker `moduleList` the same way a classic module does. The `isModuleActive` / `isModuleActiveAnywhere` queries in `XposedBridge` / `LSPosedManager` see it.
  * **XStealth.** `ApiProtectionCheck` refuses reflection on `io.github.libxposed.api.*` from non-module callers, just as it does for `de.robv.android.xposed.*`.
  * **Activation self-checks.** A libxposed module that wants to know whether it's active can use `XposedBridge.isModuleActive(...)` the same way a classic module does — the shim classes exist in the same process.

### What libxposed modules do **not** get

  * **`getRemotePreferences` / `openRemoteFile`.** Not implemented. `PROP_CAP_REMOTE` reports 0.
  * **Hot reload.** Not implemented. `onHotReloading` / `onHotReloaded` are declared by the interface for link compatibility but never called, and `HookHandle.replaceHook` throws. `getApiVersion()` reports 101 so a well-behaved module won't reach them.
  * **`system_server` hooking.** `onSystemServerStarting` is never called, and `ModuleLoadedParam.isSystemServer()` always returns `false`.
  * **`deoptimize`.** The method exists and returns `false`. Hook installation deopts implicitly as a side effect of landing the hook, so calling `deoptimize()` before `hook()` is redundant. A module that calls it directly sees the honest answer.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

  1. `assets/xposed_init` — the canonical marker.
  2. `assets/xposed_module` — an older convention.
  3. `META-INF/xposed/module.prop` — EdXposed-era.
  4. `assets/native_init` — a weak signal.

### How ShizuPosed decides which API a module speaks

The module's entry class is the authoritative signal.

  * If the entry class extends `io.github.libxposed.api.XposedModule`, the module is driven through the **libxposed lifecycle** (`onModuleLoaded` → `onPackageLoaded` → `onPackageReady`).
  * Otherwise, if the entry class implements one of the classic `de.robv.android.xposed.IXposedHook*` interfaces, the module is driven through the **classic lifecycle** (`handleLoadPackage` → `handleInitPackageResources`).
  * Otherwise, a reflective scan for a `handleLoadPackage` method is the last resort, and the module takes the classic path.

The `io.github.libxposed.api.XposedModule` superclass check is unambiguous: the class is abstract, so any concrete subclass is a libxposed module by construction. A classic module never extends it.

The module list row shows which path a module took via its API badge — see [The API badge](#the-api-badge).

### Recommended scope

Modules can declare which apps they're _intended_ for by shipping an `assets/scope.list` file inside the APK.

### The scope editor

The scope editor lists the apps a module can be applied to. Not every installed package appears:

  * **Protected UIDs are hidden.** A package whose UID is below 10000 — `SYSTEM_UID` (1000) and the reserved system UID pool (1001–1999) — is not offered. Those are the packages the shell UID cannot `setuid` to, and a scope entry for them would never fire.
  * **The manager itself is hidden.** Scoping ShizuPosed is meaningless; it's the framework, not a target.
  * **Everything else is shown.** Ordinary apps (`uid >= 10000`) and preinstalled apps with a dedicated UID in that range appear and can be selected.

A package whose UID is reported as 0 during package scanning falls back to a name-prefix check (`android.`, `com.android.`, `com.google.android.`) so framework packages are still excluded and preinstalled apps are still shown.

---

## Activation state — how "Activated" actually works

A module UI shows "Activated" when one of three things is true:

  * **Its own hook is installed in this process.** Some modules hook one of their own UI methods and use the hook's presence as the activation signal. Under ShizuPosed, this requires the module's UI to have been launched **through** ShizuPosed — long-press the module's row, or tap "Open module app" in the detail sheet. Launching from the launcher leaves the hook uninstalled and the UI reports its original state.
  * **`isModuleActive(getPackageName())` returns true.** The module asks the provider whether a marker exists for its own package, listing it in `moduleList`. That marker is written when the module's UI has been launched through ShizuPosed and its entry point ran (`handleLoadPackage`, or the libxposed lifecycle).
  * **`isModuleActiveAnywhere(getPackageName())` returns true.** The module asks the provider whether any marker lists it, regardless of which target the marker belongs to. This is the LSPosed-style "am I active anywhere" question, and it's the most compatible check for modules that were written against LSPosed's semantics.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per process it bootstraps to `<shell-base>/hooked/<pkg>.json`. The marker is written whenever the bind attempt completes, even if zero modules loaded — a marker with `moduleList: []` still records that ShizuPosed attempted this package. The manager mirrors the markers into its own files directory.

Both classic and libxposed modules appear in `moduleList` identically. A libxposed module is recorded once its `onModuleLoaded` callback fires, which happens before `onPackageLoaded` — so a marker written during `onPackageLoaded` will already list it.

### The query chain

    Module UI process
      → XposedBridge.isModuleActive(pkg)
        → LSPosedManager.isModuleActive(pkg)
          → ContentResolver.query(content://<status-authority>/active/<pkg>)
            → ModuleStatusProvider
              → reads <manager files>/.markers/*.json
              → returns active=1 or active=0

The `<status-authority>` is `com.shizuposed.manager.status` in the standard build, and `<x>.<y>.status` in the stealth build. The `any-active` route follows the same chain with a different endpoint.

A libxposed module that queries these uses the classic shim classes (`XposedBridge`, `LSPosedManager`) in the same process. No separate libxposed-side provider is needed.

### The Android 11+ package visibility requirement

On Android 11 and later, a module that queries the status provider from its own UI must declare the provider's authority in the module's own `AndroidManifest.xml`:

    <queries>
        <provider android:authorities="com.shizuposed.manager.status" />
    </queries>

In the stealth build, the authority is different. The module has to declare whichever authority matches the build it's running against. Without that declaration, every query returns an empty cursor — indistinguishable from "no marker." Both `XposedBridge` and `LSPosedManager` log a warning to logcat when this happens, with the exact manifest snippet to add. The warning tag is `ShizuPosed` (or `Xposed` for calls through `XposedBridge`).

### Modules that check their own activation state

Some modules don't query the framework's provider. They hook one of their own UI methods and use the hook's presence as the activation signal. Under ShizuPosed that means launching the module's UI through ShizuPosed.

The module's own classloader is what `lpparam.classLoader` (classic) or `PackageLoadedParam.getDefaultClassLoader()` (libxposed) points at during a self-hook launch, so `findAndHookMethod` / `hook(m).intercept(...)` on the module's own classes resolves.

**How to do it:**

  * Long-press the module's row in the Modules tab.
  * Or tap "Open module app" in the detail sheet.

### Modules that don't self-hook

Many modules don't use the self-hook pattern. Some are pure system-hook modules — CorePatch, for example, only does work when `packageName == "android"`. Those modules require LSPosed.

### When a module still reports "not activated"

The module detail sheet has a **Run activation diagnostic** action. It reports, for the selected module:

  * Whether the module is in the manager's module list.
  * Whether its cached dex is present on the shell side.
  * How many apps it's scoped to.
  * Whether any marker lists it.
  * The `<queries>` declaration it needs on Android 11+.

If the diagnostic says the marker exists and lists the module, but the module UI still shows "not activated," the module is checking a signal ShizuPosed doesn't provide — typically a check for LSPosed-specific classes, the LSPosed manager package name, or a coverage model that assumes zygote-wide injection. That's a compatibility gap between the module and the framework, not a bug in either. The fix is on the module's side: check its own hook, or use `isModuleActiveAnywhere`.

---

## Module development

ShizuPosed supports two module APIs. A module can target either; they don't interleave. Pick the one your build system or upstream library expects.

### Classic API (`de.robv.android.xposed.*`)

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

### libxposed API (`io.github.libxposed.api.*`, API 101)

Standard libxposed API. The lifecycle split differs from the classic one — see [What each callback sees](#what-each-callback-sees) below.

    public class MainModule extends XposedModule {
        @Override
        public void onModuleLoaded(ModuleLoadedParam param) {
            log(Log.INFO, "example", "loaded in " + param.getProcessName());
        }

        @Override
        public void onPackageLoaded(PackageLoadedParam param) {
            try {
                Method m = param.getDefaultClassLoader()
                    .loadClass("com.example.target.MainActivity")
                    .getDeclaredMethod("onCreate", Bundle.class);
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    // ... inspect or replace r ...
                    return r;
                });
            } catch (Throwable t) {
                log(Log.ERROR, "example", "onPackageLoaded failed: " + t);
            }
        }

        @Override
        public void onPackageReady(PackageReadyParam param) {
            // Application.onCreate has run; hooks on Application
            // members installed here are live.
        }
    }

#### What each callback sees

| Callback | When it fires | What's available |
|---|---|---|
| `onModuleLoaded` | After class load, before any package work | `ModuleLoadedParam.getProcessName()`, `isSystemServer()` (always `false`) |
| `onPackageLoaded` | During bootstrap, after classloader resolved, before `Application.onCreate` | `PackageLoadedParam.getPackageName()`, `getApplicationInfo()`, `getDefaultClassLoader()`, `isFirstPackage()` |
| `onPackageReady` | After `Application.onCreate`, before any activity | Everything from `onPackageLoaded`, plus `getClassLoader()` and `getAppComponentFactory()` |
| `onSystemServerStarting` | Never (ShizuPosed cannot hook `system_server`) | — |

`onPackageLoaded` and `onPackageReady` fire once per bootstrapped process. Modules that install hooks in the target app's own code should do so from `onPackageLoaded` — the earlier callback gives the hook a chance to fire before `Application.attachBaseContext`.

### Using the lifecycle bridges

Both bridges work identically regardless of which API a module speaks.

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

A libxposed module can call the same bridge classes; the hooks it installs through them go through the same dispatcher.

### Reporting state in a module UI

    boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
    boolean active  = XposedBridge.isModuleActive(getPackageName());
    boolean loaded  = XposedBridge.isModuleActiveAnywhere(getPackageName());
    String[] scope  = XposedBridge.getModuleScope(getPackageName());

For a module UI that wants to display "Activated" correctly under both LSPosed and ShizuPosed, check in this order:

  1. Is my own hook installed in this process? If yes, active.
  2. `isModuleActive(getPackageName())` — active in a specific process.
  3. `isModuleActiveAnywhere(getPackageName())` — active anywhere.

Step 1 is the pure self-hook pattern and works on both frameworks. Step 3 is the ShizuPosed-compatible equivalent of what LSPosed's `isModuleActive` effectively returns.

### Resource replacement

Classic API:

    public class Res implements IXposedHookInitPackageResources {
        @Override
        public void handleInitPackageResources(
                XC_InitPackageResources.InitPackageResourcesParam resparam) {
            resparam.res.setReplacement(R.string.some_string, "replacement");
        }
    }

libxposed API 101 does not include a resource-replacement callback equivalent to `IXposedHookInitPackageResources`. A libxposed module that needs resource replacement should use the classic API alongside it (i.e. ship two entry classes, one per API) or use the `ClassLoadingBridge` to hook resource lookups directly.

### Layout hooks

Classic API:

    resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(LayoutInflatedParam param) {
            // param.view, param.resId, param.resName are set
        }
    });

Layout hooks are classic-only in API 101. A libxposed module that needs them can call the classic `XResources` object through the `XposedBridge` / `LSPosedManager` shims, or hook `LayoutInflater.inflate` directly via `hook(...)`.

### Writing a hook that works on every backend

Because Amiru and Native can't carry object arguments or object returns, a module — classic or libxposed — that needs those should either rely on Pine succeeding, avoid object arguments entirely, or accept that the hook may not fire with the values it expects.

For libxposed modules, `Chain.getArg(i)` returns `null` on those backends, matching what `MethodHookParam.args[i]` would return for a classic hook. `Chain.proceed()` is implemented as a synchronous original-method invocation, so `Object r = chain.proceed(); return r.toString();` works as expected — but only when the hook is on a backend that can actually invoke the original. Pine AUTO and Pine REPLACEMENT handle this correctly; Amiru and Native don't support the pattern.

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is **Shizuku-Next** by rushiranpise at `github.com/rushiranpise/Shizuku-Next`. About 200 MB free storage. No root required.

For building the app: JDK 25 (with a Gradle toolchain), Gradle 9.8, Android SDK 37. Native libraries rebuild with `clang` alone on Termux; no NDK is required if you're building on-device. Building with the Android NDK (r25+) works and is what CI uses.

Upstream Shizuku works. Shevery is also supported, though some of its privileged-API paths have known issues. Shizuku-Next uses a different signing key from upstream Shizuku, so switching from upstream requires uninstalling it (and rebooting to ensure its server is stopped). The API surface is identical either way, but Shizuku-Next's boot persistence and watchdog recovery mean fewer service-restart races in practice.

---

## Installation

Install Shizuku (Shizuku-Next recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Download the build you want from the Releases page:

  * `app-standard-release.apk` — the normal build.
  * `app-stealth-release.apk` — the scrambled-name build.

Unless you have a specific reason to pick the stealth build, pick the standard one. Its updates work like any other app's, and everything in this README refers to it by name.

Install it:

    adb install -r app-standard-release.apk

Or `app-stealth-release.apk` for the stealth build.

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to — protected-UID packages won't appear in the picker. Then either tap **Launch App under ShizuPosed** and pick a scoped app, or just open a scoped app normally; the process monitor will trigger a spawn-only injection for it.

Activation isn't retroactive for a process already running. The monitor catches a scoped app when it starts; an app that was already running before the module was scoped is not retroactively hooked.

### The stealth build

The stealth build has a scrambled package name of the form `<letter>.<letters>.<letters>`, like `h.sghdt.kihyturd`. Its status provider authority is scrambled the same way. A target app that checks for `com.shizuposed.manager` by name finds nothing, and a check for `com.shizuposed.manager.status` finds nothing either.

It's a separate app to Android:

  * It cannot update the standard build, and the standard build cannot update it. Installing one over the other fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
  * It does not share settings, modules, or Shizuku permissions with the standard build.
  * Its launcher label is **Sync Service** by default, so you can tell the two apart if both are installed.

Switching between builds means uninstalling one, installing the other, and setting it up from scratch. Modules you added to one don't appear in the other.

Before installing the stealth build over an existing standard install, clear the shell-side cache so the new build starts fresh:

    rm -rf /data/user/0/com.android.shell/files/.syscall_cache

On devices where that path isn't writable, use the fallback:

    rm -rf /data/local/tmp/shizuposed

Either is safe if the directory doesn't exist.

The stealth build does not hide behavior. A target app that detects the hook by checking its own method entry points, or by running its own runtime-integrity SDK, still detects the hook regardless of which build you're running. The scrambled name closes name-based checks only.

---

## The manager

Five tabs.

**Home** shows the status card, the counter tiles, and the Framework Info card.

**Modules** lists installed modules with XStealth always at the top. Each third-party module row shows an API badge (`API 93` for classic, `libxposed 101` for libxposed) when the module declares one.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has the runtime toggles, scan interval and hook delay sliders, the Sysku toggle and status line, the Maintenance section, a **Check for updates** button, and config export.

### Module detail sheet

For any module, the detail sheet has a **Run activation diagnostic** action. It prints a short report for that module:

  * Whether the module is in the module list.
  * Whether its cached dex is present.
  * How many apps it's scoped to.
  * Whether any marker lists it.
  * The `<queries>` block the module needs on Android 11+.
  * Which API the module targets (`classic`, `libxposed`, or `unknown`) and the API version it declared, if any.

The report is designed to answer "why does this module show not activated?" in one screen, without needing to read logcat.

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

The status line at the top of the sheet summarizes which layers are armed. A separate line shows Sysku's state, sourced from `SyskuController.getState()` — Sysku shares XStealth's master switch, so its line is read-only.

Note: `Hide Developer Options` and `Hide ADB enabled` change what a target *reads*, not what the device actually has set.

---

## Provenance and verification

ShizuPosed is built from sources in this repository. The APK is reproducible from a clean checkout with the documented toolchain.

**Signed builds.** Both the standard and stealth APKs are signed with the project's release key.

**Native library provenance.** Each `.so` is built from the `.c` file with the same name in the `native/` directory of this repository.

**What to look for in a release.** The release notes list the NDK version used, the SDK version, the Gradle and JDK versions, and a SHA-256 of each native library.

**Vendored libxposed API.** The `io.github.libxposed.api.*` classes in `XposedHook.dex` are vendored from the upstream libxposed API 101 definitions. The vendored copies are interface-only (with `getApiVersion()` declared abstract) — no framework implementation ships in them. Everything libxposed modules call is implemented by ShizuPosed's own classes.

---

## Building

### The app

    export ANDROID_HOME=$HOME/Android/Sdk
    git clone <repo>
    cd ShizuPosed
    ./gradlew clean :app:assembleStandardRelease
    adb install -r app/build/outputs/apk/standard/release/app-standard-release.apk

To build the stealth APK as well:

    ./gradlew :app:assembleStealthRelease
    adb install -r app/build/outputs/apk/stealth/release/app-stealth-release.apk

The stealth build's package name is derived from a per-machine seed. To see what this machine will produce:

    ./gradlew printStealthId

The same machine produces the same name on every build. Pass `-PstealthSeed=<anything>` to get a different name for that build.

### The native libraries

The six native libraries (`libshizuposed.so`, `libamiru.so`, `libcallsite.so`, `libxstealth.so`, `libxstealth_next.so`, `libxstealth_bridge.so`) and the optional `libsysku.so` build with the Android NDK. On Termux, an ARM64 Android NDK is required. Build for API 29 or higher.

`libsysku.so` builds with a single `clang` command — see [Building `libsysku.so`](#building-libxsysku.so). It is optional; the build succeeds without it.

### The build checks

The `app/build.gradle` includes seven verification tasks: `checkNativeShim` (required), `checkCallSite`, `checkAmiru`, `checkXStealth`, `checkXStealthNext`, `checkXStealthBridge`, `checkSysku` (all optional, warn-only). Skip all seven with:

    ./gradlew assembleStandardRelease -PskipNativeCheck=true

### Configuration cache

The build is configuration-cache-compatible with Gradle 9.8.

---

## Reading the launch log

Every routed launch writes stderr and stdout from `app_process` to `<shell-base>/launch.log`:

    cat /data/user/0/com.android.shell/files/.syscall_cache/launch.log

The spawn-tool probe result is logged once at startup. XStealth's native preload happens first. The stub-template capture and verification logs its outcome. Each Binder-hooking class logs its refusals. The two lifecycle bridges log their own install. Each XStealth layer logs its own status.

When `libsysku.so` is loaded, the log records:

  * `Preloaded libxstealth.so` / `libxstealth_next.so` / `libxstealth_bridge.so`
  * `Loaded libamiru.so` / `libsysku.so`
  * `Loaded libsysku.so from <path> (nativeInit returned N)` where N is 1 when every `xstealth_*` export resolved.

`logcat` under tag `libsysku` shows:

  * `JNI_OnLoad: libsysku 1.0.0 (scope: syscall + getdents64 only; complements libxstealth.so)`
  * `resolved: syscall=0x… getdents64=0x… xstealth_path_is_blocked=0x… xstealth_fd_resolves_to_hidden=0x… xstealth_is_active=0x…`
  * `nativeInit: version=1.0.0 ready=1 …`

A `ready=0` means one of the five lookups failed. The log line names which — usually `xstealth_path_is_blocked` is NULL because `libxstealth.so` is older than v0.18.0.

When a libxposed module loads, the log records:

  * the entry class name,
  * `attachFramework OK for <pkg>`,
  * one line per lifecycle callback that fired (`onModuleLoaded`, `onPackageLoaded`, `onPackageReady`),
  * any exception the module threw, tagged with the callback name.

Monitor-triggered launches log through the same path. Each call to `injectProcess` logs which guard it passed, which it failed, and whether the routed launch succeeded. The launch itself is silent in the UI; the Logs tab is where to watch it.

Each bootstrap also writes a marker to `<shell-base>/hooked/<pkg>.json` when it completes, including a marker with an empty `moduleList` when no modules loaded. The marker records which modules ran and which failed. Both classic and libxposed modules appear in the marker's `moduleList`.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. Cross-process hooks aren't possible. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework.

**Protected UIDs are unreachable.** The shell UID cannot `setuid` to `SYSTEM_UID` (1000) or the reserved system UID pool (1001–1999). System UI, Settings, Phone, Bluetooth, NFC, Shell, and `system_server` cannot be hooked by any path. The scope editor hides them so they can't be selected. Ordinary apps (`uid >= 10000`), including preinstalled apps with a dedicated UID, are reachable.

**Zygote-timing APIs.** Modules that implement `IXposedHookZygoteInit` (classic) or `onSystemServerStarting` (libxposed) expect a callback that only fires under a zygote-based or system-server-capable framework. ShizuPosed declares both for link compatibility but cannot call them.

**Service.onCreate.** Reachable in the process ShizuPosed launched, via the `ApplicationThread` binder interface.

**Monitor-triggered injection doesn't attach to the detected pid.** Nothing non-root can. When the monitor sees a scoped app start, it can only trigger a spawn-only routed launch, which creates a new process for that package. For apps that cooperate with that, hooks install during bootstrap and the user's own launch routes into the bootstrapped process. For apps that manage their own process lifecycle aggressively, the routed launch may not produce a usable process — the same ceiling as the button-triggered path.

**Monitor-triggered injection never opens the UI.** The monitor path is spawn-only. The user opens the app; the framework does not pop it to the foreground on its own.

**Object arguments and object returns on the native backends.** Amiru and Native pass object arguments to Java callbacks as `null`, and don't accept object return values. `thisObject` is also `null` on both. Pine, Instrumentation, and Proxy handle objects correctly. This applies to classic and libxposed hooks identically — the adapter can't fix what the backend can't carry.

**libxposed reports 101, not 102.** Hot reload (`onHotReloading` / `onHotReloaded`), `HookHandle.getId()` with framework-assigned ids, and `HookHandle.replaceHook()` are 102 features. ShizuPosed reports 101 so a well-behaved module doesn't reach them. A module that ignores the version guard and calls them directly will get an `UnsupportedOperationException` rather than silent wrong behavior.

**libxposed remote IPC is unimplemented.** `PROP_CAP_REMOTE` reports 0. `getRemotePreferences` throws `UnsupportedOperationException`; `listRemoteFiles` returns an empty array; `openRemoteFile` throws `FileNotFoundException`. A module that needs manager-side preference storage on the hook side should use `XSharedPreferences` (the classic shim's file-based reader) instead.

**libxposed `deoptimize` is advisory.** The method returns `false`. Hook installation deopts implicitly as part of the install. A module that calls `deoptimize()` then `hook()` still gets its hook installed; a module that calls `deoptimize()` and expects a standalone deopt does not get one.

**libxposed `Chain.proceed()` depends on the backend.** The adapter invokes the original method synchronously through `XposedBridge.invokeOriginalMethod`. That works on Pine AUTO and Pine REPLACEMENT. On Amiru and Native, object arguments and `thisObject` still arrive as `null` — same limitation as classic hooks.

**Sysku is scope-limited to what a syscall interposer can see.** It catches `syscall(SYS_openat, …)` and `getdents64(…)` for paths and directories XStealth already considers hidden. It does **not** catch:

  * A caller that resolves `open` via `dlopen` and invokes it through a hand-resolved function pointer that bypasses both the PLT and `syscall()`.
  * A caller that reads `/proc/self/maps` through a memory-mapped view of `/proc/self/maps` (no syscall on each access).
  * A second process the target spawned.
  * Native code that opens `/dev/binder` directly.

**Sysku is not standalone.** It requires `libxstealth.so` v0.18.0+ for its four `xstealth_*` exports. If `libxstealth.so` is missing or older, `libsysku.so` loads but stays inert — every interposer passes through. `SyskuNative.nativeInit()` returns 0 in that case, and the Settings tab reports `Enabled (load failed)`.

**Sysku shares XStealth's master switch.** There is no independent Sysku toggle. Turning on Sysku turns on every XStealth layer; turning off XStealth turns off Sysku's interposers too. This is deliberate — a partial block is a bigger tell than no block.

**Sysku's `getdents64` interposer drops records by name.** A directory entry whose name does not match a framework-owned pattern is forwarded unchanged. If a future framework-owned file gets a name that doesn't match any pattern, it will appear in `getdents64` output. The pattern list is in `libsysku.c` — extend it if you add a new library or marker file.

**Entry-point shape matching has a ceiling.** The stub's prologue matches what ART emits for the first five or six instructions. A detector that reads past the prologue, or that disassembles the whole stub, still sees the difference.

**XStealth sanitizes reads, not values.** The underlying value of `development_settings_enabled` in the settings database is unchanged by XStealth.

**XStealth's practical ceiling.** XStealth covers the common detection paths. It doesn't cover hardware attestation, native reads of the settings database through the property shared-memory area, method-list reconstruction, exotic reflection, kernel-level watchers, full-stub disassembly, or signals unrelated to ShizuPosed.

**Bridge layer caveats.** The bridge library filters `dl_iterate_phdr` process-wide. `getenv` scrubbing only covers the API path.

**Subprocess hiding has a load-order window.** Since 8.6, the native engines load at the top of `XposedHook.main()`, so the window is a few milliseconds.

**Socket shim is inert by default.** `SocketCheck` installs no hooks unless a daemon is configured.

**Modules that require LSPosed.** Some modules do their work in `system_server`. Others check for LSPosed-specific signals — the LSPosed manager package, `org.lsposed.*` server classes, or coverage that assumes zygote-wide injection — and display "not activated" under any framework whose coverage is narrower. That's a compatibility gap, not a ShizuPosed bug. The module's UI needs to check its own hook or use `isModuleActiveAnywhere`.

**The stealth build is a separate install.** It cannot update the standard build, and vice versa. Switching between them means uninstalling one, installing the other, and setting it up again. Its scrambled package name hides the manager from name-based checks; it does not hide the hook from behavior-based ones.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**Binder-level hiding has structural gaps.** The `BinderProxy.transact` hooks only see calls that go through the Java `IBinder.transact` method in *this process*. They do not see:

  * **Calls from a second process the target spawned.**
  * **Native code that opens `/dev/binder` directly.**
  * **Calls made before the hooks install.**

**Banking apps that ship native runtime-integrity SDKs (BioCatch and friends).** Some banking apps come bundled with third-party fraud-detection SDKs that check the app's own runtime state. The one you'll run into most often is **BioCatch** — HSBC Philippines and several other regional banks use it.

XStealth shuts down the file and property channels BioCatch reads through libc, and closes the `IPackageManager` and `IContentProvider` Binder channels for package and settings queries. Sysku extends this to the raw `syscall()` and `getdents64()` paths. What neither closes:

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

Rikka, for Shizuku; the primitive that makes all of this possible. canyie, for Pine, the primary ART hooking engine. LSPosed, for the API generation ShizuPosed targets, and for the design of the module status provider contract. The libxposed project, for the modern `io.github.libxposed.api.*` surface that the API 101 support implements.

And every module author who kept the `de.robv.android.xposed.*` API alive long enough for an alternative to matter — and every module author who has moved to `io.github.libxposed.*`.