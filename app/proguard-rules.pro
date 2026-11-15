# ═════════════════════════════════════════════════════════════
# ShizuPosed ProGuard rules
#
# Design:
#   - Class names that are load-time linked (Xposed API, Shizuku,
#     libxposed API, Android entrypoints, JNI) are kept with full
#     member names.
#   - Class names that anti-tamper scanners grep for are renamed
#     when they are provably not reflected on.
#   - Debug metadata is trimmed (source file names) but line
#     numbers are kept so crash reports remain useful.
#   - Log calls are NOT stripped. The framework's diagnostics
#     depend on them; see the "Deliberately not used" section.
#
# Every rule here is either:
#   (a) necessary for reflection or JNI to work, or
#   (b) provably safe obfuscation of a class nothing reflects on.
# Anything that doesn't fall into one of those two buckets is
# listed as "do not add" at the bottom, with the reason.
# ═════════════════════════════════════════════════════════════

# ── Global attributes ────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations
-keepattributes MethodParameters

-renamesourcefileattribute SourceFile

# ── Xposed classic API shims ────────────────────────────────
#
# Covers IXposedHookLoadPackage, IXposedHookZygoteInit,
# IXposedHookInitPackageResources, IXposedHookCmdInit,
# IXposedHookDynamic, IXposedMod, the XC_* callback types, and
# the XposedBridge / XposedHelpers shims. Every class in this
# package is loaded by name from a module's entry class
# (implemented interface) or from the framework's own shim
# lookup. Keep the whole package.
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }

# IXposedHookCmdInit and IXposedHookDynamic are load-bearing
# for the "class-load fix" behavior in 1.4.9: a module that
# implements either interface must be able to resolve the
# interface at class load. Covered by the package-wide keep
# above, restated here for the next reader.
-keep interface de.robv.android.xposed.IXposedHookCmdInit { *; }
-keep interface de.robv.android.xposed.IXposedHookDynamic { *; }
-keep class de.robv.android.xposed.IXposedHookCmdInit$StartupParam { *; }

# ── libxposed API (vendored, io.github.libxposed.api.*) ────
#
# The vendored libxposed API 102 surface. Every class in this
# package is reachable through the driver:
#
#   • XposedInterface / XposedInterfaceWrapper — the driver
#     instantiates XposedInterfaceWrapper per module and calls
#     attachFramework(...) reflectively.
#   • XposedModule — the driver checks whether a module's entry
#     class extends this class to decide which lifecycle to
#     drive. Renaming it would break the check and every
#     libxposed module would be silently ignored.
#   • XposedModuleInterface / its nested Param types — the
#     driver calls the callbacks reflectively.
#   • HookBuilder / HookHandle / Hooker / Chain / Invoker /
#     CtorInvoker / ExceptionMode — the driver's adapter
#     implements these; module code links against them.
#   • @SinceApi and @InternalApi — R8's annotation stripping
#     would remove the runtime-visible annotations the driver
#     uses to decide which methods exist on which API level.
#     (They're CLASS retention, but the driver's own reflection
#     on the *declared* methods survives annotation stripping.
#     Keep the annotations anyway so a future driver that uses
#     `getAnnotation(SinceApi.class)` doesn't break silently.)
-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.api.error.** { *; }
-keep class io.github.libxposed.annotation.** { *; }

# XposedInterfaceWrapper.attachFramework uses reflection on
# its own fields to bind the base. Keep the fields and the
# method by name.
-keepclassmembers class io.github.libxposed.api.XposedInterfaceWrapper {
    <fields>;
    public final void attachFramework(
        io.github.libxposed.api.XposedInterface,
        java.lang.Runnable);
    public final void detach();
}

# XposedModule is the marker the driver uses to decide which
# lifecycle to drive. The class body is kept whole above; the
# concrete subclass check relies on `extends XposedModule`, so
# R8 must not inline or rename the base.
-keep class io.github.libxposed.api.XposedModule { *; }
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    <methods>;
}

# The driver's per-module driver class. It is called
# reflectively from ShizuPosedXposedInterface? No — it is
# called directly. But it in turn calls XposedInterfaceWrapper
# reflectively, so pin it.
-keep class com.shizuposed.manager.core.libxposed.** { *; }

# ── Hook engine (named in Class.forName / app_process) ──────
-keep class com.shizuposed.manager.core.XposedHook { *; }
-keep class com.shizuposed.manager.core.HookEngine { *; }
-keep class com.shizuposed.manager.core.HookDispatcher { *; }
-keep class com.shizuposed.manager.core.HookDispatcher$* { *; }
-keep class com.shizuposed.manager.core.XposedHookBridge { *; }
-keep class com.shizuposed.manager.core.XposedHookBridge$* { *; }
-keep class com.shizuposed.manager.core.XposedHelpersImpl { *; }
-keep class com.shizuposed.manager.core.ModuleLoader { *; }
-keep class com.shizuposed.manager.core.ModuleScanner { *; }
-keep class com.shizuposed.manager.core.ProcessMonitor { *; }
-keep class com.shizuposed.manager.core.ResourceHooking { *; }
-keep class com.shizuposed.manager.core.MarkerCache { *; }
-keep class com.shizuposed.manager.core.DexLoadingBridge { *; }
-keep class com.shizuposed.manager.core.DexLoadingBridge$* { *; }
-keep class com.shizuposed.manager.core.NativeBridge { *; }

-keep class com.shizuposed.manager.core.NativeDispatcher { *; }
-keepclassmembers class com.shizuposed.manager.core.NativeDispatcher {
    public static void dispatch(java.lang.reflect.Method,
                                de.robv.android.xposed.XC_MethodHook,
                                long[]);
}

# NativeBridge's JNI surface. The whole class is kept above;
# restated here so the JNI entry points (including the ones
# added in 1.4.9: amiruProbeLayout, amiruDescribeLayout,
# amiruUnhook, amiruSetResult, amiruHasResult,
# amiruHookMethodNative) survive any future selective keep.
-keepclassmembers class com.shizuposed.manager.core.NativeBridge {
    native <methods>;
}

# AmiruDispatcher is the JNI-resolved callback class for the
# Amiru backend. The class is instantiated on every hooked
# call; its static dispatch method is resolved by name from
# libamiru.c's JNI_OnLoad. Keep it whole.
-keep class com.shizuposed.manager.core.AmiruDispatcher { *; }
-keepclassmembers class com.shizuposed.manager.core.AmiruDispatcher {
    public static long dispatch(int, java.lang.Object, java.lang.Object[]);
    public static int amiruHasResult();
}

-keep class com.shizuposed.manager.updates.UpdateChecker { *; }
-keep class com.shizuposed.manager.updates.UpdateDownloader { *; }
-keep class com.shizuposed.manager.updates.UpdateInfo { *; }
-keep class com.shizuposed.manager.updates.UpdateInstallActivity { *; }
-keep class com.shizuposed.manager.updates.UpdateProgressDialog { *; }
-keep class com.shizuposed.manager.monitor.ForegroundMonitor { *; }

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Runtime timing preferences ──────────────────────────────
#
# RuntimePrefs stores scan interval and hook delay. Read from
# ProcessMonitor and ShizuPosedService. Called only from Java
# in the manager process; not reflected on, not looked up by
# name. Safe to obfuscate, but kept for log readability since
# its methods appear in the diagnostic logs ("scan interval
# applied", "hook delay configured"). The whole class is small
# and its members are named in log strings.
-keep,allowobfuscation class com.shizuposed.manager.runtime.** { *; }

# ── XStealth (built-in module, loaded by Class.forName) ─────
-keep class com.shizuposed.manager.stealth.** { *; }
-keep class com.shizuposed.manager.stealth.checks.** { *; }
-keepclassmembers class com.shizuposed.manager.stealth.** {
    public static ** *(...);
}

-keepclassmembers class com.shizuposed.manager.stealth.XStealthModule {
    public static final java.lang.String PACKAGE;
    public static final java.lang.String ENTRY;
    public static boolean isActive();
}

-keep class com.shizuposed.manager.stealth.XStealthStatusWriter { *; }

# libxstealth.so JNI surface.
-keep class com.shizuposed.manager.stealth.XStealthNative { *; }
-keepclassmembers class com.shizuposed.manager.stealth.XStealthNative {
    native <methods>;
    public static boolean load(java.lang.String);
    public static boolean isAvailable();
    public static void setActive(boolean);
    public static boolean isActive();
    public static java.lang.String describe();
}

# libxstealth_next.so JNI surface.
-keep class com.shizuposed.manager.stealth.XStealthNativeNext { *; }
-keepclassmembers class com.shizuposed.manager.stealth.XStealthNativeNext {
    native <methods>;
    public static boolean load(java.lang.String);
    public static boolean isAvailable();
    public static void setActive(boolean);
    public static boolean isActive();
    public static java.lang.String describe();
}

# libxstealth_bridge.so JNI surface (added 1.4.x).
-keep class com.shizuposed.manager.stealth.XStealthBridge { *; }
-keepclassmembers class com.shizuposed.manager.stealth.XStealthBridge {
    native <methods>;
    public static boolean load(java.lang.String);
    public static boolean isAvailable();
    public static void setActive(boolean);
    public static boolean isActive();
    public static java.lang.String describe();
    public static java.lang.String nativeDescribe();
}

# libsysku.so JNI surface (optional; see XposedHook.loadNativeLibs).
-keep class com.shizuposed.manager.stealth.SyskuNative { *; }
-keepclassmembers class com.shizuposed.manager.stealth.SyskuNative {
    native <methods>;
    public static boolean load(java.lang.String);
    public static int nativeInit(java.lang.String, boolean);
    public static boolean isAvailable();
}

# Sysku controller — state stored in shell-side JSON, read
# by the manager's Settings tab. Reflected on by Gson when
# reading state.json and heartbeat.json, so the fields and
# the class need to survive R8's renaming.
-keep class com.shizuposed.manager.runtime.SyskuController { *; }
-keepclassmembers class com.shizuposed.manager.runtime.SyskuController {
    <fields>;
    <methods>;
}

-keep class com.shizuposed.manager.stealth.checks.ApiProtectionCheck { *; }
-keepclassmembers class com.shizuposed.manager.stealth.checks.ApiProtectionCheck {
    public static void install(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
    <methods>;
}

# ── Hook backends ───────────────────────────────────────────
-keep class com.shizuposed.manager.core.backends.** { *; }
-keep class com.shizuposed.manager.core.hooks.** { *; }

# ── Compat layer ────────────────────────────────────────────
-keep class com.shizuposed.manager.core.compat.** { *; }

# ── Cloak flow (Hide / Unhide ShizuPosed app) ───────────────
#
# ManagerCloakFlow and ManagerCloak are invoked from the
# Settings fragment as direct Java calls, but ManagerCloak
# loads BouncyCastle classes reflectively via Jca* helpers,
# and ManagerCloak.selfSign's X509 certificate construction
# is byte-code sensitive (the BouncyCastle builders inspect
# parameter types via reflection). Keep the whole package.
#
# AxmlPatcher is a static utility. It is called directly
# (AxmlPatcher.replacePackageName / replaceLabel), but keeping
# it whole ensures the string-pool walker's inner classes and
# any future reflective use survive.
#
# ManagerMigrate is called from ShizuPosedManagerApp.onCreate
# as a direct Java call, but its `write` helper is reflection-
# adjacent (it opens files under the target's data dir), and
# a future caller that reflects on it shouldn't be broken by
# this ruleset.
-keep class com.shizuposed.manager.cloak.** { *; }
-keepclassmembers class com.shizuposed.manager.cloak.ManagerCloak {
    <fields>;
    <methods>;
}
-keepclassmembers class com.shizuposed.manager.cloak.ManagerMigrate {
    <fields>;
    <methods>;
}
-keepclassmembers class com.shizuposed.manager.cloak.AxmlPatcher {
    <fields>;
    <methods>;
}

# ── Pine ────────────────────────────────────────────────────
-keep class top.canyie.pine.** { *; }
-keep class top.canyie.pine.callback.** { *; }
-keep class top.canyie.pine.xposed.** { *; }

# ── Shizuku ─────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class moe.shizuku.** { *; }

# ── Gson model classes ──────────────────────────────────────
-keep class com.shizuposed.manager.model.** { *; }
-keepclassmembers class com.shizuposed.manager.model.** {
    <fields>;
    <init>(...);
}

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── AIDL-generated stubs ────────────────────────────────────
-keep class moe.shizuku.server.** { *; }
-keep class moe.shizuku.api.** { *; }
-keep class moe.shizuku.manager.** { *; }

# ── Android entrypoints ─────────────────────────────────────
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment

# ── Shizuku binder watcher ──────────────────────────────────
#
# ShizukuBinderWatcher is a no-op ContentProvider whose only
# job is to give the app a component Android creates eagerly
# and keeps alive for the process lifetime. Inside onCreate it
# registers Shizuku.addBinderReceivedListenerSticky and
# addBinderDeadListener, so the app is notified whenever
# Shizuku's server comes up or goes down. When the binder is
# received, it starts ShizuPosedService.
#
# Why this exists: Shizuku-Next's "ADB without Developer
# options" switch takes Shizuku's server down while a target
# app is in the foreground and brings it back when the app
# exits. Without this watcher, nothing tells ShizuPosed that
# the server has returned, and the service stays stopped until
# the user opens the app manually.
#
# The generic ContentProvider rule above already covers this
# class (it extends ContentProvider, and the manifest names it
# by class name). This explicit rule is here for two reasons:
#
#   1. Clarity — the generic rule doesn't say this class
#      exists, why it exists, or that it must not be renamed.
#   2. Safety against a future refactor — if the watcher ever
#      stops being a ContentProvider, the generic rule stops
#      applying and the class gets renamed. The manifest
#      reference would then fail with a ClassNotFoundException
#      at app start, and the failure would be silent until a
#      banking app happened to trigger the case the watcher
#      was written for.
#
# The class body is kept whole. It has no member worth
# shrinking, and the Shizuku listener fields must survive R8's
# single-pass analysis so the SAM interfaces they reference
# (Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener)
# are resolved at class-load time. rikka.shizuku.** is kept
# above, so those interfaces survive with their method names.
-keep class com.shizuposed.manager.watcher.ShizukuBinderWatcher { *; }

# ── Custom Views / ViewBinding ──────────────────────────────
#
# Two rules here, and both are needed.
#
# Rule A: keep the constructors of every class that extends
# android.view.View. This is the standard Android rule. It fires
# for classes that are actually present in the app's compiled
# output — the layouts, fragments, activities, and adapters.
#
# Rule B: keep the concrete class that the layout XML actually
# names, AND its abstract base. R8 does not count XML references
# as class references, so a widget declared only in a layout is
# invisible to Rule A's `extends` matching. The layout inflater
# instantiates the named class reflectively via
# Class.getConstructor(Context, AttributeSet), which fails with
# NoSuchMethodException if that exact constructor is stripped.
#
# The 5.0 crash was caused by the layout naming the *abstract*
# base NavigationBarView, which the inflater cannot instantiate
# at all. The layout now names the concrete BottomNavigationView.
# Both classes still have their constructors pinned here, because
# BottomNavigationView's constructors delegate to the base's, and
# R8 will strip an unused super constructor it cannot see a call
# site for.

# Rule A — constructors for anything extending View.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Rule B — Material widgets referenced only from layout XML.
# Constructors are pinned individually; the class body is not
# kept whole, so R8 can still shrink unused members.
-keep class com.google.android.material.appbar.MaterialToolbar {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.navigation.NavigationBarView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

-keep class com.google.android.material.bottomnavigation.BottomNavigationView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

-keep class com.google.android.material.navigation.NavigationBarMenuView { *; }
-keep class com.google.android.material.navigation.NavigationBarItemView { *; }
-keep class com.google.android.material.navigation.NavigationBarItemView$* { *; }

-keep class com.google.android.material.card.MaterialCardView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.materialswitch.MaterialSwitch {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.textview.MaterialTextView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.button.MaterialButton {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.button.MaterialButtonToggleGroup {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.chip.Chip {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.chip.ChipGroup {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.textfield.TextInputLayout {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.textfield.TextInputEditText {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.tabs.TabLayout {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.tabs.TabItem {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.slider.Slider {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep class com.google.android.material.bottomsheet.BottomSheetDialogFragment {
    public <init>();
}

# ViewBinding classes are referenced by name from generated code.
-keep class com.shizuposed.manager.databinding.** { *; }

# ── Fragments instantiated by class name ────────────────────
#
# Fragments are instantiated by the ViewPager2 adapter via
# `new HomeFragment()` in Java. R8 could rename them since the
# reference is a class literal, but the FragmentManager stores
# fragments by class name in its saved state. Keeping them
# preserves the ability to restore state after process death.
-keep class com.shizuposed.manager.ui.** { *; }

# ── Adapters ────────────────────────────────────────────────
#
# Adapters are held by fragments as fields and never loaded by
# name. Kept for consistency with the previous ruleset and
# because ViewHolder inner classes are referenced from the
# inflated layout's view IDs (via findViewById, not reflection).
-keep public class com.shizuposed.manager.adapter.** {
    public *;
}
-keepclassmembers class com.shizuposed.manager.adapter.** {
    <init>(...);
}

# ── Status icons ────────────────────────────────────────────
#
# ic_status_check and ic_status_cross are vector drawables
# referenced from the Home layout XML. Vector drawables are
# loaded by resource ID, not by class name, so they need no
# ProGuard rule. This comment is here to say so explicitly, so
# nobody adds a rule for them "just in case."

# ── Enum values ─────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable ──────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Serializable ────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Obfuscation: only where provably safe ──────────────────
-repackageclasses 'o'

-allowaccessmodification

# ── Obfuscate utility + helper classes ──────────────────────
# XStealthPrefs and XStealthRegistry are safe to obfuscate:
# they are only called from Java in the manager process, not
# reflectively, and not by string lookup.
#
# XStealthNative, XStealthNativeNext, XStealthModule, and
# ApiProtectionCheck are NOT in this list — they are kept
# above because the native binding, the Class.forName lookup,
# or Pine's reflection depend on their exact names.
-keep,allowobfuscation class com.shizuposed.manager.stealth.XStealthPrefs { *; }
-keep,allowobfuscation class com.shizuposed.manager.stealth.XStealthRegistry { *; }
-keep,allowobfuscation class com.shizuposed.manager.stealth.XStealthConfig { *; }

# DexOptimizeWrapper shells out to dex2oat and takes no callbacks.
# Safe to rename.
-keep,allowobfuscation class com.shizuposed.manager.utils.DexOptimizeWrapper { *; }

-keep,allowobfuscation class com.shizuposed.manager.utils.FileUtils { *; }
-keep,allowobfuscation class com.shizuposed.manager.utils.ShellUtils { *; }
-keep,allowobfuscation class com.shizuposed.manager.utils.MarkdownRenderer { *; }
-keep,allowobfuscation class com.shizuposed.manager.receiver.BootReceiver { *; }
-keep,allowobfuscation class com.shizuposed.manager.status.ModuleStatusProvider { *; }
-keep,allowobfuscation class com.shizuposed.manager.service.ShizuPosedService { *; }

# ── Logger: intentionally NOT obfuscated ────────────────────
-keep class com.shizuposed.manager.utils.Logger { *; }

# ── Suppress missing-class warnings ─────────────────────────
-dontwarn rikka.shizuku.**
-dontwarn rikka.sui.**
-dontwarn moe.shizuku.**
-dontwarn top.canyie.pine.**

# ── Third-party suppress ────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**

# ── Kotlin metadata (no-op for pure Java) ──────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ═════════════════════════════════════════════════════════════
# Deliberately NOT used
#
# Each of these was considered and rejected. Do not re-add
# without a specific test that proves reflection still works.
# ═════════════════════════════════════════════════════════════

# -assumenosideeffects class android.util.Log { ... }
#   R8 deletes every Log.d / Log.v call it can prove has no
#   side effects. That includes CompatLog.d() and the entire
#   debug-logging path. Users diagnosing a release build lose
#   all debug output. XStealth's activation log lines, the
#   interposed-symbol traces from libxstealth.so, the stub-
#   patch traces from libxstealth_next.so, the dex2oat
#   fallback traces from DexOptimizeWrapper, and the update
#   flow's `UpdateChecker` / `UpdateDownloader` logging would
#   all be stripped by this rule.

# -assumenosideeffects class java.io.PrintStream { ... }
#   Same problem. CompatLog's last-resort fallback uses
#   System.out.println when android.util.Log throws.

# -overloadaggressively
#   Pine reflects on XC_MethodHook subclasses to find
#   beforeHookedMethod / afterHookedMethod. If R8 overload-
#   aggressively renames a method to collide with another,
#   Pine's reflection lookup returns the wrong method or
#   throws. XStealth's checks — including ApiProtectionCheck's
#   loadClass and forName hooks — install these hooks and
#   would silently fail if the callbacks were renamed into
#   collisions.

# -optimizationpasses 5
#   The default (3 for R8 in AGP 8.x) is tuned for balance.
#   Increasing it lets R8 strip methods it thinks are unused
#   but which are only reached through reflection. The stealth
#   package's static installer methods, XStealthNative's JNI
#   surface, XStealthNativeNext's JNI surface,
#   XStealthBridge's JNI surface, SyskuNative's JNI surface,
#   ApiProtectionCheck's private hook setup methods, the
#   libxposed driver's reflective callbacks, and the update
#   flow's reflective activity launch are all
#   reflection-sensitive.

# -keepclassmembers,allowobfuscation on the stealth package:
#   Would hide the class names from static analysis, but
#   XStealthModule's class name is stored as a string in the
#   manager's synthetic module descriptor and looked up via
#   Class.forName in XposedHook. XStealthNative and
#   XStealthNativeNext have JNI surfaces bound by exact class
#   name. ApiProtectionCheck's XC_MethodHook subclasses are
#   reflected on by Pine. The string literals would need a
#   runtime mapping-file lookup to translate, which is not
#   worth the complexity for the small stealth gain.
#
#   XStealthPrefs, XStealthRegistry, XStealthConfig, and
#   DexOptimizeWrapper remain obfuscatable because nothing
#   reflects on them.

# -keep public class * extends android.view.View { *; } (without
#   a constructor filter)
#   Keeping every View member prevents R8 from shrinking any
#   View subclass, which is the opposite of what we want. The
#   filtered rule above keeps only the constructors the layout
#   inflater needs, which is the minimum required.

# -keep class com.google.android.material.** { *; }
#   This is the Material Components library's own consumer
#   rule. It keeps every Material class whole, which defeats
#   most of the point of minification. The explicit per-widget
#   constructor keeps above are the surgical version.

# -keep class com.google.android.material.slider.** { *; }
#   The Slider class is the only one from the Material slider
#   package used in any layout. A package-wide keep would pull
#   in the rest of the package unnecessarily. The explicit
#   Slider constructor keep above is sufficient.

# -keep class com.shizuposed.manager.cloak.** { *; }
#   Added in 1.4.10. The whole package is kept because
#   ManagerCloak's selfSign() calls BouncyCastle builders
#   reflectively, AxmlPatcher's string-pool walker has inner
#   anonymous classes that R8 would rename, and
#   ManagerCloakFlow's ProgressListener is implemented by an
#   anonymous inner class in the Settings fragment whose
#   callback methods are resolved through the interface — if
#   the interface were renamed to match the implementation,
#   the callback would still work, but if R8 overload-
#   aggressively collapsed the two `onMessage` / `onSuccess` /
#   `onError` methods, it could pick the wrong one.

# -keep io.github.libxposed.api.** (without the interface and
#   error sub-package rules)
#   Considered and rejected. The `io.github.libxposed.api` and
#   `io.github.libxposed.api.error` sub-packages need their
#   own keeps because XposedFrameworkError / HookFailedError
#   are caught by name in module code, and HookFailedError
#   is thrown by the driver. A single `api.**` rule would
#   also keep the annotations, but the annotations have
#   CLASS retention and would survive anyway; keeping them
#   explicitly is harmless and makes the intent clear.

# -keep class kotlinx.coroutines.** { *; }
#   The app is pure Java; kotlinx.coroutines isn't on the
#   classpath. The -dontwarn entry above is for transitive
#   dependencies of libraries, not for the app's own code.

# -keepclassmembers,allowobfuscation class io.github.libxposed.api.**
#   Would hide the libxposed class names, but the driver uses
#   the exact package name (`io.github.libxposed.api.XposedModule`)
#   to decide which lifecycle to drive. Renaming breaks the
#   check silently — every libxposed module would be treated
#   as a classic module or ignored. Keep the package whole.