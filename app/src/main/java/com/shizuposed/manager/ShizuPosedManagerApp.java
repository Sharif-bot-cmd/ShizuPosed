package com.shizuposed.manager;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import rikka.shizuku.Shizuku;

import java.io.File;

public class ShizuPosedManagerApp extends Application {
    private static ShizuPosedManagerApp instance;
    private static final String CHANNEL_ID = "shizuposed_channel";
    private static final String CHANNEL_NAME = "ShizuPosed Manager";
    
    private Logger logger;
    private boolean isShizukuAvailable = false;
    private boolean isShizukuAuthorized = false;
    private int shizukuVersion = 0;
    private boolean serviceAutoStarted = false;
    private boolean permissionRequested = false;
    
    // Shizuku listeners
    private final Shizuku.OnBinderReceivedListener binderListener = 
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            isShizukuAvailable = true;
            checkShizukuPermission();
            logger.i("Shizuku binder received");
        }
    };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            isShizukuAvailable = false;
            isShizukuAuthorized = false;
            serviceAutoStarted = false;
            logger.w("Shizuku binder dead");
        }
    };
    
    // Permission result listener
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode == 1001 || requestCode == 0xCA07A) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    isShizukuAuthorized = true;
                    logger.i("✅ Shizuku permission GRANTED by user!");
                    autoStartService();
                    if (instance != null && instance.getMainActivity() != null) {
                        instance.getMainActivity().onShizukuPermissionGranted();
                    }
                } else {
                    isShizukuAuthorized = false;
                    logger.w("❌ Shizuku permission DENIED by user");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        logger = Logger.getInstance(this);
        logger.i("ShizuPosed Manager v1.5 starting...");
        logger.i("Using Shizuku API: rikka.shizuku (v13+)");
        
        createDirectories();
        setupNotificationChannel();
        initShizuku();
        
        logger.i("ShizuPosed Manager initialized");
    }

    private void initShizuku() {
        try {
            // ✅ ONLY check for Shizuku Manager - NO Shevery!
            boolean shizukuManagerInstalled = isShizukuManagerInstalled();
            
            if (!shizukuManagerInstalled) {
                logger.w("Shizuku Manager not installed");
                logger.i("Please install Shizuku from: https://github.com/RikkaApps/Shizuku");
                return;
            }
            
            logger.i("Shizuku Manager installed (moe.shizuku.manager)");
            
            // Register listeners
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            
            // Check if Shizuku is running
            if (Shizuku.pingBinder()) {
                isShizukuAvailable = true;
                shizukuVersion = Shizuku.getVersion();
                checkShizukuPermission();
                logger.i("Shizuku v" + shizukuVersion + " available");
            } else {
                isShizukuAvailable = false;
                logger.w("Shizuku is not running");
                // ❌ REMOVED: Auto-launch Shizuku/Shevery
                // User must start Shizuku manually
            }
            
        } catch (Exception e) {
            logger.e("Shizuku init failed: " + e.getMessage());
            isShizukuAvailable = false;
        }
    }

    private boolean isShizukuManagerInstalled() {
        try {
            PackageManager pm = getPackageManager();
            pm.getPackageInfo("moe.shizuku.manager", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void checkShizukuPermission() {
        if (!isShizukuAvailable) return;
        
        try {
            int result = Shizuku.checkSelfPermission();
            if (result == PackageManager.PERMISSION_GRANTED) {
                isShizukuAuthorized = true;
                logger.i("✅ Shizuku permission GRANTED");
                autoStartService();
            } else {
                isShizukuAuthorized = false;
                logger.w("⚠️ Shizuku permission NOT GRANTED");
                if (!permissionRequested) {
                    permissionRequested = true;
                    new Handler(Looper.getMainLooper()).postDelayed(
                        this::requestShizukuPermission, 500);
                }
            }
        } catch (Exception e) {
            logger.e("Failed to check Shizuku permission: " + e.getMessage());
        }
    }

    public void requestShizukuPermission() {
        if (!isShizukuAvailable) {
            logger.w("Cannot request permission: Shizuku not available");
            return;
        }
        
        if (isShizukuAuthorized) {
            logger.i("Shizuku already authorized");
            return;
        }
        
        try {
            logger.i("📢 Requesting Shizuku permission...");
            Shizuku.requestPermission(0xCA07A);
            logger.i("Shizuku permission requested - Check Shizuku app");
            
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(instance, 
                    "Please grant permission in Shizuku app", 
                    Toast.LENGTH_LONG).show();
            });
            
        } catch (Exception e) {
            logger.e("Failed to request Shizuku permission: " + e.getMessage());
        }
    }

    /**
     * Auto-start service when Shizuku permission is granted
     */
    public void autoStartService() {
        if (serviceAutoStarted) {
            logger.i("Service already auto-started");
            return;
        }
        
        if (!isShizukuAuthorized) {
            logger.w("Cannot auto-start: Shizuku not authorized");
            return;
        }
        
        try {
            logger.i("🚀 Auto-starting ShizuPosedService...");
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            startService(serviceIntent);
            serviceAutoStarted = true;
            logger.i("✅ Service auto-started successfully");
            
            if (mainActivity != null) {
                mainActivity.onServiceAutoStarted();
            }
            
        } catch (Exception e) {
            logger.e("Failed to auto-start service: " + e.getMessage());
            serviceAutoStarted = false;
        }
    }

    public void startServiceManually() {
        try {
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            startService(serviceIntent);
            serviceAutoStarted = true;
            logger.i("Service started manually");
        } catch (Exception e) {
            logger.e("Failed to start service: " + e.getMessage());
        }
    }

    public boolean isShizukuAvailable() {
        return isShizukuAvailable;
    }

    public boolean isShizukuAuthorized() {
        return isShizukuAuthorized;
    }

    public int getShizukuVersion() {
        return shizukuVersion;
    }

    public boolean isServiceAutoStarted() {
        return serviceAutoStarted;
    }

    private void createDirectories() {
        File baseDir = new File(getFilesDir(), ".syscall_cache");
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }
        
        File modulesDir = new File(baseDir, "modules");
        if (!modulesDir.exists()) {
            modulesDir.mkdirs();
        }
        
        File logsDir = new File(baseDir, "logs");
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ShizuPosed Manager service notifications");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static ShizuPosedManagerApp getInstance() {
        return instance;
    }

    public Logger getLogger() {
        return logger;
    }

    private MainActivity mainActivity;

    public void setMainActivity(MainActivity activity) {
        this.mainActivity = activity;
    }

    public MainActivity getMainActivity() {
        return mainActivity;
    }
}
