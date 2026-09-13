# ═════════════════════════════════════════════════════════════
# ShizuPosed ProGuard rules
#
# Two competing goals:
#   1. Keep functional names intact (module linkage, app_process
#      entrypoint, Gson field names, reflection targets).
#   2. Obfuscate everything else so anti-tamper scanners looking
#      for "shizuposed", "xposed", "hook", "pine" in class names
#      don't find obvious hits.
#
# The current rules lean heavily on "keep" because the project
# relies on reflection almost everywhere. Renaming is enabled only
# for classes that are neither linked against by modules nor
# reflected on by the framework.
# ═════════════════════════════════════════════════════════════

# ── Global attributes (needed by Gson, reflection, JNI, R8) ─
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ── Xposed API shims ────────────────────────────────────────
# Modules link against these exact class names.
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# ── Hook engine ──────────────────────────────────────────────
# XposedHook is invoked as `XposedHook.main(String[])` from
# app_process. Everything it touches via reflection must keep its
# names.
-keep class com.shizuposed.manager.core.XposedHook { *; }
-keep class com.shizuposed.manager.core.HookEngine { *; }
-keep class com.shizuposed.manager.core.HookDispatcher { *; }
-keep class com.shizuposed.manager.core.HookDispatcher$* { *; }
-keep class com.shizuposed.manager.core.XposedHookBridge { *; }
-keep class com.shizuposed.manager.core.XposedHookBridge$* { *; }
-keep class com.shizuposed.manager.core.XposedHelpersImpl { *; }

# ── Hook backends ────────────────────────────────────────────
# Registered via reflection-ish chains and invoked by name.
-keep class com.shizuposed.manager.core.backends.** { *; }
-keep class com.shizuposed.manager.core.hooks.** { *; }

# ── Compat layer ─────────────────────────────────────────────
# ReflectionUnsafe and HiddenApiBypass use reflection heavily.
-keep class com.shizuposed.manager.core.compat.** { *; }

# ── Pine ─────────────────────────────────────────────────────
# Pine uses reflection internally to find ArtMethod fields.
-keep class top.canyie.pine.** { *; }
-keep class top.canyie.pine.callback.** { *; }

# ── Shizuku ──────────────────────────────────────────────────
# Referenced by manifest and by AIDL-deserialized binder
# interfaces; names must stay.
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class moe.shizuku.** { *; }

# ── Gson model classes ──────────────────────────────────────
# Gson reads field names. Renaming breaks JSON round-trips.
-keep class com.shizuposed.manager.model.** { *; }

# ── AIDL-generated stubs ────────────────────────────────────
# moe.shizuku.server.* comes from shizuku-aidl.jar and is
# deserialized by Android's binder machinery.
-keep class moe.shizuku.server.** { *; }

# ── Application / Service / Activity / Receiver entrypoints ──
# Android instantiates these by name from the manifest.
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ── Obfuscate names of classes that anti-tamper scans for ───
# These are not linked against by modules and not reflected on
# at runtime, so renaming them is safe and hides obvious strings
# like "Logger", "FileUtils", "ShellUtils".
-allowaccessmodification
-repackageclasses 'o'

# Rename utility helpers — no reflection, no module linkage.
-keep,allowobfuscation class com.shizuposed.manager.utils.FileUtils { *; }
-keep,allowobfuscation class com.shizuposed.manager.utils.ShellUtils { *; }
-keep,allowobfuscation class com.shizuposed.manager.receiver.BootReceiver { *; }

# Note: Logger is deliberately NOT obfuscated. Its tag string
# ("ShizuPosed") appears in every log line; obfuscating the class
# name doesn't hide the tag, and keeping the name makes log
# filtering from adb possible for users who need to diagnose.
-keep class com.shizuposed.manager.utils.Logger { *; }

# ── Adapters ─────────────────────────────────────────────────
# RecyclerView adapters are instantiated directly in fragments,
# not by reflection, so obfuscation is safe. But keep the
# generated ViewBinding names for safety.
-keep class com.shizuposed.manager.adapter.** { *; }
-keep class com.shizuposed.manager.databinding.** { *; }

# ── Fragments ────────────────────────────────────────────────
# Instantiated by the FragmentManager using the class name from
# FragmentStateAdapter. Names must stay.
-keep class com.shizuposed.manager.ui.** { *; }

# ── Suppress missing-class warnings ─────────────────────────
# Some Shizuku / Sui classes only exist at runtime.
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

# ── Strip debug metadata in release ─────────────────────────
# Reduces APK size and removes some static-analysis surface.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}