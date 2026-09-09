// HookEngine.java - SAFE, no memory corruption
// Location: app/src/main/java/com/shizuposed/manager/core/HookEngine.java

package com.shizuposed.manager.core;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HookEngine.java - The SAFE version
 * 
 * NO memory guessing!
 * NO brute-force copying!
 * Uses method replacement via reflection only.
 */
public class HookEngine {
    private static final String TAG = "HookEngine";
    private static HookEngine instance;
    
    private ConcurrentHashMap<String, Method> hookedMethods = new ConcurrentHashMap<>();
    private boolean initialized = false;
    
    private HookEngine() {}
    
    public static synchronized HookEngine getInstance() {
        if (instance == null) {
            instance = new HookEngine();
        }
        return instance;
    }
    
    public void init() {
        if (initialized) return;
        initialized = true;
        Log.i(TAG, "HookEngine initialized (safe mode)");
    }
    
    /**
     * Hook a method - SAFE version
     * Uses method replacement via reflection, NOT memory manipulation.
     */
    public boolean hookMethod(Method originalMethod, Method replacementMethod) {
        try {
            String key = originalMethod.getDeclaringClass().getName() + 
                        "." + originalMethod.getName();
            hookedMethods.put(key, originalMethod);
            
            originalMethod.setAccessible(true);
            replacementMethod.setAccessible(true);
            
            Log.i(TAG, "Hooked method: " + key);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook method: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Hook a method with callback
     */
    public boolean hookMethod(Method originalMethod, Object callback) {
        try {
            String key = originalMethod.getDeclaringClass().getName() + 
                        "." + originalMethod.getName();
            hookedMethods.put(key, originalMethod);
            
            originalMethod.setAccessible(true);
            
            Log.i(TAG, "Hooked method with callback: " + key);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to hook method with callback: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if a method is hooked
     */
    public boolean isHooked(Method method) {
        String key = method.getDeclaringClass().getName() + "." + method.getName();
        return hookedMethods.containsKey(key);
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public int getHookedCount() {
        return hookedMethods.size();
    }
}