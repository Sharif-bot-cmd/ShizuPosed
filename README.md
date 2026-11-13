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

## Why `app_process`

Without root, the injection primitives on Android 10+ are narrow. `ptrace` is blocked by SELinux for non-root UIDs. `LD_PRELOAD` needs a writable, executable path the target will load from, and app data directories are mounted `noexec`. Zygote fork requires being inside zygote, which means root or being the ROM. `Runtime.exec` runs as the caller's UID, not the target's. `am instrument` gives you an `Instrumentation` handle only after `Application.onCreate` — too late for bootstrap hooks. ContentProvider hijack works only if the target already declares a provider you can take over.

`app_process` is the one that works. It's a platform binary that exists on every Android device. Shizuku can invoke it as shell (UID 2000). It starts a Java runtime, so you can load arbitrary dex into it. And Shizuku can pass the target package and UID as arguments.

That's the entire mechanism. Everything else in ShizuPosed is a consequence of it.

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

These differences are what XStealth's appearance layer and `libxstealth_bridge`'s `/proc` scrubbing are for. They are userspace scrubs, not lineage changes, and cannot close every gap.

**If `setuid` fails on a ROM**, the process runs as UID 2000 and cannot access the target's data at all. `XposedHook` logs the failure clearly, and the launch will typically fail at `handleBindApplication`.

---

## The timing model

LSPosed is a zygote-timing framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a bootstrap-timing framework: it is `app_process`-launched into the target, installs hooks, then drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

Here's what ShizuPosed can reach in bootstrap mode:

- `Application.attachBaseContext` — hookable
- `Application.onCreate` — hookable
- `ContentProvider.onCreate` — hookable, provider classes pre-loaded
- `Activity.onCreate` — hookable via Pine, Amiru, Native, or Instrumentation
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
| Long-press the row | Opens the module's UI through ShizuPosed |

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
Target process
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
  │     ├── XStealthNative       libc symbol interposition (libxstealth.so)
  │     ├── XStealthNativeNext   libc syscall stub patching (libxstealth_next.so)
  │     ├── SyskuNative          syscall() + getdents64() interposition (libsysku.so)
  │     ├── XStealthBridge       enumeration hiding (libxstealth_bridge.so)
  │     └── ThreadNameScrub      thread renaming
  └── markers written to         <shell-base>/hooked/

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

## The two injection paths

ShizuPosed reaches a target in one of two ways. Both use the same `app_process` primitive; they differ in whether the target's UI is opened after the spawn.

### Routed launch (user-triggered)

The **Launch App under ShizuPosed** button, the module row's long-press, and the detail sheet's "Open module app" entry all go through this path:

1. Boot the target process with hooks installed:
   ```
   unset CLASSPATH; unset BOOTCLASSPATH;
   <prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]
   ```
2. Open the target's UI in that already-running process:
   ```
   am start --user current -n <resolved-component>
   ```

### Monitor-triggered injection

`ProcessMonitor` scans `/proc` on a timer. When it sees a newly-started app process that at least one enabled module scopes, it calls `injectProcess`, which performs a **spawn-only** routed launch. The monitor path is silent.

Neither path can inject into a process someone else started.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

**Pine AUTO** is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table (non-functional; every install is refused — see [CallSite backend status](#callsite-backend-status)). Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing.

### What each backend can actually hook

| Backend | Primitive args | Object args | Primitive returns | Object returns | `thisObject` | Constructors |
|---|---|---|---|---|---|---|
| Pine AUTO | yes | yes | yes | yes | yes | yes |
| Pine REPLACEMENT | yes | yes | yes | yes | yes | yes |
| Amiru | yes | **yes (1.4.9)** | yes | **yes (1.4.9)** | **yes (1.4.9)** | no |
| CallSite | n/a | n/a | n/a | n/a | n/a | n/a |
| Native (`libshizuposed`) | yes | **null** | yes | **not replaceable** | **null** | yes |
| Instrumentation | yes | yes | n/a | n/a | yes | n/a |
| Proxy | yes | yes | yes | yes | yes | no |

The **Native** row is the one that carries the important limitation. A module that hooks `void foo(String s)` and reads `s` receives `null` if the hook lands on Native.

Amiru carries objects since 1.4.9 (the C-side `box_arg` passes `L` / `[` register values through as `jobject` handles; `AmiruDispatcher` reports results via `setAmiruResult` so the stub distinguishes "returned null" from "did nothing"). Constructors and after-hooks are still unsupported on Amiru.

### Pine's argument contract

Pine's `MethodHook` gives a `CallFrame` with `thisObject` and `args`, and `PineBackend` translates both directions against `MethodHookParam`. A `beforeHookedMethod` that writes `param.args[i] = ...` or `param.thisObject = ...` has its changes honored by the original invocation.

### CallSite backend status

CallSite is present in the dispatcher chain, but it is **not functional**. `install_hook_impl` refuses every install with a logged reason. The interpreter-table scanner, decoder, and patch machinery are preserved as scaffolding. Until a future implementation actually invokes the Java callback, the backend refuses installs.

### Native-hook safety gate

`libshizuposed.so`'s `nHookNative` refuses native hooks whose first instruction is PC-relative (ADRP, ADR, B, BL, LDR-literal, ADD/SUB-from-PC). The trampoline replays `src[0]` verbatim; a PC-relative first instruction would compute its target relative to the trampoline instead of the original function. Refusing with `JNI_FALSE` lets the dispatcher fall back to another backend instead of producing a wrong trampoline that crashes.

### Disabling a backend

`HookDispatcher.setBackendEnabled(name, on)` toggles a backend by name. A disabled backend keeps its slot but is skipped during dispatch.

---

## Updates

ShizuPosed 1.4.9 ships an in-app update flow.

- **Background check** — `UpdateChecker.checkAsync` runs on app start, throttled to once per 12 hours. Posts a notification with an "Install update" action if a newer release exists.
- **Manual check** — "Check for updates" in Settings. Shows *"You're on the latest version (X)"* if no update, or an *"Update available"* dialog with a Download button if there is one.
- **Download** — `UpdateDownloader` fetches the APK to cache, verifies the size against GitHub's reported asset size, and verifies the ZIP magic (`PK`) so a captive-portal HTML page served in place of the APK is rejected before `pm install` runs.
- **Install** — `UpdateInstallActivity` prompts for confirmation (manual, not silent), stages the APK to a shell-readable path, and runs `pm install -r --user 0` via Shizuku. The install replaces the current package, so the user keeps their data — including the per-install cloak key at `files/cloak-install.p12`.

**If the manager is cloaked**, the update flow refuses with a clear message: *"Unhide first, update, then re-Hide."* The downloaded APK's manifest declares `com.shizuposed.manager`, not the cloaked package name; installing it with `-r` would install a second ShizuPosed alongside the cloak. A seamless in-place patch of a downloaded APK is a follow-up.

**No signature verification on the downloaded APK.** The size and ZIP magic checks catch truncation and HTML-instead-of-APK, but not a malicious APK served from a compromised GitHub response.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

XStealth is organized in four layers, each independently toggleable:

- **Java-layer checks** — hook Android APIs inside the target's own process.
- **Binder-layer checks** — inspect `IBinder.transact` calls before they leave the process.
- **Native layer** — interpose libc symbols, patch libc syscall stubs, and substitute plausible values into procfs fields.
- **Bridge layer** — hide from native enumeration paths, and rename threads / rewrite environment variables to look like an ordinary app process.

All layers fail open.

### Java-layer checks

**Developer Options and ADB.** `DevOptionsCheck` and `AdbCheck` hook every settings read path.

**Cached detection values.** `CachedValueCheck` hooks `SharedPreferences` reads for a per-app allow-list of keys.

**Subprocess reads of the settings XML.** `SettingsFileCheck` hooks `Runtime.exec` (all five overloads) and `ProcessBuilder.start()`.

**System properties.** `SystemPropertiesCheck` hooks `android.os.SystemProperties.get`, `getBoolean`, `getInt`, `getLong`.

**Build fields.** `BuildCheck` sets `Build.TAGS` to `release-keys`, `Build.TYPE` to `user`.

**Framework properties.** `PropertyScrubCheck` removes `shizuposed.*` entries from `System.getProperties()`.

**Shizuku and ShizuPosed packages.** `PackageCheck` intercepts the standard `PackageManager` lookup methods. Since 1.4.9, it also filters queries for the **cloaked package name** (read from the `shizuposed.cloaked.package` system property set by the cloak flow), so a target app can't discover the cloaked install by enumerating installed packages.

**Running processes.** Filters `ActivityManager` process and service listings.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` and `io.github.libxposed.api.*` when the caller isn't a loaded module.

### Binder-layer checks

**`BinderProxy.transact` hook.** Every Binder call the target makes through the Java `IBinder` API goes through `BinderProxy.transact`.

### Native layer

**`libxstealth.so`** — interposes the file, process, and exec-family libc symbols, and substitutes plausible values into procfs fields.

**`libxstealth_next.so`** — inline-patches libc syscall stubs.

**`libsysku.so`** — the syscall-level interposer. See [Sysku](#sysku--the-syscall-level-interposer). Also applies the appearance substitutions on the raw-syscall path (`SYS_readlinkat`, `SYS_readlink`), and trust-gates `SYS_getppid`.

### Bridge layer

**`libxstealth_bridge.so`** — enumeration-hiding and appearance library.

- **`dl_iterate_phdr`** — filters framework library names.
- **`opendir` / `readdir` / `closedir`** — tracks `/proc/self/fd` and `/proc/self/task`.
- **`readlink`** — substitutes `/proc/self/exe` → `/system/bin/app_process64` and `/proc/1/comm` → `zygote64`.
- **`read`** — refuses reads of hidden processes, renames `/proc/self/task/<tid>/comm`.
- **`getppid`** — trust-gated. Returns `1` for untrusted callers, the real ppid for framework callers.
- **`getenv`** — substitutes plausible values for framework environment variables, drops `LD_PRELOAD` and `JAVA_TOOL_OPTIONS`.

### Legitimate process appearance

A detector's cheapest "is this process legitimate?" checks read procfs fields that reveal the bootstrap's parentage. The appearance layer substitutes plausible values:

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

**The rule is: substitute, don't blank.** A blanked field is a stronger detection signal than a plausible wrong one.

### What XStealth doesn't hide

Hardware attestation. Root-empowered inspection. Server-side cross-reference. Native reads of the settings database that bypass the exec family. Method-list reconstruction. Exotic reflection via `Unsafe` or JNI-level private methods. Kernel-level monitors. `Uid:`, SELinux context, and process start time — those are kernel state, and the appearance layer does not touch them.

### XStealth's practical ceiling

XStealth raises the bar significantly but it cannot make a ShizuPosed process genuinely indistinguishable from a normal app. The kernel's task struct, the dynamic linker's internal state, cross-process observation by a privileged reader, and hardware attestation are all beyond the reach of a userspace library.

---

## Sysku — the syscall-level interposer

Sysku (`libsysku.so`) is an **optional** companion to `libxstealth.so` that closes detection paths the named libc interposers cannot reach.

### What it filters

**`syscall()` interposition.** A caller that bypasses the named wrapper — `syscall(SYS_openat, ...)` instead of `open(...)` — goes through libc's `syscall()` trampoline. `libsysku.so` filters the raw syscall number for path-takers (`SYS_openat`, `SYS_openat2`, `SYS_newfstatat`, `SYS_fstatat`, `SYS_statx`, `SYS_faccessat`, `SYS_faccessat2`, `SYS_readlinkat`, `SYS_open`, `SYS_stat`, `SYS_lstat`, `SYS_access`, `SYS_readlink`), pid-takers (`SYS_ptrace`, `SYS_process_vm_readv`, `SYS_perf_event_open`, `SYS_kcmp`, `SYS_pidfd_open`), and flag-takers (`SYS_getppid`, `SYS_prctl`, `SYS_mincore`).

**`getdents64()` interposition.** A detector that opens `/proc/self/fd` as a directory and counts descriptors sees ShizuPosed's fds regardless of what `libxstealth.so` does to individual reads. `libsysku.so` drops any entry whose name matches a framework-owned pattern. For `/proc/self/task`, entries are **kept** — the rename is handled by `libxstealth_bridge`'s `read()` interposer, so the thread count stays correct.

### Full mode and fallback mode

Sysku runs in one of two modes:

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

1. Generates a random package name of the **same length** as the current one (22 chars for `com.shizuposed.manager`). Same length is a string-pool simplicity constraint, not a security feature: it lets the manifest patch avoid rebuilding the AXML string pool.
2. Reads the current APK, patches `<manifest package="...">` in place, and rewrites the `applicationId`-derived suffixes (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, `androidx-startup`, `fileprovider`, `shizuku`, `shizuku-watcher`, `status`) so the cloaked package doesn't collide with the original on install. Class names (`MainActivity`, `ShizuPosedManagerApp`, etc.) are **not** rewritten — the dex still defines them under the original name.
3. Re-signs with the **per-install cloak signing key** (see below).
4. Stages the patched APK to a shell-readable path, runs `pm install --user 0` (no `-r`), verifies via `am start --user 0 -n <newPkg>/<launcherClass>`, then uninstalls the old package.
5. Relaunches the manager under the new package.

The **per-install cloak signing key**: on the first Hide, a fresh RSA-2048 keypair is generated, self-signed, and stored in `files/cloak-install.p12`. Every subsequent cloak and every Unhide uses that key. Each cloaked install has a unique signature — a target app can no longer fingerprint one key to catch every ShizuPosed user. The key survives Unhide because `ManagerMigrate` carries `cloak-install.p12` across the cloak.

### The trade-off

The cloak patches the `package` attribute and the `applicationId`-derived suffixes. Class names in the manifest stay under the original package, because the dex still defines them there. Renaming activities or services in the source manifest would require patching the dex as well.

The launcher label (`@string/app_name`) lives in `resources.arsc`, not the manifest, so it does not change per cloak. If you want an innocuous label, set it at build time.

**Existing cloaked installs pick up the per-install key on their next Unhide.** The README recommends Unhide → re-Hide after upgrading to 1.4.9 to migrate cleanly.

### The revert path

The **Unhide ShizuPosed app** row patches the current APK's manifest back to the original package name, re-signs it with the same per-install key, installs, and uninstalls the cloaked package. Both directions carry a migrate zip — settings, module DB, module keystore, cloak key — that the new package reads on first launch.

---

## API surface — classic (version 101)

ShizuPosed reports classic Xposed API version **101**.

### `IXposedHookZygoteInit`

Modules that implement it get an `initZygote()` call at bootstrap, before `handleLoadPackage`. See [IXposedHookZygoteInit under ShizuPosed](#ixposedhookzygoteinit-under-shizuposed) for the timing detail.

### Active-state queries

`isModuleActive(pkg)` asks "is this module loaded into the process for `pkg`?" `isModuleActiveAnywhere(pkg)` asks "is this module loaded into any process ShizuPosed has bootstrapped?"

Under LSPosed the two questions have the same answer. Under ShizuPosed they differ.

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
4. `assets/native_init` — a weak signal.

### How ShizuPosed decides which API a module speaks

- If the entry class extends `io.github.libxposed.api.XposedModule`, the module is driven through the **libxposed lifecycle**.
- Otherwise, if the entry class implements a classic `de.robv.android.xposed.IXposedHook*` interface, the module is driven through the **classic lifecycle**.
- Otherwise, a reflective scan for a `handleLoadPackage` method is the last resort.

### Recommended scope

Modules can declare which apps they're intended for by shipping an `assets/scope.list` file.

### The scope editor

The scope editor lists the apps a module can be applied to. Protected UIDs are hidden. The manager itself is hidden. System packages with no UI and no running process are hidden.

---

## Activation state — how "Activated" actually works

A module UI shows "Activated" when one of three things is true:

- **Its own hook is installed in this process.** Under ShizuPosed, this requires the module's UI to have been launched **through** ShizuPosed.
- **`isModuleActive(getPackageName())` returns true.**
- **`isModuleActiveAnywhere(getPackageName())` returns true.**

### The Android 11+ package visibility requirement

A module that queries the status provider from its own UI must declare the provider's authority in its own `AndroidManifest.xml`:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

Without that declaration, every query returns an empty cursor.

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

Pine honors every argument mutation a `beforeHookedMethod` or `afterHookedMethod` makes. Constructors are hookable on Pine and Native; Amiru and Proxy do not support constructor hooks. Native passes object arguments as `null`; Amiru carries object arguments since 1.4.9.

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

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab. Edit the module's scope. Then either tap **Launch App under ShizuPosed** and pick a scoped app, or just open a scoped app normally; the process monitor will trigger a spawn-only injection for it.

### Changing the installed package name

If a target app checks for `com.shizuposed.manager` by name, use **Settings → Hide ShizuPosed app**. The manager generates a random same-length name, patches the current APK's manifest, re-signs with the per-install key, installs via Shizuku, and uninstalls the old package. **Unhide ShizuPosed app** reverses it, re-signing with the same per-install key.

---

## The manager

Five tabs.

**Home** shows the status card, the counter tiles, and the Framework Info card (which now shows both the classic API level and the libxposed API level — 101 and 102).

**Modules** lists installed modules with XStealth always at the top.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has runtime toggles, scan interval and hook delay sliders, the Sysku toggle and status line, the Maintenance section (including **Hide ShizuPosed app**, **Unhide ShizuPosed app**, **Clear logs**, **Clean all caches**), **Check for updates**, and config export.

### XStealth detail sheet

Fifteen toggles, organized by layer:

**Master toggles:** Enable XStealth, Enable XStealth Next, Use bridge.

**Settings-based checks:** Hide Developer Options, Hide ADB enabled.

**System properties and Build fields:** Hide system properties, Sanitize Build fields.

**Framework properties and threads:** Scrub framework properties, Rename framework threads.

**Package and process:** Hide Shizuku package, Hide ShizuPosed package, Hide running processes, Hide `/proc` entries.

**API protection and dex:** Protect Xposed API calls, Optimize module dex.

---

## Provenance and verification

ShizuPosed is built from sources in this repository. The APK is reproducible from a clean checkout with the documented toolchain.

**Signed builds.** The released APK is signed with the project's release key.

**Native library provenance.** Each `.so` is built from the `.c` file with the same name in the `native/` directory.

**What to look for in a release.** The release notes list the NDK version, SDK version, Gradle and JDK versions, and a SHA-256 of each native library. Pine's SHA-256 should match the upstream canyie/pine release — if it doesn't, reject the build.

**Vendored libxposed API.** The `io.github.libxposed.api.*` classes in `XposedHook.dex` are vendored from the upstream libxposed API 102 definitions.

**Per-install cloak key.** `files/cloak-install.p12` is generated on the first cloak and stored in the manager's data directory. It is **not** included in the APK. It is owner-readable, protected by the app's UID sandbox. Every cloaked install has a unique signature; a target app cannot fingerprint one key across installs.

---

## Building

### The app

```
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosed
gradle clean :app:assembleRelease
```

The single-flavor release is the only build. `checkNoOldPackageLeak` runs as a `finalizedBy` on `assembleRelease` and fails if `com.shizuposed.manager` appears as a literal in `classes.dex` or `resources.arsc` (a leak into a cloaked install).

### The native libraries

The six native libraries (`libshizuposed.so`, `libamiru.so`, `libcallsite.so`, `libxstealth.so`, `libxstealth_next.so`, `libxstealth_bridge.so`) and the optional `libsysku.so` build with the Android NDK. Build for API 29 or higher.

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

`logcat -s PineBackend` shows whether Pine initialized and every hook it installed or declined. `logcat -s ShizuPosedAmiru` shows Amiru's slot activity and layout probe result.

Each bootstrap also writes a marker to `<shell-base>/hooked/<pkg>.json` when it completes.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. Cross-process hooks aren't possible. XML-level resource replacement isn't supported. No hot reload. ROMs that block `app_process` can't run the framework.

**Protected UIDs are unreachable.** The scope editor hides them.

**Zygote-timing APIs.** `initZygote()` fires once per bootstrapped process, not once per zygote fork. `onSystemServerStarting` and `onHotReloading` / `onHotReloaded` are never called.

**The process is not a zygote fork.** After `setuid`, its parent is `app_process`, not `zygote`. XStealth's appearance layer scrubs `getppid()`, the `PPid:` and `Name:` fields in `/proc/self/status`, the comm field in `/proc/self/stat`, `/proc/self/exe`, `/proc/1/comm`, `/proc/self/cmdline`, `/proc/self/mountinfo`, and `/proc/self/cgroup`. The scrub is userspace, not lineage. `Uid:`, SELinux context, and process start time are unchanged.

**The appearance layer is trust-gated.** Framework threads that call `getppid()` see the real parent PID. This is deliberate.

**The appearance layer requires `libxstealth`'s exports.** A build that skips `libxstealth.so` loses the appearance layer entirely.

**Monitor-triggered injection doesn't attach to the detected pid.** It can only trigger a spawn-only routed launch, and never opens the UI.

**Object arguments on the Native backend.** Native passes object arguments to Java callbacks as `null`, and does not accept object return values. `thisObject` is also `null`. Pine, Amiru (since 1.4.9), Instrumentation, and Proxy handle objects correctly.

**Amiru doesn't support constructors or after-hooks.** Constructor hooks fall through to the next backend; after-hooks are not implemented in the stub.

**Amiru object returns use `System.identityHashCode` as the handle token.** Correct for the vast majority of cases; a hash collision would return the wrong object. A proper JNI local-ref fix is a follow-up.

**`replaceHook` is not atomic with an in-flight invocation.** The current call finishes on the old hooker; the next call uses the new one.

**Classic API reports 101.** A module that hardcodes `getXposedVersion() == 100` may fail; the fix is `>= 100`.

**libxposed API reports 102, not 103.**

**Native hooks refuse non-relocatable first instructions.** A native hook whose first instruction is PC-relative (`adrp`, `adr`, `b`, `bl`, `ldr`-literal, `add`-from-PC) is refused with `JNI_FALSE` so the dispatcher falls back to another backend. Producing a wrong trampoline would crash.

**CallSite is non-functional.** Every install is refused. The interpreter-table scanner and patch machinery are preserved as scaffolding for a future implementation.

**Cloaked managers can't install updates in place.** Unhide → update → re-Hide is the path.

**No signature verification on the downloaded update APK.** Size and ZIP magic checks catch truncation and HTML-instead-of-APK, not a malicious APK served from a compromised GitHub response.