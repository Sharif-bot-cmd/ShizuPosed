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
import com.shizuposed.manager.stealth.XStealthModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the ShizuPosed lifecycle.
 *
 * LAUNCH MODEL
 * ------------
 * There are TWO launch paths, sharing one primitive.
 *
 * 1. Routed launch (user-triggered): the "Launch App under
 *    ShizuPosed" button, and the long-press / "Open module app"
 *    entries that call it. Two steps:
 *
 *      a. Boot the target process with hooks installed:
 *           unset CLASSPATH; unset BOOTCLASSPATH;
 *           <prefix> app_process ... XposedHook <pkg> 0 <uid> &
 *      b. Open the target's UI in that process:
 *           am start --user current -n <resolved-component>
 *
 *    This is the path a self-hooking module needs: its own UI
 *    comes up in the process where its hook is installed, so its
 *    self-check passes.
 *
 * 2. Monitor-triggered injection: ProcessMonitor sees a scoped
 *    app start and calls injectProcess. That spawns the target
 *    (step a) but does NOT run `am start` (step b). The user
 *    opens the app themselves; the hooks are live when they do.
 *    Step b would be wrong here — it would pop the app to the
 *    foreground a second time, on top of the user's own launch.
 *
 * Both paths are the same `app_process` invocation. They differ
 * only in whether the second step runs, which is what the
 * `openUi` flag on launchAppUnderShizuPosed controls.
 *
 * ENVIRONMENT SANITIZATION
 * ------------------------
 * The spawn command unsets CLASSPATH and BOOTCLASSPATH before
 * invoking app_process. Both variables are read by app_process
 * *before* the Java runtime starts, and both override the
 * framework's expected classpath. Some Shizuku implementations
 * (notably Shevery) export CLASSPATH=<their own APK> into the
 * shell environment, which causes every spawn to abort with:
 *
 *   Native registration unable to find class
 *   'com/android/internal/os/RuntimeInit'; aborting...
 *
 * Unsetting both forces app_process to use its compiled-in
 * defaults. The unset applies only to the subshell that spawns
 * app_process; it does not affect the Shizuku shell or any other
 * process.
 *
 * ART POLICY FLAGS
 * ----------------
 * Two ART flags are passed to every spawned app_process:
 *
 *   -Xhidden-api-policy:enabled
 *   -Xcore-platform-api-policy:enabled
 *
 * These relax hidden-API enforcement for the spawned process,
 * which runs as the target's UID. XposedHook needs to reach into
 * ActivityThread, ApplicationInfo, and other non-SDK internals
 * to install hooks; without these flags, those calls throw
 * NoSuchMethodError / NoSuchFieldError.
 *
 * AUTHORIZATION MODEL
 * -------------------
 * The service defers authorization failures instead of stopping
 * itself. On some devices Shizuku's Binder becomes momentarily
 * unresponsive, and a naive auth check fails. Stopping the service
 * on that transient failure would tear down the notification and
 * the running lifecycle. Instead, we:
 *
 *   • Set up the worker infrastructure on the first call,
 *     regardless of auth state.
 *   • Defer payload deployment until Shizuku responds.
 *   • Resume when the ACTION_SHIZUKU_AUTHORIZED broadcast arrives.
 *
 * The service never self-terminates on a transient auth failure.
 * It only stops if the user explicitly stops it, or if Android
 * kills it for resource reasons.
 *
 * AUTO-START ON SHIZUKU RETURN
 * ----------------------------
 * Shizuku-Next's "ADB without Developer options" switch takes
 * Shizuku's server down while a target app is in the foreground
 * and brings it back when the app exits. During that window,
 * ShizuPosed's binder connection drops and the service stops.
 *
 * ShizukuBinderWatcher — a no-op ContentProvider registered in
 * the manifest — registers Shizuku binder-received and
 * binder-dead listeners at process start, and when the binder
 * returns it fires ACTION_ENSURE_RUNNING at this service. The
 * handler is idempotent.
 */
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

    /** Delay between app_process spawn and am start. */
    private static final long BOOTSTRAP_DELAY_MS = 1500L;

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

        if (sSetsidAvailable && sNohupAvailable) {
            return ABS_SETSID + " " + ABS_NOHUP;
        }
        if (sSetsidAvailable) {
            return ABS_SETSID;
        }
        if (sNohupAvailable) {
            return ABS_NOHUP;
        }

        logger.w("Neither setsid nor nohup available — using bare spawn. "
            + "The target process may be killed when the shell exits.");
        return "";
    }

    private String buildDisownSuffix() {
        if (!sSpawnFormResolved) probeSpawnTools();
        return sDisownAvailable ? " disown" : "";
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

        // Set up the worker infrastructure regardless of auth
        // state. If Shizuku isn't responding yet, the worker sits
        // idle and waits for ACTION_SHIZUKU_AUTHORIZED to trigger
        // the deploy. The service never self-stops on a transient
        // auth failure.
        if (REQUIRE_AUTHORIZATION && !isAuthorizedNow()) {
            logger.w("onCreate: Shizuku not currently responsive — "
                + "deferring deploy; will resume on authorization");
        }

        setupWorkerInfrastructure();
    }

    /**
     * Set up the worker thread, launch executor, and component
     * references. Idempotent: safe to call multiple times.
     */
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
                                // The button path opens the UI. This
                                // is what a self-hooking module needs.
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

    /**
     * Idempotent entry point for "make sure the service's worker
     * path is running with a live Shizuku connection."
     *
     * Called from onStartCommand when ACTION_ENSURE_RUNNING
     * arrives. Three cases:
     *
     *   1. Worker ready and Shizuku authorized → re-run the
     *      payload readiness and startup sequence.
     *   2. Worker ready but Shizuku not authorized → do nothing;
     *      ACTION_SHIZUKU_AUTHORIZED handles it.
     *   3. Worker not yet set up → setupWorkerInfrastructure()
     *      will set it up.
     */
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
            if (staged.exists() && staged.length() > 0) {
                staged.setReadable(true, false);
                return true;
            }

            File parent = staged.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            logger.i("Staging XposedHook.dex from assets to "
                + localStagingDexPath);

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
                logger.i("XposedHook.dex staged! Size: "
                    + total + " bytes");
                staged.setReadable(true, false);
                return true;
            }
        } catch (Exception e) {
            logger.e("stageDexLocally error: " + e.getMessage());
            return false;
        }
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
            if (dst.exists() && dst.length() == src.length()) {
                dst.setReadable(true, false);
                return dst;
            }

            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                out.flush();
                logger.i("Staged " + libName + " (" + total + " bytes)");
            }
            dst.setReadable(true, false);
            return dst;
        } catch (Throwable t) {
            logger.e("stageNativeLib(" + libName + ") failed: "
                + t.getMessage());
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
                + " " + modulesDir
                + " " + hookedDir);

            logger.i("XposedHook.dex deployed to: " + dexPath);
            dexDeployed.set(true);
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
        if (dexDeployed.get()) return true;
        if (!shizukuHelper.isAuthorized()) {
            logger.w("ensureDexReady called but Shizuku not "
                + "authorized yet");
            return false;
        }
        return deployDexToShellDir();
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

        if (libsDeployed.get()) {
            if (logger != null) {
                logger.d("ensurePayloadReady: libs already deployed (flag)");
            }
            return true;
        }

        if (logger != null) {
            logger.i("ensurePayloadReady: checking shell for native libs...");
        }
        if (nativeLibsPresentOnShell()) {
            libsDeployed.set(true);
            if (logger != null) {
                logger.i("ensurePayloadReady: libs present on shell — "
                    + "skipping deploy");
            }
            return true;
        }

        if (logger != null) {
            logger.i("ensurePayloadReady: deploying native libs "
                + "(this takes a few seconds)...");
        }
        boolean ok = deployNativeLibsToShellDir();
        if (logger != null) {
            logger.i("ensurePayloadReady: native lib deploy result=" + ok);
        }
        return ok;
    }

    private boolean nativeLibsPresentOnShell() {
        String base = resolveShellBaseDir();
        String libsDir = shellPath(base, SUB_DIR_LIBS);
        String check =
            "test -s " + libsDir + "/" + LIB_XSTEALTH
            + " && test -s " + libsDir + "/" + LIB_SHIZUPOSED
            + " && echo READY";
        try {
            ShellUtils.CommandResult r =
                shizukuHelper.executeCommand(check);
            boolean present = r != null && r.isSuccess()
                && r.getStdoutString() != null
                && r.getStdoutString().contains("READY");
            if (logger != null) {
                logger.d("nativeLibsPresentOnShell: " + present);
            }
            return present;
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("nativeLibsPresentOnShell threw: "
                    + t.getMessage());
            }
            return false;
        }
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

    // ═════════════════════════════════════════════════════════════
    // LAUNCH UNDER SHIZUPOSED
    // ═════════════════════════════════════════════════════════════

    private boolean isTargetProcessPresent(String packageName) {
        try {
            String script =
                "for d in /proc/[0-9]*; do "
                + "  [ -r \"$d/cmdline\" ] || continue; "
                + "  if tr '\\0' '\\n' < \"$d/cmdline\" 2>/dev/null "
                + "     | grep -qxF '" + packageName + "'; then "
                + "    echo FOUND; exit 0; "
                + "  fi; "
                + "done; "
                + "echo NOT_FOUND";

            ShellUtils.CommandResult r = shizukuHelper.executeCommand(
                ABS_SH + " -c '"
                + script.replace("'", "'\\''") + "'");

            return r != null
                && r.getStdoutString() != null
                && r.getStdoutString().contains("FOUND");
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("isTargetProcessPresent threw: "
                    + t.getMessage());
            }
            return false;
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

    /**
     * Routed launch. The one-argument form opens the UI (the
     * button path). The two-argument form chooses.
     */
    public boolean launchAppUnderShizuPosed(String packageName,
                                             AtomicBoolean sent) {
        return launchAppUnderShizuPosed(packageName, sent, true);
    }

    /**
     * Variant with an explicit openUi flag.
     *
     * openUi=true  — the full two-step routed launch: spawn the
     *                process (hooks installed during bootstrap)
     *                AND `am start` the target's UI. This is the
     *                "Launch App under ShizuPosed" button and the
     *                "Open module app" / long-press entries. A
     *                self-hooking module needs this: its own UI
     *                comes up in the process where its hook is
     *                installed.
     *
     * openUi=false — spawn-only: install hooks in the bootstrapped
     *                process but do NOT open any activity. Used by
     *                the monitor-triggered path so a scoped app the
     *                user opens themselves gets hooks without the
     *                framework also opening it a second time.
     */
    public boolean launchAppUnderShizuPosed(String packageName,
                                             AtomicBoolean sent,
                                             boolean openUi) {
        if (!shizukuHelper.isAuthorized()) {
            logger.w("Shizuku not authorized, cannot launch "
                + packageName);
            return false;
        }
        if (!ensurePayloadReady()) {
            logger.e("Cannot launch " + packageName
                + ": payload not deployed");
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

            String spawnPrefix = buildSpawnPrefix();
            String disownSuffix = buildDisownSuffix();

            String inner =
                "unset CLASSPATH; unset BOOTCLASSPATH; "
                + (spawnPrefix.isEmpty() ? "" : spawnPrefix + " ")
                + binary + " "
                + "-Xverify:none "
                + "-Xhidden-api-policy:enabled "
                + "-Xcore-platform-api-policy:enabled "
                + "-Djava.class.path=" + dexPath + " "
                + "-Dshizuposed.shell.base=" + base + " "
                + "-Dshizuposed.shell.libs=" + libsDir + " "
                + "-Dshizuposed.hook.delay=" + hookDelayMs + " "
                + "/system/bin "
                + "com.shizuposed.manager.core.XposedHook "
                + packageName + " 0 " + targetUid
                + " </dev/null >>" + logPath + " 2>&1 &"
                + disownSuffix;

            String cmd = ABS_SH + " -c '"
                + inner.replace("'", "'\\''") + "'";

            logger.i("Launching " + packageName + " under ShizuPosed"
                + " (openUi=" + openUi
                + ", binary=" + binary
                + ", uid=" + targetUid
                + ", spawn=" + (spawnPrefix.isEmpty()
                    ? "bare" : spawnPrefix)
                + (disownSuffix.isEmpty() ? "" : " + disown")
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
                return false;
            }

            logger.i(binary + " spawn accepted for " + packageName);

            try {
                Thread.sleep(BOOTSTRAP_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            boolean processPresent =
                isTargetProcessPresent(packageName);
            if (processPresent) {
                logger.i("Post-spawn check: process present for "
                    + packageName);
            } else {
                logger.w("Post-spawn check: process for " + packageName
                    + " not found — proceeding anyway. "
                    + "Check launch.log for bootstrap output.");
            }

            // ── Step 2: open the UI ─────────────────────────────
            //
            // Skipped entirely when openUi=false. In that mode the
            // process is left running with hooks live, and the user
            // opens the app themselves. Nothing else in the method
            // depends on step 2 having run.
            if (!openUi) {
                logger.i("Spawn-only launch for " + packageName
                    + " — hooks installed, UI not opened");
                broadcastOnce(packageName, sent, true, null);
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
                broadcastOnce(packageName, sent, true, null);
                return true;
            } else {
                logger.w("Process for " + packageName
                    + " is running, but no UI could be started. "
                    + "Module may have no launchable activity.");
                broadcastOnce(packageName, sent, true,
                    "process running (no launchable UI)");
                return true;
            }

        } catch (Exception e) {
            logger.e("launchAppUnderShizuPosed error: "
                + e.getMessage());
            return false;
        }
    }

    /**
     * Monitor-triggered injection into a detected app process.
     *
     * Called by ProcessMonitor for every newly-seen app process
     * that has at least one enabled module. The (packageName, pid,
     * uid) tuple describes a process the user launched normally,
     * NOT one ShizuPosed started.
     *
     * WHAT THIS DOES
     * --------------
     * Without root there is no way to attach a JVM to a process
     * that is already running. app_process starts a NEW process;
     * it does not inject into the pid passed here. So this method
     * performs the only non-root injection the framework actually
     * supports: a spawn-only routed launch (openUi=false) of the
     * package. The bootstrapped process comes up with hooks
     * installed, and the user's own tap opens the UI into it.
     *
     * The pid argument is used for logging and for dedup only. It
     * is not the process hooks land in.
     *
     * GUARDS
     * ------
     *   1. Prerequisites. Shizuku authorized + service running +
     *      payload deployed. If any is false, skip silently — the
     *      monitor will call again next cycle.
     *
     *   2. UID. The shell UID (2000) can setuid to ordinary app
     *      UIDs (>= 10000) — the normal app_process target range,
     *      which includes system apps that the OEM assigned a
     *      dedicated uid. It cannot setuid to SYSTEM_UID (1000) or
     *      the reserved system UID pool (1001-1999). So: refuse
     *      uid < 10000. This is a uid check, not a name check.
     *
     *   3. Scope. Only packages that at least one enabled module
     *      actually scopes are launched.
     *
     *   4. Dedup. A package that already has a shell-side hooked
     *      marker, or that is already being launched by this
     *      service, is skipped.
     */
    public boolean injectProcess(String packageName, int pid, int uid,
                                 List<ModuleInfo> modules) {
        if (packageName == null || packageName.isEmpty()) return false;

        // Guard 1: prerequisites.
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

        // Guard 2: uid. Only ordinary app UIDs (>= 10000) are
        // assumable by the shell.
        if (uid < 10000) {
            if (logger != null) {
                logger.d("injectProcess: " + packageName
                    + " has uid " + uid
                    + " (protected/reserved) — skipping");
            }
            return false;
        }

        // Guard 3: scope.
        if (!isPackageScoped(packageName)) {
            if (logger != null) {
                logger.v("injectProcess: " + packageName
                    + " not in any module's scope — skipping");
            }
            return false;
        }

        // Guard 4: in-flight dedup.
        if (!inFlightLaunches.add(packageName)) {
            if (logger != null) {
                logger.d("injectProcess: " + packageName
                    + " launch already in flight — skipping");
            }
            return false;
        }

        try {
            // Guard 5: already hooked.
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

            // openUi=false — the monitor must not open the app.
            // The user opens it; hooks are already live by then.
            AtomicBoolean sent = new AtomicBoolean(false);
            boolean ok = launchAppUnderShizuPosed(packageName, sent, false);
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

    /**
     * True if any enabled module's scope contains this package.
     * Mirrors XposedHook.shouldHookApp() and ModuleInfo.isAppHooked()
     * so the service and the in-process injector agree on scope.
     */
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

    /**
     * True if a hooked marker exists for this package. Reads the
     * local mirror first (no IPC); falls back to a single shell
     * `test -s` on miss. Used to avoid relaunching an app whose
     * hooks are already live.
     */
    private boolean isPackageAlreadyHooked(String packageName) {
        if (packageName == null) return false;

        // Local mirror first.
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

        // Fall back to a single shell check.
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