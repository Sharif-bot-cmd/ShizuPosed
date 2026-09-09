package com.shizuposed.manager.network;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class RemoteModuleManager {
    private static final String TAG = "RemoteModuleManager";
    private static RemoteModuleManager instance;
    
    private Context context;
    private Logger logger;
    private Gson gson;
    private ExecutorService executorService;
    
    // Remote repository URLs
    private static final String REPO_URL = "https://api.shizuposed.com/modules";
    private static final String MODULE_DOWNLOAD_URL = "https://api.shizuposed.com/modules/download";
    
    private RemoteModuleManager(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        this.gson = new Gson();
        this.executorService = Executors.newCachedThreadPool();
    }
    
    public static synchronized RemoteModuleManager getInstance(Context context) {
        if (instance == null) {
            instance = new RemoteModuleManager(context);
        }
        return instance;
    }
    
    /**
     * Fetch available modules from remote repository
     */
    public void fetchAvailableModules(ModuleFetchCallback callback) {
        executorService.execute(() -> {
            try {
                URL url = new URL(REPO_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "ShizuPosedManager/1.5");
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    // Parse response
                    JsonObject json = gson.fromJson(response.toString(), JsonObject.class);
                    JsonArray modulesArray = json.getAsJsonArray("modules");
                    
                    List<ModuleInfo> modules = new ArrayList<>();
                    for (int i = 0; i < modulesArray.size(); i++) {
                        JsonObject moduleJson = modulesArray.get(i).getAsJsonObject();
                        ModuleInfo module = gson.fromJson(moduleJson, ModuleInfo.class);
                        modules.add(module);
                    }
                    
                    if (callback != null) {
                        callback.onSuccess(modules);
                    }
                } else {
                    if (callback != null) {
                        callback.onError("Server error: " + responseCode);
                    }
                }
                
                connection.disconnect();
                
            } catch (Exception e) {
                logger.e("Failed to fetch modules: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * Download a module from remote repository
     */
    public void downloadModule(String moduleId, String packageName, DownloadCallback callback) {
        executorService.execute(() -> {
            try {
                // Prepare download request
                URL url = new URL(MODULE_DOWNLOAD_URL + "?id=" + moduleId);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Save APK to cache
                    java.io.File cacheDir = context.getCacheDir();
                    java.io.File apkFile = new java.io.File(cacheDir, packageName + ".apk");
                    
                    java.io.InputStream inputStream = connection.getInputStream();
                    java.io.FileOutputStream outputStream = new java.io.FileOutputStream(apkFile);
                    
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    long contentLength = connection.getContentLength();
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                        
                        if (callback != null && contentLength > 0) {
                            int progress = (int) ((totalBytes * 100) / contentLength);
                            callback.onProgress(progress);
                        }
                    }
                    
                    outputStream.close();
                    inputStream.close();
                    
                    if (callback != null) {
                        callback.onSuccess(apkFile.getAbsolutePath());
                    }
                } else {
                    if (callback != null) {
                        callback.onError("Download failed: " + responseCode);
                    }
                }
                
                connection.disconnect();
                
            } catch (Exception e) {
                logger.e("Failed to download module: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * Check for module updates
     */
    public void checkUpdates(String packageName, UpdateCheckCallback callback) {
        executorService.execute(() -> {
            try {
                URL url = new URL(REPO_URL + "/updates?package=" + packageName);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    JsonObject json = gson.fromJson(response.toString(), JsonObject.class);
                    boolean hasUpdate = json.get("hasUpdate").getAsBoolean();
                    String version = json.get("version").getAsString();
                    String changelog = json.get("changelog").getAsString();
                    
                    if (callback != null) {
                        callback.onResult(hasUpdate, version, changelog);
                    }
                } else {
                    if (callback != null) {
                        callback.onError("Update check failed: " + responseCode);
                    }
                }
                
                connection.disconnect();
                
            } catch (Exception e) {
                logger.e("Failed to check updates: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * Submit module feedback
     */
    public void submitFeedback(String moduleId, String feedback, FeedbackCallback callback) {
        executorService.execute(() -> {
            try {
                URL url = new URL(REPO_URL + "/feedback");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                
                JsonObject json = new JsonObject();
                json.addProperty("moduleId", moduleId);
                json.addProperty("feedback", feedback);
                json.addProperty("timestamp", System.currentTimeMillis());
                
                String jsonString = gson.toJson(json);
                
                OutputStream os = connection.getOutputStream();
                os.write(jsonString.getBytes());
                os.flush();
                os.close();
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    if (callback != null) {
                        callback.onSuccess("Feedback submitted");
                    }
                } else {
                    if (callback != null) {
                        callback.onError("Failed to submit feedback: " + responseCode);
                    }
                }
                
                connection.disconnect();
                
            } catch (Exception e) {
                logger.e("Failed to submit feedback: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    // Callback interfaces
    public interface ModuleFetchCallback {
        void onSuccess(List<ModuleInfo> modules);
        void onError(String error);
    }
    
    public interface DownloadCallback {
        void onSuccess(String apkPath);
        void onProgress(int progress);
        void onError(String error);
    }
    
    public interface UpdateCheckCallback {
        void onResult(boolean hasUpdate, String version, String changelog);
        void onError(String error);
    }
    
    public interface FeedbackCallback {
        void onSuccess(String message);
        void onError(String error);
    }
}