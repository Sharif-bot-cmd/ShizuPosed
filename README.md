# ShizuPosed

**A non-root Xposed-compatible hook framework built on `app_process`.**

ShizuPosed runs Xposed-API modules in apps launched through it — no root, no bootloader unlock, no system partition changes. Shizuku invokes `app_process` as the shell UID, a Java runtime bootstraps inside the target's process, and method hooks go in through a multi-backend dispatcher. Modules written for LSPosed and classic Xposed keep working.

Version 7.0.

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

### Row interaction model

The Modules tab row supports four gestures, each doing one thing:

| Gesture | Action |
|---|---|
| Tap the row body | Opens the scope editor |
| Tap the enable switch | Toggles the module |
| Tap the icon | Opens the detail sheet |
| Long-press the row | Opens the module's UI through ShizuPosed |

Uninstall lives in the detail sheet's `Uninstall` button. That's a deliberate choice: opening a module's UI is a much more common action than removing it, and long-press is a reasonable gesture for it.

### Behavior of XStealth's row

XStealth's row keeps its special treatment. No enable switch on the row (its toggle lives in the detail sheet), and long-press opens the detail sheet rather than the UI, because XStealth has no launchable UI.

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
  │     ├── DevOptionsCheck      Settings.Global + resolver hooks
  │     ├── AdbCheck             Settings.Secure + Global + resolver
  │     ├── SettingsFileCheck    Runtime.exec + ProcessBuilder
  │     ├── SocketCheck          LocalSocket shim (opt-in)
  │     ├── PackageCheck         PackageManager lookups
  │     ├── RunningProcessCheck  ActivityManager lookups
  │     ├── ApiProtectionCheck   reflection + baselines
  │     ├── XStealthNative       libc symbol interposition
  │     │     └── includes exec-family interposers
  │     └── XStealthNativeNext   libc syscall stub patching
  └── markers written to         <shell-base>/hooked/

Manager process
  ├── ProcessMonitor             /proc scan + marker mirror refresh
  ├── MarkerCache                local mirror of <shell-base>/hooked/
  ├── IconResolver               LruCache for app icons
  ├── RuntimePrefs               scan interval + hook delay storage
  └── ModuleStatusProvider       reads the mirror, never Shizuku
```

The manager never touches the target process directly. Everything passes through Shizuku.

---

## The multi-backend dispatcher

Not every method can be hooked the same way, so the dispatcher tries a fixed order and takes the first backend that succeeds.

Pine AUTO is the primary engine. Pine REPLACEMENT catches methods Pine AUTO rejects. Amiru is a per-method stub engine reached when Pine declines. CallSite patches the ART interpreter dispatch table. Native is the original `libshizuposed.so` shared-dispatcher engine. Instrumentation covers the Application and Activity lifecycle only. Proxy handles interface methods. Noop always succeeds and does nothing — so a module that hooks ten methods and finds one it can't hook doesn't lose the other nine.

`HookEngine` records the declaring class of every method or constructor it attempts to hook. That record feeds `ApiProtectionCheck`'s fingerprint baselines.

---

## The dex-loading fallback ladder

Loading a module's dex into a target process can fail for several reasons. `DexLoadingBridge` tries three strategies in order:

1. **`InMemoryDexClassLoader`** (Android 8+). Reads the dex into a `ByteBuffer` and loads it without any filesystem write.
2. **`DexClassLoader`.** The classic path. Reads the dex from a file and uses an optimization directory for ART's compiled output.
3. **`BaseDexClassLoader` injection.** Appends the module's dex to the target's existing classloader by reflection into its `DexPathList`.

Each strategy logs its outcome.

---

## XStealth — the built-in privacy module

XStealth ships with ShizuPosed and hides the framework's presence from detection checks in target apps. No separate APK. Its classes live in `XposedHook.dex`, its UI is a detail sheet in the Modules tab.

XStealth applies to every app ShizuPosed launches. It has no per-app scope.

XStealth is organized in two layers:

- **Java-layer checks** — hook Android APIs inside the target's own process.
- **Native layer** — interpose libc symbols and patch libc syscall stubs so native callers are also covered.

Both layers fail open. A bug in a check means the target gets the real API result, not a crash.

### What XStealth hides

**Developer Options and ADB.** Hooks every settings read path: `Settings.Global` and `Settings.Secure` static getters, the `ForUser` variants, and direct `ContentResolver.query` calls against `content://settings/{global,secure}/<key>`.

**Subprocess reads of the settings XML (7.0).** `SettingsFileCheck` hooks `Runtime.exec` (all five overloads) and `ProcessBuilder.start()`. When a command references a settings XML path or a watched shell-side path, it returns a synthetic `Process` whose stdout is a scrubbed XML document, with exit code 0. The subprocess never spawns.

**Shizuku and ShizuPosed packages.** Intercepts `PackageManager.getPackageInfo`, `getApplicationInfo`, `getInstalledPackages`, and `getInstalledApplications` — the int overloads and the newer `PackageInfoFlags` / `ApplicationInfoFlags` overloads.

**Running processes.** Filters `ActivityManager.getRunningAppProcesses`, `getRunningServices`, and `getRunningTasks`.

**`/proc` entries.** With the native layer active, hidden strings are scrubbed from `/proc/self/maps`, `cmdline`, `status`, and `mountinfo`.

**The shim classes.** `ApiProtectionCheck` refuses `Class.forName` and `ClassLoader.loadClass` for `de.robv.android.xposed.*` when the caller isn't a loaded module. It also filters reflection walks on shim classes and hooks resource/package lookups for shim paths.

**Method-count fingerprinting.** Pins a baseline for every framework class the framework has actually hooked.

### What XStealth doesn't hide

Hardware attestation (Play Integrity `MEETS_STRONG_INTEGRITY`). Root-empowered inspection. Server-side cross-reference. Native reads of the settings database that don't go through the exec family. Method-list reconstruction. Exotic reflection via `Unsafe` or JNI-level private methods. Signals unrelated to ShizuPosed: keyboard, accessibility services, overlay permissions, bootloader state, custom ROM, screen recorders.

---

## The subprocess gap (7.0)

A common detection pattern is to bypass the Java settings API entirely and shell out:

```java
Runtime.getRuntime().exec(new String[]{
    "sh", "-c",
    "cat /data/system/users/0/settings_global.xml"});
```

The app never calls `Settings.Global.getInt()`. It never calls `ContentResolver.query()`. It reads the XML directly through a subprocess that has the file permission the app itself lacks.

Before 7.0, XStealth did not cover this. An app using this pattern would see the real settings and detect Developer Options or ADB.

7.0 closes the gap in two layers:

**Java layer — `SettingsFileCheck`.** Hooks `Runtime.exec` (all five public overloads) and `ProcessBuilder.start()`. If a command references a watched settings XML path or a watched shell-side data path, the hook returns a synthetic `Process` that serves a scrubbed XML document on stdout, an empty stream on stderr, and `exitValue() == 0`. No subprocess spawns.

**Native layer — `libxstealth.so` exec interposers.** Hooks `execve`, `posix_spawn`, `posix_spawnp`, `popen`, and `system`. This catches callers that go through JNI or a native library instead of the Java `Runtime.exec` API. The `posix_spawn` interposer catches `Runtime.exec` at the native layer on Android 10+, because `ProcessImpl` routes through it. So even if the Java hook is somehow bypassed, the native layer still serves scrubbed output.

Both layers use the same narrow match rule: only commands that reference a watched path or a watched key alongside a file-reading verb are affected. `logcat -d`, `getprop`, and other unrelated shell-outs pass through unchanged.

**Still not covered:** native `execve` called before the `.so` is loaded, or from a process that loaded its own copy of libc. That's a load-order limitation, not a design gap.

---

## Socket shim — opt-in, off by default (7.0)

Some detection kits talk to a privileged daemon over a unix domain `LocalSocket` instead of through Binder. To intercept that traffic, XStealth includes `SocketCheck`.

`SocketCheck` is **off by default** and installs **no hooks** unless a specific daemon is configured. The reasoning:

- `LocalSocket` is used by crash reporters, analytics SDKs, media pipelines, and custom app IPC. Blanket-hooking it breaks apps.
- Intercepting a daemon's traffic requires knowing that daemon's wire protocol. The protocol isn't guessable; it has to be reversed.
- Without a protocol implementation, interception is a no-op that costs CPU and adds risk.

The shape:

- `SocketCheck.install(lpparam, config)` returns early if `config.hideSocketDaemons` is false or `config.socketDaemons` is empty.
- When a daemon is configured, its traffic is shadowed but passed through byte-for-byte until a `Responder` for that daemon is registered. A no-op `Responder` is installed by default.
- When you reverse a daemon's protocol, you swap the no-op for a real `Responder` and the shim starts serving synthetic replies.

This is infrastructure, not a working feature. It exists so that when a specific daemon needs to be shimmed, the mechanism is already in place.

---

## XStealth Next — the aggressive engine

The primary native engine (`libxstealth.so`) interposes libc functions by symbol. XStealth Next closes the gap for targets that resolve the syscall stub directly. It finds the libc stubs and inline-patches their prologues with a branch to a per-syscall handler. It also patches the generic `syscall()` dispatcher and covers `fstatat`/`newfstatat`.

Turning Next on renames the module's display in the Modules tab to **XStealth (Next)**.

---

## API surface — version 96

ShizuPosed reports Xposed API version **96** (LSPosed generation 93, plus fork revisions 94, 95, and 96).

- **93** — LSPosed base. `IXUnhook`, `isModuleActive` and `isModuleEnabled` with and without arguments, provider-backed state queries.
- **94** — `hookAllMethods` returns `Set<XC_MethodHook.Unhook>`.
- **95** — `hookAllConstructors` returns the same.
- **96** — `MethodHookParam.isReturnEarly()` exposed.

Modules that guard on 97 or higher correctly believe ShizuPosed doesn't support those fork revisions.

---

## Module loading

Standard Xposed module resolution. The manager scans each installed module's APK for one of four markers:

1. `assets/xposed_init` — the canonical marker, contains the entry class.
2. `assets/xposed_module` — an older convention, same content.
3. `META-INF/xposed/module.prop` — EdXposed-era, key=value format.
4. `assets/native_init` — a weak signal. The module ships native hooks and may have no Java entry point.

Once detected, the descriptor and dex go to the shell side. `XposedHook` reads the module JSON, checks the target against the module's scope, and loads the dex through `DexLoadingBridge`.

Built-in modules skip the dex load. The marker file is the source of truth for activation state.

### Recommended scope

Modules can declare which apps they're *intended* for by shipping an `assets/scope.list` file inside the APK. ShizuPosed reads that file and surfaces the packages as **recommended**: a chip in the scope editor that selects them, a badge on their rows, and a count on the module's row.

---

## Activation state — how "Activated" actually works

Two distinct questions. "Am I enabled?" comes from `XposedBridge.isModuleEnabled(pkg)`, sourced from the manager's preference store. "Am I active?" comes from `XposedBridge.isModuleActive(pkg)`, sourced from the shell-side marker files.

### The marker mirror

The shell-side `XposedHook` writes one JSON marker per hooked target to `<shell-base>/hooked/<pkg>.json` after installing hooks. The manager mirrors the markers into its own files directory. `ProcessMonitor` runs one compound shell command every five seconds and writes each marker into `<manager files>/.markers/<pkg>.json`. `ModuleStatusProvider` reads from that mirror.

- **Queries are local file reads.** No Shizuku in the query path.
- **Activation state survives Shizuku outages.**
- **The shell cost is bounded.**

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

### The Android 11+ package visibility requirement

On Android 11 and later, a module that queries the status provider from its own UI must declare the provider's authority in the module's own `AndroidManifest.xml`:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

Without it, the query returns null regardless of the provider being exported.

### Modules that check their own activation state

Some modules don't query the framework's provider. They hook one of their own UI methods and use the hook's presence as the activation signal. The pattern is:

```java
if (MODULE_PACKAGE.equals(lpparam.packageName)) {
    XposedHelpers.findAndHookMethod(
        MainActivity.class.getName(),
        lpparam.classLoader,
        "isXposedEnabled",
        XC_MethodReplacement.returnConstant(true));
    return;
}
```

For that hook to install, the module's own UI process needs hooks. Under ShizuPosed that means launching the module's UI through ShizuPosed.

**How to do it:**

- Long-press the module's row in the Modules tab.
- Or tap "Open module app" in the detail sheet.

Either route opens the module's UI through ShizuPosed. The self-hook installs. The UI shows "Enabled."

If the user launches the module's UI from the launcher instead, the hook doesn't install and the UI shows the original value — usually "Disabled." This is a consequence of the injection model, not a bug.

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

Android 10 or newer, ARM64. Shizuku 13.1.1 or newer, running and authorized — the recommended build is the fork by **thedjchi** at `github.com/thedjchi/Shizuku`. About 200 MB free storage. No root required.

For building the app: JDK 21 and Android SDK 37.

For rebuilding the native libraries: `clang` alone is sufficient on Termux. No NDK is required if you're building on-device, because Termux clang already targets the host platform (aarch64 Android) and ships the Android headers. If you're cross-compiling from a desktop, use NDK r25+ or Termux clang.

Shevery is also supported, though some of its privileged-API paths have known issues.

---

## Installation

Install Shizuku (fork recommended) from the link above, then start it via ADB or via the Shizuku app's own start flow.

Install the ShizuPosed Manager APK:

```
adb install -r ShizuPosed-R-7.0.apk
```

Or just tap the APK to install it. ADB is not required for the manager itself — only for starting Shizuku.

Open the manager. It requests Shizuku permission on first launch. Add a module from the Modules tab, or use one that auto-detects. Edit the module's scope to choose which apps it applies to. Then tap **Launch App under ShizuPosed** and pick a scoped app.

Activation isn't retroactive. A module's UI will show "Activated" only after at least one scoped app has been launched through ShizuPosed.

---

## The manager

Five tabs.

**Home** shows the status card (Activated with a green check, or Not Activated with a red cross), the counter tiles, and the Framework Info card.

**Modules** lists installed modules with XStealth always at the top. Four gestures on each row: tap body for scope, tap switch for enable/disable, tap icon for detail sheet, long-press to open the module's UI through ShizuPosed.

**Repo** shows every installed module with sub-tabs for Readme, Releases, and Info.

**Logs** shows the manager's own log with live search and a clear button.

**Settings** has the runtime toggles, scan interval and hook delay sliders, cache management, and config export.

The XStealth detail sheet gains two new toggles in 7.0:

- **Hide subprocess reads** — gates `SettingsFileCheck`. On by default.
- **Hide socket daemons** — gates `SocketCheck`. Off by default. Does nothing unless daemons are listed in the config.

---

## Building

### The app

```bash
export ANDROID_HOME=$HOME/Android/Sdk
git clone <repo>
cd ShizuPosed
./gradlew clean :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### The native libraries (7.0)

On Termux, from the source directory:

```bash
pkg install clang binutils

clang -shared -fPIC -O2 \
    -o libxstealth.so \
    libxstealth.c \
    -llog -ldl -lpthread

clang -shared -fPIC -O2 \
    -o libxstealth_next.so \
    libxstealth_next.c \
    -llog -ldl -lpthread
```

No NDK, no target triple, no cross-compilation. Termux clang is already running on the target architecture and ships the Android headers (`jni.h`, `android/log.h`). If any header is missing:

```bash
pkg install ndk-sysroot
```

Deploy:

```bash
adb push libxstealth.so libxstealth_next.so /data/local/tmp/
adb shell "cp /data/local/tmp/libxstealth.so \
            /data/user/0/com.android.shell/files/libs/"
adb shell "cp /data/local/tmp/libxstealth_next.so \
            /data/user/0/com.android.shell/files/libs/"
adb shell "chmod 644 /data/user/0/com.android.shell/files/libs/libxstealth*.so"
```

The Java side requires no changes when the native libraries are rebuilt — the JNI signatures are unchanged.

---

## Changelog

### 7.0

**NEW — subprocess hiding**

• `SettingsFileCheck`: Java-layer interception of `Runtime.exec`
  (all five overloads) and `ProcessBuilder.start()`. Commands that
  reference a settings XML path or a watched shell-side path get a
  synthetic `Process` serving scrubbed XML. Narrow match — only
  commands with a watched path or a watched key plus a file-reading
  verb are affected. Everything else passes through.

• `libxstealth.so` grows exec-family interposers: `execve`,
  `posix_spawn`, `posix_spawnp`, `popen`, `system`. The
  `posix_spawn` hook catches `Runtime.exec` at the native layer on
  Android 10+, since `ProcessImpl` routes through it.

• Both layers fail open. If a hook throws, the real call proceeds.

**NEW — socket shim (opt-in)**

• `SocketCheck`: Java-layer shadow of `LocalSocket` connections to
  configured daemons. Off by default. Installs no hooks unless a
  daemon is listed in the config.

• Without a registered `Responder` for a daemon, its traffic is
  observed but passed through byte-for-byte. This is infrastructure
  for future per-daemon shims, not a working feature.

**NEW — native library reconstruction**

• `libxstealth.c` and `libxstealth_next.c` are now reconstructable
  from the shipped `.so`s. The reconstruction is behaviorally
  equivalent, includes the exec-family additions, and compiles with
  Termux clang without the NDK.

**CONFIG**

• `XStealthConfig` gains `hideSettingsFileReads` (default true) and
  `hideSocketDaemons` + `socketDaemons` (default false / empty).

**IMPROVED**

• `XStealthModule` wires up the new checks. Registry counts reflect
  them.

• `XStealthNative.describe()` reports which interposers are
  installed, including the new exec family.

**NOTES**

• The `Service.onCreate` and `system_server` gaps are unchanged.
  The exec-family additions close a detection path, not a timing
  path.

• `SocketCheck` is deliberately narrow. It will not help against
  Binder-based settings access (already covered by
  `ContentResolver.query` hooks) or native `connect()` from JNI.

### 6.9

• add remove module in module sheet detail instead of uninstall module.

### 6.8

• remove uninstall module and long press launch app that cause an issues.
• Fix the self hook not installed when open module app.

### 6.7

• Improve design for add module to use material 3.
• fix the issues when adding the module so it doesnt auto remove in 30-60 seconds.

### 6.6

• Remove the useless command -v that cause an issue when executing app_process.

### 6.5

• Fix some bugs.

### 6.4

• improve tabs design.

### 6.3

• Bug Fix.

### 6.2

```
IMPROVED
• Modules tab: long-press a row to open the module's own UI
  through ShizuPosed. This installs the self-hook that modules
  using the pattern rely on to detect their own activation
  state. The row previously had long-press mapped to uninstall;
  that action moves to the detail sheet.

• Module detail sheet: when "Open module app" can't route
  through ShizuPosed and falls back to a direct launch, the
  user sees a message explaining that the module UI may report
  "Disabled" or "Not Activated" and what to do about it.

• Module detail sheet: the "Open module app" button labels
  distinguish the fallback paths. Modules with a settings
  activity but no launcher entry show "(no launcher)". Modules
  where the resolver had to fall back to a non-launcher
  activity show "(fallback)".

• Modules tab: row icons use the LSPosed-style puzzle piece
  instead of the previous locker shape.

• App icon: the adaptive icon foreground now shows a bandage X
  with the Android head perched above the crossing point.

• Home tab: status card shows a green check when Activated and
  a red cross when Not Activated.

• Home tab: process list removed. The framework still collects
  the process data; it's just not shown on Home.

• Repo tab: module detail gains sub-tabs for Readme, Releases,
  and Info.

• Settings: scan interval and hook delay sliders tune framework
  timing to the device.

NOTES
• Modules that implement IXposedHookZygoteInit still cannot
  receive the initZygote callback under ShizuPosed. This is
  structural: ShizuPosed doesn't fork from zygote, so the
  callback has no place in its lifecycle.

• Module UIs that check their own activation state by hooking
  a method on themselves work when the UI is launched through
  ShizuPosed. When launched from the launcher, the hook is not
  installed and the UI reports the original state.

• No changes to the hook backends, the dispatcher chain, or
  the module format.
```

### 6.0

```
IMPROVED
• Home tab redesigned in the LSPosed style. Status card,
  counter tiles, aligned Framework Info rows. Process list
  removed from Home.

• Modules tab redesigned. Enable switch below the module name,
  scope summary line, no more "Select Apps" button. Row body
  opens the scope editor.

• Search in Modules and Logs filters live as you type, with a
  clear button and an empty-result state. Keyboard dismisses
  on scroll.

• Repo tab: module detail gains sub-tabs for Readme, Releases,
  and Info.

• Settings: scan interval (2–30 s) and hook delay (0–2000 ms)
  sliders.

• New app icon: X bandage with Android head.

• RuntimePrefs for framework timing tunables.
```

### 5.9

```
NEW
• API version 96.
• IXUnhook interface and XposedBridgeUnhook implementation.
• XC_MethodHook.Unhook nested class. hookAll* returns Set.
• MethodHookParam.isReturnEarly().
• DexLoadingBridge: three-strategy fallback ladder.
```

### 5.7

```
NEW
• Deeper settings hooks in DevOptionsCheck and AdbCheck.
• Expanded package and process checks.
• ApiProtectionCheck expansion and fingerprint baselines.
• Icon cache.
```

### 5.6

```
NEW
• XStealth scope.
```

### 5.4

```
NEW
• Self-hook module support.
```

### 5.3

```
NEW
• Marker mirror.
• No-arg overloads.
• Android 11+ package visibility diagnostic.
• Cross-process shell base persistence.
```

### Earlier

See the release notes on the Releases page.

---

## Known limitations

These are structural, not bugs to be fixed.

**Reach.** `system_server` isn't hooked. `Service.onCreate` has no fallback. XML-level resource replacement isn't supported. No hot-reload. ROMs that block `app_process` can't run the framework. Modules that require zygote-wide timing won't work. Hooks only reach processes ShizuPosed launches.

**Zygote-timing APIs.** Modules that implement `IXposedHookZygoteInit` or `IXposedHookCmdInit` expect callbacks that only fire under a zygote-based framework. ShizuPosed declares those interfaces for link compatibility but cannot call them. Modules that depend on `initZygote` — for example, to capture `StartupParam.modulePath` — will see the values it would set remain null. The module may load and hook methods, but resource injection or any other setup that depends on zygote-time state will fail.

**Activation reporting in module UIs.** Module UIs that check their own activation state by hooking one of their own methods report the original value unless their UI is launched through ShizuPosed. Long-press a module row or tap "Open module app" to launch through ShizuPosed. Modules that query the status provider via `XposedBridge.isModuleActive()` or `LSPosedManager.isModuleActive()` report correctly regardless of how they were launched.

**`unhook()` on most backends is a no-op.** Pine, Amiru, Native, and Instrumentation don't expose a reverse. CallSite, Proxy, and Noop do.

**XStealth's practical ceiling.** XStealth covers the common detection paths for Developer Options, ADB, package presence, running processes, `/proc` reads, `Runtime.exec` subprocess reads, and reflection walks. It doesn't cover hardware attestation, native reads of the settings database that bypass the exec family entirely, method-list reconstruction, exotic reflection, kernel-level watchers, or signals unrelated to ShizuPosed.

A banking app that shows a warning may be failing on any of these, not just the ones XStealth hides.

**Subprocess hiding has a load-order window.** The exec-family interposers in `libxstealth.so` only catch subprocess spawns that happen after the library is loaded. Spawns during the app's earliest init, before `XStealthModule.handleLoadPackage()` runs, may slip through. In practice bootstrap mode installs hooks before `Application.onCreate`, so this window is small, but it exists.

**Socket shim is inert by default.** `SocketCheck` installs no hooks unless a daemon is configured and a `Responder` is registered for it. Without both, it's a no-op. This is deliberate — the alternative is breaking every app that uses `LocalSocket`, which is most of them.

**Android 11+.** Module UIs that want to report Activated via the status provider must declare ShizuPosed's provider authority in their own `<queries>` block.

**Amiru-specific:** object arguments arrive as `null`, `thisObject` arrives as `null`, after-hooks aren't dispatched, JIT-inlined callers aren't invalidated, constructors aren't hookable, ARM64 only.

**Native engines:** in-process only, object arguments and `thisObject` arrive as `null`, after-hooks aren't dispatched, ARM64 only.

**Native reconstruction caveat.** The reconstructed `libxstealth_next.c` handlers call the resolved real symbol directly rather than executing the saved stub prologue as a trampoline. This is behaviorally equivalent for path hiding, but it means the reconstruction does not chain through any pre-existing patches on the same stub. If another framework is also patching those stubs, behavior may differ from the original `.so`.

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