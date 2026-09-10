package com.shizuposed.manager.model;

public class HookedProcess {
    private String processName;
    private int pid;
    private int uid;
    private boolean hooked;
    private long hookedAt;
    private String moduleName;
    private String packageName;

    public HookedProcess() {
        this.hooked = false;
        this.hookedAt = 0;
    }

    public HookedProcess(String processName, int pid, int uid) {
        this.processName = processName;
        this.pid = pid;
        this.uid = uid;
        this.hooked = false;
        this.hookedAt = 0;
    }

    public String getProcessName() {
        return processName;
    }

    public void setProcessName(String processName) {
        this.processName = processName;
    }

    public int getPid() {
        return pid;
    }

    public void setPid(int pid) {
        this.pid = pid;
    }

    public int getUid() {
        return uid;
    }

    public void setUid(int uid) {
        this.uid = uid;
    }

    public boolean isHooked() {
        return hooked;
    }

    public void setHooked(boolean hooked) {
        this.hooked = hooked;
    }

    public long getHookedAt() {
        return hookedAt;
    }

    public void setHookedAt(long hookedAt) {
        this.hookedAt = hookedAt;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
}