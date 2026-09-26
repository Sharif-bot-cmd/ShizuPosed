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
 * Keeps a live map of running app processes. Reads /proc directly when
 * possible; falls back to reading through Shizuku (shell uid) when the
 * direct read is blocked by SELinux.
 *
 * MARKER MIRROR
 * -------------
 * Each scan cycle also refreshes MarkerCache, the local mirror of
 * the shell-side hooked markers. ModuleStatusProvider reads from
 * that mirror to answer "is this module active?" without a Shizuku
 * round-trip per query. Refreshing here means the provider's queries
 * stay fast and independent of Shizuku's state at query time.
 *
 * SCAN INTERVAL
 * -------------
 * The interval between scan cycles is read from RuntimePrefs at
 * startMonitoring() time. It defaults to 5 seconds and can be tuned
 * from the Settings tab. A change takes effect on the next service
 * start, since the scheduler is created once per startMonitoring()
 * call.
 *
 * The marker mirror refresh runs at the same interval, bounded by
 * MARKER_REFRESH_INTERVAL_MS so a very fast scan interval doesn't
 * hammer the shell.
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
     * Minimum interval between marker mirror refreshes. The provider's
     * own in-memory TTL is 3 seconds, so a 5-second refresh keeps the
     * mirror at most one provider-TTL stale. This does NOT follow the
     * scan interval — the mirror refresh is capped at this rate even
     * if the scan runs faster.
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

    public void startMonitoring() {
        if (!isRunning.compareAndSet(false, true)) {
            logger.d("ProcessMonitor already running");
            return;
        }

        // ── Read the configured scan interval. Defaults to 5 s.
        //    Bounded by RuntimePrefs to [2 s, 30 s].
        int intervalMs = RuntimePrefs.getScanIntervalMs(context);
        logger.i("ProcessMonitor started - scan interval " + intervalMs + "ms");

        // ── Prime the marker mirror so the first provider query
        //    after startup finds a populated cache instead of
        //    paying the cold-start cost on the query thread.
        try {
            MarkerCache.refresh(context);
            lastMarkerRefresh = System.currentTimeMillis();
        } catch (Throwable t) {
            logger.w("Initial MarkerCache.refresh failed: " + t.getMessage());
        }

        scanExistingProcesses();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessMonitor-scan");
            t.setDaemon(true);
            return t;
        });
        scanTask = scheduler.scheduleWithFixedDelay(
            this::scanExistingProcesses,
            intervalMs, intervalMs, TimeUnit.MILLISECONDS
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

        // ── Refresh the marker mirror once per MARKER_REFRESH_INTERVAL_MS.
        //    This is decoupled from the scan interval: a fast scan
        //    interval doesn't hammer the shell with mirror refreshes.
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

            // ── Marker promotion. Reads the local mirror, no Shizuku.
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

            logger.i("Scanned " + found.size() + " processes ("
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

    /**
     * Parse an integer value out of a tiny JSON object by key.
     * Returns -1 if the key isn't found or the value isn't numeric.
     */
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
        Map<Integer, ProcInfo> direct = scanProcDirect();
        if (direct.size() >= 10) return direct;

        Map<Integer, ProcInfo> shell = scanProcViaShell();
        if (shell.size() > direct.size()) {
            logger.d("Using shell scan (" + shell.size()
                + " processes) — direct /proc read yielded " + direct.size());
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

    private Map<Integer, ProcInfo> scanProcViaShell() {
        Map<Integer, ProcInfo> out = new HashMap<>();
        try {
            ShizukuHelper sh = ShizukuHelper.getInstance(context);
            if (!sh.isAvailable() || !sh.isAuthorized()) {
                shellScanAvailable = Boolean.FALSE;
                return out;
            }

            String script =
                "for d in /proc/[0-9]*; do " +
                "  pid=${d##*/}; " +
                "  uid=$(grep '^Uid:' $d/status 2>/dev/null | awk '{print $2}'); " +
                "  cmd=$(tr '\\0' ' ' < $d/cmdline 2>/dev/null); " +
                "  if [ -n \"$uid\" ] && [ -n \"$cmd\" ]; then " +
                "    echo \"$pid|$uid|$cmd\"; " +
                "  fi; " +
                "done";

            ShellUtils.CommandResult r = sh.executeCommand(script);
            if (!r.isSuccess() || r.stdout == null) {
                logger.d("Shell scan returned no output");
                shellScanAvailable = Boolean.FALSE;
                return out;
            }

            shellScanAvailable = Boolean.TRUE;

            for (String line : r.stdout) {
                if (line == null) continue;
                line = line.trim();
                if (line.isEmpty()) continue;

                int p1 = line.indexOf('|');
                if (p1 <= 0) continue;
                int p2 = line.indexOf('|', p1 + 1);
                if (p2 <= 0) continue;

                int pid;
                int uid;
                try {
                    pid = Integer.parseInt(line.substring(0, p1));
                    uid = Integer.parseInt(line.substring(p1 + 1, p2));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid < 100) continue;
                if (pid == android.os.Process.myPid()) continue;

                String cmdline = line.substring(p2 + 1);
                String pkg = parsePackageName(cmdline);
                if (pkg == null || pkg.isEmpty()) continue;

                ProcInfo info = new ProcInfo();
                info.packageName = pkg;
                info.uid = uid;
                out.put(pid, info);
            }
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