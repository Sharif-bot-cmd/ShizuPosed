package com.shizuposed.manager.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogEntry {
    public long timestamp;
    public String level;
    public String tag;
    public String message;
    public String packageName;
    public int pid;
    public String threadName;
    
    public LogEntry() {
        this.timestamp = System.currentTimeMillis();
        this.level = "INFO";
        this.tag = "ShizuPosed";
        this.threadName = Thread.currentThread().getName();
    }
    
    public LogEntry(String level, String tag, String message) {
        this();
        this.level = level;
        this.tag = tag;
        this.message = message;
    }
    
    public LogEntry(String level, String tag, String message, String packageName) {
        this(level, tag, message);
        this.packageName = packageName;
    }
    
    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
    
    @Override
    public String toString() {
        return String.format(Locale.getDefault(), 
            "[%s] %s/%s: %s", 
            getFormattedTime(), level, tag, message);
    }
}