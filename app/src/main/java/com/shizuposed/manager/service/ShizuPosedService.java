package com.shizuposed.manager.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
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
import com.shizuposed.manager.core.MarkerCache;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.runtime.RuntimePrefs;
import com.shizuposed.manager.utils.FileUtils;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;
import com.shizuposed.manager.stealth.SmartForegroundController;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.stealth.XStealthConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ShizuPosedService extends Service {
    private static final String CHANNEL_ID = "shizuposed_service";
    private static final String CHANNEL_NAME = "ShizuPosed Service";
    private static final int NOTIFICATION_ID = 1001;

    private static final String SHELL_BASE_FILE = ".shell_base";

    public static final String ACTION_REPUSH_MODULES =
        "com.shizuposed.manager.ACTION_REPUSH_MODULES";
    public static final String ACTION_LAUNCH_APP =
        "com.shizuposed.manager.ACTION_LAUNCH_APP";
    public static final String EXTRA_LAUNCH_PACKAGE = "package";

    /**
     * Fired by ShizukuBinderWatcher (and safe to fire from anywhere
     * else that detects Shizuku's binder returning) to restart the
     * service's worker path without user interaction. Idempotent:
     * if the service is already running and authorized, this is a
     * no-op.
     */
    public static final String ACTION_ENSURE_RUNNING =
        "com.shizuposed.manager.action.ENSURE_RUNNING";

    /**
     * Fired by SmartForegroundController when a watched app comes
     * to the foreground. Writes Developer Options and ADB to 0
     * through Shizuku, saving the prior values.
     */
    public static final String ACTION_DISABLE_DEV_SETTINGS =
        "com.shizuposed.manager.action.DISABLE_DEV_SETTINGS";

    /**
     * Fired by SmartForegroundController when a watched app leaves
     * the foreground. Restores the saved prior values.
     */
    public static final String ACTION_RESTORE_DEV_SETTINGS =
        "com.shizuposed.manager.action.RESTORE_DEV_SETTINGS";

    /** Extra carrying the package name for smart-fg actions. */
    public static final String EXTRA_SMART_FG_PACKAGE = "smartFgPackage";

    /** Removes shell-side logs (launch.log and variants). */
    public static final String ACTION_CLEAR_LOGS =
        "com.shizuposed.manager.ACTION_CLEAR_LOGS";
    /** Removes shell-side caches (dex, libs, modules, markers). */
    public static final String ACTION_CLEAN_ALL =
        "com.shizuposed.manager.ACTION_CLEAN_ALL";

    public static final String ACTION_LAUNCH_RESULT =
        "com.shizuposed.manager.action.LAUNCH_RESULT";
    public static final String EXTRA_LAUNCH_OK  = "ok";
    public static final String EXTRA_LAUNCH_MSG = "message";
    public static final String EXTRA_LAUNCH_PKG = "package";

    /**
     * Sentinel package name used for maintenance-action results
     * broadcast on ACTION_LAUNCH_RESULT. The UI's launch-result
     * receiver checks for this value and treats the result as a
     * maintenance result rather than a launch result.
     */
    public static final String MAINTENANCE_RESULT_PKG = "__logs__";

    private static final String[] SHELL_BASE_CANDIDATES = {
        "/data/user/0/com.android.shell/files/.syscall_cache",
        "/data/local/tmp/shizuposed",
    };

    private static final String SUB_DIR_LIBS    = "libs";
    private static final String SUB_DIR_MODULES = "modules";
    private static final String SUB_DIR_HOOKED  = "hooked";
    private static final String DEX_NAME        = "XposedHook.dex";
    private static final String LAUNCH_LOG      = "launch.log";

    private static final String LIB_AMIRU       = "libamiru.so";
    private static final String LIB_SHIZUPOSED  = "libshizuposed.so";
    private static final String LIB_XSTEALTH    = "libxstealth.so";
    private static final String LIB_XSTEALTH_NEXT = "libxstealth_next.so";
    private static final String LIB_CALLSITE    = "libcallsite.so";
    private static final String LIB_XSTEALTH_BRIDGE = "libxstealth_bridge.so";

    private static final boolean REQUIRE_AUTHORIZATION = true;

    /** Absolute paths for spawn tools. */
    private static final String ABS_SH      = "/system/bin/sh";
    private static final String ABS_SETSID  = "/system/bin/setsid";
    private static final String ABS_NOHUP   = "/system/bin/nohup";

    private static final long BOOTSTRAP_MIN_WAIT_MS = 500L;
    private static final long BOOTSTRAP_MAX_WAIT_MS = 8000L;
    private static final long BOOTSTRAP_POLL_MS = 250L;

    /**
     * File inside the app's private storage that holds the
     * Developer-Options and ADB values that were in effect before
     * smart-foreground last wrote 0.
     *
     * Layout:
     *   line 1: the value of development_settings_enabled
     *   line 2: the value of adb_enabled
     *
     * If the file exists, a smart-foreground disable is in effect
     * and hasn't been restored. If it doesn't, no disable is
     * outstanding.
     */
    private static final String SMART_FG_STATE_FILE = "smart_fg_state";

    // ═════════════════════════════════════════════════════════════
    // SPAWN-TOOL PROBE (cached once per process)
    // ═════════════════════════════════════════════════════════════

    private static volatile boolean sSpawnFormResolved = false;
    private static volatile boolean sSetsidAvailable = false;
    private static volatile boolean sNohupAvailable = false;
    private static volatile boolean sDisownAvailable = false;

    private String localStagingDexPath;
    private String localStagingModulesDir;
    private String localStagingLibDir;

    private static volatile boolean isServiceRunning = false;

    private static volatile String sResolvedShellBase = null;

    private Logger logger;
    private HandlerThread workerThread;
    private Handler workerHandler;
    private ProcessMonitor processMonitor;
    private ModuleLoader moduleLoader;
    private ShizukuHelper shizukuHelper;
    private final Gson gson = new Gson();

    private ExecutorService launchExecutor;

    private volatile String deployedDexHash = null;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean xposedHookStarted = new AtomicBoolean(false);
    private final AtomicBoolean dexDeployed = new AtomicBoolean(false);
    private final AtomicBoolean libsDeployed = new AtomicBoolean(false);

    private final AtomicBoolean onAuthorizedDispatched = new AtomicBoolean(false);
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);

    private final AtomicBoolean modulesPushed = new AtomicBoolean(false);
    private volatile long modulesPushedAt = 0L;

    /** Set once setupWorkerInfrastructure() has been called at least once. */
    private final AtomicBoolean workerSetupStarted = new AtomicBoolean(false);

    /**
     * Whether the WRITE_SECURE_SETTINGS bootstrap has already been
     * checked. Once checked, it never runs again in this process.
     */
    private final AtomicBoolean writeSecureSettingsChecked =
        new AtomicBoolean(false);

    /**
     * Packages with a monitor-triggered launch currently in flight.
     * Prevents the scan interval from queuing a second launch for a
     * package whose first launch hasn't finished yet.
     */
    private final java.util.Set<String> inFlightLaunches =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Map<String, PushedModuleEntry> pushedModules =
        new ConcurrentHashMap<>();

    private final Object pushLock = new Object();

    private static final class PushedModuleEntry {
        final long mtime;
        final long size;
        PushedModuleEntry(long mtime, long size) {
            this.mtime = mtime;
            this.size = size;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SHELL BASE RESOLUTION
    // ═════════════════════════════════════════════════════════════

    public static String getResolvedShellBase() {
        String b = sResolvedShellBase;
        if (b != null) return b;

        try {
            Context ctx = ShizuPosedManagerApp.getInstance();
            if (ctx != null) {
                File f = new File(ctx.getFilesDir(), SHELL_BASE_FILE);
                if (f.exists()) {
                    String diskBase = FileUtils.readFile(f);
                    if (diskBase != null && !diskBase.isEmpty()) {
                        String trimmed = diskBase.trim();
                        sResolvedShellBase = trimmed;
                        return trimmed;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return SHELL_BASE_CANDIDATES[0];
    }

    private String resolveShellBaseDir() {
        String cached = sResolvedShellBase;
        if (cached != null) return cached;

        if (shizukuHelper == null || !shizukuHelper.isAuthorized()) {
            return SHELL_BASE_CANDIDATES[0];
        }

        for (String base : SHELL_BASE_CANDIDATES) {
            if (probeWritable(base)) {
                logger.i("Shell base resolved: " + base);
                sResolvedShellBase = base;
                persistShellBase(base);
                return base;
            }
            logger.w("Shell base not writable: " + base);
        }

        logger.e("Neither shell base is writable — deploy will fail");
        sResolvedShellBase = SHELL_BASE_CANDIDATES[0];
        persistShellBase(SHELL_BASE_CANDIDATES[0]);
        return SHELL_BASE_CANDIDATES[0];
    }

    private void persistShellBase(String base) {
        if (base == null) return;
        try {
            File f = new File(getFilesDir(), SHELL_BASE_FILE);
            try (FileWriter w = new FileWriter(f, false)) {
                w.write(base);
            }
            f.setReadable(true, false);
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("Failed to persist shell base: " + t.getMessage());
            }
        }
    }

    private boolean probeWritable(String base) {
        try {
            String probePath = base + "/.probe";
            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                "mkdir -p " + base + " && "
                + "echo ok > " + probePath + " && "
                + "rm -f " + probePath + " && "
                + "echo READY");
            return r != null
                && r.isSuccess()
                && r.getStdoutString() != null
                && r.getStdoutString().contains("READY");
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("probeWritable(" + base + ") threw: " + t.getMessage());
            }
            return false;
        }
    }

    private static String shellPath(String base, String sub) {
        return base + "/" + sub;
    }

    private static String shellFile(String base, String filename) {
        return base + "/" + filename;
    }

    // ═════════════════════════════════════════════════════════════
    // SPAWN-TOOL PROBING
    // ═════════════════════════════════════════════════════════════

    private void probeSpawnTools() {
        if (sSpawnFormResolved) return;

        synchronized (ShizuPosedService.class) {
            if (sSpawnFormResolved) return;

            boolean setsid = false;
            boolean nohup = false;
            boolean disown = false;

            try {
                String probe =
                    "S=0; N=0; D=0; "
                    + "if command -v setsid >/dev/null 2>&1; then S=1; "
                    + "elif type setsid >/dev/null 2>&1; then S=1; fi; "
                    + "if command -v nohup >/dev/null 2>&1; then N=1; "
                    + "elif type nohup >/dev/null 2>&1; then N=1; fi; "
                    + "if command -v disown >/dev/null 2>&1; then D=1; "
                    + "elif type disown >/dev/null 2>&1; then D=1; fi; "
                    + "echo SETSID=$S NOHUP=$N DISOWN=$D";

                ShellUtils.CommandResult r = shizukuHelper.executeCommand(probe);

                if (r != null && r.getStdoutString() != null) {
                    String out = r.getStdoutString();
                    setsid = out.contains("SETSID=1");
                    nohup  = out.contains("NOHUP=1");
                    disown = out.contains("DISOWN=1");
                } else {
                    logger.w("probeSpawnTools: no output, assuming bare spawn");
                }
            } catch (Throwable t) {
                logger.w("probeSpawnTools threw: " + t.getMessage()
                    + " — assuming bare spawn");
            }

            sSetsidAvailable = setsid;
            sNohupAvailable  = nohup;
            sDisownAvailable = disown;
            sSpawnFormResolved = true;

            logger.i("Spawn tools: setsid=" + setsid
                + " nohup=" + nohup
                + " disown=" + disown);
        }
    }

    private String buildSpawnPrefix() {
        if (!sSpawnFormResolved) {
            probeSpawnTools();
        }

        if (sSetsidAvailable) {
            return ABS_SETSID + " -d";
        }
        if (sNohupAvailable) {
            return ABS_NOHUP;
        }

        logger.w("Neither setsid nor nohup available — using bare spawn. "
            + "The target process may be killed when the shell exits.");
        return "";
    }

    // ═════════════════════════════════════════════════════════════
    // AUTHORIZATION GATE
    // ═════════════════════════════════════════════════════════════

    private boolean isAuthorizedNow() {
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            return helper.isAvailable() && helper.isAuthorized();
        } catch (Throwable t) {
            if (logger != null) {
                logger.e("isAuthorizedNow failed: " + t.getMessage());
            }
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        createNotificationChannel();

        try {
            startForeground(NOTIFICATION_ID,
                buildNotification("Service starting"));
            foregroundStarted.set(true);
        } catch (Throwable t) {
            android.util.Log.e("ShizuPosedService",
                "startForeground failed in onCreate", t);
            isServiceRunning = false;
            stopSelf();
            return;
        }

        logger = Logger.getInstance(this);
        logger.i("ShizuPosedService creating...");

        if (REQUIRE_AUTHORIZATION && !isAuthorizedNow()) {
            logger.w("onCreate: Shizuku not currently responsive — "
                + "deferring deploy; will resume on authorization");
        }

        setupWorkerInfrastructure();
    }

    private synchronized void setupWorkerInfrastructure() {
        if (!workerSetupStarted.compareAndSet(false, true)) {
            return;
        }

        workerThread = new HandlerThread("ShizuPosedWorker",
                Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        launchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ShizuPosed-launch");
            t.setDaemon(true);
            return t;
        });

        workerHandler.post(() -> {
            try {
                File externalDir = getExternalFilesDir(null);
                if (externalDir == null) {
                    logger.w("External app dir unavailable, "
                        + "falling back to internal");
                    externalDir = getFilesDir();
                }
                if (!externalDir.exists()) externalDir.mkdirs();

                localStagingDexPath =
                    new File(externalDir, "XposedHook.dex").getAbsolutePath();
                localStagingModulesDir =
                    new File(externalDir, "modules").getAbsolutePath();
                new File(localStagingModulesDir).mkdirs();

                localStagingLibDir =
                    new File(externalDir, "libs").getAbsolutePath();
                new File(localStagingLibDir).mkdirs();

                logger.i("Local staging dex:  " + localStagingDexPath);
                logger.i("Local staging mods: " + localStagingModulesDir);
                logger.i("Local staging libs: " + localStagingLibDir);

                initComponents();

                String shellBase = resolveShellBaseDir();
                logger.i("Shell base:         " + shellBase);

                // One-time bootstrap: grant WRITE_SECURE_SETTINGS to
                // our own package if it isn't already granted. Runs
                // once per process. See the method javadoc for the
                // rationale and the fail-open behavior.
                ensureWriteSecureSettingsGranted();

                if (shizukuHelper.isAuthorized()) {
                    probeSpawnTools();
                    startServiceInternal();
                } else {
                    logger.i("ShizuPosed: worker ready, waiting for "
                        + "Shizuku authorization");
                }

                logger.i("ShizuPosedService setup complete");
            } catch (Throwable t) {
                logger.e("setupWorkerInfrastructure failed: "
                    + t.getMessage());
                workerSetupStarted.set(false);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID,
                buildNotification("Service running"));
            foregroundStarted.set(true);
        } catch (Throwable t) {
            android.util.Log.e("ShizuPosedService",
                "startForeground failed in onStartCommand", t);
        }

        isServiceRunning = true;

        if (workerHandler == null) {
            setupWorkerInfrastructure();
        }

        if (workerHandler == null) {
            if (intent != null
                    && ShizuPosedManagerApp.ACTION_SHIZUKU_AUTHORIZED
                        .equals(intent.getAction())) {
                if (logger != null) {
                    logger.i("ACTION_SHIZUKU_AUTHORIZED arrived before "
                        + "worker ready — will dispatch after setup");
                }
            }
            return START_STICKY;
        }

        if (intent != null) {
            String action = intent.getAction();
            if (action == null) {
                return START_STICKY;
            }

            switch (action) {
                case ShizuPosedManagerApp.ACTION_SHIZUKU_AUTHORIZED:
                    logger.i("ACTION_SHIZUKU_AUTHORIZED received");
                    onShizukuAuthorized();
                    break;

                case ACTION_ENSURE_RUNNING:
                    logger.i("ACTION_ENSURE_RUNNING received");
                    onEnsureRunning();
                    break;

                case ACTION_DISABLE_DEV_SETTINGS: {
                    final String pkg = intent.getStringExtra(
                        EXTRA_SMART_FG_PACKAGE);
                    logger.i("ACTION_DISABLE_DEV_SETTINGS for " + pkg);
                    workerHandler.post(() -> {
                        String result = disableDevSettings(pkg);
                        logger.i("smart-fg disable result: " + result);
                    });
                    break;
                }

                case ACTION_RESTORE_DEV_SETTINGS: {
                    final String pkg = intent.getStringExtra(
                        EXTRA_SMART_FG_PACKAGE);
                    logger.i("ACTION_RESTORE_DEV_SETTINGS for " + pkg);
                    workerHandler.post(() -> {
                        String result = restoreDevSettings(pkg);
                        logger.i("smart-fg restore result: " + result);
                    });
                    break;
                }

                case ACTION_LAUNCH_APP: {
                    final String target =
                        intent.getStringExtra(EXTRA_LAUNCH_PACKAGE);
                    if (target != null) {
                        logger.i("ACTION_LAUNCH_APP received for " + target);
                        launchExecutor.execute(() -> {
                            AtomicBoolean sent = new AtomicBoolean(false);
                            String failReason = null;
                            boolean ok = false;
                            try {
                                ok = launchAppUnderShizuPosed(target, sent);
                            } catch (Throwable t) {
                                failReason = t.getMessage();
                                logger.e("launchAppUnderShizuPosed threw: "
                                    + t.getMessage());
                            }
                            if (!ok && failReason == null) {
                                failReason = "launch returned false — "
                                    + "see Logs tab";
                            }
                            broadcastOnce(target, sent, ok, failReason);
                        });
                    } else {
                        logger.w("ACTION_LAUNCH_APP without "
                            + "EXTRA_LAUNCH_PACKAGE");
                    }
                    break;
                }

                case ACTION_REPUSH_MODULES:
                    logger.i("ACTION_REPUSH_MODULES received");
                    workerHandler.post(() -> {
                        if (ensurePayloadReady()) {
                            pushModulesToShellDir(moduleLoader.loadModules());
                        }
                    });
                    break;

                case ACTION_CLEAR_LOGS:
                    logger.i("ACTION_CLEAR_LOGS received");
                    workerHandler.post(() -> {
                        String result = clearShellSideLogs();
                        broadcastMaintenanceResult(result);
                    });
                    break;

                case ACTION_CLEAN_ALL:
                    logger.i("ACTION_CLEAN_ALL received");
                    workerHandler.post(() -> {
                        String result = cleanAllCaches();
                        broadcastMaintenanceResult(result);
                    });
                    break;

                default:
                    logger.d("onStartCommand: unknown action " + action);
                    break;
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
        if (logger != null) logger.i("ShizuPosedService destroying...");
        isRunning.set(false);
        isServiceRunning = false;
        if (processMonitor != null) processMonitor.stopMonitoring();
        if (launchExecutor != null) {
            launchExecutor.shutdownNow();
            launchExecutor = null;
        }
        if (workerThread != null) workerThread.quitSafely();

        if (foregroundStarted.getAndSet(false)) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Throwable ignored) {}
        }

        onAuthorizedDispatched.set(false);
        workerSetupStarted.set(false);

        try {
            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            if (app != null) app.onServiceDestroyed();
        } catch (Throwable ignored) {}

        super.onDestroy();
    }

    // ═════════════════════════════════════════════════════════════
    // ENSURE RUNNING (fired by ShizukuBinderWatcher)
    // ═════════════════════════════════════════════════════════════

    private void onEnsureRunning() {
        if (logger != null) {
            logger.i("onEnsureRunning: isRunning=" + isRunning.get()
                + " workerHandler=" + (workerHandler != null ? "ready" : "null")
                + " authorized=" + (shizukuHelper != null
                    && shizukuHelper.isAuthorized()));
        }

        if (workerHandler == null) {
            setupWorkerInfrastructure();
            return;
        }

        if (shizukuHelper == null || !shizukuHelper.isAuthorized()) {
            if (logger != null) {
                logger.i("onEnsureRunning: Shizuku not authorized — "
                    + "deferring");
            }
            return;
        }

        workerHandler.post(() -> {
            try {
                if (ensurePayloadReady()) {
                    pushModulesToShellDir(moduleLoader.loadModules());
                }
                startServiceInternal();
                onAuthorizedDispatched.set(false);

                if (logger != null) {
                    logger.i("onEnsureRunning: service restarted");
                }
            } catch (Throwable t) {
                if (logger != null) {
                    logger.e("onEnsureRunning worker failed: "
                        + t.getMessage());
                }
            }
        });
    }

    private String disableDevSettings(String pkg) {
        if (!shizukuHelper.isAuthorized()) {
            return "Shizuku not authorized";
        }

        XStealthConfig config = XStealthConfig.load();
        if (config == null) return "no config";
        boolean wantDev = config.hideDevOptions;
        boolean wantAdb = config.hideAdb;
        if (!wantDev && !wantAdb) {
            return "no signals enabled in config";
        }

        File stateFile = new File(getFilesDir(), SMART_FG_STATE_FILE);
        if (stateFile.exists()) {
            return "already disabled (saved state present)";
        }

        try {
            String currentDev = wantDev
                ? readGlobalSetting("development_settings_enabled")
                : null;
            String currentAdb = wantAdb
                ? readGlobalSetting("adb_enabled")
                : null;
            if (wantDev && currentDev == null) currentDev = "1";
            if (wantAdb && currentAdb == null) currentAdb = "1";

            // Save prior values. The file format is:
            //   line 1: dev value, or "-" if dev is not being touched
            //   line 2: adb value, or "-" if adb is not being touched
            try (FileWriter w = new FileWriter(stateFile, false)) {
                w.write(wantDev ? currentDev : "-");
                w.write("\n");
                w.write(wantAdb ? currentAdb : "-");
                w.write("\n");
            }
            stateFile.setReadable(true, false);

            boolean okDev = !wantDev
                || writeGlobalSetting("development_settings_enabled", "0");
            boolean okAdb = !wantAdb
                || writeGlobalSetting("adb_enabled", "0");

            if (!okDev || !okAdb) {
                stateFile.delete();
                return "write failed (dev=" + okDev + " adb=" + okAdb + ")";
            }

            SmartForegroundController.markPendingRestore(this);

            logger.i("smart-fg: disabled for " + pkg
                     + " (dev=" + (wantDev ? currentDev : "skipped")
                     + " adb=" + (wantAdb ? currentAdb : "skipped") + ")");
            return "disabled";
        } catch (Throwable t) {
            if (stateFile.exists()) stateFile.delete();
            logger.w("smart-fg: disable failed: " + t.getMessage());
            return "error: " + t.getMessage();
        }
    }

    private String restoreDevSettings(String pkg) {
        if (!shizukuHelper.isAuthorized()) {
            return "Shizuku not authorized";
        }

        File stateFile = new File(getFilesDir(), SMART_FG_STATE_FILE);
        if (!stateFile.exists()) {
            return "nothing to restore";
        }

        try {
            String dev = "-";
            String adb = "-";
            try (BufferedReader r = new BufferedReader(
                    new FileReader(stateFile))) {
                String l1 = r.readLine();
                String l2 = r.readLine();
                if (l1 != null && !l1.isEmpty()) dev = l1.trim();
                if (l2 != null && !l2.isEmpty()) adb = l2.trim();
            }

            boolean wantDev = !"-".equals(dev);
            boolean wantAdb = !"-".equals(adb);

            boolean okDev = !wantDev
                || writeGlobalSetting("development_settings_enabled", dev);
            boolean okAdb = !wantAdb
                || writeGlobalSetting("adb_enabled", adb);

            if (okDev && okAdb) {
                stateFile.delete();
                SmartForegroundController.clearPendingRestore(this);
                logger.i("smart-fg: restored for " + pkg
                         + " (dev=" + dev + " adb=" + adb + ")");
                return "restored";
            } else {
                logger.w("smart-fg: restore partial (dev=" + okDev
                         + " adb=" + okAdb + ")");
                return "partial: dev=" + okDev + " adb=" + okAdb;
            }
        } catch (Throwable t) {
            logger.w("smart-fg: restore failed: " + t.getMessage());
            return "error: " + t.getMessage();
        }
    }

    private String readGlobalSetting(String key) {
        try {
            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                "settings get global " + key);
            if (r != null && r.isSuccess() && r.getStdoutString() != null) {
                return r.getStdoutString().trim();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean writeGlobalSetting(String key, String value) {
        try {
            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                "settings put global " + key + " " + value);
            return r != null && r.isSuccess();
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureWriteSecureSettingsGranted() {
        if (!writeSecureSettingsChecked.compareAndSet(false, true)) {
            return;
        }
        if (!shizukuHelper.isAuthorized()) return;

        String pkg = getPackageName();

        try {
            // Already granted? Look for the permission in the
            // package's granted list. The grep is narrow: we only
            // care about the exact permission name.
            ShellUtils.CommandResult check = shizukuHelper.executeCommand(
                "dumpsys package " + pkg
                + " | grep -A1 'grantedPermissions:' "
                + "| grep android.permission.WRITE_SECURE_SETTINGS");
            if (check != null && check.isSuccess()
                    && check.getStdoutString() != null
                    && check.getStdoutString().contains(
                        "WRITE_SECURE_SETTINGS")) {
                return;
            }
        } catch (Throwable ignored) {
            // If the check fails, fall through to issuing the
            // grant. A redundant grant is harmless.
        }

        try {
            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                "pm grant " + pkg
                + " android.permission.WRITE_SECURE_SETTINGS");
            if (r != null && r.isSuccess()) {
                logger.i("bootstrap: granted WRITE_SECURE_SETTINGS to " + pkg);
            } else {
                logger.w("bootstrap: WRITE_SECURE_SETTINGS grant "
                    + "returned non-zero");
            }
        } catch (Throwable t) {
            logger.w("bootstrap: WRITE_SECURE_SETTINGS grant failed: "
                + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LAUNCH RESULT BROADCAST
    // ═════════════════════════════════════════════════════════════

    private void broadcastOnce(String packageName,
                               AtomicBoolean sent,
                               boolean ok,
                               String message) {
        if (!sent.compareAndSet(false, true)) return;
        broadcastLaunchResult(packageName, ok, message);
    }

    private void broadcastLaunchResult(String packageName,
                                       boolean ok,
                                       String message) {
        try {
            Intent i = new Intent(ACTION_LAUNCH_RESULT);
            i.putExtra(EXTRA_LAUNCH_PKG, packageName);
            i.putExtra(EXTRA_LAUNCH_OK, ok);
            if (message != null) {
                i.putExtra(EXTRA_LAUNCH_MSG, message);
            }
            i.setPackage(getPackageName());
            sendBroadcast(i);

            if (logger != null) {
                logger.i("Broadcast launch result: pkg=" + packageName
                    + " ok=" + ok
                    + (message != null ? " msg=" + message : ""));
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("broadcastLaunchResult failed: " + t.getMessage());
            }
        }
    }

    private void broadcastMaintenanceResult(String message) {
        boolean ok = message != null && (
            message.startsWith("Shell logs cleared")
            || message.startsWith("All caches cleaned"));
        broadcastLaunchResult(MAINTENANCE_RESULT_PKG, ok, message);
    }

    // ═════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═════════════════════════════════════════════════════════════

    public String clearShellSideLogs() {
        try {
            if (!shizukuHelper.isAuthorized()) {
                return "Shizuku not authorized";
            }

            String base = resolveShellBaseDir();
            String logPath = shellFile(base, LAUNCH_LOG);

            String script =
                "rm -f \"" + logPath + "\"; "
                + "rm -f " + base + "/*.log; "
                + "rm -f " + base + "/*.log.old; "
                + "rm -f " + base + "/hooked/*.tmp; "
                + "echo CLEARED";

            String cmd = ABS_SH + " -c '"
                + script.replace("'", "'\\''") + "'";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(cmd);
            if (r != null && r.isSuccess()
                    && r.getStdoutString() != null
                    && r.getStdoutString().contains("CLEARED")) {
                if (logger != null) {
                    logger.i("Shell-side logs cleared at " + base);
                }
                return "Shell logs cleared";
            }

            if (logger != null) {
                logger.w("clearShellSideLogs: command failed: "
                    + (r != null ? "stdout=[" + r.getStdoutString()
                        + "] stderr=[" + r.getStderrString() + "]"
                        : "null result"));
            }
            return "Could not clear shell logs";

        } catch (Throwable t) {
            if (logger != null) {
                logger.w("clearShellSideLogs threw: " + t.getMessage());
            }
            return "Error: " + t.getMessage();
        }
    }

    public String diagnoseModuleActivation(String modulePackage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Diagnostic for ").append(modulePackage).append("\n");

        try {
            List<ModuleInfo> all = moduleLoader.loadModules();
            ModuleInfo mi = null;
            for (ModuleInfo m : all) {
                if (m != null && modulePackage.equals(m.packageName)) {
                    mi = m;
                    break;
                }
            }
            sb.append("  In module list: ")
              .append(mi != null ? "yes" : "NO").append("\n");
            if (mi == null) {
                sb.append("  → Module was not discovered. Check the APK is "
                    + "installed and declares an Xposed marker.\n");
                return sb.toString();
            }
            sb.append("  Enabled: ")
              .append(mi.enabled ? "yes" : "NO").append("\n");

            boolean dexOk = mi.cachedDexPath != null
                && new File(mi.cachedDexPath).exists();
            sb.append("  Cached dex: ")
              .append(dexOk ? "present" : "MISSING").append("\n");
            if (!dexOk) {
                sb.append("  → The module's dex wasn't pushed to the shell. "
                    + "Try Settings → Maintenance → Repush modules.\n");
            }

            int scoped = mi.hookedApps != null ? mi.hookedApps.size() : 0;
            sb.append("  Scoped to ").append(scoped).append(" app(s)");
            if (mi.hookAllApps) sb.append(" (all apps)");
            sb.append("\n");

            boolean anyMarker = false;
            int markerCount = 0;
            try {
                Map<String, String> mirror = MarkerCache.read(this);
                markerCount = mirror.size();
                for (String body : mirror.values()) {
                    if (body != null
                            && body.contains("\"" + modulePackage + "\"")) {
                        anyMarker = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                sb.append("  Marker check failed: ")
                  .append(t.getMessage()).append("\n");
            }
            sb.append("  Marker listing module: ")
              .append(anyMarker ? "yes" : "NO").append("\n");
            sb.append("  Marker count in mirror: ")
              .append(markerCount).append("\n");
            if (!anyMarker) {
                sb.append("  → Launch a scoped app under ShizuPosed, or "
                    + "launch the module's own UI via long-press, then "
                    + "re-run this diagnostic.\n");
            }

            sb.append("  Manifest <queries> check: not queryable from here.\n");
            sb.append("  → On Android 11+, the module must declare:\n");
            sb.append("      <queries>\n");
            sb.append("        <provider android:authorities="
                + "\"com.shizuposed.manager.status\" />\n");
            sb.append("      </queries>\n");
            sb.append("    Without it, XposedBridge.isModuleActive returns "
                + "false regardless of marker state.\n");

        } catch (Throwable t) {
            sb.append("  Diagnostic failed: ")
              .append(t.getMessage()).append("\n");
        }

        return sb.toString();
    }

    public String cleanAllCaches() {
        try {
            if (!shizukuHelper.isAuthorized()) {
                return "Shizuku not authorized";
            }

            String base = resolveShellBaseDir();
            String libsDir = shellPath(base, SUB_DIR_LIBS);
            String modulesDir = shellPath(base, SUB_DIR_MODULES);
            String hookedDir = shellPath(base, SUB_DIR_HOOKED);
            String dexPath = shellFile(base, DEX_NAME);

            String script =
                "rm -f " + base + "/*.log; "
                + "rm -f " + base + "/*.log.old; "
                + "rm -f \"" + dexPath + "\"; "
                + "rm -f " + libsDir + "/*.so; "
                + "rm -f " + modulesDir + "/*; "
                + "rm -f " + hookedDir + "/*; "
                + "echo CLEANED";

            String cmd = ABS_SH + " -c '"
                + script.replace("'", "'\\''") + "'";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(cmd);
            if (r != null && r.isSuccess()
                    && r.getStdoutString() != null
                    && r.getStdoutString().contains("CLEANED")) {

                dexDeployed.set(false);
                libsDeployed.set(false);
                modulesPushed.set(false);
                pushedModules.clear();
                modulesPushedAt = 0L;

                if (logger != null) {
                    logger.i("All shell-side caches cleaned at " + base
                        + " — next launch will redeploy");
                }
                return "All caches cleaned";
            }

            if (logger != null) {
                logger.w("cleanAllCaches: command failed: "
                    + (r != null ? "stdout=[" + r.getStdoutString()
                        + "] stderr=[" + r.getStderrString() + "]"
                        : "null result"));
            }
            return "Could not clean caches";

        } catch (Throwable t) {
            if (logger != null) {
                logger.w("cleanAllCaches threw: " + t.getMessage());
            }
            return "Error: " + t.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ═════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel =
                new android.app.NotificationChannel(
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
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void updateNotification(String status) {
        if (!foregroundStarted.get()) return;
        NotificationManager manager =
            getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(status));
        }
    }

    // ═════════════════════════════════════════════════════════════
    // COMPONENT INIT
    // ═════════════════════════════════════════════════════════════

    private void initComponents() {
        shizukuHelper = ShizukuHelper.getInstance(this);
        moduleLoader = ModuleLoader.getInstance(this);
        processMonitor = ProcessMonitor.getInstance(this);
        processMonitor.setShizuPosedService(this);
        logger.i("Components initialized (payload deployment deferred "
            + "until Shizuku ready)");
    }

    // ═════════════════════════════════════════════════════════════
    // LOCAL STAGING
    // ═════════════════════════════════════════════════════════════

    private boolean stageDexLocally() {
        try {
            File staged = new File(localStagingDexPath);
            File parent = staged.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            byte[] assetBytes;
            try (InputStream is = getAssets().open("XposedHook.dex")) {
                assetBytes = readAllBytes(is);
            } catch (Exception e) {
                logger.e("stageDexLocally: cannot read asset: "
                    + e.getMessage());
                return false;
            }
            if (assetBytes.length == 0) {
                logger.e("stageDexLocally: asset is empty");
                return false;
            }

            if (staged.exists() && staged.isFile()
                    && staged.length() == assetBytes.length) {
                byte[] stagedBytes;
                try (InputStream is = new FileInputStream(staged)) {
                    stagedBytes = readAllBytes(is);
                } catch (Exception e) {
                    stagedBytes = null;
                }

                if (stagedBytes != null
                        && java.util.Arrays.equals(stagedBytes, assetBytes)) {
                    logger.d("stageDexLocally: staged dex already matches "
                        + "asset (" + assetBytes.length + " bytes)");
                    staged.setReadable(true, false);
                    return true;
                }

                logger.i("stageDexLocally: staged dex differs from asset "
                    + "(staged=" + staged.length()
                    + " bytes, asset=" + assetBytes.length
                    + " bytes) — re-staging");
            } else if (staged.exists()) {
                logger.i("stageDexLocally: staged dex size mismatch "
                    + "(staged=" + staged.length()
                    + " bytes, asset=" + assetBytes.length
                    + " bytes) — re-staging");
            } else {
                logger.i("stageDexLocally: no staged dex — staging "
                    + assetBytes.length + " bytes");
            }

            File tmp = new File(staged.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(assetBytes);
                fos.flush();
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }

            if (staged.exists() && !staged.delete()) {
                logger.w("stageDexLocally: could not delete old staged dex");
            }
            if (!tmp.renameTo(staged)) {
                logger.e("stageDexLocally: rename failed for "
                    + tmp.getAbsolutePath());
                tmp.delete();
                return false;
            }

            staged.setReadable(true, false);
            logger.i("XposedHook.dex staged! Size: "
                + assetBytes.length + " bytes");
            return true;

        } catch (Exception e) {
            logger.e("stageDexLocally error: " + e.getMessage());
            return false;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private File stageNativeLib(String libName) {
        try {
            File src = new File(getApplicationInfo().nativeLibraryDir,
                libName);
            if (!src.exists()) {
                logger.w("stageNativeLib: source missing: "
                    + src.getAbsolutePath());
                return null;
            }

            File dst = new File(localStagingLibDir, libName);

            byte[] srcBytes = readFileBytes(src);
            if (srcBytes == null || srcBytes.length == 0) {
                logger.e("stageNativeLib: could not read source: "
                    + src.getAbsolutePath());
                return null;
            }

            if (dst.exists()) {
                byte[] dstBytes = readFileBytes(dst);
                if (dstBytes != null
                        && java.util.Arrays.equals(srcBytes, dstBytes)) {
                    dst.setReadable(true, false);
                    logger.d("stageNativeLib: " + libName
                        + " already staged and matches source");
                    return dst;
                }
                if (dstBytes != null) {
                    logger.i("stageNativeLib: " + libName
                        + " differs from source (staged=" + dstBytes.length
                        + " bytes, source=" + srcBytes.length
                        + " bytes) — re-staging");
                }
            } else {
                logger.i("stageNativeLib: no staged copy of " + libName
                    + " — staging " + srcBytes.length + " bytes");
            }

            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(srcBytes);
                out.flush();
                try { out.getFD().sync(); } catch (Throwable ignored) {}
            }

            if (dst.exists() && !dst.delete()) {
                logger.w("stageNativeLib: could not delete old " + libName);
            }
            if (!tmp.renameTo(dst)) {
                logger.e("stageNativeLib: rename failed for " + libName);
                tmp.delete();
                return null;
            }

            dst.setReadable(true, false);
            logger.i("Staged " + libName + " (" + srcBytes.length
                + " bytes)");
            return dst;

        } catch (Throwable t) {
            logger.e("stageNativeLib(" + libName + ") failed: "
                + t.getMessage());
            return null;
        }
    }

    private static byte[] readFileBytes(File f) {
        if (f == null || !f.exists() || !f.isFile()) return null;
        try (InputStream is = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DEX DEPLOYMENT
    // ═════════════════════════════════════════════════════════════

    private boolean deployDexToShellDir() {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("Cannot deploy dex: Shizuku not authorized");
            return false;
        }
        if (!stageDexLocally()) return false;

        String base = resolveShellBaseDir();
        String dexPath    = shellFile(base, DEX_NAME);
        String libsDir    = shellPath(base, SUB_DIR_LIBS);
        String modulesDir = shellPath(base, SUB_DIR_MODULES);
        String hookedDir  = shellPath(base, SUB_DIR_HOOKED);

        try {
            logger.i("Deploying XposedHook.dex to " + dexPath);

            ShellUtils.CommandResult mkdirResult =
                shizukuHelper.executeCommand("mkdir -p " + base);
            if (!mkdirResult.isSuccess()) {
                logger.e("Failed to create base dir: "
                    + mkdirResult.getStderrString());
                return false;
            }

            String copyCmd = ABS_SH + " -c 'cat \""
                + localStagingDexPath + "\" > \"" + dexPath + "\"'";
            ShellUtils.CommandResult copyResult =
                shizukuHelper.executeCommand(copyCmd);
            if (!copyResult.isSuccess()) {
                logger.e("Failed to copy dex: "
                    + copyResult.getStderrString());
                return false;
            }

            shizukuHelper.executeCommand("chmod 755 " + dexPath);

            ShellUtils.CommandResult verify =
                shizukuHelper.executeCommand(
                    "test -s " + dexPath + " && echo OK");
            if (!verify.isSuccess()
                    || !verify.getStdoutString().contains("OK")) {
                logger.e("Verification failed: dex not present");
                return false;
            }

            shizukuHelper.executeCommand("mkdir -p " + libsDir);
            shizukuHelper.executeCommand("mkdir -p " + modulesDir);
            shizukuHelper.executeCommand("mkdir -p " + hookedDir);

            shizukuHelper.executeCommand("chmod 755 " + base
                + " " + libsDir
                + " " + modulesDir);
            shizukuHelper.executeCommand("chmod 777 " + hookedDir);

            logger.i("XposedHook.dex deployed to: " + dexPath);
            dexDeployed.set(true);
            try {
                deployedDexHash = assetHash("XposedHook.dex");
            } catch (Throwable ignored) {}
            return true;

        } catch (Exception e) {
            logger.e("deployDexToShellDir error: " + e.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // NATIVE LIBRARY DEPLOYMENT
    // ═════════════════════════════════════════════════════════════

    private boolean deployNativeLibsToShellDir() {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("Cannot deploy native libs: Shizuku not authorized");
            return false;
        }

        String base = resolveShellBaseDir();
        String libsDir = shellPath(base, SUB_DIR_LIBS);

        try {
            logger.i("Deploying native libs to " + libsDir);

            ShellUtils.CommandResult mkdir =
                shizukuHelper.executeCommand("mkdir -p " + libsDir);
            if (!mkdir.isSuccess()) {
                logger.e("Failed to create shell lib dir: "
                    + mkdir.getStderrString());
                return false;
            }

            boolean amiruOk  = pushOneLib(LIB_AMIRU,
                libsDir + "/" + LIB_AMIRU);
            boolean szpOk    = pushOneLib(LIB_SHIZUPOSED,
                libsDir + "/" + LIB_SHIZUPOSED);
            boolean xsOk     = pushOneLib(LIB_XSTEALTH,
                libsDir + "/" + LIB_XSTEALTH);
            boolean xsNextOk = pushOneLib(LIB_XSTEALTH_NEXT,
                libsDir + "/" + LIB_XSTEALTH_NEXT);
            boolean csOk     = pushOneLib(LIB_CALLSITE,
                libsDir + "/" + LIB_CALLSITE);
            boolean bridgeOk = pushOneLib(LIB_XSTEALTH_BRIDGE,
                libsDir + "/" + LIB_XSTEALTH_BRIDGE);

            if (!amiruOk && !szpOk && !xsOk && !xsNextOk && !csOk) {
                logger.e("No native libs deployed");
                return false;
            }
            if (!amiruOk)  logger.w("libamiru.so not deployed");
            if (!szpOk)    logger.w("libshizuposed.so not deployed");
            if (!xsOk)     logger.w("libxstealth.so not deployed");
            if (!xsNextOk) logger.w("libxstealth_next.so not deployed");
            if (!csOk)     logger.w("libcallsite.so not deployed");
            if (!bridgeOk) logger.w("libxstealth_bridge.so not deployed");

            logger.i("Native libs deployed to: " + libsDir);
            libsDeployed.set(true);
            return true;

        } catch (Exception e) {
            logger.e("deployNativeLibsToShellDir error: " + e.getMessage());
            return false;
        }
    }

    private boolean pushOneLib(String libName, String dstPath) {
        File staged = stageNativeLib(libName);
        if (staged == null) return false;

        String copy = ABS_SH + " -c 'cat \""
            + staged.getAbsolutePath() + "\" > \"" + dstPath + "\"'";
        ShellUtils.CommandResult r = shizukuHelper.executeCommand(copy);
        if (!r.isSuccess()) {
            logger.w("Failed to push " + libName + ": "
                + r.getStderrString());
            return false;
        }

        shizukuHelper.executeCommand("chmod 755 " + dstPath);

        ShellUtils.CommandResult verify =
            shizukuHelper.executeCommand(
                "test -s " + dstPath + " && echo OK");
        if (!verify.isSuccess()
                || !verify.getStdoutString().contains("OK")) {
            logger.w("Verification failed for " + libName
                + " at " + dstPath);
            return false;
        }
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    // PAYLOAD READINESS
    // ═════════════════════════════════════════════════════════════

    public boolean ensureDexReady() {
        try {
            String currentHash = assetHash("XposedHook.dex");
            if (currentHash != null
                    && deployedDexHash != null
                    && !currentHash.equals(deployedDexHash)) {
                logger.i("ensureDexReady: XposedHook.dex asset changed "
                    + "since last deploy — forcing redeploy");
                dexDeployed.set(false);
            }
        } catch (Throwable t) {
            logger.d("ensureDexReady: hash check failed: " + t.getMessage());
        }

        if (dexDeployed.get()) return true;
        if (!shizukuHelper.isAuthorized()) {
            logger.w("ensureDexReady called but Shizuku not "
                + "authorized yet");
            return false;
        }
        boolean ok = deployDexToShellDir();
        if (ok) {
            try {
                deployedDexHash = assetHash("XposedHook.dex");
            } catch (Throwable ignored) {}
        }
        return ok;
    }

    private String assetHash(String assetName) {
        try (InputStream is = getAssets().open(assetName)) {
            java.security.MessageDigest md =
                java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean ensurePayloadReady() {
        if (logger != null) {
            logger.d("ensurePayloadReady: checking dex...");
        }
        boolean dexOk = ensureDexReady();
        if (!dexOk) {
            if (logger != null) {
                logger.w("ensurePayloadReady: dex not ready");
            }
            return false;
        }

        probeSpawnTools();

        if (nativeLibsPresentOnShell()) {
            libsDeployed.set(true);
            if (logger != null) {
                logger.d("ensurePayloadReady: libs match APK — "
                    + "skipping deploy");
            }
            return true;
        }

        if (logger != null) {
            logger.i("ensurePayloadReady: libs differ or missing — "
                + "deploying native libs (this takes a few seconds)...");
        }
        libsDeployed.set(false);
        boolean ok = deployNativeLibsToShellDir();
        if (logger != null) {
            logger.i("ensurePayloadReady: native lib deploy result=" + ok);
        }
        return ok;
    }

    private boolean nativeLibsPresentOnShell() {
        String base = resolveShellBaseDir();
        String libsDir = shellPath(base, SUB_DIR_LIBS);

        String[] libs = {
            LIB_XSTEALTH,
            LIB_XSTEALTH_NEXT,
            LIB_XSTEALTH_BRIDGE,
            LIB_SHIZUPOSED,
            LIB_AMIRU,
            LIB_CALLSITE,
        };

        try {
            for (String lib : libs) {
                File apkCopy = new File(
                    getApplicationInfo().nativeLibraryDir, lib);
                if (!apkCopy.exists()) {
                    logger.d("nativeLibsPresentOnShell: " + lib
                        + " not in APK — skipping");
                    continue;
                }

                String shellPath = libsDir + "/" + lib;
                String apkHash = sha256OfFile(apkCopy);
                String shellHash = sha256OfShellFile(shellPath);

                if (apkHash == null) {
                    logger.d("nativeLibsPresentOnShell: cannot hash APK "
                        + lib + " — assuming present");
                    continue;
                }
                if (shellHash == null) {
                    logger.i("nativeLibsPresentOnShell: " + lib
                        + " missing on shell side");
                    return false;
                }
                if (!apkHash.equals(shellHash)) {
                    logger.i("nativeLibsPresentOnShell: " + lib
                        + " differs (apk=" + apkHash.substring(0, 8)
                        + ", shell=" + shellHash.substring(0, 8)
                        + ") — redeploy needed");
                    return false;
                }
            }
            logger.d("nativeLibsPresentOnShell: all libraries match");
            return true;

        } catch (Throwable t) {
            logger.w("nativeLibsPresentOnShell threw: " + t.getMessage());
            return false;
        }
    }

    private static String sha256OfFile(File f) {
        if (f == null || !f.exists() || !f.isFile()) return null;
        try (InputStream is = new FileInputStream(f)) {
            java.security.MessageDigest md =
                java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
            return hexOf(md.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    private String sha256OfShellFile(String path) {
        try {
            String script = "cat \"" + path
                + "\" 2>/dev/null | sha256sum | awk '{print $1}'";
            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '" + script.replace("'", "'\\''") + "'");
            if (r == null || !r.isSuccess()) return null;
            String out = r.getStdoutString();
            if (out == null) return null;
            out = out.trim();
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hexOf(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════
    // MODULE PUSH
    // ═════════════════════════════════════════════════════════════

    private boolean pushModulesToShellDir(List<ModuleInfo> modules) {
        if (!shizukuHelper.isAuthorized()) return false;

        synchronized (pushLock) {
            String base = resolveShellBaseDir();
            String modulesDir = shellPath(base, SUB_DIR_MODULES);

            try {
                shizukuHelper.executeCommand("mkdir -p " + modulesDir);
                shizukuHelper.executeCommand("chmod 755 " + base
                    + " " + modulesDir);

                int pushed = 0;
                int skipped = 0;
                for (ModuleInfo module : modules) {
                    if (module == null || !module.enabled) continue;

                    if (com.shizuposed.manager.stealth.XStealthModule.PACKAGE
                            .equals(module.packageName)) {
                        continue;
                    }

                    File localDex = module.cachedDexPath != null
                        ? new File(module.cachedDexPath) : null;
                    long srcMtime = localDex != null && localDex.exists()
                        ? localDex.lastModified() : 0L;
                    long srcSize  = localDex != null && localDex.exists()
                        ? localDex.length() : 0L;

                    PushedModuleEntry prev =
                        pushedModules.get(module.packageName);
                    if (prev != null
                            && prev.mtime == srcMtime
                            && prev.size == srcSize
                            && srcMtime > 0) {
                        skipped++;
                        continue;
                    }

                    String json = gson.toJson(module);
                    File localJson = new File(localStagingModulesDir,
                        module.packageName + ".json");
                    try (FileOutputStream fos =
                             new FileOutputStream(localJson)) {
                        fos.write(json.getBytes());
                        fos.flush();
                    }
                    localJson.setReadable(true, false);

                    String dstJson = modulesDir + "/"
                        + module.packageName + ".json";
                    String copyJson = ABS_SH + " -c 'cat \""
                        + localJson.getAbsolutePath()
                        + "\" > \"" + dstJson + "\"'";
                    ShellUtils.CommandResult r1 =
                        shizukuHelper.executeCommand(copyJson);
                    if (!r1.isSuccess()) {
                        logger.w("Failed to push JSON for "
                            + module.packageName + ": "
                            + r1.getStderrString());
                        continue;
                    }

                    if (localDex != null && localDex.exists()) {
                        String dexToPush = localDex.getAbsolutePath();
                        if (com.shizuposed.manager.stealth.XStealthPrefs
                                .isDexOptimizeEnabled(this)) {
                            String optimized =
                                com.shizuposed.manager.utils.DexOptimizeWrapper
                                    .optimize(this, shizukuHelper,
                                        localDex.getAbsolutePath(), logger);
                            if (optimized != null) {
                                dexToPush = optimized;
                            }
                        }

                        String dstDex = modulesDir + "/"
                            + module.packageName + ".dex";
                        String copyDex = ABS_SH + " -c 'cat \""
                            + dexToPush + "\" > \"" + dstDex + "\"'";
                        ShellUtils.CommandResult r2 =
                            shizukuHelper.executeCommand(copyDex);
                        if (r2.isSuccess()) {
                            shizukuHelper.executeCommand(
                                "chmod 644 " + dstDex);

                            String patchedJson = json.replace(
                                localDex.getAbsolutePath(), dstDex);
                            File patchedLocal =
                                new File(localStagingModulesDir,
                                    module.packageName + ".json");
                            try (FileOutputStream fos =
                                     new FileOutputStream(patchedLocal)) {
                                fos.write(patchedJson.getBytes());
                                fos.flush();
                            }
                            String copyPatched = ABS_SH + " -c 'cat \""
                                + patchedLocal.getAbsolutePath()
                                + "\" > \"" + dstJson + "\"'";
                            shizukuHelper.executeCommand(copyPatched);
                        }
                    }

                    if (srcMtime > 0) {
                        pushedModules.put(module.packageName,
                            new PushedModuleEntry(srcMtime, srcSize));
                    }

                    pushed++;
                }

                try {
                    com.shizuposed.manager.stealth.XStealthStatusWriter.push(
                        this, base, shizukuHelper, logger);
                } catch (Throwable t) {
                    logger.w("XStealth config push failed: "
                        + t.getMessage());
                }

                logger.i("Pushed " + pushed + " module(s), skipped "
                    + skipped + " unchanged (of " + modules.size() + ")");
                modulesPushed.set(true);
                modulesPushedAt = System.currentTimeMillis();
                return pushed > 0 || skipped > 0;

            } catch (Exception e) {
                logger.e("pushModulesToShellDir error: " + e.getMessage());
                return false;
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SERVICE STARTUP
    // ═════════════════════════════════════════════════════════════

    private void startServiceInternal() {
        if (!isRunning.compareAndSet(false, true)) return;

        if (workerHandler == null) {
            logger.w("startServiceInternal: worker not ready");
            isRunning.set(false);
            return;
        }

        workerHandler.post(() -> {
            try {
                logger.i("Starting ShizuPosed service...");

                List<ModuleInfo> modules = moduleLoader.loadModules();
                logger.i("Loaded " + modules.size() + " modules");

                if (shizukuHelper.isAuthorized()) {
                    logger.i("Shizuku authorized at startup — "
                        + "deploying payload...");
                    if (ensurePayloadReady()) {
                        pushModulesToShellDir(modules);
                    }
                } else {
                    logger.i("Shizuku not yet authorized — "
                        + "deploy deferred");
                }

                processMonitor.startMonitoring();

                logger.i("ShizuPosed service started successfully");
                updateNotification("Service running");
            } catch (Exception e) {
                logger.e("Failed to start service: " + e.getMessage());
                isRunning.set(false);
                updateNotification("Service error: " + e.getMessage());
            }
        });
    }

    private void prepareXposedHook() {
        if (!xposedHookStarted.compareAndSet(false, true)) return;

        if (!shizukuHelper.isAuthorized()) {
            logger.w("Shizuku not authorized, cannot prepare XposedHook");
            xposedHookStarted.set(false);
            return;
        }
        if (!ensurePayloadReady()) {
            logger.e("Cannot prepare XposedHook without deployed payload");
            xposedHookStarted.set(false);
            return;
        }
        if (!pushModulesToShellDir(moduleLoader.loadModules())) {
            logger.w("No modules pushed to shell dir (nothing will hook)");
        }

        logger.i("XposedHook ready (launcher will run on demand)");
        updateNotification("Ready");
    }

    public void onShizukuAuthorized() {
        if (!onAuthorizedDispatched.compareAndSet(false, true)) {
            if (logger != null) {
                logger.d("onShizukuAuthorized() already dispatched — "
                    + "skipping");
            }
            return;
        }
        logger.i("onShizukuAuthorized() — deploying now");
        if (workerHandler == null) {
            onAuthorizedDispatched.set(false);
            return;
        }
        workerHandler.post(() -> {
            try {
                if (ensurePayloadReady()) {
                    prepareXposedHook();
                }
                startServiceInternal();
            } catch (Throwable t) {
                logger.e("onShizukuAuthorized worker failed: "
                    + t.getMessage());
                onAuthorizedDispatched.set(false);
            }
        });
    }

    public void resetAuthorizedDispatch() {
        onAuthorizedDispatched.set(false);
    }

    private boolean isMarkerPresent(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;

        try {
            String base = resolveShellBaseDir();
            String marker = shellPath(base, SUB_DIR_HOOKED)
                + "/" + packageName + ".json";
            String script = "test -f \"" + marker
                + "\" && echo YES || echo NO";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '"
                + script.replace("'", "'\\''") + "'");

            return r != null && r.isSuccess()
                && r.getStdoutString() != null
                && r.getStdoutString().contains("YES");
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("isMarkerPresent(" + packageName + ") failed: "
                    + t.getMessage());
            }
            return false;
        }
    }

    private boolean isTargetProcessPresent(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;

        final String needle =
            "com.shizuposed.manager.core.XposedHook " + packageName;

        try {
            String script =
                "command -v pgrep >/dev/null 2>&1 "
                + "&& pgrep -f -- '" + needle + "' >/dev/null 2>&1 "
                + "&& echo FOUND || true";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '" + script.replace("'", "'\\''") + "'");

            if (r != null && r.getStdoutString() != null
                    && r.getStdoutString().contains("FOUND")) {
                return true;
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("isTargetProcessPresent: pgrep tier failed: "
                    + t.getMessage());
            }
        }

        try {
            String script =
                "for p in $(ps -A -o PID,NAME 2>/dev/null "
                +     "| grep -E 'app_process(64)?' "
                +     "| grep -v grep "
                +     "| awk '{print $1}'); do "
                + "  [ -r \"/proc/$p/cmdline\" ] || continue; "
                + "  if tr '\\0' ' ' < \"/proc/$p/cmdline\" 2>/dev/null "
                + "     | grep -qF -- '" + needle + "'; then "
                + "    echo FOUND; exit 0; "
                + "  fi; "
                + "done; "
                + "echo NOT_FOUND";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '" + script.replace("'", "'\\''") + "'");

            if (r != null && r.getStdoutString() != null) {
                String out = r.getStdoutString();
                if (out.contains("FOUND") && !out.contains("NOT_FOUND")) {
                    return true;
                }
                if (out.contains("NOT_FOUND")) {
                    return false;
                }
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("isTargetProcessPresent: ps tier failed: "
                    + t.getMessage());
            }
        }

        try {
            String script =
                "for d in /proc/[0-9]*; do "
                + "  [ -r \"$d/cmdline\" ] || continue; "
                + "  if tr '\\0' ' ' < \"$d/cmdline\" 2>/dev/null "
                + "     | grep -qF -- '" + needle + "'; then "
                + "    echo FOUND; exit 0; "
                + "  fi; "
                + "done; "
                + "echo NOT_FOUND";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '" + script.replace("'", "'\\''") + "'");

            if (r != null && r.getStdoutString() != null) {
                return r.getStdoutString().contains("FOUND");
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("isTargetProcessPresent: /proc tier failed: "
                    + t.getMessage());
            }
        }

        return false;
    }

    private int countTargetProcesses(String packageName) {
        if (packageName == null || packageName.isEmpty()) return 0;
        try {
            String script =
                "ps -A -o ARGS 2>/dev/null "
                + "| grep -F -- ' " + packageName + "' "
                + "| grep -Fv ' grep ' "
                + "| wc -l";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '"
                + script.replace("'", "'\\''") + "'");

            if (r == null || r.getStdoutString() == null) return 0;
            String out = r.getStdoutString().trim();
            if (out.isEmpty()) return 0;
            return Integer.parseInt(out);
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("countTargetProcesses(" + packageName + ") failed: "
                    + t.getMessage());
            }
            return 0;
        }
    }

    private String resolveLauncherComponent(String packageName) {
        try {
            String cmd = "cmd package resolve-activity --brief "
                + "-a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER "
                + packageName;
            ShellUtils.CommandResult r =
                shizukuHelper.executeCommand(cmd);
            return parseResolvedComponent(r);
        } catch (Throwable t) {
            logger.w("resolveLauncherComponent threw: "
                + t.getMessage());
            return null;
        }
    }

    private String resolveMainComponent(String packageName) {
        try {
            String cmd = "cmd package resolve-activity --brief "
                + "-a android.intent.action.MAIN "
                + packageName;
            ShellUtils.CommandResult r =
                shizukuHelper.executeCommand(cmd);
            return parseResolvedComponent(r);
        } catch (Throwable t) {
            logger.w("resolveMainComponent threw: " + t.getMessage());
            return null;
        }
    }

    private String parseResolvedComponent(ShellUtils.CommandResult r) {
        if (r == null || !r.isSuccess()
                || r.getStdoutString() == null) {
            return null;
        }
        String resolved = r.getStdoutString().trim();

        for (String token : resolved.split("\\s+")) {
            String t = token.trim();
            if (t.contains("/")
                    && !t.startsWith("priority=")
                    && !t.startsWith("Intent")
                    && !t.startsWith("-")) {
                return t;
            }
        }

        for (String line : resolved.split("\n")) {
            String t = line.trim();
            if (t.contains("/")
                    && !t.startsWith("priority=")
                    && !t.startsWith("Intent")
                    && !t.startsWith("-")) {
                return t;
            }
        }

        logger.d("parseResolvedComponent: no component in output: "
            + resolved);
        return null;
    }

    public boolean launchAppUnderShizuPosed(String packageName,
                                             AtomicBoolean sent) {
        return launchAppUnderShizuPosed(packageName, sent, true, true);
    }

    public boolean launchAppUnderShizuPosed(String packageName,
                                             AtomicBoolean sent,
                                             boolean openUi) {
        return launchAppUnderShizuPosed(packageName, sent, openUi, true);
    }

    public boolean launchAppUnderShizuPosed(String packageName,
                                             AtomicBoolean sent,
                                             boolean openUi,
                                             boolean notifyUi) {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("Shizuku not authorized, cannot launch "
                + packageName);
            if (notifyUi) {
                broadcastOnce(packageName, sent, false,
                    "Shizuku not authorized");
            }
            return false;
        }
        if (!ensurePayloadReady()) {
            logger.e("Cannot launch " + packageName
                + ": payload not deployed");
            if (notifyUi) {
                broadcastOnce(packageName, sent, false,
                    "payload not deployed");
            }
            return false;
        }

        if (!modulesPushed.get()) {
            if (logger != null) {
                logger.i("launch: modules not yet pushed, pushing now");
            }
            pushModulesToShellDir(moduleLoader.loadModules());
        } else {
            if (logger != null) {
                logger.d("launch: modules already pushed (age="
                    + (System.currentTimeMillis() - modulesPushedAt)
                    + "ms)");
            }
        }

        String binary = shizukuHelper.getAppProcessBinary();
        if (binary == null) {
            logger.e("No usable app_process binary on this ROM.");
            updateNotification("app_process unavailable");
            if (notifyUi) {
                broadcastOnce(packageName, sent, false,
                    "no app_process binary");
            }
            return false;
        }

        String base    = resolveShellBaseDir();
        String dexPath = shellFile(base, DEX_NAME);
        String libsDir = shellPath(base, SUB_DIR_LIBS);
        String logPath = shellFile(base, LAUNCH_LOG);

        try {
            int targetUid = -1;
            try {
                ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(
                        packageName, 0);
                targetUid = ai.uid;
            } catch (Throwable ignored) {}

            int hookDelayMs = RuntimePrefs.getHookDelayMs(this);

            if (openUi) {
                try {
                    logger.i("Force-stopping " + packageName
                        + " before routed launch");
                    shizukuHelper.executeCommand(
                        "am force-stop " + packageName);
                    Thread.sleep(300);
                } catch (Throwable t) {
                    logger.w("force-stop failed for " + packageName
                        + ": " + t.getMessage());
                }
            }

            int beforeCount = countTargetProcesses(packageName);

            String spawnPrefix = buildSpawnPrefix();

            String inner =
                "unset CLASSPATH; unset BOOTCLASSPATH; "
                + "( "
                +   (spawnPrefix.isEmpty() ? "" : spawnPrefix + " ")
                +   binary + " "
                +   "-Xverify:none "
                +   "-Xhidden-api-policy:enabled "
                +   "-Xcore-platform-api-policy:enabled "
                +   "-Djava.class.path=" + dexPath + " "
                +   "-Dshizuposed.shell.base=" + base + " "
                +   "-Dshizuposed.shell.libs=" + libsDir + " "
                +   "-Dshizuposed.hook.delay=" + hookDelayMs + " "
                +   "/system/bin "
                +   "com.shizuposed.manager.core.XposedHook "
                +   packageName + " 0 " + targetUid
                +   " </dev/null >>" + logPath + " 2>&1 "
                + "& ) "
                + ">/dev/null 2>&1 < /dev/null &";

            String cmd = ABS_SH + " -c '"
                + inner.replace("'", "'\\''") + "'";

            logger.i("Launching " + packageName + " under ShizuPosed"
                + " (openUi=" + openUi
                + ", notifyUi=" + notifyUi
                + ", binary=" + binary
                + ", uid=" + targetUid
                + ", spawn=" + (spawnPrefix.isEmpty()
                    ? "bare" : spawnPrefix)
                + ", base=" + base + ")");

            ShellUtils.CommandResult result =
                shizukuHelper.executeCommand(cmd);
            if (result == null || !result.isSuccess()) {
                String err = (result != null)
                    ? result.getStderrString() : "null result";
                logger.e(binary + " launch failed: " + err);
                if (err != null && err.contains("not found")) {
                    logger.e("Spawn tool missing — check spawn-tool "
                        + "probe output above. setsid=" + sSetsidAvailable
                        + " nohup=" + sNohupAvailable);
                }
                if (notifyUi) {
                    broadcastOnce(packageName, sent, false, err);
                }
                return false;
            }

            logger.i(binary + " spawn accepted for " + packageName);

            long waitStart = System.currentTimeMillis();
            long deadline = waitStart + BOOTSTRAP_MAX_WAIT_MS;
            boolean markerSeen = false;
            boolean processSeen = false;
            long firstProcessAt = 0L;
            long firstMarkerAt = 0L;

            try {
                Thread.sleep(BOOTSTRAP_MIN_WAIT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            while (System.currentTimeMillis() < deadline) {
                if (!processSeen) {
                    processSeen = isTargetProcessPresent(packageName);
                    if (processSeen) firstProcessAt = System.currentTimeMillis();
                }
                if (!markerSeen) {
                    markerSeen = isMarkerPresent(packageName);
                    if (markerSeen) firstMarkerAt = System.currentTimeMillis();
                }

                if (markerSeen) break;

                if (processSeen
                        && (System.currentTimeMillis() - firstProcessAt) > 2000L) {
                    break;
                }

                try {
                    Thread.sleep(BOOTSTRAP_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            long elapsed = System.currentTimeMillis() - waitStart;
            if (markerSeen) {
                logger.i("Bootstrap ready for " + packageName
                    + " (marker at " + (firstMarkerAt - waitStart)
                    + "ms, waited " + elapsed + "ms)");
            } else if (processSeen) {
                logger.i("Bootstrap process up for " + packageName
                    + " (process at " + (firstProcessAt - waitStart)
                    + "ms, no marker after " + elapsed
                    + "ms — proceeding; app may have no applicable modules)");
            } else {
                logger.w("Bootstrap not detected for " + packageName
                    + " after " + elapsed
                    + "ms — proceeding anyway. Check launch.log for "
                    + "bootstrap output.");
            }

            if (!openUi) {
                logger.i("Spawn-only launch for " + packageName
                    + " — hooks installed, UI not opened"
                    + (notifyUi ? "" : ", no broadcast"));
                if (notifyUi) {
                    broadcastOnce(packageName, sent, true, null);
                }
                return true;
            }

            String component = resolveLauncherComponent(packageName);
            if (component == null) {
                component = resolveMainComponent(packageName);
            }

            boolean started = false;
            String startMethod = "none";

            if (component != null) {
                String compCmd = "am start --user current -n "
                    + component + " >/dev/null 2>&1";
                ShellUtils.CommandResult compResult =
                    shizukuHelper.executeCommand(compCmd);
                if (compResult != null && compResult.isSuccess()) {
                    started = true;
                    startMethod = "component:" + component;
                } else {
                    logger.w("am start -n " + component
                        + " failed for " + packageName);
                }
            }

            if (!started) {
                String startCmd = "am start --user current "
                    + "-a android.intent.action.MAIN "
                    + "-c android.intent.category.LAUNCHER "
                    + "-p " + packageName
                    + " >/dev/null 2>&1";
                ShellUtils.CommandResult startResult =
                    shizukuHelper.executeCommand(startCmd);
                if (startResult != null && startResult.isSuccess()) {
                    started = true;
                    startMethod = "launcher-intent-fallback";
                }
            }

            if (started) {
                logger.i("UI opened for " + packageName
                    + " via " + startMethod);

                try {
                    int afterCount = countTargetProcesses(packageName);
                    if (afterCount > beforeCount + 1) {
                        logger.w("AM START created a second process for "
                            + packageName + " (before=" + beforeCount
                            + ", after=" + afterCount
                            + "). AMS did not route into the bootstrapped "
                            + "process; the UI is running without hooks.");
                    } else {
                        logger.i("Process count stable for " + packageName
                            + " (before=" + beforeCount
                            + ", after=" + afterCount + ")");
                    }
                } catch (Throwable t) {
                    logger.d("post-am-start process check failed: "
                        + t.getMessage());
                }

                if (notifyUi) {
                    broadcastOnce(packageName, sent, true, null);
                }
                return true;
            } else {
                logger.w("Process for " + packageName
                    + " is running, but no UI could be started. "
                    + "Module may have no launchable activity.");
                if (notifyUi) {
                    broadcastOnce(packageName, sent, true,
                        "process running (no launchable UI)");
                }
                return true;
            }

        } catch (Exception e) {
            logger.e("launchAppUnderShizuPosed error: "
                + e.getMessage());
            if (notifyUi) {
                broadcastOnce(packageName, sent, false, e.getMessage());
            }
            return false;
        }
    }

    public boolean injectProcess(String packageName, int pid, int uid,
                                 List<ModuleInfo> modules) {
        if (packageName == null || packageName.isEmpty()) return false;

        if (!isRunning.get() || shizukuHelper == null
                || !shizukuHelper.isAuthorized()) {
            if (logger != null) {
                logger.d("injectProcess: skipped " + packageName
                    + " — service not ready or Shizuku unauthorized");
            }
            return false;
        }
        if (!ensurePayloadReady()) {
            if (logger != null) {
                logger.d("injectProcess: skipped " + packageName
                    + " — payload not deployed");
            }
            return false;
        }

        if (uid < 10000) {
            if (logger != null) {
                logger.d("injectProcess: " + packageName
                    + " has uid " + uid
                    + " (protected/reserved) — skipping");
            }
            return false;
        }

        if (!isPackageScoped(packageName)) {
            if (logger != null) {
                logger.v("injectProcess: " + packageName
                    + " not in any module's scope — skipping");
            }
            return false;
        }

        if (!inFlightLaunches.add(packageName)) {
            if (logger != null) {
                logger.d("injectProcess: " + packageName
                    + " launch already in flight — skipping");
            }
            return false;
        }

        try {
            if (isPackageAlreadyHooked(packageName)) {
                if (logger != null) {
                    logger.d("injectProcess: " + packageName
                        + " already has a hooked marker — skipping");
                }
                return false;
            }

            if (logger != null) {
                logger.i("injectProcess: spawning (no UI) for "
                    + packageName + " (detected pid=" + pid
                    + ", uid=" + uid + ")");
            }

            AtomicBoolean sent = new AtomicBoolean(false);
            boolean ok = launchAppUnderShizuPosed(
                packageName, sent, false, false);
            if (!ok && logger != null) {
                logger.w("injectProcess: spawn-only launch returned false for "
                    + packageName + " — see Logs tab");
            }
            return ok;

        } catch (Throwable t) {
            if (logger != null) {
                logger.e("injectProcess threw for " + packageName + ": "
                    + t.getMessage());
            }
            return false;
        } finally {
            inFlightLaunches.remove(packageName);
        }
    }

    private boolean isPackageScoped(String packageName) {
        if (packageName == null) return false;
        try {
            List<ModuleInfo> enabled =
                ModuleLoader.getInstance(this).getEnabledModules();
            if (enabled == null || enabled.isEmpty()) return false;
            for (ModuleInfo m : enabled) {
                if (m == null || !m.enabled) continue;
                if (m.isAppHooked(packageName)) return true;
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("isPackageScoped(" + packageName + ") failed: "
                    + t.getMessage());
            }
        }
        return false;
    }

    private boolean isPackageAlreadyHooked(String packageName) {
        if (packageName == null) return false;

        try {
            Map<String, String> mirror = MarkerCache.read(this);
            if (mirror != null && mirror.containsKey(packageName)) {
                return true;
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("isPackageAlreadyHooked: mirror read failed: "
                    + t.getMessage());
            }
        }

        try {
            String base = resolveShellBaseDir();
            String marker = shellPath(base, SUB_DIR_HOOKED)
                + "/" + packageName + ".json";
            String check = "test -s \"" + marker
                + "\" && echo YES || echo NO";
            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '" + check.replace("'", "'\\''") + "'");
            return r != null && r.isSuccess()
                && r.getStdoutString() != null
                && r.getStdoutString().contains("YES");
        } catch (Throwable t) {
            if (logger != null) {
                logger.d("isPackageAlreadyHooked(" + packageName
                    + ") shell check failed: " + t.getMessage());
            }
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═════════════════════════════════════════════════════════════

    public boolean isRunning() { return isRunning.get(); }
    public boolean isXposedHookStarted() { return xposedHookStarted.get(); }
    public boolean isDexDeployed() { return dexDeployed.get(); }
    public boolean isLibsDeployed() { return libsDeployed.get(); }
    public static boolean isServiceRunning() { return isServiceRunning; }
}