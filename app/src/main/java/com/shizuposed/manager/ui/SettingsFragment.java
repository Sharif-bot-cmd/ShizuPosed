package com.shizuposed.manager.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.receiver.BootReceiver;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SettingsFragment
 *
 * App settings. The XStealth master toggle no longer lives here —
 * it moved to the XStealth detail sheet, which is reached by
 * tapping the XStealth row in the Modules tab. Settings keeps the
 * runtime toggles (auto-start, debug, log-to-file), cache
 * management, and config export.
 */
public class SettingsFragment extends Fragment {
    private MaterialSwitch swAutoStart, swDebugMode, swLogToFile;
    private EditText etScanInterval, etHookDelay;
    private Button btnClearCache, btnExportConfig;
    private TextView tvVersion, tvShizukuStatus, tvServiceStatus;

    private Logger logger;
    private ShizukuHelper shizukuHelper;
    private SharedPreferences prefs;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean viewReady = false;

    private final CompoundButton.OnCheckedChangeListener autoStartListener =
        (buttonView, isChecked) -> {
            saveSetting("auto_start", isChecked);
            if (isChecked) {
                enableBootReceiver();
                maybeStartServiceIfAuthorized();
                if (isAdded()) Toast.makeText(requireContext(),
                    "Auto-start enabled — service will start after boot",
                    Toast.LENGTH_SHORT).show();
            } else {
                disableBootReceiver();
                if (isAdded()) Toast.makeText(requireContext(),
                    "Auto-start disabled", Toast.LENGTH_SHORT).show();
            }
        };

    private final CompoundButton.OnCheckedChangeListener debugModeListener =
        (buttonView, isChecked) -> {
            saveSetting("debug_mode", isChecked);
            if (logger != null) logger.setDebug(isChecked);
            if (isAdded()) Toast.makeText(requireContext(),
                isChecked ? "Debug mode enabled" : "Debug mode disabled",
                Toast.LENGTH_SHORT).show();
        };

    private final CompoundButton.OnCheckedChangeListener logToFileListener =
        (buttonView, isChecked) -> {
            saveSetting("log_to_file", isChecked);
            if (logger != null) logger.setLogToFile(isChecked);
            if (isAdded()) Toast.makeText(requireContext(),
                isChecked ? "Logging to file enabled" : "Logging to file disabled",
                Toast.LENGTH_SHORT).show();
        };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
        shizukuHelper = ShizukuHelper.getInstance(context);
        prefs = context.getSharedPreferences("shizuposed_settings", Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        initViews(view);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;
        setupListeners();
        loadSettings();
        updateRealStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (logger == null) return;
        loadSettings();
        updateRealStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        swAutoStart = null;
        swDebugMode = null;
        swLogToFile = null;
        etScanInterval = null;
        etHookDelay = null;
        btnClearCache = null;
        btnExportConfig = null;
        tvVersion = null;
        tvShizukuStatus = null;
        tvServiceStatus = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void initViews(View view) {
        swAutoStart = view.findViewById(R.id.swAutoStart);
        swDebugMode = view.findViewById(R.id.swDebugMode);
        swLogToFile = view.findViewById(R.id.swLogToFile);
        etScanInterval = view.findViewById(R.id.etScanInterval);
        etHookDelay = view.findViewById(R.id.etHookDelay);
        btnClearCache = view.findViewById(R.id.btnClearCache);
        btnExportConfig = view.findViewById(R.id.btnExportConfig);
        tvVersion = view.findViewById(R.id.tvVersion);
        tvShizukuStatus = view.findViewById(R.id.tvShizukuStatus);
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus);

        if (tvVersion != null) tvVersion.setText("5.8");
    }

    private void setupListeners() {
        if (btnClearCache != null) btnClearCache.setOnClickListener(v -> clearCacheSafe());
        if (btnExportConfig != null) btnExportConfig.setOnClickListener(v -> exportConfig());
        if (swAutoStart != null) swAutoStart.setOnCheckedChangeListener(autoStartListener);
        if (swDebugMode != null) swDebugMode.setOnCheckedChangeListener(debugModeListener);
        if (swLogToFile != null) swLogToFile.setOnCheckedChangeListener(logToFileListener);
    }

    private void enableBootReceiver() {
        if (!isAdded()) return;
        try {
            PackageManager pm = requireContext().getPackageManager();
            ComponentName receiver = new ComponentName(requireContext(), BootReceiver.class);
            pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
            if (prefs != null) prefs.edit().putBoolean("boot_receiver_enabled", true).apply();
            if (logger != null) logger.i("BootReceiver enabled");
        } catch (Exception e) {
            if (logger != null) logger.e("Failed to enable boot receiver: " + e.getMessage());
        }
    }

    private void disableBootReceiver() {
        if (!isAdded()) return;
        try {
            PackageManager pm = requireContext().getPackageManager();
            ComponentName receiver = new ComponentName(requireContext(), BootReceiver.class);
            pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            if (prefs != null) prefs.edit().putBoolean("boot_receiver_enabled", false).apply();
            if (logger != null) logger.i("BootReceiver disabled");
        } catch (Exception e) {
            if (logger != null) logger.e("Failed to disable boot receiver: " + e.getMessage());
        }
    }

    private void maybeStartServiceIfAuthorized() {
        if (!isAdded()) return;
        try {
            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            if (app == null) return;
            if (!app.isShizukuAuthorized()) return;
            app.autoStartService();
        } catch (Throwable t) {
            if (logger != null) logger.w("maybeStartServiceIfAuthorized failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void loadSettings() {
        if (prefs == null || !viewReady) return;
        if (swAutoStart == null) return;

        swAutoStart.setOnCheckedChangeListener(null);
        swDebugMode.setOnCheckedChangeListener(null);
        swLogToFile.setOnCheckedChangeListener(null);

        swAutoStart.setChecked(prefs.getBoolean("auto_start", false));
        swDebugMode.setChecked(prefs.getBoolean("debug_mode", false));
        swLogToFile.setChecked(prefs.getBoolean("log_to_file", true));

        if (etScanInterval != null) {
            etScanInterval.setText(prefs.getString("scan_interval", "1"));
        }
        if (etHookDelay != null) {
            etHookDelay.setText(prefs.getString("hook_delay", "5"));
        }

        swAutoStart.setOnCheckedChangeListener(autoStartListener);
        swDebugMode.setOnCheckedChangeListener(debugModeListener);
        swLogToFile.setOnCheckedChangeListener(logToFileListener);

        if (logger != null) {
            logger.setDebug(prefs.getBoolean("debug_mode", false));
            logger.setLogToFile(prefs.getBoolean("log_to_file", true));
        }
    }

    private void saveSetting(String key, boolean value) {
        if (prefs == null) return;
        prefs.edit().putBoolean(key, value).apply();
    }

    private void updateRealStatus() {
        if (!viewReady || !isAdded() || getActivity() == null) return;
        if (tvShizukuStatus == null || tvServiceStatus == null) return;

        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || tvShizukuStatus == null) return;

            boolean shizukuAvailable = shizukuHelper != null && shizukuHelper.isAvailable();
            boolean shizukuAuthorized = shizukuHelper != null && shizukuHelper.isAuthorized();
            boolean isSui = shizukuHelper != null && shizukuHelper.isSui();
            int version = shizukuHelper == null ? 0 : shizukuHelper.getVersion();

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
                ShizukuHelper.ShizukuStatus status = shizukuHelper == null
                    ? ShizukuHelper.ShizukuStatus.NOT_INSTALLED
                    : shizukuHelper.checkShizukuActive();
                if (status == ShizukuHelper.ShizukuStatus.NOT_INSTALLED) {
                    tvShizukuStatus.setText("❌ Not Installed");
                } else if (status == ShizukuHelper.ShizukuStatus.NOT_ACTIVE) {
                    tvShizukuStatus.setText("⚠️ Installed - Not Running");
                } else {
                    tvShizukuStatus.setText("❌ Not Available");
                }
                tvShizukuStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
            }

            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            boolean serviceRunning =
                ShizuPosedService.isServiceRunning()
                || (app != null && app.isServiceAutoStarted());

            if (tvServiceStatus != null) {
                if (serviceRunning) {
                    tvServiceStatus.setText("✅ Running");
                    tvServiceStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light));
                } else {
                    tvServiceStatus.setText("❌ Stopped");
                    tvServiceStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
                }
            }
        });
    }

    private void clearCacheSafe() {
        if (!isAdded() || executor == null) return;
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Cache")
            .setMessage("This will remove all cached DEX files and temporary data. Continue?")
            .setPositiveButton("Clear", (dialog, which) -> {
                if (!isAdded()) return;
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
                        if (!isAdded() || getActivity() == null) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            Toast.makeText(requireContext(),
                                "Cache cleared (" + finalCount + " files removed)",
                                Toast.LENGTH_LONG).show();
                            if (logger != null) logger.i("Cache cleared, removed " + finalCount + " files");
                        });
                    } catch (Exception e) {
                        if (logger != null) logger.e("Clear cache error: " + e.getMessage());
                        if (!isAdded() || getActivity() == null) return;
                        requireActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
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
                        if (child.isDirectory()) count += deleteDirectorySafe(child);
                        if (child.exists() && child.delete()) count++;
                    }
                }
            }
            if (dir.exists() && dir.delete()) count++;
        } catch (Exception e) {
            if (logger != null) logger.e("Delete error: " + e.getMessage());
        }
        return count;
    }

    private void exportConfig() {
        if (!isAdded() || getContext() == null || prefs == null) return;
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
            config.append("boot_receiver_enabled=")
                  .append(prefs.getBoolean("boot_receiver_enabled", false)).append("\n");

            config.append("\n## XStealth\n");
            config.append("xstealth_enabled=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isEnabled(requireContext()))
                  .append("\n");
            config.append("xstealth_next_enabled=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isNextEnabled(requireContext()))
                  .append("\n");
            config.append("xstealth_hide_dev_options=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isHideDevOptions(requireContext()))
                  .append("\n");
            config.append("xstealth_hide_adb=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isHideAdb(requireContext()))
                  .append("\n");
            config.append("xstealth_hide_shizuku_package=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isHideShizukuPackage(requireContext()))
                  .append("\n");
            config.append("xstealth_hide_shizuposed_package=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isHideShizuPosedPackage(requireContext()))
                  .append("\n");
            config.append("xstealth_hide_running_processes=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isHideRunningProcesses(requireContext()))
                  .append("\n");
            config.append("xstealth_hide_procfs=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs.isHideProcFs(requireContext()))
                  .append("\n");

            String fileName = "shizuposed_config_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date()) + ".txt";
            File configFile = new File(requireContext().getExternalFilesDir(null), fileName);
            com.shizuposed.manager.utils.FileUtils.writeFile(configFile, config.toString());

            Toast.makeText(requireContext(), "Config exported: " + fileName, Toast.LENGTH_LONG).show();
            if (logger != null) logger.i("Config exported: " + configFile.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to export config", Toast.LENGTH_SHORT).show();
            if (logger != null) logger.e("Export error: " + e.getMessage());
        }
    }
}