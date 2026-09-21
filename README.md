# ShizuPosed

**A non-root Xposed-compatible hook framework built on `app_process`.**

ShizuPosed runs Xposed-API modules in apps launched through it — no root, no bootloader unlock, no system partition changes. Shizuku invokes `app_process` as the shell UID, a Java runtime bootstraps inside the target's process, and method hooks go in through a multi-backend dispatcher. Modules written for LSPosed and classic Xposed keep working.

Version 5.8.

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

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite is the interpreter-table backend, described below. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

`HookEngine` records the declaring class of every method or constructor it attempts to hook. That record feeds `ApiProtectionCheck`'s fingerprint baselines.

---

## The CallSite backend

Pine, Amiru, and Native all hook methods by **modifying the method's `ArtMethod` entry point**. That's the standard technique, and it works on the vast majority of methods. But it has one visible side effect: the entry pointer changes. A target app that reads the entry pointer — which some anti-tampering code does — can see that the method has been hooked.

CallSite is a different technique. Instead of modifying the method, it **patches ART's interpreter dispatch table**. When the interpreter would invoke a flagged method, it routes through a stub that dispatches to Java and then calls the original implementation. The method's `ArtMethod` entry pointer is left untouched. A target that reads it sees the original.

### What it covers

Interpreted execution only. ART has three execution modes: interpreted, JIT-compiled, and AOT-compiled. CallSite intercepts the interpreter. Once a method has been JIT-compiled (or was AOT-compiled at build time), the interpreter is bypassed and CallSite has no effect.

In practice, hooks installed early — from `Application.attachBaseContext`, `Application.onCreate`, or a module's `handleLoadPackage` — run before the target's methods have been JIT-compiled. The interpreter path covers them.

### What it doesn't cover

- Methods called from already-compiled code. Intercepting compiled call sites requires inline-cache rewriting, which CallSite doesn't do.
- Constructors. The frame shape for `<init>` differs from ordinary method invocation and CallSite doesn't decode it.
- Arguments and `thisObject` for methods called from the JIT-compiled code of their callers.
- After-hooks. CallSite dispatches before-hooks only.

### How it decides to activate

At load time, the native library:

1. Resolves `libart.so`'s exported C++ accessors for `ArtMethod`. If the symbols are stripped, CallSite reports itself unavailable.
2. Locates the interpreter dispatch table by scanning the prologue of `art::interpreter::Execute` for the load-address sequence, then validating the candidate against `libart.so`'s address range.
3. Builds a small trampoline for each invoke type and patches the table slots.

Each step either succeeds or fails cleanly. If any step fails, `isAvailable()` returns false and the dispatcher moves on. The primary backends are unaffected.

### ART families

`AndroidCompat` groups Android releases by ART family — 10–11, 12, 13, 14, 15, 16+ — rather than by SDK number. The interpreter patch, the `ArtMethod` layout, and the symbol names differ across families. Native components check the family, not the version.

The family boundaries are approximate. The user-facing meaning is: **CallSite works on stock Android 10 and newer, and refuses on ROMs where `libart.so` has been stripped of its C++ export symbols.** No version table, no per-version hardcoded offsets.

### Diagnostics

The `CallSiteCallbackRegistry` counts every dispatch. A non-zero count after launching a scoped app confirms the interpreter patch is actually routing calls. A zero count means the patch installed but the frame decoding didn't extract the `ArtMethod` pointer — a version-specific detail that needs per-family work.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

### How it compares to Shamiko

XStealth is often described as a "Shamiko equivalent" for non-root setups. Useful shorthand, but not literal. Shamiko runs inside zygote and hides root artifacts before the target starts. XStealth runs inside the target and hides ShizuPosed's own artifacts. Different mechanism, lower ceiling.

### What XStealth hides

**Developer Options and ADB.** Hooks every settings read path: `Settings.Global` and `Settings.Secure` static getters, the `ForUser` variants, and direct `ContentResolver.query` calls against `content://settings/{global,secure}/<key>`.

**Shizuku and ShizuPosed packages.** Intercepts `PackageManager.getPackageInfo`, `getApplicationInfo`, `getInstalledPackages`, and `getInstalledApplications` — the int overloads and the newer `PackageInfoFlags` / `ApplicationInfoFlags` overloads.

**Running processes.** Filters `ActivityManager.getRunningAppProcesses`, `getRunningServices`, and `getRunningTasks`.

**`/proc` entries.** With the native layer active, hidden strings are scrubbed from `/proc/self/maps`, `cmdline`, `status`, and `mountinfo`.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` when the caller isn't a loaded module or the framework itself. It also filters `getDeclaredMethods` and related reflection walks on shim classes, and hooks `getResource`, `getPackage`, and their variants for shim paths.

**Method-count fingerprinting.** `ApiProtectionCheck` pins a baseline for every framework class the framework has hooked, plus a static list of common detection targets. Every later `getDeclaredMethods()` or `getMethods()` against those classes returns the pinned count.

### What XStealth doesn't hide

**Hardware attestation.** Play Integrity's `MEETS_STRONG_INTEGRITY` is a cryptographic proof of bootloader and ROM state.

**Root-empowered inspection.** An app that already has root can read `/proc` directly.

**Server-side cross-reference.** A server that cross-checks against Play Store inventory notices.

**Native reads of the settings database.** Raw binder to the settings provider bypasses the Java hooks.

**`Runtime.exec("settings get ...")`.** A subprocess that reads the real setting isn't affected.

**Method-list reconstruction.** Only method counts are pinned, not method lists.

**Exotic reflection.** `Unsafe`, `Class.getDeclaredMethods0`, and similar paths bypass the Java hooks.

**Signals unrelated to ShizuPosed.** Keyboard, accessibility services, overlay permissions, bootloader state, custom ROM, screen recorders, Play Integrity state. A banking app that flags one of these displays the warning it fails on — and it may not be Developer Options.

---

## XStealth Next — the aggressive engine

XStealth Next closes the gap for targets that resolve libc syscall stubs directly or go through the generic syscall dispatcher. It finds the libc stubs and inline-patches their prologues with a branch to a per-syscall handler. It also patches the generic `syscall()` dispatcher and covers `fstatat`/`newfstatat`.

Each stub's prologue is validated before patching. Unknown shapes are refused rather than corrupted.

---

## Dex optimization

By default, a module's dex goes to the shell side as-is, and the target compiles it on first use. Dex Optimization runs `dex2oat` on the cached dex before pushing, so the target loads a pre-optimized file. The wrapper tries three compiler filters in order — `speed`, `speed-profile`, `quicken` — and uses whichever succeeds first. Off by default.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

1. `assets/xposed_init` — the canonical marker, contains the entry class.
2. `assets/xposed_module` — an older convention, same content.
3. `META-INF/xposed/module.prop` — EdXposed-era, key=value format.
4. `assets/native_init` — a weak signal. The module ships native hooks and may have no Java entry point.

A module that ships none of the four markers is not detected.

Once detected, the descriptor and dex go to the shell side. `XposedHook` reads the module JSON, checks the target against the module's scope, and loads the dex with a `DexClassLoader`.

### Self-hook modules

Some modules determine their activation state by installing a hook on one of their own UI methods. When you tap **Open module app** in a module's detail sheet, ShizuPosed launches the module's own UI through itself, installing hooks into the module's process before the UI starts.

### Recommended scope

Modules can declare which apps they're *intended* for via `assets/scope.list`. ShizuPosed surfaces those as **recommended**: a chip in the scope editor, a badge on their rows, and a count on the module's row.

---

## Activation state — how "Activated" actually works

Two distinct questions. "Am I enabled?" comes from `XposedBridge.isModuleEnabled(pkg)`. "Am I active?" comes from `XposedBridge.isModuleActive(pkg)`.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per hooked target to `<shell-base>/hooked/<pkg>.json`. The manager mirrors the markers into its own files directory. `ProcessMonitor` runs one compound shell command every five seconds. `ModuleStatusProvider` reads from the mirror.

Marker writes are atomic — temp file plus rename — so a concurrent refresh never sees a partial file.

### The Android 11+ package visibility requirement

Module UIs that query the status provider from their own process must declare the provider's authority in their own `AndroidManifest.xml`:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

Without it, the query returns null on Android 11+. The shim logs a specific warning when this happens.

---

## Compatibility shims

The `de.robv.android.xposed.*` package ships every class modules link against.

`XposedHelpers` provides `findAndHookMethod`, `findClass`, `findClassIfExists`, `findFieldIfExists`, `findMethodIfExists`, `findConstructorIfExists`, and reflection helpers.

`XposedBridge` provides `getXposedVersion`, `isModuleEnabled`, `isModuleActive`, `getModuleScope`, and `log(...)`, in both arg and no-arg forms.

`LSPosedManager` provides the LSPosed-compatible manager API.

`XCallback` and `XCallback.Priority` ship as marker types.

`IXposedMod`, `IXposedHookLoadPackage`, `IXposedHookInitPackageResources`, and `IXposedHookCmdInit` are all present. `IXposedHookCmdInit` fires from `XposedHook.main()` before the target's `Application` object exists.

`XC_LayoutInflated` ships as a functional callback type, driven by a global `LayoutInflater.inflate` hook.

The API version reported is **93** (LSPosed's generation).

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

### Layout hooks

```java
resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
    @Override
    public void handleLayoutInflated(LayoutInflatedParam param) {
        // param.view, param.resId, param.resName are set
    }
});
```

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is the fork by **thedjchi** at `github.com/thedjchi/Shizuku`. About 200 MB free storage. No root required. For building: JDK 21 and Android SDK 37. For rebuilding the native libraries: `clang` (Termux `clang` or NDK r25+).

Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (fork recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Install the ShizuPosed Manager APK:

```
adb install -r ShizuPosed-R-5.8.apk
```

Or just tap the APK to install it. ADB is not required for the manager itself — only for starting Shizuku.

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to. Then tap **Launch App under ShizuPosed** and pick a scoped app.

Activation isn't retroactive. A module's UI will show "Activated" only after at least one scoped app has been launched through ShizuPosed.

---

## The manager

Five tabs.

**Home** shows the Framework Info card, the status card, the counters, and the list of currently hooked processes. The Framework Info card now reports the ART family and CallSite status.

**Modules** lists installed modules with XStealth always at the top. Tapping a module opens its detail sheet.

**Repo** shows every installed module with its README, metadata, and quick links.

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

### 5.8

```
NEW
• CallSite backend. A new HookDispatcher backend that intercepts
  method calls by patching ART's interpreter dispatch table
  rather than the method's ArtMethod entry point. Raises the
  cost of detection for targets that inspect ArtMethod entries.
  Interpreted execution only; JIT-compiled and AOT-compiled call
  sites are unaffected.

• AndroidCompat ART family tracking. Android releases are now
  grouped by ART generation (10-11, 12, 13, 14, 15, 16+) in
  addition to SDK number. Native components check the family
  rather than the version.

• Dynamic ART symbol resolution. libcallsite resolves
  ArtMethod accessors from libart.so at load time instead of
  hardcoding per-version struct offsets. Works on any Android
  version where the C++ export symbols are present; fails
  cleanly when they are stripped.

• Interpreter table discovery. libcallsite locates the
  interpreter's dispatch table by scanning the prologue of
  art::interpreter::Execute for the load-address pattern, then
  validating the candidate against libart.so's range.

• CallSiteCallbackRegistry dispatch counter. Reports how many
  times any intercepted method has fired. Non-zero after
  launching a scoped app confirms the patch is routing.

CHANGED
• HookEngine.setShellLibsDir() allows XposedHook to tell the
  engine where the shell-side libs live, so libcallsite.so can
  be loaded from the correct directory before backends install.

• Backend chain order: Pine AUTO, Pine REPLACEMENT, Amiru,
  CallSite, Native, Instrumentation, Proxy, Noop.

• XposedHook logs the presence or absence of libcallsite.so at
  startup, without loading it — loading happens inside
  HookEngine.

NOTES
• The interpreter frame decoding in handler_check is
  version-specific. The interpreter table location, symbol
  resolution, and patching are dynamic; extracting the
  ArtMethod pointer and argument array from the frame requires
  per-family work.

• CallSite doesn't cover constructors, after-hooks, or
  JIT-compiled call sites. Those remain with Pine, Amiru, and
  Native.

• CallSite is optional. If libart.so's symbols are stripped
  (some OEM ROMs), it reports itself unavailable and the
  dispatcher skips it. The primary backends are unaffected.
```

### 5.7

```
NEW
• Deeper settings hooks (ForUser variants, direct resolver
  queries).
• Expanded package checks (Android 13+ flags overloads).
• RunningProcessCheck hooks getRunningTasks.
• ApiProtectionCheck expanded reflection coverage and
  fingerprint baselines.
• Icon cache.

CHANGED
• XStealth scope removed. Applies to every launch.
```

### 5.6

```
NEW
• XStealth scope (initial).
• ScopeProvider abstraction.
```

### 5.5

```
NEW
• XStealth scope (initial design).
```

### 5.4

```
NEW
• Self-hook module support.
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

**CallSite coverage.** Interpreted execution only. Constructors and after-hooks aren't covered. JIT-compiled call sites bypass the interpreter and aren't intercepted.

**XStealth's practical ceiling.** XStealth covers the common detection paths for Developer Options, ADB, package presence, running processes, `/proc` reads, and reflection walks. It doesn't cover hardware attestation, native reads of the settings database, `Runtime.exec` subprocesses, method-list reconstruction, exotic reflection, kernel-level watchers, or signals unrelated to ShizuPosed.

A banking app that shows a warning may be failing on any of these, not just the ones XStealth hides.

**Android 11+.** Module UIs that want to report Activated must declare ShizuPosed's status provider authority in their own `<queries>` block.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, JIT-inlined callers aren't invalidated, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**CallSite-specific:** constructor interception, after-hooks, and argument/`thisObject` extraction from interpreted frames depend on version-specific frame layout. Coverage varies across Android versions.

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