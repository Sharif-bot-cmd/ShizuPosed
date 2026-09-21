# ═════════════════════════════════════════════════════════════
# ShizuPosed ProGuard rules
#
# Design:
#   - Class names that are load-time linked (Xposed API, Shizuku,
#     Android entrypoints, JNI) are kept with full member names.
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

# ── Xposed API shims ────────────────────────────────────────
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }

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

-keep class com.shizuposed.manager.core.NativeBridge { *; }

-keep class com.shizuposed.manager.core.NativeDispatcher { *; }
-keepclassmembers class com.shizuposed.manager.core.NativeDispatcher {
    public static void dispatch(java.lang.reflect.Method,
                                de.robv.android.xposed.XC_MethodHook,
                                long[]);
}

# ── XStealth (built-in module, loaded by Class.forName) ─────
#
# The shell-side XposedHook resolves XStealthModule by its
# fully-qualified name, and the manager's ModuleLoader writes
# that same string into the synthetic module descriptor. The
# class name and its public entry method must survive R8.
-keep class com.shizuposed.manager.stealth.** { *; }
-keep class com.shizuposed.manager.stealth.checks.** { *; }
-keepclassmembers class com.shizuposed.manager.stealth.** {
    public static ** *(...);
}

# XStealthModule's ENTRY constant must match the string in the
# built-in module descriptor. Keeping the class whole covers the
# constant.
-keepclassmembers class com.shizuposed.manager.stealth.XStealthModule {
    public static final java.lang.String PACKAGE;
    public static final java.lang.String ENTRY;
    public static boolean isActive();
}

# XStealthStatusWriter is called by ShizuPosedService after
# every module push.
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

# ApiProtectionCheck hooks ClassLoader.loadClass and
# Class.forName. It is called by name from XStealthModule, and
# its XC_MethodHook subclasses are reflected on by Pine when the
# hooks fire. The class must not be renamed or its hooks will
# silently fail to install.
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

# ViewBinding classes are referenced by name from generated code.
-keep class com.shizuposed.manager.databinding.** { *; }

# ── Fragments instantiated by class name ────────────────────
-keep class com.shizuposed.manager.ui.** { *; }

# ── Adapters ────────────────────────────────────────────────
-keep public class com.shizuposed.manager.adapter.** {
    public *;
}
-keepclassmembers class com.shizuposed.manager.adapter.** {
    <init>(...);
}

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
#   patch traces from libxstealth_next.so, and the dex2oat
#   fallback traces from DexOptimizeWrapper would all be
#   stripped by this rule.

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
#   surface, XStealthNativeNext's JNI surface, and
#   ApiProtectionCheck's private hook setup methods are all
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