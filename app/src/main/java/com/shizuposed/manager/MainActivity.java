package com.shizuposed.manager;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.shizuposed.manager.R;
import com.shizuposed.manager.adapter.MainPagerAdapter;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.ui.HomeFragment;
import com.shizuposed.manager.ui.LogsFragment;
import com.shizuposed.manager.ui.ModulesFragment;
import com.shizuposed.manager.ui.SettingsFragment;
import com.shizuposed.manager.utils.Logger;

import rikka.shizuku.Shizuku;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int SHIZUKU_CODE = 0xCA07A;
    
    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;
    private Toolbar toolbar;
    private MainPagerAdapter pagerAdapter;
    private ShizuPosedManagerApp app;
    private boolean isServiceConnected = false;
    private Logger logger;
    private boolean shizukuAuthorized = false;
    private boolean isShizukuAvailable = false;
    private boolean permissionsGranted = false;
    private boolean shizukuChecked = false;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // ============================================================
    // RUNTIME PERMISSION LAUNCHER
    // ============================================================
    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                
                permissionsGranted = allGranted;
                
                if (allGranted) {
                    logger.i("✅ All runtime permissions granted");
                    // ✅ Reset check flag and re-check Shizuku
                    shizukuChecked = false;
                    mainHandler.postDelayed(this::checkShizukuAndRequestPermission, 500);
                } else {
                    logger.w("⚠️ Some permissions were denied");
                    // Still try Shizuku but with limited functionality
                    mainHandler.postDelayed(this::checkShizukuAndRequestPermission, 500);
                }
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
        setupTabLayout();
        
        startServices();
        
        // ✅ Check if permissions are already granted
        checkAndRequestPermissions();
    }

    /**
     * ✅ Check and request runtime permissions
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        // Android 13+ - Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        
        // Android 11-12 - Storage
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        
        // Android 10+ - Media
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO);
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

    /**
     * Called when Shizuku permission is granted
     */
    public void onShizukuPermissionGranted() {
        runOnUiThread(() -> {
            shizukuAuthorized = true;
            isShizukuAvailable = true;
            shizukuChecked = true;
            updateToolbarStatus("v1.8 • 🔑 Privileged");
            Snackbar.make(findViewById(android.R.id.content), 
                "✅ Shizuku permission granted!", Snackbar.LENGTH_LONG).show();
            refreshAll();
        });
    }

    /**
     * Called when service is auto-started
     */
    public void onServiceAutoStarted() {
        runOnUiThread(() -> {
            Snackbar.make(findViewById(android.R.id.content), 
                "✅ Service started!", Snackbar.LENGTH_SHORT).show();
            refreshAll();
        });
    }

    /**
     * ✅ Check Shizuku status (auto-request, no manual dialog)
     */
    private void checkShizukuAndRequestPermission() {
        // ✅ Don't re-check if already checked and authorized
        if (shizukuChecked && shizukuAuthorized) {
            logger.i("Shizuku already checked and authorized");
            updateToolbarStatus("v1.8 • 🔑 Privileged");
            return;
        }
        
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(this);
            
            isShizukuAvailable = helper.isAvailable();
            shizukuAuthorized = helper.isAuthorized();
            shizukuChecked = true;
            
            logger.i("Shizuku Status - Available: " + isShizukuAvailable + 
                     ", Authorized: " + shizukuAuthorized);
            
            if (!isShizukuAvailable) {
                ShizukuHelper.ShizukuStatus status = helper.checkShizukuActive();
                
                if (status == ShizukuHelper.ShizukuStatus.NOT_INSTALLED) {
                    showShizukuNotInstalledDialog();
                    updateToolbarStatus("v1.8 • ❌ Shizuku Not Installed");
                } else if (status == ShizukuHelper.ShizukuStatus.NOT_ACTIVE) {
                    showShizukuNotActiveDialog();
                    updateToolbarStatus("v1.8 • ⚠️ Shizuku Not Running");
                } else {
                    updateToolbarStatus("v1.8 • ❌ Shizuku Error");
                }
                return;
            }
            
            if (!shizukuAuthorized) {
                updateToolbarStatus("v1.8 • ⚠️ Not Authorized");
                // ✅ Auto-request Shizuku permission
                helper.requestPermission();
            } else {
                updateToolbarStatus("v1.8 • 🔑 Privileged");
                if (app.isServiceAutoStarted()) {
                    Snackbar.make(findViewById(android.R.id.content), 
                        "✅ Service running!", Snackbar.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            logger.e("Shizuku check error: " + e.getMessage());
            updateToolbarStatus("v1.8 • ❌ Error");
        }
    }

    private void showShizukuNotInstalledDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Shizuku Not Found")
            .setMessage("Shizuku is required for privileged operations.\n\n" +
                       "Please install Shizuku from:\n" +
                       "• Google Play Store\n" +
                       "• F-Droid\n" +
                       "• GitHub: https://github.com/RikkaApps/Shizuku")
            .setPositiveButton("Open Play Store", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, 
                        Uri.parse("market://details?id=moe.shizuku.manager")));
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW, 
                        Uri.parse("https://github.com/RikkaApps/Shizuku/releases")));
                }
            })
            .setNegativeButton("Skip", (d, w) -> updateToolbarStatus("v1.8 • ⚠️ Limited"))
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
                    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.manager");
                    if (intent != null) {
                        startActivity(intent);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Please open Shizuku manually", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Skip", (d, w) -> updateToolbarStatus("v1.8 • ⚠️ Limited"))
            .setCancelable(false)
            .show();
    }

    private void updateToolbarStatus(String status) {
        runOnUiThread(() -> {
            if (toolbar != null) {
                toolbar.setSubtitle(status);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ✅ Re-check on resume but only if not already authorized
        if (!shizukuAuthorized) {
            shizukuChecked = false;
            mainHandler.postDelayed(this::checkShizukuAndRequestPermission, 200);
        } else {
            // Just update the status
            updateToolbarStatus("v1.8 • 🔑 Privileged");
        }
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
        viewPager.setOffscreenPageLimit(3);
        viewPager.setUserInputEnabled(false);
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                viewPager.setCurrentItem(0);
                return true;
            } else if (itemId == R.id.nav_modules) {
                viewPager.setCurrentItem(1);
                return true;
            } else if (itemId == R.id.nav_logs) {
                viewPager.setCurrentItem(2);
                return true;
            } else if (itemId == R.id.nav_settings) {
                viewPager.setCurrentItem(3);
                return true;
            }
            return false;
        });
    }

    private void setupTabLayout() {
        TabLayout tabLayout = findViewById(R.id.tabLayout);
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Home");
                    tab.setIcon(R.drawable.ic_home);
                    break;
                case 1:
                    tab.setText("Modules");
                    tab.setIcon(R.drawable.ic_module);
                    break;
                case 2:
                    tab.setText("Logs");
                    tab.setIcon(R.drawable.ic_logs);
                    break;
                case 3:
                    tab.setText("Settings");
                    tab.setIcon(R.drawable.ic_settings);
                    break;
            }
        }).attach();
    }

    private void startServices() {
        Intent serviceIntent = new Intent(this, ShizuPosedService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            isServiceConnected = true;
            logger.i("Connected to ShizuPosedService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceConnected = false;
            logger.w("Disconnected from ShizuPosedService");
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_options_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_grant_shizuku) {
            shizukuChecked = false;
            ShizukuHelper.getInstance(this).requestPermission();
            Toast.makeText(this, "Check Shizuku app", Toast.LENGTH_SHORT).show();
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

    private void refreshAll() {
        for (int i = 0; i < pagerAdapter.getItemCount(); i++) {
            Fragment fragment = pagerAdapter.getFragment(i);
            if (fragment instanceof HomeFragment) {
                ((HomeFragment) fragment).refresh();
            } else if (fragment instanceof ModulesFragment) {
                ((ModulesFragment) fragment).refresh();
            } else if (fragment instanceof LogsFragment) {
                ((LogsFragment) fragment).refresh();
            }
        }
        shizukuChecked = false;
        checkShizukuAndRequestPermission();
        Toast.makeText(this, "Refreshed", Toast.LENGTH_SHORT).show();
    }

    private void restartServices() {
        Intent serviceIntent = new Intent(this, ShizuPosedService.class);
        stopService(serviceIntent);
        startService(serviceIntent);
        Toast.makeText(this, "Service restarted", Toast.LENGTH_SHORT).show();
    }

    private void showStatusDialog() {
        ShizukuHelper helper = ShizukuHelper.getInstance(this);
        boolean available = helper.isAvailable();
        boolean authorized = helper.isAuthorized();
        int version = helper.getVersion();
        boolean isSui = helper.isSui();
        boolean serviceStarted = app.isServiceAutoStarted();
        
        String status = "ShizuPosed Manager v1.8\n\n" +
                       "Permissions: " + (permissionsGranted ? "✅ Granted" : "⚠️ Missing") + "\n" +
                       "Shizuku Status: " + (available ? "✅ Available" : "❌ Unavailable") + "\n" +
                       "Authorization: " + (authorized ? "✅ Authorized" : "❌ Not Authorized") + "\n" +
                       "Shizuku Version: " + version + "\n" +
                       "Sui Active: " + (isSui ? "✅ Yes" : "❌ No") + "\n\n" +
                       "Service: " + (serviceStarted ? "✅ Started" : "❌ Not Started") + "\n" +
                       "Service Connected: " + (isServiceConnected ? "✅ Connected" : "❌ Disconnected") + "\n\n" +
                       "Privileged Mode: " + (authorized ? "🔑 Enabled" : "⚠️ Limited");
        
        new AlertDialog.Builder(this)
            .setTitle("Status")
            .setMessage(status)
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        if (isServiceConnected) {
            unbindService(serviceConnection);
        }
        super.onDestroy();
    }
}
