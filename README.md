# ShizuPosed

**A non-root Xposed-compatible hook framework built on `app_process`.**

ShizuPosed runs Xposed-API modules in apps launched through it — no root, no bootloader unlock, no system partition changes. Shizuku invokes `app_process` as the shell UID, a Java runtime bootstraps inside the target's process, and method hooks go in through a multi-backend dispatcher. Modules written for LSPosed and classic Xposed keep working.

Version 5.1.

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
  ├── HookEngine                 backend selection
  ├── HookDispatcher             per-method backend chain
  ├── ModuleLoader               dex load + entry invocation
  ├── ResourceHooking            resource + layout hooks
  ├── XStealthModule             built-in privacy module
  │     ├── XStealthNative       libc symbol interposition
  │     ├── XStealthNativeNext   libc syscall stub patching
  │     └── ApiProtectionCheck   Xposed API call guarding
  └── markers written to         <shell-base>/hooked/
```

The manager never touches the target process directly. Everything passes through Shizuku.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab, and its master toggle lives only there.

### How it compares to Shamiko

XStealth is often described as a "Shamiko equivalent" for non-root setups. Useful shorthand, but not literal. Shamiko runs inside zygote and hides root artifacts before the target starts. XStealth runs inside the target and hides ShizuPosed's own artifacts. Different mechanism, lower ceiling. For the checks that matter in practice, XStealth behaves similarly. Against an adversary with root, it doesn't.

### What XStealth hides

- Developer Options enabled
- ADB enabled
- Shizuku package presence
- ShizuPosed package presence
- Running processes (Shizuku, ShizuPosed, `app_process`)
- `/proc` entries — with the native layer active, hidden strings are scrubbed from `/proc/self/maps`, `cmdline`, `status`, and `mountinfo`
- File access to ShizuPosed's payload paths

### What XStealth doesn't hide

**Hardware attestation.** Play Integrity's `MEETS_STRONG_INTEGRITY` is a cryptographic proof of bootloader and ROM state. XStealth doesn't touch any of the inputs to that check.

**Root-empowered inspection.** An app that already has root can read `/proc` directly, bypassing both the Java and libc layers.

**Server-side cross-reference.** XStealth can spoof what an app reports locally. A server that cross-checks against Play Store inventory notices.

---

## XStealth Next — the aggressive engine

The primary native engine (`libxstealth.so`) interposes libc functions by symbol. That covers anything that goes through the named wrappers. A target that resolves the syscall stub directly, or goes through the generic syscall dispatcher, sidesteps symbol interposition entirely.

XStealth Next closes that gap. It finds the libc stubs and inline-patches their prologues with a branch to a per-syscall handler. Any entry into the stub lands in the handler instead of executing the real `svc #0`. It also patches the generic `syscall()` dispatcher and covers `fstatat`/`newfstatat` in addition to `stat`/`lstat` — which closes the most common stat path on modern Android.

Each stub's prologue is validated before patching. Unknown shapes are refused rather than corrupted, and the failure is logged with the actual bytes so a future bionic change is visible in logcat rather than silent.

Turning Next on renames the module's display in the Modules tab to **XStealth (Next)**. The package name never changes.

### What Next still doesn't catch

A target that emits its own `svc #0` sequence never touches the libc stub, and there's no user-space way to intercept raw syscall instructions without `ptrace` or an LSM. Next raises the cost of detection for libc-mediated code. It isn't undefeatable.

---

## API call protection

Some apps walk their own classpath looking for `de.robv.android.xposed.*` and flag the device as modified. Others call into the Xposed API just to see if it answers. Both are fingerprinting.

API call protection hooks `ClassLoader.loadClass` and `Class.forName`. When the caller isn't a loaded module or the framework itself, lookups of the shim package return `ClassNotFoundException`. The app never sees the Xposed classes.

This is a defensive measure. It raises the cost of runtime fingerprinting; it doesn't remove the classes from the process. They're still loadable by the module dexes, and a determined target that inspects its own memory map for them will still find them.

Off by default. When on, it's greyed out unless the XStealth master toggle is also on — nothing installs the check otherwise.

Status appears in the Home tab's Framework Info card as **API Protection: Active / Disabled / Inactive**.

---

## Dex optimization

By default, a module's dex goes to the shell side as-is, and the target compiles it on first use. Small delay on cold start.

Dex Optimization runs `dex2oat` on the cached dex before pushing, so the target loads a pre-optimized file. The wrapper tries three compiler filters in order — `speed`, `speed-profile`, `quicken` — and uses whichever succeeds first. If all three fail (dex2oat missing, out of memory, incompatible filters, permission error), the original dex goes unchanged.

Performance feature, not a security one. Faster module loading on cold start.

Off by default. Status appears in the Home tab's Framework Info card as **Dex Optimization: Enabled / Disabled**.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for `assets/xposed_init`, caches the APK as a dex container, and pushes the descriptor and dex to the shell side. `XposedHook` reads the module JSON, checks the target against the module's scope, and loads the dex with a `DexClassLoader` whose parent is the target's classloader. The entry class comes from `xposed_init`, from `assets/xposed_init`, or from one of eight conventionally-named candidates.

Built-in modules skip the dex load — their classes resolve against `XposedHook`'s own classloader. The marker file is the source of truth for activation state.

### Recommended scope

Modules can declare which apps they're *intended* for by shipping an `assets/scope.list` file inside the APK — one package name per line, blank lines and `#` comments ignored. This is the LSPosed convention.

ShizuPosed reads that file at install and load time and surfaces the packages as **recommended**:

- The scope editor shows a **Recommended** chip that selects every recommended app currently in the list.
- Recommended apps appear first in the scope list, with a small **Recommended** badge on each row.
- The module row shows a **· N recommended** hint next to the hooked-apps count.

Modules without a `scope.list` see no change — the chip is hidden, the hint doesn't appear, and the scope editor behaves exactly as before. Recommended is informational. It doesn't pre-select anything; it just tells the user what the author had in mind.

---

## Activation state — how "Activated" actually works

Two distinct questions. "Am I enabled?" comes from `XposedBridge.isModuleEnabled(pkg)`, sourced from the manager's preference store. "Am I active?" comes from `XposedBridge.isModuleActive(pkg)`, sourced from the shell-side marker files.

A module's own UI runs in its own process, launched by the launcher and not by ShizuPosed. So the chain is:

```
Module UI process
  → XposedBridge.isModuleActive(pkg)
    → LSPosedManager.isModuleActive(pkg)
      → ContentResolver.query(content://com.shizuposed.manager.status/active/<pkg>)
        → ModuleStatusProvider
          → reads <shell-base>/hooked/*.json
          → returns active=1 or active=0
```

The provider caches marker scans for three seconds.

---

## Compatibility shims

The `de.robv.android.xposed.*` package ships every class modules link against.

`XposedHelpers` provides `findAndHookMethod`, `findClass`, `findClassIfExists`, `findFieldIfExists`, `findMethodIfExists`, `findConstructorIfExists`, reflection helpers, and forwards for version and enabled-state queries.

`XposedBridge` provides `getXposedVersion`, `isModuleEnabled`, `isModuleActive`, `getModuleScope`, and `log(...)`.

`LSPosedManager` provides the LSPosed-compatible manager API.

`XCallback` and `XCallback.Priority` ship as marker types so modules that reference the callback hierarchy link cleanly.

`IXposedMod`, `IXposedHookLoadPackage`, `IXposedHookInitPackageResources`, and `IXposedHookCmdInit` are all present. `IXposedHookCmdInit` is wired, not stubbed — it fires from `XposedHook.main()` before the target's `Application` object exists.

`XC_LayoutInflated` ships as a functional callback type. Modules can register callbacks via `XResources.hookLayout()`; the framework installs a global `LayoutInflater.inflate` hook to drive them.

The API version reported is **93** (LSPosed's generation). Modules that check `getXposedVersion() >= 82` accept ShizuPosed as compatible.

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

### Earliest hook: `IXposedHookCmdInit`

Fires from `XposedHook.main()` after the backend chain is installed and before the target's `Application` exists. The classloader it receives is the framework's own — the target's loader isn't resolved until `runBootstrapMode()`. Modules that need the target's classloader should use `IXposedHookLoadPackage` instead.

```java
public class MainHook implements IXposedHookLoadPackage, IXposedHookCmdInit {

    @Override
    public void initCmdProcess(InitCmdProcessParam param) {
        XposedBridge.log("cmd init: pkg=" + param.processName
            + " argv=" + Arrays.toString(param.argv));
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // ...
    }
}
```

A module that throws inside `initCmdProcess()` is logged and skipped. The launch continues.

### Reporting state in a module UI

```java
boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
boolean active  = XposedBridge.isModuleActive(getPackageName());
String[] scope  = XposedBridge.getModuleScope(getPackageName());
```

For the module UI to reach the provider on Android 11+, the module's own `AndroidManifest.xml` needs:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
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

Only the programmatic form is supported. XML-level replacement is on the limitations list.

### Layout hooks

```java
public class Res implements IXposedHookInitPackageResources {
    @Override
    public void handleInitPackageResources(
            XC_InitPackageResources.InitPackageResourcesParam resparam) {
        resparam.res.hookLayout(R.layout.main, new XC_LayoutInflated() {
            @Override
            public void handleLayoutInflated(LayoutInflatedParam param) {
                // param.view, param.resId, param.resName are set
            }
        });
    }
}
```

The global `inflate` hook is installed before `Application.onCreate` in bootstrap mode, so callbacks fire for every layout the target inflates from that point on. In post-application mode, the hook is installed later — layouts already inflated are missed.

If the hook couldn't be installed for a target, `hookLayout()` logs a warning at registration time. A callback that will never fire is easier to debug than one that silently doesn't.

---

## Requirements

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is the fork by **thedjchi** at `github.com/thedjchi/Shizuku`. About 200 MB free storage. No root required. For building: JDK 21 and Android SDK 37. For rebuilding the native libraries: `clang` (Termux `clang` or NDK r25+).

Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (fork recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Install the ShizuPosed Manager APK:

```
adb install -r ShizuPosed-R-5.1.apk
```

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to. Then tap **Launch App under ShizuPosed** and pick a scoped app.

Activation isn't retroactive. A module's UI will show "Activated" only after at least one scoped app has been launched through ShizuPosed.

---

## The manager

Five tabs.

**Home** shows the Framework Info card, the status card, the counters, and the list of currently hooked processes. The Framework Info card reports framework version, API version, shell package, system version, device, system ABI, shell UID, API Protection state, and Dex Optimization state.

**Modules** lists installed modules with XStealth always at the top. Tapping a module opens its detail sheet. XStealth's sheet has the master toggle, the Next toggle, the API Protection and Dex Optimization toggles, the six per-check toggles, and a status line. Sub-toggles grey out when the master is off.

**Repo** shows every installed module with its README, metadata, and quick links. Tap a row for the module's details: icon, version, author, description, homepage and support buttons, and a two-tab content area with **README** (rendered from Markdown) and **Details** (metadata table). READMEs are read from `assets/README.md`, `assets/readme.md`, or `README.md` at the APK root. Modules that ship one get a **README** badge on their row. Modules that don't see a friendly empty state. Search filters by name, package, author, and description.

**Logs** shows the manager's own log with search.

**Settings** has the runtime toggles, cache management, and config export. The XStealth toggle isn't here — the detail sheet is the only place for it.

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

### 5.1

```
NEW
• Repo tab. Installed modules are now browsable. Each module
  shows its README (rendered from Markdown), metadata, and
  quick links. READMEs are read from assets/README.md,
  assets/readme.md, or README.md at the APK root.

• Markdown rendering. Lightweight renderer handles headings,
  bold, italic, inline code, code blocks, links, lists,
  blockquotes, and horizontal rules. No external dependency.

• Recommended scope. Modules can declare intended scope via
  assets/scope.list. Recommended apps get a badge and sort to
  the top of the scope editor, and a Recommended chip selects
  them in one tap. The module row shows "· N recommended"
  when a scope is declared.

• IXposedHookCmdInit. Fires before the target's Application
  exists, giving modules the earliest hook point ShizuPosed
  offers. Receives argv, target package, and the framework's
  own classloader.

• Layout hooks. XC_LayoutInflated is functional. Modules
  register via XResources.hookLayout(); a global
  LayoutInflater.inflate hook drives the callbacks.

• XCallback and XCallback.Priority ship as marker types so
  modules that reference the callback hierarchy link cleanly.

• XC_LoadPackage.LoadPackageParam and
  XC_InitPackageResources.InitPackageResourcesParam now
  extend XCallback and implement XCallback.Param, matching
  upstream. Modules that type a parameter as XCallback no
  longer fail with NoClassDefFoundError.

• Scope editor no longer shows the module being scoped in its
  own list. Matches LSPosed behavior.

CHANGED
• Repo tab is no longer a placeholder.

• Framework Info card unchanged; layout-hook state is
  reported per-target in the log rather than on the card.

NOTES
• Layout hooks fire only for inflations that happen after
  the global inflate hook is installed. Post-application
  mode misses anything inflated during Application.onCreate.

• Recommended scope is informational. It does not
  pre-select anything.
```

### 5.0

```
NEW
• ShizuPosed 5.0 release.

• Shell base fallback and path resolution hardened across
  both the manager and the shell side.

• Multi-backend dispatcher refined. Pine AUTO remains the
  primary engine; Pine REPLACEMENT, Amiru, Native,
  Instrumentation, Proxy, and Noop round out the chain.

• XStealth and XStealth Next documentation expanded.

• Module scanner runs discovery before purge, preventing
  spurious "1 module detected" notifications.

• XposedHook.dex deployment hardened with size verification
  and permission normalization.

NOTES
• ShizuPosed is not a drop-in LSPosed replacement. It uses
  a different injection primitive and has a narrower reach.
```

### 4.9

```
NEW
• API call protection. Hooks ClassLoader.loadClass and
  Class.forName to refuse Xposed shim lookups from non-module
  code. Raises the cost of runtime fingerprinting. Off by
  default.

• Dex optimization. Runs dex2oat on module dex files before
  pushing them to the shell side. Falls back to the original
  dex if optimization fails. Off by default.

• Framework Info card now shows API Protection and Dex
  Optimization state.

CHANGED
• libxstealth and libxstealth_next resolve their interposed
  symbols through alias tables. A future bionic that renames
  or hides a symbol is still reachable.

• libxstealth_next also patches fstatat/newfstatat and the
  generic syscall() dispatcher.

• Failed symbol resolution is logged with the actual bytes,
  so a bionic change is visible in logcat rather than silent.

• Module scanner runs discovery before purge, so a module
  the discovery pass just observed cannot be purged in the
  same scan.

• Module scanner refreshes stale apkPath values in place
  instead of re-registering the module, eliminating another
  source of spurious "detected" notifications.

NOTES
• API Protection and Dex Optimization are opt-in.
• Neither engine catches a target that emits its own svc #0
  instruction, kernel-level watchers, or inspection from a
  root process outside the target.
```

### 4.6

```
NEW
• XStealth Next — an opt-in native engine that inline-patches
  libc syscall stubs. Catches detection code that resolves the
  stub directly or goes through the generic syscall() dispatcher.

• Turning on Next renames the module to "XStealth (Next)" in
  the Modules tab.

• Hide /proc entries is now a real toggle in the XStealth
  detail sheet.

• XStealth row shows the shield icon.

CHANGED
• XStealth master toggle moved out of Settings. The detail
  sheet is the only place.

• Sub-toggles are greyed out when the master is off.

• The dead hideClassLoaderArtifacts flag was removed.
```

### 4.3

```
NEW
• XStealth — built-in privacy module.

• Shell base fallback for hardened ROMs.

• findClassIfExists, findFieldIfExists, findMethodIfExists,
  findConstructorIfExists added to XposedHelpers.
```

### Earlier

See the release notes for 4.2 and below on the Releases page.

---

## Known limitations

These are structural, not bugs to be fixed.

`system_server` isn't hooked. `Service.onCreate` has no fallback. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework. Modules that require zygote-wide timing won't work.

Layout hooks fire only for inflations that happen after the global inflate hook is installed. Post-application mode misses anything inflated during `Application.onCreate`.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, JIT-inlined callers aren't invalidated, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**XStealth:** defeats common detection checks, not a security boundary. The primary engine covers Java and libc-mediated code. Next covers libc syscall stubs. Neither covers raw `svc #0` instructions, kernel-level watchers, or inspection from a root process outside the target.

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