package com.shizuposed.manager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.shizuposed.manager.adapter.MainPagerAdapter;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.ui.HomeFragment;
import com.shizuposed.manager.ui.LogsFragment;
import com.shizuposed.manager.ui.ModulesFragment;
import com.shizuposed.manager.ui.RepoFragment;
import com.shizuposed.manager.utils.Logger;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String VERSION_LABEL = "v3.3";

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private Toolbar toolbar;
    private MainPagerAdapter pagerAdapter;
    private ShizuPosedManagerApp app;
    private Logger logger;
    private boolean shizukuAuthorized = false;
    private boolean isShizukuAvailable = false;
    private boolean permissionsGranted = false;
    private boolean shizukuChecked = false;

    private boolean notActiveDialogShownThisSession = false;
    private boolean lastServiceRunning = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                permissionsGranted = allGranted;

                if (allGranted) {
                    logger.i("✅ All runtime permissions granted");
                } else {
                    logger.d("Optional runtime permissions not granted: " + result);
                }

                shizukuChecked = false;
                mainHandler.postDelayed(this::checkShizukuAndRequestPermission, 500);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        app = ShizuPosedManagerApp.getInstance();
        app.setMainActivity(this);
        logger = Logger.getInstance(this);

        initViews();
        setupToolbar();
        setupViewPager();
        setupBottomNavigation();

        startServices();
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            logger.i("Requesting " + permissionsNeeded.size() + " permissions...");
            requestPermissionsLauncher.launch(permissionsNeeded.toArray(new String[0]));
        } else {
            permissionsGranted = true;
            logger.i("✅ All permissions already granted");
            mainHandler.postDelayed(this::checkShizukuAndRequestPermission, 300);
        }
    }

    public void onShizukuPermissionGranted() {
        runOnUiThread(() -> {
            shizukuAuthorized = true;
            isShizukuAvailable = true;
            shizukuChecked = true;
            notActiveDialogShownThisSession = false;
            updateToolbarStatus(VERSION_LABEL + " • 🔑 Privileged");
            refreshAll();
            invalidateOptionsMenu();
        });
    }

    public void onServiceAutoStarted() {
        runOnUiThread(() -> {
            refreshAll();
            updateServiceMenuState();
        });
    }

    private void checkShizukuAndRequestPermission() {
        if (shizukuChecked && shizukuAuthorized) {
            logger.d("Shizuku already checked and authorized");
            updateToolbarStatus(VERSION_LABEL + " • 🔑 Privileged");
            return;
        }

        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            isShizukuAvailable = helper.isAvailable();
            shizukuAuthorized = helper.isAuthorized();
            shizukuChecked = true;

            logger.i("Shizuku Status - Available: " + isShizukuAvailable
                + ", Authorized: " + shizukuAuthorized);

            if (!isShizukuAvailable) {
                ShizukuHelper.ShizukuStatus status = helper.checkShizukuActive();
                if (status == ShizukuHelper.ShizukuStatus.NOT_INSTALLED) {
                    showShizukuNotInstalledDialog();
                    updateToolbarStatus(VERSION_LABEL + " • ❌ Shizuku Not Installed");
                } else if (status == ShizukuHelper.ShizukuStatus.NOT_ACTIVE) {
                    if (!notActiveDialogShownThisSession) {
                        notActiveDialogShownThisSession = true;
                        showShizukuNotActiveDialog();
                    }
                    updateToolbarStatus(VERSION_LABEL + " • ⏹ Shizuku Stopped");
                } else {
                    updateToolbarStatus(VERSION_LABEL + " • ❌ Shizuku Error");
                }
                return;
            }

            notActiveDialogShownThisSession = false;

            if (!shizukuAuthorized) {
                updateToolbarStatus(VERSION_LABEL + " • ⚠️ Not Authorized");
                helper.requestPermission();
            } else {
                updateToolbarStatus(VERSION_LABEL + " • 🔑 Privileged");
            }
        } catch (Exception e) {
            logger.e("Shizuku check error: " + e.getMessage());
            updateToolbarStatus(VERSION_LABEL + " • ⏹ Shizuku Stopped");
        }
    }

    private void showShizukuNotInstalledDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Shizuku Not Found")
            .setMessage("Shizuku is required for privileged operations.\n\n" +
                       "Please install Shizuku from:\n" +
                       "• Google Play Store\n" +
                       "• F-Droid\n" +
                       "• GitHub: https://github.com/thedjchi/Shizuku")
            .setPositiveButton("Open Play Store", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=moe.shizuku.manager")));
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/thedjchi/Shizuku/releases")));
                }
            })
            .setNegativeButton("Skip", (d, w) -> updateToolbarStatus(VERSION_LABEL + " • ⚠️ Limited"))
            .setCancelable(false)
            .show();
    }

    private void showShizukuNotActiveDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Shizuku Not Running")
            .setMessage("Shizuku is installed but not running.\n\n" +
                       "Please start Shizuku:\n" +
                       "1. Open Shizuku app\n" +
                       "2. Tap 'Start' (requires ADB or root)\n" +
                       "3. Return to this app")
            .setPositiveButton("Open Shizuku", (d, w) -> {
                try {
                    Intent intent = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.manager");
                    if (intent != null) startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Please open Shizuku manually", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Skip", (d, w) -> updateToolbarStatus(VERSION_LABEL + " • ⚠️ Limited"))
            .setCancelable(false)
            .show();
    }

    private void updateToolbarStatus(String status) {
        runOnUiThread(() -> {
            if (toolbar != null) toolbar.setSubtitle(status);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!shizukuAuthorized) {
            shizukuChecked = false;
            mainHandler.postDelayed(this::checkShizukuAndRequestPermission, 200);
        } else {
            updateToolbarStatus(VERSION_LABEL + " • 🔑 Privileged");
        }
        updateServiceMenuState();
    }

    private void initViews() {
        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        toolbar = findViewById(R.id.toolbar);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("ShizuPosed Manager");
        }
    }

    private void setupViewPager() {
        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(4);
        viewPager.setUserInputEnabled(false);
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home)     { viewPager.setCurrentItem(0); return true; }
            if (itemId == R.id.nav_modules)  { viewPager.setCurrentItem(1); return true; }
            if (itemId == R.id.nav_repo)     { viewPager.setCurrentItem(2); return true; }
            if (itemId == R.id.nav_logs)     { viewPager.setCurrentItem(3); return true; }
            if (itemId == R.id.nav_settings) { viewPager.setCurrentItem(4); return true; }
            return false;
        });
    }

    /**
     * Start the foreground service.
     *
     * Skips the start if the Application already auto-started it — this
     * prevents two back-to-back startForegroundService() calls during
     * cold start, which is a known trigger for
     * ForegroundServiceDidNotStartInTimeException on ColorOS.
     */
    private void startServices() {
        try {
            if (app != null && app.isServiceAutoStarted()) {
                logger.d("Service already auto-started by Application — skipping startServices()");
                return;
            }
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Throwable t) {
            logger.e("Failed to start service: " + t.getMessage());
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_options_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean running = isShizuPosedServiceRunning();

        MenuItem startItem = menu.findItem(R.id.action_start_service);
        MenuItem stopItem  = menu.findItem(R.id.action_stop_service);

        if (startItem != null) startItem.setVisible(!running);
        if (stopItem != null)  stopItem.setVisible(running);

        lastServiceRunning = running;
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_grant_shizuku) {
            shizukuChecked = false;
            ShizukuHelper.getInstance(this).requestPermission();
            Toast.makeText(this, "Check Shizuku app", Toast.LENGTH_SHORT).show();
            return true;

        } else if (id == R.id.action_start_service) {
            handleStartService();
            return true;

        } else if (id == R.id.action_stop_service) {
            handleStopService();
            return true;

        } else if (id == R.id.action_status) {
            showStatusDialog();
            return true;

        } else if (id == R.id.action_refresh) {
            refreshAll();
            return true;

        } else if (id == R.id.action_restart) {
            restartServices();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void handleStartService() {
        try {
            if (app != null && app.isServiceAutoStarted()) {
                Toast.makeText(this, "Service already running", Toast.LENGTH_SHORT).show();
                updateServiceMenuState();
                return;
            }
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Service starting…", Toast.LENGTH_SHORT).show();
            logger.i("Service start requested from menu");

            mainHandler.postDelayed(() -> {
                updateServiceMenuState();
                refreshAll();
            }, 500);

        } catch (Throwable t) {
            Toast.makeText(this, "Failed to start service", Toast.LENGTH_SHORT).show();
            logger.e("handleStartService error: " + t.getMessage());
        }
    }

    private void handleStopService() {
        new AlertDialog.Builder(this)
            .setTitle("Stop Service")
            .setMessage("Stop the ShizuPosed service?\n\n" +
                       "Running hooks will continue in already-launched targets, " +
                       "but new targets will not be hooked until the service is started again.")
            .setPositiveButton("Stop", (d, w) -> {
                try {
                    Intent serviceIntent = new Intent(this, ShizuPosedService.class);
                    stopService(serviceIntent);
                    Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show();
                    logger.i("Service stop requested from menu");

                    lastServiceRunning = false;
                    invalidateOptionsMenu();
                    mainHandler.postDelayed(() -> {
                        updateServiceMenuState();
                        refreshAll();
                    }, 500);

                } catch (Throwable t) {
                    Toast.makeText(this, "Failed to stop service", Toast.LENGTH_SHORT).show();
                    logger.e("handleStopService error: " + t.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private boolean isShizuPosedServiceRunning() {
        try {
            if (ShizuPosedService.isServiceRunning()) return true;
        } catch (Throwable ignored) {}
        try {
            if (app != null && app.isServiceAutoStarted()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void updateServiceMenuState() {
        boolean running = isShizuPosedServiceRunning();
        if (running != lastServiceRunning) {
            lastServiceRunning = running;
            invalidateOptionsMenu();
        }
    }

    private void refreshAll() {
        for (int i = 0; i < pagerAdapter.getItemCount(); i++) {
            Fragment fragment = pagerAdapter.getFragment(i);
            if (fragment instanceof HomeFragment) {
                ((HomeFragment) fragment).refresh();
            } else if (fragment instanceof ModulesFragment) {
                ((ModulesFragment) fragment).refresh();
            } else if (fragment instanceof RepoFragment) {
                ((RepoFragment) fragment).refresh();
            } else if (fragment instanceof LogsFragment) {
                ((LogsFragment) fragment).refresh();
            }
        }
        shizukuChecked = false;
        checkShizukuAndRequestPermission();
    }

    private void restartServices() {
        try {
            Intent serviceIntent = new Intent(this, ShizuPosedService.class);
            stopService(serviceIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show();

            mainHandler.postDelayed(() -> {
                updateServiceMenuState();
                refreshAll();
            }, 500);
        } catch (Throwable t) {
            Toast.makeText(this, "Failed to restart service", Toast.LENGTH_SHORT).show();
            logger.e("Restart service error: " + t.getMessage());
        }
    }

    private void showStatusDialog() {
        ShizukuHelper helper = ShizukuHelper.getInstance(this);
        boolean available = helper.isAvailable();
        boolean authorized = helper.isAuthorized();
        int version = helper.getVersion();
        boolean isSui = helper.isSui();

        boolean serviceRunning = isShizuPosedServiceRunning();

        StringBuilder status = new StringBuilder();
        status.append("ShizuPosed").append(VERSION_LABEL).append("\n\n");
        status.append("Permissions: ")
              .append(permissionsGranted ? "✅ Granted" : "⚠️ Missing").append("\n");
        status.append("Shizuku Status: ")
              .append(available ? "✅ Available" : "❌ Unavailable").append("\n");
        status.append("Authorization: ")
              .append(authorized ? "✅ Authorized" : "❌ Not Authorized").append("\n");
        status.append("Shizuku Version: ").append(version).append("\n");
        if (isSui) {
            status.append("Sui Active: ✅ Yes\n");
        }
        status.append("\n");
        status.append("Service: ")
              .append(serviceRunning ? "✅ Running" : "❌ Stopped").append("\n");
        status.append("\n");
        status.append("Privileged Mode: ")
              .append(authorized ? "🔑 Enabled" : "⚠️ Limited");

        new AlertDialog.Builder(this)
            .setTitle("Status")
            .setMessage(status.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}