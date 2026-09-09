package com.shizuposed.manager.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
    private List<String> hookedProcesses = new ArrayList<>();
    private boolean isServiceRunning = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Modern approach: ServiceConnection to check if service is bound
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            isServiceRunning = true;
            updateStatus(ShizuPosedManagerApp.getInstance().isShizukuAuthorized());
            logger.i("Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceRunning = false;
            updateStatus(ShizuPosedManagerApp.getInstance().isShizukuAuthorized());
            logger.w("Service disconnected");
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        
        initViews(view);
        setupListeners();
        bindToService();
        loadData();
        
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
        processRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
    }

    private void bindToService() {
        try {
            Intent intent = new Intent(requireContext(), ShizuPosedService.class);
            requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            logger.e("Failed to bind to service: " + e.getMessage());
        }
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> refresh());
        btnStartService.setOnClickListener(v -> startService());
    }

    private void loadData() {
        showLoading(true);
        try {
            ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
            boolean shizukuAuthorized = app.isShizukuAuthorized();
            
            updateStatus(shizukuAuthorized);
            
            int totalApps = getTotalInstalledApps();
            tvTotalApps.setText(String.valueOf(totalApps));
            
            int hookedCount = getHookedProcessCount();
            tvHookedCount.setText(String.valueOf(hookedCount));
            
        } catch (Exception e) {
            logger.e("Error loading data: " + e.getMessage());
            tvStatus.setText("Error");
            cardStatus.setCardBackgroundColor(
                requireContext().getColor(android.R.color.holo_red_light));
        }
        showLoading(false);
    }

    private void updateStatus(boolean shizukuAuthorized) {
        if (!shizukuAuthorized) {
            tvStatus.setText("No Shizuku Permission");
            tvStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_light));
            cardStatus.setCardBackgroundColor(
                requireContext().getColor(android.R.color.holo_red_light));
            btnStartService.setEnabled(false);
        } else if (isServiceRunning) {
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
            return apps.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private int getHookedProcessCount() {
        return 0;
    }

    private void startService() {
        try {
            Intent serviceIntent = new Intent(requireContext(), ShizuPosedService.class);
            requireContext().startService(serviceIntent);
            Toast.makeText(requireContext(), "Service starting...", Toast.LENGTH_SHORT).show();
            logger.i("Service started manually");
            
            mainHandler.postDelayed(this::loadData, 1000);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to start service", Toast.LENGTH_SHORT).show();
            logger.e("Start service error: " + e.getMessage());
        }
    }

    public void refresh() {
        loadData();
        Toast.makeText(requireContext(), "Refreshed", Toast.LENGTH_SHORT).show();
        logger.i("Home fragment refreshed");
    }

    private void showLoading(boolean show) {
        progressIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try {
            requireContext().unbindService(serviceConnection);
        } catch (Exception e) {
            // Ignore
        }
    }
}
