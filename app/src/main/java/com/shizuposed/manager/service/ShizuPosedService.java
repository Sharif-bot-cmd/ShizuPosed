package com.shizuposed.manager.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import androidx.core.app.NotificationCompat;

import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class ShizuPosedService extends Service {
    private static final String CHANNEL_ID = "shizuposed_service";
    private static final String CHANNEL_NAME = "ShizuPosed Service";
    private static final int NOTIFICATION_ID = 1001;
    
    // ✅ CHANGED: Now using app's private storage
    private String xposedHookDexPath;  // Will be set in onCreate()
    
    private Logger logger;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private ProcessMonitor processMonitor;
    private ModuleLoader moduleLoader;
    private ShizukuHelper shizukuHelper;
    private boolean isRunning = false;
    private boolean xposedHookStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        
        logger = Logger.getInstance(this);
        logger.i("🚀 ShizuPosedService creating...");
        
        // ✅ SET the path to app's private storage
        xposedHookDexPath = new File(getFilesDir(), "XposedHook.dex").getAbsolutePath();
        logger.i("XposedHook.dex will be stored at: " + xposedHookDexPath);
        
        createNotificationChannel();
        
        workerThread = new HandlerThread("ShizuPosedWorker", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        
        initComponents();
        startForegroundService();
        startService();
        
        logger.i("✅ ShizuPosedService created");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ShizuPosed Manager service");
            channel.setShowBadge(false);
            
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundService() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShizuPosed Manager")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        
        startForeground(NOTIFICATION_ID, notification);
        logger.i("Started foreground service");
    }

    private void initComponents() {
        shizukuHelper = ShizukuHelper.getInstance(this);
        moduleLoader = ModuleLoader.getInstance(this);
        processMonitor = ProcessMonitor.getInstance(this);
        processMonitor.setShizuPosedService(this);
        
        // ✅ Copy XposedHook.dex from assets to files/ directory
        ensureXposedHookDexExists();
        
        logger.i("Components initialized");
    }

    /**
     * ✅ FIXED: Copy XposedHook.dex from assets to app's private storage
     */
    private boolean ensureXposedHookDexExists() {
        try {
            File dexFile = new File(xposedHookDexPath);
            
            // Check if already exists
            if (dexFile.exists() && dexFile.length() > 0) {
                logger.i("✅ XposedHook.dex already exists in files/: " + xposedHookDexPath);
                return true;
            }
            
            logger.i("📋 Copying XposedHook.dex from assets to " + xposedHookDexPath);
            
            // ✅ Copy from assets to files/ directory
            try (InputStream is = getAssets().open("XposedHook.dex");
                 FileOutputStream fos = new FileOutputStream(dexFile)) {
                
                byte[] buffer = new byte[8192];
                int length;
                long totalBytes = 0;
                
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                    totalBytes += length;
                }
                
                fos.flush();
                logger.i("✅ XposedHook.dex copied successfully! Size: " + totalBytes + " bytes");
                
                // Verify the file was written correctly
                if (dexFile.exists() && dexFile.length() > 0) {
                    logger.i("✅ XposedHook.dex verified at: " + xposedHookDexPath);
                    
                    // Make it executable
                    dexFile.setExecutable(true);
                    dexFile.setReadable(true, false);
                    
                    return true;
                } else {
                    logger.e("❌ XposedHook.dex verification failed - file missing or empty");
                    return false;
                }
                
            } catch (Exception e) {
                logger.e("❌ Failed to copy XposedHook.dex from assets: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
            
        } catch (Exception e) {
            logger.e("❌ ensureXposedHookDexExists error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void startService() {
        if (isRunning) return;
        isRunning = true;
        
        workerHandler.post(() -> {
            try {
                logger.i("Starting ShizuPosed service...");
                
                // Load modules
                List<ModuleInfo> modules = moduleLoader.loadModules();
                logger.i("Loaded " + modules.size() + " modules");
                
                // ✅ START XPOSEDHOOK.DEX from files/ directory
                startXposedHook();
                
                // Start process monitoring
                processMonitor.startMonitoring();
                
                logger.i("✅ ShizuPosed service started successfully");
                updateNotification("Service running");
                
            } catch (Exception e) {
                logger.e("❌ Failed to start service: " + e.getMessage());
                isRunning = false;
                updateNotification("Service error: " + e.getMessage());
            }
        });
    }

    /**
     * ✅ FIXED: Uses XposedHook.dex from files/ directory
     */
    private void startXposedHook() {
        if (xposedHookStarted) {
            logger.i("XposedHook already started");
            return;
        }
        
        // Check if Shizuku is authorized
        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ Shizuku not authorized, cannot start XposedHook");
            return;
        }
        
        // ✅ Check if XposedHook.dex exists in files/ directory
        File dexFile = new File(xposedHookDexPath);
        if (!dexFile.exists() || dexFile.length() == 0) {
            logger.e("❌ XposedHook.dex not found at: " + xposedHookDexPath);
            // Try to copy it again
            if (!ensureXposedHookDexExists()) {
                logger.e("❌ Cannot continue without XposedHook.dex");
                return;
            }
        }
        
        try {
            logger.i("🚀 Launching XposedHook.dex from: " + xposedHookDexPath);
            
            // ✅ Use the path to the copied file in files/ directory
            String cmd = String.format(
                "app_process -Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-cp %s /system/bin XposedHook &",
                Build.VERSION.SDK_INT,
                xposedHookDexPath  // ✅ Now points to files/ directory
            );
            
            logger.i("Executing: " + cmd);
            
            // Execute via Shizuku
            boolean success = shizukuHelper.executeCommand(cmd).isSuccess();
            
            if (success) {
                xposedHookStarted = true;
                logger.i("✅ XposedHook.dex launched successfully!");
                updateNotification("XposedHook running");
            } else {
                logger.e("❌ Failed to launch XposedHook.dex");
            }
            
        } catch (Exception e) {
            logger.e("❌ Failed to start XposedHook: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ✅ INJECT INTO A SPECIFIC PROCESS
     * Uses XposedHook.dex from files/ directory
     */
    public boolean injectProcess(String packageName, int pid, int uid, List<ModuleInfo> modules) {
        logger.i("📥 Injecting into " + packageName + " (PID: " + pid + ", UID: " + uid + ")");
        
        if (!isRunning) {
            logger.w("❌ Service not running, cannot inject");
            return false;
        }
        
        if (!shizukuHelper.isAuthorized()) {
            logger.w("❌ Shizuku not authorized, cannot inject");
            return false;
        }
        
        // ✅ Ensure XposedHook.dex exists in files/ directory
        File dexFile = new File(xposedHookDexPath);
        if (!dexFile.exists() || dexFile.length() == 0) {
            logger.e("❌ XposedHook.dex not found!");
            if (!ensureXposedHookDexExists()) {
                return false;
            }
        }
        
        // If XposedHook hasn't been started yet, start it now
        if (!xposedHookStarted) {
            startXposedHook();
        }
        
        // ✅ Use the path from files/ directory
        String dexPath = xposedHookDexPath;
        for (ModuleInfo module : modules) {
            if (module.enabled && module.cachedDexPath != null) {
                File moduleDex = new File(module.cachedDexPath);
                if (moduleDex.exists()) {
                    dexPath = module.cachedDexPath;
                    break;
                }
            }
        }
        
        // Launch XposedHook for this specific process
        boolean success = shizukuHelper.launchXposedHook(packageName, pid, uid, dexPath);
        
        if (success) {
            logger.i("✅ Injection successful: " + packageName);
        } else {
            logger.e("❌ Injection failed for: " + packageName);
        }
        
        return success;
    }

    private void updateNotification(String status) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShizuPosed Manager")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    public boolean isRunning() {
        return isRunning;
    }
    
    public boolean isXposedHookStarted() {
        return xposedHookStarted;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        logger.i("ShizuPosedService destroying...");
        isRunning = false;
        if (processMonitor != null) {
            processMonitor.stopMonitoring();
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }
}