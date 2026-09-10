package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import com.shizuposed.manager.core.ProcessMonitor;
import com.shizuposed.manager.model.HookedProcess;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {
    private RecyclerView processRecyclerView;
    private ProgressBar progressIndicator;
    private TextView tvStatus, tvHookedCount, tvTotalApps;
    private CardView cardStatus;
    private Button btnRefresh, btnStartService;

    private Logger logger;
    private HookedProcessAdapter processAdapter;
    private List<HookedProcess> hookedProcesses = new ArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProcessMonitor processMonitor;

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
        cardStatus = view.findViewById(R.id.cardStatus);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnStartService = view.findViewById(R.id.btnStartService);

        logger = Logger.getInstance(requireContext());
        processMonitor = ProcessMonitor.getInstance(requireContext());
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
            Toast.makeText(requireContext(), "Refreshed", Toast.LENGTH_SHORT).show();
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

        // Real total installed apps
        int totalApps = getTotalInstalledApps();
        tvTotalApps.setText(String.valueOf(totalApps));

        // Real hooked processes — read from the shared ProcessMonitor singleton.
        // The service updates this same instance (same process).
        List<HookedProcess> processes = (processMonitor != null)
            ? processMonitor.getHookedProcesses()
            : new ArrayList<>();

        hookedProcesses.clear();
        if (processes != null) hookedProcesses.addAll(processes);

        tvHookedCount.setText(String.valueOf(hookedProcesses.size()));
        if (processAdapter != null) {
            processAdapter.updateData(hookedProcesses);
        }

        logger.d("Updated real data - Hooked: " + hookedProcesses.size()
            + ", Apps: " + totalApps + ", Service: " + serviceRunning);
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

    private void startService() {
        try {
            Intent serviceIntent = new Intent(requireContext(), ShizuPosedService.class);
            requireContext().startForegroundService(serviceIntent);
            Toast.makeText(requireContext(), "Service starting...", Toast.LENGTH_SHORT).show();
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