package com.shizuposed.manager;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

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
    private volatile boolean isShizukuAvailable = false;
    private volatile boolean isShizukuAuthorized = false;
    private int shizukuVersion = 0;
    private volatile boolean serviceAutoStarted = false;
    private boolean permissionRequested = false;

    private ShizuPosedService shizuPosedService;

    // Prevents firing the "service is authorized" notification more than once per grant
    private final AtomicBoolean notifiedAuthorized = new AtomicBoolean(false);

    // Ensures our own Shizuku listeners are registered at most once
    private final AtomicBoolean listenersRegistered = new AtomicBoolean(false);

    // Re-entrancy guard for autoStartService (posted callbacks could
    // otherwise loop through binderReceived -> checkPermission -> autoStart)
    private final AtomicBoolean inAutoStart = new AtomicBoolean(false);

    // ─── Shizuku listeners ─────────────────────────────────────
    private final Shizuku.OnBinderReceivedListener binderListener =
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            isShizukuAvailable = true;
            logger.i("Shizuku binder received");
            // Post so we never run on a binder callback stack
            new Handler(Looper.getMainLooper()).post(ShizuPosedManagerApp.this::checkShizukuPermission);
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
        new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            isShizukuAvailable = false;
            isShizukuAuthorized = false;
            serviceAutoStarted = false;
            notifiedAuthorized.set(false);
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
                new Handler(Looper.getMainLooper()).post(() -> {
                    autoStartService();
                    notifyServiceShizukuAuthorized();
                });
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

        // Defer all Shizuku-related setup so nothing can fire a binder
        // callback on this thread before onCreate finishes.
        new Handler(Looper.getMainLooper()).post(this::deferredShizukuSetup);

        logger.i("ShizuPosed Manager initialized");
    }

    /**
     * Runs on the main looper AFTER onCreate has returned. From here we:
     *   • Register our own Shizuku listeners
     *   • Register a PermissionListener on ShizukuHelper
     *   • Kick off an initial permission check
     *
     * Because this runs on the main looper, getInstance() calls always
     * return the fully constructed singleton.
     */
    private void deferredShizukuSetup() {
        try {
            // 1. Register our own Shizuku listeners
            registerShizukuListeners();

            // 2. Register a permission listener on the helper
            try {
                ShizukuHelper.getInstance(this).addPermissionListener(
                    new ShizukuHelper.PermissionListener() {
                        @Override
                        public void onPermissionGranted() {
                            logger.i("ShizukuHelper reports permission granted");
                            isShizukuAuthorized = true;
                            new Handler(Looper.getMainLooper()).post(() -> {
                                autoStartService();
                                notifyServiceShizukuAuthorized();
                            });
                        }
                        @Override
                        public void onPermissionDenied() {
                            isShizukuAuthorized = false;
                            notifiedAuthorized.set(false);
                            logger.w("ShizukuHelper reports permission denied");
                        }
                    });
            } catch (Throwable t) {
                logger.e("Failed to register ShizukuHelper permission listener: " + t.getMessage());
            }

            // 3. Read current state and possibly request permission
            checkShizukuPermission();

        } catch (Throwable t) {
            logger.e("deferredShizukuSetup failed: " + t.getMessage());
        }
    }

    private void registerShizukuListeners() {
        if (!listenersRegistered.compareAndSet(false, true)) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            logger.d("ShizuPosedManagerApp Shizuku listeners registered");
        } catch (Throwable t) {
            logger.e("Failed to register Shizuku listeners: " + t.getMessage());
            listenersRegistered.set(false);
        }
    }

    // ─── Service plumbing ──────────────────────────────────────

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
            notifiedAuthorized.set(false);
        }
    }

    // ─── Shizuku state ─────────────────────────────────────────

    private boolean isShizukuManagerInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.manager", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void checkShizukuPermission() {
        if (!isShizukuManagerInstalled()) {
            logger.w("Shizuku Manager not installed");
            logger.i("Please install Shizuku from: https://github.com/RikkaApps/Shizuku");
            return;
        }
        logger.i("Shizuku Manager installed (moe.shizuku.manager)");

        try {
            if (!Shizuku.pingBinder()) {
                isShizukuAvailable = false;
                logger.w("Shizuku is not running");
                return;
            }

            isShizukuAvailable = true;
            shizukuVersion = Shizuku.getVersion();
            logger.i("Shizuku v" + shizukuVersion + " available");

            int result = Shizuku.checkSelfPermission();
            if (result == PackageManager.PERMISSION_GRANTED) {
                boolean wasAuthorized = isShizukuAuthorized;
                isShizukuAuthorized = true;
                logger.i("✅ Shizuku permission GRANTED");
                if (!wasAuthorized) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        autoStartService();
                        notifyServiceShizukuAuthorized();
                    });
                }
            } else {
                isShizukuAuthorized = false;
                logger.w("⚠️ Shizuku permission NOT GRANTED");
                if (!permissionRequested) {
                    permissionRequested = true;
                    new Handler(Looper.getMainLooper()).postDelayed(
                        this::requestShizukuPermission, 500);
                }
            }
        } catch (Throwable t) {
            logger.e("Failed to check Shizuku permission: " + t.getMessage());
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
            ShizukuHelper.getInstance(this).requestPermission();
        } catch (Exception e) {
            logger.e("Failed to request Shizuku permission: " + e.getMessage());
        }
    }

    // ─── Service lifecycle ─────────────────────────────────────

    public void autoStartService() {
        if (serviceAutoStarted) {
            logger.d("Service already auto-started");
            return;
        }

        // Guard against re-entry on the same thread
        if (!inAutoStart.compareAndSet(false, true)) {
            return;
        }
        try {
            // ✅ Trust ONLY the local flag. Do not call getInstance() here —
            // this method may be reached through a chain that would loop.
            if (!isShizukuAuthorized) {
                logger.d("Cannot auto-start: not authorized yet");
                return;
            }

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
        } finally {
            inAutoStart.set(false);
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

    // ─── Public accessors ──────────────────────────────────────

    public boolean isShizukuAvailable() { return isShizukuAvailable; }

    /**
     * Returns true if we OR the helper thinks we're authorized.
     * This is safe to call from anywhere on the main thread now that
     * deferredShizukuSetup has run.
     */
    public boolean isShizukuAuthorized() {
        if (isShizukuAuthorized) return true;
        try {
            return ShizukuHelper.getInstance(this).isAuthorized();
        } catch (Throwable t) {
            return false;
        }
    }

    public int getShizukuVersion() { return shizukuVersion; }
    public boolean isServiceAutoStarted() { return serviceAutoStarted; }

    // ─── Misc ──────────────────────────────────────────────────

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