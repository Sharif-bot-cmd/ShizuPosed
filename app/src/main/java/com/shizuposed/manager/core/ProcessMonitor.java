package com.shizuposed.manager.core;

import android.content.Context;

import com.shizuposed.manager.model.HookedProcess;
import com.shizuposed.manager.model.ModuleInfo;  // ← ADD THIS IMPORT!
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProcessMonitor {
    private static ProcessMonitor instance;
    private Context context;
    private Logger logger;
    private ShizuPosedService injectionService;
    private ConcurrentHashMap<Integer, HookedProcess> hookedProcesses = new ConcurrentHashMap<>();
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    
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
    
    public void setShizuPosedService(ShizuPosedService service) {
        this.injectionService = service;
        logger.i("Injection service set");
    }
    
    public void startMonitoring() {
        isRunning.set(true);
        logger.i("ProcessMonitor started - monitoring for new apps");
        scanExistingProcesses();
    }
    
    public void stopMonitoring() {
        isRunning.set(false);
        logger.i("ProcessMonitor stopped");
    }
    
    private void scanExistingProcesses() {
        try {
            File procDir = new File("/proc");
            File[] pidDirs = procDir.listFiles();
            if (pidDirs == null) return;
            
            int count = 0;
            for (File dir : pidDirs) {
                if (!dir.isDirectory()) continue;
                try {
                    int pid = Integer.parseInt(dir.getName());
                    if (pid < 100) continue;
                    
                    File cmdline = new File("/proc/" + pid + "/cmdline");
                    if (!cmdline.exists()) continue;
                    
                    String content = readFile(cmdline);
                    if (content == null || content.isEmpty()) continue;
                    
                    String packageName = content.replace("\0", "").trim();
                    if (packageName.contains("/")) {
                        packageName = packageName.substring(packageName.lastIndexOf('/') + 1);
                    }
                    
                    if (isSystemProcess(packageName)) continue;
                    if (packageName.equals("sh") || packageName.equals("su")) continue;
                    if (packageName.equals("app_process")) continue;
                    
                    int uid = getProcessUid(pid);
                    if (uid < 0) continue;
                    
                    if (uid >= 10000) {
                        count++;
                        logger.v("Found existing app: " + packageName + " (PID: " + pid + ")");
                        
                        if (injectionService != null) {
                            // ✅ Get enabled modules
                            List<ModuleInfo> modules = ModuleLoader.getInstance(context).getEnabledModules();
                            if (!modules.isEmpty()) {
                                injectionService.injectProcess(packageName, pid, uid, modules);
                            }
                        }
                    }
                    
                } catch (NumberFormatException e) {
                    // Skip non-PID directories
                }
            }
            
            logger.i("Scanned " + count + " existing app processes");
        } catch (Exception e) {
            logger.e("Scan error: " + e.getMessage());
        }
    }
    
    private String readFile(File file) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }
    
    private int getProcessUid(int pid) {
        try {
            File status = new File("/proc/" + pid + "/status");
            if (!status.exists()) return -1;
            
            String content = readFile(status);
            if (content == null) return -1;
            
            String[] lines = content.split("\n");
            for (String line : lines) {
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
            "init", "zygote", "system_server", "servicemanager",
            "hwservicemanager", "vndbinder", "surfaceflinger",
            "netd", "installd", "lmkd", "logd", "keystore",
            "sh", "su", "app_process", "adbd", "ueventd",
            "healthd", "watchdogd", "logcat", "debuggerd"
        };
        for (String proc : systemProcesses) {
            if (packageName.equals(proc) || packageName.startsWith(proc + ":")) {
                return true;
            }
        }
        return false;
    }
    
    public List<HookedProcess> getHookedProcesses() {
        return new ArrayList<>(hookedProcesses.values());
    }
}
