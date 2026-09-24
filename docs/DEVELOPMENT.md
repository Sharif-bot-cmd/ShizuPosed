# ShizuPosed Development Guide

This document is for contributors and module authors who need to understand the
implementation beyond the user-facing README.

## Repository map

```text
app/src/main/java/com/shizuposed/manager/
├── MainActivity.java              Activity shell and navigation
├── ShizukuHelper.java             Shizuku binder, shell, and app_process access
├── core/                          Bootstrap, module loading, and hook dispatch
│   ├── XposedHook.java            Shell-side target bootstrap entry point
│   ├── ModuleLoader.java           Module metadata and payload loading
│   ├── ModuleScanner.java          APK scanning and module discovery
│   ├── HookEngine.java             Backend availability and selection
│   ├── HookDispatcher.java         Per-method backend chain
│   ├── AmiruDispatcher.java        Amiru callback routing
│   ├── NativeBridge.java           JNI declarations and native bridge
│   └── backends/                   Pine, Amiru, native, proxy, and fallback backends
├── service/ShizuPosedService.java  Foreground service and launch orchestration
├── status/ModuleStatusProvider.java Read-only module activation provider
├── ui/                             Home, modules, logs, repo, and settings screens
└── utils/                          Logging, files, shell, and launcher helpers
```

Native libraries are packaged from `app/src/main/jniLibs/arm64-v8a/`:

- `libshizuposed.so`: native fallback and symbol-hooking engine.
- `libamiru.so`: ARM64 per-method stub engine.

The repository also contains local dependency JARs in `app/libs/`. They are
intentionally committed because this project builds against Shizuku and Pine
artifacts without a remote dependency for every local API surface.

## Runtime architecture

The manager does not inject into an already-running target process. The normal
path is:

```text
Manager UI
  -> Shizuku AIDL
  -> shell-side ShizuPosedService
  -> /system/bin/app_process*
  -> XposedHook.main(target package, target uid)
  -> module loading and hook installation
  -> target Application/Activity startup
```

`app_process` is the critical boundary. If a ROM does not expose a usable
`app_process64`, `app_process`, or `app_process32`, the Home screen reports that
scoped launch is unavailable and no hook can be installed through this path.

The manager can inspect whether the binary exists, but inspection alone does
not hook an app. The target must be launched through ShizuPosed so the bootstrap
runs before normal application startup.

## Module lifecycle

1. `ModuleScanner` reads installed module APK metadata and `assets/xposed_init`.
2. `ModuleLoader` stores module descriptors and the selected application scope.
3. The service copies module payloads and native libraries into the shell-side
   working directory when needed.
4. `XposedHook` receives the target package and UID from the service.
5. It checks enabled modules and scope, creates a target class loader, and
   invokes each Xposed entry point.
6. Successful module loads are written to shell-side marker files.
7. `ModuleStatusProvider` reads those markers so a module UI can query enabled
   and active state from its own process.

Activation is therefore a runtime fact, not a mirror of the enabled toggle. A
module can be enabled while inactive until a scoped target has been launched.

## Hook dispatcher

`HookDispatcher` tries backends in order. A backend should return failure when
it cannot safely handle a method so the next backend can try:

1. `PineBackend` for normal hooks.
2. `PineReplaceBackend` for replacement callbacks.
3. `AmiruBackend` for supported primitive static methods.
4. `NativeBackend` for the native fallback engine.
5. `InstrumentationBackend` for supported lifecycle methods.
6. `ProxyBackend` for interface methods.
7. `NoopBackend` as a deliberate final fallback.

The `NoopBackend` prevents one unsupported method from aborting an entire module,
but it also means successful module loading does not guarantee every requested
hook was installed. Logs should identify backend selection when debugging.

## Native engines

### Amiru

Amiru probes ART at runtime by resolving calibration methods and locating their
executable entry-point fields. It then creates a unique ARM64 stub for each
accepted method. The current implementation is intentionally conservative:
primitive static arguments and primitive return replacement are supported;
object arguments, instance receivers, constructors, after-hooks, and non-ARM64
ABIs are rejected so dispatch can fall through safely.

### Native fallback

`libshizuposed.so` uses a shared native dispatcher and runtime layout probing.
It supports primitive marshaling and selected native symbol hooks. It does not
promise object arguments, instance receivers, after-hooks, or non-ARM64 support.

Do not add hard-coded ART offsets without a device-specific validation strategy.
A failed layout probe should degrade to the next backend rather than patching
unknown memory.

## Status provider contract

The exported authority is:

```text
content://com.shizuposed.manager.status
```

Module UIs can query their own status through the compatibility API or directly
through the provider. Android 11+ module manifests should declare:

```xml
<queries>
    <provider android:authorities="com.shizuposed.manager.status" />
</queries>
```

The provider is read-only. It exposes module enabled state, active state, scope,
and framework information. It does not expose module private data.

## Build internals

The project uses Android Gradle Plugin 9.4.0, Gradle 9.7, Java 21, and Android
platform `android-37.0`. `app/build.gradle` registers `makeDex`, which:

1. Compiles the debug Java classes.
2. Selects framework/core classes and Pine classes.
3. Resolves the Android platform JAR and compile classpath.
4. Runs D8 to create `XposedHook.dex`.
5. Adds that output to the APK assets.

The build also validates that `libshizuposed.so` is present. `libamiru.so` is
optional at build time and produces a warning if absent.

A local release can be signed with `keystore.properties`, but never commit that
file or its keystore. CI reconstructs it from protected GitHub secrets. See the
main README for the complete signing setup.

## UI conventions

- Use Material 3 semantic attributes such as `colorSurface`, `colorOnSurface`,
  and `colorPrimaryContainer`; avoid platform `holo_*` colors.
- Keep light and dark resources paired in `values/` and `values-night/`.
- Apply window insets at the activity root so toolbar and bottom navigation do
  not overlap system bars.
- Keep module scope editing and scoped launch actions connected to the existing
  `ModulesFragment` flow.
- Log operational failures with enough package/binary context to diagnose a
  device-specific issue without logging credentials.

## CI and release workflow

`.github/workflows/release.yml` builds debug and release APKs on `v*` tags or a
manual dispatch. It provisions Java 21, Android platform `android-37.0`, build
tools 35.0.0, and Gradle 9.7. The release job decodes the protected keystore
only into the runner's temporary directory, creates `keystore.properties`, and
publishes normalized APK names as GitHub Release assets.

Required secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never print secret values in workflow logs.

## Debugging checklist

1. Check Home for Shizuku authorization and app launch capability.
2. Confirm the target is in the enabled module's scope.
3. Launch the target from the module detail action, not from the normal launcher.
4. Inspect Logs for the selected `app_process` binary and backend decisions.
5. Check shell-side marker files when a module reports enabled but inactive.
6. Verify the target device is ARM64 and the native libraries are present.

## Contribution checklist

Before committing a change:

- Run the narrowest relevant Gradle task or diagnostics available.
- Check both light and dark resource variants for UI changes.
- Keep generated `.gradle/`, keystores, and local secret files untracked.
- Use a Conventional Commit message.
- Update the main README only when user-facing behavior or setup changes.
