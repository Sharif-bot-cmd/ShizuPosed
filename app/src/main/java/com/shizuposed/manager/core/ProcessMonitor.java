package com.shizuposed.manager.core;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.model.HookedProcess;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProcessMonitor {
    private static ProcessMonitor instance;

    private final Context context;
    private final Logger logger;
    private ShizuPosedService injectionService;

    private final ConcurrentHashMap<Integer, HookedProcess> hookedProcesses = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Periodic rescanner — keeps the hooked-processes map fresh
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scanTask;

    private ProcessMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
    }

    public static synchronized ProcessMonitor getInstance(Context context) {
        if (instance == null) {
            instance = new ProcessMonitor(context);
        }
        return instance;
    }

    // ═════════════════════════════════════════════════════════════
    // SERVICE WIRING
    // ═════════════════════════════════════════════════════════════

    public void setShizuPosedService(ShizuPosedService service) {
        this.injectionService = service;
        logger.i("Injection service set");

        try {
            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            if (app != null) app.setShizuPosedService(service);
        } catch (Throwable t) {
            logger.w("Could not propagate service to Application: " + t.getMessage());
        }

        try {
            if (injectionService != null
                    && ShizukuHelper.getInstance(context).isAuthorized()
                    && !injectionService.isDexDeployed()) {
                logger.i("Shizuku already authorized at service-bind time - triggering dex deploy");
                injectionService.onShizukuAuthorized();
            }
        } catch (Throwable t) {
            logger.w("Error nudging service to deploy dex: " + t.getMessage());
        }
    }

    public ShizuPosedService getShizuPosedService() {
        return injectionService;
    }

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    public void startMonitoring() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.d("ProcessMonitor already running");
            return;
        }
        logger.i("ProcessMonitor started - monitoring for new apps");

        // Initial scan
        scanExistingProcesses();

        // Periodic rescan every 5 seconds so the UI reflects reality.
        // Each scan is cheap (reads /proc); running it off the main
        // thread keeps the UI responsive.
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessMonitor-scan");
            t.setDaemon(true);
            return t;
        });
        scanTask = scheduler.scheduleWithFixedDelay(
            this::scanExistingProcesses,
            5, 5, TimeUnit.SECONDS
        );
    }

    public void stopMonitoring() {
        if (!isRunning.compareAndSet(true, false)) return;
        logger.i("ProcessMonitor stopped");

        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SCAN
    // ═════════════════════════════════════════════════════════════

    private void scanExistingProcesses() {
        if (!isRunning.get()) return;

        try {
            // We don't clear the map here. Anything no longer present in
            // /proc gets removed at the end via pruneDeadProcesses().
            // That way, adding a process while another scan is running
            // doesn't wipe live entries.

            File procDir = new File("/proc");
            File[] pidDirs = procDir.listFiles();
            if (pidDirs == null) return;

            int found = 0;
            int newlyAdded = 0;

            for (File dir : pidDirs) {
                if (!dir.isDirectory()) continue;

                int pid;
                try {
                    pid = Integer.parseInt(dir.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid < 100) continue;

                // Skip our own pid
                if (pid == android.os.Process.myPid()) continue;

                File cmdline = new File("/proc/" + pid + "/cmdline");
                if (!cmdline.exists()) continue;

                String raw = readFile(cmdline);
                if (raw == null || raw.isEmpty()) continue;

                String packageName = parsePackageName(raw);
                if (packageName == null || packageName.isEmpty()) continue;

                if (isSystemProcess(packageName)) continue;

                int uid = getProcessUid(pid);
                if (uid < 0) continue;

                // Only track user apps
                if (uid < 10000) continue;

                found++;

                // Only add to the map if we haven't already recorded it.
                if (hookedProcesses.containsKey(pid)) continue;

                HookedProcess process = new HookedProcess();
                process.setProcessName(packageName);
                process.setPid(pid);
                process.setUid(uid);
                process.setHooked(false);            // not hooked until the service says so
                process.setHookedAt(System.currentTimeMillis());

                hookedProcesses.put(pid, process);
                newlyAdded++;

                logger.v("Found app: " + packageName + " (pid=" + pid + ", uid=" + uid + ")");

                // Ask the service to inject. In the current timing model
                // this returns false for externally launched pids — that's
                // expected; the process stays in the map as "not hooked".
                if (injectionService != null) {
                    try {
                        List<ModuleInfo> modules =
                            ModuleLoader.getInstance(context).getEnabledModules();
                        if (!modules.isEmpty()) {
                            injectionService.injectProcess(packageName, pid, uid, modules);
                        }
                    } catch (Throwable t) {
                        logger.w("injectProcess failed for " + packageName + ": " + t.getMessage());
                    }
                }
            }

            pruneDeadProcesses();

            logger.i("Scanned " + found + " app processes (" + newlyAdded
                + " new, " + hookedProcesses.size() + " tracked)");
        } catch (Exception e) {
            logger.e("Scan error: " + e.getMessage());
        }
    }

    /**
     * Remove entries whose pid no longer exists or whose /proc/<pid>
     * directory is gone.
     */
    private void pruneDeadProcesses() {
        List<Integer> toRemove = new ArrayList<>();
        for (Integer pid : hookedProcesses.keySet()) {
            File proc = new File("/proc/" + pid);
            if (!proc.exists()) {
                toRemove.add(pid);
            }
        }
        for (Integer pid : toRemove) {
            HookedProcess removed = hookedProcesses.remove(pid);
            if (removed != null) {
                logger.v("Process exited: " + removed.getProcessName()
                    + " (pid=" + pid + ")");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * Read the entire file. Earlier versions used readLine() and only
     * saw the first line, which made getProcessUid() always return -1.
     */
    private String readFile(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Strip NULs, path prefixes and ":suffix" from a /proc/<pid>/cmdline
     * content string.
     *
     *   "/system/bin/app_process\0"           → "app_process"
     *   "com.example.app\0"                   → "com.example.app"
     *   "com.example.app:remote\0"            → "com.example.app"
     *   "/data/app/.../com.example.app\0"     → "com.example.app"
     */
    private String parsePackageName(String raw) {
        if (raw == null) return null;
        String clean = raw.replace("\0", "").trim();
        if (clean.isEmpty()) return null;
        if (clean.contains("/")) {
            clean = clean.substring(clean.lastIndexOf('/') + 1);
        }
        int colon = clean.indexOf(':');
        if (colon > 0) clean = clean.substring(0, colon);
        return clean;
    }

    private int getProcessUid(int pid) {
        try {
            File status = new File("/proc/" + pid + "/status");
            if (!status.exists()) return -1;

            String content = readFile(status);
            if (content == null) return -1;

            for (String line : content.split("\n")) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1) {
                        return Integer.parseInt(parts[1]);
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isSystemProcess(String packageName) {
        if (packageName == null) return true;
        String[] systemProcesses = {
            "init", "zygote", "zygote64", "system_server", "servicemanager",
            "hwservicemanager", "vndbinder", "surfaceflinger",
            "netd", "installd", "lmkd", "logd", "keystore",
            "sh", "su", "app_process", "adbd", "ueventd",
            "healthd", "watchdogd", "logcat", "debuggerd",
            "android.hardware", "android.system",
            "com.android.systemui", "com.android.phone",
            "com.android.bluetooth", "com.android.nfc"
        };
        for (String proc : systemProcesses) {
            if (packageName.equals(proc)) return true;
            if (packageName.startsWith(proc + ":")) return true;
        }
        // Any process starting with "android." is a system component
        return packageName.startsWith("android.");
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC QUERIES
    // ═════════════════════════════════════════════════════════════

    public List<HookedProcess> getHookedProcesses() {
        return new ArrayList<>(hookedProcesses.values());
    }

    /**
     * Real count of processes the service has actually hooked. Filters
     * out entries that were merely *discovered* — a process is only
     * counted when HookedProcess.isHooked() is true.
     */
    public int getHookedProcessCount() {
        int count = 0;
        for (HookedProcess p : hookedProcesses.values()) {
            if (p.isHooked()) count++;
        }
        return count;
    }

    /**
     * Total tracked processes (discovered, whether hooked or not).
     * Home tab uses this for "Total Apps" if you prefer it over the
     * PackageManager count.
     */
    public int getTrackedProcessCount() {
        return hookedProcesses.size();
    }

    /**
     * Called by the service when it successfully installs hooks in a
     * target process. Promotes the entry from "discovered" to "hooked".
     */
    public void addHookedProcess(HookedProcess process) {
        if (process == null || process.getPid() <= 0) return;
        process.setHooked(true);
        process.setHookedAt(System.currentTimeMillis());
        hookedProcesses.put(process.getPid(), process);
        logger.i("Hooked process recorded: " + process.getProcessName()
            + " (pid=" + process.getPid() + ")");
    }

    public void removeHookedProcess(int pid) {
        hookedProcesses.remove(pid);
    }

    public boolean isProcessHooked(int pid) {
        HookedProcess p = hookedProcesses.get(pid);
        return p != null && p.isHooked();
    }

    public void clearHookedProcesses() {
        hookedProcesses.clear();
    }
}