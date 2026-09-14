package com.shizuposed.manager.ui;

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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.adapter.HookedProcessAdapter;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.HookedProcess;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.LSPosedManager;
import de.robv.android.xposed.XposedBridge;

public class HomeFragment extends Fragment {
    private RecyclerView processRecyclerView;
    private ProgressBar progressIndicator;
    private TextView tvStatus, tvHookedCount, tvTotalApps, tvActiveModules;
    private CardView cardStatus;
    private Button btnRefresh, btnStartService;

    // Framework info card fields
    private TextView tvFrameworkVersion, tvApiVersion;
    private TextView tvShellPackage, tvShellUid;
    private TextView tvSystemVersion, tvDevice, tvSystemAbi;

    private Logger logger;
    private HookedProcessAdapter processAdapter;
    private List<HookedProcess> hookedProcesses = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProcessMonitor processMonitor;
    private ModuleLoader moduleLoader;

    // Shell uid is a platform constant: shell runs as uid 2000 on every
    // Android build. Declared here so the value used in the UI and the
    // value used anywhere else in the framework come from one place.
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final int SHELL_UID = 2000;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateRealData();
            mainHandler.postDelayed(this, 3000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        initViews(view);
        populateFrameworkInfo();   // static info, populated once
        setupListeners();
        setupRecyclerView();

        return view;
    }

    private void initViews(View view) {
        processRecyclerView = view.findViewById(R.id.processRecyclerView);
        progressIndicator = view.findViewById(R.id.progressIndicator);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvHookedCount = view.findViewById(R.id.tvHookedCount);
        tvTotalApps = view.findViewById(R.id.tvTotalApps);
        tvActiveModules = view.findViewById(R.id.tvActiveModules);
        cardStatus = view.findViewById(R.id.cardStatus);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnStartService = view.findViewById(R.id.btnStartService);

        // Framework info card
        tvFrameworkVersion = view.findViewById(R.id.tvFrameworkVersion);
        tvApiVersion       = view.findViewById(R.id.tvApiVersion);
        tvShellPackage     = view.findViewById(R.id.tvShellPackage);
        tvShellUid         = view.findViewById(R.id.tvShellUid);
        tvSystemVersion    = view.findViewById(R.id.tvSystemVersion);
        tvDevice           = view.findViewById(R.id.tvDevice);
        tvSystemAbi        = view.findViewById(R.id.tvSystemAbi);

        logger = Logger.getInstance(requireContext());
        processMonitor = ProcessMonitor.getInstance(requireContext());
        moduleLoader = ModuleLoader.getInstance(requireContext());
    }

    // ═════════════════════════════════════════════════════════════
    // FRAMEWORK INFO CARD
    //
    // Populated once. None of these values change at runtime.
    // ═════════════════════════════════════════════════════════════

    private void populateFrameworkInfo() {
        if (!isAdded() || getContext() == null) return;

        // 1. Framework version
        String frameworkVersion;
        try {
            frameworkVersion = LSPosedManager.getFrameworkName()
                + " " + LSPosedManager.getVersionName();
        } catch (Throwable t) {
            frameworkVersion = "ShizuPosed";
        }
        tvFrameworkVersion.setText(frameworkVersion);

        // 2. API version
        int apiVersion = XposedBridge.getXposedVersion();
        tvApiVersion.setText(String.valueOf(apiVersion));

        // 3. Shell package + uid. This is the identity the hook payload
        //    runs as, and the one that actually determines permissions.
        //    Both values are platform constants.
        tvShellPackage.setText(SHELL_PACKAGE);
        tvShellUid.setText(String.valueOf(SHELL_UID));

        // 4. System version
        String systemVersion = "Android " + Build.VERSION.RELEASE
            + " (API " + Build.VERSION.SDK_INT + ")";
        tvSystemVersion.setText(systemVersion);

        // 5. Device: manufacturer + model with capitalized manufacturer
        String manufacturer = Build.MANUFACTURER != null
            ? capitalize(Build.MANUFACTURER) : "";
        String model = Build.MODEL != null ? Build.MODEL : "";
        String device = (manufacturer + " " + model).trim();
        if (device.isEmpty()) device = "Unknown";
        tvDevice.setText(device);

        // 6. System ABI
        String abi = "unknown";
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) abi = abis[0];
        } catch (Throwable ignored) {}
        tvSystemAbi.setText(abi);

        logger.d("Framework info: " + frameworkVersion
            + ", API " + apiVersion
            + ", shell " + SHELL_PACKAGE + " (uid " + SHELL_UID + ")"
            + ", " + systemVersion
            + ", " + device
            + ", ABI " + abi);
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

    private void setupRecyclerView() {
        processRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        processAdapter = new HookedProcessAdapter(hookedProcesses, requireContext());
        processRecyclerView.setAdapter(processAdapter);
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> {
            showLoading(true);
            updateRealData();
            showLoading(false);
        });

        btnStartService.setOnClickListener(v -> startService());
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.post(statusUpdater);
        updateRealData();
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(statusUpdater);
    }

    public void refresh() {
        updateRealData();
    }

    private void updateRealData() {
        if (!isAdded() || getContext() == null) return;

        ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
        boolean shizukuAuthorized = app != null && app.isShizukuAuthorized();
        boolean serviceRunning =
            ShizuPosedService.isServiceRunning()
            || (app != null && app.isServiceAutoStarted());

        updateStatus(shizukuAuthorized, serviceRunning);

        int totalApps = getTotalInstalledApps();
        tvTotalApps.setText(String.valueOf(totalApps));

        int scopedApps = getScopedAppCount();
        tvHookedCount.setText(String.valueOf(scopedApps));

        int activeModules = getEnabledModuleCount();
        if (tvActiveModules != null) {
            tvActiveModules.setText(String.valueOf(activeModules));
        }

        List<HookedProcess> processes = (processMonitor != null)
            ? processMonitor.getHookedProcesses()
            : new ArrayList<>();

        hookedProcesses.clear();
        for (HookedProcess p : processes) {
            if (p == null) continue;
            boolean hooked = p.isHooked();
            boolean inScope = processMonitor != null
                && processMonitor.isPackageInScope(p.getProcessName());
            if (hooked || inScope) {
                hookedProcesses.add(p);
            }
        }

        if (processAdapter != null) {
            processAdapter.updateData(hookedProcesses);
        }

        logger.d("Updated real data - Scoped: " + scopedApps
            + ", Active modules: " + activeModules
            + ", Apps: " + totalApps
            + ", Shown processes: " + hookedProcesses.size()
            + ", Service: " + serviceRunning);
    }

    private void updateStatus(boolean shizukuAuthorized, boolean serviceRunning) {
        if (!shizukuAuthorized) {
            tvStatus.setText("No Shizuku Permission");
            tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
            cardStatus.setCardBackgroundColor(
                requireContext().getColor(android.R.color.holo_red_light));
            btnStartService.setEnabled(false);
        } else if (serviceRunning) {
            tvStatus.setText("Running ✅");
            tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_light));
            cardStatus.setCardBackgroundColor(
                requireContext().getColor(android.R.color.holo_green_light));
            btnStartService.setEnabled(false);
        } else {
            tvStatus.setText("Stopped ⚠️");
            tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_orange_light));
            cardStatus.setCardBackgroundColor(
                requireContext().getColor(android.R.color.holo_orange_light));
            btnStartService.setEnabled(true);
        }
    }

    private int getTotalInstalledApps() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            return apps != null ? apps.size() : 0;
        } catch (Exception e) {
            logger.e("Error getting total apps: " + e.getMessage());
            return 0;
        }
    }

    private int getScopedAppCount() {
        try {
            Set<String> union = new HashSet<>();
            for (ModuleInfo m : moduleLoader.getEnabledModules()) {
                if (m.hookedApps != null) union.addAll(m.hookedApps);
            }
            return union.size();
        } catch (Throwable t) {
            logger.e("getScopedAppCount error: " + t.getMessage());
            return 0;
        }
    }

    private int getEnabledModuleCount() {
        try {
            return moduleLoader.getEnabledModules().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void startService() {
        try {
            Intent serviceIntent = new Intent(requireContext(), ShizuPosedService.class);
            requireContext().startForegroundService(serviceIntent);
            Toast.makeText(requireContext(), "Service starting…", Toast.LENGTH_SHORT).show();
            logger.i("Service started manually");

            mainHandler.postDelayed(this::updateRealData, 1000);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to start service", Toast.LENGTH_SHORT).show();
            logger.e("Start service error: " + e.getMessage());
        }
    }

    private void showLoading(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacks(statusUpdater);
    }
}