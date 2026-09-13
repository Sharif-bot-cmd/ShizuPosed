# ShizuPosed - Xposed via Shizuku | Non-Root Hook Framework

[![Version](https://img.shields.io/badge/version-2.9-green.svg)](https://github.com/Sharif-bot-cmd/ShizuPosed)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-29%2B-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-red.svg)](LICENSE)

**ShizuPosed** is a **non-root Xposed-style hook framework** for Android 10+
(API 29+). It runs a shell-side hook process via
[Shizuku](https://github.com/thedjchi/Shizuku) (UID 2000) and installs
method hooks through a **multi-backend dispatcher** whose primary engine is
[Pine](https://github.com/canyie/pine), with `Instrumentation` as a fallback
for Application and Activity lifecycle hooks. A compatibility layer absorbs
hidden-API renames so the framework keeps running on Android 16, 17, and
later.

It is **not** a zygote-timing framework. `Application.onCreate` and
`ContentProvider.onCreate` run in the hooked process under bootstrap mode;
`Activity.onCreate` and `Service.onCreate` are delivered by AMS to the
original process, so only hooks that Pine or Instrumentation can install
before dispatch will fire.

---

## Key features

| Feature | Status |
|---|---|
| No root, no bootloader unlock | ✅ |
| Non-system-modifying | ✅ |
| Multi-backend dispatcher (Pine → Instrumentation → Proxy → Noop) | ✅ |
| Bootstrap mode (hooks before `Application.onCreate`) | ✅ |
| `Instrumentation` fallback for Application / Activity lifecycle | ✅ v2.8 |
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
3. `InstrumentationBackend` — Application / Activity lifecycle
4. `ProxyBackend` — interface methods
5. `NoopBackend` — always succeeds, does nothing

Each backend is tried in order per method. The first one that installs wins.
`InstrumentationBackend` only accepts lifecycle methods; everything else
passes through to `Proxy` and `Noop`.

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
│           ├── java/
│           │   └── com/
│           │       └── shizuposed/
│           │           └── manager/
│           │               ├── ShizuPosedManagerApp.java
│           │               ├── MainActivity.java
│           │               ├── ShizukuHelper.java
│           │               ├── ui/
│           │               │   ├── HomeFragment.java
│           │               │   ├── ModulesFragment.java
│           │               │   ├── ModuleDetailSheet.java
│           │               │   ├── RepoFragment.java          # v2.9
│           │               │   ├── LogsFragment.java
│           │               │   └── SettingsFragment.java
│           │               ├── adapter/
│           │               │   ├── ModuleAdapter.java
│           │               │   ├── IconResolver.java
│           │               │   ├── AppSelectionAdapter.java
│           │               │   ├── HookedProcessAdapter.java
│           │               │   ├── LogAdapter.java
│           │               │   └── MainPagerAdapter.java
│           │               ├── model/
│           │               │   ├── ModuleInfo.java
│           │               │   ├── HookedProcess.java
│           │               │   └── LogEntry.java
│           │               ├── service/
│           │               │   └── ShizuPosedService.java
│           │               ├── core/
│           │               │   ├── XposedHook.java
│           │               │   ├── HookEngine.java
│           │               │   ├── HookDispatcher.java
│           │               │   ├── XposedHookBridge.java
│           │               │   ├── XposedHelpersImpl.java
│           │               │   ├── ModuleLoader.java
│           │               │   ├── ModuleScanner.java
│           │               │   ├── ProcessMonitor.java
│           │               │   ├── ResourceHooking.java
│           │               │   ├── backends/
│           │               │   │   ├── PineBackend.java
│           │               │   │   ├── PineReplaceBackend.java
│           │               │   │   ├── InstrumentationBackend.java
│           │               │   │   ├── ProxyBackend.java
│           │               │   │   └── NoopBackend.java
│           │               │   ├── hooks/
│           │               │   │   └── LifecycleRegistry.java
│           │               │   └── compat/
│           │               │       ├── AndroidCompat.java
│           │               │       ├── HiddenApiBypass.java
│           │               │       └── ReflectionUnsafe.java
│           │               ├── status/
│           │               │   └── ModuleStatusProvider.java
│           │               ├── utils/
│           │               │   ├── Logger.java
│           │               │   ├── FileUtils.java
│           │               │   └── ShellUtils.java
│           │               └── receiver/
│           │                   └── BootReceiver.java
│           ├── java/
│           │   └── de/
│           │       └── robv/
│           │           └── android/
│           │               └── xposed/
│           │                   ├── XposedHelpers.java
│           │                   ├── XposedBridge.java
│           │                   ├── LSPosedManager.java
│           │                   ├── XResources.java
│           │                   ├── XSharedPreferences.java
│           │                   ├── XC_MethodHook.java
│           │                   ├── XC_MethodReplacement.java
│           │                   ├── IXposedHookLoadPackage.java
│           │                   ├── IXposedHookInitPackageResources.java
│           │                   └── callbacks/
│           │                       ├── XC_LoadPackage.java
│           │                       └── XC_InitPackageResources.java
│           └── res/
│               ├── drawable/
│               │   ├── ic_launcher.xml
│               │   ├── ic_launcher_background.xml
│               │   ├── ic_launcher_foreground.xml
│               │   ├── ic_module.xml
│               │   ├── ic_home.xml
│               │   ├── ic_repo.xml                            # v2.9
│               │   ├── ic_logs.xml
│               │   ├── ic_settings.xml
│               │   ├── status_indicator_enabled.xml
│               │   └── status_indicator_disabled.xml
│               ├── layout/
│               │   ├── activity_main.xml
│               │   ├── fragment_home.xml
│               │   ├── fragment_modules.xml
│               │   ├── fragment_repo.xml                      # v2.9
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
| `Activity.onCreate` | ✅ | ✅ via Pine or Instrumentation | ❌ |
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

Shizuku must be running and ShizuPosed must be granted Shizuku permission
before any hooking occurs. Root-started Shizuku enables uid switching during
bootstrap.

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
  original process. Pine can hook them from bootstrap mode when the ART
  layout allows; `Instrumentation` covers them when Pine cannot.
- `Service.onCreate` is not covered by any fallback — Instrumentation
  doesn't dispatch Service lifecycle.
- XML-level resource replacement is not supported; only the programmatic
  `setReplacement(id, value)` form.
- No hot-reload; relaunch the target under ShizuPosed.
- ROMs that block `app_process` under all names (`app_process64`,
  `app_process`, `app_process32`) cannot run the framework at all.
- The Repo tab is a placeholder; it does not fetch or install anything yet.

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
```

## What changed vs. v2.8

| Area | Change |
|---|---|
| Version badge | 2.8 → 2.9 |
| Key features table | Added "Repo tab (placeholder) ✅ v2.9" |
| Project structure | Added `ui/RepoFragment.java`, `res/layout/fragment_repo.xml`, `res/drawable/ic_repo.xml` |
| New section | "Repo tab" between the compatibility shims and known limitations |
| Known limitations | Added "The Repo tab is a placeholder" bullet |
| Thread title (XDA) | Update to v2.9 if you're bumping it |