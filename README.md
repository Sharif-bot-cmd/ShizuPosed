# ShizuPosed - Xposed via Shizuku | Non-Root Hook Framework

[![Version](https://img.shields.io/badge/version-3.3-green.svg)](https://github.com/Sharif-bot-cmd/ShizuPosed)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-29%2B-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-red.svg)](LICENSE)

**ShizuPosed** is a **non-root Xposed-style hook framework** for Android 10+
(API 29+). It runs a shell-side hook process via
[Shizuku](https://github.com/thedjchi/Shizuku) (UID 2000) or [Shevery](https://github.com/HmnDev-Tech/shevery) and installs
method hooks through a **multi-backend dispatcher** whose primary engine is
[Pine](https://github.com/canyie/pine), with a bundled native shim
(`libshizuposed.so`) as a second ART engine and `Instrumentation` as a
fallback for Application and Activity lifecycle hooks. A compatibility layer
absorbs hidden-API renames so the framework keeps running on Android 16, 17,
and later.

It is **not** a zygote-timing framework. `Application.onCreate` and
`ContentProvider.onCreate` run in the hooked process under bootstrap mode;
`Activity.onCreate` and `Service.onCreate` are delivered by AMS to the
original process, so only hooks that Pine, the native shim, or
Instrumentation can install before dispatch will fire.

---

## Key features

| Feature | Status |
|---|---|
| No root, no bootloader unlock | ✅ |
| Non-system-modifying | ✅ |
| Multi-backend dispatcher (Pine → Native → Instrumentation → Proxy → Noop) | ✅ |
| Bootstrap mode (hooks before `Application.onCreate`) | ✅ |
| `Instrumentation` fallback for Application / Activity lifecycle | ✅ v2.8 |
| Native ART engine (`libshizuposed.so`, in-process) | ✅ v3.1 |
| Native JNI hooking (inline patch via `dlsym`) | ✅ v3.1 |
| Dynamic ART layout probe (auto-detects offsets) | ✅ v3.3 |
| Dynamic argument marshaling (any primitive signature) | ✅ v3.3 |
| LSPosed API 93 compatibility (`LSPosedManager`) | ✅ |
| Active-state API (`isModuleActive`, `getModuleScope`) | ✅ |
| Resource hooking (`IXposedHookInitPackageResources`) | ✅ |
| `XposedBridge.log(...)`, `XSharedPreferences` shims | ✅ |
| Auto-detect installed Xposed modules | ✅ |
| Auto-purge uninstalled modules | ✅ |
| Home tab Framework Info card | ✅ |
| Repo tab (placeholder) | ✅ v2.9 |
| `app_process` binary fallback (64 / universal / 32) | ✅ |
| Forward-compatible compat layer | ✅ |

### Hook dispatcher order

1. `PineBackend` — Pine AUTO mode
2. `PineReplaceBackend` — Pine REPLACEMENT mode
3. `NativeBackend` — `libshizuposed.so` ART entry-point patcher
4. `InstrumentationBackend` — Application / Activity lifecycle
5. `ProxyBackend` — interface methods
6. `NoopBackend` — always succeeds, does nothing

Each backend is tried in order per method. The first one that installs wins.
`InstrumentationBackend` only accepts lifecycle methods; everything else
passes through to `Proxy` and `Noop`. `NativeBackend` is only reachable when
Pine's `.so` is missing, blocked, or its `isInitialized()` probe fails — in
that case the native shim takes over ART hooking for methods it can reach.

---

### Structure

```
ShizuPosedManager/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── XposedHook.dex                # built by :app:makeDex
│           ├── jniLibs/
│           │   └── arm64-v8a/
│           │       └── libshizuposed.so          # built from native/libshizuposed.c
│           ├── java/
│           │   ├── com/shizuposed/manager/
│           │   │   ├── ShizuPosedManagerApp.java
│           │   │   ├── MainActivity.java
│           │   │   ├── ShizukuHelper.java
│           │   │   ├── ui/
│           │   │   │   ├── HomeFragment.java
│           │   │   │   ├── ModulesFragment.java
│           │   │   │   ├── ModuleDetailSheet.java
│           │   │   │   ├── RepoFragment.java              # v2.9
│           │   │   │   ├── LogsFragment.java
│           │   │   │   └── SettingsFragment.java
│           │   │   ├── adapter/
│           │   │   │   ├── ModuleAdapter.java
│           │   │   │   ├── IconResolver.java
│           │   │   │   ├── AppSelectionAdapter.java
│           │   │   │   ├── HookedProcessAdapter.java
│           │   │   │   ├── LogAdapter.java
│           │   │   │   └── MainPagerAdapter.java
│           │   │   ├── model/
│           │   │   │   ├── ModuleInfo.java
│           │   │   │   ├── HookedProcess.java
│           │   │   │   └── LogEntry.java
│           │   │   ├── service/
│           │   │   │   └── ShizuPosedService.java
│           │   │   ├── core/
│           │   │   │   ├── XposedHook.java
│           │   │   │   ├── HookEngine.java
│           │   │   │   ├── HookDispatcher.java
│           │   │   │   ├── XposedHookBridge.java
│           │   │   │   ├── XposedHelpersImpl.java
│           │   │   │   ├── NativeBridge.java              # v3.1
│           │   │   │   ├── NativeDispatcher.java          # v3.3
│           │   │   │   ├── ModuleLoader.java
│           │   │   │   ├── ModuleScanner.java
│           │   │   │   ├── ProcessMonitor.java
│           │   │   │   ├── ResourceHooking.java
│           │   │   │   ├── backends/
│           │   │   │   │   ├── PineBackend.java
│           │   │   │   │   ├── PineReplaceBackend.java
│           │   │   │   │   ├── NativeBackend.java         # v3.1
│           │   │   │   │   ├── InstrumentationBackend.java
│           │   │   │   │   ├── ProxyBackend.java
│           │   │   │   │   └── NoopBackend.java
│           │   │   │   ├── hooks/
│           │   │   │   │   └── LifecycleRegistry.java
│           │   │   │   └── compat/
│           │   │   │       ├── AndroidCompat.java
│           │   │   │       ├── CompatLog.java
│           │   │   │       ├── HiddenApiBypass.java
│           │   │   │       └── ReflectionUnsafe.java
│           │   │   ├── status/
│           │   │   │   └── ModuleStatusProvider.java
│           │   │   ├── utils/
│           │   │   │   ├── Logger.java
│           │   │   │   ├── FileUtils.java
│           │   │   │   └── ShellUtils.java
│           │   │   └── receiver/
│           │   │       └── BootReceiver.java
│           │   └── de/robv/android/xposed/
│           │       ├── XposedHelpers.java
│           │       ├── XposedBridge.java
│           │       ├── LSPosedManager.java
│           │       ├── XResources.java
│           │       ├── XSharedPreferences.java
│           │       ├── XC_MethodHook.java
│           │       ├── XC_MethodReplacement.java
│           │       ├── IXposedHookLoadPackage.java
│           │       ├── IXposedHookInitPackageResources.java
│           │       └── callbacks/
│           │           ├── XC_LoadPackage.java
│           │           └── XC_InitPackageResources.java
│           └── res/
│               ├── drawable/
│               │   ├── ic_launcher.xml
│               │   ├── ic_launcher_background.xml
│               │   ├── ic_launcher_foreground.xml
│               │   ├── ic_module.xml
│               │   ├── ic_home.xml
│               │   ├── ic_repo.xml                        # v2.9
│               │   ├── ic_logs.xml
│               │   ├── ic_settings.xml
│               │   ├── status_indicator_enabled.xml
│               │   └── status_indicator_disabled.xml
│               ├── layout/
│               │   ├── activity_main.xml
│               │   ├── fragment_home.xml
│               │   ├── fragment_modules.xml
│               │   ├── fragment_repo.xml                  # v2.9
│               │   ├── fragment_logs.xml
│               │   ├── fragment_settings.xml
│               │   ├── item_module.xml
│               │   ├── item_hooked_process.xml
│               │   ├── item_log.xml
│               │   ├── item_app_selection.xml
│               │   ├── dialog_add_module.xml
│               │   ├── dialog_select_apps.xml
│               │   └── sheet_module_detail.xml
│               ├── menu/
│               │   ├── main_menu.xml
│               │   └── main_options_menu.xml
│               ├── mipmap-anydpi-v26/
│               │   ├── ic_launcher.xml
│               │   └── ic_launcher_round.xml
│               ├── values/
│               │   ├── colors.xml
│               │   ├── strings.xml
│               │   └── themes.xml
│               └── xml/
│                   ├── backup_rules.xml
│                   ├── data_extraction_rules.xml
│                   └── file_paths.xml
├── native/
│   ├── libshizuposed.c                           # single-file native shim
│   └── build-termux.sh                           # optional Termux build wrapper
├── keystore/
│   └── shizuposed-release.jks                    # not committed
├── keystore.properties                           # not committed
├── libs/
│   ├── shizuku-api.jar
│   ├── shizuku-provider.jar
│   ├── shizuku-aidl.jar
│   ├── shizuku-shared.jar
│   └── pine-0.3.0.jar
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew (if applicable)
├── gradlew.bat (if applicable)
├── .gitignore
└── README.md
```

## Timing model

| Phase | Zygote (LSPosed) | Bootstrap (ShizuPosed) | Post-Application |
|---|---|---|---|
| `Application.attachBaseContext` | ✅ | ✅ | ❌ |
| `Application.onCreate` | ✅ | ✅ | ❌ |
| `ContentProvider.onCreate` | ✅ | ✅ | ❌ |
| `Activity.onCreate` | ✅ | ✅ via Pine, Native, or Instrumentation | ❌ |
| `Service.onCreate` | ✅ | ❌ | ❌ |
| Bootloader unlock | ✅ usually | ❌ | ❌ |

---

## Requirements

| Requirement | Minimum |
|---|---|
| Android | 10 (API 29) |
| Architecture | ARM64 |
| Shizuku | 13.1.1+ |
| JDK | 21 |
| Android SDK | API 36 |
| clang (native shim only) | Termux `clang` or NDK r25+ |

Shizuku must be running and ShizuPosed must be granted Shizuku permission
before any hooking occurs. Root-started Shizuku enables uid switching during
bootstrap.

`clang` is only needed if you build `libshizuposed.so` yourself. Prebuilt
`.so` binaries are committed under `app/src/main/jniLibs/arm64-v8a/`, so a
plain `./gradlew :app:assembleDebug` works without a compiler toolchain on
the build host.

---

## Building

```bash
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosedManager
./gradlew clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For release builds, add a signing config as described in the full
documentation.

---

## Native hooking (`libshizuposed.so`)

ShizuPosed ships a small native shim that gives the framework a second ART
hooking engine, independent of Pine. It exists for three reasons:

1. **Redundancy.** If Pine's `.so` is missing, blocked by a ROM, or its
   ART-layout probe fails on a new Android version, `NativeBackend` keeps
   hooking working for methods it can reach.
2. **Self-containment.** The shim is under 1000 lines of C with no external
   dependencies beyond `liblog` and `libdl`. It's the piece of the framework
   that can be audited end-to-end.
3. **Dynamic adaptation.** The layout probe and argument marshaling adapt to
   Android changes at runtime. No hardcoded offset lists, no per-signature
   codegen, no rebuild when ART shifts.

### What it does

| Capability | How |
|---|---|
| Detect the ART `ArtMethod` layout | Walks the first 64 bytes of two real methods (`Object.hashCode`, `Object.toString`), cross-validates the entry-point and access-flags offsets, and confirms the entry point lands in executable memory via `/proc/self/maps` |
| Hook a Java method | Patches the `ArtMethod` quick-compiled entry point to a per-method stub; saves the original as a trampoline |
| Route to a Java callback | Per-method stubs load the `jmethodID` into `x17` and branch to a shared dispatcher, which forwards the raw argument registers to `NativeDispatcher` |
| Marshal arguments | `NativeDispatcher` decodes the raw registers using `Method.getParameterTypes()` — primitive signatures work automatically, no per-shorty work |
| Return a replacement value | Callbacks that call `setResult` have the value encoded and returned by the asm entry, skipping the original |
| Call the original | Dispatcher tail-branches to the trampoline after the callback runs (when no replacement was set) |
| Hook a native symbol | Inline-patches the prologue of any `dlsym` result (JNI functions, small libc leaves) |
| Graceful degradation | Returns `false` on unknown layouts instead of corrupting memory; `NativeBackend` falls through to the next backend |

### What it does not do

- **Hook methods in other apps.** No `ptrace`, no `process_vm_writev`, no
  cross-UID memory access. The shim only works in processes ShizuPosed
  launched via `app_process` — "be the target process" is the whole model.
- **See object arguments.** `String`, `Bundle`, `Context`, and any other
  object parameter arrive as `null` in the callback. The raw register value
  is a JVM-internal reference, not a `jobject` handle, and reconstructing
  one requires per-ABI native work that isn't done yet. Primitive arguments
  work.
- **See `thisObject`.** Instance methods currently see `null` for `this` in
  the callback, for the same reason.
- **Run after-hooks.** The asm entry returns or tail-branches immediately
  after `NativeDispatcher.dispatch(...)` completes. There is no post-call
  hook point. Modules that need `afterHookedMethod` should rely on Pine.
- **Hook PC-relative prologues.** The inline hooker refuses `adrp`, `adr`,
  `b`, `bl`, and `ldr literal` at the top of the target function. Many real
  functions start with `adrp`, so the inline path only covers small leaf
  functions. Full instruction decoding is not implemented.

### When it activates

`NativeBackend` is placed after `PineBackend` and `PineReplaceBackend` in
the dispatch chain. It is only reached when Pine declines a method or is
entirely unavailable. On a normal device running a healthy Pine, the native
shim never runs a hook. On a device where Pine is broken, the native shim
becomes the primary ART engine.

### Argument marshaling in practice

The dynamic dispatcher handles any primitive signature. For a hook on
`Activity.onCreate(Bundle)`:

- The `Bundle` argument is `null` in the callback (object arg).
- The callback still fires before the original runs.
- If the callback calls `setResult(...)`, that's ignored because the return
  type is `void`.
- The original `onCreate` runs normally.

For a hook on `SomeClass.compute(int, long)`:

- The `int` and `long` arguments are decoded from registers and visible in
  `param.args`.
- If the callback calls `setResult(42)`, the asm entry returns `42` without
  calling the original.
- Otherwise the original runs.

A healthy load looks like:

```
I ShizuPosedNative: JNI_OnLoad: libshizuposed 1.0.0
I ShizuPosedNative: layout probed: entry@0x20 access@0x4 ptr=1
D NativeBridge: libshizuposed.so loaded
D NativeBridge: szp_dispatch_entry = 0x...
D NativeBackend: Native ART hooker available: valid=1 entry@0x20 access@0x4 ptr=1
```

If you see `layout probe: no candidate matched`, the ART layout has changed
again and a new candidate needs adding to `probe_layout` in
`native/libshizuposed.c`.

---

## Module development

Standard Xposed API. The entry point is `assets/xposed_init` or a class
name under the module's own package (`<pkg>.MainHook`, `<pkg>.XposedMain`,
`<pkg>.Hook`, `<pkg>.XposedEntry`, `<pkg>.XposedModule`, `<pkg>.Module`,
`<pkg>.Main`, `<pkg>.XposedInit`).

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

---

## Compatibility shims

The `de.robv.android.xposed.*` package ships the classes modules link
against:

| Class | Purpose |
|---|---|
| `XposedHelpers` | `findAndHookMethod`, reflection helpers |
| `XposedBridge` | `getXposedVersion`, `isModuleEnabled`, `isModuleActive`, `getModuleScope`, `log(...)` |
| `LSPosedManager` | LSPosed-compatible manager API |
| `XSharedPreferences` | Read-only `SharedPreferences` shim |
| `XResources` | Resource replacement object |
| `IXposedHookLoadPackage` | Module entry point |
| `IXposedHookInitPackageResources` | Resource entry point |
| `XC_MethodHook`, `XC_MethodReplacement` | Callback base classes |
| `XC_LoadPackage`, `XC_InitPackageResources` | Param classes |

The API version reported is **93** (LSPosed's generation). Modules that
check `getXposedVersion() >= 82` accept ShizuPosed as compatible.

---

## Repo tab

As of v2.9 the bottom navigation has a **Repo** tab alongside Home, Modules,
Logs, and Settings. It currently displays a "Not yet implemented"
placeholder. When the module repository backend ships, this tab will let you
browse, install, and update Xposed modules directly from the manager,
similar to LSPosed's Repo tab.

Nothing about the existing hook path changes. Modules continue to install
via the Modules tab or be auto-detected from the device.

---

## Known limitations

- `system_server` is not hooked.
- `Activity.onCreate` and `Service.onCreate` are dispatched by AMS to the
  original process. Pine and the native shim can hook them from bootstrap
  mode when the ART layout allows; `Instrumentation` covers them when
  neither can.
- `Service.onCreate` is not covered by any fallback — Instrumentation
  doesn't dispatch Service lifecycle.
- XML-level resource replacement is not supported; only the programmatic
  `setReplacement(id, value)` form.
- No hot-reload; relaunch the target under ShizuPosed.
- ROMs that block `app_process` under all names (`app_process64`,
  `app_process`, `app_process32`) cannot run the framework at all.
- The Repo tab is a placeholder; it does not fetch or install anything yet.

### Native shim specific

- `libshizuposed.so` is **in-process only**. It cannot hook apps ShizuPosed
  did not launch, `system_server`, `zygote`, or any other UID's process.
  This is a hard constraint of running without root — no `ptrace`, no
  cross-UID memory access — not a missing feature.
- After-hooks are not dispatched from the native path. Use Pine when you
  need `afterHookedMethod`.
- Argument marshaling is not implemented in the native dispatcher. The
  Java callback sees an empty `args` array. Hooks that read or mutate
  arguments will not see them.
- The inline (native symbol) hooker refuses prologues containing
  PC-relative instructions. Only small leaf functions are hookable this
  way; a full arm64 instruction decoder is not part of the shim.
- Live-process hooking (after `Application.onCreate`) is not synchronised
  against concurrent execution of the target method. It is safe under
  bootstrap timing, which is the only mode ShizuPosed uses.
- Only `arm64-v8a` is supported. The `.so` contains no armv7, x86, or
  x86_64 code paths. On an x86_64 emulator, `System.loadLibrary` throws
  and `NativeBackend` degrades to Pine.

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