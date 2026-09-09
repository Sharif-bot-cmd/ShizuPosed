# ============================================================
# Shizuku Rules - moe.shizuku.privileged.api
# ============================================================

# Keep Shizuku API - CORRECT PACKAGE
-keep class moe.shizuku.privileged.api.** { *; }
-keep class moe.shizuku.privileged.api.Shizuku { *; }
-keep class moe.shizuku.privileged.api.ShizukuProvider { *; }
-keep class moe.shizuku.privileged.api.ShizukuBinderWrapper { *; }

# Keep Shizuku manager classes
-keep class moe.shizuku.manager.** { *; }

# Keep Shizuku server interfaces
-keep interface moe.shizuku.privileged.api.** { *; }

# Keep Shizuku callback interfaces
-keep interface moe.shizuku.privileged.api.Shizuku$OnBinderReceivedListener { *; }
-keep interface moe.shizuku.privileged.api.Shizuku$OnBinderDeadListener { *; }
-keep interface moe.shizuku.privileged.api.Shizuku$OnPermissionRevokedListener { *; }

# Keep Shizuku Provider
-keep class moe.shizuku.privileged.api.ShizukuProvider { 
    public <init>(...);
    *; 
}

# Keep Shizuku Binder Wrapper
-keep class moe.shizuku.privileged.api.ShizukuBinderWrapper {
    public <init>(...);
    *;
}