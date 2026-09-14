# ═════════════════════════════════════════════════════════════
# ShizuPosed ProGuard rules — v3.3
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
# Annotations, signatures, and generic info are needed by Gson,
# reflection, and JNI. Line numbers are kept so crash reports
# are readable.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations
-keepattributes MethodParameters

# Rename the source file attribute to a constant so original
# file names don't leak in stack traces. Line numbers survive,
# so `adb logcat` still shows useful frames.
-renamesourcefileattribute SourceFile

# ── Xposed API shims ────────────────────────────────────────
# Modules link against these exact class and member names. Any
# rename breaks every module that uses them.
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }

# ── Hook engine (named in Class.forName / app_process) ──────
# These class names appear as strings in bootstrap paths and
# in the app_process main-class argument. Their names and
# member signatures must survive.
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

# NativeBridge: JNI binds methods by exact class + method name
# via RegisterNatives. Renaming any of them silently breaks
# the native bridge at the first call.
-keep class com.shizuposed.manager.core.NativeBridge { *; }

# NativeDispatcher: invoked from libshizuposed.so by exact
# class name, method name, and signature.
-keep class com.shizuposed.manager.core.NativeDispatcher { *; }
-keepclassmembers class com.shizuposed.manager.core.NativeDispatcher {
    public static void dispatch(java.lang.reflect.Method,
                                de.robv.android.xposed.XC_MethodHook,
                                long[]);
}

# ── Hook backends ───────────────────────────────────────────
# Instantiated directly, but their names appear in log strings
# and in the dispatcher chain. Keeping them whole is simpler
# than risking a stripped method that the native path calls.
-keep class com.shizuposed.manager.core.backends.** { *; }
-keep class com.shizuposed.manager.core.hooks.** { *; }

# ── Compat layer ────────────────────────────────────────────
# ReflectionUnsafe, HiddenApiBypass, and AndroidCompat walk
# classes and fields by name. The class names themselves are
# sometimes referenced from strings.
-keep class com.shizuposed.manager.core.compat.** { *; }

# ── Pine ────────────────────────────────────────────────────
# Pine reflects on its own classes to find ArtMethod fields.
-keep class top.canyie.pine.** { *; }
-keep class top.canyie.pine.callback.** { *; }
-keep class top.canyie.pine.xposed.** { *; }

# ── Shizuku ─────────────────────────────────────────────────
# Manifest references, AIDL binder interfaces, and Shizuku's
# provider name all require exact names.
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class moe.shizuku.** { *; }

# ── Gson model classes ──────────────────────────────────────
# Gson reads field names via reflection. Renaming breaks JSON
# round-trips unless every field is annotated with @SerializedName.
-keep class com.shizuposed.manager.model.** { *; }
-keepclassmembers class com.shizuposed.manager.model.** {
    <fields>;
    <init>(...);
}

# Gson respects @SerializedName, so fields with that annotation
# can be safely renamed. This lets R8 obfuscate the field name
# while Gson still reads/writes the JSON key correctly.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson's TypeToken captures generic signatures that R8 would
# otherwise erase.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── AIDL-generated stubs ────────────────────────────────────
-keep class moe.shizuku.server.** { *; }
-keep class moe.shizuku.api.** { *; }
-keep class moe.shizuku.manager.** { *; }

# ── Android entrypoints ─────────────────────────────────────
# Instantiated by the framework from the manifest, by name.
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment

# ── Custom Views / ViewBinding ──────────────────────────────
# Android inflates these from XML, and ViewBinding generates
# per-layout classes with names derived from the XML file.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.shizuposed.manager.databinding.** { *; }

# ── Fragments instantiated by class name ────────────────────
# FragmentManager instantiates these from FragmentStateAdapter
# using the class name string.
-keep class com.shizuposed.manager.ui.** { *; }

# ── Adapters ────────────────────────────────────────────────
# Instantiated directly, not reflectively, but generated
# RecyclerView binding code calls methods by name.
-keep public class com.shizuposed.manager.adapter.** {
    public *;
}
-keepclassmembers class com.shizuposed.manager.adapter.** {
    <init>(...);
}

# ── Enum values ─────────────────────────────────────────────
# Enums are often walked reflectively. Cheap to keep.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable ──────────────────────────────────────────────
# Android's parceling machinery calls CREATOR by name.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ── Serializable ────────────────────────────────────────────
# Java serialization reflects on these by name.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ── Obfuscation: only where provably safe ──────────────────
# Repackaging moves every renamed class into a single package
# so scanners can't group them by package name.
-repackageclasses 'o'

# Allow R8 to widen visibility where needed for inlining.
-allowaccessmodification

# ── Obfuscate utility + helper classes ──────────────────────
# These are not reflected on, not linked against by modules,
# and not referenced from any string that survives obfuscation.
# Renaming them hides obvious names from static analysis.
-keep,allowobfuscation class com.shizuposed.manager.utils.FileUtils { *; }
-keep,allowobfuscation class com.shizuposed.manager.utils.ShellUtils { *; }
-keep,allowobfuscation class com.shizuposed.manager.receiver.BootReceiver { *; }
-keep,allowobfuscation class com.shizuposed.manager.status.ModuleStatusProvider { *; }
-keep,allowobfuscation class com.shizuposed.manager.service.ShizuPosedService { *; }

# ── Logger: intentionally NOT obfuscated ────────────────────
# The tag string "ShizuPosed" appears in every log line via
# android.util.Log. Obfuscating the Logger class name doesn't
# hide the tag, and keeping the name lets users filter logcat
# with `adb logcat -s ShizuPosed` during diagnosis.
#
# If you want to hide the tag, change the tag constant in
# Logger.java and CompatLog.java to something innocuous. But
# then users can't filter, and you lose the diagnostic property
# that makes the framework debuggable. Not recommended.
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
# Safe to keep; needed if any transitive dependency is Kotlin.
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
#   all debug output. The few KB saved are not worth the
#   diagnostic loss.
#
# -assumenosideeffects class java.io.PrintStream { ... }
#   Same problem. CompatLog's last-resort fallback uses
#   System.out.println when android.util.Log throws. If R8
#   strips println, the fallback silently dies.

# -overloadaggressively
#   Lets R8 reuse the same method name with different return
#   types in the same class. Pine reflects on XC_MethodHook
#   subclasses to find beforeHookedMethod / afterHookedMethod.
#   If R8 overload-aggressively renames a method to collide
#   with another, Pine's reflection lookup returns the wrong
#   method or throws. The failure is silent: the callback is
#   never invoked, no error is logged. The size win is small
#   (~2% DEX); the debugging cost is high.

# -optimizationpasses 5
#   The default (3 for R8 in AGP 8.x) is tuned for a balance
#   between size and reflection safety. Increasing it lets R8
#   strip methods it thinks are unused but which are only
#   reached through reflection. The compat layer in particular
#   touches methods reflectively on platform classes R8 doesn't
#   touch, but the extra passes can still cascade into
#   stripping something the reflection path needs. Not worth
#   the risk without a specific measurement.

# -keepclassmembers,allowobfuscation on the compat / backends /
# hooks packages:
#   Would hide the class names from static analysis, but the
#   names are referenced as strings in a few places (e.g.
#   Class.forName("...NativeDispatcher")). Those string
#   literals would need a mapping-file lookup at runtime to
#   translate the obfuscated name back. Not worth the runtime
#   complexity for the small stealth gain.