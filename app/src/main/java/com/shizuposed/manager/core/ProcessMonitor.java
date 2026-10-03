package com.shizuposed.manager.core;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.model.HookedProcess;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.runtime.RuntimePrefs;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ProcessMonitor
 *
 * Keeps a live map of running app processes. Reads /proc directly
 * when possible; falls back to reading through Shizuku (shell uid)
 * when the direct read is blocked by SELinux.
 *
 * MARKER MIRROR
 * -------------
 * Each scan cycle also refreshes MarkerCache, the local mirror of
 * the shell-side hooked markers. ModuleStatusProvider reads from
 * that mirror to answer "is this module active?" without a Shizuku
 * round-trip per query.
 *
 * NON-BLOCKING START (7.0)
 * ------------------------
 * startMonitoring() schedules its priming work (marker cache
 * refresh + initial scan) on the scheduler thread and returns
 * immediately. Previously it ran them inline, which stalled the
 * caller for 10-20 seconds on devices where the shell-side process
 * enumeration is slow. That stalled everything queued behind the
 * caller on the same worker thread — including routed module
 * launches.
 *
 * FAST SHELL SCAN (7.0)
 * ---------------------
 * The fallback shell scan used to loop over /proc/[0-9]* and run
 * grep/awk/tr per process. On a device with 300+ processes that's
 * 15-20 seconds of shell work. It now uses a single `ps -A -o
 * PID,UID,ARGS` call, which is ~1 second.
 */
public class ProcessMonitor {
    private static ProcessMonitor instance;

    private final Context context;
    private final Logger logger;
    private ShizuPosedService injectionService;

    private final ConcurrentHashMap<Integer, HookedProcess> hookedProcesses = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scanTask;

    private volatile Boolean shellScanAvailable = null;

    /** Timestamp of the last marker mirror refresh, in millis. */
    private volatile long lastMarkerRefresh = 0L;

    /**
     * Minimum interval between marker mirror refreshes.
     */
    private static final long MARKER_REFRESH_INTERVAL_MS = 5000L;

    // Shell-side paths (mirror what XposedHook uses)
    private static final String SHELL_FILES_DIR = "/data/user/0/com.android.shell/files";
    private static final String HOOKED_DIR      = SHELL_FILES_DIR + "/.syscall_cache/hooked";

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

    /**
     * Start monitoring. Returns immediately — the priming work
     * (marker cache refresh + initial scan) runs on the scheduler
     * thread in the background.
     *
     * Previously this method ran the priming inline, which stalled
     * the caller for 10-20 seconds on devices where the shell-side
     * process enumeration is slow. That blocked everything queued
     * behind the caller on the same thread, including routed module
     * launches.
     */
    public void startMonitoring() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.d("ProcessMonitor already running");
            return;
        }

        int intervalMs = RuntimePrefs.getScanIntervalMs(context);
        logger.i("ProcessMonitor starting - scan interval " + intervalMs + "ms");

        // Create the scheduler with a daemon thread so it doesn't
        // keep the process alive.
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessMonitor-scan");
            t.setDaemon(true);
            return t;
        });

        // Schedule the priming work in the background. Everything
        // that used to block startMonitoring() now runs here.
        scheduler.execute(() -> {
            try {
                MarkerCache.refresh(context);
                lastMarkerRefresh = System.currentTimeMillis();
            } catch (Throwable t) {
                logger.w("Initial MarkerCache.refresh failed: " + t.getMessage());
            }
            try {
                long t0 = System.currentTimeMillis();
                scanExistingProcesses();
                long dt = System.currentTimeMillis() - t0;
                logger.i("Initial ProcessMonitor scan took " + dt + "ms");
            } catch (Throwable t) {
                logger.w("Initial scanExistingProcesses failed: " + t.getMessage());
            }
        });

        // Schedule the recurring scan.
        scanTask = scheduler.scheduleWithFixedDelay(
            this::scanExistingProcesses,
            intervalMs, intervalMs, TimeUnit.MILLISECONDS
        );

        logger.i("ProcessMonitor scheduled - initial scan deferred to background");
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

        // Refresh the marker mirror once per MARKER_REFRESH_INTERVAL_MS.
        try {
            long now = System.currentTimeMillis();
            if (now - lastMarkerRefresh >= MARKER_REFRESH_INTERVAL_MS) {
                lastMarkerRefresh = now;
                MarkerCache.refresh(context);
            }
        } catch (Throwable t) {
            logger.w("MarkerCache.refresh failed: " + t.getMessage());
        }

        try {
            Map<Integer, ProcInfo> found = scanProcTree();

            int newlyAdded = 0;
            for (Map.Entry<Integer, ProcInfo> e : found.entrySet()) {
                int pid = e.getKey();
                ProcInfo info = e.getValue();

                if (isSystemProcess(info.packageName)) continue;
                if (info.uid < 10000) continue;
                if (pid == android.os.Process.myPid()) continue;

                if (hookedProcesses.containsKey(pid)) continue;

                HookedProcess process = new HookedProcess();
                process.setProcessName(info.packageName);
                process.setPid(pid);
                process.setUid(info.uid);
                process.setHooked(false);
                process.setHookedAt(System.currentTimeMillis());

                hookedProcesses.put(pid, process);
                newlyAdded++;

                logger.v("Found app: " + info.packageName
                    + " (pid=" + pid + ", uid=" + info.uid + ")");

                if (injectionService != null) {
                    try {
                        List<ModuleInfo> modules =
                            ModuleLoader.getInstance(context).getEnabledModules();
                        if (!modules.isEmpty()) {
                            injectionService.injectProcess(
                                info.packageName, pid, info.uid, modules);
                        }
                    } catch (Throwable t) {
                        logger.w("injectProcess failed for "
                            + info.packageName + ": " + t.getMessage());
                    }
                }
            }

            // Marker promotion. Reads the local mirror, no Shizuku.
            promoteFromMirror();

            // Prune tracked pids that no longer exist in /proc
            List<Integer> toRemove = new ArrayList<>();
            for (Integer pid : hookedProcesses.keySet()) {
                if (!found.containsKey(pid)) toRemove.add(pid);
            }
            for (Integer pid : toRemove) {
                HookedProcess removed = hookedProcesses.remove(pid);
                if (removed != null) {
                    logger.v("Process exited: " + removed.getProcessName()
                        + " (pid=" + pid + ")");
                }
            }

            logger.d("Scanned " + found.size() + " processes ("
                + newlyAdded + " new, " + hookedProcesses.size() + " tracked)");

        } catch (Exception e) {
            logger.e("Scan error: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // MARKER PROMOTION FROM LOCAL MIRROR
    // ═════════════════════════════════════════════════════════════

    private void promoteFromMirror() {
        try {
            Map<String, String> markers = MarkerCache.read(context);
            if (markers.isEmpty()) return;

            for (Map.Entry<String, String> entry : markers.entrySet()) {
                String pkg = entry.getKey();
                String body = entry.getValue();
                if (pkg == null || body == null) continue;

                int pid     = extractInt(body, "pid");
                int uid     = extractInt(body, "uid");
                int modules = extractInt(body, "modules");

                HookedProcess target = null;
                if (pid > 0) target = hookedProcesses.get(pid);
                if (target == null) {
                    for (HookedProcess p : hookedProcesses.values()) {
                        if (pkg.equals(p.getProcessName())) {
                            target = p;
                            break;
                        }
                    }
                }

                if (target != null && target.isHooked()) continue;

                if (target == null) {
                    target = new HookedProcess();
                    target.setProcessName(pkg);
                    target.setPid(pid > 0 ? pid : -1);
                    target.setUid(uid);
                }

                target.setHooked(true);
                target.setHookedAt(System.currentTimeMillis());

                if (target.getPid() > 0) {
                    hookedProcesses.put(target.getPid(), target);
                }

                logger.i("Hook confirmed by target (mirror): " + pkg
                    + " (pid=" + pid + ", modules=" + modules + ")");
            }
        } catch (Throwable t) {
            logger.d("promoteFromMirror: " + t.getMessage());
        }
    }

    private int extractInt(String json, String key) {
        if (json == null || key == null) return -1;
        try {
            String needle = "\"" + key + "\":";
            int s = json.indexOf(needle);
            if (s < 0) return -1;
            s += needle.length();
            int e = s;
            while (e < json.length()
                    && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) {
                e++;
            }
            if (e == s) return -1;
            return Integer.parseInt(json.substring(s, e));
        } catch (Throwable t) {
            return -1;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PROC READERS
    // ═════════════════════════════════════════════════════════════

    private static final class ProcInfo {
        String packageName;
        int uid;
    }

    private Map<Integer, ProcInfo> scanProcTree() {
        long t0 = System.currentTimeMillis();
        Map<Integer, ProcInfo> direct = scanProcDirect();
        long directMs = System.currentTimeMillis() - t0;

        if (direct.size() >= 10) {
            return direct;
        }

        long t1 = System.currentTimeMillis();
        Map<Integer, ProcInfo> shell = scanProcViaShell();
        long shellMs = System.currentTimeMillis() - t1;

        logger.d("scanProcTree: direct=" + direct.size() + " (" + directMs
            + "ms), shell=" + shell.size() + " (" + shellMs + "ms)");

        if (shell.size() > direct.size()) {
            return shell;
        }
        return direct;
    }

    private Map<Integer, ProcInfo> scanProcDirect() {
        Map<Integer, ProcInfo> out = new HashMap<>();
        try {
            File procDir = new File("/proc");
            File[] pidDirs = procDir.listFiles();
            if (pidDirs == null) return out;

            for (File dir : pidDirs) {
                if (!dir.isDirectory()) continue;

                int pid;
                try { pid = Integer.parseInt(dir.getName()); }
                catch (NumberFormatException e) { continue; }
                if (pid < 100) continue;
                if (pid == android.os.Process.myPid()) continue;

                File cmdline = new File("/proc/" + pid + "/cmdline");
                if (!cmdline.exists()) continue;

                String raw = readFile(cmdline);
                if (raw == null || raw.isEmpty()) continue;

                String pkg = parsePackageName(raw);
                if (pkg == null || pkg.isEmpty()) continue;

                int uid = getProcessUidDirect(pid);
                if (uid < 0) continue;

                ProcInfo info = new ProcInfo();
                info.packageName = pkg;
                info.uid = uid;
                out.put(pid, info);
            }
        } catch (Throwable t) {
            logger.d("scanProcDirect error: " + t.getMessage());
        }
        return out;
    }

    /**
     * Fallback: enumerate processes via a single `ps` invocation
     * through Shizuku.
     *
     * Previously this used a shell loop over /proc/[0-9]* with
     * per-process grep/awk/tr. On a device with 300+ processes that
     * took 15-20 seconds. A single `ps -A -o PID,UID,ARGS` is
     * roughly 1 second.
     *
     * Falls back progressively through `ps` output formats, since
     * column names vary across Android versions and OEM builds.
     */
    private Map<Integer, ProcInfo> scanProcViaShell() {
        Map<Integer, ProcInfo> out = new HashMap<>();
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(context);
            if (!sh.isAvailable() || !sh.isAuthorized()) {
                shellScanAvailable = Boolean.FALSE;
                return out;
            }

            // Try ARGS first (full command line), then NAME (short
            // name), then bare ps. Each returns fewer details but
            // at least gives pid+uid.
            String script =
                "ps -A -o PID,UID,ARGS 2>/dev/null "
                + "|| ps -A -o PID,UID,NAME 2>/dev/null "
                + "|| ps -A 2>/dev/null";

            long t0 = System.currentTimeMillis();
            ShellUtils.CommandResult r = sh.executeCommand(script);
            long dt = System.currentTimeMillis() - t0;

            if (!r.isSuccess() || r.stdout == null) {
                logger.d("Shell scan returned no output (took " + dt + "ms)");
                shellScanAvailable = Boolean.FALSE;
                return out;
            }

            shellScanAvailable = Boolean.TRUE;

            for (String line : r.stdout) {
                if (line == null) continue;
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip headers.
                if (line.startsWith("PID")) continue;
                if (line.startsWith("USER")) continue;
                if (line.startsWith("UID")) continue;

                // Format: PID UID ARGS (whitespace-separated, ARGS
                // may contain spaces).
                String[] parts = line.split("\\s+", 3);
                if (parts.length < 3) continue;

                int pid;
                int uid;
                try {
                    pid = Integer.parseInt(parts[0]);
                    uid = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    // Some ROMs emit non-numeric columns; try to
                    // parse from the second field if the first
                    // looks like a username.
                    try {
                        uid = Integer.parseInt(parts[1]);
                        pid = -1;
                        if (uid < 0) continue;
                    } catch (NumberFormatException e2) {
                        continue;
                    }
                    if (pid < 0) continue;
                }
                if (pid < 100) continue;
                if (pid == android.os.Process.myPid()) continue;

                String cmdline = parts[2];
                String pkg = parsePackageName(cmdline);
                if (pkg == null || pkg.isEmpty()) continue;

                ProcInfo info = new ProcInfo();
                info.packageName = pkg;
                info.uid = uid;
                out.put(pid, info);
            }

            logger.d("Shell scan via ps: " + out.size()
                + " processes parsed (took " + dt + "ms)");
        } catch (Throwable t) {
            logger.d("scanProcViaShell error: " + t.getMessage());
        }
        return out;
    }

    private int getProcessUidDirect(int pid) {
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

    // ═════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════

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

    private String parsePackageName(String raw) {
        if (raw == null) return null;
        String clean = raw.replace("\0", "").trim();
        if (clean.isEmpty()) return null;
        if (clean.contains("/")) {
            clean = clean.substring(clean.lastIndexOf('/') + 1);
        }
        int colon = clean.indexOf(':');
        if (colon > 0) clean = clean.substring(0, colon);
        clean = clean.split("\\s+")[0];
        return clean;
    }

    private boolean isSystemProcess(String packageName) {
        if (packageName == null) return true;
        String[] systemProcesses = {
            "init", "zygote", "zygote64", "system_server", "servicemanager",
            "hwservicemanager", "vndbinder", "surfaceflinger",
            "netd", "installd", "lmkd", "logd", "keystore", "rish",
            "sh", "su", "app_process", "adbd", "ueventd",
            "healthd", "watchdogd", "logcat", "debuggerd",
            "android.hardware", "android.system"
        };
        for (String proc : systemProcesses) {
            if (packageName.equals(proc)) return true;
            if (packageName.startsWith(proc + ":")) return true;
        }
        return packageName.startsWith("android.");
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC QUERIES
    // ═════════════════════════════════════════════════════════════

    public List<HookedProcess> getHookedProcesses() {
        return new ArrayList<>(hookedProcesses.values());
    }

    public int getHookedProcessCount() {
        int count = 0;
        for (HookedProcess p : hookedProcesses.values()) {
            if (p.isHooked()) count++;
        }
        return count;
    }

    public int getTrackedProcessCount() {
        return hookedProcesses.size();
    }

    public boolean isPackageInScope(String packageName) {
        if (packageName == null) return false;
        try {
            List<ModuleInfo> enabled =
                ModuleLoader.getInstance(context).getEnabledModules();
            for (ModuleInfo m : enabled) {
                if (m.hookedApps != null && m.hookedApps.contains(packageName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

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

    public boolean isUsingShellScan() {
        return Boolean.TRUE.equals(shellScanAvailable);
    }
}