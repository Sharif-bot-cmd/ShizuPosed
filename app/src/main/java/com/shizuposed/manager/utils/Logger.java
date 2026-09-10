package com.shizuposed.manager.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.shizuposed.manager.model.LogEntry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Logger {
    private static final String TAG = "ShizuPosed";
    private static final int MAX_LOG_ENTRIES = 10000;
    private static final int MAX_LOG_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    private static Logger instance;
    private Context context;
    private File logFile;
    private ConcurrentLinkedQueue<LogEntry> logQueue = new ConcurrentLinkedQueue<>();
    private boolean isDebug = false;
    private boolean logToFile = true;
    private SharedPreferences prefs;

    // Tracks whether we're still inside the constructor. If so, setters
    // don't write markers — we're just reading the persisted state.
    private boolean initializing = true;

    private static final String LOG_FILE_NAME = "shizuposed.log";
    private static final String PREFS_NAME = "shizuposed_settings";

    private Logger(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Read persisted values without triggering markers
        this.logToFile = prefs.getBoolean("log_to_file", true);
        this.isDebug = prefs.getBoolean("debug_mode", false);

        File logDir = new File(context.getFilesDir(), ".syscall_cache/logs");
        if (!logDir.exists()) logDir.mkdirs();
        this.logFile = new File(logDir, LOG_FILE_NAME);

        initializing = false;

        Log.i(TAG, "Logger initialized - logToFile: " + logToFile + ", debug: " + isDebug);
    }

    public static synchronized Logger getInstance(Context context) {
        if (instance == null) {
            instance = new Logger(context);
        }
        return instance;
    }

    // ============================================================
    // SET LOG TO FILE — idempotent: only writes a marker on change
    // ============================================================
    public void setLogToFile(boolean enabled) {
        if (this.logToFile == enabled) {
            // Value unchanged — nothing to do, no marker, no prefs write
            return;
        }
        this.logToFile = enabled;
        prefs.edit().putBoolean("log_to_file", enabled).apply();

        Log.i(TAG, "Log to file: " + (enabled ? "ENABLED" : "DISABLED"));

        // Only write a marker when the value actually flipped
        if (!initializing) {
            if (enabled) {
                log("INFO", TAG, "=== LOG TO FILE ENABLED ===");
            } else {
                // Write marker BEFORE disabling so the file gets it
                log("INFO", TAG, "=== LOG TO FILE DISABLED ===");
            }
        }
    }

    public boolean isLogToFile() {
        return logToFile;
    }

    // ============================================================
    // SET DEBUG MODE — idempotent
    // ============================================================
    public void setDebug(boolean debug) {
        if (this.isDebug == debug) {
            return;
        }
        this.isDebug = debug;
        prefs.edit().putBoolean("debug_mode", debug).apply();

        if (!initializing) {
            if (debug) {
                log("INFO", TAG, "=== DEBUG MODE ENABLED ===");
            } else {
                log("INFO", TAG, "=== DEBUG MODE DISABLED ===");
            }
        }
    }

    public boolean isDebug() {
        return isDebug;
    }

    // ============================================================
    // LOGGING METHODS
    // ============================================================

    public void v(String message) { log("VERBOSE", TAG, message); }
    public void d(String message) { if (isDebug) log("DEBUG", TAG, message); }
    public void i(String message) { log("INFO", TAG, message); }
    public void w(String message) { log("WARN", TAG, message); }
    public void e(String message) { log("ERROR", TAG, message); }

    public void v(String tag, String message) { log("VERBOSE", tag, message); }
    public void d(String tag, String message) { if (isDebug) log("DEBUG", tag, message); }
    public void i(String tag, String message) { log("INFO", tag, message); }
    public void w(String tag, String message) { log("WARN", tag, message); }
    public void e(String tag, String message) { log("ERROR", tag, message); }

    // ============================================================
    // CORE LOGGING
    // ============================================================

    private void log(String level, String tag, String message) {
        LogEntry entry = new LogEntry(level, tag, message);
        logQueue.add(entry);

        switch (level) {
            case "VERBOSE": Log.v(tag, message); break;
            case "DEBUG":   Log.d(tag, message); break;
            case "INFO":    Log.i(tag, message); break;
            case "WARN":    Log.w(tag, message); break;
            case "ERROR":   Log.e(tag, message); break;
        }

        if (logToFile) {
            writeToFile(entry);
        }

        while (logQueue.size() > MAX_LOG_ENTRIES) {
            logQueue.poll();
        }
    }

    private synchronized void writeToFile(LogEntry entry) {
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                rotateLogFile();
            }
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(entry.toString() + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write log: " + e.getMessage());
        }
    }

    private void rotateLogFile() {
        try {
            File backup = new File(logFile.getParent(), "shizuposed.log.old");
            if (backup.exists()) backup.delete();
            if (logFile.renameTo(backup)) {
                logFile = new File(logFile.getParent(), LOG_FILE_NAME);
                Log.i(TAG, "Log file rotated");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to rotate log file: " + e.getMessage());
        }
    }

    // ============================================================
    // LOG RETRIEVAL
    // ============================================================

    public List<LogEntry> getLogEntries() {
        List<LogEntry> entries = new ArrayList<>();

        try {
            if (logFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                    String line;
                    SimpleDateFormat sdf = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

                    while ((line = reader.readLine()) != null) {
                        try {
                            int bracketEnd = line.indexOf(']');
                            if (bracketEnd > 0) {
                                String timeStr = line.substring(1, bracketEnd);
                                String rest = line.substring(bracketEnd + 2);

                                int slashIndex = rest.indexOf('/');
                                int colonIndex = rest.indexOf(':');

                                if (slashIndex > 0 && colonIndex > slashIndex) {
                                    String level = rest.substring(0, slashIndex);
                                    String tag = rest.substring(slashIndex + 1, colonIndex);
                                    String message = rest.substring(colonIndex + 2);

                                    LogEntry entry = new LogEntry(level, tag, message);
                                    try {
                                        entry.timestamp = sdf.parse(timeStr).getTime();
                                    } catch (Exception ignored) {}
                                    entries.add(entry);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read log file: " + e.getMessage());
        }

        return entries;
    }

    public String exportLogs() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        sb.append("=== ShizuPosed Logs ===\n");
        sb.append("Generated: ").append(sdf.format(new Date())).append("\n");
        sb.append("Log to File: ").append(logToFile).append("\n");
        sb.append("Debug Mode: ").append(isDebug).append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        List<LogEntry> entries = getLogEntries();
        for (LogEntry entry : entries) {
            sb.append(entry.toString()).append("\n");
        }

        if (!logQueue.isEmpty()) {
            sb.append("\n=== Recent Memory Logs ===\n");
            for (LogEntry entry : logQueue) {
                sb.append(entry.toString()).append("\n");
            }
        }

        return sb.toString();
    }

    public void clearLogs() {
        try {
            if (logFile.exists()) {
                logFile.delete();
                logFile.createNewFile();
            }
            logQueue.clear();
            Log.i(TAG, "Logs cleared");
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear logs: " + e.getMessage());
        }
    }

    public File getLogFile() { return logFile; }

    public long getLogFileSize() {
        if (logFile.exists()) return logFile.length();
        return 0;
    }
}