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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.receiver.BootReceiver;
import com.shizuposed.manager.runtime.RuntimePrefs;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SettingsFragment
 *
 * App settings:
 *   • Runtime toggles (auto-start, debug, log-to-file)
 *   • Scan interval slider (ProcessMonitor refresh rate)
 *   • Hook delay slider (framework-to-bootstrap pause)
 *   • Cache management
 *   • Config export
 *
 * The XStealth master toggle lives in the XStealth detail sheet,
 * not here.
 *
 * SCAN INTERVAL / HOOK DELAY
 * --------------------------
 * Both are stored in RuntimePrefs and read at their point of use:
 *   • Scan interval: ProcessMonitor.startMonitoring()
 *   • Hook delay: XposedHook via -Dshizuposed.hook.delay
 *
 * The scan interval takes effect on the next service start (it's
 * read when the scheduler is created). The hook delay takes effect
 * on the next app launch, because it's passed as a -D property to
 * app_process.
 */
public class SettingsFragment extends Fragment {

    private MaterialSwitch swAutoStart, swDebugMode, swLogToFile;
    private Slider sliderScanInterval, sliderHookDelay;
    private TextView tvScanIntervalValue, tvHookDelayValue;
    private Button btnClearCache, btnExportConfig;
    private TextView tvVersion, tvShizukuStatus, tvServiceStatus;

    private Logger logger;
    private ShizukuHelper shizukuHelper;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
        prefs = context.getSharedPreferences("shizuposed_settings",
            Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        initViews(view);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
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
        sliderScanInterval = null;
        sliderHookDelay = null;
        tvScanIntervalValue = null;
        tvHookDelayValue = null;
        btnClearCache = null;
        btnExportConfig = null;
        tvVersion = null;
        tvShizukuStatus = null;
        tvServiceStatus = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        // Executor kept for fragment lifetime; do not shut down here
        // because onDetach can happen on configuration change. It
        // gets GC'd with the fragment.
    }

    // ═════════════════════════════════════════════════════════════
    // VIEW + SETUP
    // ═════════════════════════════════════════════════════════════

    private void initViews(View view) {
        swAutoStart = view.findViewById(R.id.swAutoStart);
        swDebugMode = view.findViewById(R.id.swDebugMode);
        swLogToFile = view.findViewById(R.id.swLogToFile);
        sliderScanInterval = view.findViewById(R.id.sliderScanInterval);
        sliderHookDelay = view.findViewById(R.id.sliderHookDelay);
        tvScanIntervalValue = view.findViewById(R.id.tvScanIntervalValue);
        tvHookDelayValue = view.findViewById(R.id.tvHookDelayValue);
        btnClearCache = view.findViewById(R.id.btnClearCache);
        btnExportConfig = view.findViewById(R.id.btnExportConfig);
        tvVersion = view.findViewById(R.id.tvVersion);
        tvShizukuStatus = view.findViewById(R.id.tvShizukuStatus);
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus);

        if (tvVersion != null) tvVersion.setText("6.4");
    }

    private void setupListeners() {
        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> clearCacheSafe());
        }
        if (btnExportConfig != null) {
            btnExportConfig.setOnClickListener(v -> exportConfig());
        }
        if (swAutoStart != null) {
            swAutoStart.setOnCheckedChangeListener(autoStartListener);
        }
        if (swDebugMode != null) {
            swDebugMode.setOnCheckedChangeListener(debugModeListener);
        }
        if (swLogToFile != null) {
            swLogToFile.setOnCheckedChangeListener(logToFileListener);
        }

        // ── Scan interval slider ──────────────────────────────────
        if (sliderScanInterval != null) {
            sliderScanInterval.addOnChangeListener((slider, value, fromUser) -> {
                int ms = Math.round(value) * 1000;
                if (tvScanIntervalValue != null) {
                    tvScanIntervalValue.setText((ms / 1000) + " s");
                }
                if (fromUser && isAdded()) {
                    RuntimePrefs.setScanIntervalMs(requireContext(), ms);
                }
            });
        }

        // ── Hook delay slider ─────────────────────────────────────
        if (sliderHookDelay != null) {
            sliderHookDelay.addOnChangeListener((slider, value, fromUser) -> {
                int ms = Math.round(value);
                if (tvHookDelayValue != null) {
                    tvHookDelayValue.setText(ms + " ms");
                }
                if (fromUser && isAdded()) {
                    RuntimePrefs.setHookDelayMs(requireContext(), ms);
                }
            });
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LOAD / SAVE
    // ═════════════════════════════════════════════════════════════

    private void loadSettings() {
        if (prefs == null || !viewReady) return;

        // Detach listeners first so setChecked doesn't fire them.
        if (swAutoStart != null) swAutoStart.setOnCheckedChangeListener(null);
        if (swDebugMode != null) swDebugMode.setOnCheckedChangeListener(null);
        if (swLogToFile != null) swLogToFile.setOnCheckedChangeListener(null);

        if (swAutoStart != null) {
            swAutoStart.setChecked(prefs.getBoolean("auto_start", false));
        }
        if (swDebugMode != null) {
            swDebugMode.setChecked(prefs.getBoolean("debug_mode", false));
        }
        if (swLogToFile != null) {
            swLogToFile.setChecked(prefs.getBoolean("log_to_file", true));
        }

        // ── Sliders ───────────────────────────────────────────────
        // The sliders display values in seconds (scan) and
        // milliseconds (hook delay). RuntimePrefs stores everything
        // in milliseconds.
        if (sliderScanInterval != null) {
            int ms = RuntimePrefs.getScanIntervalMs(requireContext());
            sliderScanInterval.setValue(ms / 1000f);
            if (tvScanIntervalValue != null) {
                tvScanIntervalValue.setText((ms / 1000) + " s");
            }
        }
        if (sliderHookDelay != null) {
            int ms = RuntimePrefs.getHookDelayMs(requireContext());
            sliderHookDelay.setValue(ms);
            if (tvHookDelayValue != null) {
                tvHookDelayValue.setText(ms + " ms");
            }
        }

        // Reattach listeners.
        if (swAutoStart != null) {
            swAutoStart.setOnCheckedChangeListener(autoStartListener);
        }
        if (swDebugMode != null) {
            swDebugMode.setOnCheckedChangeListener(debugModeListener);
        }
        if (swLogToFile != null) {
            swLogToFile.setOnCheckedChangeListener(logToFileListener);
        }

        if (logger != null) {
            logger.setDebug(prefs.getBoolean("debug_mode", false));
            logger.setLogToFile(prefs.getBoolean("log_to_file", true));
        }
    }

    private void saveSetting(String key, boolean value) {
        if (prefs == null) return;
        prefs.edit().putBoolean(key, value).apply();
    }

    // ═════════════════════════════════════════════════════════════
    // BOOT RECEIVER + SERVICE
    // ═════════════════════════════════════════════════════════════

    private void enableBootReceiver() {
        if (!isAdded()) return;
        try {
            PackageManager pm = requireContext().getPackageManager();
            ComponentName receiver = new ComponentName(
                requireContext(), BootReceiver.class);
            pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
            if (prefs != null) {
                prefs.edit().putBoolean("boot_receiver_enabled", true).apply();
            }
            if (logger != null) logger.i("BootReceiver enabled");
        } catch (Exception e) {
            if (logger != null) {
                logger.e("Failed to enable boot receiver: " + e.getMessage());
            }
        }
    }

    private void disableBootReceiver() {
        if (!isAdded()) return;
        try {
            PackageManager pm = requireContext().getPackageManager();
            ComponentName receiver = new ComponentName(
                requireContext(), BootReceiver.class);
            pm.setComponentEnabledSetting(receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
            if (prefs != null) {
                prefs.edit().putBoolean("boot_receiver_enabled", false).apply();
            }
            if (logger != null) logger.i("BootReceiver disabled");
        } catch (Exception e) {
            if (logger != null) {
                logger.e("Failed to disable boot receiver: " + e.getMessage());
            }
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
            if (logger != null) {
                logger.w("maybeStartServiceIfAuthorized failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // STATUS
    // ═════════════════════════════════════════════════════════════

    private void updateRealStatus() {
        if (!viewReady || !isAdded() || getActivity() == null) return;
        if (tvShizukuStatus == null || tvServiceStatus == null) return;

        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || tvShizukuStatus == null) return;

            boolean shizukuAvailable = shizukuHelper != null
                && shizukuHelper.isAvailable();
            boolean shizukuAuthorized = shizukuHelper != null
                && shizukuHelper.isAuthorized();
            boolean isSui = shizukuHelper != null && shizukuHelper.isSui();
            int version = shizukuHelper == null ? 0 : shizukuHelper.getVersion();

            if (isSui) {
                tvShizukuStatus.setText("Active (Sui)");
                tvShizukuStatus.setTextColor(requireContext()
                    .getColor(android.R.color.holo_green_light));
            } else if (shizukuAvailable && shizukuAuthorized) {
                tvShizukuStatus.setText("Authorized (v" + version + ")");
                tvShizukuStatus.setTextColor(requireContext()
                    .getColor(android.R.color.holo_green_light));
            } else if (shizukuAvailable) {
                tvShizukuStatus.setText("Available — not authorized");
                tvShizukuStatus.setTextColor(requireContext()
                    .getColor(android.R.color.holo_orange_light));
            } else {
                ShizukuHelper.ShizukuStatus status = shizukuHelper == null
                    ? ShizukuHelper.ShizukuStatus.NOT_INSTALLED
                    : shizukuHelper.checkShizukuActive();
                if (status == ShizukuHelper.ShizukuStatus.NOT_INSTALLED) {
                    tvShizukuStatus.setText("Not installed");
                } else if (status == ShizukuHelper.ShizukuStatus.NOT_ACTIVE) {
                    tvShizukuStatus.setText("Installed — not running");
                } else {
                    tvShizukuStatus.setText("Not available");
                }
                tvShizukuStatus.setTextColor(requireContext()
                    .getColor(android.R.color.holo_red_light));
            }

            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            boolean serviceRunning =
                ShizuPosedService.isServiceRunning()
                || (app != null && app.isServiceAutoStarted());

            if (tvServiceStatus != null) {
                if (serviceRunning) {
                    tvServiceStatus.setText("Running");
                    tvServiceStatus.setTextColor(requireContext()
                        .getColor(android.R.color.holo_green_light));
                } else {
                    tvServiceStatus.setText("Stopped");
                    tvServiceStatus.setTextColor(requireContext()
                        .getColor(android.R.color.holo_red_light));
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════
    // CACHE
    // ═════════════════════════════════════════════════════════════

    private void clearCacheSafe() {
        if (!isAdded()) return;
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Cache")
            .setMessage("This will remove all cached DEX files and "
                + "temporary data. Continue?")
            .setPositiveButton("Clear", (dialog, which) -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Clearing cache…",
                    Toast.LENGTH_SHORT).show();

                final Context appCtx = requireContext().getApplicationContext();
                executor.execute(() -> {
                    int deletedCount = 0;
                    try {
                        File internalCache = appCtx.getCacheDir();
                        File externalCache = appCtx.getExternalCacheDir();
                        File syscallCache = new File(
                            appCtx.getFilesDir(), ".syscall_cache");

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
                    } catch (Exception e) {
                        if (logger != null) {
                            logger.e("Clear cache error: " + e.getMessage());
                        }
                    }

                    final int finalCount = deletedCount;
                    if (!isAdded() || getActivity() == null) return;
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(),
                            "Cache cleared (" + finalCount + " files)",
                            Toast.LENGTH_LONG).show();
                        if (logger != null) {
                            logger.i("Cache cleared, removed "
                                + finalCount + " files");
                        }
                    });
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

    // ═════════════════════════════════════════════════════════════
    // CONFIG EXPORT
    // ═════════════════════════════════════════════════════════════

    private void exportConfig() {
        if (!isAdded() || getContext() == null || prefs == null) return;
        try {
            StringBuilder config = new StringBuilder();
            config.append("# ShizuPosed Manager Configuration\n");
            config.append("# Generated: ").append(new java.util.Date())
                  .append("\n\n");
            config.append("## Settings\n");
            config.append("auto_start=")
                  .append(prefs.getBoolean("auto_start", false)).append("\n");
            config.append("debug_mode=")
                  .append(prefs.getBoolean("debug_mode", false)).append("\n");
            config.append("log_to_file=")
                  .append(prefs.getBoolean("log_to_file", true)).append("\n");
            config.append("scan_interval_ms=")
                  .append(RuntimePrefs.getScanIntervalMs(requireContext()))
                  .append("\n");
            config.append("hook_delay_ms=")
                  .append(RuntimePrefs.getHookDelayMs(requireContext()))
                  .append("\n");
            config.append("boot_receiver_enabled=")
                  .append(prefs.getBoolean("boot_receiver_enabled", false))
                  .append("\n");

            config.append("\n## XStealth\n");
            config.append("xstealth_enabled=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isEnabled(requireContext())).append("\n");
            config.append("xstealth_next_enabled=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isNextEnabled(requireContext())).append("\n");
            config.append("xstealth_hide_dev_options=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isHideDevOptions(requireContext())).append("\n");
            config.append("xstealth_hide_adb=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isHideAdb(requireContext())).append("\n");
            config.append("xstealth_hide_shizuku_package=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isHideShizukuPackage(requireContext())).append("\n");
            config.append("xstealth_hide_shizuposed_package=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isHideShizuPosedPackage(requireContext())).append("\n");
            config.append("xstealth_hide_running_processes=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isHideRunningProcesses(requireContext())).append("\n");
            config.append("xstealth_hide_procfs=")
                  .append(com.shizuposed.manager.stealth.XStealthPrefs
                      .isHideProcFs(requireContext())).append("\n");

            String fileName = "shizuposed_config_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.getDefault())
                    .format(new java.util.Date()) + ".txt";
            File configFile = new File(
                requireContext().getExternalFilesDir(null), fileName);
            com.shizuposed.manager.utils.FileUtils.writeFile(
                configFile, config.toString());

            Toast.makeText(requireContext(),
                "Config exported: " + fileName, Toast.LENGTH_LONG).show();
            if (logger != null) {
                logger.i("Config exported: " + configFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to export config",
                Toast.LENGTH_SHORT).show();
            if (logger != null) logger.e("Export error: " + e.getMessage());
        }
    }
}