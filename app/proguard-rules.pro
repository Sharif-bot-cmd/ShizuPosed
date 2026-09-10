# ============================================================
# Shizuku Rules - moe.shizuku.privileged.api
# ============================================================

# Keep Shizuku API - CORRECT PACKAGE
-keep class rikka.shizuku.** { *; }
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class rikka.shizuku.ShizukuBinderWrapper { *; }

# Keep Shizuku manager classes
-keep class moe.shizuku.manager.** { *; }

# Keep Shizuku server interfaces
-keep interface moe.shizuku.server.** { *; }

# Keep Shizuku callback interfaces
-keep interface rikka.shizuku.Shizuku$OnBinderReceivedListener { *; }
-keep interface rikka.shizuku.Shizuku$OnBinderDeadListener { *; }
-keep interface rikka.shizuku.Shizuku$OnPermissionRevokedListener { *; }

# Keep Shizuku Provider
-keep class rikka.shizuku.ShizukuProvider { 
    public <init>(...);
    *; 
}

# Keep Shizuku Binder Wrapper
-keep class rikka.shizuku.ShizukuBinderWrapper {
    public <init>(...);
    *;
}