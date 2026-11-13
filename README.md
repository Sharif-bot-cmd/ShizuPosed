# ShizuPosed

**An Xposed-compatible hook framework that doesn't need root — it runs on `app_process`.**

ShizuPosed runs Xposed-API modules inside target apps without root. No bootloader unlock, no system partition changes. Shizuku calls `app_process` as the shell UID, a Java runtime spins up, and hooks get installed through a dispatcher that tries several backends until one sticks. Modules built for LSPosed and classic Xposed keep working, and modules built against libxposed API 101 (the modern `io.github.libxposed.*` surface) are supported as well.

---

## One build

ShizuPosed ships as a single APK. Package name `com.shizuposed.manager`. Its updates work like any other app's.

If a target app checks for `com.shizuposed.manager` by name, use the **Hide ShizuPosed app** flow in the Settings tab. It rebuilds the APK under a new package name and launcher label, installs it via Shizuku, and uninstalls the old package. The rebuilt APK is signed with a key bundled inside the manager — see [Hide ShizuPosed app](#hide-shizuposed-app) for the trade-off.

Details are in [Installation](#installation).

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

### After `setuid` — what the process is and isn't

Once `XposedHook.main()` calls `Process.setuid(targetUid)`, the process **runs as the target's UID**. It can read the target's private data, and its SELinux context is the target's context on most ROMs.

What it is *not*:

  * **It is not a zygote fork.** Its parent process is `app_process`, not `zygote`. `getppid()` and the `PPid` field in `/proc/self/status` reflect the bootstrapped parent, not zygote's PID. XStealth scrubs both, but the scrub is userspace — a kernel-level reader can still see the real parent.
  * **Its SELinux context can differ** on ROMs that enforce stricter labels for non-zygote processes. On those ROMs, a target app that reads its own `/proc/self/attr/current` sees a different label than a real zygote fork would produce.
  * **It does not inherit zygote's open file descriptors** for the target's data directory. The process opens the target's data directory through normal path traversal after `setuid`; a zygote-forked process inherits it.

These differences are what XStealth's `getppid` interposition, the appearance layer, and `libxstealth_bridge`'s `/proc` scrubbing are for. They are userspace scrubs, not lineage changes, and cannot close every gap. The kernel's task struct is unchanged.

**If `setuid` fails on a ROM**, the process runs as UID 2000 and cannot access the target's data at all. `XposedHook` logs the failure clearly (`Continuing as uid N (target uid M)`), and the launch will typically fail at `handleBindApplication`. This is a `switchToUser` failure on that ROM, not a structural limitation — a bug report should include the `launch.log` excerpt.

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

### `IXposedHookZygoteInit` under ShizuPosed

ShizuPosed is not a zygote-timing framework, so `initZygote()` fires **once per bootstrapped process**, not once per zygote fork. It runs at the start of bootstrap, before any module's `handleLoadPackage`.

`StartupParam`:

  * `modulePath` is the **installed APK path**, not the cached dex path. Modules use it to open their own assets.
  * `startsSystemServer` is always `false`. ShizuPosed cannot reach `system_server`.

Modules that do one-time setup in `initZygote()` (WAEX, and most LSPosed-era modules) run correctly. Modules that expect zygote-specific behavior — forking into multiple apps from one zygote, sharing state across app processes via a zygote-resident cache — will not get it. The callback fires, but the process model it assumes doesn't exist.

### How module rows respond to touch

The Modules tab row supports four gestures, each doing one thing:

| Gesture | Action |
|---|---|
| Tap the row body | Opens the scope editor |
| Tap the enable switch | Toggles the module |
| Tap the icon | Opens the detail sheet |
| Long-press the row | Opens the module's UI through ShizuPosed |

Uninstall lives in the detail sheet's `Uninstall` button.

### XStealth's row behaves differently

XStealth's row keeps its special treatment. No enable switch on the row (its toggle lives in the detail sheet), and long-press opens the detail sheet rather than the UI.

### The API badge

Every third-party module row shows an API badge when the module declares one. The badge has two forms:

  * **`API 93`**, **`API 94`**, … — the module declared a classic Xposed API level.
  * **`libxposed 101`** — the module extends `io.github.libxposed.api.XposedModule`.

The badge reports **exactly what the module declared** — no `(unsupported)` suffix. Whether the module will actually activate is answered by the detail sheet's **Run activation diagnostic**, not the badge.

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
  ├── LibXposedDriver            libxposed API 101 driver
  ├── ShizuPosedXposedInterface  XposedInterface impl (reports 101)
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
  │     ├── SocketCheck          LocalSocket shim (opt-in)
  │     ├── PackageCheck         PackageManager lookups + Binder
  │     ├── RunningProcessCheck  ActivityManager lookups
  │     ├── ApiProtectionCheck   reflection + baselines
  │     ├── XStealthNative       libc symbol interposition (libxstealth.so)
  │     │     ├── appearance: /proc/self/exe, /proc/1/comm,
  │     │     │               Name:, PPid:, cmdline, stat comm
  │     ├── XStealthNativeNext   libc syscall stub patching (libxstealth_next.so)
  │     ├── SyskuNative          syscall() + getdents64() interposition (libsysku.so)
  │     │     ├── appearance: readlinkat substitutions, trust-gated getppid
  │     ├── XStealthBridge       enumeration hiding (libxstealth_bridge.so)
  │     │     ├── appearance: thread renames, env rewrites,
  │     │     │               readlink substitutions, getppid gate
  │     └── ThreadNameScrub      thread renaming
  └── markers written to         <shell-base>/hooked/

Manager process
  ├── ProcessMonitor             /proc scan + marker mirror refresh
  ├── MarkerCache                local mirror of <shell-base>/hooked/
  ├── IconResolver               LruCache for app icons
  ├── RuntimePrefs               scan interval + hook delay storage
  ├── SyskuController            state / heartbeat / diagnostics
  │     └── readSyskuStateEnabled(...)   deploy gate for libsysku.so
  ├── ModuleStatusProvider       reads the mirror, never Shizuku
  ├── ModuleDiagnostics          activation-state report for a module
  └── ManagerCloakFlow           Hide ShizuPosed app (rebuild + install)
        ├── ManagerCloak         manifest rewrite + re-sign
        └── ManagerMigrate       settings / DB / keystore transfer
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

  * `XposedHelpers.findAndHookMethod("com.example.target.MainActivity", lpparam.classLoader, ...)` — target class resolves through step 3.
  * `XposedHelpers.findAndHookMethod(MyActivity.class.getName(), lpparam.classLoader, ...)` — module class resolves through step 2 (self-hook pattern).

A single loader can't do both. Prior versions set `lpparam.classLoader` to the raw module loader, which silently broke every `findAndHookMethod` on a target class — the module's `DexClassLoader` doesn't see the target's dex, and the resulting `ClassNotFoundException` was usually caught by the module and treated as "hook failed."

---

## The two injection paths

ShizuPosed reaches a target in one of two ways. Both use the same `app_process` primitive; they differ in whether the target's UI is opened after the spawn.

### Routed launch (user-triggered)

The **Launch App under ShizuPosed** button, the module row's long-press, and the detail sheet's "Open module app" entry all go through this path. It has two steps:

  1. Boot the target process with hooks installed:

         unset CLASSPATH; unset BOOTCLASSPATH;
         <prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]

  2. Open the target's UI in that already-running process:

         am start --user current -n <resolved-component>

Step 2 is required for this path. `app_process` booting a process does not start any activity.

### Monitor-triggered injection

`ProcessMonitor` scans `/proc` on a timer (default interval from Settings). When it sees a newly-started app process that at least one enabled module scopes, it calls `injectProcess`. That method performs a **spawn-only** routed launch for the package — it runs step 1 above but **not** step 2.

The monitor path is silent. It does not broadcast a launch result, so the manager UI never raises a popup for an app you opened yourself. The Logs tab records everything the service does.

Four guards keep the monitor from misbehaving:

  * **UID.** Packages with `uid < 10000` are refused.
  * **Scope.** Only packages a module actually scopes are launched.
  * **Dedup.** A package that already has a shell-side hooked marker, or whose launch is already in flight, is skipped.
  * **In-flight set.** A package currently being spawned is skipped until that spawn completes.

### What neither path can do

Neither path can inject into a process someone else started. If you launch Facebook from the launcher, the process that comes up is not the one ShizuPosed will hook. The monitor notices it, but the only injection it can perform is a spawn-only routed launch — which starts a fresh process for the same package. For apps that manage their own process lifecycle aggressively, it may not produce a usable process.

### Spawn construction

The spawn command is wrapped in a subshell whose stdout is redirected to `/dev/null`, and the whole thing is backgrounded:

    unset CLASSPATH; unset BOOTCLASSPATH; \
    ( <prefix> app_process ... </dev/null >>launch.log 2>&1 & ) \
    >/dev/null 2>&1 < /dev/null &

The outer redirect is what makes the manager's shell call return immediately. The `<prefix>` is `setsid -d` when available, `nohup` otherwise.

---

## The lifecycle bridges

Two bridges extend what the framework can reach without changing the injection model.

**`ApplicationThreadBackend`** hooks the binder interface `ActivityThread` exposes to `system_server` (its `mAppThread` field). Methods hooked: `scheduleCreateService`, `scheduleBindService`, `scheduleUnbindService`, `scheduleStopService`, `scheduleServiceArgs`, `scheduleTransaction` (Android 9+). Each is fail-open.

**`ClassLoadingBridge`** hooks `ClassLoader.loadClass` (listeners fire before a class is defined), hooks `DexClassLoader` / `PathClassLoader` construction (listeners fire when a new loader is created at runtime), and pre-loads the target's receiver and provider classes via `PackageManager`.

Both bridges are installed by `XposedHook` during bootstrap, before any module's entry point runs.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

**Pine AUTO** is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing.

Both classic and libxposed hooks land in the same dispatcher.

### What each backend can actually hook

| Backend | Primitive args | Object args | Primitive returns | Object returns | `thisObject` | Constructors |
|---|---|---|---|---|---|---|
| Pine AUTO | yes | yes | yes | yes | yes | yes |
| Pine REPLACEMENT | yes | yes | yes | yes | yes | yes |
| Amiru | yes | **null** | yes | **not replaceable** | **null** | no |
| CallSite | **n/a** | **n/a** | **n/a** | **n/a** | **n/a** | **n/a** |
| Native (`libshizuposed`) | yes | **null** | yes | **not replaceable** | **null** | yes |
| Instrumentation | yes | yes | n/a | n/a | yes | n/a |
| Proxy | yes | yes | yes | yes | yes | no |

The two rows in bold are the important ones. A module — classic or libxposed — that hooks `void foo(String s)` and reads `s` from the hook receives `null` if the hook landed on Amiru or Native.

### Pine's argument contract

Pine's `MethodHook` gives a `CallFrame` with `thisObject` and `args`, and `PineBackend` translates both directions against `MethodHookParam`. A `beforeHookedMethod` that writes `param.args[i] = ...` or `param.thisObject = ...` has its changes honored by the original invocation. A `beforeHookedMethod` that sets `param.setResult(...)` short-circuits the original. Same for `afterHookedMethod`: a callback that replaces `param.result` post-invocation has its change propagated to the caller.

`param.method` is populated in every callback. Modules that read `param.method.getDeclaringClass()` or `param.method.getName()` get the actual hooked member.

### CallSite backend status

CallSite is present in the dispatcher chain, but it is **not functional**. It patches the ART interpreter handler table but does not reconstruct Java-level arguments from the interpreter's `ShadowFrame`, so the trampoline returns the callback ID without ever invoking a Java callback. `install_hook_impl` refuses every install with a logged reason, so `CallSiteBackend.hook` returns `false` and the dispatcher falls through.

### Disabling a backend

`HookDispatcher.setBackendEnabled(name, on)` toggles a backend by name. A disabled backend keeps its slot in the ordering but is skipped during dispatch. The stats string shows `[disabled]` next to the name.

---

## The dex-loading fallback ladder

Loading a module's dex into a target process can fail for several reasons. `DexLoadingBridge` tries three strategies in order:

  1. **`InMemoryDexClassLoader`** (Android 8+).
  2. **`DexClassLoader`.**
  3. **`BaseDexClassLoader` injection.**

### Spawn-tool fallback

At startup, ShizuPosed runs a single shell probe that checks for `setsid`, `nohup`, and the `disown` shell builtin.

| Condition | Spawn prefix |
|---|---|
| `setsid` present | `/system/bin/setsid -d` |
| `setsid` missing, `nohup` present | `/system/bin/nohup` |
| neither present | bare spawn |

### ART policy flags

Two ART flags are passed to every spawned `app_process`:

    -Xhidden-api-policy:enabled
    -Xcore-platform-api-policy:enabled

### Post-spawn verification

After the `BOOTSTRAP_DELAY_MS` wait (1500ms), ShizuPosed runs a single `ps -A -o ARGS` call and greps for the target package name. If the process is not found, the launch logs a warning and proceeds to `am start` anyway.

### Launch-result timeout

The manager's UI waits for a result broadcast from `ShizuPosedService` after dispatching a button-path launch. A timeout (`LAUNCH_TIMEOUT_MS`, 20s) guards against a lost broadcast.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

XStealth is organized in four layers, each independently toggleable:

  * **Java-layer checks** — hook Android APIs inside the target's own process.
  * **Binder-layer checks** — inspect `IBinder.transact` calls before they leave the process.
  * **Native layer** — interpose libc symbols, patch libc syscall stubs, and substitute plausible values into procfs fields.
  * **Bridge layer** — hide from native enumeration paths, and rename threads / rewrite environment variables to look like an ordinary app process.

All layers fail open.

### Java-layer checks

**Developer Options and ADB.** `DevOptionsCheck` and `AdbCheck` hook every settings read path.

**Cached detection values.** `CachedValueCheck` hooks `SharedPreferences` reads for a per-app allow-list of keys.

**Subprocess reads of the settings XML.** `SettingsFileCheck` hooks `Runtime.exec` (all five overloads) and `ProcessBuilder.start()`.

**System properties.** `SystemPropertiesCheck` hooks `android.os.SystemProperties.get`, `getBoolean`, `getInt`, and `getLong` for a watched key list.

**Build fields.** `BuildCheck` sets `Build.TAGS` to `release-keys`, `Build.TYPE` to `user`.

**Framework properties.** `PropertyScrubCheck` removes `shizuposed.*` entries from `System.getProperties()`.

**Shizuku and ShizuPosed packages.** `PackageCheck` intercepts the standard `PackageManager` lookup methods.

**Running processes.** Filters `ActivityManager` process and service listings.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` and `io.github.libxposed.api.*` when the caller isn't a loaded module.

**Method-count fingerprinting.** Pins a baseline for every framework class the framework has actually hooked.

### Binder-layer checks

**`BinderProxy.transact` hook.** Every Binder call the target makes through the Java `IBinder` API goes through `BinderProxy.transact`. Three classes install it: `PackageCheck` covers `IPackageManager`, `AdbCheck` and `DevOptionsCheck` cover `IContentProvider`.

**`DUMP_TRANSACTION` handling.** `Binder.dump(fd, args)` calls route through `transact` with the fixed code `IBinder.DUMP_TRANSACTION`.

**`Binder.ProxyTransactListener`.** `PackageCheck` installs one for observation. It cannot refuse.

### Native layer

**`libxstealth.so`** — the primary native engine. Interposes the file, process, and exec-family libc symbols, and substitutes plausible values into procfs fields:

  * `/proc/self/exe` → `/system/bin/app_process64`
  * `/proc/1/comm` → `zygote64`
  * `/proc/self/status` `Name:` → `app_process64`
  * `/proc/self/status` `PPid:` → `1`
  * `/proc/self/cmdline` → a single plausible argument
  * `/proc/self/stat` `comm` field → `app_process64`
  * `/proc/self/mountinfo` → plausible `/data/user/...` mounts
  * `/proc/self/cgroup` → `/uid_<target_uid>/pid_<pid>`

**`libxstealth_next.so`** — the aggressive engine. Inline-patches libc syscall stubs. Strategy ladder: SVC-discovery first, classifier fallback second, dispatcher patching third.

**`libsysku.so`** — the syscall-level interposer. See [Sysku](#sysku--the-syscall-level-interposer) below. Also applies the appearance substitutions on the raw-syscall path (`SYS_readlinkat`, `SYS_readlink`), and trust-gates `SYS_getppid`.

**Stub-shape verification.** Both `libxstealth.so` and the backends that build hook stubs coordinate around a captured prologue template. Backends require the template to save every register they restore — in particular, `x30` — before splicing it into a stub.

### Bridge layer

**`libxstealth_bridge.so`** — the enumeration-hiding and appearance library.

  * **`dl_iterate_phdr`** — filters framework library names from the loaded-library walk.
  * **`opendir` / `readdir` / `closedir`** — tracks `/proc/self/fd` and `/proc/self/task`. For `/proc/self/fd`, drops entries whose target contains a hidden string. For `/proc/self/task`, **keeps** the tid directory entry (preserving the thread count) and renames the `comm` to a plausible ART thread name on later reads.
  * **`readlink`** — substitutes `/proc/self/exe` → `/system/bin/app_process64` and `/proc/1/comm` → `zygote64`. For `/proc/<pid>/exe`, substitutes if the real target contains a hidden string.
  * **`read`** — refuses reads of hidden processes (`/proc/<pid>/{status,cmdline,comm,maps,smaps}`), and renames `/proc/self/task/<tid>/comm` to plausible ART thread names.
  * **`getppid`** — trust-gated. Returns `1` for untrusted callers, the real ppid for framework callers.
  * **`getenv`** — substitutes plausible values for framework environment variables, drops `LD_PRELOAD` and `JAVA_TOOL_OPTIONS`.

**Thread name scrubbing.** `ThreadNameScrub` (Java) renames ShizuPosed's own threads. The bridge extends this to any thread whose name matches a framework pattern, so a detector enumerating `/proc/self/task` sees the correct thread count with plausible ART names.

### Smart foreground hiding (opt-in)

Covered in 1.2.7's release notes. No changes in 1.2.9.

### Legitimate process appearance

A detector's cheapest "is this process legitimate?" checks read procfs fields that reveal the bootstrap's parentage. The appearance layer substitutes plausible values into those fields, so a detector sees a consistent picture:

| Field | Real value | Substituted value |
|---|---|---|
| `/proc/self/exe` | resolved against the dex path | `/system/bin/app_process64` |
| `/proc/1/comm` | `init` | `zygote64` |
| `/proc/self/status` `Name:` | `app_process64` | `app_process64` (explicit) |
| `/proc/self/status` `PPid:` | `app_process` pid | `1` |
| `/proc/self/cmdline` | full bootstrap args | one plausible argument |
| `/proc/self/stat` `comm` | `app_process64` | `app_process64` (explicit) |
| `/proc/self/mountinfo` hidden lines | shell-base paths | plausible `/data/user/...` mounts |
| `/proc/self/cgroup` path field | shell-base paths | `/uid_<target_uid>/pid_<pid>` |
| `/proc/self/task/<tid>/comm` | `ShizuPosed-*`, `Xposed-*`, … | `Signal Catcher`, `JDWP`, `Jit thread pool`, … |
| `getenv("LD_PRELOAD")` | framework value | `NULL` (drop) |
| `getenv("SHIZUPOSED_*")` | framework value | plausible value |

**The rule is: substitute, don't blank.** A blanked field is a stronger detection signal than a plausible wrong one. The old behavior — blanking `Name:`, zeroing `cmdline`, dropping threads — left holes that a detector could flag. The appearance layer fills them with values that match what a zygote-forked app would report.

**What the appearance layer cannot fix:**

  * **`Uid:` is real.** The process genuinely runs as the target UID — that's correct, and faking it would be a lie the kernel doesn't support.
  * **SELinux context can differ** on ROMs that enforce stricter labels for non-zygote processes.
  * **`/proc/self/stat` start time** and **cgroup membership** are kernel state.
  * **The parent is `app_process`, not `zygote`.** Scrubbing `PPid:` to `1` is a userspace lie; a detector that reads `/proc/<real-ppid>/comm` via `open()` (not `readlink()`) still sees the real parent's comm unless `open()` is also interposed.

The appearance layer closes the userspace-visible surfaces that are cheap to close. It does not move the kernel surface — that's still the ceiling described below.

### What each layer covers

| Detection path | Java | Binder | Native (`libxstealth`) | **Sysku (`libsysku`)** | Bridge |
|---|---|---|---|---|---|
| `Settings.Global.getInt` in-process | ✅ | — | — | — | — |
| `ContentResolver.query` in-process | ✅ | — | — | — | — |
| Direct `IContentProvider.query` over Binder | — | ✅ | — | — | — |
| `cat settings_global.xml` via `Runtime.exec` | ✅ | — | ✅ | — | — |
| `open()` of settings XML | — | — | ✅ | — | — |
| `syscall(SYS_openat, ...)` of settings XML | — | — | — | ✅ | — |
| `dumpsys settings get global <key>` via Binder | — | ✅ | — | — | — |
| Direct `IPackageManager` lookup over Binder | — | ✅ | — | — | — |
| `dumpsys package <pkg>` via Binder | — | ✅ | — | — | — |
| `PackageManager.getInstalledPackages` | ✅ | — | — | — | — |
| `dl_iterate_phdr` loaded-library walk | — | — | — | — | ✅ |
| `/proc/self/maps` scan for `.so` names | — | — | ✅ | — | — |
| `syscall(SYS_openat, ...)` of `/proc/self/maps` | — | — | — | ✅ | — |
| `/proc/self/fd` enumeration (read) | — | — | ✅ | ✅ | ✅ |
| `getdents64` of `/proc/self/fd` | — | — | — | ✅ | — |
| `getdents64` of `/proc/self/task` | — | — | — | ✅ | — |
| `getppid()` | — | — | ✅ | — | ✅ |
| `syscall(SYS_getppid)` | — | — | — | ✅ | — |
| `syscall(SYS_ptrace, PTRACE_TRACEME)` | — | — | — | ✅ | — |
| `syscall(SYS_process_vm_readv)` of our pid | — | — | — | ✅ | — |
| `syscall(SYS_perf_event_open)` on our pid | — | — | — | ✅ | — |
| `syscall(SYS_kcmp)` involving our pid | — | — | — | ✅ | — |
| `syscall(SYS_pidfd_open)` for our pid | — | — | — | ✅ | — |
| `syscall(SYS_prctl, PR_GET_DUMPABLE)` | — | — | — | ✅ | — |
| `syscall(SYS_prctl, PR_SET_DUMPABLE)` | — | — | — | ✅ | — |
| `syscall(SYS_mincore)` on a hidden region | — | — | — | ✅ | — |
| `PPid:` line in `/proc/self/status` | — | — | ✅ | — | — |
| `Name:` line in `/proc/self/status` | — | — | ✅ | — | — |
| PPid field in `/proc/self/stat` | — | — | ✅ | — | — |
| comm field in `/proc/self/stat` | — | — | ✅ | — | — |
| `/proc/self/cmdline` | — | — | ✅ | — | — |
| `/proc/self/exe` readlink | — | — | ✅ | ✅ | ✅ |
| `/proc/1/comm` readlink | — | — | ✅ | ✅ | ✅ |
| `/proc/<pid>/exe` readlink (hidden path) | — | — | ✅ | ✅ | ✅ |
| `/proc/self/task/<tid>/comm` | — | — | ✅ | — | ✅ |
| `/proc/self/mountinfo` hidden lines | — | — | ✅ | — | — |
| `/proc/self/cgroup` path field | — | — | ✅ | — | — |
| `getenv("LD_PRELOAD")` / `SHIZUPOSED_*` | — | — | — | — | ✅ |
| Inode column in `/proc/self/maps` | — | — | ✅ | — | — |
| `SystemProperties` reads (API) | ✅ | — | ✅ | — | — |
| `Build.TAGS`/`Build.TYPE` | ✅ | — | — | — | — |
| Hook-stub entry-point byte pattern | — | — | ✅ | — | — |
| **Second process the target spawned** | — | — | — | — | — |
| **Native `/dev/binder` direct** | — | — | — | — | — |
| **Property shared-memory area** | — | — | — | — | — |
| **Hardware attestation** | — | — | — | — | — |

The bottom four rows are the structural gaps.

### What XStealth doesn't hide

Hardware attestation. Root-empowered inspection. Server-side cross-reference. Native reads of the settings database that bypass the exec family entirely. Method-list reconstruction. Exotic reflection via `Unsafe` or JNI-level private methods. Kernel-level monitors. Signals unrelated to ShizuPosed. `Uid:`, SELinux context, and process start time — those are kernel state, not userspace state, and the appearance layer does not touch them. (`/proc/self/cgroup` is substituted — the *path* is userspace-visible — but the underlying cgroup membership is kernel state and a detector that resolves the cgroup controller still sees the truth.)

**`/proc/self/attr/current` (SELinux context).** Substituting it would make the `read()` path disagree with `getcon()`, which is a stronger tell than the truth. A detector that reads the context sees the real value — the same value a zygote fork would show on most ROMs, but a stricter label on ROMs that enforce stricter labels for non-zygote processes.

**`/proc/self/auxv` (`AT_EXECFN`).** Interposing `getauxval()` is possible, but a detector that `mmap`s `/proc/self/auxv` bypasses every read-path interposer, so a substitution via `getauxval()` would disagree with the `mmap` path. The auxv is kernel-populated state.

### XStealth's practical ceiling

XStealth raises the bar significantly but it cannot make a ShizuPosed process genuinely indistinguishable from a normal app. The kernel's task struct, the dynamic linker's internal state, cross-process observation by a privileged reader, and hardware attestation are all beyond the reach of a userspace library. The appearance layer extends what a userspace library can *fake* — comm, ppid, exe, cmdline, thread names, environment — but it does not move the kernel surface. A detector that reads `Uid:` and sees the target UID is correct, and a detector that reads cgroup membership or start time still sees the truth.

---

## The subprocess gap

A common detection pattern is to bypass the Java settings API entirely and shell out.

XStealth closes this in two layers: `SettingsFileCheck` at the Java level, and the exec-family interposers in `libxstealth.so` at the native level.

**Still not covered:** native `execve` called before the `.so` is loaded, or from a process that loaded its own copy of libc.

---

## Sysku — the syscall-level interposer

Sysku (`libsysku.so`) is an **optional** companion to `libxstealth.so` that closes detection paths the named libc interposers cannot reach.

### What it filters

**`syscall()` interposition.** A caller that bypasses the named wrapper — `syscall(SYS_openat, AT_FDCWD, "/proc/self/maps", O_RDONLY)` instead of `open(...)` — goes through libc's `syscall()` trampoline. `libsysku.so` filters the raw syscall number for:

| Path-takers | Pid-takers | Flag-takers |
|---|---|---|
| `SYS_openat` | `SYS_ptrace` (PTRACE_TRACEME) | `SYS_getppid` |
| `SYS_openat2` | `SYS_process_vm_readv` | `SYS_prctl` (GET/SET_DUMPABLE) |
| `SYS_newfstatat` / `SYS_fstatat` | `SYS_perf_event_open` | `SYS_mincore` (on hidden regions) |
| `SYS_statx` | `SYS_kcmp` | |
| `SYS_faccessat` | `SYS_pidfd_open` | |
| `SYS_faccessat2` | | |
| `SYS_readlinkat` | | |
| Plus `SYS_open`, `SYS_stat`, `SYS_lstat`, `SYS_access`, `SYS_readlink` where the NDK exposes them | | |

**Appearance substitutions on the raw-syscall path.** `SYS_readlinkat` and `SYS_readlink` are checked for `/proc/self/exe` and `/proc/1/comm` *before* the path-is-blocked filter, and return the substituted value instead of `ENOENT`. `SYS_getppid` is trust-gated: framework callers see the real ppid, everyone else sees `1`.

**`getdents64()` interposition.** A detector that opens `/proc/self/fd` as a directory and counts descriptors sees ShizuPosed's fds regardless of what `libxstealth.so` does to individual reads. `libsysku.so` walks the `linux_dirent64` records and drops any whose name matches a framework-owned pattern. For `/proc/self/task`, entries are **kept** — the rename is handled by `libxstealth_bridge`'s `read()` interposer, so the thread count stays correct.

### Full mode and fallback mode

Sysku runs in one of two modes, decided at `nativeInit()`:

  * **Full mode** — the Sysku ADB module's `service.sh` is running (heartbeat fresh). `syscall()` and `getdents64()` both filter.
  * **Fallback mode** — the module isn't running. `syscall()` filters; `getdents64` passes through unchanged.

Fallback exists because hiding a directory entry is a more visible tell than returning `ENOENT` for a path that shouldn't exist.

The mode is set by `nativeInit(shellBase, moduleRunning)`. `XposedHook` computes `moduleRunning` from `<shell-base>/sysku/heartbeat.json` freshness.

### Activation model

Sysku is active when **two conditions hold**:

  1. `state.json` says `enabled: true` — the user's choice, written by the Settings tab's toggle.
  2. `libsysku.so` is present in `<shell-base>/libs/`.

`ShizuPosedService` deploys `libsysku.so` whenever `state.json.enabled` is `true` — that is the source of truth. The legacy `<shell-base>/sysku/gate.live` file is still written (and removed) on every toggle for compatibility with older `XposedHook` builds, but deployment does not depend on it.

The Settings tab reports five states:

| State | Meaning |
|---|---|
| `Disabled` | The user has not enabled Sysku. |
| `Not installed` | `libsysku.so` is not present on the shell side, and no heartbeat has ever reported a load attempt. |
| `Enabled (lib failed to load)` | The heartbeat is fresh and reports `lib_loaded: false` — `service.sh` is running and tried to load `libsysku.so`, but the file is not on the shell side. Usually means the APK build skipped `libsysku.so` (it is optional). |
| `Enabled (module not running)` | Sysku is on, `libsysku.so` is loaded, but the ADB module's heartbeat is stale. **Sysku is running in fallback mode.** |
| `Enabled (load failed)` | Sysku is on, `libsysku.so` is loaded, but the last bootstrap's `nativeInit()` returned 0. |
| `Enabled` | Sysku is on, `libsysku.so` is loaded, and the heartbeat is fresh. **Full mode.** |

### Building `libsysku.so`

    clang -shared -fPIC -O2 -Wall -Wextra -s \
        -target aarch64-linux-android29 \
        -o libsysku.so libsysku.c \
        -llog -ldl

Save it to `app/src/main/jniLibs/arm64-v8a/libsysku.so`. If you build without it, the build succeeds, but the Settings tab will report **`Enabled (lib failed to load)`** once the ADB module's `service.sh` starts reporting a heartbeat — because the manager can deploy nothing that isn't bundled.

---

## Socket shim — opt-in, off by default

`SocketCheck` is **off by default** and installs **no hooks** unless a specific daemon is configured.

---

## Hide ShizuPosed app

The Settings tab has a **Hide ShizuPosed app** row. It rebuilds the manager APK under a new package name and launcher label, installs it via Shizuku, and uninstalls the old package. This is the same mechanism the (now removed) stealth build used, but performed on-device.

The dialog has two optional fields:

  * **Package name** — leave blank to generate a random `<letter>.<letters>.<letters>` form, or type a specific name.
  * **App name** — leave blank to generate a plausible launcher label like "Sync Service", or type a specific label.

Both fields are validated: the package name must match Android's package-name grammar, and the label must be 2–30 characters with no angle brackets.

**The trade-off.** The rebuilt APK is signed with a keystore bundled inside the manager at `assets/keystore`. Re-signing an APK requires the private key, and the private key must be available to the device to re-sign at runtime. That means **every cloak produces an APK with the same signature**. A target app that fingerprints the signature can identify cloaked ShizuPosed installs regardless of package name. The launcher label and the package name change; the signature does not.

If you need signature-anonymity, keep the signing key off the device and build the APK on a release machine — that's what the separate stealth build used to do. The single-APK model trades that anonymity for convenience.

### The revert path

The **Unhide ShizuPosed app** row downloads the latest standard APK from GitHub, embeds a fresh migrate zip, installs it under the original package name, and uninstalls the current cloaked package. It's the "undo" for a cloak install.

Both directions carry a migrate zip — a small asset inside the rebuilt APK that the new package reads on first launch to restore the previous install's `SharedPreferences`, SQLite database, and keystore. That's what makes the cloak install "feel like the same app" even though the package name changed.

---

## API surface — classic (version 101)

ShizuPosed reports classic Xposed API version **101**.

  * **93** — LSPosed base. `IXUnhook`, `isModuleActive` and `isModuleEnabled`, provider-backed state queries.
  * **94** — `hookAllMethods` returns `Set<XC_MethodHook.Unhook>`.
  * **95** — `hookAllConstructors` returns the same.
  * **96** — `MethodHookParam.isReturnEarly()` exposed.
  * **97** — Instance field accessors on `XposedBridge`.
  * **98** — Static field accessors on `XposedBridge`, and `XposedHelpers.getSurroundingThis`.
  * **99** — Method invokers on `XposedBridge`.
  * **100** — ShizuPosed-specific extensions: `getShizuPosedApiMin()`, `getShizuPosedApiMax()`, `supportsApi(int)`, `isModuleActiveAnywhere(...)`.
  * **101** — Reported version. The surface below 101 is unchanged; the version bump aligns the classic report with what the libxposed surface already reports.

### `IXposedHookZygoteInit`

Shipped in 1.2.9 for link compatibility. Modules that implement it get an `initZygote()` call at bootstrap, before `handleLoadPackage`. See [IXposedHookZygoteInit under ShizuPosed](#ixposedhookzygoteinit-under-shizuposed) above for the timing detail.

### Active-state queries

Two distinct questions. `isModuleActive(pkg)` asks "is this module loaded into the process for `pkg`?" `isModuleActiveAnywhere(pkg)` asks "is this module loaded into any process ShizuPosed has bootstrapped?"

Under LSPosed the two questions have the same answer, because LSPosed injects into every process. Under ShizuPosed they differ. `isModuleActiveAnywhere(getPackageName())` exists to answer the broader question directly.

---

## API surface — libxposed (version 101)

ShizuPosed vendors the modern `io.github.libxposed.api.*` surface and reports **API 101**.

  * **`XposedInterface`** — `hook(Executable)` → `HookBuilder` → `intercept(Hooker)` → `HookHandle`, `hookClassInitializer(Class)`, `deoptimize(Executable)`, `getInvoker(Method)` / `getInvoker(Constructor)`, `log(...)`, `getFrameworkName()` / `getFrameworkVersion()` / `getFrameworkVersionCode()` / `getFrameworkProperties()`, `getModuleApplicationInfo()`.
  * **`XposedModule`** — the abstract class a libxposed module extends.
  * **`XposedModuleInterface`** — the lifecycle callbacks: `onModuleLoaded`, `onPackageLoaded` (Android 10+), `onPackageReady`, `onSystemServerStarting`.
  * **`Chain`** — the hook callback context: `getExecutable()`, `getThisObject()`, `getArgs()`, `getArg(i)`, `proceed()`, `proceed(Object[])`, `proceedWith(Object)`, `proceedWith(Object, Object[])`.

### What ShizuPosed reports for libxposed

  * `getApiVersion()` returns **101**.
  * `getFrameworkName()` returns **`"ShizuPosed"`**.
  * `getFrameworkVersion()` returns the app's version string.
  * `getFrameworkVersionCode()` returns **100**.
  * `getFrameworkProperties()` returns the capability bitmask described below.

### Capability bits

| Bit | Constant | Value | Meaning |
|---|---|---|---|
| system-server hooking | `PROP_CAP_SYSTEM` | **0** | ShizuPosed cannot hook `system_server`. |
| remote IPC | `PROP_CAP_REMOTE` | **0** | `getRemotePreferences` / `openRemoteFile` not implemented. |
| runtime API protection | `PROP_RT_API_PROTECTION` | **1** | XStealth's `ApiProtectionCheck` is active. |

### What libxposed API 101 does *not* include

The 102 surface adds hot-reload, `HookHandle.getId()`, and `HookHandle.replaceHook()`. ShizuPosed reports 101 specifically so a module that guards on `getApiVersion() >= 102` falls back.

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

### What libxposed modules share with classic ones

  * **Backends.** Both APIs route hooks through `XposedHookBridge.installHook(...)` → `HookDispatcher` → `HookEngine`.
  * **Classloader.** `PackageLoadedParam.getDefaultClassLoader()` returns the same `ModuleClassLoader` a classic module sees as `lpparam.classLoader` — both the target's classes and the module's own classes resolve through it.
  * **Scope.** The scope editor doesn't care which API a module speaks.
  * **Markers and status provider.** A libxposed module shows up in the marker `moduleList` the same way a classic module does.
  * **XStealth.** `ApiProtectionCheck` refuses reflection on `io.github.libxposed.api.*` from non-module callers.
  * **Activation self-checks.** `XposedBridge.isModuleActive(...)` works the same way.

### What libxposed modules do **not** get

  * **`getRemotePreferences` / `openRemoteFile`.** Not implemented.
  * **Hot reload.** Not implemented.
  * **`system_server` hooking.** `onSystemServerStarting` is never called.
  * **`deoptimize`.** Returns `false`. Hook installation deopts implicitly.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

  1. `assets/xposed_init` — the canonical marker.
  2. `assets/xposed_module` — an older convention.
  3. `META-INF/xposed/module.prop` — EdXposed-era.
  4. `assets/native_init` — a weak signal.

### How ShizuPosed decides which API a module speaks

  * If the entry class extends `io.github.libxposed.api.XposedModule`, the module is driven through the **libxposed lifecycle** (`onModuleLoaded` → `onPackageLoaded` → `onPackageReady`).
  * Otherwise, if the entry class implements one of the classic `de.robv.android.xposed.IXposedHook*` interfaces, the module is driven through the **classic lifecycle** (`initZygote` → `handleLoadPackage` → `handleInitPackageResources`).
  * Otherwise, a reflective scan for a `handleLoadPackage` method is the last resort.

### Recommended scope

Modules can declare which apps they're _intended_ for by shipping an `assets/scope.list` file inside the APK.

### The scope editor

The scope editor lists the apps a module can be applied to. Not every installed package appears:

  * **Protected UIDs are hidden.** A package whose UID is below 10000 is not offered.
  * **The manager itself is hidden.**
  * **System packages with no UI and no running process are hidden.** "No UI" means no activity matching `ACTION_MAIN` + `CATEGORY_LAUNCHER` — the same signal the launcher uses. "No running process" means no entry in `ActivityManager.getRunningAppProcesses()` matching the package name or its process name. These packages cannot be meaningfully scoped (a module hooks a process, and there is no process to hook; the smart-foreground monitor watches foreground activities, and there is no activity to watch). The 3-button navigation overlay and various OEM telemetry stubs fall into this bucket.
  * **Everything else is shown.**

Third-party apps are **never** filtered by the UI/process probe. The user installed them, and a module may legitimately scope a background service, IME, or widget that has no launcher activity. System apps with a launcher activity, or a process that `ActivityManager` reports as running, are also kept.

The probe fails open — any error during the UI or process query keeps the package listed, so a restricted ROM does not silently hide apps that would otherwise be scopeable.

A package whose UID is reported as 0 during package scanning falls back to a name-prefix check.

---

## Activation state — how "Activated" actually works

A module UI shows "Activated" when one of three things is true:

  * **Its own hook is installed in this process.** Under ShizuPosed, this requires the module's UI to have been launched **through** ShizuPosed — long-press the module's row, or tap "Open module app" in the detail sheet.
  * **`isModuleActive(getPackageName())` returns true.** The module asks the provider whether a marker exists for its own package.
  * **`isModuleActiveAnywhere(getPackageName())` returns true.** The module asks the provider whether any marker lists it.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per process it bootstraps to `<shell-base>/hooked/<pkg>.json`. The manager mirrors the markers into its own files directory. Both classic and libxposed modules appear in `moduleList` identically. The marker's `engines` object reports which native engines loaded, including `sysku` and `sysku_fallback`.

### The query chain

    Module UI process
      → XposedBridge.isModuleActive(pkg)
        → LSPosedManager.isModuleActive(pkg)
          → ContentResolver.query(content://<status-authority>/active/<pkg>)
            → ModuleStatusProvider
              → reads <manager files>/.markers/*.json
              → returns active=1 or active=0

### The Android 11+ package visibility requirement

A module that queries the status provider from its own UI must declare the provider's authority in the module's own `AndroidManifest.xml`:

    <queries>
        <provider android:authorities="com.shizuposed.manager.status" />
    </queries>

Without that declaration, every query returns an empty cursor. (After a cloak install, the authority changes to `<new-package-name>.status` — the module's `<queries>` declaration would need updating to match, or the module can query both.)

### When a module still reports "not activated"

The module detail sheet has a **Run activation diagnostic** action. It reports:

  * Whether the module is in the manager's module list.
  * Whether its cached dex is present on the shell side.
  * How many apps it's scoped to.
  * Whether any marker lists it.
  * The `<queries>` declaration it needs on Android 11+.

If the diagnostic says the marker exists but the module UI still shows "not activated," the module is checking a signal ShizuPosed doesn't provide.

---

## Module development

ShizuPosed supports two module APIs.

### Classic API (`de.robv.android.xposed.*`)

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

### `IXposedHookZygoteInit` example

    public class MainHook implements IXposedHookLoadPackage,
                                     IXposedHookZygoteInit {
        @Override
        public void initZygote(StartupParam startupParam) {
            XposedBridge.log("module loaded from "
                + startupParam.modulePath);
            // one-time setup
        }

        @Override
        public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
            // per-package hooks
        }
    }

### libxposed API (`io.github.libxposed.api.*`, API 101)

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

#### What each callback sees

| Callback | When it fires | What's available |
|---|---|---|
| `onModuleLoaded` | After class load, before any package work | `ModuleLoadedParam.getProcessName()`, `isSystemServer()` (always `false`) |
| `onPackageLoaded` | During bootstrap, after classloader resolved, before `Application.onCreate` | `PackageLoadedParam.getPackageName()`, `getApplicationInfo()`, `getDefaultClassLoader()`, `isFirstPackage()` |
| `onPackageReady` | After `Application.onCreate`, before any activity | Everything from `onPackageLoaded`, plus `getClassLoader()` and `getAppComponentFactory()` |
| `onSystemServerStarting` | Never | — |

### Using the lifecycle bridges

Both bridges work identically regardless of which API a module speaks.

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

### Reporting state in a module UI

    boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
    boolean active  = XposedBridge.isModuleActive(getPackageName());
    boolean loaded  = XposedBridge.isModuleActiveAnywhere(getPackageName());
    String[] scope  = XposedBridge.getModuleScope(getPackageName());

### Resource replacement

Classic API:

    public class Res implements IXposedHookInitPackageResources {
        @Override
        public void handleInitPackageResources(
                XC_InitPackageResources.InitPackageResourcesParam resparam) {
            resparam.res.setReplacement(R.string.some_string, "replacement");
        }
    }

libxposed API 101 does not include a resource-replacement callback.

### Layout hooks

Classic API:

    resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
        @Override
        public void handleLayoutInflated(LayoutInflatedParam param) {
            // param.view, param.resId, param.resName are set
        }
    });

Layout hooks are classic-only in API 101.

### Writing a hook that works on every backend

Because Amiru and Native can't carry object arguments or object returns, a module that needs those should either rely on Pine succeeding, avoid object arguments entirely, or accept that the hook may not fire with the values it expects.

Pine honors every argument mutation a `beforeHookedMethod` or `afterHookedMethod` makes, including receiver swaps, arg rewrites, and post-invocation result replacement. Constructors are hookable on Pine and Native; Amiru and Proxy do not support constructor hooks.

For libxposed modules, `Chain.getArg(i)` returns `null` on Amiru or Native. `Chain.proceed()` is implemented as a synchronous original-method invocation; it works on Pine AUTO and Pine REPLACEMENT.

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is **Shizuku-Next** by rushiranpise at `github.com/rushiranpise/Shizuku-Next`. About 200 MB free storage. No root required.

For building the app: JDK 21 (with a Gradle toolchain), Gradle 9.8, Android SDK 37. Native libraries rebuild with `clang` alone on Termux. Building with the Android NDK (r25+) works and is what CI uses.

Upstream Shizuku works. Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (Shizuku-Next recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Download the APK from the Releases page:

  * `app-release.apk` — the single build.

Install it:

    adb install -r app-release.apk

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope. Then either tap **Launch App under ShizuPosed** and pick a scoped app, or just open a scoped app normally; the process monitor will trigger a spawn-only injection for it.

### Changing the installed package name

If a target app checks for `com.shizuposed.manager` by name, use **Settings → Hide ShizuPosed app**:

  1. Tap **Hide ShizuPosed app**.
  2. Leave both fields blank for a random package name and label, or type specific values.
  3. Tap **Hide**.

The manager rebuilds the APK, installs it via Shizuku, and uninstalls the old package. The migrate zip carries the current settings, module DB, and keystore into the new install, so it comes up where the old one left off.

**Unhide ShizuPosed app** reverses the operation: downloads the latest standard APK from GitHub, restores the original package name, installs it, and uninstalls the cloaked package.

**The cloak flow does not hide behavior.** A target app that detects the hook by checking its own method entry points still detects it. And because every cloak shares the same bundled signing key, a target app that fingerprints the signature can identify cloaked ShizuPosed installs regardless of package name.

---

## The manager

Five tabs.

**Home** shows the status card, the counter tiles, and the Framework Info card.

**Modules** lists installed modules with XStealth always at the top. Each third-party module row shows an API badge when the module declares one.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has the runtime toggles, scan interval and hook delay sliders, the Sysku toggle and status line, the Maintenance section (including **Hide ShizuPosed app** and **Unhide ShizuPosed app**), a **Check for updates** button, and config export.

### Module detail sheet

For any module, the detail sheet has a **Run activation diagnostic** action.

### XStealth detail sheet

Fifteen toggles, organized by layer:

**Master toggles**
  * **Enable XStealth**
  * **Enable XStealth Next**
  * **Use bridge**

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

A separate line shows Sysku's state, sourced from `SyskuController.getState()`. The state names it can report are `Disabled`, `Not installed`, `Enabled (lib failed to load)`, `Enabled (module not running)`, `Enabled (load failed)`, and `Enabled`.

The bridge's coverage line, sourced from `XStealthBridge.nativeDescribe()`, reports `tracked_dirs`, `procPidReadsBlocked`, `getppidIntercepted`, `threadRenames`, and whether the path classifier comes from `libxstealth` or the local fallback.

---

## Provenance and verification

ShizuPosed is built from sources in this repository. The APK is reproducible from a clean checkout with the documented toolchain.

**Signed builds.** The released APK is signed with the project's release key.

**Native library provenance.** Each `.so` is built from the `.c` file with the same name in the `native/` directory.

**What to look for in a release.** The release notes list the NDK version used, the SDK version, the Gradle and JDK versions, and a SHA-256 of each native library. Pine's SHA-256 should match the upstream canyie/pine release — if it doesn't, reject the build.

**Vendored libxposed API.** The `io.github.libxposed.api.*` classes in `XposedHook.dex` are vendored from the upstream libxposed API 101 definitions. The vendored copies are interface-only.

**Bundled cloak keystore.** `assets/keystore` is the signing key the Hide ShizuPosed app flow uses to re-sign rebuilt APKs. It is public — it has to be, for the flow to work. It is not the release signing key.

---

## Building

### The app

    export ANDROID_HOME=$HOME/Android/Sdk
    git clone <repo>
    cd ShizuPosed
    gradle clean :app:assembleRelease

### The native libraries

The six native libraries (`libshizuposed.so`, `libamiru.so`, `libcallsite.so`, `libxstealth.so`, `libxstealth_next.so`, `libxstealth_bridge.so`) and the optional `libsysku.so` build with the Android NDK. On Termux, an ARM64 Android NDK is required. Build for API 29 or higher.

`libsysku.so` builds with a single `clang` command. It is optional; the build succeeds without it.

### The build checks

The `app/build.gradle` includes seven verification tasks: `checkNativeShim` (required), `checkCallSite`, `checkAmiru`, `checkXStealth`, `checkXStealthNext`, `checkXStealthBridge`, `checkSysku` (all optional, warn-only). Skip all seven with:

    gradle assembleRelease -PskipNativeCheck=true

### Configuration cache

The build is configuration-cache-compatible with Gradle 9.8.

---

## Reading the launch log

Every routed launch writes stderr and stdout from `app_process` to `<shell-base>/launch.log`:

    cat /data/user/0/com.android.shell/files/.syscall_cache/launch.log

When a classic module loads, the log records:

  * the entry class name,
  * `initZygote called for <class> (modulePath=<apk>)` — the new 1.2.9 line,
  * `Built ModuleClassLoader for <pkg> (module=DexClassLoader, target=PathClassLoader)` — the new 1.2.9 line,
  * `Calling handleLoadPackage on <class> for pkg=<pkg>`,
  * any `Pine.hook` or `XposedBridge.hookMethod` log lines.

`logcat -s PineBackend` shows whether Pine initialized and every hook it installed or declined.

Each bootstrap also writes a marker to `<shell-base>/hooked/<pkg>.json` when it completes. The marker's `engines` object reports which native engines loaded, including `sysku` and `sysku_fallback`.

The bridge's appearance layer is visible in `nativeDescribe()`: `threadRenames` climbs when the target enumerates `/proc/self/task`, and `getppidIntercepted` climbs when the target calls `getppid()`. If both stay at zero, the target is not probing those surfaces — which is fine; the layer is a safety net, not a required path.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. Cross-process hooks aren't possible. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework.

**Protected UIDs are unreachable.** The shell UID cannot `setuid` to `SYSTEM_UID` (1000) or the reserved system UID pool (1001–1999). The scope editor hides them.

**Zygote-timing APIs.** Modules that implement `IXposedHookZygoteInit` (classic) get an `initZygote()` call at bootstrap, but the process model differs from a real zygote fork — one call per bootstrapped process, not one call per zygote. Modules that implement `onSystemServerStarting` (libxposed) never get the callback.

**The process is not a zygote fork.** After `setuid`, it runs as the target UID and can read the target's data, but its parent process is `app_process`, not `zygote`. XStealth's appearance layer scrubs `getppid()`, the `PPid:` and `Name:` fields in `/proc/self/status`, the comm field in `/proc/self/stat`, `/proc/self/exe`, `/proc/1/comm`, `/proc/self/cmdline`, `/proc/self/mountinfo`, and `/proc/self/cgroup`. The scrub is userspace, not lineage. `Uid:`, SELinux context, and process start time are unchanged.

**The appearance layer is trust-gated.** Framework threads that call `getppid()` see the real parent PID, not `1`. This is deliberate — the framework's own code needs to know its real parent in some paths, and returning `1` would break those. A detector running on a thread whose comm matches a framework prefix sees the real ppid; every other thread sees `1`. The gate is based on the thread's `comm`, which a determined detector could mimic — the gate is a correctness measure, not a security boundary.

**The appearance layer requires `libxstealth`'s exports.** The bridge falls back to a local hidden-string list when `libxstealth.so` isn't loaded, but the `readlink` substitutions and the `getppid` gate only fire when `libxstealth` is present and active. A build that skips `libxstealth.so` loses the appearance layer entirely.

**Monitor-triggered injection doesn't attach to the detected pid.** When the monitor sees a scoped app start, it can only trigger a spawn-only routed launch.

**Monitor-triggered injection never opens the UI.**

**Object arguments and object returns on the native backends.** Amiru and Native pass object arguments to Java callbacks as `null`, and don't accept object return values. `thisObject` is also `null` on both. Pine, Instrumentation, and Proxy handle objects correctly.

**The classic API reports 101.** A module that hardcodes `getXposedVersion() == 100` may fail; the fix is `>= 100`.

**libxposed reports 101, not 102.**

**libxposed remote IPC is unimplemented.**

**libxposed `deoptimize` is advisory.**

**libxposed `Chain.proceed()` depends on the backend.** Works on Pine AUTO and Pine REPLACEMENT.

**Sysku is scope-limited to what a syscall interposer can see.**

**Sysku is not standalone.** It requires `libxstealth.so` v0.18.0+ for its four `xstealth_*` exports.

**Sysku shares XStealth's master switch.**

**Sysku in fallback mode doesn't filter `getdents64`.**

**Sysku reports `Enabled (lib failed to load)` on builds that skip `libsysku.so`.** The build succeeds without it, but the Settings tab cannot report `Enabled` until `libsysku.so` is bundled and deployed.

**Entry-point shape matching has a ceiling.**

**XStealth sanitizes reads, not values.**

**XStealth's practical ceiling.**

**The appearance layer cannot fake kernel state.** `Uid:`, SELinux context, and process start time are kernel-enforced. A detector that reads those surfaces sees the truth. `/proc/self/cgroup` is substituted (the *path* is userspace-visible), but a detector that resolves the cgroup controller sees the real membership. `/proc/self/attr/current` and `/proc/self/auxv` are not substituted, because a substitution would disagree with `getcon()` / `mmap()` and that disagreement is a stronger tell than the truth. The appearance layer closes the userspace-visible surfaces that are cheap to close; it does not make the process a real zygote fork.

**The cloak flow ships the signing key.** Re-signing an APK requires the private key, and the private key must be available to the device to re-sign at runtime. The key is bundled at `assets/keystore`. Every cloaked install shares a signature. This is the trade-off for the single-APK convenience; keeping the signing key off the device (and shipping a separate build) avoids it.

**The cloak flow requires Shizuku.** Installing the rebuilt APK and uninstalling the old package both go through Shizuku. If Shizuku isn't authorized, the flow can't run.

**The cloak flow requires a single-APK install.** Split-APK installs (App Bundle, some distribution channels) can't be rebuilt cleanly. The flow detects this and refuses.

**After a cloak, the status provider authority changes.** A module that queries `com.shizuposed.manager.status` will get an empty cursor from a cloaked install. The module's `<queries>` declaration would need to include the cloaked authority, or the module can query both.

**Unhide downloads from GitHub.** The Unhide flow needs network access to `github.com` and the latest `app-release.apk` release asset. If either is unavailable, Unhide fails.

**Bridge layer caveats.** The bridge library filters `dl_iterate_phdr` process-wide. `getenv` scrubbing only covers the API path. `/proc` enumeration is not filtered.

**Subprocess hiding has a load-order window.**

**Socket shim is inert by default.**

**Modules that require LSPosed.**

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**Binder-level hiding has structural gaps.**

**Banking apps that ship native runtime-integrity SDKs (BioCatch and friends).** XStealth shuts down the file and property channels BioCatch reads through libc, and Sysku extends this to the raw `syscall()` and `getdents64()` paths. What neither closes:

  * The **USB Binder channel** — BioCatch calls `IUsbManager.getCurrentFunctions()` directly.
  * The **ART-integrity channel** — shape matching closes the byte-pattern check on the entry point's first instructions.
  * **Behavioral signals** — tap timing and swipe pressure.

The only workaround that holds up:

  1. Turn off Developer Options completely.
  2. Turn off USB debugging.
  3. Reboot.
  4. Open the banking app.

---

## License

    Copyright 2026-2027 ShizuPosed Contributors

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

Rikka, for Shizuku; the primitive that makes all of this possible. canyie, for Pine, the primary ART hooking engine. LSPosed, for the API generation ShizuPosed targets, the design of the module status provider contract, and the `ManifestEditor` / `apkzlib` components the cloak flow is built on. The libxposed project, for the modern `io.github.libxposed.api.*` surface.

And every module author who kept the `de.robv.android.xposed.*` API alive long enough for an alternative to matter — and every module author who has moved to `io.github.libxposed.*`.