package com.shizuposed.manager;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.updates.UpdateChecker;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public class ShizuPosedManagerApp extends Application {
    private static ShizuPosedManagerApp instance;
    private static final String CHANNEL_ID = "shizuposed_channel";
    private static final String CHANNEL_NAME = "ShizuPosed Manager";

    /**
     * The prefs file that carries original_package_name across a
     * cloak. ManagerMigrate packages this file, so the seed
     * survives both Hide and Unhide.
     */
    private static final String PREFS_SETTINGS = "shizuposed_settings";
    private static final String KEY_ORIGINAL_PACKAGE = "original_package_name";

    /**
     * Broadcast action used to tell the service that Shizuku is
     * authorized.
     *
     * NOT a constant. The old form used
     * BuildConfig.APPLICATION_ID, which is a compile-time
     * constant inlined into classes.dex — it stays
     * "com.shizuposed.manager" after a cloak, because the cloak
     * patches AndroidManifest.xml, not the dex. A target app
     * inspecting broadcasts would see the original name. Use
     * getPackageName() at call time instead.
     */
    public static String actionShizukuAuthorized() {
        ShizuPosedManagerApp app = getInstance();
        String pkg = app != null ? app.getPackageName() : "com.shizuposed.manager";
        return pkg + ".ACTION_SHIZUKU_AUTHORIZED";
    }

    private Logger logger;
    private volatile boolean isShizukuAvailable = false;
    private volatile boolean isShizukuAuthorized = false;
    private int shizukuVersion = 0;
    private volatile boolean serviceAutoStarted = false;
    private boolean permissionRequested = false;

    private ShizuPosedService shizuPosedService;

    private final AtomicBoolean notifiedAuthorized = new AtomicBoolean(false);
    private final AtomicBoolean listenersRegistered = new AtomicBoolean(false);
    private final AtomicBoolean inAutoStart = new AtomicBoolean(false);

    // ─── Shizuku listeners ─────────────────────────────────────
    private final Shizuku.OnBinderReceivedListener binderListener =
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            isShizukuAvailable = true;
            if (logger != null) logger.i("Shizuku binder received");
            new Handler(Looper.getMainLooper()).post(
                ShizuPosedManagerApp.this::checkShizukuPermission);
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
            if (logger != null) {
                logger.w("Shizuku binder dead — service left running, "
                    + "Shizuku-backed ops will defer until reconnected");
            }
            try {
                if (shizuPosedService != null) {
                    shizuPosedService.resetAuthorizedDispatch();
                }
            } catch (Throwable ignored) {}
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != 1001 && requestCode != 0xCA07A) return;

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                isShizukuAuthorized = true;
                if (logger != null) {
                    logger.i("✅ Shizuku permission GRANTED by user!");
                }
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
                serviceAutoStarted = false;
                if (logger != null) {
                    logger.w("❌ Shizuku permission DENIED by user — "
                        + "service left running");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        try {
            logger = Logger.getInstance(this);
        } catch (Throwable t) {
            logger = Logger.getNoOp();
        }
        if (logger != null) {
            logger.i("ShizuPosed Manager starting...");
            logger.i("Using Shizuku API: rikka.shizuku");
        }

        createDirectories();
        setupNotificationChannel();
        seedOriginalPackageName();

        try {
            String currentVersion = getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
            UpdateChecker.checkAsync(this, currentVersion);
        } catch (Throwable t) {
            if (logger != null) logger.d("Update check skipped: " + t.getMessage());
        }

        registerShizukuListeners();

        new Handler(Looper.getMainLooper()).post(this::deferredShizukuSetup);

        try {
            com.shizuposed.manager.cloak.ManagerMigrate.importIfNeeded(this);
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("ManagerMigrate.importIfNeeded failed: "
                    + t.getMessage());
            }
        }

        if (logger != null) logger.i("ShizuPosed Manager initialized");
    }

    /**
     * Record the current package name as the "original" one, so
     * Unhide knows what to restore to.
     *
     * On a fresh standard install, this is
     * "com.shizuposed.manager". On a cloaked install, the seed
     * arrives via ManagerMigrate (it was in shizuposed_settings
     * before the cloak), so this method sees it already present
     * and leaves it alone.
     *
     * If the seed is missing entirely — e.g. data was cleared
     * between the cloak and the first launch — the only safe
     * thing to do is refuse to set it, because we cannot know
     * whether we are standard or cloaked. ManagerCloakFlow's
     * Unhide path already refuses when the seed is missing; this
     * method must not invent one.
     */
    private void seedOriginalPackageName() {
        try {
            SharedPreferences prefs = getSharedPreferences(
                PREFS_SETTINGS, MODE_PRIVATE);
            if (prefs.contains(KEY_ORIGINAL_PACKAGE)) return;

            // Only seed when we are the known original. If the
            // current package already differs from the canonical
            // original, we are already cloaked and have no seed —
            // don't fabricate one.
            String current = getPackageName();
            String canonicalOriginal = "com.shizuposed.manager";
            if (!current.equals(canonicalOriginal)) {
                if (logger != null) {
                    logger.w("No original_package_name seed and current "
                        + "package is " + current + "; refusing to seed.");
                }
                return;
            }
            prefs.edit()
                .putString(KEY_ORIGINAL_PACKAGE, current)
                .apply();
            if (logger != null) {
                logger.i("Seeded original_package_name=" + current);
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("seedOriginalPackageName failed: " + t.getMessage());
            }
        }
    }

    private void promptForBatteryWhitelist() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                getSystemService(POWER_SERVICE);
            if (pm == null) return;
            String pkg = getPackageName();
            if (pm.isIgnoringBatteryOptimizations(pkg)) {
                if (logger != null) {
                    logger.i("Battery optimization already ignored");
                }
                return;
            }
            if (!shouldAskForWhitelist()) return;
            markWhitelistAsked();

            if (logger != null) {
                logger.i("Requesting battery-optimization whitelist");
            }
            Intent intent = new Intent(
                android.provider.Settings
                    .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("Battery whitelist prompt failed: " + t.getMessage());
            }
        }
    }

    private static final String PREFS_ONBOARDING = "shizuposed_onboarding";
    private static final String KEY_ASKED_BATTERY = "asked_battery";

    private boolean shouldAskForWhitelist() {
        try {
            return !getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
                .getBoolean(KEY_ASKED_BATTERY, false);
        } catch (Throwable t) {
            return true;
        }
    }

    private void markWhitelistAsked() {
        try {
            getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ASKED_BATTERY, true)
                .apply();
        } catch (Throwable ignored) {}
    }

    private void deferredShizukuSetup() {
        try {
            try {
                ShizukuHelper.getInstance(this).addPermissionListener(
                    new ShizukuHelper.PermissionListener() {
                        @Override
                        public void onPermissionGranted() {
                            if (logger != null) {
                                logger.i("ShizukuHelper: granted");
                            }
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
                            if (logger != null) {
                                logger.w("ShizukuHelper: denied");
                            }
                        }
                    });
            } catch (Throwable t) {
                if (logger != null) {
                    logger.e("Failed to register ShizukuHelper "
                        + "permission listener: " + t.getMessage());
                }
            }

            checkShizukuPermission();

            new Handler(Looper.getMainLooper()).postDelayed(
                this::promptForBatteryWhitelist, 3000L);

        } catch (Throwable t) {
            if (logger != null) {
                logger.e("deferredShizukuSetup failed: " + t.getMessage());
            }
        }
    }

    private void registerShizukuListeners() {
        if (!listenersRegistered.compareAndSet(false, true)) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            if (logger != null) {
                logger.d("ShizuPosedManagerApp Shizuku listeners registered");
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.e("Failed to register Shizuku listeners: "
                    + t.getMessage());
            }
            listenersRegistered.set(false);
        }
    }

    // ─── Service plumbing ──────────────────────────────────────

    public void setShizuPosedService(ShizuPosedService service) {
        this.shizuPosedService = service;
        if (service != null && isShizukuAuthorized
                && notifiedAuthorized.compareAndSet(false, true)) {
            if (logger != null) {
                logger.i("Service bound while Shizuku already "
                    + "authorized — deploying now");
            }
            service.onShizukuAuthorized();
        }
    }

    public ShizuPosedService getShizuPosedService() {
        return shizuPosedService;
    }

    private void notifyServiceShizukuAuthorized() {
        if (!notifiedAuthorized.compareAndSet(false, true)) {
            if (logger != null) {
                logger.d("Already notified service about "
                    + "authorization — skipping");
            }
            return;
        }

        ShizuPosedService svc = shizuPosedService;
        if (svc == null) {
            try {
                svc = com.shizuposed.manager.core.ProcessMonitor
                    .getInstance(this).getShizuPosedService();
            } catch (Throwable ignored) {}
        }
        if (svc != null) {
            if (logger != null) {
                logger.i("Notifying ShizuPosedService (direct) that "
                    + "Shizuku is authorized");
            }
            svc.onShizukuAuthorized();
            return;
        }

        boolean alreadyRunning = false;
        try {
            alreadyRunning = ShizuPosedService.isServiceRunning();
        } catch (Throwable ignored) {}

        if (logger != null) {
            logger.i("Sending ACTION_SHIZUKU_AUTHORIZED intent "
                + "(running=" + alreadyRunning + ")");
        }
        Intent i = new Intent(this, ShizuPosedService.class);
        i.setAction(actionShizukuAuthorized());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.e("Failed to send ACTION_SHIZUKU_AUTHORIZED: "
                    + e.getMessage());
            }
            notifiedAuthorized.set(false);
        }
    }

    public void onServiceDestroyed() {
        serviceAutoStarted = false;
        notifiedAuthorized.set(false);
        shizuPosedService = null;
        if (logger != null) {
            logger.d("Service destroyed — auto-start re-armed");
        }
    }

    public void resetAutoStart() {
        serviceAutoStarted = false;
        notifiedAuthorized.set(false);
        if (logger != null) logger.d("Auto-start reset");
    }

    // ─── Shizuku state ─────────────────────────────────────────

    private boolean isShizukuManagerInstalled() {
        String[] candidates = {
            "moe.shizuku.privileged.api",
            "moe.shizuku.manager",
        };
        for (String pkg : candidates) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return false;
    }

    private void checkShizukuPermission() {
        if (!isShizukuManagerInstalled()) {
            if (logger != null) {
                logger.w("Shizuku Manager not installed");
                logger.i("Install Shizuku: "
                    + "https://github.com/thedjchi/Shizuku");
            }
            return;
        }
        if (logger != null) {
            logger.i("Shizuku Manager installed");
        }

        try {
            if (!Shizuku.pingBinder()) {
                isShizukuAvailable = false;
                if (logger != null) logger.w("Shizuku is not running");
                return;
            }
            isShizukuAvailable = true;
            shizukuVersion = Shizuku.getVersion();
            if (logger != null) {
                logger.i("Shizuku v" + shizukuVersion + " available");
            }

            int result = Shizuku.checkSelfPermission();
            if (result == PackageManager.PERMISSION_GRANTED) {
                isShizukuAuthorized = true;
                if (logger != null) logger.i("✅ Shizuku permission GRANTED");
                new Handler(Looper.getMainLooper()).post(() -> {
                    autoStartService();
                    notifyServiceShizukuAuthorized();
                });
            } else {
                isShizukuAuthorized = false;
                if (logger != null) {
                    logger.w("⚠️ Shizuku permission NOT GRANTED");
                }
                if (!permissionRequested) {
                    permissionRequested = true;
                    new Handler(Looper.getMainLooper()).postDelayed(
                        this::requestShizukuPermission, 500);
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.e("Failed to check Shizuku permission: "
                    + t.getMessage());
            }
        }
    }

    public void requestShizukuPermission() {
        if (!isShizukuAvailable) {
            if (logger != null) {
                logger.w("Cannot request permission: Shizuku not available");
            }
            return;
        }
        if (isShizukuAuthorized) {
            if (logger != null) logger.i("Shizuku already authorized");
            return;
        }
        try {
            ShizukuHelper.getInstance(this).requestPermission();
        } catch (Exception e) {
            if (logger != null) {
                logger.e("Failed to request Shizuku permission: "
                    + e.getMessage());
            }
        }
    }

    // ─── Service lifecycle ─────────────────────────────────────

    public void autoStartService() {
        if (serviceAutoStarted) {
            if (logger != null) logger.d("Service already auto-started");
            return;
        }
        if (!isShizukuAuthorized) {
            if (logger != null) logger.d("Cannot auto-start: not authorized");
            return;
        }
        if (!inAutoStart.compareAndSet(false, true)) return;
        try {
            boolean alreadyRunning = false;
            try {
                alreadyRunning = ShizuPosedService.isServiceRunning();
            } catch (Throwable ignored) {}

            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (alreadyRunning) {
                serviceIntent.setAction(actionShizukuAuthorized());
                if (logger != null) {
                    logger.i("Service already running — sending "
                        + "ACTION_SHIZUKU_AUTHORIZED only");
                }
            } else {
                if (logger != null) {
                    logger.i("🚀 Auto-starting ShizuPosedService...");
                }
            }

            serviceAutoStarted = true;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                if (logger != null) {
                    logger.i("✅ Service auto-start dispatched");
                }
                if (mainActivity != null) mainActivity.onServiceAutoStarted();
            } catch (Exception e) {
                serviceAutoStarted = false;
                if (logger != null) {
                    logger.e("Failed to auto-start service: "
                        + e.getMessage());
                }
            }
        } finally {
            inAutoStart.set(false);
        }
    }

    public void startServiceManually() {
        if (!isShizukuAuthorized) {
            if (logger != null) {
                logger.w("startServiceManually refused: not authorized yet");
            }
            return;
        }
        try {
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            serviceAutoStarted = true;
            if (logger != null) logger.i("Service started manually");
        } catch (Exception e) {
            if (logger != null) {
                logger.e("Failed to start service: " + e.getMessage());
            }
        }
    }

    // ─── Public accessors ──────────────────────────────────────

    public boolean isShizukuAvailable() { return isShizukuAvailable; }

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
            NotificationManager manager =
                getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static ShizuPosedManagerApp getInstance() { return instance; }
    public Logger getLogger() { return logger; }

    private MainActivity mainActivity;
    public void setMainActivity(MainActivity activity) {
        this.mainActivity = activity;
    }
    public MainActivity getMainActivity() { return mainActivity; }
}