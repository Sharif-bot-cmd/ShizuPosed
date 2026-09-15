# ShizuPosed

**A non-root Xposed-compatible hook framework built on `app_process`.**

ShizuPosed runs Xposed-API modules in apps that are launched through it, without root, without bootloader unlock, and without modifying the system partition. It uses Shizuku to invoke `app_process` as the shell UID, bootstraps a Java runtime inside the target's process, installs method hooks through a multi-backend dispatcher, and exposes the standard `de.robv.android.xposed.*` API so modules written for LSPosed and classic Xposed keep working.

Version 3.7.

---

## What this is — and what it is not

This section exists because it determines everything else in the document.

**ShizuPosed is not a drop-in replacement for LSPosed.** It does not aim for feature parity. It uses a fundamentally different injection primitive — `app_process` into a target launched by ShizuPosed — because that is the only reliable injection path available without root on modern Android. That choice is what makes ShizuPosed possible, and it is also what defines its ceiling.

If you need zygote-wide hooking, `system_server` interception, cross-UID hooks, or `Service.onCreate` coverage, use LSPosed. ShizuPosed exists for users who cannot or will not root, and who still want LSPosed-API modules to work inside the apps ShizuPosed launches.

The rest of this document is honest about where that boundary lies.

---

## Why `app_process`

To explain ShizuPosed's shape, it helps to explain what it cannot do and why.

Without root, the available injection primitives on Android 10+ are narrow:

| Primitive | Why it doesn't work |
|---|---|
| `ptrace` | Blocked by SELinux for non-root UIDs. |
| `LD_PRELOAD` | Requires a writable, executable path the target will load from. App data dirs are `noexec`. |
| Zygote fork | Requires being inside zygote. Root or ROM only. |
| `Runtime.exec` | Runs as the caller's UID, not the target's. |
| `am instrument` | Gives you an `Instrumentation` handle after `Application.onCreate`. Too late for bootstrap hooks. |
| ContentProvider hijack | Only works if the target already declares a provider you can take over. You cannot add one. |

`app_process` works because:

1. It is a platform binary that already exists on every Android device.
2. Shizuku can invoke it as **shell (UID 2000)**, which has permissions normal apps do not.
3. It starts a Java runtime, so you can load arbitrary dex into it.
4. Shizuku can pass the target package and UID as arguments, letting ShizuPosed either run as shell or switch to the target's UID.

That is the entire mechanism. Everything in ShizuPosed follows from it.

---

## The timing model

LSPosed is a **zygote-timing** framework: it forks from zygote and installs hooks before the target's `Application` object exists. ShizuPosed is a **bootstrap-timing** framework: it is `app_process`-launched into the target, installs hooks, and *then* drives the target's own `ActivityThread` bootstrap so that `Application.onCreate` runs with hooks already live.

| Phase | Zygote (LSPosed) | Bootstrap (ShizuPosed) | Post-Application |
|---|---|---|---|
| `Application.attachBaseContext` | ✅ | ✅ | ❌ |
| `Application.onCreate` | ✅ | ✅ | ❌ |
| `ContentProvider.onCreate` | ✅ | ✅ | ❌ |
| `Activity.onCreate` | ✅ | ✅ via Pine, Native, or Instrumentation | ❌ |
| `Service.onCreate` | ✅ | ❌ | ❌ |
| Bootloader unlock | ✅ usually | ❌ | ❌ |

The `Service.onCreate` gap is structural. `Instrumentation` does not dispatch Service lifecycle, Pine and the native shim can only install hooks from bootstrap, and `app_process` cannot get into a service process it did not launch. There is no fallback because there is no mechanism.

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
  ├── HookEngine                 backend selection
  ├── HookDispatcher             per-method backend chain
  ├── ModuleLoader               dex load + entry invocation
  ├── ResourceHooking            IXposedHookInitPackageResources
  └── markers written to         /data/user/0/com.android.shell/files/.syscall_cache/hooked/
```

The manager never touches the target process directly. Everything passes through Shizuku, which is the only component that has the privilege to launch `app_process` in the first place.

---

## The multi-backend dispatcher

Not every method can be hooked the same way. The dispatcher tries a fixed order and the first backend that succeeds wins:

| Order | Backend | Covers |
|---|---|---|
| 1 | `PineBackend` | Pine AUTO mode. Primary engine. Handles object arguments, after-hooks, most method shapes. |
| 2 | `PineReplaceBackend` | Pine REPLACEMENT mode. For `XC_MethodReplacement` callbacks. |
| 3 | `NativeBackend` | `libshizuposed.so` ART entry-point patcher. Fallback when Pine is missing, blocked, or its layout probe fails. |
| 4 | `InstrumentationBackend` | Application and Activity lifecycle only. |
| 5 | `ProxyBackend` | Interface methods. |
| 6 | `NoopBackend` | Always succeeds. Does nothing. Last resort so module load does not abort. |

The order matters. `NativeBackend` is only reachable when Pine declines a method or is entirely unavailable. On a device with a healthy Pine, the native shim never runs a hook. On a device where Pine is broken by a ROM or an Android version shift, the native shim becomes the primary ART engine for whatever it can reach.

`NoopBackend` deserves a note: it reports success without installing anything. This is deliberate — a module that hooks ten methods and finds one it cannot hook should not abort the other nine. But it also means a module can appear to have loaded successfully while installing zero real hooks. If you are debugging why a module "runs" but does not work, check whether the dispatcher silently fell through to `Noop`.

---

## The native shim (`libshizuposed.so`)

ShizuPosed ships a small ARM64 native library that provides a second ART hooking engine, independent of Pine. It exists for three reasons:

1. **Redundancy.** If Pine's `.so` is missing, blocked by a ROM, or its layout probe fails on a new Android version, the native backend keeps hooks working for the methods it can reach.
2. **Self-containment.** The shim is under 1000 lines of C with no dependencies beyond `liblog` and `libdl`. It is the part of the framework that can be audited end-to-end.
3. **Dynamic adaptation.** The layout probe and argument marshaling adapt at runtime. No hardcoded offset tables, no per-signature codegen, no rebuild when ART shifts.

### What it does

| Capability | How |
|---|---|
| Detect the ART `ArtMethod` layout | Walks the first 64 bytes of two real methods (`Object.hashCode`, `Object.toString`), cross-validates the entry-point and access-flags offsets, and confirms the entry point lands in executable memory via `/proc/self/maps`. |
| Hook a Java method | Patches the `ArtMethod` quick-compiled entry point to a per-method stub; saves the original as a trampoline. |
| Route to a Java callback | Stubs load the `jmethodID` into `x17` and branch to a shared dispatcher, which forwards raw argument registers to `NativeDispatcher`. |
| Marshal primitive arguments | `NativeDispatcher` decodes raw registers using `Method.getParameterTypes()`. Any primitive signature works automatically. |
| Return a replacement value | Callbacks that call `setResult` have the value encoded and returned by the asm entry, skipping the original. |
| Call the original | Dispatcher tail-branches to the trampoline after the callback runs when no replacement was set. |
| Hook a native symbol | Inline-patches the prologue of any `dlsym` result — JNI functions, small libc leaves. |
| Graceful degradation | Returns `false` on unknown layouts instead of corrupting memory; `NativeBackend` falls through to the next backend. |

### What it does not do

These are not missing features. They are consequences of running without root.

- **Hook methods in other apps.** No `ptrace`, no `process_vm_writev`, no cross-UID memory access. The shim only works in processes ShizuPosed launched via `app_process`. "Be the target process" is the whole model.
- **See object arguments.** `String`, `Bundle`, `Context`, and any other object parameter arrive as `null` in the callback. The raw register value is a JVM-internal reference, not a `jobject` handle. Reconstructing a `jobject` from a raw register requires per-ABI native work that is not implemented.
- **See `thisObject`.** Instance methods see `null` for `this` in the callback, for the same reason.
- **Run after-hooks.** The asm entry returns or tail-branches immediately after `NativeDispatcher.dispatch(...)`. There is no post-call hook point. Modules that need `afterHookedMethod` should rely on Pine.
- **Hook PC-relative prologues.** The inline hooker refuses `adrp`, `adr`, `b`, `bl`, and `ldr literal` at the top of the target function. Many real functions start with `adrp`, so the inline path only covers small leaf functions. Full instruction decoding is not implemented.
- **Support non-ARM64.** No armv7, x86, or x86_64 code paths. On an x86_64 emulator, `System.loadLibrary` throws and `NativeBackend` degrades to Pine.

### When it activates

`NativeBackend` is placed after the two Pine backends in the dispatch chain. It is only reached when Pine declines a method or is entirely unavailable. On a normal device running a healthy Pine, the native shim never runs a hook.

A healthy load looks like:

```
I ShizuPosedNative: JNI_OnLoad: libshizuposed 1.0.0
I ShizuPosedNative: layout probed: entry@0x20 access@0x4 ptr=1
D NativeBridge: libshizuposed.so loaded
D NativeBridge: szp_dispatch_entry = 0x...
D NativeBackend: Native ART hooker available: valid=1 entry@0x20 access@0x4 ptr=1
```

If you see `layout probe: no candidate matched`, ART's layout has changed again and a new candidate needs adding to `probe_layout` in `native/libshizuposed.c`.

---

## Module loading

Standard Xposed module resolution:

1. The manager scans each installed module's APK for `assets/xposed_init` and caches the APK as a dex container next to the target's own module record.
2. The shell-side `XposedHook` reads the module JSON, checks whether the target package is in the module's scope, and loads the dex with a `DexClassLoader` whose parent is the target's classloader.
3. The entry class is resolved from `xposed_init`, or from `assets/xposed_init` read directly out of the cached dex, or — as a last resort — from one of eight conventionally-named candidates (`<pkg>.MainHook`, `<pkg>.XposedMain`, `<pkg>.Hook`, `<pkg>.XposedEntry`, `<pkg>.XposedModule`, `<pkg>.Module`, `<pkg>.Main`, `<pkg>.XposedInit`).
4. `handleLoadPackage` is invoked. If it returns normally, the module name is added to the process's `loadedModuleNames` list.
5. After the module load loop completes, `writeHookedMarker` writes a JSON file to the shell-side marker directory recording which modules actually ran.

The marker is the source of truth for activation state.

---

## Activation state — how "Activated" actually works

This is the part users ask about most, so it deserves its own section.

There are two distinct questions a module can ask about itself:

| Question | API | Source of truth |
|---|---|---|
| "Am I enabled?" | `XposedBridge.isModuleEnabled(pkg)` | The manager's preference store. |
| "Am I active?" | `XposedBridge.isModuleActive(pkg)` | The shell-side marker files written by `XposedHook`. |

**Enabled** is a user preference. Nothing about the target app or the hook process is involved.

**Active** means: *at least one in-scope process has actually loaded this module's entry class and returned from `handleLoadPackage`.* This is not inferred from the enabled toggle. It is a fact observed by the framework and recorded on disk.

### How a module's UI learns it is active

A module's own UI runs in the module's own process — the process launched by the launcher, not by ShizuPosed. That process is **not hooked**. It has no access to ShizuPosed's in-memory state. The only way it can learn its activation status is by asking the manager.

The chain is:

```
Module UI process (module's own APK, its own signing key)
  → XposedBridge.isModuleActive(pkg)
    → LSPosedManager.isModuleActive(pkg)
      → ContentResolver.query(content://com.shizuposed.manager.status/active/<pkg>)
        → ModuleStatusProvider (in the manager's process)
          → reads /data/user/0/com.android.shell/files/.syscall_cache/hooked/*.json
          → parses each marker's moduleList
          → returns active=1 or active=0
```

This is why the provider must be **world-readable**. It exposes only:
- whether a module is enabled (a preference),
- whether a module has loaded into at least one target,
- which targets a module has loaded into,
- framework name, version, and enabled-module count.

None of that is sensitive, and a signature-level permission would make it unreachable to every module, which is exactly the failure mode ShizuPosed 3.6 had and 3.7 fixes.

### If a module shows "not activated"

Check these in order:

1. **Has the target app been launched through ShizuPosed since the module was enabled?** Activation is not retroactive. A scoped app must be launched through ShizuPosed for the module to load into it.
2. **Does a marker exist for the target?** `adb shell su -c "ls /data/user/0/com.android.shell/files/.syscall_cache/hooked/"` — if empty, the module never ran in any process.
3. **Does the marker list the module?** `adb shell su -c "cat /data/user/0/com.android.shell/files/.syscall_cache/hooked/<target>.json"` — the `moduleList` array must contain the module's package name.
4. **Can the module's process reach the provider?** `adb shell content query --uri content://com.shizuposed.manager.status/info` — should return a row. If it does not, the provider is not exported or the module does not declare the `<queries>` entry for it on Android 11+.
5. **Is the module calling a different `XposedBridge`?** If logcat shows nothing from the `ShizuPosed` tag when the module's UI opens, the module is linking against a different copy of the shim.

---

## Compatibility shims

The `de.robv.android.xposed.*` package ships the classes modules link against:

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

The API version reported is **93** (LSPosed's generation). Modules that check `getXposedVersion() >= 82` accept ShizuPosed as compatible.

---

## Module development

Standard Xposed API. The entry point is `assets/xposed_init`, or one of the conventionally-named classes listed in *Module loading* above.

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

For the module UI to reach the provider on Android 11+, the module's own `AndroidManifest.xml` must declare:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

Without that declaration, `ContentResolver.query` returns `Unknown URL` regardless of whether the provider is exported.

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

Only the programmatic form is supported. XML-level resource replacement is not implemented.

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

Shizuku must be running and ShizuPosed must be granted Shizuku permission before any hooking occurs. `clang` is only needed if you build `libshizuposed.so` yourself. Prebuilt `.so` binaries are committed under `app/src/main/jniLibs/arm64-v8a/`, so a plain `./gradlew :app:assembleDebug` works without a compiler toolchain on the build host.

---

## Building

```bash
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosed
./gradlew clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For release builds, add a signing config as described in the full documentation.

---

## Structure

```
ShizuPosed/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── XposedHook.dex
│           ├── jniLibs/
│           │   └── arm64-v8a/
│           │       └── libshizuposed.so
│           ├── java/
│           │   ├── com/shizuposed/manager/
│           │   │   ├── ShizuPosedManagerApp.java
│           │   │   ├── MainActivity.java
│           │   │   ├── ShizukuHelper.java
│           │   │   ├── ui/
│           │   │   ├── adapter/
│           │   │   ├── model/
│           │   │   ├── service/
│           │   │   │   └── ShizuPosedService.java
│           │   │   ├── core/
│           │   │   │   ├── XposedHook.java
│           │   │   │   ├── HookEngine.java
│           │   │   │   ├── HookDispatcher.java
│           │   │   │   ├── XposedHookBridge.java
│           │   │   │   ├── XposedHelpersImpl.java
│           │   │   │   ├── NativeBridge.java
│           │   │   │   ├── NativeDispatcher.java
│           │   │   │   ├── ModuleLoader.java
│           │   │   │   ├── ModuleScanner.java
│           │   │   │   ├── ProcessMonitor.java
│           │   │   │   ├── ResourceHooking.java
│           │   │   │   ├── backends/
│           │   │   │   ├── hooks/
│           │   │   │   └── compat/
│           │   │   ├── status/
│           │   │   │   └── ModuleStatusProvider.java
│           │   │   ├── utils/
│           │   │   └── receiver/
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
│           └── res/
├── native/
│   ├── libshizuposed.c
│   └── build-termux.sh
├── keystore/
├── keystore.properties
├── libs/
│   ├── shizuku-api.jar
│   ├── shizuku-provider.jar
│   ├── shizuku-aidl.jar
│   ├── shizuku-shared.jar
│   └── pine-0.3.0.jar
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
├── .gitignore
└── README.md
```

---

## What's new in 3.7

### Fixed

- **`ModuleStatusProvider` was unreachable by modules.** The provider was declared with a `signature`-level read permission. Modules are signed with their own keys, so every module UI query threw `SecurityException`, which was silently swallowed by `catch (Throwable ignored)`. The provider is now world-readable. It exposes only non-sensitive state: enabled/active flags, scope, framework name and version. This was the root cause of "module features exist but the UI never shows Activated."
- **`isModuleActive` returned `false` on background threads.** `ActivityThread.currentApplication()` only reliably returns the Application on the main thread. Module UIs frequently poll from a background thread or a `ContentProvider.onCreate`. Both `LSPosedManager` and `XposedBridge` now fall back to `ActivityThread.currentActivityThread().getSystemContext()`, then to a shared static fallback captured by `XposedBridge.log()`.
- **`XposedBridge.isModuleEnabled()` (no-arg) returned `true` unconditionally.** It now derives the enabled state from the caller's own package, matching LSPosed semantics.
- **Silent failures everywhere.** Every `catch (Throwable ignored)` in `LSPosedManager`, `XposedBridge`, and `ModuleStatusProvider` now logs through `Log.e`. If activation fails again, logcat will show which hop broke.
- **The marker JSON was matched by substring.** `ModuleStatusProvider` searched for `"<pkg>"` as a substring of the raw marker file. It now parses the JSON with `org.json` and reads `moduleList` explicitly. The writer (`XposedHook.writeHookedMarker`) now uses `org.json` too, so both sides agree on the schema.
- **The provider shelled out on every query.** A single `isModuleActive` call with 30 hooked targets and 5 modules generated 150+ Shizuku round-trips. The marker scan is now cached for 3 seconds and shared between `queryModuleActive` and `queryModuleScope`.
- **`ensureModulesLoaded` could race during reload.** It cleared and repopulated the module map in place, so a concurrent query could observe an empty map mid-load. Now synchronized.
- **`XposedHook.parseModule` used a hand-rolled JSON parser.** Replaced with `org.json`. The old parser broke on any string value containing a `"key":` sequence.
- **`XposedHook.loadModule` fell back to `/data/local/tmp/pine-opt`.** That directory is not reliably writable in bootstrap mode. The fallback is now `BASE_DIR/dexopt/<pkg>`, which is shell-owned and always writable.
- **`XposedHook.guessEntryPoint` ignored `assets/xposed_init`.** The manager already extracted the entry class from the APK, but if the JSON record was stale or the field missing, the shell side had no way to recover. `loadModule` now reads `assets/xposed_init` directly out of the cached dex as a fallback.

### Added

- **`ModuleStatusProvider.call()` support.** Modern modules sometimes use `ContentResolver.call()` instead of `query()`. The provider now responds to `active`, `enabled`, `scope`, and `info` methods with a `Bundle`.
- **`LSPosedManager.getFallbackContext()`.** Shared with `XposedBridge` for context resolution.
- **This README.** Rewritten to be honest about the mechanism and the limits, in the shape of how Shizuku's own documentation explains why it works the way it does.

---

## Known limitations

These are structural. They cannot be fixed without changing what ShizuPosed is.

- `system_server` is not hooked. Hooking it requires being inside zygote, which requires root.
- `Activity.onCreate` and `Service.onCreate` are dispatched by AMS to the original process. Pine and the native shim can hook `Activity.onCreate` from bootstrap mode when ART's layout allows. `Service.onCreate` has no fallback — `Instrumentation` does not dispatch Service lifecycle, and there is no other mechanism.
- XML-level resource replacement is not supported. Only the programmatic `setReplacement(id, value)` form.
- No hot-reload. Relaunch the target under ShizuPosed to pick up module changes.
- ROMs that block `app_process` under all names (`app_process64`, `app_process`, `app_process32`) cannot run the framework at all. This is rare but real on hardened ROMs.
- The Repo tab is a placeholder. It does not fetch or install anything yet.
- Modules that require zygote-wide timing will not work. This is the majority of modules that hook `system_server`, `PackageManagerService`, `ActivityManagerService`, or anything in `com.android.server.*`.

### Native shim specific

- **In-process only.** No `ptrace`, no `process_vm_writev`, no cross-UID memory access. The shim only works in processes ShizuPosed launched via `app_process`.
- **No after-hooks.** The asm entry returns or tail-branches immediately after the callback runs.
- **Object arguments arrive as `null`.** The raw register value is a JVM-internal reference, not a `jobject` handle.
- **`thisObject` arrives as `null`** for the same reason.
- **Inline (native symbol) hooking refuses PC-relative prologues.** `adrp`, `adr`, `b`, `bl`, and `ldr literal` are not decoded. Only small leaf functions are hookable this way.
- **Live-process hooking is not synchronised** against concurrent execution of the target method. It is safe under bootstrap timing, which is the only mode ShizuPosed uses.
- **ARM64 only.** No armv7, x86, or x86_64 code paths. On an x86_64 emulator, `System.loadLibrary` throws and `NativeBackend` degrades to Pine.

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

- **Rikka** — for Shizuku, the primitive that makes all of this possible.
- **Pine** — for the primary ART hooking engine.
- **LSPosed** — for the API generation ShizuPosed targets, and for the design of the module status provider contract.
- Every module author who kept the `de.robv.android.xposed.*` API alive long enough for an alternative to matter.