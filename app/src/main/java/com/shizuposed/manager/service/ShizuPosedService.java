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
 * LAUNCH MODEL (7.3)
 * ------------------
 * A routed launch has TWO steps, not one:
 *
 *   1. Boot the target process with hooks installed:
 *        unset CLASSPATH; unset BOOTCLASSPATH;
 *        <prefix> app_process ... XposedHook <pkg> 0 <uid> & [disown]
 *      where <prefix> is chosen at runtime based on which spawn
 *      tools are available on the device:
 *        • /system/bin/setsid /system/bin/nohup   (preferred)
 *        • /system/bin/nohup                      (setsid missing)
 *        • (bare, no wrapper)                     (both missing)
 *      If the shell supports `disown`, it is appended after the
 *      backgrounded command to remove the job from the shell's job
 *      table. This is redundant when setsid is used (setsid already
 *      detaches), but harmless and covers the nohup-only path.
 *      This starts the process, installs hooks during bootstrap,
 *      and leaves the process idle with hooks live.
 *
 *   2. Open the target's UI in that already-running process:
 *        am start --user current -n <resolved-component>
 *      This tells ActivityManager to launch the module's UI. The
 *      activity runs in the process we just booted, so the hooks
 *      installed in step 1 are live.
 *
 * Step 2 is REQUIRED. app_process booting a process does not start
 * any activity. Without step 2, the process runs idle and the user
 * sees nothing. That was the "launched but nothing opens" bug.
 *
 * Step 2 prefers an explicit component over an implicit launcher
 * intent. Explicit-component starts avoid the implicit-intent
 * replay behavior that could re-deliver the launcher intent after
 * the activity exits. See resolveLauncherComponent().
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
 * AUTO-START ON SHIZUKU RETURN (8.6)
 * ----------------------------------
 * Shizuku-Next's "ADB without Developer options" switch takes
 * Shizuku's server down while a target app is in the foreground
 * and brings it back when the app exits. During that window,
 * ShizuPosed's binder connection drops and the service stops.
 *
 * Without intervention, nothing tells ShizuPosed that the server
 * has returned, and the service stays stopped until the user
 * manually opens the app.
 *
 * ShizukuBinderWatcher — a no-op ContentProvider registered in
 * the manifest — closes that gap. It registers
 * Shizuku.addBinderReceivedListenerSticky and addBinderDeadListener
 * at process start, and when the binder returns, it fires
 * ACTION_ENSURE_RUNNING at this service. The action handler is
 * idempotent: it restarts the service's worker path if the
 * service is alive, or starts the service if it isn't.
 *
 * This action is also safe to fire from any other component that
 * detects Shizuku's return, so the service does not depend on the
 * watcher alone.
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
     *
     * Called from onCreate on every path. If the worker is already
     * running, this is a no-op.
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
                // Allow a retry on the next call.
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

        // If the worker hasn't been set up yet (deferred auth case
        // where onCreate returned quickly), set it up now. This
        // covers the race where onStartCommand fires before the
        // worker thread has started.
        if (workerHandler == null) {
            setupWorkerInfrastructure();
        }

        // If the worker still isn't ready (setup is asynchronous),
        // and this is the authorization broadcast, remember that we
        // owe a deploy. We'll run it as soon as the worker is up.
        if (workerHandler == null) {
            if (intent != null
                    && ShizuPosedManagerApp.ACTION_SHIZUKU_AUTHORIZED
                        .equals(intent.getAction())) {
                if (logger != null) {
                    logger.i("ACTION_SHIZUKU_AUTHORIZED arrived before "
                        + "worker ready — will dispatch after setup");
                }
                // Nothing else to do here; the worker setup path
                // will detect authorization and dispatch on its own.
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
                    // Fired by ShizukuBinderWatcher (and possibly
                    // other components) when Shizuku's binder comes
                    // back after being away — including the case
                    // where Shizuku-Next's "ADB without Developer
                    // options" switch took the server down while a
                    // target app was foregrounded.
                    //
                    // The handler is idempotent. If the service is
                    // already running and authorized, it does
                    // nothing meaningful; if the worker was torn
                    // down or never fully started, this restarts
                    // it.
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
     *      payload readiness and startup sequence. This covers
     *      the case where the worker thread survived but the
     *      binder went away and came back.
     *
     *   2. Worker ready but Shizuku not authorized → do nothing;
     *      the ACTION_SHIZUKU_AUTHORIZED path handles that case
     *      when permission is granted.
     *
     *   3. Worker not yet set up (service just restarted) →
     *      setupWorkerInfrastructure() will set it up. Its own
     *      post() checks authorization and calls
     *      startServiceInternal() when ready.
     *
     * In all three cases, the caller should expect this method
     * to return quickly; the actual work is posted to the worker
     * thread.
     */
    private void onEnsureRunning() {
        if (logger != null) {
            logger.i("onEnsureRunning: isRunning=" + isRunning.get()
                + " workerHandler=" + (workerHandler != null ? "ready" : "null")
                + " authorized=" + (shizukuHelper != null
                    && shizukuHelper.isAuthorized()));
        }

        // Case 3: worker not set up. Set it up and let its own
        // post() decide whether to start.
        if (workerHandler == null) {
            setupWorkerInfrastructure();
            return;
        }

        // Case 2: worker ready but Shizuku not currently
        // authorized. Nothing to do yet; ACTION_SHIZUKU_AUTHORIZED
        // will handle it when the user grants permission.
        if (shizukuHelper == null || !shizukuHelper.isAuthorized()) {
            if (logger != null) {
                logger.i("onEnsureRunning: Shizuku not authorized — "
                    + "deferring");
            }
            return;
        }

        // Case 1: worker ready and Shizuku authorized. Re-run the
        // startup path. This is idempotent because
        // isRunning.compareAndSet guards startServiceInternal().
        workerHandler.post(() -> {
            try {
                if (ensurePayloadReady()) {
                    pushModulesToShellDir(moduleLoader.loadModules());
                }
                startServiceInternal();

                // Also reset the authorized-dispatch latch so a
                // later ACTION_SHIZUKU_AUTHORIZED (if the user
                // re-grants) is not suppressed.
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
                // Kick the service into full startup if it was
                // deferred at onCreate.
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

    public boolean launchAppUnderShizuPosed(String packageName,
                                             AtomicBoolean sent) {
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
                + " (binary=" + binary
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
                    + " not found — proceeding to am start anyway. "
                    + "Check launch.log for bootstrap output.");
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

    public boolean injectProcess(String packageName, int pid, int uid,
                                 List<ModuleInfo> modules) {
        logger.i("injectProcess(" + packageName + ", pid=" + pid
            + ", uid=" + uid + ")");
        if (!isRunning.get() || !shizukuHelper.isAuthorized()) return false;
        if (!ensurePayloadReady()) return false;
        logger.w("Cannot hook externally-launched process "
            + packageName
            + " — use launchAppUnderShizuPosed() to launch it "
            + "under ShizuPosed");
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════
    // ACCESSORS
    // ═════════════════════════════════════════════════════════════

    public boolean isRunning() { return isRunning.get(); }
    public boolean isXposedHookStarted() { return xposedHookStarted.get(); }
    public boolean isDexDeployed() { return dexDeployed.get(); }
    public boolean isLibsDeployed() { return libsDeployed.get(); }
    public static boolean isServiceRunning() { return isServiceRunning; }
}