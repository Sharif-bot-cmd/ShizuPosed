# ShizuPosed

**A non-root Xposed-compatible hook framework built on `app_process`.**

ShizuPosed runs Xposed-API modules in apps launched through it — no root, no bootloader unlock, no system partition changes. Shizuku invokes `app_process` as the shell UID, a Java runtime bootstraps inside the target's process, and method hooks go in through a multi-backend dispatcher. Modules written for LSPosed and classic Xposed keep working.

Version 5.9.

---

## What this is, and what it isn't

This part comes first because everything else is downstream of it.

ShizuPosed is **not** a drop-in LSPosed replacement. It doesn't try to be. It uses a different injection primitive — `app_process` into a target launched by ShizuPosed — because that's the only injection path that reliably works without root on modern Android. That choice is what makes ShizuPosed possible. It's also what defines the ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or `Service.onCreate` coverage, use LSPosed. ShizuPosed exists for the people who can't root or won't, and who still want LSPosed-API modules to work in the apps ShizuPosed launches.

The rest of this document is honest about where that line falls.

---

## Why `app_process`

It helps to understand what ShizuPosed *can't* do before looking at what it can.

Without root, the injection primitives on Android 10+ are narrow. `ptrace` is blocked by SELinux for non-root UIDs. `LD_PRELOAD` needs a writable, executable path the target will load from, and app data directories are mounted `noexec`. Zygote fork requires being inside zygote, which means root or being the ROM. `Runtime.exec` runs as the caller's UID, not the target's. `am instrument` gives you an `Instrumentation` handle only after `Application.onCreate` — too late for bootstrap hooks. ContentProvider hijack works only if the target already declares a provider you can take over.

`app_process` is the one that works. It's a platform binary that exists on every Android device. Shizuku can invoke it as shell (UID 2000). It starts a Java runtime, so you can load arbitrary dex into it. And Shizuku can pass the target package and UID as arguments.

That's the entire mechanism. Everything else in ShizuPosed is a consequence of it.

---

## The timing model

LSPosed is a zygote-timing framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a bootstrap-timing framework: it's `app_process`-launched into the target, installs hooks, then drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

What ShizuPosed reaches in bootstrap mode:

- `Application.attachBaseContext` — hookable
- `Application.onCreate` — hookable
- `ContentProvider.onCreate` — hookable
- `Activity.onCreate` — hookable via Pine, Amiru, Native, or Instrumentation
- `LayoutInflater.inflate` — hookable, drives `XC_LayoutInflated` callbacks
- `Service.onCreate` — not hookable
- `system_server` — not hookable

The `Service.onCreate` gap is structural. There's no fallback because there's no mechanism. If you need it, LSPosed is the answer.

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
  ├── IXposedHookCmdInit         pre-Application dispatch
  ├── HookEngine                 backend selection + fingerprint tracking
  ├── HookDispatcher             per-method backend chain
  ├── DexLoadingBridge           dex load fallback ladder
  ├── ModuleLoader               dex load + entry invocation
  ├── ResourceHooking            resource + layout hooks
  ├── XStealthModule             built-in privacy module
  │     ├── DevOptionsCheck      settings + resolver hooks
  │     ├── AdbCheck             settings + resolver hooks
  │     ├── PackageCheck         PackageManager lookups
  │     ├── RunningProcessCheck  ActivityManager lookups
  │     ├── ApiProtectionCheck   reflection + baselines
  │     ├── XStealthNative       libc symbol interposition
  │     └── XStealthNativeNext   libc syscall stub patching
  └── markers written to         <shell-base>/hooked/

Manager process
  ├── ProcessMonitor             /proc scan + marker mirror refresh
  ├── MarkerCache                local mirror of <shell-base>/hooked/
  ├── IconResolver               LruCache for app icons
  └── ModuleStatusProvider       reads the mirror, never Shizuku
```

The manager never touches the target process directly. Everything passes through Shizuku.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table — used when a target inspects `ArtMethod` entries to detect the other backends. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

`HookEngine` records the declaring class of every method or constructor it attempts to hook. That record feeds `ApiProtectionCheck`'s fingerprint baselines, so the framework knows which framework classes it has touched and can pin their method counts.

---

## The dex-loading fallback ladder

Loading a module's dex into a target process can fail for several reasons: the target's cache directory isn't writable, `DexClassLoader` is restricted, the ROM blocks dynamic code loading through certain paths. `DexLoadingBridge` tries three strategies in order:

1. **`InMemoryDexClassLoader`** (Android 8+). Reads the dex into a `ByteBuffer` and loads it without any filesystem write. This is the most reliable path because nothing it does can be blocked by a `noexec` mount or a read-only cache directory.
2. **`DexClassLoader`.** The classic path. Reads the dex from a file and uses an optimization directory for ART's compiled output.
3. **`BaseDexClassLoader` injection.** Appends the module's dex to the target's existing classloader by reflection into its `DexPathList`. This is the deepest fallback — used only when both loaders above fail. It doesn't create a new loader; it augments the one the target already has.

Each strategy logs its outcome. If all three fail, the module is skipped with a specific reason logged, rather than a generic load error.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

### How it compares to Shamiko

XStealth is often described as a "Shamiko equivalent" for non-root setups. Useful shorthand, but not literal. Shamiko runs inside zygote and hides root artifacts before the target starts. XStealth runs inside the target and hides ShizuPosed's own artifacts. Different mechanism, lower ceiling.

### What XStealth hides

**Developer Options and ADB.** Hooks every settings read path: `Settings.Global` and `Settings.Secure` static getters, the `ForUser` variants, and direct `ContentResolver.query` calls against `content://settings/{global,secure}/<key>`.

**Shizuku and ShizuPosed packages.** Intercepts `PackageManager.getPackageInfo`, `getApplicationInfo`, `getInstalledPackages`, and `getInstalledApplications` — the int overloads and the newer `PackageInfoFlags` / `ApplicationInfoFlags` overloads added in Android 13.

**Running processes.** Filters `ActivityManager.getRunningAppProcesses`, `getRunningServices`, and `getRunningTasks`.

**`/proc` entries.** With the native layer active, hidden strings are scrubbed from `/proc/self/maps`, `cmdline`, `status`, and `mountinfo`.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` when the caller isn't a loaded module or the framework itself. It also filters `getDeclaredMethods`, `getMethods`, `getDeclaredConstructors`, and `getDeclaredFields` on shim classes, and hooks `getResource`, `getResourceAsStream`, `getResources`, and `getPackage` for shim paths.

**Method-count fingerprinting.** `ApiProtectionCheck` pins a baseline for every framework class the framework has actually hooked, plus a static list of common detection targets. Every later `getDeclaredMethods()` or `getMethods()` against those classes returns the pinned count for non-trusted callers.

### What XStealth doesn't hide

**Hardware attestation.** Play Integrity's `MEETS_STRONG_INTEGRITY` is a cryptographic proof of bootloader and ROM state. XStealth doesn't touch any of the inputs to that check.

**Root-empowered inspection.** An app that already has root can read `/proc` directly, bypassing both the Java and libc layers.

**Server-side cross-reference.** XStealth can spoof what an app reports locally. A server that cross-checks against Play Store inventory notices.

**Native reads of the settings database.** An app that reads `/data/system/users/0/settings_global.xml` via native code, or talks to the settings provider over raw binder, bypasses the Java hooks.

**`Runtime.exec("settings get ...")`.** A subprocess that reads the real setting isn't affected by hooks in the app's process.

**Method-list reconstruction.** `ApiProtectionCheck` pins method *counts*, not method *lists*.

**Exotic reflection.** `Unsafe`, `Class.getDeclaredMethods0` (the JNI-level private method), and similar paths bypass the Java hooks entirely.

**Signals unrelated to ShizuPosed.** XStealth hides ShizuPosed's own footprint. It doesn't hide: what keyboard you have installed, what accessibility services are enabled, what overlay permissions apps hold, what apps you have installed in general, whether the bootloader is unlocked, whether the device is a custom ROM, whether a screen recorder is running, or Play Integrity state. A banking app that flags one of those signals displays the warning it fails on — and it may not be Developer Options.

---

## XStealth Next — the aggressive engine

The primary native engine (`libxstealth.so`) interposes libc functions by symbol. XStealth Next closes the gap for targets that resolve the syscall stub directly or go through the generic syscall dispatcher. It finds the libc stubs and inline-patches their prologues with a branch to a per-syscall handler. It also patches the generic `syscall()` dispatcher and covers `fstatat`/`newfstatat` in addition to `stat`/`lstat`.

Each stub's prologue is validated before patching. Unknown shapes are refused rather than corrupted.

Turning Next on renames the module's display in the Modules tab to **XStealth (Next)**. The package name never changes.

### What Next still doesn't catch

A target that emits its own `svc #0` sequence never touches the libc stub. There's no user-space way to intercept raw syscall instructions without `ptrace` or an LSM.

---

## API surface — version 96

ShizuPosed reports Xposed API version **96** (LSPosed generation 93, plus fork revisions 94, 95, and 96). Modules that guard on `getXposedVersion() >= N` for `N` up to 96 accept ShizuPosed as compatible.

What each version level means for the shim:

- **93** — LSPosed base. `IXUnhook`, `isModuleActive` and `isModuleEnabled` with and without arguments, provider-backed state queries.
- **94** — `XposedBridge.hookAllMethods` and `XposedHelpers.hookAllMethods` return `Set<XC_MethodHook.Unhook>`.
- **95** — `hookAllConstructors` returns the same.
- **96** — `MethodHookParam.isReturnEarly()` exposed, distinguishing "result set to `null`" from "result not set."

Modules that guard on 97 or higher correctly believe ShizuPosed doesn't support those fork revisions, and fall back to their earlier code paths.

### `IXUnhook` and `XC_MethodHook.Unhook`

Both forms of the unhook handle exist:

- `IXUnhook<T extends XC_MethodHook>` — returned by `XposedBridge.hookMethod`, `XposedBridge.hookConstructor`, `XposedHelpers.findAndHookMethod`, and `XposedHelpers.findAndHookConstructor`.
- `XC_MethodHook.Unhook` — the element type of the `Set` returned by `hookAllMethods` and `hookAllConstructors`.

Both support `getHookedMethod()` and `unhook()`. Whether `unhook()` actually reverses the install depends on the backend:

| Backend | `unhook()` behavior |
|---|---|
| CallSite | Reversible in principle (flag cleared when wired) |
| Proxy | Reversible in principle |
| Noop | Trivially succeeds — nothing was installed |
| Pine AUTO | Logs that the backend can't reverse |
| Pine REPLACEMENT | Logs that the backend can't reverse |
| Amiru | Depends on the native library |
| Native | Depends on the native library |
| Instrumentation | Depends on the implementation |

When a backend can't reverse, `unhook()` is a logged no-op. It never throws. Modules that call `unhook()` in a cleanup path see a clear log line rather than a crash.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers and caches the APK as a dex container:

1. `assets/xposed_init` — the canonical marker, contains the entry class.
2. `assets/xposed_module` — an older convention, same content.
3. `META-INF/xposed/module.prop` — EdXposed-era, key=value format.
4. `assets/native_init` — a weak signal. The module ships native hooks and may have no Java entry point.

A module that ships none of the four markers is not detected.

Once detected, the descriptor and dex go to the shell side. `XposedHook` reads the module JSON, checks the target against the module's scope, and loads the dex through `DexLoadingBridge` — which tries in-memory loading, then `DexClassLoader`, then classloader injection.

Built-in modules skip the dex load. The marker file is the source of truth for activation state.

### Self-hook modules

Some modules determine their own activation state by installing a hook on one of their own UI methods. Under LSPosed, this works automatically. Under ShizuPosed, when you tap **Open module app** in a module's detail sheet, ShizuPosed launches the module's own UI through itself, installing hooks into the module's process before the UI starts. The self-hook installs, the UI reads it back, and the activation state is reported correctly.

### Recommended scope

Modules can declare which apps they're *intended* for by shipping an `assets/scope.list` file inside the APK. ShizuPosed reads that file and surfaces the packages as **recommended**: a chip in the scope editor that selects them, a badge on their rows, and a count on the module's row.

Modules without a `scope.list` see no change.

---

## Activation state — how "Activated" actually works

Two distinct questions. "Am I enabled?" comes from `XposedBridge.isModuleEnabled(pkg)`, sourced from the manager's preference store. "Am I active?" comes from `XposedBridge.isModuleActive(pkg)`, sourced from the shell-side marker files.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per hooked target to `<shell-base>/hooked/<pkg>.json` after installing hooks. The manager mirrors the markers into its own files directory. `ProcessMonitor` runs one compound shell command every five seconds and writes each marker into `<manager files>/.markers/<pkg>.json`. `ModuleStatusProvider` reads from that mirror.

- **Queries are local file reads.** No Shizuku in the query path.
- **Activation state survives Shizuku outages.**
- **The shell cost is bounded.** One command per five seconds.

The marker write is atomic — a temp file followed by a rename — so a concurrent refresh sees either the old marker or the new one, never a truncated body.

### The query chain

```
Module UI process
  → XposedBridge.isModuleActive(pkg)
    → LSPosedManager.isModuleActive(pkg)
      → ContentResolver.query(content://com.shizuposed.manager.status/active/<pkg>)
        → ModuleStatusProvider
          → reads <manager files>/.markers/*.json
          → returns active=1 or active=0
```

The provider caches its parsed scan for three seconds, on top of the five-second mirror refresh. Worst-case latency is about eight seconds.

### The Android 11+ package visibility requirement

On Android 11 and later, a module that queries the status provider from its own UI must declare the provider's authority in the module's own `AndroidManifest.xml`:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

Without it, the query returns null regardless of the provider being exported. When the query returns empty on Android 11+, the shim logs a specific warning naming the missing declaration.

---

## Module development

Standard Xposed API.

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

### Reporting state in a module UI

```java
boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
boolean active  = XposedBridge.isModuleActive(getPackageName());
String[] scope  = XposedBridge.getModuleScope(getPackageName());
```

Or the no-arg form:

```java
boolean enabled = XposedBridge.isModuleEnabled();
boolean active  = XposedBridge.isModuleActive();
```

For the module UI to reach the provider on Android 11+, the module's own `AndroidManifest.xml` needs the `<queries>` declaration above.

### Resource replacement

```java
public class Res implements IXposedHookInitPackageResources {
    @Override
    public void handleInitPackageResources(
            XC_InitPackageResources.InitPackageResourcesParam resparam) {
        resparam.res.setReplacement(R.string.some_string, "replacement");
    }
}
```

Only the programmatic form is supported.

### Layout hooks

```java
resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
    @Override
    public void handleLayoutInflated(LayoutInflatedParam param) {
        // param.view, param.resId, param.resName are set
    }
});
```

The global `inflate` hook is installed before `Application.onCreate` in bootstrap mode. In post-application mode it's installed later — layouts already inflated are missed.

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is the fork by **thedjchi** at `github.com/thedjchi/Shizuku`. About 200 MB free storage. No root required. For building: JDK 21 and Android SDK 37. For rebuilding the native libraries: `clang` (Termux `clang` or NDK r25+).

Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (fork recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Install the ShizuPosed Manager APK:

```
adb install -r ShizuPosed-R-5.9.apk
```

Or just tap the APK to install it. ADB is not required for the manager itself — only for starting Shizuku.

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to. Then tap **Launch App under ShizuPosed** and pick a scoped app.

Activation isn't retroactive. A module's UI will show "Activated" only after at least one scoped app has been launched through ShizuPosed.

---

## The manager

Five tabs.

**Home** shows the Framework Info card, the status card, the counters, and the list of currently hooked processes.

**Modules** lists installed modules with XStealth always at the top. Tapping a module opens its detail sheet.

**Repo** shows every installed module with its README, metadata, and quick links. READMEs are read from `assets/README.md`, `assets/readme.md`, or `README.md` at the APK root.

**Logs** shows the manager's own log with search.

**Settings** has the runtime toggles, cache management, and config export.

---

## Building

```bash
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosed
./gradlew clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Changelog

### 5.9

```
NEW
• API version 96. XposedBridge.getXposedVersion() now reports
  96 (LSPosed generation 93 plus fork revisions 94-96). Modules
  that guard on >= 96 accept ShizuPosed as compatible.

• IXUnhook interface and XposedBridgeUnhook implementation.
  XposedBridge.hookMethod and XposedHelpers.findAndHookMethod
  return an IXUnhook<XC_MethodHook> handle. The handle exposes
  getHookedMethod(), getCallback(), and unhook(). Whether
  unhook() reverses the install depends on the backend; when a
  backend can't reverse, unhook() is a logged no-op.

• XC_MethodHook.Unhook nested class. XposedBridge.hookAllMethods
  and XposedBridge.hookAllConstructors now return
  Set<XC_MethodHook.Unhook>, one handle per installed hook. An
  empty set means no methods matched.

• MethodHookParam.isReturnEarly(). Distinguishes "result
  explicitly set to null" from "result not set."

• DexLoadingBridge. Loading a module's dex into a target now
  tries three strategies in order: InMemoryDexClassLoader (no
  filesystem write), DexClassLoader, and BaseDexClassLoader
  path-list injection. Each strategy logs its outcome. If all
  three fail, the module is skipped with a specific reason
  rather than a generic load error.

CHANGED
• HookEngine tracks the declaring class of every method and
  constructor it attempts to hook, exposed via
  getHookedClassNames() for ApiProtectionCheck.

• HookEngine.InstallRecord records which backend installed each
  hook, so IXUnhook.unhook() can look up whether a reverse
  action is available.

• HookDispatcher exposes getLastInstalledBackendName() and
  getLastInstalledReverse() for HookEngine to consume.

• XposedHelpers.hookAllMethods and hookAllConstructors are thin
  forwarders to XposedBridge, which builds the handle set.

NOTES
• unhook() is a real reverse on backends that support it
  (CallSite, Proxy, Noop). On Pine, Amiru, Native, and
  Instrumentation, it logs a warning naming the backend and
  leaves the hook in place. This is documented behavior, not a
  bug.

• DexLoadingBridge is a load-time fallback ladder, not a
  hooking backend. It participates in the module load path only;
  it doesn't register with HookDispatcher.

• No changes to the hook backends themselves, the dispatcher
  chain, or the module format.
```

### 5.7

```
NEW
• Deeper settings hooks. DevOptionsCheck and AdbCheck now hook
  Settings.Global.getIntForUser / getStringForUser / getLongForUser
  in addition to the plain getters, and hook ContentResolver.query
  directly for content://settings/{global,secure}/<key> URIs.

• Expanded package checks. PackageCheck now hooks the Android
  13+ PackageInfoFlags and ApplicationInfoFlags overloads.

• RunningProcessCheck now hooks getRunningTasks.

• ApiProtectionCheck expanded. Now hooks ClassLoader.getResource,
  getResourceAsStream, getResources, and getPackage, plus
  Package.getPackage. Filters Class.getDeclaredMethods and
  friends on shim classes.

• Fingerprint baselines. ApiProtectionCheck pins method-count
  baselines on framework classes ShizuPosed has hooked.

• Icon cache. IconResolver wraps its lookups in an LruCache.

CHANGED
• XStealth scope removed. XStealth applies to every app
  ShizuPosed launches.

PERFORMANCE
• Scope editor no longer passes GET_META_DATA to
  PackageManager.getInstalledApplications.
• Scope editor no longer calls ModuleLoader.loadModules() on
  the UI thread.
• IconResolver caches resolved icons.
```

### 5.6

```
NEW
• XStealth scope (initial design).
• ScopeProvider abstraction.
```

### 5.5

```
NEW
• XStealth scope (first implementation).
```

### 5.4

```
NEW
• Self-hook module support. "Open module app" routes through
  ShizuPosed when the prerequisites are met.
```

### 5.3

```
NEW
• Marker mirror. Activation queries no longer call Shizuku.
• No-arg overloads of isModuleActive() and isModuleEnabled().
• Android 11+ package visibility diagnostic.
• Cross-process shell base persistence.
• Background-thread context fallback in the shims.
• Atomic marker write.
```

### 5.2

```
NEW
• Broader module detection. Four markers instead of one.
```

### 5.1

```
NEW
• Repo tab with README rendering.
• Recommended scope.
• IXposedHookCmdInit.
• Layout hooks (XC_LayoutInflated).
• XCallback and XCallback.Priority marker types.
```

### 5.0

```
NEW
• ShizuPosed 5.0 release.
• Shell base fallback and path resolution hardened.
• Multi-backend dispatcher refined.
```

### Earlier

See the release notes for 4.9 and below on the Releases page.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. `Service.onCreate` has no fallback. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework. Modules that require zygote-wide timing won't work. Hooks only reach processes ShizuPosed launches — apps started from the launcher or by the system are unaffected.

**Activation reporting in module UIs.** Module UIs that check their own activation state by hooking one of their own methods will report "not activated" unless their UI is launched through ShizuPosed.

**What does work:** modules that check activation via `XposedBridge.isModuleActive()`, `LSPosedManager.isModuleActive()`, the status provider, or `XSharedPreferences` report correctly without any extra step. The manager's **Open module app** button routes through ShizuPosed when it can.

**`unhook()` on most backends is a no-op.** Pine, Amiru, Native, and Instrumentation don't expose a reverse. CallSite, Proxy, and Noop do. Modules that rely on unhook() should check whether the handle's backend supports it, or accept the logged no-op.

**Dex loading fallback ladder.** `InMemoryDexClassLoader` requires Android 8+. `BaseDexClassLoader` injection relies on reflection into hidden internals and is version-fragile — it's a fallback, not a primary path.

**XStealth's practical ceiling.** XStealth covers the common detection paths for Developer Options, ADB, package presence, running processes, `/proc` reads, and reflection walks. It doesn't cover:

- Hardware attestation (Play Integrity `MEETS_STRONG_INTEGRITY`).
- Native reads of the settings database, or raw binder to the settings provider.
- `Runtime.exec("settings get ...")` subprocesses.
- Method-list reconstruction on framework classes (only method counts are pinned).
- Exotic reflection via `Unsafe` or JNI-level private methods.
- Kernel-level watchers, or inspection from a root process outside the target.
- Signals unrelated to ShizuPosed: keyboard, accessibility services, overlay permissions, bootloader state, custom ROM, screen recorders, Play Integrity.

A banking app that shows a warning may be failing on any of these, not just the ones XStealth hides.

**Android 11+.** Module UIs that want to report Activated must declare ShizuPosed's status provider authority in their own `<queries>` block.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, JIT-inlined callers aren't invalidated, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

---

## License

```
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
```

---

## Acknowledgements

Rikka, for Shizuku — the primitive that makes all of this possible. canyie, for Pine, the primary ART hooking engine. LSPosed, for the API generation ShizuPosed targets, and for the design of the module status provider contract.

And every module author who kept the `de.robv.android.xposed.*` API alive long enough for an alternative to matter.