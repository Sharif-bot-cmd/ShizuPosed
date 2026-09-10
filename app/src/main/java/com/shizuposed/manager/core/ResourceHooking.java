package com.shizuposed.manager.core;

import android.content.Context;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;

public class ResourceHooking {
    private static final String TAG = "ResourceHooking";
    private static ResourceHooking instance;
    
    private Context context;
    private Logger logger;
    
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Object>> resourceOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> resourceNameOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> hookedPackages = new ConcurrentHashMap<>();
    private boolean initialized = false;
    
    private ResourceHooking(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
    }
    
    public static synchronized ResourceHooking getInstance(Context context) {
        if (instance == null) {
            instance = new ResourceHooking(context);
        }
        return instance;
    }
    
    public void init() {
        if (initialized) return;
        
        try {
            logger.i("Initializing ResourceHooking...");
            registerResourceHooks();
            initialized = true;
            logger.i("ResourceHooking initialized successfully");
        } catch (Exception e) {
            logger.e("Failed to initialize ResourceHooking: " + e.getMessage());
        }
    }
    
    private void registerResourceHooks() {
        try {
            HookEngine hookEngine = HookEngine.getInstance();
            if (!hookEngine.isInitialized()) {
                logger.w("HookEngine not initialized, cannot register resource hooks");
                return;
            }
            
            Class<?> resourcesClass = Class.forName("android.content.res.Resources");
            registerStringHook(resourcesClass, hookEngine);
            registerColorHook(resourcesClass, hookEngine);
            registerDrawableHook(resourcesClass, hookEngine);
            
            logger.i("Registered resource hooks");
        } catch (Exception e) {
            logger.e("Failed to register resource hooks: " + e.getMessage());
        }
    }
    
    private void registerStringHook(Class<?> resourcesClass, HookEngine hookEngine) {
        try {
            Method method = resourcesClass.getMethod("getString", int.class);
            hookEngine.hookMethod(method, new ResourceHookCallback() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    int id = (int) param.args[0];
                    String packageName = getPackageName(param.thisObject);
                    Object replacement = getReplacement(packageName, id);
                    if (replacement instanceof String) {
                        return replacement;
                    }
                    return param.thisObject.getClass()
                        .getMethod("getString", int.class)
                        .invoke(param.thisObject, id);
                }
            });
            logger.d("Hooked Resources.getString(int)");
        } catch (Exception e) {
            logger.e("Failed to hook getString: " + e.getMessage());
        }
    }
    
    private void registerColorHook(Class<?> resourcesClass, HookEngine hookEngine) {
        try {
            Method method = resourcesClass.getMethod("getColor", int.class);
            hookEngine.hookMethod(method, new ResourceHookCallback() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    int id = (int) param.args[0];
                    String packageName = getPackageName(param.thisObject);
                    Object replacement = getReplacement(packageName, id);
                    if (replacement instanceof Integer) {
                        return replacement;
                    }
                    return param.thisObject.getClass()
                        .getMethod("getColor", int.class)
                        .invoke(param.thisObject, id);
                }
            });
            logger.d("Hooked Resources.getColor(int)");
        } catch (Exception e) {
            logger.e("Failed to hook getColor: " + e.getMessage());
        }
    }
    
    private void registerDrawableHook(Class<?> resourcesClass, HookEngine hookEngine) {
        try {
            Method method = resourcesClass.getMethod("getDrawable", int.class);
            hookEngine.hookMethod(method, new ResourceHookCallback() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    int id = (int) param.args[0];
                    String packageName = getPackageName(param.thisObject);
                    Object replacement = getReplacement(packageName, id);
                    if (replacement != null) {
                        return replacement;
                    }
                    return param.thisObject.getClass()
                        .getMethod("getDrawable", int.class)
                        .invoke(param.thisObject, id);
                }
            });
            logger.d("Hooked Resources.getDrawable(int)");
        } catch (Exception e) {
            logger.e("Failed to hook getDrawable: " + e.getMessage());
        }
    }
    
    private String getPackageName(Object resources) {
        try {
            Field field = resources.getClass().getDeclaredField("mPackageName");
            field.setAccessible(true);
            return (String) field.get(resources);
        } catch (Exception e) {
            return "";
        }
    }
    
    // ============================================================
    // PUBLIC API - Resource Replacement
    // ============================================================
    
    public void setReplacement(String packageName, int id, Object replacement) {
        try {
            ConcurrentHashMap<Integer, Object> overrides = resourceOverrides.get(packageName);
            if (overrides == null) {
                overrides = new ConcurrentHashMap<>();
                resourceOverrides.put(packageName, overrides);
            }
            overrides.put(id, replacement);
            
            ModuleLoader moduleLoader = ModuleLoader.getInstance(context);
            moduleLoader.notifyResourceChange(packageName, id, replacement);
            
            logger.i("Resource replacement set: " + packageName + " ID: 0x" + Integer.toHexString(id));
        } catch (Exception e) {
            logger.e("Failed to set replacement: " + e.getMessage());
        }
    }
    
    public void setReplacementByName(String packageName, String resourceName, Object replacement) {
        try {
            ConcurrentHashMap<String, Object> overrides = resourceNameOverrides.get(packageName);
            if (overrides == null) {
                overrides = new ConcurrentHashMap<>();
                resourceNameOverrides.put(packageName, overrides);
            }
            overrides.put(resourceName, replacement);
            logger.i("Resource replacement set by name: " + packageName + " " + resourceName);
        } catch (Exception e) {
            logger.e("Failed to set replacement by name: " + e.getMessage());
        }
    }
    
    public Object getReplacement(String packageName, int id) {
        ConcurrentHashMap<Integer, Object> overrides = resourceOverrides.get(packageName);
        if (overrides != null) {
            return overrides.get(id);
        }
        return null;
    }
    
    public Object getReplacementByName(String packageName, String resourceName) {
        ConcurrentHashMap<String, Object> overrides = resourceNameOverrides.get(packageName);
        if (overrides != null) {
            return overrides.get(resourceName);
        }
        return null;
    }
    
    // ============================================================
    // ADDED: getOverrides method for ResourceHookingBridge
    // ============================================================
    public ConcurrentHashMap<Integer, Object> getOverrides(String packageName) {
        return resourceOverrides.get(packageName);
    }
    
    public void clearOverrides(String packageName) {
        resourceOverrides.remove(packageName);
        resourceNameOverrides.remove(packageName);
        hookedPackages.remove(packageName);
        logger.i("Resource overrides cleared for: " + packageName);
    }
    
    public void clearAllOverrides() {
        resourceOverrides.clear();
        resourceNameOverrides.clear();
        hookedPackages.clear();
        logger.i("All resource overrides cleared");
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    // ============================================================
    // Inner Classes
    // ============================================================
    
    public abstract static class XC_MethodReplacement {
        protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
    }
    
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean hasResult;
        public boolean hasThrowable;
        
        public void setResult(Object result) {
            this.result = result;
            this.hasResult = true;
        }
        
        public Object getResult() {
            return result;
        }
    }
    
    private abstract static class ResourceHookCallback extends XC_MethodReplacement {
        @Override
        protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
    }
}