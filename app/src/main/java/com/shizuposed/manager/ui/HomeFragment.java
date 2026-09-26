package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.LSPosedManager;
import de.robv.android.xposed.XposedBridge;

/**
 * HomeFragment
 *
 * The Home tab. Shows:
 *   • A status card ("Activated" / "Not Activated") at the top,
 *     with a check or cross icon matching the state.
 *   • A row of counters: scoped apps, active modules, installed apps.
 *   • A Framework Info card with the same fields LSPosed's Home
 *     tab reports.
 *   • A refresh button.
 *
 * STATUS SEMANTICS
 * ----------------
 * "Activated" means ShizuPosed is fully operational: Shizuku is
 * authorized and the foreground service is running. Anything else
 * is "Not Activated" with a short subtitle explaining what's
 * missing.
 *
 * Two icons live in the status card:
 *   • ivStatusIcon     — the green check, visible when Activated
 *   • ivStatusIconCross — the red cross, visible when Not Activated
 *
 * updateStatus() swaps them. Only one is ever visible.
 */
public class HomeFragment extends Fragment {

    private TextView tvStatus, tvStatusDetail;
    private TextView tvHookedCount, tvTotalApps, tvActiveModules;
    private CardView cardStatus;
    private ImageView ivStatusIcon, ivStatusIconCross;
    private Button btnRefresh, btnStartService;

    // Framework info card fields
    private TextView tvFrameworkVersion, tvApiVersion;
    private TextView tvShellPackage, tvShellUid;
    private TextView tvSystemVersion, tvDevice, tvSystemAbi;
    private TextView tvFrameworkApiProtection, tvFrameworkDexOptimize;

    private Logger logger;
    private ProcessMonitor processMonitor;
    private ModuleLoader moduleLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean viewReady = false;

    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final int SHELL_UID = 2000;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateRealData();
            mainHandler.postDelayed(this, 3000);
        }
    };

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
        processMonitor = ProcessMonitor.getInstance(context);
        moduleLoader = ModuleLoader.getInstance(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;
        initViews(view);
        populateFrameworkInfo();
        setupListeners();
        updateRealData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        mainHandler.removeCallbacks(statusUpdater);

        tvStatus = null;
        tvStatusDetail = null;
        tvHookedCount = null;
        tvTotalApps = null;
        tvActiveModules = null;
        cardStatus = null;
        ivStatusIcon = null;
        ivStatusIconCross = null;
        btnRefresh = null;
        btnStartService = null;
        tvFrameworkVersion = null;
        tvApiVersion = null;
        tvShellPackage = null;
        tvShellUid = null;
        tvSystemVersion = null;
        tvDevice = null;
        tvSystemAbi = null;
        tvFrameworkApiProtection = null;
        tvFrameworkDexOptimize = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (logger == null) return;
        mainHandler.post(statusUpdater);
        updateRealData();
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(statusUpdater);
    }

    // ═════════════════════════════════════════════════════════════
    // VIEW + EVENT WIRING
    // ═════════════════════════════════════════════════════════════

    public void refresh() {
        if (!viewReady) {
            if (logger != null) logger.d("refresh() skipped: view not ready");
            return;
        }
        updateRealData();
    }

    private void initViews(View view) {
        tvStatus = view.findViewById(R.id.tvStatus);
        tvStatusDetail = view.findViewById(R.id.tvStatusDetail);
        tvHookedCount = view.findViewById(R.id.tvHookedCount);
        tvTotalApps = view.findViewById(R.id.tvTotalApps);
        tvActiveModules = view.findViewById(R.id.tvActiveModules);
        cardStatus = view.findViewById(R.id.cardStatus);
        ivStatusIcon = view.findViewById(R.id.ivStatusIcon);
        ivStatusIconCross = view.findViewById(R.id.ivStatusIconCross);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnStartService = view.findViewById(R.id.btnStartService);

        tvFrameworkVersion = view.findViewById(R.id.tvFrameworkVersion);
        tvApiVersion = view.findViewById(R.id.tvApiVersion);
        tvShellPackage = view.findViewById(R.id.tvShellPackage);
        tvShellUid = view.findViewById(R.id.tvShellUid);
        tvSystemVersion = view.findViewById(R.id.tvSystemVersion);
        tvDevice = view.findViewById(R.id.tvDevice);
        tvSystemAbi = view.findViewById(R.id.tvSystemAbi);
        tvFrameworkApiProtection = view.findViewById(R.id.tvFrameworkApiProtection);
        tvFrameworkDexOptimize = view.findViewById(R.id.tvFrameworkDexOptimize);
    }

    private void setupListeners() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> updateRealData());
        }
        if (btnStartService != null) {
            btnStartService.setOnClickListener(v -> startService());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FRAMEWORK INFO
    // ═════════════════════════════════════════════════════════════

    private void populateFrameworkInfo() {
        if (!viewReady) return;
        if (tvFrameworkVersion == null) return;

        String frameworkVersion;
        try {
            frameworkVersion = LSPosedManager.getFrameworkName()
                + " " + LSPosedManager.getVersionName();
        } catch (Throwable t) {
            frameworkVersion = "ShizuPosed";
        }
        tvFrameworkVersion.setText(frameworkVersion);
        tvApiVersion.setText(String.valueOf(XposedBridge.getXposedVersion()));
        tvShellPackage.setText(SHELL_PACKAGE);
        tvShellUid.setText(String.valueOf(SHELL_UID));

        String systemVersion = "Android " + Build.VERSION.RELEASE
            + " (API " + Build.VERSION.SDK_INT + ")";
        tvSystemVersion.setText(systemVersion);

        String manufacturer = Build.MANUFACTURER != null
            ? capitalize(Build.MANUFACTURER) : "";
        String model = Build.MODEL != null ? Build.MODEL : "";
        String device = (manufacturer + " " + model).trim();
        if (device.isEmpty()) device = "Unknown";
        tvDevice.setText(device);

        String abi = "unknown";
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) abi = abis[0];
        } catch (Throwable ignored) {}
        tvSystemAbi.setText(abi);

        populateXStealthStatus();

        if (logger != null) {
            logger.d("Framework info: " + frameworkVersion
                + ", API " + XposedBridge.getXposedVersion()
                + ", shell " + SHELL_PACKAGE + " (uid " + SHELL_UID + ")"
                + ", " + systemVersion
                + ", " + device
                + ", ABI " + abi);
        }
    }

    private void populateXStealthStatus() {
        if (!viewReady) return;
        Context ctx = requireContext();

        if (tvFrameworkApiProtection != null) {
            boolean master = com.shizuposed.manager.stealth.XStealthPrefs
                .isEnabled(ctx);
            boolean on = master && com.shizuposed.manager.stealth.XStealthPrefs
                .isApiProtectionEnabled(ctx);

            String label;
            int colorRes;
            if (!master) {
                label = "Inactive";
                colorRes = android.R.color.darker_gray;
            } else if (on) {
                label = "Active";
                colorRes = android.R.color.holo_green_light;
            } else {
                label = "Disabled";
                colorRes = android.R.color.darker_gray;
            }
            tvFrameworkApiProtection.setText(label);
            tvFrameworkApiProtection.setTextColor(ctx.getColor(colorRes));
        }

        if (tvFrameworkDexOptimize != null) {
            boolean on = com.shizuposed.manager.stealth.XStealthPrefs
                .isDexOptimizeEnabled(ctx);
            tvFrameworkDexOptimize.setText(on ? "Enabled" : "Disabled");
            tvFrameworkDexOptimize.setTextColor(ctx.getColor(
                on ? android.R.color.holo_green_light
                   : android.R.color.darker_gray));
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        boolean upperNext = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '_' || c == '-') {
                out.append(c);
                upperNext = true;
            } else if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    // ═════════════════════════════════════════════════════════════
    // STATUS + COUNTERS
    // ═════════════════════════════════════════════════════════════

    private void updateRealData() {
        if (!viewReady) return;
        if (logger == null || moduleLoader == null) return;

        ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
        boolean shizukuAuthorized = app != null && app.isShizukuAuthorized();
        boolean serviceRunning =
            ShizuPosedService.isServiceRunning()
            || (app != null && app.isServiceAutoStarted());

        updateStatus(shizukuAuthorized, serviceRunning);

        if (tvTotalApps != null) {
            tvTotalApps.setText(String.valueOf(getTotalInstalledApps()));
        }
        if (tvHookedCount != null) {
            tvHookedCount.setText(String.valueOf(getScopedAppCount()));
        }
        if (tvActiveModules != null) {
            tvActiveModules.setText(String.valueOf(getEnabledModuleCount()));
        }

        populateXStealthStatus();

        if (logger != null) {
            logger.d("Updated real data - Scoped: " + getScopedAppCount()
                + ", Active modules: " + getEnabledModuleCount()
                + ", Apps: " + getTotalInstalledApps()
                + ", Service: " + serviceRunning
                + ", Shizuku: " + shizukuAuthorized);
        }
    }

    /**
     * Binary status with a matching icon.
     *
     *   Activated      → green check
     *   Not Activated  → red cross
     *
     * The subtitle tells the user exactly what's missing. The
     * "Activate" button is shown only when the user can act on it —
     * i.e. Shizuku is authorized but the service isn't running.
     */
    private void updateStatus(boolean shizukuAuthorized, boolean serviceRunning) {
        if (tvStatus == null || cardStatus == null) return;
        if (!isAdded()) return;
        Context ctx = requireContext();

        boolean activated = shizukuAuthorized && serviceRunning;

        // ── Icon swap. Only one is ever visible.
        if (ivStatusIcon != null) {
            ivStatusIcon.setVisibility(activated ? View.VISIBLE : View.GONE);
        }
        if (ivStatusIconCross != null) {
            ivStatusIconCross.setVisibility(activated ? View.GONE : View.VISIBLE);
        }

        if (!shizukuAuthorized) {
            tvStatus.setText("Not Activated");
            tvStatus.setTextColor(ctx.getColor(android.R.color.holo_red_light));
            if (tvStatusDetail != null) {
                tvStatusDetail.setText("Grant Shizuku permission to continue");
            }
            if (btnStartService != null) {
                btnStartService.setVisibility(View.VISIBLE);
                btnStartService.setEnabled(false);
            }
        } else if (serviceRunning) {
            tvStatus.setText("Activated");
            tvStatus.setTextColor(ctx.getColor(android.R.color.holo_green_light));
            if (tvStatusDetail != null) {
                tvStatusDetail.setText("Framework is running");
            }
            if (btnStartService != null) {
                btnStartService.setVisibility(View.GONE);
            }
        } else {
            tvStatus.setText("Not Activated");
            tvStatus.setTextColor(ctx.getColor(android.R.color.holo_orange_light));
            if (tvStatusDetail != null) {
                tvStatusDetail.setText("Tap Activate to start the service");
            }
            if (btnStartService != null) {
                btnStartService.setVisibility(View.VISIBLE);
                btnStartService.setEnabled(true);
            }
        }
    }

    private int getTotalInstalledApps() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            return apps != null ? apps.size() : 0;
        } catch (Exception e) {
            if (logger != null) {
                logger.e("Error getting total apps: " + e.getMessage());
            }
            return 0;
        }
    }

    private int getScopedAppCount() {
        if (moduleLoader == null) return 0;
        try {
            Set<String> union = new HashSet<>();
            for (ModuleInfo m : moduleLoader.getEnabledModules()) {
                if (m.hookedApps != null) union.addAll(m.hookedApps);
            }
            return union.size();
        } catch (Throwable t) {
            if (logger != null) {
                logger.e("getScopedAppCount error: " + t.getMessage());
            }
            return 0;
        }
    }

    private int getEnabledModuleCount() {
        if (moduleLoader == null) return 0;
        try {
            return moduleLoader.getEnabledModules().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SERVICE CONTROL
    // ═════════════════════════════════════════════════════════════

    private void startService() {
        if (!isAdded() || getContext() == null) return;
        try {
            Intent serviceIntent = new Intent(
                requireContext(), ShizuPosedService.class);
            requireContext().startForegroundService(serviceIntent);
            Toast.makeText(requireContext(), "Service starting…",
                Toast.LENGTH_SHORT).show();
            if (logger != null) logger.i("Service started manually");
            mainHandler.postDelayed(this::updateRealData, 1000);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to start service",
                Toast.LENGTH_SHORT).show();
            if (logger != null) logger.e("Start service error: " + e.getMessage());
        }
    }
}