package com.shizuposed.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final int SHIZUKU_CODE = 0xCA07A;
    private static ShizukuHelper instance;
    
    private Context context;
    private Logger logger;
    private boolean isAvailable = false;
    private boolean isAuthorized = false;
    private int shizukuVersion = 0;
    private boolean isSui = false;
    private boolean binderStatus = false;
    
    // ✅ ONLY Shizuku Manager - NO Shevery!
    private static final String SHIZUKU_API_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager";
    
    // Shizuku listeners
    private final Shizuku.OnBinderReceivedListener binderListener = 
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            binderStatus = true;
            isAvailable = true;
            checkPermission();
            logger.i("Shizuku binder received");
        }
    };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            binderStatus = false;
            isAvailable = false;
            isAuthorized = false;
            logger.w("Shizuku binder dead");
        }
    };
    
    private ShizukuHelper(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        initShizuku();
    }
    
    public static synchronized ShizukuHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ShizukuHelper(context);
        }
        return instance;
    }
    
    private void initShizuku() {
        try {
            // ✅ Initialize Sui first (from shizuku-api.jar)
            String packageName = context.getPackageName();
            try {
                isSui = Sui.init(packageName);
                if (isSui) {
                    logger.i("✅ Sui detected and initialized!");
                    isAvailable = true;
                    checkPermission();
                    return;
                }
            } catch (NoClassDefFoundError e) {
                logger.w("Sui not found, continuing with Shizuku only");
                isSui = false;
            } catch (Exception e) {
                logger.w("Sui init failed: " + e.getMessage());
                isSui = false;
            }
            
            // ✅ ONLY check Shizuku - NO Shevery!
            boolean shizukuApiInstalled = isShizukuApiInstalled();
            boolean shizukuManagerInstalled = isShizukuManagerInstalled();
            
            if (!shizukuApiInstalled && !shizukuManagerInstalled) {
                logger.w("Shizuku not installed");
                logger.w("Please install Shizuku from: https://github.com/RikkaApps/Shizuku");
                return;
            }
            
            if (shizukuApiInstalled) {
                logger.i("✅ Shizuku API installed (moe.shizuku.privileged.api)");
            }
            if (shizukuManagerInstalled) {
                logger.i("✅ Shizuku Manager installed (moe.shizuku.manager)");
            }
            
            // Register listeners
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            
            // Check binder status
            binderStatus = Shizuku.pingBinder();
            
            if (binderStatus && !Shizuku.isPreV11()) {
                isAvailable = true;
                shizukuVersion = Shizuku.getVersion();
                checkPermission();
                logger.i("✅ Shizuku v" + shizukuVersion + " available");
            } else {
                isAvailable = false;
                logger.w("❌ Shizuku is not active");
                logger.w("   binder: " + binderStatus + ", preV11: " + Shizuku.isPreV11());
                // ❌ REMOVED: Auto-launch Shizuku Manager
                // User must start Shizuku manually
            }
            
        } catch (NoClassDefFoundError e) {
            logger.e("Shizuku API not found: " + e.getMessage());
            isAvailable = false;
        } catch (Exception e) {
            logger.e("Shizuku init failed: " + e.getMessage());
            isAvailable = false;
        }
    }
    
    private boolean isShizukuApiInstalled() {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(SHIZUKU_API_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    private boolean isShizukuManagerInstalled() {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    public ShizukuStatus checkShizukuActive() {
        if (isSui) {
            return ShizukuStatus.ACTIVE;
        }
        
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(SHIZUKU_API_PACKAGE, 0);
            
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                return ShizukuStatus.ACTIVE;
            }
            return ShizukuStatus.NOT_ACTIVE;
        } catch (PackageManager.NameNotFoundException e) {
            try {
                PackageManager pm = context.getPackageManager();
                pm.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0);
                
                if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                    return ShizukuStatus.ACTIVE;
                }
                return ShizukuStatus.NOT_ACTIVE;
            } catch (PackageManager.NameNotFoundException e2) {
                return ShizukuStatus.NOT_INSTALLED;
            }
        }
    }
    
    private void checkPermission() {
        if (!isAvailable) return;
        
        try {
            if (isSui) {
                isAuthorized = true;
                logger.i("✅ Sui is active");
                autoStartServiceIfPossible();
                return;
            }
            
            int result = Shizuku.checkSelfPermission();
            isAuthorized = (result == PackageManager.PERMISSION_GRANTED);
            
            if (isAuthorized) {
                logger.i("✅ Shizuku permission GRANTED");
                autoStartServiceIfPossible();
            } else {
                logger.w("⚠️ Shizuku permission NOT GRANTED");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    this::requestPermission, 500);
            }
        } catch (Exception e) {
            logger.e("Failed to check permission: " + e.getMessage());
            isAuthorized = false;
        }
    }
    
    private void autoStartServiceIfPossible() {
        try {
            if (context instanceof ShizuPosedManagerApp) {
                ((ShizuPosedManagerApp) context).autoStartService();
            }
        } catch (Exception e) {
            logger.e("Failed to auto-start service: " + e.getMessage());
        }
    }
    
    public void requestPermission() {
        if (!isAvailable) {
            logger.w("Cannot request permission: Shizuku not available");
            return;
        }
        
        if (isAuthorized) {
            logger.i("Shizuku already authorized");
            return;
        }
        
        try {
            logger.i("📢 Requesting Shizuku permission...");
            
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode == SHIZUKU_CODE) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            isAuthorized = true;
                            logger.i("✅ Shizuku permission GRANTED!");
                            autoStartServiceIfPossible();
                            if (context instanceof ShizuPosedManagerApp) {
                                ShizuPosedManagerApp app = (ShizuPosedManagerApp) context;
                                if (app.getMainActivity() != null) {
                                    app.getMainActivity().onShizukuPermissionGranted();
                                }
                            }
                        } else {
                            isAuthorized = false;
                            logger.w("❌ Shizuku permission DENIED");
                        }
                    }
                }
            });
            
            Shizuku.requestPermission(SHIZUKU_CODE);
            logger.i("Shizuku permission requested");
            
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, 
                    "Please grant permission in Shizuku app", 
                    Toast.LENGTH_LONG).show();
            });
            
        } catch (Exception e) {
            logger.e("Failed to request permission: " + e.getMessage());
        }
    }
    
    public boolean isAvailable() {
        return isAvailable;
    }
    
    public boolean isAuthorized() {
        return isAuthorized;
    }
    
    public int getVersion() {
        return shizukuVersion;
    }
    
    public boolean isSui() {
        return isSui;
    }
    
    public ShellUtils.CommandResult executeCommand(String command) {
        ShellUtils.CommandResult result = new ShellUtils.CommandResult();
        
        if (!isAvailable || !isAuthorized) {
            result.stderr.add("Shizuku not available or not authorized");
            result.exitCode = -1;
            return result;
        }
        
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                result.stdout.add(line);
            }
            while ((line = errorReader.readLine()) != null) {
                result.stderr.add(line);
            }
            
            result.exitCode = process.waitFor();
            
            reader.close();
            errorReader.close();
            
            if (result.isSuccess()) {
                logger.d("Command executed via Shizuku: " + command);
            } else {
                logger.e("Command failed: " + result.getStderrString());
            }
            
        } catch (Exception e) {
            logger.e("Command execution failed: " + e.getMessage());
            result.stderr.add(e.getMessage());
            result.exitCode = -1;
        }
        
        return result;
    }
    
    public boolean launchXposedHook(String packageName, int pid, int uid, String dexPath) {
        if (!isAuthorized()) {
            logger.w("Cannot launch: Shizuku not authorized");
            return false;
        }
        
        try {
            String cmd = String.format(
                "app_process -Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-cp %s /system/bin XposedHook %s %d %d &",
                Build.VERSION.SDK_INT,
                dexPath,
                packageName,
                pid,
                uid
            );
            
            ShellUtils.CommandResult result = executeCommand(cmd);
            return result.isSuccess();
            
        } catch (Exception e) {
            logger.e("launchXposedHook error: " + e.getMessage());
            return false;
        }
    }
    
    public String getStatusString() {
        if (isSui) {
            return "✅ Sui Active";
        }
        if (!isAvailable) {
            return "❌ Shizuku Not Available";
        }
        if (!isAuthorized) {
            return "⚠️ Shizuku Available (Not Authorized)";
        }
        return "✅ Shizuku Authorized (v" + shizukuVersion + ")";
    }
    
    public void cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    public enum ShizukuStatus {
        ACTIVE,
        NOT_ACTIVE,
        NOT_INSTALLED
    }
}
