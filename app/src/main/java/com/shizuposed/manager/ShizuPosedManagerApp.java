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

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public class ShizuPosedManagerApp extends Application {
    private static ShizuPosedManagerApp instance;
    private static final String CHANNEL_ID = "shizuposed_channel";
    private static final String CHANNEL_NAME = "ShizuPosed Manager";

    public static final String ACTION_SHIZUKU_AUTHORIZED =
        "com.shizuposed.manager.ACTION_SHIZUKU_AUTHORIZED";

    private Logger logger;
    private boolean isShizukuAvailable = false;
    private boolean isShizukuAuthorized = false;
    private int shizukuVersion = 0;
    private boolean serviceAutoStarted = false;
    private boolean permissionRequested = false;

    private ShizuPosedService shizuPosedService;

    // ✅ Prevents firing the "service is authorized" notification more than once per grant
    private final AtomicBoolean notifiedAuthorized = new AtomicBoolean(false);

    // ─── Shizuku listeners ─────────────────────────────────────
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
            notifiedAuthorized.set(false);   // ✅ allow a fresh grant to notify again
            logger.w("Shizuku binder dead");
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != 1001 && requestCode != 0xCA07A) return;

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                isShizukuAuthorized = true;
                logger.i("✅ Shizuku permission GRANTED by user!");
                autoStartService();
                notifyServiceShizukuAuthorized();
                if (instance != null && instance.getMainActivity() != null) {
                    instance.getMainActivity().onShizukuPermissionGranted();
                }
            } else {
                isShizukuAuthorized = false;
                notifiedAuthorized.set(false);
                logger.w("❌ Shizuku permission DENIED by user");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        logger = Logger.getInstance(this);
        logger.i("ShizuPosed Manager starting...");
        logger.i("Using Shizuku API: rikka.shizuku (v13+)");

        createDirectories();
        setupNotificationChannel();
        initShizuku();

        try {
            ShizukuHelper.getInstance(this).addPermissionListener(
                new ShizukuHelper.PermissionListener() {
                    @Override
                    public void onPermissionGranted() {
                        logger.i("ShizukuHelper reports permission granted");
                        isShizukuAuthorized = true;
                        autoStartService();
                        notifyServiceShizukuAuthorized();
                    }
                    @Override
                    public void onPermissionDenied() {
                        isShizukuAuthorized = false;
                        notifiedAuthorized.set(false);
                        logger.w("ShizukuHelper reports permission denied");
                    }
                });
        } catch (Exception e) {
            logger.e("Failed to register ShizukuHelper permission listener: " + e.getMessage());
        }

        logger.i("ShizuPosed Manager initialized");
    }

    public void setShizuPosedService(ShizuPosedService service) {
        this.shizuPosedService = service;
        if (service != null && isShizukuAuthorized) {
            logger.i("Service bound while Shizuku already authorized - deploying now");
            service.onShizukuAuthorized();
            notifiedAuthorized.set(true);
        }
    }

    public ShizuPosedService getShizuPosedService() {
        return shizuPosedService;
    }

    /**
     * Tells the service that Shizuku is authorized, once per grant.
     */
    private void notifyServiceShizukuAuthorized() {
        // ✅ Single-fire guard
        if (!notifiedAuthorized.compareAndSet(false, true)) {
            logger.d("Already notified service about authorization — skipping");
            return;
        }

        ShizuPosedService svc = shizuPosedService;
        if (svc != null) {
            logger.i("Notifying ShizuPosedService (direct) that Shizuku is authorized");
            svc.onShizukuAuthorized();
            return;
        }

        try {
            svc = com.shizuposed.manager.core.ProcessMonitor
                .getInstance(this).getShizuPosedService();
        } catch (Throwable ignored) {}

        if (svc != null) {
            logger.i("Notifying ShizuPosedService (via ProcessMonitor) that Shizuku is authorized");
            svc.onShizukuAuthorized();
            return;
        }

        logger.i("Sending ACTION_SHIZUKU_AUTHORIZED intent to ShizuPosedService");
        Intent i = new Intent(this, ShizuPosedService.class);
        i.setAction(ACTION_SHIZUKU_AUTHORIZED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            logger.e("Failed to send ACTION_SHIZUKU_AUTHORIZED: " + e.getMessage());
            // ✅ Allow another attempt since we failed
            notifiedAuthorized.set(false);
        }
    }

    private void initShizuku() {
        try {
            boolean shizukuManagerInstalled = isShizukuManagerInstalled();
            if (!shizukuManagerInstalled) {
                logger.w("Shizuku Manager not installed");
                logger.i("Please install Shizuku from: https://github.com/RikkaApps/Shizuku");
                return;
            }
            logger.i("Shizuku Manager installed (moe.shizuku.manager)");

            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);

            if (Shizuku.pingBinder()) {
                isShizukuAvailable = true;
                shizukuVersion = Shizuku.getVersion();
                checkShizukuPermission();
                logger.i("Shizuku v" + shizukuVersion + " available");
            } else {
                isShizukuAvailable = false;
                logger.w("Shizuku is not running");
            }
        } catch (Exception e) {
            logger.e("Shizuku init failed: " + e.getMessage());
            isShizukuAvailable = false;
        }
    }

    private boolean isShizukuManagerInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.manager", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void checkShizukuPermission() {
        if (!isShizukuAvailable) return;
        try {
            int result = Shizuku.checkSelfPermission();
            if (result == PackageManager.PERMISSION_GRANTED) {
                isShizukuAuthorized = true;
                logger.i("✅ Shizuku permission GRANTED");
                autoStartService();
                notifyServiceShizukuAuthorized();
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
            // ✅ Delegate to ShizukuHelper so the single-flight guard applies
            ShizukuHelper.getInstance(this).requestPermission();
        } catch (Exception e) {
            logger.e("Failed to request Shizuku permission: " + e.getMessage());
        }
    }

    public void autoStartService() {
        if (serviceAutoStarted) {
            logger.i("Service already auto-started");
            return;
        }

        boolean authorized = isShizukuAuthorized
            || ShizukuHelper.getInstance(this).isAuthorized();

        if (!authorized) {
            logger.w("Cannot auto-start: Shizuku not authorized");
            return;
        }

        try {
            logger.i("🚀 Auto-starting ShizuPosedService...");
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            serviceAutoStarted = true;
            logger.i("✅ Service auto-started successfully");

            if (mainActivity != null) mainActivity.onServiceAutoStarted();
        } catch (Exception e) {
            logger.e("Failed to auto-start service: " + e.getMessage());
            serviceAutoStarted = false;
        }
    }

    public void startServiceManually() {
        try {
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            serviceAutoStarted = true;
            logger.i("Service started manually");
        } catch (Exception e) {
            logger.e("Failed to start service: " + e.getMessage());
        }
    }

    public boolean isShizukuAvailable() { return isShizukuAvailable; }
    public boolean isShizukuAuthorized() {
        return isShizukuAuthorized
            || ShizukuHelper.getInstance(this).isAuthorized();
    }
    public int getShizukuVersion() { return shizukuVersion; }
    public boolean isServiceAutoStarted() { return serviceAutoStarted; }

    private void createDirectories() {
        File baseDir = new File(getFilesDir(), ".syscall_cache");
        if (!baseDir.exists()) baseDir.mkdirs();
        File modulesDir = new File(baseDir, "modules");
        if (!modulesDir.exists()) modulesDir.mkdirs();
        File logsDir = new File(baseDir, "logs");
        if (!logsDir.exists()) logsDir.mkdirs();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ShizuPosed Manager service notifications");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    public static ShizuPosedManagerApp getInstance() { return instance; }
    public Logger getLogger() { return logger; }

    private MainActivity mainActivity;
    public void setMainActivity(MainActivity activity) { this.mainActivity = activity; }
    public MainActivity getMainActivity() { return mainActivity; }
}