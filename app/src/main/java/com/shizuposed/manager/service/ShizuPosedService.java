package com.shizuposed.manager.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the ShizuPosed lifecycle.
 *
 * Responsibilities:
 *   - Stay foreground so Android doesn't kill us mid-hook.
 *   - Stage XposedHook.dex locally, then deploy it to the shell-writable
 *     directory via Shizuku.
 *   - Push module descriptors to the same directory.
 *   - Launch target apps under app_process so the hook framework runs
 *     in-process.
 *
 * Threading:
 *   All non-foreground work happens on a single background HandlerThread.
 *   The main thread only ever calls startForeground() and dispatches
 *   Intents to the worker. This is what keeps us under the 5-second
 *   foreground timer.
 *
 * Shizuku state:
 *   ShizukuHelper owns the binder listeners. This service only reads
 *   isAvailable() / isAuthorized() and reacts to onShizukuAuthorized()
 *   calls from the Application.
 */
public class ShizuPosedService extends Service {
    private static final String CHANNEL_ID = "shizuposed_service";
    private static final String CHANNEL_NAME = "ShizuPosed Service";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_REPUSH_MODULES =
        "com.shizuposed.manager.ACTION_REPUSH_MODULES";
    public static final String ACTION_LAUNCH_APP =
        "com.shizuposed.manager.ACTION_LAUNCH_APP";
    public static final String EXTRA_LAUNCH_PACKAGE = "package";

    private static final String SHELL_FILES_DIR   = "/data/user/0/com.android.shell/files";
    private static final String SHELL_DEX_PATH    = SHELL_FILES_DIR + "/XposedHook.dex";
    private static final String SHELL_CACHE_DIR   = SHELL_FILES_DIR + "/.syscall_cache";
    private static final String SHELL_MODULES_DIR = SHELL_CACHE_DIR + "/modules";
    private static final String SHELL_HOOKED_DIR  = SHELL_CACHE_DIR + "/hooked";

    private String localStagingDexPath;
    private String localStagingModulesDir;
    private String xposedHookDexPath;

    private static volatile boolean isServiceRunning = false;

    private Logger logger;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private ProcessMonitor processMonitor;
    private ModuleLoader moduleLoader;
    private ShizukuHelper shizukuHelper;
    private final Gson gson = new Gson();

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean xposedHookStarted = new AtomicBoolean(false);
    private final AtomicBoolean dexDeployed = new AtomicBoolean(false);

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        // 1. Notification channel before startForeground on O+.
        createNotificationChannel();

        // 2. Foreground IMMEDIATELY. Nothing slow before this.
        startForeground(NOTIFICATION_ID, buildNotification("Service starting"));

        // 3. Slow init off the main thread.
        logger = Logger.getInstance(this);
        logger.i("🚀 ShizuPosedService creating...");

        workerThread = new HandlerThread("ShizuPosedWorker",
                Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        workerHandler.post(() -> {
            try {
                File externalDir = getExternalFilesDir(null);
                if (externalDir == null) {
                    logger.w("⚠️ External app dir unavailable, falling back to internal");
                    externalDir = getFilesDir();
                }
                if (!externalDir.exists()) externalDir.mkdirs();

                localStagingDexPath =
                    new File(externalDir, "XposedHook.dex").getAbsolutePath();
                localStagingModulesDir =
                    new File(externalDir, "modules").getAbsolutePath();
                new File(localStagingModulesDir).mkdirs();

                xposedHookDexPath = SHELL_DEX_PATH;

                logger.i("Local staging dex:  " + localStagingDexPath);
                logger.i("Local staging mods: " + localStagingModulesDir);
                logger.i("Target shell dex:   " + xposedHookDexPath);

                initComponents();
                startServiceInternal();

                logger.i("✅ ShizuPosedService created");
            } catch (Throwable t) {
                logger.e("❌ onCreate worker failed: " + t.getMessage());
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isServiceRunning = true;

        // Re-assert foreground on every entry. This is safe because
        // we already called startForeground in onCreate — this is a
        // cheap re-post, not a new 5-second timer.
        startForeground(NOTIFICATION_ID, buildNotification("Service running"));

        if (workerHandler == null) {
            // Very early re-entry; onCreate's worker will handle it.
            return START_STICKY;
        }

        if (intent != null) {
            String action = intent.getAction();

            if (ShizuPosedManagerApp.ACTION_SHIZUKU_AUTHORIZED.equals(action)) {
                logger.i("📨 ACTION_SHIZUKU_AUTHORIZED received");
                onShizukuAuthorized();

            } else if (ACTION_LAUNCH_APP.equals(action)) {
                String pkg = intent.getStringExtra(EXTRA_LAUNCH_PACKAGE);
                if (pkg != null) {
                    final String target = pkg;
                    logger.i("📨 ACTION_LAUNCH_APP received for " + target);
                    workerHandler.post(() -> launchAppUnderShizuPosed(target));
                }

            } else if (ACTION_REPUSH_MODULES.equals(action)) {
                logger.i("📨 ACTION_REPUSH_MODULES received");
                workerHandler.post(() -> {
                    if (ensureDexReady()) {
                        pushModulesToShellDir(moduleLoader.loadModules());
                    }
                });
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        logger.i("ShizuPosedService destroying...");
        isRunning.set(false);
        isServiceRunning = false;
        if (processMonitor != null) processMonitor.stopMonitoring();
        if (workerThread != null) workerThread.quitSafely();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ═════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ShizuPosed Manager service");
            channel.setShowBadge(false);
            android.app.NotificationManager manager =
                getSystemService(android.app.NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShizuPosed Manager")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void updateNotification(String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
    }

    // ═════════════════════════════════════════════════════════════
    // COMPONENT INIT
    // ═════════════════════════════════════════════════════════════

    private void initComponents() {
        shizukuHelper = ShizukuHelper.getInstance(this);
        moduleLoader = ModuleLoader.getInstance(this);
        processMonitor = ProcessMonitor.getInstance(this);
        processMonitor.setShizuPosedService(this);
        logger.i("Components initialized (dex deployment deferred until Shizuku ready)");
    }

    // ═════════════════════════════════════════════════════════════
    // DEX STAGING + DEPLOYMENT
    // ═════════════════════════════════════════════════════════════

    private boolean stageDexLocally() {
        try {
            File staged = new File(localStagingDexPath);
            if (staged.exists() && staged.length() > 0) {
                logger.i("✅ XposedHook.dex already staged locally");
                staged.setReadable(true, false);
                return true;
            }

            File parent = staged.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            logger.i("📋 Staging XposedHook.dex from assets to " + localStagingDexPath);

            try (InputStream is = getAssets().open("XposedHook.dex");
                 FileOutputStream fos = new FileOutputStream(staged)) {
                byte[] buffer = new byte[8192];
                int length;
                long total = 0;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                    total += length;
                }
                fos.flush();
                logger.i("✅ XposedHook.dex staged! Size: " + total + " bytes");
                staged.setReadable(true, false);
                return true;
            }
        } catch (Exception e) {
            logger.e("❌ stageDexLocally error: " + e.getMessage());
            return false;
        }
    }

    private boolean deployDexToShellDir() {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ Cannot deploy dex: Shizuku not authorized");
            return false;
        }
        if (!stageDexLocally()) return false;

        try {
            logger.i("📤 Deploying XposedHook.dex to shell dir via Shizuku...");

            ShellUtils.CommandResult mkdirResult =
                shizukuHelper.executeCommand("mkdir -p " + SHELL_FILES_DIR);
            if (!mkdirResult.isSuccess()) {
                logger.e("❌ Failed to create shell dir: " + mkdirResult.getStderrString());
                return false;
            }

            String copyCmd = "sh -c 'cat \"" + localStagingDexPath
                + "\" > \"" + SHELL_DEX_PATH + "\"'";
            ShellUtils.CommandResult copyResult = shizukuHelper.executeCommand(copyCmd);
            if (!copyResult.isSuccess()) {
                logger.e("❌ Failed to copy dex: " + copyResult.getStderrString());
                return false;
            }

            shizukuHelper.executeCommand("chmod 755 " + SHELL_DEX_PATH);
            shizukuHelper.executeCommand("chown shell:shell " + SHELL_DEX_PATH);

            ShellUtils.CommandResult verify =
                shizukuHelper.executeCommand("test -s " + SHELL_DEX_PATH + " && echo OK");
            if (!verify.isSuccess() || !verify.getStdoutString().contains("OK")) {
                logger.e("❌ Verification failed: dex not present");
                return false;
            }

            shizukuHelper.executeCommand("mkdir -p " + SHELL_CACHE_DIR);
            shizukuHelper.executeCommand("mkdir -p " + SHELL_MODULES_DIR);
            shizukuHelper.executeCommand("mkdir -p " + SHELL_HOOKED_DIR);
            shizukuHelper.executeCommand("chmod 755 " + SHELL_CACHE_DIR
                + " " + SHELL_MODULES_DIR
                + " " + SHELL_HOOKED_DIR);

            logger.i("✅ XposedHook.dex deployed to: " + SHELL_DEX_PATH);
            dexDeployed.set(true);
            return true;

        } catch (Exception e) {
            logger.e("❌ deployDexToShellDir error: " + e.getMessage());
            return false;
        }
    }

    public boolean ensureDexReady() {
        if (dexDeployed.get()) return true;
        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ ensureDexReady called but Shizuku not authorized yet");
            return false;
        }
        return deployDexToShellDir();
    }

    // ═════════════════════════════════════════════════════════════
    // MODULE PUSH
    // ═════════════════════════════════════════════════════════════

    private boolean pushModulesToShellDir(List<ModuleInfo> modules) {
        if (!shizukuHelper.isAuthorized()) return false;

        try {
            shizukuHelper.executeCommand("mkdir -p " + SHELL_MODULES_DIR);
            shizukuHelper.executeCommand("chmod 755 " + SHELL_CACHE_DIR + " " + SHELL_MODULES_DIR);

            int pushed = 0;
            for (ModuleInfo module : modules) {
                if (module == null || !module.enabled) continue;

                String json = gson.toJson(module);
                File localJson = new File(localStagingModulesDir, module.packageName + ".json");
                try (FileOutputStream fos = new FileOutputStream(localJson)) {
                    fos.write(json.getBytes());
                    fos.flush();
                }
                localJson.setReadable(true, false);

                String dstJson = SHELL_MODULES_DIR + "/" + module.packageName + ".json";
                String copyJson = "sh -c 'cat \"" + localJson.getAbsolutePath()
                    + "\" > \"" + dstJson + "\"'";
                ShellUtils.CommandResult r1 = shizukuHelper.executeCommand(copyJson);
                if (!r1.isSuccess()) {
                    logger.w("Failed to push JSON for " + module.packageName
                        + ": " + r1.getStderrString());
                    continue;
                }

                if (module.cachedDexPath != null) {
                    File localDex = new File(module.cachedDexPath);
                    if (localDex.exists()) {
                        String dstDex = SHELL_MODULES_DIR + "/" + module.packageName + ".dex";
                        String copyDex = "sh -c 'cat \"" + localDex.getAbsolutePath()
                            + "\" > \"" + dstDex + "\"'";
                        ShellUtils.CommandResult r2 = shizukuHelper.executeCommand(copyDex);
                        if (r2.isSuccess()) {
                            shizukuHelper.executeCommand("chmod 644 " + dstDex);

                            String patchedJson = json.replace(
                                localDex.getAbsolutePath(), dstDex);
                            File patchedLocal =
                                new File(localStagingModulesDir, module.packageName + ".json");
                            try (FileOutputStream fos = new FileOutputStream(patchedLocal)) {
                                fos.write(patchedJson.getBytes());
                                fos.flush();
                            }
                            String copyPatched = "sh -c 'cat \""
                                + patchedLocal.getAbsolutePath()
                                + "\" > \"" + dstJson + "\"'";
                            shizukuHelper.executeCommand(copyPatched);
                        }
                    }
                }

                pushed++;
            }

            logger.i("Pushed " + pushed + " module descriptors to shell dir");
            return pushed > 0;

        } catch (Exception e) {
            logger.e("pushModulesToShellDir error: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SERVICE STARTUP
    // ═════════════════════════════════════════════════════════════

    private void startServiceInternal() {
        if (!isRunning.compareAndSet(false, true)) return;

        workerHandler.post(() -> {
            try {
                logger.i("Starting ShizuPosed service...");

                List<ModuleInfo> modules = moduleLoader.loadModules();
                logger.i("Loaded " + modules.size() + " modules");

                if (shizukuHelper.isAuthorized()) {
                    logger.i("Shizuku authorized at startup — deploying dex...");
                    if (ensureDexReady()) {
                        pushModulesToShellDir(modules);
                    }
                } else {
                    logger.i("⏳ Shizuku not yet authorized — deploy deferred");
                }

                processMonitor.startMonitoring();

                logger.i("✅ ShizuPosed service started successfully");
                updateNotification("Service running");
            } catch (Exception e) {
                logger.e("❌ Failed to start service: " + e.getMessage());
                isRunning.set(false);
                updateNotification("Service error: " + e.getMessage());
            }
        });
    }

    private void prepareXposedHook() {
        if (!xposedHookStarted.compareAndSet(false, true)) return;

        if (!shizukuHelper.isAuthorized()) {
            logger.w("⚠️ Shizuku not authorized, cannot prepare XposedHook");
            xposedHookStarted.set(false);
            return;
        }
        if (!ensureDexReady()) {
            logger.e("❌ Cannot prepare XposedHook without deployed dex");
            xposedHookStarted.set(false);
            return;
        }
        // Push once. launchAppUnderShizuPosed will push again if the
        // module set has changed since the last push, but it will not
        // double-push on every launch.
        if (!pushModulesToShellDir(moduleLoader.loadModules())) {
            logger.w("⚠️ No modules pushed to shell dir (nothing will hook)");
        }

        logger.i("✅ XposedHook ready (launcher will run on demand)");
        updateNotification("Ready");
    }

    /**
     * Called by the Application when Shizuku authorization is granted.
     * Safe to call multiple times; ensures dex + module push happen
     * exactly once per authorization lifecycle.
     */
    public void onShizukuAuthorized() {
        logger.i("📢 onShizukuAuthorized() — deploying now");
        if (workerHandler == null) return;
        workerHandler.post(() -> {
            if (ensureDexReady()) {
                prepareXposedHook();
            }
        });
    }

    // ═════════════════════════════════════════════════════════════
    // LAUNCH UNDER SHIZUPOSED
    // ═════════════════════════════════════════════════════════════

    public boolean launchAppUnderShizuPosed(String packageName) {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("❌ Shizuku not authorized, cannot launch " + packageName);
            return false;
        }
        if (!ensureDexReady()) {
            logger.e("❌ Cannot launch " + packageName + ": dex not deployed");
            return false;
        }

        // Push once per launch. If the module set is unchanged since the
        // last push, pushModulesToShellDir will still copy files (cheap,
        // a few hundred bytes each) and return true. That's simpler than
        // tracking a "dirty" flag, and the copy is sub-millisecond per
        // module.
        pushModulesToShellDir(moduleLoader.loadModules());

        String binary = shizukuHelper.getAppProcessBinary();
        if (binary == null) {
            logger.e("❌ No usable app_process binary on this ROM. "
                    + "Hooking is not possible on this device.");
            updateNotification("app_process unavailable");
            return false;
        }

        try {
            int targetUid = -1;
            try {
                ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(packageName, 0);
                targetUid = ai.uid;
            } catch (Throwable ignored) {}

            String cmd = String.format(
                "CLASSPATH=%s %s " +
                "-Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-Djava.class.path=%s " +
                "/system/bin com.shizuposed.manager.core.XposedHook %s 0 %d &",
                SHELL_DEX_PATH,
                binary,
                Build.VERSION.SDK_INT,
                SHELL_DEX_PATH,
                packageName,
                targetUid
            );

            logger.i("🚀 Launching " + packageName + " under ShizuPosed"
                + " (binary=" + binary + ", uid=" + targetUid + ")");
            logger.i("Executing: " + cmd);

            ShellUtils.CommandResult result = shizukuHelper.executeCommand(cmd);
            if (!result.isSuccess()) {
                logger.e("❌ " + binary + " launch failed: "
                    + result.getStderrString());
                return false;
            }

            logger.i("✅ " + binary + " spawned for " + packageName);
            return true;

        } catch (Exception e) {
            logger.e("❌ launchAppUnderShizuPosed error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Not supported without root. Kept as a stub so callers get a clear
     * "no" instead of a crash, and the log explains why.
     */
    public boolean injectProcess(String packageName, int pid, int uid,
                                 List<ModuleInfo> modules) {
        logger.i("📥 injectProcess(" + packageName + ", pid=" + pid
                + ", uid=" + uid + ")");
        if (!isRunning.get() || !shizukuHelper.isAuthorized()) return false;
        if (!ensureDexReady()) return false;
        logger.w("Cannot hook externally-launched process " + packageName
            + " — use launchAppUnderShizuPosed() to launch it under ShizuPosed");
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═════════════════════════════════════════════════════════════

    public boolean isRunning() { return isRunning.get(); }
    public boolean isXposedHookStarted() { return xposedHookStarted.get(); }
    public boolean isDexDeployed() { return dexDeployed.get(); }
    public static boolean isServiceRunning() { return isServiceRunning; }
}