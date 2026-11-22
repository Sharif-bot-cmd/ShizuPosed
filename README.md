# ShizuPosed

**An Xposed-compatible hook framework that doesn't need root — it runs on `app_process`.**

ShizuPosed runs Xposed-API modules inside target apps without root. No bootloader unlock, no system partition changes. Shizuku calls `app_process` as the shell UID, a Java runtime spins up, and hooks get installed through a dispatcher that tries several backends until one sticks. Modules built for LSPosed and classic Xposed keep working, and modules built against libxposed API 101 or 102 are supported as well.

---

## One build

ShizuPosed ships as a single APK. Package name `com.shizuposed.manager`. Its updates work like any other app's — and 1.4.9 also ships an in-app update flow (see [Updates](#updates)).

If a target app checks for `com.shizuposed.manager` by name, use the **Hide ShizuPosed app** flow in the Settings tab. It patches the current APK's manifest in place, re-signs with a **per-install** key, installs under a new random package name, and uninstalls the old one. See [Hide ShizuPosed app](#hide-shizuposed-app).

Details are in [Installation](#installation).

---

## What this is, and what it isn't

ShizuPosed is **not** a drop-in replacement for LSPosed. It uses a different injection primitive — `app_process` into a target it starts — because that's the only injection path that reliably works without root on modern Android. That choice is what makes ShizuPosed possible, and it's also what sets the ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or cross-process hooks, use LSPosed. ShizuPosed is for people who can't root or won't, and who still want LSPosed-API and libxposed-API modules to work in the apps it can reach.

The rest of this document is honest about where that line falls.

---

## Verification status

ShizuPosed's core primitive has been exercised on-device, but not every link in the chain has been independently verified. This section is the honest ledger.

**Verified on-device (Android 16 / HyperOS, ARM64):**

- `app_process` reaches ART after the `BOOTCLASSPATH` fix (see [The `BOOTCLASSPATH` fix](#the-bootclasspath-fix)). Prior to the fix, `Runtime::ParseOptions` aborted with `BOOTCLASSPATH must not empty`. The inherited shell `BOOTCLASSPATH` is now preserved, with the classpath-protobuf fallback used only when the shell has none.
- The spawn-only path now requires evidence of survival (marker present or process alive after the bootstrap window) before reporting success. See [Spawn-only success semantics](#spawn-only-success-semantics).

**Not yet independently verified end-to-end:**

- `setuid` to the target UID succeeding from the shell-spawned process.
- SELinux domain transition to the target's context.
- AMS not reaping the unregistered PID before hooks fire.
- Hooks actually firing inside the target's `Application.onCreate`.

These four are the remaining load-bearing assumptions. The code has diagnostics for each — read [Reading the launch log](#reading-the-launch-log) and check the manager's Logs tab. A future release will add explicit log lines for each outcome (target UID readback, SELinux context, process survival window, hook-fire confirmation) so the ledger above can be updated with measurements rather than expectations.

If you can test a build on a device where the old `BOOTCLASSPATH` abort reproduced, open an issue with the `launch.log` and `logcat -s XposedHook`. That's the missing experiment.

---

## Why `app_process`

Without root, the injection primitives on Android 10+ are narrow. `ptrace` is blocked by SELinux for non-root UIDs. `LD_PRELOAD` needs a writable, executable path the target will load from, and app data directories are mounted `noexec`. Zygote fork requires being inside zygote, which means root or being the ROM. `Runtime.exec` runs as the caller's UID, not the target's. `am instrument` gives you an `Instrumentation` handle only after `Application.onCreate` — too late for bootstrap hooks. ContentProvider hijack works only if the target already declares a provider you can take over.

`app_process` is the one that works. It's a platform binary that exists on every Android device. Shizuku can invoke it as shell (UID 2000). It starts a Java runtime, so you can load arbitrary dex into it. And Shizuku can pass the target package and UID as arguments.

That's the entire mechanism. Everything else in ShizuPosed is a consequence of it.

### The `BOOTCLASSPATH` fix

`app_process` inherits ART's boot classpath from the invoking shell. If the shell has none, ART aborts in `Runtime::ParseOptions` with `BOOTCLASSPATH must not empty` (SIGABRT). Earlier builds unset `BOOTCLASSPATH` unconditionally before spawning, which caused exactly that abort on ROMs where the shell's environment supplies a valid classpath.

The launch command now:

1. Keeps the inherited `BOOTCLASSPATH`.
2. Falls back to parsing `/apex/com.android.art/etc/classpaths/bootclasspath.pb` and `/system/etc/classpaths/bootclasspath.pb` with `strings` + `grep` + `awk` + `paste` only when the shell has none. This covers HyperOS's extra `system_ext` jars and mirrors the order init uses.
3. Mirrors the result to `DEX2OATBOOTCLASSPATH` so both ART and dex2oat see a valid boot classpath.
4. Unsets `CLASSPATH` only — keeping it would shadow the dex path.

Verified on Android 16 / HyperOS: `app_process64` gets past ART with the inherited value, and the fallback path is portable because `strings`, `grep`, `awk`, and `paste` are all present in Android toybox.

### What the shell UID can and cannot assume

`app_process` runs as whichever UID the shell passes it. The shell UID is 2000. It can `setuid` to ordinary app UIDs (>= 10000), which is the normal `app_process` target range. It **cannot** `setuid` to `SYSTEM_UID` (1000) or to the reserved system UID pool (1001–1999) — those are protected.

This means:

- A preinstalled app with its own UID in the 10000+ range is a normal target. It can be hooked.
- `com.android.systemui`, `com.android.settings`, `com.android.phone`, `com.android.bluetooth`, `com.android.nfc`, `com.android.shell`, and `system_server` all run under protected UIDs. They cannot be hooked by ShizuPosed, and they are not offered as scope targets.

The scope editor hides protected-UID packages, so what you see in the picker is what the framework can actually reach.

### After `setuid` — what the process is and isn't

Once `XposedHook.main()` calls `Process.setuid(targetUid)`, the process **runs as the target's UID**. It can read the target's private data, and its SELinux context is the target's context on most ROMs.

What it is _not_:

- **It is not a zygote fork.** Its parent process is `app_process`, not `zygote`. `getppid()` and the `PPid` field in `/proc/self/status` reflect the bootstrapped parent, not zygote's PID. XStealth scrubs both, but the scrub is userspace — a kernel-level reader can still see the real parent.
- **Its SELinux context can differ** on ROMs that enforce stricter labels for non-zygote processes.
- **It does not inherit zygote's open file descriptors** for the target's data directory.
- **It is not registered with ActivityManagerService.** AMS tracks app processes in `ProcessRecord`. A shell-spawned process is not in that list; the code's own post-`am start` diagnostic logs `AMS did not route into the bootstrapped process` when this happens. On most apps the bootstrapped instance and the launcher's instance converge on the same application state, but the two PIDs are distinct and only the launcher's is registered. This is the single largest open question in the verification ledger.

These differences are what XStealth's appearance layer and `libxstealth_bridge`'s `/proc` scrubbing are for. They are userspace scrubs, not lineage changes, and cannot close every gap.

**If `setuid` fails on a ROM**, the process runs as UID 2000 and cannot access the target's data at all. `XposedHook` logs the failure clearly, and the launch will typically fail at `handleBindApplication`.

---

## The timing model

LSPosed is a zygote-timing framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a bootstrap-timing framework: it is `app_process`-launched into the target, installs hooks, then drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

Here's what ShizuPosed can reach in bootstrap mode:

- `Application.attachBaseContext` — hookable
- `Application.onCreate` — hookable
- `ContentProvider.onCreate` — hookable, provider classes pre-loaded
- `Activity.onCreate` — hookable via Pine, Amiru, Native, CallSite, or Instrumentation
- `LayoutInflater.inflate` — hookable, drives `XC_LayoutInflated` callbacks
- `Service.onCreate` — hookable via `ApplicationThread.scheduleCreateService`
- `BroadcastReceiver.onReceive` — hookable, receiver classes pre-loaded
- Classes loaded at runtime — hookable before their static initializer runs
- Dynamic code (plugin/split APKs loaded via `DexClassLoader`) — reachable via the loader-construction listener
- `system_server` — not hookable

The `system_server` gap is structural.

### `IXposedHookZygoteInit` under ShizuPosed

ShizuPosed is not a zygote-timing framework, so `initZygote()` fires **once per bootstrapped process**, not once per zygote fork. It runs at the start of bootstrap, before any module's `handleLoadPackage`.

`StartupParam`:

- `modulePath` is the **installed APK path**, not the cached dex path.
- `startsSystemServer` is always `false`.

---

## How a module gets into a target process

There are two ways a module's hooks get installed into a process, and **which one applies depends on what the module is for.** Understanding the split is the single most important thing about ShizuPosed's injection model.

### The headless path (the normal workflow)

A **headless module** is a module whose job is to hook methods in a target app: remove ads, tweak privacy, modify behavior. It has no UI of its own, or its UI is not where the hooks need to be live.

For a headless module, the workflow is:

1. The user installs the module, opens ShizuPosed, and scopes the target app in the module's scope editor.
2. The user **opens the target app normally** — from the launcher, from a notification, from another app, however they'd normally open it.
3. `ProcessMonitor` (the service's `/proc` scanner, default interval from Settings) notices the new process for the scoped package.
4. `ProcessMonitor` calls `injectProcess`, which spawns a fresh `app_process` bootstrapped with the target package and UID.
5. The bootstrapped process installs the module's hooks for the target app, drives `ActivityThread` bootstrap, and writes a marker to `<shell-base>/hooked/<pkg>.json`.
6. The module's hooks fire against the app the user is interacting with.

**The user does not use "Launch App under ShizuPosed" for this.** They open the app the way they always do. The monitor path handles the rest.

**About the process identity.** The process the launcher started and the process `app_process` spawned are technically two different processes — the launcher's is a zygote fork, the bootstrapped one is not. For the vast majority of apps, the two converge on the same application state: both load the same code, both construct the same `Application`, and the hooks are installed in the bootstrapped one before its `Application.onCreate` runs. The user sees the app, the module works, and the process-identity nuance is invisible.

The process-identity nuance *does* matter for a small number of apps — apps that aggressively restart their process, apps that hold a process-level lock, apps that depend on being the only instance. For those, the headless path may not produce a usable hooked instance, and the app needs the explicit-launch path.

### The explicit-launch path (for self-hook modules and problem apps)

**"Launch App under ShizuPosed"** in the manager, the module row's long-press, and "Open module app" in the detail sheet all use this path.

It's for three cases:

1. **Self-hook modules.** A module whose own UI needs to run in a process where its hooks are live (e.g., a module that hooks its own settings screen to display module state). Launching the module's own app through ShizuPosed puts the UI in a bootstrapped process, so `isModuleActive()` returns true from the module's own code.
2. **Apps that need a fresh, hooked process with the UI attached.** When the user wants to *see* the hooked app, and the monitor path isn't producing a usable instance.
3. **Apps that aggressively restart their process.** Some apps kill themselves on launch and let zygote restart them. The monitor's spawn gets bypassed, and the explicit-launch path is the only one that reliably produces a hooked instance.

The explicit-launch path runs `am force-stop <pkg>` (if the UI will be opened), spawns `app_process`, waits for the bootstrap, then runs `am start --user current -n <launcher-component>` to open the UI in the already-running process. Hooks are live before the target's `Application.onCreate` runs, because the bootstrap ran first.

### Which path should a user use?

| Module type | Path |
|---|---|
| Headless module (hooks a target app, no module UI, or module UI is separate from the hook) | Open the target app normally. The monitor handles it. |
| Self-hook module (module's own UI needs its hooks live) | Use "Launch App under ShizuPosed" for the module's own app. |
| App that aggressively restarts its process | Use "Launch App under ShizuPosed". |
| App that doesn't work under the monitor path for any reason | Use "Launch App under ShizuPosed". |

If a headless module "works when I open the app normally" — which is the common case — that's the intended workflow, not a workaround.

### What neither path can do

Neither path can inject into a process that someone else started and that ShizuPosed didn't spawn. The monitor notices the process, but its only injection primitive is "spawn a fresh `app_process` for the same package." It cannot attach to the zygote process. The `pid` argument to `injectProcess` is used for logging only; it is not the target of an attach.

For most apps, this doesn't matter — the bootstrapped instance and the launcher's instance converge on the same application state, and the module's hooks fire against the running app. For apps where it does matter (aggressive process restarts, process-level locks), the explicit-launch path is the answer, and even it has a fallback case where the app restarts itself and the UI ends up in a zygote process without hooks.

### Routed launch sequence

The explicit-launch path:

1. Boot the target process with hooks installed:
   ```
   unset CLASSPATH;
   if [ -z "$BOOTCLASSPATH" ]; then ... (parse classpath protobufs) ... fi;
   if [ -z "$DEX2OATBOOTCLASSPATH" ]; then DEX2OATBOOTCLASSPATH="$BOOTCLASSPATH"; fi;
   export BOOTCLASSPATH DEX2OATBOOTCLASSPATH;
   <prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]
   ```
2. Open the target's UI in that already-running process:
   ```
   am start --user current -n <resolved-component>
   ```

After `am start`, the service compares the target-package process count before and after. If `am start` created a second process (`after > before + 1`), the launcher's UI is in a zygote process without hooks and the log says so:

```
AM START created a second process for <pkg> (before=N, after=M).
AMS did not route into the bootstrapped process; the UI is running
without hooks.
```

### Monitor-triggered injection sequence

The headless path:

1. `ProcessMonitor` scans `/proc` on a timer.
2. For each new process whose package is scoped by at least one enabled module, calls `injectProcess`.
3. `injectProcess` runs a **spawn-only** routed launch (step 1 above, without step 2).
4. The monitor path is silent — it doesn't broadcast a launch result, so the manager UI never raises a popup for an app the user opened themselves.

### Spawn-only success semantics

A spawn-only launch (monitor path or `injectProcess`) reports success only when there is evidence the child survived the bootstrap window:

- the marker `<shell-base>/hooked/<pkg>.json` exists, **or**
- the target process is still alive after the wait.

If neither is true, the launch reports failure with `bootstrap did not survive (no marker, no process)` and points at `launch.log`. Earlier builds logged "hooks installed" unconditionally here, which masked real failures (e.g. the `BOOTCLASSPATH` SIGABRT). That path is gone.

### The marker

Every completed bootstrap writes a JSON marker to `<shell-base>/hooked/<pkg>.json`. The marker's `moduleList` lists the modules whose hooks are installed in that process. The manager's `MarkerCache` mirrors these markers into the manager's own files directory, and `ModuleStatusProvider` serves them to a module's own UI for activation queries.

The marker is the signal "this package has a bootstrapped process with the module's hooks installed." It is not a signal "the process you're currently in has hooks." That distinction is why `isModuleActive()` (this process) and `isModuleActiveAnywhere()` (any process) are separate questions under ShizuPosed.

---

## API surfaces

ShizuPosed speaks two API surfaces, and they report two different version numbers. That's deliberate — the numbers describe different things.

### Classic Xposed API — reported **101**

`XposedBridge.getXposedVersion()` returns **101**. The classic surface is 93/94/95/96/97/98/99/100/101. There is no classic 102.

- **93** — LSPosed base. `IXUnhook`, `isModuleActive`, provider-backed state queries.
- **94** — `hookAllMethods` returns `Set<XC_MethodHook.Unhook>`.
- **95** — `hookAllConstructors` returns the same.
- **96** — `MethodHookParam.isReturnEarly()` exposed.
- **97** — Instance field accessors on `XposedBridge`.
- **98** — Static field accessors, `XposedHelpers.getSurroundingThis`.
- **99** — Method invokers on `XposedBridge`.
- **100** — ShizuPosed extensions: `getShizuPosedApiMin()`, `getShizuPosedApiMax()`, `supportsApi(int)`, `isModuleActiveAnywhere(...)`.
- **101** — Reported version. The surface below 101 is unchanged.

A classic module that checks `getXposedVersion() >= 102` gets `false`. That's correct: there is no classic 102.

### `IXposedHookZygoteInit`

Modules that implement it get an `initZygote()` call at bootstrap, before `handleLoadPackage`. See [IXposedHookZygoteInit under ShizuPosed](#ixposedhookzygoteinit-under-shizuposed) for the timing detail.

### `IXposedHookDynamic` — supported

`IXposedHookDynamic` is a composite interface that extends `IXposedHookLoadPackage`, `IXposedHookZygoteInit`, and `IXposedHookInitPackageResources`, with no additional methods. It's a declaration of intent: "this module hooks dynamically-loaded classes."

A module that implements it is driven through the full classic lifecycle:

- `initZygote` fires once per bootstrapped process (same timing as a plain `IXposedHookZygoteInit` module).
- `handleLoadPackage` fires per package.
- `handleInitPackageResources` fires per package.

Dynamic loader detection is provided by `ClassLoadingBridge`, which hooks `ClassLoader.loadClass` and the construction of `DexClassLoader` / `PathClassLoader`. A module that uses `IXposedHookDynamic` and a `ClassLoadingBridge` listener can hook classes that don't exist at `handleLoadPackage` time (plugin APKs, dynamic feature modules, split APKs loaded via `DexClassLoader`).

`IXposedHookDynamic` was added to the classic shim in 1.4.9. A module that implements it previously failed at class load with `NoClassDefFoundError` — a silent failure, with no `handleLoadPackage` call and no visible error. It now links and runs.

### `IXposedHookCmdInit` — links, never fires

`IXposedHookCmdInit` is the classic interface for modules that want to run code in the `cmd` process. Under a zygote-timing framework (LSPosed, EdXposed), the framework forks `cmd` from zygote and calls `initCmdProcess()` before any command runs.

**Under ShizuPosed, `initCmdProcess()` is never called.** The `cmd` process runs under a protected UID (2000, shell) and cannot be bootstrapped by the framework — the shell UID cannot `setuid` to itself from a spawned process, and the process model the framework uses (`app_process` spawned as a target UID) does not apply to a system process.

The interface is shipped for **link compatibility**. A module that implements it and is compiled against upstream Xposed links against the class. Without the interface on ShizuPosed's shim, the module fails at class load with `NoClassDefFoundError`. Shipping the interface turns that silent failure into a diagnosable one: the module loads, `initCmdProcess` is not called, and the manager logs one line per such module:

```
Module <pkg> implements IXposedHookCmdInit —
initCmdProcess will NOT be called (cmd process is unreachable
under ShizuPosed); the interface is present for link
compatibility only
```

This is the same declared-but-never-invoked pattern used for `onSystemServerStarting`, `onHotReloading`, and `onHotReloaded` in the libxposed API. The callback exists, the framework doesn't call it, and the documentation says so.

A module whose activation depends on `initCmdProcess` will report "not activated" under ShizuPosed. That's the honest answer — there's no `cmd` process to hook.

### Active-state queries

`isModuleActive(pkg)` asks "is this module loaded into the process for `pkg`?" `isModuleActiveAnywhere(pkg)` asks "is this module loaded into any process ShizuPosed has bootstrapped?"

Under LSPosed the two questions have the same answer. Under ShizuPosed they differ. See [The marker](#the-marker) for why.

### libxposed API — reported **102**

`XposedInterface.getApiVersion()` returns **102**. The 102-only features are functional:

- `HookBuilder.setId(String)` — stores the id.
- `HookHandle.getId()` — returns the id, or `null` if none was set.
- `HookHandle.replaceHook(Hooker)` — swaps the live callback on the same `Executable` without reinstalling the native stub.

`onHotReloading` / `onHotReloaded` are declared on `XposedModuleInterface` for source compatibility but are **not invoked** — ShizuPosed doesn't reload module classloaders. Same pattern as `onSystemServerStarting`: the callback exists, the framework doesn't call it.

A 101 libxposed module that never touches a 102 entry point works unchanged. A 101 libxposed module that branches on `getApiVersion() >= 102` now takes the 102 branch — which is the correct answer for a framework that implements 102.

**Capability bits** (`getFrameworkProperties()`):

| Bit | Constant | Value | Meaning |
|---|---|---|---|
| system-server hooking | `PROP_CAP_SYSTEM` | **0** | Cannot hook `system_server`. |
| remote IPC | `PROP_CAP_REMOTE` | **0** | `getRemotePreferences` / `openRemoteFile` not implemented. |
| runtime API protection | `PROP_RT_API_PROTECTION` | **1** | XStealth's `ApiProtectionCheck` is active. |

**For classic modules that want to know about libxposed support**, `LSPosedManager.getLibxposedApiVersion()` returns 102, and `LSPosedManager.supportsLibxposedApi(int)` answers the boolean form. A classic module that wants to use libxposed-only features should check this accessor, not the classic version.

### The version matrix

| Path | Constant | Value |
|---|---|---|
| Classic Xposed | `XposedBridge.XPOSED_BRIDGE_VERSION` | 101 |
| Classic Xposed | `LSPosedManager.API_VERSION` | 101 |
| Classic Xposed | `LSPosedManager.getLibxposedApiVersion()` | 102 |
| libxposed | `ShizuPosedXposedInterface.REPORTED_API` | 102 |
| libxposed | `XposedInterface.LIB_API` | 102 |

---

## How module rows respond to touch

The Modules tab row supports four gestures:

| Gesture | Action |
|---|---|
| Tap the row body | Opens the scope editor |
| Tap the enable switch | Toggles the module |
| Tap the icon | Opens the detail sheet |
| Long-press the row | Opens the module's UI through ShizuPosed (explicit-launch path) |

Uninstall lives in the detail sheet.

### The API badge

Every third-party module row shows an API badge when the module declares one:

- **`API 93`**, **`API 94`**, … — classic Xposed API level.
- **`libxposed 101`** or **`libxposed 102`** — extends `io.github.libxposed.api.XposedModule`.

The badge reports **exactly what the module declared**. Whether the module will actually activate is answered by the detail sheet's **Run activation diagnostic**.

---

## Architecture

```
ShizuPosed Manager (APK)
        │
        │  Shizuku AIDL
        ▼
Shizuku (shell, UID 2000)
        │
        │  app_process
        ▼
Target process (bootstrapped by ShizuPosed)
  ├── XposedHook.main()          bootstrap entry
  ├── LibXposedDriver            libxposed API 102 driver
  ├── ShizuPosedXposedInterface  XposedInterface impl (reports 102)
  ├── LibXposedHookerAdapter     Hooker.intercept(Chain) → XC_MethodHook
  ├── ModuleClassLoader          chained loader (module + target + framework)
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
  │     ├── PackageCheck         PackageManager lookups + Binder
  │     ├── RunningProcessCheck  ActivityManager lookups
  │     ├── ApiProtectionCheck   reflection + baselines
  │     ├── MethodBaselineCheck  method-list freezing
  │     ├── XStealthNative       libc symbol interposition (libxstealth.so)
  │     ├── XStealthNativeNext   libc syscall stub patching (libxstealth_next.so)
  │     ├── SyskuNative          syscall() + getdents64() interposition (libsysku.so)
  │     ├── XStealthBridge       enumeration hiding (libxstealth_bridge.so)
  │     └── ThreadNameScrub      thread renaming
  └── markers written to         <shell-base>/hooked/

Native backends (loaded into the target process):
  ├── libpine.so                 Pine AUTO / REPLACEMENT
  ├── libamiru.so                Amiru per-method stub
  ├── libshizuposed.so           Native shared-dispatcher engine
  │     ├── nHookArtMethod       entry-point replacement
  │     ├── nRegisterCallback    callback stub + registry
  │     ├── emit_callback_stub   dispatcher stub
  │     ├── szp_dispatch_c       single dispatch entry (Java side)
  │     ├── shizuposed_lazy_install_artmethod
  │     └── shizuposed_unhook_artmethod
  └── libcallsite.so             interpreter-table deferred trigger
        ├── handler_check_impl   first-sighting handoff
        └── interpreter-slot patch (dispatch-table entry)

Manager process
  ├── ProcessMonitor             /proc scan + marker mirror refresh
  ├── MarkerCache                local mirror of <shell-base>/hooked/
  ├── IconResolver               LruCache for app icons
  ├── RuntimePrefs               scan interval + hook delay storage
  ├── SyskuController            state / heartbeat / diagnostics
  ├── ModuleStatusProvider       reads the mirror, never Shizuku
  ├── ModuleDiagnostics          activation-state report for a module
  ├── UpdateChecker              GitHub Releases query + notification
  ├── UpdateDownloader           APK download + size/ZIP verification
  ├── UpdateInstallActivity      download → confirm → pm install
  └── ManagerCloakFlow           Hide / Unhide flow
        ├── ManagerCloak         manifest patch + re-sign with per-install key
        ├── AxmlPatcher          in-place AXML string-pool rewrite
        └── ManagerMigrate       settings / DB / module keystore / cloak key transfer
```

**CallSite's path, in one line:** `libcallsite.so` patches ART's interpreter dispatch table; when an interpreted call hits a patched slot, the trampoline calls `handler_check_impl(ArtMethod*)`, which on first sighting hands off to `shizuposed_lazy_install_artmethod`. That call performs the entry-point replacement exactly as `nHookArtMethod` would. After that, the callback stub from `nRegisterCallback` runs, routing through `szp_dispatch_c` — the **same** dispatcher Native hooks use. There is exactly one dispatch path, not two.

---

## Module classloaders

When a module's `handleLoadPackage` runs, `lpparam.classLoader` is a **`ModuleClassLoader`** — a chained loader that resolves both the module's own classes and the target app's classes.

Delegation order:

1. Bootstrap classes (`java.*`, `javax.*`, `sun.*`, `dalvik.*`, `android.*`) → boot classloader.
2. Module classes → module's `DexClassLoader`.
3. Target classes → target's `PathClassLoader`.
4. Framework classes → `XposedHook`'s own loader.
5. Parent → boot classloader.

This is what makes both of these work through the same `lpparam.classLoader`:

- `XposedHelpers.findAndHookMethod("com.example.target.MainActivity", lpparam.classLoader, ...)` — target class resolves through step 3.
- `XposedHelpers.findAndHookMethod(MyActivity.class.getName(), lpparam.classLoader, ...)` — module class resolves through step 2 (self-hook pattern).

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

**Pine AUTO** is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. **CallSite** patches the ART interpreter dispatch table and defers the entry-point replacement until the first interpreted invocation of a hooked method — its purpose is detection-evasion, not a distinct dispatch mechanism. It hands off to the Native backend on first hit. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing.

### What each backend can actually hook

| Backend | Primitive args | Object args | Primitive returns | Object returns | `thisObject` | Constructors |
|---|---|---|---|---|---|---|
| Pine AUTO | yes | yes | yes | yes | yes | yes |
| Pine REPLACEMENT | yes | yes | yes | yes | yes | yes |
| Amiru | yes | **yes (1.4.9)** | yes | **yes (1.4.9)** | **yes (1.4.9)** | no |
| CallSite | **delegates to Native** | **delegates to Native** | **delegates to Native** | **delegates to Native** | **delegates to Native** | **delegates to Native** |
| Native (`libshizuposed`) | yes | **null** | yes | **not replaceable** | **null** | yes |
| Instrumentation | yes | yes | n/a | n/a | yes | n/a |
| Proxy | yes | yes | yes | yes | yes | no |

The **Native** row is the one that carries the important limitation. A module that hooks `void foo(String s)` and reads `s` receives `null` if the hook lands on Native. CallSite, because it hands off to Native's `nRegisterCallback` + `emit_callback_stub`, inherits that limitation — the deferred trigger changes *when* the hook is installed, not *what* the callback sees.

Amiru carries objects since 1.4.9 (the C-side `box_arg` passes `L` / `[` register values through as `jobject` handles; `AmiruDispatcher` reports results via `setAmiruResult` so the stub distinguishes "returned null" from "did nothing"). Constructors and after-hooks are still unsupported on Amiru.

### Pine's argument contract

Pine's `MethodHook` gives a `CallFrame` with `thisObject` and `args`, and `PineBackend` translates both directions against `MethodHookParam`. A `beforeHookedMethod` that writes `param.args[i] = ...` or `param.thisObject = ...` has its changes honored by the original invocation.

### CallSite backend status

CallSite is **functional**, but its role is narrower than "another complete hooking engine":

- **It is a deferred trigger, not a dispatcher.** CallSite does not own a callback registry and does not build its own Java-dispatch path. On install it only arms an intercept record and patches ART's interpreter dispatch table. On the first interpreted invocation of a hooked method, it calls `shizuposed_lazy_install_artmethod` — a direct-C export from `libshizuposed.so` — which performs the entry-point replacement that `nHookArtMethod` would have done eagerly. After that, the **Native backend owns the hook** and the callback runs through `szp_dispatch_c`, the single dispatcher Native hooks use.
- **It preserves its reason for existing.** The ArtMethod entry point is untouched at hook-install time. A pre-launch hook-detection check sees no modification. After the first interpreted call, the entry is replaced like any Native hook, so subsequent interpreted and JIT-compiled calls are also intercepted.
- **It requires the Native backend.** Without `libshizuposed.so` loaded (`NativeBridge.nIsAvailable()` false, or the `shizuposed_lazy_install_artmethod` symbol unresolved), CallSite refuses every install — there's no dispatch path without it. The Java side reports *"inactive (native bridge absent)"* in that case, and `CallSiteBackend.isAvailable()` returns false so the dispatcher moves on.
- **It inherits Native's callback contract.** Object arguments arrive as `null`, object returns are not replaceable, `thisObject` is `null`, constructors are hookable. See the Native row in the table above.
- **Constructors are not supported by CallSite.** `hookConstructor` returns `false` so the dispatcher tries another backend.
- **Idempotent handoff.** If the method is already hooked by another path when the first interpreted invocation fires, `shizuposed_lazy_install_artmethod` returns the existing trampoline rather than installing a second one. CallSite's branch then goes to the existing hook — no duplicate stub.
- **Graceful degradation when the bridge refuses.** If `shizuposed_lazy_install_artmethod` returns 0 (no layout, no registered callback, `MAX_ART_HOOKS` exhausted, allocation failure), the interpreter trampoline falls through to the original handler unchanged. CallSite behaves as if it weren't there.

The interpreter-table scanner, ARM64 decoder, validator, and patch machinery are all real and exercised at init; the trampoline's `handler_check` → `cbnz` → branch-to-bridge-or-original control flow is the v0.4.1 design.

### Native-hook safety gate

`libshizuposed.so`'s `nHookNative` refuses native hooks whose first instruction is PC-relative (ADRP, ADR, B, BL, LDR-literal, ADD/SUB-from-PC). The trampoline replays `src[0]` verbatim; a PC-relative first instruction would compute its target relative to the trampoline instead of the original function. Refusing with `JNI_FALSE` lets the dispatcher fall back to another backend instead of producing a wrong trampoline that crashes.

This gate applies to `nHookNative` (raw native address hooks). It does **not** apply to `nHookArtMethod` or `shizuposed_lazy_install_artmethod` — those replace the ArtMethod entry pointer, which is a data write, not a code patch.

### Disabling a backend

`HookDispatcher.setBackendEnabled(name, on)` toggles a backend by name. A disabled backend keeps its slot but is skipped during dispatch.

---

## Updates

ShizuPosed 1.4.9 ships an in-app update flow.

- **Background check** — `UpdateChecker.checkAsync` runs on app start, throttled to once per 12 hours. Posts a notification with an "Install update" action if a newer release exists.
- **Manual check** — "Check for updates" in Settings. Shows *"You're on the latest version (X)"* if no update, or an *"Update available"* dialog with a Download button if there is one.
- **Download** — `UpdateDownloader` fetches the APK to cache, verifies the size against GitHub's reported asset size, and verifies the ZIP magic (`PK`).
- **Install** — `UpdateInstallActivity` prompts for confirmation, stages the APK to a shell-readable path, and runs `pm install -r --user 0` via Shizuku.

**If the manager is cloaked**, the update flow refuses with a clear message: *"Unhide first, update, then re-Hide."*

**No signature verification on the downloaded APK.**

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

XStealth is organized in four layers, each independently toggleable:

- **Java-layer checks** — hook Android APIs inside the target's own process.
- **Binder-layer checks** — inspect `IBinder.transact` calls.
- **Native layer** — interpose libc symbols, patch libc syscall stubs, substitute plausible procfs values.
- **Bridge layer** — hide from native enumeration paths, rename threads, rewrite environment variables.

All layers fail open.

### Java-layer checks

- `DevOptionsCheck` / `AdbCheck` — hook every settings read path.
- `CachedValueCheck` — hooks `SharedPreferences` reads for a per-app allow-list.
- `SettingsFileCheck` — hooks `Runtime.exec` and `ProcessBuilder.start()`.
- `SystemPropertiesCheck` — hooks `android.os.SystemProperties.get`, `getBoolean`, `getInt`, `getLong`.
- `BuildCheck` — sets `Build.TAGS` to `release-keys`, `Build.TYPE` to `user`.
- `PropertyScrubCheck` — removes `shizuposed.*` entries from `System.getProperties()`. Hooks `Properties.list` / `Properties.store` so a target that dumps the property set to a stream gets the scrubbed view. Hooks `System.getenv` for completeness.
- `PackageCheck` — intercepts `PackageManager` lookup methods. Filters queries for the **cloaked package name** (read from `shizuposed.cloaked.package`). Hides signatures for cloaked and Shizuku packages: strips the signature flags from `PackageInfoFlags` / `ApplicationInfoFlags` overloads, post-filters `getInstalledPackages` / `getInstalledApplications`, and returns `SIGNATURE_NEITHER_SIGNED` from `checkSignatures`.
- `RunningProcessCheck` — filters `ActivityManager` process and service listings.
- `ApiProtectionCheck` — refuses `Class.forName`, `ClassLoader.loadClass`, `getDeclaredMethod`, `getDeclaredConstructor`, `getDeclaredField`, `getDeclaredClasses` for `de.robv.android.xposed.*`, `io.github.libxposed.api.*`, and `com.shizuposed.manager.*` when the caller isn't a loaded module.
- `MethodBaselineCheck` — freezes the full method list of every hooked class at capture time, filters `getDeclaredMethods` / `getMethods` against that baseline.

### Binder-layer checks

- `BinderProxy.transact` hook — every Binder call through the Java `IBinder` API.

### Native layer

- **`libxstealth.so`** — interposes file, process, and exec-family libc symbols.
- **`libxstealth_next.so`** — inline-patches libc syscall stubs.
- **`libsysku.so`** — syscall-level interposer. See [Sysku](#sysku--the-syscall-level-interposer).

### Bridge layer

- **`libxstealth_bridge.so`** — `dl_iterate_phdr` filtering, `/proc/self/fd` and `/proc/self/task` tracking, `readlink` substitutions, `getppid` trust gate, `getenv` substitutions.

### Legitimate process appearance

| Field | Real value | Substituted value |
|---|---|---|
| `/proc/self/exe` | resolved against the dex path | `/system/bin/app_process64` |
| `/proc/1/comm` | `init` | `zygote64` |
| `/proc/self/status` `Name:` | `app_process64` | `app_process64` |
| `/proc/self/status` `PPid:` | `app_process` pid | `1` |
| `/proc/self/cmdline` | full bootstrap args | one plausible argument |
| `/proc/self/stat` `comm` | `app_process64` | `app_process64` |
| `/proc/self/mountinfo` hidden lines | shell-base paths | plausible `/data/user/...` mounts |
| `/proc/self/cgroup` path field | shell-base paths | `/uid_<target_uid>/pid_<pid>` |
| `/proc/self/task/<tid>/comm` | `ShizuPosed-*`, `Xposed-*`, … | `Signal Catcher`, `JDWP`, `Jit thread pool`, … |
| `getenv("LD_PRELOAD")` | framework value | `NULL` (drop) |
| `getenv("SHIZUPOSED_*")` | framework value | plausible value |

**The rule is: substitute, don't blank.**

### What XStealth doesn't hide

Hardware attestation. Root-empowered inspection. Server-side cross-reference. Native reads of the settings database that bypass the exec family. Method-list reconstruction. Exotic reflection via `Unsafe` or JNI-level private methods. Kernel-level monitors. `Uid:`, SELinux context, and process start time.

### XStealth's practical ceiling

XStealth raises the bar significantly but cannot make a ShizuPosed process genuinely indistinguishable from a normal app. The kernel's task struct, the dynamic linker's internal state, cross-process observation by a privileged reader, and hardware attestation are all beyond the reach of a userspace library.

---

## Sysku — the syscall-level interposer

Sysku (`libsysku.so`) is an **optional** companion to `libxstealth.so` that closes detection paths the named libc interposers cannot reach.

### What it filters

- **`syscall()` interposition** — path-takers (`SYS_openat`, `SYS_openat2`, `SYS_newfstatat`, `SYS_fstatat`, `SYS_statx`, `SYS_faccessat`, `SYS_faccessat2`, `SYS_readlinkat`, `SYS_open`, `SYS_stat`, `SYS_lstat`, `SYS_access`, `SYS_readlink`), pid-takers (`SYS_ptrace`, `SYS_process_vm_readv`, `SYS_perf_event_open`, `SYS_kcmp`, `SYS_pidfd_open`), and flag-takers (`SYS_getppid`, `SYS_prctl`, `SYS_mincore`).
- **`getdents64()` interposition** — drops framework-owned entries from `/proc/self/fd`; keeps `/proc/self/task` entries (renamed by the bridge's `read()` interposer).

### Full mode and fallback mode

- **Full mode** — the Sysku ADB module's `service.sh` is running (heartbeat fresh). `syscall()` and `getdents64()` both filter.
- **Fallback mode** — the module isn't running. `syscall()` filters; `getdents64` passes through unchanged.

### Activation

Sysku is active when `state.json` says `enabled: true` and `libsysku.so` is present in `<shell-base>/libs/`. The Settings tab reports five states: `Disabled`, `Not installed`, `Enabled (lib failed to load)`, `Enabled (module not running)`, `Enabled (load failed)`, `Enabled`.

### Building `libsysku.so`

```
clang -shared -fPIC -O2 -Wall -Wextra -s \
    -target aarch64-linux-android29 \
    -o libsysku.so libsysku.c \
    -llog -ldl
```

Save it to `app/src/main/jniLibs/arm64-v8a/libsysku.so`. If you build without it, the build succeeds, but the Settings tab will report **`Enabled (lib failed to load)`**.

---

## Hide ShizuPosed app

The Settings tab has a **Hide ShizuPosed app** row. The flow:

1. Generates a random package name of the **same length** as the current one (22 chars for `com.shizuposed.manager`).
2. Reads the current APK, patches `<manifest package="...">` in place, and rewrites the `applicationId`-derived suffixes (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, `androidx-startup`, `fileprovider`, `shizuku`, `shizuku-watcher`, `status`). Class names (`MainActivity`, `ShizuPosedManagerApp`, etc.) are **not** rewritten.
3. Re-signs with the **per-install cloak signing key**.
4. Stages the patched APK to a shell-readable path, runs `pm install --user 0` (no `-r`), verifies via `am start --user 0 -n <newPkg>/<launcherClass>`, then uninstalls the old package.
5. Relaunches the manager under the new package.

The **per-install cloak signing key**: on the first Hide, a fresh RSA-4096 keypair is generated, self-signed, and stored in `files/cloak-install.p12`. Every subsequent cloak and every Unhide uses that key. Each cloaked install has a unique signature — a target app can no longer fingerprint one key to catch every ShizuPosed user. The key survives Unhide because `ManagerMigrate` carries `cloak-install.p12` across the cloak.

### The trade-off

- The cloak patches the `package` attribute and the `applicationId`-derived suffixes. Class names stay under the original package, because the dex still defines them there.
- The launcher label (`android:label` in the manifest) is a **literal** (`"ShizuPosed"`), not a `@string/app_name` reference, so the cloak can patch it to a random innocuous 10-character label. The same-length constraint applies: the original label is 10 chars, so replacements must also be 10 chars.
- The launcher icon (`@mipmap/ic_launcher`) is a resource reference, so it does not change per cloak.
- **Existing cloaked installs pick up the per-install key on their next Unhide.** The README recommends Unhide → re-Hide after upgrading to 1.4.9 to migrate cleanly.

### The revert path

The **Unhide ShizuPosed app** row patches the current APK's manifest back to the original package name and label, re-signs with the same per-install key, installs, and uninstalls the cloaked package.

---

## API surface — classic (version 101)

ShizuPosed reports classic Xposed API version **101**.

See [Classic Xposed API — reported 101](#classic-xposed-api--reported-101) for the version list, and the three interface subsections above for `IXposedHookZygoteInit`, `IXposedHookDynamic`, and `IXposedHookCmdInit`.

---

## API surface — libxposed (version 102)

ShizuPosed vendors the modern `io.github.libxposed.api.*` surface and reports **102**.

### What's functional

- `XposedInterface` — `hook(Executable)` → `HookBuilder` → `intercept(Hooker)` → `HookHandle`, `hookClassInitializer(Class)`, `deoptimize(Executable)`, `getInvoker(Method)` / `getInvoker(Constructor)`, `log(...)`, `getFrameworkName()` / `getFrameworkVersion()` / `getFrameworkVersionCode()` / `getFrameworkProperties()`, `getModuleApplicationInfo()`.
- `HookBuilder.setId(String)` / `HookHandle.getId()` — id storage and retrieval.
- `HookHandle.replaceHook(Hooker)` — swaps the live callback without reinstalling the stub.
- `XposedModule` / `XposedModuleInterface` — `onModuleLoaded`, `onPackageLoaded` (Android 10+), `onPackageReady`, `onSystemServerStarting` (never called), `onHotReloading` / `onHotReloaded` (declared, never called).
- `Chain` — `getExecutable()`, `getThisObject()`, `getArgs()`, `getArg(i)`, `proceed()`, `proceed(Object[])`, `proceedWith(Object)`, `proceedWith(Object, Object[])`.

### What's not

- `getRemotePreferences` / `openRemoteFile` — not implemented.
- Hot reload — declared, never invoked.
- `system_server` hooking — `onSystemServerStarting` is never called.
- `deoptimize` — returns `false`. Hook installation deopts implicitly.

### Hooking from a libxposed module

```java
public class MainModule extends XposedModule {
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            Method m = param.getDefaultClassLoader()
                .loadClass("com.example.target.MainActivity")
                .getDeclaredMethod("onCreate", Bundle.class);
            hook(m).setId("main-oncreate").intercept(chain -> {
                Object r = chain.proceed();
                // ... post-processing ...
                return r;
            });
        } catch (Throwable t) {
            log(Log.ERROR, "example", "onPackageLoaded failed: " + t);
        }
    }
}
```

`replaceHook` swaps the live callback without reinstalling the stub. It is not atomic with respect to an in-flight invocation: the current call finishes on the old hooker, the next call uses the new one.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

1. `assets/xposed_init` — the canonical marker.
2. `assets/xposed_module` — an older convention.
3. `META-INF/xposed/module.prop` — EdXposed-era.

### How ShizuPosed decides which API a module speaks

- If the entry class extends `io.github.libxposed.api.XposedModule`, the module is driven through the **libxposed lifecycle**.
- Otherwise, if the entry class implements a classic `de.robv.android.xposed.IXposedHook*` interface, the module is driven through the **classic lifecycle**.
- Otherwise, a reflective scan for a `handleLoadPackage` method is the last resort.

The classic check uses `instanceof IXposedHookLoadPackage`, which matches a plain `IXposedHookLoadPackage` module and a module that implements the `IXposedHookDynamic` composite (because the composite extends `IXposedHookLoadPackage`). The two are driven through the same `handleLoadPackage` path; the composite is a declaration of intent that says "this module hooks dynamically-loaded classes," and `ClassLoadingBridge` provides the loader detection.

A module that implements `IXposedHookCmdInit` also lands here if it implements any of the other classic interfaces. If it implements *only* `IXposedHookCmdInit`, it has no `handleLoadPackage` to call — the module loads, but no hooks are installed. The manager logs the "implements IXposedHookCmdInit" line so the activation diagnostic can explain why.

### Recommended scope

Modules can declare which apps they're intended for by shipping an `assets/scope.list` file.

### The scope editor

The scope editor lists the apps a module can be applied to. Protected UIDs are hidden. The manager itself is hidden. System packages with no UI and no running process are hidden.

---

## Activation state — how "Activated" actually works

A module UI shows "Activated" when one of three things is true:

- **Its own hook is installed in this process.** Under ShizuPosed, this requires the module's UI to have been launched **through** ShizuPosed (the explicit-launch path), or the module's own app to be bootstrapped by the monitor.
- **`isModuleActive(getPackageName())` returns true.**
- **`isModuleActiveAnywhere(getPackageName())` returns true.**

### The Android 11+ package visibility requirement

A module that queries the status provider from its own UI must declare the provider's authority in its own `AndroidManifest.xml`:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

### When a module still reports "not activated"

The module detail sheet has a **Run activation diagnostic**. It reports whether the module is in the manager's module list, whether its cached dex is present, how many apps it's scoped to, whether any marker lists it, and the `<queries>` declaration it needs on Android 11+.

---

## Module development

ShizuPosed supports two module APIs.

### Classic API (`de.robv.android.xposed.*`)

```java
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
```

### libxposed API (`io.github.libxposed.api.*`, API 102)

```java
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
                return r;
            });
        } catch (Throwable t) {
            log(Log.ERROR, "example", "onPackageLoaded failed: " + t);
        }
    }
}
```

#### What each callback sees

| Callback | When it fires | What's available |
|---|---|---|
| `onModuleLoaded` | After class load, before any package work | `ModuleLoadedParam.getProcessName()`, `isSystemServer()` (always `false`) |
| `onPackageLoaded` | During bootstrap, after classloader resolved, before `Application.onCreate` | `PackageLoadedParam.getPackageName()`, `getApplicationInfo()`, `getDefaultClassLoader()`, `isFirstPackage()` |
| `onPackageReady` | After `Application.onCreate`, before any activity | Everything from `onPackageLoaded`, plus `getClassLoader()` and `getAppComponentFactory()` |
| `onSystemServerStarting` | Never | — |
| `onHotReloading` | Never | — |
| `onHotReloaded` | Never | — |

### Using the lifecycle bridges

```java
ClassLoadingBridge.registerClassLoadListener((name, loader) -> {
    if ("com.example.target.SecretDetector".equals(name)) {
        XposedHelpers.findAndHookMethod(name, loader, "check", ...);
    }
});

ApplicationThreadBackend.registerListener((methodName, args) -> {
    if ("scheduleCreateService".equals(methodName)) {
        // ...
    }
    return true;
});
```

### Reporting state in a module UI

```java
boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
boolean active  = XposedBridge.isModuleActive(getPackageName());
boolean loaded  = XposedBridge.isModuleActiveAnywhere(getPackageName());
String[] scope  = XposedBridge.getModuleScope(getPackageName());
```

### Resource replacement

Classic API:

```java
public class Res implements IXposedHookInitPackageResources {
    @Override
    public void handleInitPackageResources(
            XC_InitPackageResources.InitPackageResourcesParam resparam) {
        resparam.res.setReplacement(R.string.some_string, "replacement");
    }
}
```

libxposed API 102 does not include a resource-replacement callback.

### Layout hooks

Classic API:

```java
resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
    @Override
    public void handleLayoutInflated(LayoutInflatedParam param) {
        // param.view, param.resId, param.resName are set
    }
});
```

Layout hooks are classic-only in API 102.

### Writing a hook that works on every backend

Pine honors every argument mutation and every return value. Constructors are hookable on Pine, Native, and CallSite. Amiru and Proxy do not support constructor hooks.

Object arguments are the caveat: Native passes them as `null`, and because CallSite hands off to Native's callback stub, CallSite inherits the same limitation. If your hook reads `String` / `Object` / array arguments, Pine and Amiru (since 1.4.9) are the backends that will actually carry them. CallSite is **not** a workaround for Native's null-object-args — it's a detection-evasion trigger that defers the same Native hook.

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is **Shizuku-Next** by rushiranpise at `github.com/rushiranpise/Shizuku-Next`. About 200 MB free storage. No root required.

For building the app: JDK 21 (with a Gradle toolchain), Gradle 9.8, Android SDK 37. Native libraries rebuild with `clang` alone on Termux. Building with the Android NDK (r25+) works and is what CI uses. BouncyCastle `bcprov-jdk18on:1.78.1` and `bcpkix-jdk18on:1.78.1` are required (used by `ManagerCloak.selfSign` for per-install key generation).

---

## Installation

Install Shizuku (Shizuku-Next recommended), then start it via ADB or via the Shizuku app's own start flow.

Download the APK from the Releases page:

- `app-release.apk` — the single build.

Install it:

```
adb install -r app-release.apk
```

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab. Edit the module's scope.

**Then what?** It depends on the module:

- **Headless module** (hooks a target app): open the target app normally. The monitor handles it. See [The headless path](#the-headless-path-the-normal-workflow).
- **Self-hook module** (module's own UI needs its hooks live): use **Launch App under ShizuPosed** for the module's own app.
- **App that aggressively restarts its process**: use **Launch App under ShizuPosed** for that app.

### Changing the installed package name

If a target app checks for `com.shizuposed.manager` by name, use **Settings → Hide ShizuPosed app**. The manager generates a random same-length name, patches the current APK's manifest, re-signs with the per-install key, installs via Shizuku, and uninstalls the old package. **Unhide ShizuPosed app** reverses it, re-signing with the same per-install key.

---

## The manager

Five tabs.

**Home** shows the status card, the counter tiles, and the Framework Info card (which shows both the classic API level and the libxposed API level — 101 and 102).

**Modules** lists installed modules with XStealth always at the top.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has runtime toggles, scan interval and hook delay sliders, the Sysku toggle and status line, the Maintenance section (including **Hide ShizuPosed app**, **Unhide ShizuPosed app**, **Clear logs**, **Clean all caches**), **Check for updates**, and config export.

### XStealth detail sheet

Fifteen toggles, organized by layer:

- **Master toggles:** Enable XStealth, Enable XStealth Next, Use bridge.
- **Settings-based checks:** Hide Developer Options, Hide ADB enabled.
- **System properties and Build fields:** Hide system properties, Sanitize Build fields.
- **Framework properties and threads:** Scrub framework properties, Rename framework threads.
- **Package and process:** Hide Shizuku package, Hide ShizuPosed package, Hide running processes, Hide `/proc` entries.
- **API protection and dex:** Protect Xposed API calls, Optimize module dex.

---

## Provenance and verification

ShizuPosed is built from sources in this repository. The APK is reproducible from a clean checkout with the documented toolchain.

**Signed builds.** The released APK is signed with the project's release key.

**Native library provenance.** Each `.so` is built from the `.c` file with the same name in the `native/` directory.

**What to look for in a release.** The release notes list the NDK version, SDK version, Gradle and JDK versions, and a SHA-256 of each native library. Pine's SHA-256 should match the upstream canyie/pine release — if it doesn't, reject the build.

**Vendored libxposed API.** The `io.github.libxposed.api.*` classes in `XposedHook.dex` are vendored from the upstream libxposed API 102 definitions.

**Per-install cloak key.** `files/cloak-install.p12` is generated on the first cloak and stored in the manager's data directory. It is **not** included in the APK. It is owner-readable, protected by the app's UID sandbox. Every cloaked install has a unique signature.

---

## Building

### The app

```
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosed
gradle clean :app:assembleRelease
```

The single-flavor release is the only build. `checkNoOldPackageLeak` runs as a `finalizedBy` on `assembleRelease` and fails if `com.shizuposed.manager` appears as a literal in `classes.dex` or `resources.arsc`.

### The native libraries

The six native libraries (`libshizuposed.so`, `libamiru.so`, `libcallsite.so`, `libxstealth.so`, `libxstealth_next.so`, `libxstealth_bridge.so`) and the optional `libsysku.so` build with the Android NDK. Build for API 29 or higher.

`libcallsite.so` (v0.4.1) resolves `shizuposed_lazy_install_artmethod` / `shizuposed_unhook_artmethod` from `libshizuposed.so` (v1.2.3) via `dlsym(RTLD_DEFAULT, ...)` on first use. If `libshizuposed.so` is loaded after `libcallsite.so`, the resolution is retried automatically — order of loading does not matter for correctness.

### The build checks

`app/build.gradle` includes seven verification tasks: `checkNativeShim` (required), `checkCallSite`, `checkAmiru`, `checkXStealth`, `checkXStealthNext`, `checkXStealthBridge`, `checkSysku` (all optional, warn-only). Skip all seven with `-PskipNativeCheck=true`.

### Configuration cache

The build is configuration-cache-compatible with Gradle 9.8.

---

## Reading the launch log

Every routed launch writes stderr and stdout from `app_process` to `<shell-base>/launch.log`:

```
cat /data/user/0/com.android.shell/files/.syscall_cache/launch.log
```

`logcat -s PineBackend` shows whether Pine initialized and every hook it installed or declined. `logcat -s ShizuPosedAmiru` shows Amiru's slot activity and layout probe result. `logcat -s CallSite` shows the interpreter-table scan, `native bridge resolved`, `armed ArtMethod`, and on first interpreted hit, `lazy_install: art_method ... entry -> stub ...`.

Each bootstrap also writes a marker to `<shell-base>/hooked/<pkg>.json` when it completes. A spawn-only launch that produced neither a marker nor a live process reports failure — see [Spawn-only success semantics](#spawn-only-success-semantics).

For the verification ledger, the following log lines are the ones to look for:

- `Bootstrap ready for <pkg> (marker at Nms, waited Mms)` — the child survived and wrote its marker.
- `Bootstrap process up for <pkg> (process at Nms, no marker after Mms)` — the child survived the wait but did not write a marker; the app likely has no applicable modules.
- `Bootstrap not detected for <pkg> after Nms` — the child died; check `launch.log` for the ART abort reason.
- `AM START created a second process for <pkg> (before=N, after=M). AMS did not route into the bootstrapped process` — the launcher's UI is in a zygote process without hooks. This is the AMS `ProcessRecord` gap in observable form.
- `Spawn-only launch for <pkg> produced no marker and no live process — reporting failure` — the false-success path that older builds hit. Now a hard failure.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. Cross-process hooks aren't possible. XML-level resource replacement isn't supported. No hot reload. ROMs that block `app_process` can't run the framework.

**Protected UIDs are unreachable.** The scope editor hides them.

**Zygote-timing APIs.** `initZygote()` fires once per bootstrapped process, not once per zygote fork. `onSystemServerStarting` and `onHotReloading` / `onHotReloaded` are never called.

**The process is not a zygote fork.** After `setuid`, its parent is `app_process`, not `zygote`. XStealth's appearance layer scrubs the userspace-visible fields; the kernel task struct is unchanged. `Uid:`, SELinux context, and process start time are unchanged.

**The process is not registered with AMS.** AMS tracks app processes in `ProcessRecord`; a shell-spawned process is not in that list. The code's post-`am start` diagnostic detects when the UI ends up in a different (zygote) process than the bootstrapped one and logs it. Whether AMS actively reaps an unregistered bootstrapped process before hooks fire is the largest open question in the [verification ledger](#verification-status).

**The appearance layer is trust-gated.** Framework threads that call `getppid()` see the real parent PID.

**The appearance layer requires `libxstealth`'s exports.**

**Monitor-triggered injection doesn't attach to the detected pid.** It spawns a fresh `app_process` for the same package; the `pid` argument is used for logging only. For most headless modules this is the normal workflow and works; for apps that aggressively restart their process, it may not produce a usable hooked instance. See [How a module gets into a target process](#how-a-module-gets-into-a-target-process).

**Object arguments on the Native backend.** Native passes object arguments to Java callbacks as `null`, and does not accept object return values. `thisObject` is also `null`. Pine, Amiru (since 1.4.9), Instrumentation, and Proxy handle objects correctly.

**CallSite inherits the Native callback contract.** Because CallSite is a deferred trigger in front of the Native backend — not an independent dispatch path — object arguments arrive as `null`, object returns are not replaceable, and `thisObject` is `null`. CallSite's reason for existing is detection-evasion (the entry point is untouched until first interpreted call), not a different callback contract.

**CallSite requires the Native backend.** Without `libshizuposed.so` loaded and `shizuposed_lazy_install_artmethod` resolvable, CallSite refuses every install and `isAvailable()` returns false. There is no separate dispatch path; this is the design.

**CallSite does not support constructors.** `hookConstructor` returns `false` so the dispatcher tries another backend. Constructors are hookable via Pine and Native (not via CallSite).

**CallSite's first-interpreted-call trigger is, by definition, first-call.** Between hook install and first interpreted invocation, the entry point is untouched — that's the point — but it also means a method that is never interpreted (e.g., already JIT-compiled before the hook was armed) is not intercepted by CallSite. When that happens, `handler_check_impl` is never invoked, `shizuposed_lazy_install_artmethod` is never called, and no hook fires. This is the same "interpreted execution only" ceiling as before — but the ceiling is on *trigger delivery*, not on *hook dispatch*: once fired, the hook covers JIT-compiled calls too, because the entry-point replacement is what Native owns.

**Amiru doesn't support constructors or after-hooks.** Constructor hooks fall through to the next backend; after-hooks are not implemented in the stub.

**Amiru object returns use `System.identityHashCode` as the handle token.** Correct for the vast majority of cases; a hash collision would return the wrong object. A proper JNI local-ref fix is a follow-up.

**`replaceHook` is not atomic with an in-flight invocation.** The current call finishes on the old hooker; the next call uses the new one.

**Classic API reports 101.** A module that hardcodes `getXposedVersion() == 100` may fail; the fix is `>= 100`.

**libxposed API reports 102, not 103.**

**Native hooks refuse non-relocatable first instructions.** A native hook whose first instruction is PC-relative is refused with `JNI_FALSE` so the dispatcher falls back.

**`IXposedHookCmdInit` links but never fires.** The `cmd` process is a protected-UID system process and cannot be bootstrapped. A module that implements `IXposedHookCmdInit` loads (no `NoClassDefFoundError`), but its `initCmdProcess` callback is never invoked. Modules whose activation depends on it will report "not activated." This is the same declared-but-never-invoked pattern used for `onSystemServerStarting` and `onHotReloading`.

**`IXposedHookDynamic` is supported.** The composite extends `IXposedHookLoadPackage`, `IXposedHookZygoteInit`, and `IXposedHookInitPackageResources`; all three lifecycle callbacks fire. Dynamic loader detection is provided by `ClassLoadingBridge`. No limitation beyond what already applies to a plain `IXposedHookLoadPackage` module.

**Cloaked managers can't install updates in place.** Unhide → update → re-Hide is the path.

**No signature verification on the downloaded update APK.**