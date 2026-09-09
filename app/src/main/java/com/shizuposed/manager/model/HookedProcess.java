package com.shizuposed.manager.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HookedProcess {
    public int pid;
    public int uid;
    public String packageName;
    public String processName;
    public long hookTime;
    public boolean isSystemProcess;
    public String status;
    public int moduleCount;
    
    public HookedProcess() {
        this.hookTime = System.currentTimeMillis();
        this.status = "Detected";
        this.moduleCount = 0;
    }
    
    public HookedProcess(int pid, String packageName) {
        this();
        this.pid = pid;
        this.packageName = packageName;
        this.processName = packageName;
    }
    
    public String getStatus() {
        return status;
    }
    
    public String getFormattedHookTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(hookTime));
    }
    
    public boolean isHooked() {
        return "Hooked".equals(status);
    }
    
    public boolean isFailed() {
        return "Failed".equals(status) || "Error".equals(status);
    }
}