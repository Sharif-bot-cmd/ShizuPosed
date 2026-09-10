Here's an accurate rewrite of the README. I've kept the structure and tone, but corrected every factual claim that doesn't match the actual code you've built, and added the mechanisms (Pine, ContentProvider, Scope, post-Application timing) that are actually in your implementation.

```markdown
# ShizuPosed - Xposed via Shizuku | Non-Root Hook Framework

[![Version](https://img.shields.io/badge/version-1.9-green.svg)](https://github.com/Sharif-bot-cmd/ShizuPosed)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-29%2B-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-red.svg)](LICENSE)

**ShizuPosed** is a **non-root Xposed-style hook framework** for Android 10+ (API 29+).
It runs a shell-side hook process via [Shizuku](https://github.com/RikkaApps/Shizuku)
(UID 2000) and installs method hooks using [Pine](https://github.com/canyie/pine),
a pure-Java ART hooking library.

It is **not** a replacement for LSPosed at zygote timing. Hooks are installed
**after** the target app's `Application.onCreate()` has run, from inside the
target process. That trade-off is documented in detail below.

---

## Table of Contents

- [What is ShizuPosed?](#what-is-shizuposed)
- [Timing Model](#timing-model)
- [How It Works](#how-it-works)
- [Architecture](#architecture)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Building from Source](#building-from-source)
- [Module Development](#module-development)
- [Module Compatibility](#module-compatibility)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [License](#license)

---

## What is ShizuPosed?

ShizuPosed lets you run standard Xposed modules against apps on your device
without modifying the system partition and without unlocking the bootloader.
It does this by asking Shizuku for a shell-UID process, loading the hook
payload into that process, and using Pine to replace ART method bodies.

### What ShizuPosed is

- A **non-root** alternative to Xposed for Android 10–15.
- Compatible with modules that use the standard `de.robv.android.xposed.*` API
  surface (via shim classes bundled with the manager).
- **App-scoped**: you choose which apps each module applies to.
- **Non-persistent**: no system partition changes, no boot image changes.

### What ShizuPosed is not

- **Not** a zygote-timing framework. Hooks cannot run before
  `Application.attachBaseContext()` / `onCreate()`.
- **Not** a drop-in replacement for LSPosed. Modules that assume LSPosed-specific
  APIs (`LSPosedManager`, `LSPosedContext`, `XposedBridge` internals) may not work.
- **Not** invisible to Play Integrity on every ROM. Whether you pass depends on
  what the module does once loaded, not on ShizuPosed itself.

---

## Timing Model

This is the single most important thing to understand about ShizuPosed.

```
┌──────────────────────────────────────────────────────────────────────┐
│                      HOOK TIMING COMPARISON                          │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  LSPosed / EdXposed (zygote-level)                                   │
│  ├── Hooks active BEFORE ActivityThread.main()                       │
│  ├── Can intercept Application.attachBaseContext()                   │
│  ├── Can intercept ContentProvider.onCreate()                        │
│  └── Requires Zygisk or Riru                                          │
│                                                                      │
│  ShizuPosed (post-Application)                                       │
│  ├── Process is spawned via app_process under shell UID              │
│  ├── Module's handleLoadPackage() runs AFTER app init                │
│  ├── Hooks apply to method calls that happen AFTER installation      │
│  └── No Zygisk, no Riru, no system mods                              │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

**Practical consequence:** a module that needs to change a value read during
`Application.onCreate()` will not be able to. A module that hooks something
the user triggers later (a button press, a network call, a setting lookup)
works fine.

---

## How It Works

```
┌──────────────────────────────────────────────────────────────────────┐
│                      SHIZUPOSED ARCHITECTURE                         │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │              ShizuPosed Manager (app process)              │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │    │
│  │  │  Home    │ │ Modules  │ │   Logs   │ │ Settings │      │    │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘      │    │
│  │                                                            │    │
│  │  • ModuleLoader (reads/writes .json module descriptors)   │    │
│  │  • ModuleStatusProvider (exported ContentProvider)        │    │
│  │  • ShizuPosedService (foreground service)                 │    │
│  └──────────────────────────┬─────────────────────────────────┘    │
│                             │                                       │
│                             ▼                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │              Shizuku (user-granted shell access)           │    │
│  │  • Executes app_process with our classpath                 │    │
│  │  • Streams stdout/stderr back to the manager               │    │
│  └──────────────────────────┬─────────────────────────────────┘    │
│                             │                                       │
│                             ▼                                       │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │     XposedHook.dex  →  runs inside target app process      │    │
│  │  ┌──────────────────────────────────────────────────────┐  │    │
│  │  │ 1. Verify Process.myPid() == target pid              │  │    │
│  │  │ 2. Read /data/user/0/com.android.shell/files/...     │  │    │
│  │  │    .syscall_cache/modules/*.json                     │  │    │
│  │  │ 3. Filter by module.hookedApps set                   │  │    │
│  │  │ 4. Load module dex via DexClassLoader                │  │    │
│  │  │ 5. Call module.handleLoadPackage(LoadPackageParam)   │  │    │
│  │  │ 6. Each findAndHookMethod → XposedHookBridge →       │  │    │
│  │  │    HookEngine → Pine.hook(Method, MethodHook)        │  │    │
│  │  └──────────────────────────────────────────────────────┘  │    │
│  └────────────────────────────────────────────────────────────┘    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### Hook pipeline

```
Module calls XposedHelpers.findAndHookMethod(...)
        │
        ▼
XposedHelpersImpl.findAndHookMethod(...)   (com.shizuposed.manager.core)
        │
        ▼
XposedHookBridge.installHook(method, callback)
        │
        ▼
HookEngine → Pine.hook(Method, MethodHook)
        │
        ▼
MethodHook#beforeCall / #afterCall invoke the module's XC_MethodHook
```

The shim classes live in the `de.robv.android.xposed` package so that modules
compiled against the standard Xposed API resolve correctly without any
code changes on the module side.

### Hook backend

Pine is used as the actual ART manipulation layer. Pine is a pure-Java
hooker: it detects the ART method structure at runtime, generates a stub,
and swaps the method's entry point. No native `.so` is required.

---

## Architecture

### Project structure

```
ShizuPosedManager/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/shizuposed/manager/
│       │   ├── ShizuPosedManagerApp.java       # Application class
│       │   ├── MainActivity.java               # Bottom-nav host
│       │   ├── ShizukuHelper.java              # Shizuku bridge + AIDL
│       │   ├── ui/
│       │   │   ├── HomeFragment.java
│       │   │   ├── ModulesFragment.java
│       │   │   ├── ModuleDetailSheet.java      # LSPosed-style detail sheet
│       │   │   ├── LogsFragment.java
│       │   │   └── SettingsFragment.java
│       │   ├── adapter/
│       │   │   ├── ModuleAdapter.java          # Real launcher icons
│       │   │   ├── AppSelectionAdapter.java
│       │   │   └── LogAdapter.java
│       │   ├── model/
│       │   │   ├── ModuleInfo.java
│       │   │   ├── HookedProcess.java
│       │   │   └── LogEntry.java
│       │   ├── service/
│       │   │   └── ShizuPosedService.java      # Foreground + hook launcher
│       │   ├── core/
│       │   │   ├── XposedHook.java             # Runs inside target process
│       │   │   ├── HookEngine.java             # Pine backend adapter
│       │   │   ├── XposedHookBridge.java       # Backend seam
│       │   │   ├── XposedHelpersImpl.java      # Standard Xposed helper impl
│       │   │   ├── ModuleLoader.java           # Module persistence + scan
│       │   │   ├── ProcessMonitor.java         # /proc scanning
│       │   │   └── ResourceHooking.java        # Resource override registry
│       │   ├── status/
│       │   │   └── ModuleStatusProvider.java   # Exported ContentProvider
│       │   ├── utils/
│       │   │   ├── Logger.java
│       │   │   ├── FileUtils.java
│       │   │   └── ShellUtils.java
│       │   └── receiver/
│       │       └── BootReceiver.java
│       ├── java/de/robv/android/xposed/        # Xposed API shims
│       │   ├── XposedHelpers.java
│       │   ├── XposedBridge.java
│       │   ├── XC_MethodHook.java
│       │   ├── XC_MethodReplacement.java
│       │   ├── IXposedHookLoadPackage.java
│       │   └── callbacks/XC_LoadPackage.java
│       └── res/
├── libs/
│   ├── shizuku-api.jar
│   ├── shizuku-provider.jar
│   ├── shizuku-aidl.jar
│   ├── shizuku-shared.jar
│   └── pine-0.3.0.jar                          # Vendored Pine
├── build.gradle
├── settings.gradle
└── README.md
```

### Key components

| Component | Responsibility |
|---|---|
| `ShizuPosedService` | Foreground service. Stages `XposedHook.dex` to external app storage, deploys it to `/data/user/0/com.android.shell/files/` via Shizuku, pushes module descriptors, launches targets under ShizuPosed. |
| `ShizukuHelper` | Talks to Shizuku over the `IShizukuService` AIDL binder. Provides `executeCommand(String)` running as shell UID and `launchXposedHook(...)`. |
| `XposedHook` | Runs inside the spawned `app_process`. Reads module JSONs, loads module dexes, calls `handleLoadPackage`, wires every `findAndHookMethod` to Pine. |
| `HookEngine` | Installs Pine as the active hook backend. Translates `XC_MethodHook` to Pine's `MethodHook` and `Pine.CallFrame`. |
| `ModuleStatusProvider` | Exported read-only ContentProvider that answers "is module X enabled?" for modules running in their own app process. |
| `ModuleLoader` | Reads/writes `<moduleDir>/<pkg>.json`, scans module APKs for `assets/xposed_init`, caches the module dex under `.syscall_cache/`. |
| `ProcessMonitor` | Scans `/proc` for app processes. Updates `HookedProcesses` in the Home tab. |

---

## Features

### Core

| Feature | Status |
|---|---|
| No root required | ✅ |
| No bootloader unlock | ✅ |
| Non-system-modifying | ✅ (all files under shell's data dir) |
| Non-root hook install via Pine | ✅ |
| App-scoped hooking (per-module scope) | ✅ |
| Module enable/disable persistence | ✅ |
| Module install from APK file | ✅ |
| LSPosed-style module detail sheet | ✅ |
| Real module launcher icons in list | ✅ |
| Standard `de.robv.android.xposed.*` shim API | ✅ |
| ContentProvider status API for module UIs | ✅ |
| LSPosed-style broadcast on toggle | ✅ |
| Foreground service with notification | ✅ |
| In-app log viewer | ✅ |

### Hook timing

| Capability | Status |
|---|---|
| Hooks after `Application.onCreate()` | ✅ |
| Hooks before `Application.attachBaseContext()` | ❌ |
| Hooks before `ContentProvider.onCreate()` | ❌ |
| Zygote-level preload | ❌ |

---

## Requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Android | 10 (API 29) | 13–15 |
| Architecture | ARM64 | ARM64 |
| Shizuku | 13.1.1 or newer | Latest |
| Storage | ~100 MB | 200 MB |
| JDK | 21 | 21 |
| Android SDK | API 35 | API 35 |
| Gradle | 8.x | 9.x |

Shizuku **must be running** and ShizuPosed **must be granted Shizuku permission**
before any hooking can occur.

---

## Installation

### Method 1: Shizuku (recommended)

1. Install Shizuku from Google Play, F-Droid, or GitHub.
2. Start Shizuku using ADB or root:
   ```bash
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```
3. Install the ShizuPosed Manager APK:
   ```bash
   adb install app-debug.apk
   ```
4. Open ShizuPosed Manager. The first launch will request Shizuku permission.
5. In Shizuku's "Authorized applications" list, ShizuPosed Manager should appear.
   If it doesn't, re-open ShizuPosed Manager and grant when prompted.

### Method 2: Building and installing locally

See [Building from Source](#building-from-source).

---

## Building from Source

### Prerequisites

- JDK 21
- Android SDK with platform 35 and build-tools 35
- Gradle wrapper is included (`./gradlew`)

### Environment

```bash
export ANDROID_HOME=$HOME/Android/Sdk
# or wherever your SDK lives
export PATH=$ANDROID_HOME/platform-tools:$PATH
```

### Build

```bash
./gradlew clean :app:assembleDebug
```

Output:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Notes on the build

- `XposedHook.dex` is built at compile time by the `:app:makeDex` task. It
  compiles the app's `core/` and `de.robv.android.xposed/` packages together
  with Pine, then links them into a single dex via `d8`.
- The `:app:copyHookDex` task copies the resulting dex into
  `app/src/main/assets/XposedHook.dex` before asset merging, so it's
  packaged into the APK.
- `Pine` is vendored as `libs/pine-0.3.0.jar`. Do not bump the Pine version
  without re-checking the `Pine.hook(Method, MethodHook)` signature used in
  `HookEngine.java`.

---

## Module Development

### Writing a module

ShizuPosed modules use the standard Xposed API. Compile against any recent
`de.robv.android.xposed` API jar (or LSPosed's, since the surface is the same
for the entry point).

```java
package com.example.mymodule;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.example.target".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(
            "com.example.target.MainActivity",
            lpparam.classLoader,
            "onCreate",
            android.os.Bundle.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    android.util.Log.d("MyModule", "onCreate intercepted");
                }
            }
        );
    }
}
```

### Module entry point

ShizuPosed locates the entry class using the standard convention:

1. `assets/xposed_init` inside the module APK — first non-empty line is the
   fully qualified class name.
2. If absent, ShizuPosed guesses based on common patterns:
   `<pkg>.MainHook`, `<pkg>.XposedMain`, `<pkg>.Hook`, `<pkg>.Main`,
   `<pkg>.XposedEntry`, `<pkg>.XposedModule`, `<pkg>.Module`, `<pkg>.XposedInit`.

Always ship `assets/xposed_init` for reliability.

### Module descriptor (internal)

The manager stores one JSON per module under
`<appFiles>/.syscall_cache/modules/<pkg>.json`:

```json
{
  "packageName": "com.example.mymodule",
  "name": "My Module",
  "version": "1.0",
  "xposedInit": "com.example.mymodule.MainHook",
  "apkPath": "/data/user/0/com.shizuposed.manager/files/module_apks/com.example.mymodule.apk",
  "cachedDexPath": "/data/user/0/com.shizuposed.manager/files/.syscall_cache/com.example.mymodule.dex",
  "enabled": true,
  "hookAllApps": false,
  "hookSystemApps": false,
  "hookedApps": [
    "com.example.app1",
    "com.example.app2"
  ]
}
```

This file is written by the manager. Do not edit it from the module.

### Reporting "active" status in a module UI

Modules that have their own config Activity can display accurate
Active/Inactive state by querying ShizuPosed's exported provider:

```java
// Queries com.shizuposed.manager.status
boolean enabled = XposedBridge.isModuleEnabled(getPackageName());
textView.setText(enabled ? "Module is Active" : "Module is Inactive");
```

Where `XposedBridge` is the shim from this project. Any module that already
calls `XposedBridge.isModuleEnabled(...)` gets this behaviour for free once
ShizuPosed is installed.

Modules that watch for LSPosed broadcasts will also receive:

- `de.robv.android.xposed.action.MODULE_ENABLED`
- `de.robv.android.xposed.action.MODULE_DISABLED`

delivered only to their own package, with an extra `"module"` string.

---

## Module Compatibility

### Expected to work

- Modules whose entry point is `handleLoadPackage` and which use
  `XposedHelpers.findAndHookMethod`, `XposedHelpers.findClass`,
  `XposedHelpers.getObjectField`, `XposedHelpers.callMethod`, etc.
- Modules that respond to user actions or hook methods that run after
  `Application.onCreate`.
- Modules that only need to alter UI text/behaviour after the app is up.

### Expected NOT to work

- Modules that hook `Application` lifecycle methods and need their hook
  installed *before* `onCreate` runs.
- Modules that rely on `LSPosedManager`, `LSPosedContext`, or any LSPosed-only
  API surface.
- Modules whose `handleLoadPackage` throws before installing any hook.
- Modules that replace resources via the LSPosed resource-injection framework
  (`IXposedHookInitPackageResources`) — the shim exists, but only the storage
  side is implemented; the actual injection into `Resources` is not.

### Known limitations

- **`system_server` is not hooked.** Skipped deliberately to avoid soft-bricking.
- **No constructor hooking through the shim.** `findAndHookConstructor(...)`
  is currently a no-op logged at INFO; only method hooks are wired.
- **Resource hooking is incomplete.** `ResourceHooking.setReplacement(...)` is
  stored but not applied to a live `Resources` instance.
- **No hot-reload of modules.** Changing a module requires re-launching the
  target app under ShizuPosed.

---

## Troubleshooting

### Shizuku permission not granted

- Verify Shizuku is running: `adb shell ps -A | grep shizuku`
- Open Shizuku → "Authorized applications" → confirm ShizuPosed is listed.
- If it isn't listed, open ShizuPosed once more; permission request fires on
  first launch and on every `onResume` while not authorized.

### `XposedHook.dex deployed` but no hooks fire

Check, in order:

1. That at least one module is enabled and one app is in its scope.
2. `/data/user/0/com.android.shell/files/.syscall_cache/xposed.log`
   via `adb shell cat` — this log is written inside the target process.
3. That the target app was launched via the ModuleDetailSheet's
   "Launch App under ShizuPosed…" action, not from the launcher.

Hooks that run inside a normally-launched app cannot be installed; the
post-Application timing model requires the app to be launched under
ShizuPosed's control.

### `Cannot hook externally-launched process`

Expected. `ProcessMonitor.injectProcess(...)` refuses to inject into processes
it didn't launch. Use the ModuleDetailSheet's launch action.

### Build fails on `d8` or `makeDex`

- Confirm `ANDROID_HOME` points to your SDK.
- Confirm `build-tools` is installed: `sdkmanager "build-tools;35.0.0"`.
- The `makeDex` task reads `app/build/intermediates/javac/debug/classes`.
  If that directory is missing, run `:app:compileDebugJavaWithJavac` first
  (or just run `:app:assembleDebug`, which does it automatically).

### Runtime `ClassNotFoundException: top.canyie.pine.Pine`

Pine wasn't bundled into `XposedHook.dex`. Check `makeDex`'s output in the
Gradle log for the line `makeDex: Pine classes extracted from pine-0.3.0.jar`.
If it's absent, verify `app/libs/pine-0.3.0.jar` exists.

---

## FAQ

**Q: Does ShizuPosed require root?**
A: No. It uses Shizuku, which runs as shell UID (2000) after the user grants
permission via ADB or root—but the *device* itself does not need root.

**Q: Does it work on Android 15?**
A: Yes. Tested against API 35.

**Q: Does it modify the system partition?**
A: No. All files live under `/data/user/0/com.android.shell/files/` and
`/data/user/0/com.shizuposed.manager/files/`.

**Q: Will it break OTA updates?**
A: No. Nothing on `/system` or `/vendor` is touched.

**Q: Does it pass Play Integrity?**
A: Depends on what modules do at runtime. ShizuPosed itself is not a root
framework and doesn't trip the integrity checks by virtue of existing.
However, some modules *will* alter the behaviour of apps that Integrity
depends on, which the user should evaluate on a case-by-case basis.

**Q: Is it a Zygisk module?**
A: No. There is no Zygisk or Riru component. It's a plain Android app plus
a shell-side hook process.

**Q: How does it hook without root?**
A: Shizuku gives the manager the ability to run `app_process` as shell UID.
That process loads `XposedHook.dex` and uses Pine to replace method entry
points inside the target app's own process.

**Q: Why not just use LSPosed?**
A: LSPosed requires Zygisk or Riru, which on most devices means a bootloader
unlock. ShizuPosed runs on locked bootloaders, at the cost of post-Application
timing and a narrower module compatibility surface.

**Q: Can I hook `system_server`?**
A: Deliberately no. `XposedHook` skips `system_server` to prevent bootloops
and other systemic failures.

**Q: Where are the hook logs written?**
A: `/data/user/0/com.android.shell/files/.syscall_cache/xposed.log`, readable
via `adb shell cat`. The in-app Logs tab shows the manager's own log, not
the shell-side hook log.

**Q: How do I know a module is running?**
A: Open the module's config UI (if it has one). It can query ShizuPosed's
`ModuleStatusProvider` and display "Active". Modules that don't show a status
will still install their hooks; whether they work depends on timing and the
API surface they use.

---

## License

```
Copyright 2024-2025 ShizuPosed Contributors

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

## Credits

| Project | Author | Use |
|---|---|---|
| [Shizuku](https://github.com/thedjchi/Shizuku) | djchi | Privileged shell bridge |
| [Pine](https://github.com/canyie/pine) | canyie | Pure-Java ART hooking backend |
| [Xposed API](https://github.com/rovo89/XposedBridge) | rovo89 | Module-facing interface (shimmed) |
| [LSPosed](https://github.com/LSPosed/LSPosed) | LSPosed team | Reference for module UX patterns |

### Contributors

- **Sharif-bot-cmd** — maintainer

---

## Disclaimer

This software is provided "as is" for educational and research purposes.
Use at your own risk. The developers are not responsible for any damage
to your device, data loss, or voided warranty. Some Android OEMs may
disallow the use of Shizuku-like tools under their terms of service.