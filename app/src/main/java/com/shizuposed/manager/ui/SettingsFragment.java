package com.shizuposed.manager.ui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.cloak.ManagerCloakFlow;
import com.shizuposed.manager.receiver.BootReceiver;
import com.shizuposed.manager.runtime.RuntimePrefs;
import com.shizuposed.manager.runtime.SyskuController;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.updates.UpdateChecker;
import com.shizuposed.manager.updates.UpdateInstallActivity;
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
 *   • Hide ShizuPosed app / Unhide ShizuPosed app
 *   • Maintenance actions (clear logs, clean all caches)
 *   • Check for updates (GitHub Releases)
 *   • Config export
 *
 * The XStealth master toggle lives in the XStealth detail sheet,
 * not here.
 *
 * SYSKU
 * -----
 * The Sysku row is a two-part UI: a status line updated from
 * SyskuController.getState(), and a toggle switch that drives
 * SyskuController.setEnabled(). Both read from the same source
 * of truth ("sysku_enabled"), so a toggle is immediately
 * reflected in the next status refresh.
 *
 * HIDE / UNHIDE
 * -------------
 * The cloak flow patches the CURRENT APK's manifest in place,
 * re-signs with the per-install key, installs the patched APK
 * as a new package, launches it, and uninstalls the old
 * package. Unhide does the same in reverse, restoring the
 * original package name from the migrate payload.
 *
 * There is no dialog for package-name or label input. The new
 * package name is generated at runtime (same length as the
 * current one), and the launcher label is unchanged because it
 * is @string/app_name (ARSC, not AXML, so not patchable).
 *
 * CHECK FOR UPDATES
 * -----------------
 * Manual check via UpdateChecker.checkNowAsync. If no update
 * exists, shows "You're on the latest version (X)". If one
 * exists, shows a dialog with a Download button that opens
 * UpdateInstallActivity. The activity downloads the APK,
 * prompts for confirmation, and installs via pm install via
 * Shizuku.
 */
public class SettingsFragment extends Fragment {

    private MaterialSwitch swAutoStart, swDebugMode, swLogToFile, swSyskuEnabled;
    private Slider sliderScanInterval, sliderHookDelay;
    private TextView tvScanIntervalValue, tvHookDelayValue;
    private TextView tvSyskuStatus, tvSyskuHint;
    private Button btnClearLogs, btnCleanAll, btnExportConfig, btnCheckUpdates;
    private Button btnHideShizuPosed, btnUnhideShizuPosed;
    private TextView tvVersion, tvShizukuStatus, tvServiceStatus;

    private Logger logger;
    private ShizukuHelper shizukuHelper;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean viewReady = false;

    private BroadcastReceiver maintenanceReceiver;
    private boolean maintenanceReceiverRegistered = false;

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

    private final CompoundButton.OnCheckedChangeListener syskuToggleListener =
        (buttonView, isChecked) -> {
            if (!isAdded()) return;
            SyskuController.setEnabled(requireContext(), isChecked);
            if (logger != null) {
                logger.i("Sysku " + (isChecked ? "enabled" : "disabled")
                    + " by user");
            }
            Toast.makeText(requireContext(),
                isChecked
                    ? "Sysku enabled — takes effect on next launch"
                    : "Sysku disabled",
                Toast.LENGTH_SHORT).show();
            refreshSyskuStatus();
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
        refreshSyskuStatus();
        registerMaintenanceReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (logger == null) return;
        loadSettings();
        updateRealStatus();
        refreshSyskuStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        unregisterMaintenanceReceiver();
        swAutoStart = null;
        swDebugMode = null;
        swLogToFile = null;
        swSyskuEnabled = null;
        sliderScanInterval = null;
        sliderHookDelay = null;
        tvScanIntervalValue = null;
        tvHookDelayValue = null;
        tvSyskuStatus = null;
        tvSyskuHint = null;
        btnClearLogs = null;
        btnCleanAll = null;
        btnExportConfig = null;
        btnCheckUpdates = null;
        btnHideShizuPosed = null;
        btnUnhideShizuPosed = null;
        tvVersion = null;
        tvShizukuStatus = null;
        tvServiceStatus = null;
    }

    // ═════════════════════════════════════════════════════════════
    // VIEW + SETUP
    // ═════════════════════════════════════════════════════════════

    private void initViews(View view) {
        swAutoStart = view.findViewById(R.id.swAutoStart);
        swDebugMode = view.findViewById(R.id.swDebugMode);
        swLogToFile = view.findViewById(R.id.swLogToFile);
        swSyskuEnabled = view.findViewById(R.id.swSyskuEnabled);
        sliderScanInterval = view.findViewById(R.id.sliderScanInterval);
        sliderHookDelay = view.findViewById(R.id.sliderHookDelay);
        tvScanIntervalValue = view.findViewById(R.id.tvScanIntervalValue);
        tvHookDelayValue = view.findViewById(R.id.tvHookDelayValue);
        tvSyskuStatus = view.findViewById(R.id.tvSyskuStatus);
        tvSyskuHint = view.findViewById(R.id.tvSyskuHint);
        btnClearLogs = view.findViewById(R.id.btnClearLogs);
        btnCleanAll = view.findViewById(R.id.btnCleanAll);
        btnExportConfig = view.findViewById(R.id.btnExportConfig);
        btnCheckUpdates = view.findViewById(R.id.btnCheckUpdates);
        btnHideShizuPosed = view.findViewById(R.id.btnHideShizuPosed);
        btnUnhideShizuPosed = view.findViewById(R.id.btnUnhideShizuPosed);
        tvVersion = view.findViewById(R.id.tvVersion);
        tvShizukuStatus = view.findViewById(R.id.tvShizukuStatus);
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus);

        if (tvVersion != null) {
            try {
                String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0)
                    .versionName;
                tvVersion.setText(v != null ? v : "unknown");
            } catch (Throwable t) {
                tvVersion.setText("unknown");
            }
        }
    }

    private void setupListeners() {
        if (btnClearLogs != null) {
            btnClearLogs.setOnClickListener(v -> confirmClearLogs());
        }
        if (btnCleanAll != null) {
            btnCleanAll.setOnClickListener(v -> confirmCleanAll());
        }
        if (btnExportConfig != null) {
            btnExportConfig.setOnClickListener(v -> exportConfig());
        }
        if (btnCheckUpdates != null) {
            btnCheckUpdates.setOnClickListener(v -> checkForUpdates());
        }
        if (btnHideShizuPosed != null) {
            btnHideShizuPosed.setOnClickListener(v -> showHideConfirm());
        }
        if (btnUnhideShizuPosed != null) {
            btnUnhideShizuPosed.setOnClickListener(v -> confirmUncloak());
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
        if (swSyskuEnabled != null) {
            swSyskuEnabled.setOnCheckedChangeListener(syskuToggleListener);
        }

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
    // SYSKU
    // ═════════════════════════════════════════════════════════════

    private void refreshSyskuStatus() {
        if (!isAdded() || !viewReady) return;

        final Context appCtx = requireContext().getApplicationContext();

        final boolean enabled = SyskuController.isEnabled(appCtx);
        if (swSyskuEnabled != null) {
            swSyskuEnabled.setOnCheckedChangeListener(null);
            swSyskuEnabled.setChecked(enabled);
            swSyskuEnabled.setOnCheckedChangeListener(syskuToggleListener);
        }

        if (tvSyskuHint != null) {
            tvSyskuHint.setText(enabled
                ? "Syscall-based hook backend. Takes effect on next launch."
                : "Syscall-based hook backend. Off by default.");
        }

        executor.execute(() -> {
            if (executor.isShutdown()) return;
            final SyskuController.State st = SyskuController.getState(appCtx);
            final String label = SyskuController.describe(st);
            final int color = SyskuController.colorFor(st);

            if (!isAdded() || !viewReady || tvSyskuStatus == null) return;
            requireActivity().runOnUiThread(() -> {
                if (tvSyskuStatus == null) return;
                tvSyskuStatus.setText(label);
                tvSyskuStatus.setTextColor(color);
            });
        });
    }

    // ═════════════════════════════════════════════════════════════
    // HIDE / UNHIDE (cloak flow)
    // ═════════════════════════════════════════════════════════════

    private void showHideConfirm() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Hide ShizuPosed app?")
            .setMessage("This will patch the current APK's package name "
                + "to a random same-length name, re-sign it with a "
                + "per-install key, install it as a new package, and "
                + "uninstall this one.\n\n"
                + "The launcher will show a random package name instead "
                + "of \"com.shizuposed.manager\". The launcher label is "
                + "unchanged (it is a string resource, not a manifest "
                + "attribute).\n\n"
                + "The per-install key is generated once and survives "
                + "Unhide, so the signature stays stable across the "
                + "cloak/uncloak cycle.")
            .setPositiveButton("Hide", (d, w) -> runCloak())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void runCloak() {
        if (!isAdded()) return;
        Context ctx = requireContext();

        AlertDialog progress = new AlertDialog.Builder(ctx)
            .setTitle("Hiding ShizuPosed")
            .setMessage("Preparing…")
            .setCancelable(false)
            .create();
        progress.show();

        new Thread(() -> {
            ManagerCloakFlow.hideShizuPosed(new ManagerCloakFlow.ProgressListener() {
                @Override public void onMessage(String text) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        if (progress.isShowing()) progress.setMessage(text);
                    });
                }
                @Override public void onSuccess(String newPkg) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        if (progress.isShowing()) progress.dismiss();
                        Toast.makeText(ctx,
                            "Installed as " + newPkg
                                + ". The old package has been uninstalled.",
                            Toast.LENGTH_LONG).show();
                    });
                }
                @Override public void onError(String message) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        if (progress.isShowing()) progress.dismiss();
                        new AlertDialog.Builder(ctx)
                            .setTitle("Hide failed")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                    });
                }
            });
        }, "ShizuPosed-cloak").start();
    }

    private void confirmUncloak() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Unhide ShizuPosed app?")
            .setMessage("This will patch the current APK's manifest "
                + "back to the original package name, re-sign it with "
                + "the same per-install key the cloaked install uses, "
                + "install it as a new package, and uninstall this one.\n\n"
                + "Your settings, modules, and cloak key are preserved.")
            .setPositiveButton("Unhide", (d, w) -> runRevert())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void runRevert() {
        if (!isAdded()) return;
        Context ctx = requireContext();

        AlertDialog progress = new AlertDialog.Builder(ctx)
            .setTitle("Unhiding ShizuPosed")
            .setMessage("Preparing…")
            .setCancelable(false)
            .create();
        progress.show();

        new Thread(() -> {
            ManagerCloakFlow.unhideShizuPosed(new ManagerCloakFlow.ProgressListener() {
                @Override public void onMessage(String text) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        if (progress.isShowing()) progress.setMessage(text);
                    });
                }
                @Override public void onSuccess(String newPkg) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        if (progress.isShowing()) progress.dismiss();
                        Toast.makeText(ctx,
                            "Restored as " + newPkg,
                            Toast.LENGTH_LONG).show();
                    });
                }
                @Override public void onError(String message) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        if (progress.isShowing()) progress.dismiss();
                        new AlertDialog.Builder(ctx)
                            .setTitle("Unhide failed")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                    });
                }
            });
        }, "ShizuPosed-uncloak").start();
    }

    // ═════════════════════════════════════════════════════════════
    // CHECK FOR UPDATES
    // ═════════════════════════════════════════════════════════════

    private void checkForUpdates() {
        if (!isAdded()) return;

        String currentVersion = null;
        try {
            currentVersion = requireContext().getPackageManager()
                .getPackageInfo(requireContext().getPackageName(), 0)
                .versionName;
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("checkForUpdates: cannot read version: "
                    + t.getMessage());
            }
        }
        if (currentVersion == null) {
            Toast.makeText(requireContext(),
                "Cannot read installed version",
                Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(requireContext(),
            "Checking for updates…", Toast.LENGTH_SHORT).show();

        if (logger != null) {
            logger.i("Manual update check requested (current="
                + currentVersion + ")");
        }

        final Context ctx = requireContext();
        final String version = currentVersion;
        UpdateChecker.checkNowAsync(ctx, currentVersion,
            new UpdateChecker.CheckResult() {
                @Override public void onNoUpdate(String v) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> Toast.makeText(
                        ctx,
                        "You're on the latest version (" + v + ")",
                        Toast.LENGTH_LONG).show());
                }
                @Override public void onUpdateFound(String tag, String apkUrl) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> {
                        new AlertDialog.Builder(ctx)
                            .setTitle("Update available")
                            .setMessage("ShizuPosed " + tag
                                + " is available.\n\n"
                                + "Download and install now? Your settings, "
                                + "modules, and cloak key are preserved.")
                            .setPositiveButton("Download",
                                (d, w) -> {
                                    Intent i = new Intent(ctx,
                                        UpdateInstallActivity.class);
                                    i.putExtra(
                                        UpdateInstallActivity.EXTRA_TAG, tag);
                                    startActivity(i);
                                })
                            .setNegativeButton("Later", null)
                            .show();
                    });
                }
                @Override public void onError(String message) {
                    if (!isAdded() || !viewReady) return;
                    requireActivity().runOnUiThread(() -> Toast.makeText(
                        ctx,
                        "Update check failed: " + message,
                        Toast.LENGTH_LONG).show());
                }
            });
    }

    // ═════════════════════════════════════════════════════════════
    // LOAD / SAVE
    // ═════════════════════════════════════════════════════════════

    private void loadSettings() {
        if (prefs == null || !viewReady) return;

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
    // MAINTENANCE ACTIONS
    // ═════════════════════════════════════════════════════════════

    private void registerMaintenanceReceiver() {
        if (maintenanceReceiverRegistered) return;
        if (!isAdded() || getContext() == null) return;

        try {
            maintenanceReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!ShizuPosedService.ACTION_LAUNCH_RESULT
                            .equals(intent.getAction())) return;

                    String pkg = intent.getStringExtra(
                        ShizuPosedService.EXTRA_LAUNCH_PKG);
                    if (!ShizuPosedService.MAINTENANCE_RESULT_PKG
                            .equals(pkg)) return;

                    String msg = intent.getStringExtra(
                        ShizuPosedService.EXTRA_LAUNCH_MSG);
                    boolean ok = intent.getBooleanExtra(
                        ShizuPosedService.EXTRA_LAUNCH_OK, false);

                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                        msg != null ? msg : (ok ? "Done" : "Failed"),
                        Toast.LENGTH_LONG).show();

                    if (logger != null) {
                        logger.i("Maintenance result: ok=" + ok
                            + (msg != null ? " msg=" + msg : ""));
                    }
                }
            };

            IntentFilter filter = new IntentFilter(
                ShizuPosedService.ACTION_LAUNCH_RESULT);
            Context ctx = requireContext().getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(maintenanceReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(maintenanceReceiver, filter);
            }
            maintenanceReceiverRegistered = true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("registerMaintenanceReceiver failed: "
                    + t.getMessage());
            }
            maintenanceReceiver = null;
            maintenanceReceiverRegistered = false;
        }
    }

    private void unregisterMaintenanceReceiver() {
        if (!maintenanceReceiverRegistered) return;
        if (maintenanceReceiver == null) return;
        try {
            requireContext().getApplicationContext()
                .unregisterReceiver(maintenanceReceiver);
        } catch (Throwable ignored) {}
        maintenanceReceiver = null;
        maintenanceReceiverRegistered = false;
    }

    private void confirmClearLogs() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Clear logs?")
            .setMessage("Removes launch.log and other shell-side log files. "
                + "The manager's own log is cleared separately from the Logs tab.")
            .setPositiveButton("Clear", (d, w) ->
                dispatchMaintenance(ShizuPosedService.ACTION_CLEAR_LOGS))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmCleanAll() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Clean all caches?")
            .setMessage("Removes the deployed dex, native libs, module files, "
                + "markers, and logs. The next routed launch will redeploy "
                + "everything from scratch and be slower.")
            .setPositiveButton("Clean", (d, w) ->
                dispatchMaintenance(ShizuPosedService.ACTION_CLEAN_ALL))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void dispatchMaintenance(String action) {
        if (!isAdded()) return;
        if (!ShizuPosedService.isServiceRunning()) {
            Toast.makeText(requireContext(),
                "Service is not running. Start it first.",
                Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(requireContext(), ShizuPosedService.class);
            i.setAction(action);
            requireContext().startForegroundService(i);
            Toast.makeText(requireContext(),
                "Working…", Toast.LENGTH_SHORT).show();
            if (logger != null) {
                logger.i("Dispatched maintenance action: " + action);
            }
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                "Dispatch failed: " + t.getMessage(),
                Toast.LENGTH_LONG).show();
            if (logger != null) {
                logger.e("dispatchMaintenance failed: " + t.getMessage());
            }
        }
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

            config.append("\n## Sysku\n");
            config.append("sysku_enabled=")
                  .append(SyskuController.isEnabled(requireContext()))
                  .append("\n");
            SyskuController.State st =
                SyskuController.getState(requireContext());
            config.append("sysku_state=")
                  .append(SyskuController.describe(st))
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