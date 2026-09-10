package com.shizuposed.manager.core;

import android.content.Context;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.utils.FileUtils;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ResourceHookingBridge.java
 * 
 * Bridges the Manager's ResourceHooking with the XposedHook engine.
 * Writes resource replacements to a file that XposedHook reads.
 * 
 * This allows modules to register resource replacements in the Manager
 * and have them applied by the XposedHook engine.
 */
public class ResourceHookingBridge {
    private static final String TAG = "ResourceHookingBridge";
    private static ResourceHookingBridge instance;
    
    private Context context;
    private Logger logger;
    private ShizukuHelper shizukuHelper;
    private ResourceHooking resourceHooking;
    
    // Cache directory for resource overrides
    private static final String CACHE_DIR = ".syscall_cache/resource_overrides";
    
    private ResourceHookingBridge(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.shizukuHelper = ShizukuHelper.getInstance(context);
        this.resourceHooking = ResourceHooking.getInstance(context);
        
        // Create cache directory
        File cacheDir = new File(context.getFilesDir(), CACHE_DIR);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }
    
    public static synchronized ResourceHookingBridge getInstance(Context context) {
        if (instance == null) {
            instance = new ResourceHookingBridge(context);
        }
        return instance;
    }
    
    /**
     * Sync resource overrides from Manager to XposedHook
     * Called when a module registers a resource replacement
     */
    public void syncOverrides(String packageName) {
        try {
            // Get all overrides for this package
            ConcurrentHashMap<Integer, Object> overrides = resourceHooking.getOverrides(packageName);
            if (overrides == null || overrides.isEmpty()) {
                return;
            }
            
            // Build the override data
            StringBuilder sb = new StringBuilder();
            for (ConcurrentHashMap.Entry<Integer, Object> entry : overrides.entrySet()) {
                int id = entry.getKey();
                Object value = entry.getValue();
                String type = getValueType(value);
                String stringValue = value.toString();
                
                // Format: ID|TYPE|VALUE
                sb.append(id).append("|").append(type).append("|").append(stringValue).append("\n");
            }
            
            // Write to file for XposedHook to read
            File overrideFile = new File(context.getFilesDir(), 
                CACHE_DIR + "/" + packageName + ".overrides");
            FileUtils.writeFile(overrideFile, sb.toString());
            
            logger.i("Synced " + overrides.size() + " overrides for " + packageName);
            
            // Notify XposedHook to reload overrides
            notifyXposedHook(packageName);
            
        } catch (Exception e) {
            logger.e("Failed to sync overrides: " + e.getMessage());
        }
    }
    
    /**
     * Sync all resource overrides
     */
    public void syncAllOverrides() {
        try {
            // Get all packages with overrides
            // This would require iterating through all overrides
            // For simplicity, we'll just sync each package
            
            // Clear all override files
            File cacheDir = new File(context.getFilesDir(), CACHE_DIR);
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".overrides")) {
                        String packageName = file.getName().replace(".overrides", "");
                        syncOverrides(packageName);
                    }
                }
            }
            
            logger.i("Synced all resource overrides");
            
        } catch (Exception e) {
            logger.e("Failed to sync all overrides: " + e.getMessage());
        }
    }
    
    /**
     * Get value type for serialization
     */
    private String getValueType(Object value) {
        if (value instanceof String) return "STRING";
        if (value instanceof Integer) return "INTEGER";
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Float) return "FLOAT";
        if (value instanceof Long) return "LONG";
        if (value instanceof Double) return "DOUBLE";
        if (value instanceof CharSequence) return "CHAR_SEQUENCE";
        return "OBJECT";
    }
    
    /**
     * Notify XposedHook to reload overrides
     */
    private void notifyXposedHook(String packageName) {
        try {
            // Write a notification file
            File notifyFile = new File(context.getFilesDir(), 
                CACHE_DIR + "/" + packageName + ".reload");
            FileUtils.writeFile(notifyFile, System.currentTimeMillis() + "");
            
            // Try to send notification via Shizuku
            if (shizukuHelper.isAvailable() && shizukuHelper.isAuthorized()) {
                String cmd = "touch /data/local/tmp/.syscall_cache/resource_reload_" + packageName;
                shizukuHelper.executeCommand(cmd);
            }
            
            logger.i("Notified XposedHook to reload overrides for " + packageName);
            
        } catch (Exception e) {
            logger.e("Failed to notify XposedHook: " + e.getMessage());
        }
    }
    
    /**
     * Load resource overrides from XposedHook
     * Called when XposedHook reports a status
     */
    public boolean loadOverridesFromXposed(String packageName) {
        try {
            // Try to read override status from XposedHook
            File statusFile = new File(context.getFilesDir(), 
                CACHE_DIR + "/" + packageName + ".status");
            
            if (statusFile.exists()) {
                String status = FileUtils.readFile(statusFile);
                if (status != null && status.contains("LOADED")) {
                    logger.i("Resource overrides loaded by XposedHook for " + packageName);
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            logger.e("Failed to load overrides from Xposed: " + e.getMessage());
            return false;
        }
    }
}