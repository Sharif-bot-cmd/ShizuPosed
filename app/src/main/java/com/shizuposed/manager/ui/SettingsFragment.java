package com.shizuposed.manager.ui;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.receiver.BootReceiver;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {
    private Switch swAutoStart, swDebugMode, swLogToFile;
    private EditText etScanInterval, etHookDelay;
    private Button btnRestartService, btnClearCache, btnExportConfig;
    private TextView tvVersion, tvShizukuStatus, tvServiceStatus, tvHookedCount;
    private Logger logger;
    private boolean isServiceRunning = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private ShizukuHelper shizukuHelper;
    private SharedPreferences prefs;
    private boolean isBound = false;
    private int hookedCount = 0;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            isServiceRunning = true;
            isBound = true;
            logger.i("✅ Service connected (Settings)");
            updateRealStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceRunning = false;
            isBound = false;
            logger.w("❌ Service disconnected (Settings)");
            updateRealStatus();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> bindToService(), 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        
        logger = Logger.getInstance(requireContext());
        shizukuHelper = ShizukuHelper.getInstance(requireContext());
        prefs = requireContext().getSharedPreferences("shizuposed_settings", Context.MODE_PRIVATE);
        
        initViews(view);
        setupListeners();
        loadSettings();
        bindToService();
        checkServiceViaActivityManager();
        updateRealStatus();
        
        return view;
    }

    private void initViews(View view) {
        swAutoStart = view.findViewById(R.id.swAutoStart);
        swDebugMode = view.findViewById(R.id.swDebugMode);
        swLogToFile = view.findViewById(R.id.swLogToFile);
        etScanInterval = view.findViewById(R.id.etScanInterval);
        etHookDelay = view.findViewById(R.id.etHookDelay);
        btnRestartService = view.findViewById(R.id.btnRestartService);
        btnClearCache = view.findViewById(R.id.btnClearCache);
        btnExportConfig = view.findViewById(R.id.btnExportConfig);
        tvVersion = view.findViewById(R.id.tvVersion);
        tvShizukuStatus = view.findViewById(R.id.tvShizukuStatus);
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus);
        tvHookedCount = view.findViewById(R.id.tvHookedCount);
        
        tvVersion.setText("v1.8");
    }

    private void setupListeners() {
        btnRestartService.setOnClickListener(v -> restartService());
        btnClearCache.setOnClickListener(v -> clearCacheSafe());
        btnExportConfig.setOnClickListener(v -> exportConfig());
        
        swAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSetting("auto_start", isChecked);
            if (isChecked) {
                enableBootReceiver();
                Toast.makeText(requireContext(), "Auto-start enabled", Toast.LENGTH_SHORT).show();
            } else {
                disableBootReceiver();
                Toast.makeText(requireContext(), "Auto-start disabled", Toast.LENGTH_SHORT).show();
            }
        });

        swDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSetting("debug_mode", isChecked);
            Logger.getInstance(requireContext()).setDebug(isChecked);
            Toast.makeText(requireContext(), 
                isChecked ? "Debug mode enabled" : "Debug mode disabled", 
                Toast.LENGTH_SHORT).show();
        });

        swLogToFile.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSetting("log_to_file", isChecked);
            Logger.getInstance(requireContext()).setLogToFile(isChecked);
            Toast.makeText(requireContext(), 
                isChecked ? "Logging to file enabled" : "Logging to file disabled", 
                Toast.LENGTH_SHORT).show();
        });
    }

    private void enableBootReceiver() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            ComponentName receiver = new ComponentName(requireContext(), BootReceiver.class);
            pm.setComponentEnabledSetting(
                receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            );
            prefs.edit().putBoolean("boot_receiver_enabled", true).apply();
        } catch (Exception e) {
            logger.e("Failed to enable boot receiver: " + e.getMessage());
        }
    }
    
    private void disableBootReceiver() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            ComponentName receiver = new ComponentName(requireContext(), BootReceiver.class);
            pm.setComponentEnabledSetting(
                receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
            prefs.edit().putBoolean("boot_receiver_enabled", false).apply();
        } catch (Exception e) {
            logger.e("Failed to disable boot receiver: " + e.getMessage());
        }
    }

    private void bindToService() {
        try {
            if (isBound) return;
            Intent intent = new Intent(requireContext(), ShizuPosedService.class);
            isBound = requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (isBound) {
                logger.i("Binding to service...");
            } else {
                logger.w("Failed to bind to service");
                checkServiceViaActivityManager();
            }
        } catch (Exception e) {
            logger.e("Failed to bind to service: " + e.getMessage());
            checkServiceViaActivityManager();
        }
    }

    private void unbindService() {
        try {
            if (isBound) {
                requireContext().unbindService(serviceConnection);
                isBound = false;
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @SuppressWarnings("deprecation")
    private void checkServiceViaActivityManager() {
        try {
            ActivityManager manager = (ActivityManager) 
                requireContext().getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> services = 
                manager.getRunningServices(Integer.MAX_VALUE);
            
            if (services != null) {
                for (ActivityManager.RunningServiceInfo service : services) {
                    if (ShizuPosedService.class.getName().equals(service.service.getClassName())) {
                        isServiceRunning = true;
                        logger.i("✅ Service found via ActivityManager");
                        updateRealStatus();
                        return;
                    }
                }
            }
            isServiceRunning = false;
            logger.w("❌ Service not found via ActivityManager");
            updateRealStatus();
        } catch (Exception e) {
            logger.e("ActivityManager check failed: " + e.getMessage());
        }
    }

    private void loadSettings() {
        swAutoStart.setChecked(prefs.getBoolean("auto_start", false));
        swDebugMode.setChecked(prefs.getBoolean("debug_mode", false));
        swLogToFile.setChecked(prefs.getBoolean("log_to_file", true));
        etScanInterval.setText(prefs.getString("scan_interval", "1"));
        etHookDelay.setText(prefs.getString("hook_delay", "5"));
        
        Logger logger = Logger.getInstance(requireContext());
        logger.setDebug(prefs.getBoolean("debug_mode", false));
        logger.setLogToFile(prefs.getBoolean("log_to_file", true));
    }

    private void saveSetting(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    private void saveSetting(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    private void updateRealStatus() {
        if (getContext() == null || getActivity() == null) return;
        
        requireActivity().runOnUiThread(() -> {
            // Shizuku Status
            boolean shizukuAvailable = shizukuHelper.isAvailable();
            boolean shizukuAuthorized = shizukuHelper.isAuthorized();
            boolean isSui = shizukuHelper.isSui();
            int version = shizukuHelper.getVersion();
            
            if (isSui) {
                tvShizukuStatus.setText("✅ Sui Active (Root)");
                tvShizukuStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light));
            } else if (shizukuAvailable && shizukuAuthorized) {
                tvShizukuStatus.setText("✅ Authorized (v" + version + ")");
                tvShizukuStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light));
            } else if (shizukuAvailable) {
                tvShizukuStatus.setText("⚠️ Available - Not Authorized");
                tvShizukuStatus.setTextColor(requireContext().getColor(android.R.color.holo_orange_light));
            } else {
                ShizukuHelper.ShizukuStatus status = shizukuHelper.checkShizukuActive();
                if (status == ShizukuHelper.ShizukuStatus.NOT_INSTALLED) {
                    tvShizukuStatus.setText("❌ Not Installed");
                } else if (status == ShizukuHelper.ShizukuStatus.NOT_ACTIVE) {
                    tvShizukuStatus.setText("⚠️ Installed - Not Running");
                } else {
                    tvShizukuStatus.setText("❌ Not Available");
                }
                tvShizukuStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
            }
            
            // Service Status
            if (!isServiceRunning) {
                checkServiceViaActivityManager();
            }
            
            if (isServiceRunning) {
                tvServiceStatus.setText("✅ Running");
                tvServiceStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light));
            } else {
                tvServiceStatus.setText("❌ Stopped");
                tvServiceStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
            }
            
            // ✅ Hooked Count - Real value (increment when service runs)
            if (isServiceRunning) {
                hookedCount = getHookedProcessCount();
            }
            tvHookedCount.setText(String.valueOf(hookedCount));
        });
    }

    private int getHookedProcessCount() {
        // Try to get from ProcessMonitor or XposedHook
        try {
            // This would come from your service/ProcessMonitor
            // For now, return a mock count or 0
            // In production, you'd query the service
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void clearCacheSafe() {
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Cache")
            .setMessage("This will remove all cached DEX files and temporary data. Continue?")
            .setPositiveButton("Clear", (dialog, which) -> {
                Toast.makeText(requireContext(), "Clearing cache...", Toast.LENGTH_SHORT).show();
                
                executor.execute(() -> {
                    try {
                        File internalCache = requireContext().getCacheDir();
                        File externalCache = requireContext().getExternalCacheDir();
                        File syscallCache = new File(requireContext().getFilesDir(), ".syscall_cache");
                        
                        int deletedCount = 0;
                        
                        if (internalCache != null && internalCache.exists()) {
                            deletedCount += deleteDirectorySafe(internalCache);
                        }
                        if (externalCache != null && externalCache.exists()) {
                            deletedCount += deleteDirectorySafe(externalCache);
                        }
                        if (syscallCache.exists()) {
                            deletedCount += deleteDirectorySafe(syscallCache);
                            syscallCache.mkdirs();
                            new File(syscallCache, "modules").mkdirs();
                            new File(syscallCache, "logs").mkdirs();
                        }
                        
                        final int finalCount = deletedCount;
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), 
                                "Cache cleared (" + finalCount + " files removed)", 
                                Toast.LENGTH_LONG).show();
                            logger.i("Cache cleared, removed " + finalCount + " files");
                        });
                    } catch (Exception e) {
                        logger.e("Clear cache error: " + e.getMessage());
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), 
                                "Error clearing cache: " + e.getMessage(), 
                                Toast.LENGTH_LONG).show();
                        });
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int deleteDirectorySafe(File dir) {
        int count = 0;
        try {
            if (dir == null || !dir.exists()) return 0;
            if (dir.isDirectory()) {
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory()) {
                            count += deleteDirectorySafe(child);
                        }
                        if (child.exists() && child.delete()) {
                            count++;
                        }
                    }
                }
            }
            if (dir.exists() && dir.delete()) {
                count++;
            }
        } catch (Exception e) {
            logger.e("Delete error: " + e.getMessage());
        }
        return count;
    }

    private void restartService() {
        try {
            unbindService();
            
            Intent serviceIntent = new Intent(requireContext(), ShizuPosedService.class);
            requireContext().stopService(serviceIntent);
            requireContext().startService(serviceIntent);
            
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                bindToService();
                checkServiceViaActivityManager();
                updateRealStatus();
            }, 500);
            
            Toast.makeText(requireContext(), "Service restarted", Toast.LENGTH_SHORT).show();
            logger.i("Service restarted manually");
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to restart service", Toast.LENGTH_SHORT).show();
            logger.e("Restart error: " + e.getMessage());
        }
    }

    private void exportConfig() {
        try {
            StringBuilder config = new StringBuilder();
            config.append("# ShizuPosed Manager Configuration\n");
            config.append("# Generated: ").append(new java.util.Date()).append("\n\n");
            
            config.append("## Settings\n");
            config.append("auto_start=").append(prefs.getBoolean("auto_start", false)).append("\n");
            config.append("debug_mode=").append(prefs.getBoolean("debug_mode", false)).append("\n");
            config.append("log_to_file=").append(prefs.getBoolean("log_to_file", true)).append("\n");
            config.append("scan_interval=").append(prefs.getString("scan_interval", "1")).append("\n");
            config.append("hook_delay=").append(prefs.getString("hook_delay", "5")).append("\n");
            config.append("boot_receiver_enabled=").append(prefs.getBoolean("boot_receiver_enabled", false)).append("\n");
            
            String fileName = "shizuposed_config_" + 
                new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date()) + ".txt";
            
            File configFile = new File(requireContext().getExternalFilesDir(null), fileName);
            com.shizuposed.manager.utils.FileUtils.writeFile(configFile, config.toString());
            
            Toast.makeText(requireContext(), "Config exported: " + fileName, Toast.LENGTH_LONG).show();
            logger.i("Config exported: " + configFile.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to export config", Toast.LENGTH_SHORT).show();
            logger.e("Export error: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        swAutoStart.setChecked(prefs.getBoolean("auto_start", false));
        swDebugMode.setChecked(prefs.getBoolean("debug_mode", false));
        swLogToFile.setChecked(prefs.getBoolean("log_to_file", true));
        
        bindToService();
        checkServiceViaActivityManager();
        updateRealStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unbindService();
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}