package com.shizuposed.manager.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.adapter.AppSelectionAdapter;
import com.shizuposed.manager.adapter.ModuleAdapter;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ModuleScanner;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.stealth.XStealthPrefs;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ModulesFragment
 *
 * Shows every installed Xposed module, with the built-in XStealth
 * pinned at the top of the list.
 *
 * APP LIST CACHING
 * ----------------
 * The scope editor needs the installed-app list, which requires
 * PackageManager.getInstalledApplications(GET_META_DATA | ...).
 * That call opens every installed APK and reads its manifest, which
 * costs 300-800ms on a device with 200+ apps. Running it on every
 * dialog open makes the dialog feel sluggish.
 *
 * The fix is a two-layer cache:
 *
 *   • A process-wide static list of ApplicationInfo, invalidated
 *     when the system broadcasts a package add / remove / change /
 *     replace. A short TTL bounds staleness if a broadcast is
 *     missed.
 *
 *   • An eager warm-up on fragment open, run on a background
 *     thread, so the first dialog open after the tab appears
 *     already has a populated cache.
 *
 * The scope editor is also async: it opens immediately with a
 * spinner, and the list populates when the query returns. If the
 * cache is warm (typical), the populate happens within a frame.
 */
public class ModulesFragment extends Fragment {
    private RecyclerView moduleRecyclerView;
    private ProgressBar progressIndicator;
    private FloatingActionButton fabAddModule;
    private EditText searchView;
    private TextView tvEmptyState;

    private ModuleLoader moduleLoader;
    private Logger logger;
    private ShizukuHelper shizukuHelper;
    private PackageManager packageManager;

    private List<ModuleInfo> modules = new ArrayList<>();
    private ModuleAdapter moduleAdapter;

    private AlertDialog addModuleDialog = null;
    private AlertDialog selectAppsDialog = null;
    private AlertDialog confirmDialog = null;

    private TextView tvFilePath;
    private EditText etPackage, etModuleName, etEntry;
    private MaterialCheckBox cbAutoDetect;
    private Uri selectedApkUri = null;
    private String selectedApkPath = null;
    private String selectedApkName = null;
    private boolean isFilePickerActive = false;

    private long lastToggleTime = 0;
    private static final long TOGGLE_DEBOUNCE = 500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorService scannerExecutor = null;
    private ExecutorService appListExecutor = null;

    private volatile boolean scanInProgress = false;
    private volatile boolean viewReady = false;

    /** Fires when XStealth's master toggle changes in SharedPreferences. */
    private SharedPreferences.OnSharedPreferenceChangeListener xStealthPrefsListener;

    // ═════════════════════════════════════════════════════════════
    // APP LIST CACHE
    //
    // Process-wide, not per-fragment. Two fragments (Modules tab and
    // a future Repo tab or settings screen) that both need the list
    // share the same cache.
    //
    // Volatile: reads and writes happen on different threads. The
    // assignment of a fully-built List is atomic; no other state is
    // shared.
    // ═════════════════════════════════════════════════════════════

    private static volatile List<ApplicationInfo> sCachedApps = null;
    private static volatile long sCachedAppsAt = 0L;

    /** Defensive TTL in case a package-change broadcast is missed. */
    private static final long APP_CACHE_TTL_MS = 60_000L;

    /** True while a background refresh is in flight. */
    private static volatile boolean sAppRefreshInFlight = false;

    /**
     * Receiver that clears the cache when the app set changes.
     * Registered per-fragment but shared behavior: any instance that
     * receives the broadcast clears the shared static cache, so a
     * single registration is enough.
     */
    private BroadcastReceiver packageChangeReceiver;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    isFilePickerActive = false;
                    if (!isAdded() || getContext() == null) return;
                    if (!viewReady) return;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri != null) handleSelectedApk(uri);
                    } else {
                        Toast.makeText(requireContext(), "No file selected",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        packageManager = context.getPackageManager();
        logger = Logger.getInstance(context);
        moduleLoader = ModuleLoader.getInstance(context);
        shizukuHelper = ShizukuHelper.getInstance(context);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scannerExecutor = Executors.newSingleThreadExecutor();
        appListExecutor = Executors.newSingleThreadExecutor();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_modules, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;
        initViews(view);
        setupRecyclerView();
        setupListeners();
        registerXStealthPrefsListener();
        registerPackageChangeReceiver();
        warmAppListCache();
        loadModules();
        startBackgroundScan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        unregisterXStealthPrefsListener();
        unregisterPackageChangeReceiver();
        dismissAllDialogs();
        addModuleDialog = null;
        selectAppsDialog = null;
        confirmDialog = null;
        isFilePickerActive = false;
        moduleRecyclerView = null;
        progressIndicator = null;
        fabAddModule = null;
        searchView = null;
        tvEmptyState = null;
        moduleAdapter = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scannerExecutor != null) {
            scannerExecutor.shutdownNow();
            scannerExecutor = null;
        }
        if (appListExecutor != null) {
            appListExecutor.shutdownNow();
            appListExecutor = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!viewReady) return;
        loadModules();
        startBackgroundScan();
    }

    public void refresh() {
        if (!viewReady) {
            if (logger != null) logger.d("refresh() skipped: view not ready");
            return;
        }
        loadModules();
    }

    // ═════════════════════════════════════════════════════════════
    // APP LIST CACHE
    // ═════════════════════════════════════════════════════════════

    /**
     * Return the cached app list if it's fresh, or null.
     */
    private static List<ApplicationInfo> getCachedApps() {
        List<ApplicationInfo> cached = sCachedApps;
        if (cached == null) return null;
        if (System.currentTimeMillis() - sCachedAppsAt > APP_CACHE_TTL_MS) return null;
        return cached;
    }

    /**
     * Query PackageManager for the full app list and store it in
     * the cache. Called on a background thread only.
     */
    private List<ApplicationInfo> queryInstalledApps() {
        if (packageManager == null) return new ArrayList<>();
        try {
            List<ApplicationInfo> fresh = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (fresh == null) return new ArrayList<>();
            sCachedApps = fresh;
            sCachedAppsAt = System.currentTimeMillis();
            if (logger != null) {
                logger.d("App list cached: " + fresh.size() + " packages");
            }
            return fresh;
        } catch (Throwable t) {
            if (logger != null) logger.w("queryInstalledApps failed: " + t.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Warm the cache on fragment open. Runs on the appListExecutor,
     * so it doesn't touch the UI thread. If the cache is already
     * warm, this is a no-op.
     *
     * The result is discarded here — the next caller picks it up
     * from the static cache. This exists purely to shift the query
     * cost off the first user interaction.
     */
    private void warmAppListCache() {
        if (getCachedApps() != null) return;
        if (sAppRefreshInFlight) return;
        if (appListExecutor == null || appListExecutor.isShutdown()) return;

        sAppRefreshInFlight = true;
        appListExecutor.execute(() -> {
            try {
                queryInstalledApps();
            } finally {
                sAppRefreshInFlight = false;
            }
        });
    }

    /**
     * Register a receiver that clears the cache when the installed
     * app set changes. ACTION_PACKAGE_REPLACED handles updates that
     * don't change the package name but do change metadata.
     */
    private void registerPackageChangeReceiver() {
        if (!isAdded() || getContext() == null) return;
        try {
            packageChangeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getData() == null) return;
                    sCachedApps = null;
                    sCachedAppsAt = 0L;
                    if (logger != null) {
                        logger.d("App cache invalidated: "
                            + intent.getAction() + " " + intent.getData());
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");

            Context ctx = requireContext().getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(packageChangeReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(packageChangeReceiver, filter);
            }
        } catch (Throwable t) {
            if (logger != null) logger.w("registerPackageChangeReceiver failed: "
                + t.getMessage());
            packageChangeReceiver = null;
        }
    }

    private void unregisterPackageChangeReceiver() {
        if (packageChangeReceiver == null) return;
        try {
            requireContext().getApplicationContext()
                .unregisterReceiver(packageChangeReceiver);
        } catch (Throwable ignored) {}
        packageChangeReceiver = null;
    }

    // ═════════════════════════════════════════════════════════════
    // XSTEALTH STATE SYNC
    // ═════════════════════════════════════════════════════════════

    private void registerXStealthPrefsListener() {
        if (!isAdded() || getContext() == null) return;
        try {
            xStealthPrefsListener = (sharedPreferences, key) -> {
                if (key == null) return;
                if (!"enabled".equals(key)) return;
                if (!viewReady) return;
                if (logger != null) logger.d("XStealth prefs changed, reloading rows");
                loadModules();
            };
            requireContext()
                .getSharedPreferences("xstealth", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(xStealthPrefsListener);
        } catch (Throwable t) {
            if (logger != null) logger.w("registerXStealthPrefsListener failed: "
                + t.getMessage());
        }
    }

    private void unregisterXStealthPrefsListener() {
        if (xStealthPrefsListener == null) return;
        try {
            requireContext()
                .getSharedPreferences("xstealth", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(xStealthPrefsListener);
        } catch (Throwable ignored) {}
        xStealthPrefsListener = null;
    }

    // ═════════════════════════════════════════════════════════════
    // VIEW SETUP
    // ═════════════════════════════════════════════════════════════

    private void initViews(View view) {
        moduleRecyclerView = view.findViewById(R.id.moduleRecyclerView);
        progressIndicator = view.findViewById(R.id.progressIndicator);
        fabAddModule = view.findViewById(R.id.fabAddModule);
        searchView = view.findViewById(R.id.searchView);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
    }

    private void setupRecyclerView() {
        if (moduleRecyclerView == null) return;
        moduleAdapter = new ModuleAdapter(modules, requireContext());
        moduleAdapter.setOnModuleActionListener(new ModuleAdapter.OnModuleActionListener() {
            @Override public void onToggle(ModuleInfo module, boolean enable) { toggleModule(module, enable); }
            @Override public void onDetail(ModuleInfo module) { showModuleDetail(module); }
            @Override public void onUninstall(ModuleInfo module) { uninstallModule(module); }
            @Override public void onSelectApps(ModuleInfo module) { showSelectAppsDialog(module); }
        });
        moduleRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        moduleRecyclerView.setAdapter(moduleAdapter);
    }

    private void setupListeners() {
        if (fabAddModule != null) {
            fabAddModule.setOnClickListener(v -> {
                dismissAllDialogs();
                showAddModuleDialog();
            });
        }
        if (searchView != null) {
            searchView.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterModules(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void dismissAllDialogs() {
        if (addModuleDialog != null && addModuleDialog.isShowing()) addModuleDialog.dismiss();
        if (selectAppsDialog != null && selectAppsDialog.isShowing()) selectAppsDialog.dismiss();
        if (confirmDialog != null && confirmDialog.isShowing()) confirmDialog.dismiss();
    }

    // ═════════════════════════════════════════════════════════════
    // BACKGROUND SCAN
    // ═════════════════════════════════════════════════════════════

    private void startBackgroundScan() {
        if (!viewReady || scanInProgress) return;
        if (scannerExecutor == null || scannerExecutor.isShutdown()) {
            if (logger != null) logger.d("startBackgroundScan: executor not available");
            return;
        }
        scanInProgress = true;

        final Context appCtx = requireContext().getApplicationContext();
        scannerExecutor.execute(() -> {
            try {
                ModuleScanner.ScanResult result =
                    ModuleScanner.scanInstalledModules(appCtx);

                if (!isAdded()) return;
                mainHandler.post(() -> {
                    scanInProgress = false;
                    if (!viewReady) return;
                    loadModules();

                    View v = getView();
                    if (v == null) return;

                    int newCount = result.newlyRegistered;
                    int purged = result.purgedCount;

                    if (purged > 0 && newCount > 0) {
                        Snackbar.make(v,
                            purged + " removed, " + newCount + " added",
                            Snackbar.LENGTH_LONG).show();
                    } else if (purged > 0) {
                        Snackbar.make(v,
                            purged + " module" + (purged != 1 ? "s" : "") + " removed",
                            Snackbar.LENGTH_LONG).show();
                    } else if (newCount > 0) {
                        Snackbar.make(v,
                            newCount + " new module" + (newCount != 1 ? "s" : "") + " detected",
                            Snackbar.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                scanInProgress = false;
                if (isAdded()) {
                    mainHandler.post(() -> {
                        if (logger != null) logger.w("Module scan failed: " + t.getMessage());
                    });
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════
    // MODULE LIST
    // ═════════════════════════════════════════════════════════════

    private void loadModules() {
        if (!viewReady) return;
        if (moduleLoader == null) {
            if (logger != null) logger.w("loadModules: moduleLoader is null");
            return;
        }
        if (moduleAdapter == null) {
            if (logger != null) logger.w("loadModules: adapter not ready");
            return;
        }

        showLoading(true);
        try {
            modules = moduleLoader.loadModules();

            if (modules == null || modules.isEmpty()) {
                if (logger != null) {
                    logger.i("loadModules: empty, running synchronous scan");
                }
                try {
                    ModuleScanner.scanInstalledModules(requireContext());
                    modules = moduleLoader.loadModules();
                } catch (Throwable t) {
                    if (logger != null) logger.w("Synchronous scan failed: " + t.getMessage());
                }
            }

            moveXStealthToTop(modules);
            moduleAdapter.updateData(modules);

            if (tvEmptyState != null) {
                tvEmptyState.setVisibility(modules.isEmpty() ? View.VISIBLE : View.GONE);
            }
            if (moduleRecyclerView != null) {
                moduleRecyclerView.setVisibility(modules.isEmpty() ? View.GONE : View.VISIBLE);
            }
            if (logger != null) {
                logger.i("loadModules: final count = " + modules.size());
            }
        } catch (Exception e) {
            if (logger != null) logger.e("loadModules error: " + e.getMessage());
        }
        showLoading(false);
    }

    private void moveXStealthToTop(List<ModuleInfo> list) {
        if (list == null || list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            ModuleInfo m = list.get(i);
            if (m != null && XStealthModule.PACKAGE.equals(m.packageName)) {
                if (i != 0) {
                    ModuleInfo x = list.remove(i);
                    list.add(0, x);
                }
                return;
            }
        }
    }

    private void filterModules(String query) {
        if (!viewReady || moduleAdapter == null) return;
        if (query == null || query.isEmpty()) {
            moduleAdapter.updateData(modules);
            return;
        }
        List<ModuleInfo> filtered = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (ModuleInfo module : modules) {
            if (module == null || module.packageName == null) continue;
            if (module.packageName.toLowerCase().contains(lowerQuery) ||
                (module.name != null && module.name.toLowerCase().contains(lowerQuery))) {
                filtered.add(module);
            }
        }
        moduleAdapter.updateData(filtered);
    }

    private void updateModuleRow(ModuleInfo updated) {
        if (!viewReady || updated == null || updated.packageName == null) return;
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo existing = modules.get(i);
            if (existing != null && updated.packageName.equals(existing.packageName)) {
                modules.set(i, updated);
                if (moduleAdapter != null) moduleAdapter.notifyItemChanged(i);
                return;
            }
        }
        loadModules();
    }

    // ═════════════════════════════════════════════════════════════
    // PROVIDER + BROADCAST
    // ═════════════════════════════════════════════════════════════

    public void requestModuleRepush(String reason) {
        if (!isAdded() || getContext() == null) return;
        try {
            if (!ShizuPosedService.isServiceRunning()) {
                if (logger != null) logger.d("Service not running — skipping repush (" + reason + ")");
                return;
            }
            Intent i = new Intent(requireContext(), ShizuPosedService.class);
            i.setAction(ShizuPosedService.ACTION_REPUSH_MODULES);
            requireContext().startForegroundService(i);
            if (logger != null) logger.d("Requested module repush: " + reason);
        } catch (Throwable t) {
            if (logger != null) logger.w("requestModuleRepush failed: " + t.getMessage());
        }
    }

    private void notifyProviderChanged(String packageName, boolean enabled) {
        if (!isAdded() || getContext() == null) return;
        try {
            if (packageName != null) {
                requireContext().getContentResolver().notifyChange(
                    com.shizuposed.manager.status.ModuleStatusProvider
                        .moduleUri(packageName), null);
            }
            requireContext().getContentResolver().notifyChange(
                com.shizuposed.manager.status.ModuleStatusProvider.MODULES_URI, null);
            requireContext().getContentResolver().notifyChange(
                com.shizuposed.manager.status.ModuleStatusProvider.INFO_URI, null);
        } catch (Throwable t) {
            if (logger != null) logger.d("notifyChange failed: " + t.getMessage());
        }
    }

    private void broadcastModuleStateChange(ModuleInfo module) {
        if (!isAdded() || getContext() == null) return;
        if (module == null || module.packageName == null) return;
        try {
            Intent i = new Intent(module.enabled
                ? "de.robv.android.xposed.action.MODULE_ENABLED"
                : "de.robv.android.xposed.action.MODULE_DISABLED");
            i.putExtra("module", module.packageName);
            i.setPackage(module.packageName);
            requireContext().sendBroadcast(i);
            if (logger != null) logger.d("Broadcast module state: "
                    + module.packageName + " -> " + module.enabled);
        } catch (Throwable t) {
            if (logger != null) logger.w("broadcastModuleStateChange failed: " + t.getMessage());
        }
    }

    private void announceModuleStateChange(ModuleInfo module) {
        if (module == null) return;
        notifyProviderChanged(module.packageName, module.enabled);
        broadcastModuleStateChange(module);
    }

    // ═════════════════════════════════════════════════════════════
    // TOGGLE
    // ═════════════════════════════════════════════════════════════

    private void toggleModule(ModuleInfo module, boolean enable) {
        if (module == null) return;

        if (XStealthModule.PACKAGE.equals(module.packageName)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastToggleTime < TOGGLE_DEBOUNCE) return;
        lastToggleTime = now;

        if (moduleLoader == null) return;
        try {
            module.enabled = enable;
            moduleLoader.saveModule(module);

            String msg = enable ? "Module enabled" : "Module disabled";
            if (isAdded()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            }
            if (logger != null) logger.i(msg + ": " + module.packageName);

            updateModuleRow(module);
            requestModuleRepush("toggle " + module.packageName + " -> " + enable);
            announceModuleStateChange(module);
        } catch (Exception e) {
            if (isAdded()) {
                Toast.makeText(requireContext(), "Failed to toggle module: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
            if (logger != null) logger.e("Toggle error: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DETAIL SHEET
    // ═════════════════════════════════════════════════════════════

    private void showModuleDetail(ModuleInfo module) {
        if (module == null || module.packageName == null) return;
        if (!isAdded()) return;

        if (XStealthModule.PACKAGE.equals(module.packageName)) {
            XStealthDetailSheet.newInstance()
                .show(getParentFragmentManager(), "xstealth_detail");
            return;
        }

        ModuleDetailSheet sheet = ModuleDetailSheet.newInstance(module.packageName);
        sheet.show(getChildFragmentManager(), "module_detail");
    }

    public void onModuleUpdated(ModuleInfo updated) {
        if (!viewReady || updated == null) return;
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo existing = modules.get(i);
            if (existing != null && updated.packageName.equals(existing.packageName)) {
                modules.set(i, updated);
                if (moduleAdapter != null) moduleAdapter.notifyItemChanged(i);
                break;
            }
        }
        requestModuleRepush("updated " + updated.packageName);
        announceModuleStateChange(updated);
    }

    public void onModuleRemoved(String packageName) {
        if (!viewReady || packageName == null) return;
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo existing = modules.get(i);
            if (existing != null && packageName.equals(existing.packageName)) {
                modules.remove(i);
                if (moduleAdapter != null) moduleAdapter.notifyItemRemoved(i);
                break;
            }
        }

        if (modules.isEmpty()) {
            if (tvEmptyState != null) tvEmptyState.setVisibility(View.VISIBLE);
            if (moduleRecyclerView != null) moduleRecyclerView.setVisibility(View.GONE);
        }
        requestModuleRepush("removed " + packageName);

        try {
            Intent i = new Intent("de.robv.android.xposed.action.MODULE_DISABLED");
            i.putExtra("module", packageName);
            i.setPackage(packageName);
            if (isAdded()) requireContext().sendBroadcast(i);
        } catch (Throwable ignored) {}
        notifyProviderChanged(packageName, false);
    }

    // ═════════════════════════════════════════════════════════════
    // LAUNCH
    // ═════════════════════════════════════════════════════════════

    public void launchUnderShizuPosed(String packageName) {
        if (packageName == null) return;
        if (!isAdded()) return;
        if (shizukuHelper == null || !shizukuHelper.isAvailable() || !shizukuHelper.isAuthorized()) {
            Toast.makeText(requireContext(),
                "Shizuku not available or not authorized", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent svc = new Intent(requireContext(), ShizuPosedService.class);
            svc.setAction(ShizuPosedService.ACTION_LAUNCH_APP);
            svc.putExtra(ShizuPosedService.EXTRA_LAUNCH_PACKAGE, packageName);
            requireContext().startForegroundService(svc);
            Toast.makeText(requireContext(),
                "Launching " + packageName + " under ShizuPosed…",
                Toast.LENGTH_SHORT).show();
            if (logger != null) logger.i("Requested launch under ShizuPosed: " + packageName);
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                "Failed to launch: " + t.getMessage(), Toast.LENGTH_LONG).show();
            if (logger != null) logger.e("launchUnderShizuPosed failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SCOPE EDITOR
    // ═════════════════════════════════════════════════════════════
    public void openScopeEditor(ModuleInfo module) {
        if (module == null || !isAdded() || !viewReady) return;
        if (XStealthModule.PACKAGE.equals(module.packageName)) return;
        showSelectAppsDialog(module);
    }

    public void showSelectAppsDialog(ModuleInfo module) {
        if (module == null || !isAdded() || getContext() == null) return;
        if (XStealthModule.PACKAGE.equals(module.packageName)) return;

        if (selectAppsDialog != null && selectAppsDialog.isShowing()) {
            selectAppsDialog.dismiss();
        }

        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_select_apps, null);

        RecyclerView appRecyclerView = dialogView.findViewById(R.id.appRecyclerView);
        ProgressBar dialogSpinner = dialogView.findViewById(R.id.appListSpinner);
        TextInputEditText etSearchApps = dialogView.findViewById(R.id.etSearchApps);
        Chip chipSelectAll = dialogView.findViewById(R.id.chipSelectAll);
        Chip chipClearAll = dialogView.findViewById(R.id.chipClearAll);
        Chip chipSelectSystem = dialogView.findViewById(R.id.chipSelectSystem);
        com.google.android.material.button.MaterialButton btnApply =
            dialogView.findViewById(R.id.btnApply);
        TextView tvSelectedCount = dialogView.findViewById(R.id.tvSelectedCount);
        MaterialCheckBox cbHideSystem = dialogView.findViewById(R.id.cbHideSystem);

        // ── Selection state is initialized from the module and
        //    survives the async list load.
        Set<String> currentSelection = new HashSet<>();
        if (module.hookedApps != null) currentSelection.addAll(module.hookedApps);

        AppSelectionAdapter adapter = new AppSelectionAdapter(requireContext());

        if (tvSelectedCount != null) {
            tvSelectedCount.setText(currentSelection.size() + " selected");
            adapter.setOnSelectionChangedListener(count ->
                tvSelectedCount.setText(count + " selected"));
        }
        adapter.setSelectedApps(currentSelection);

        if (appRecyclerView != null) {
            appRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            appRecyclerView.setAdapter(adapter);
            appRecyclerView.setVisibility(View.GONE);
        }
        if (dialogSpinner != null) {
            dialogSpinner.setVisibility(View.VISIBLE);
        }

        // ── Search, hide-system, chip state. These work on the
        //    filtered list once it's populated. Until then they're
        //    inert — the adapters are empty so nothing happens.
        final List<ApplicationInfo> source = new ArrayList<>();
        final List<ApplicationInfo> userApps = new ArrayList<>();
        final List<ApplicationInfo> allAppsRef = new ArrayList<>();

        final Runnable reapply = () -> {
            if (etSearchApps == null) return;
            String query = etSearchApps.getText().toString().toLowerCase().trim();
            List<ApplicationInfo> filtered = new ArrayList<>();
            for (ApplicationInfo app : source) {
                if (query.isEmpty()) { filtered.add(app); continue; }
                String label = app.loadLabel(packageManager).toString().toLowerCase();
                if (label.contains(query) || app.packageName.toLowerCase().contains(query)) {
                    filtered.add(app);
                }
            }
            adapter.setApps(filtered);
        };

        if (cbHideSystem != null) {
            cbHideSystem.setChecked(true);
            cbHideSystem.setOnCheckedChangeListener((v, checked) -> {
                source.clear();
                source.addAll(checked ? userApps : allAppsRef);
                reapply.run();
            });
        }

        if (etSearchApps != null) {
            etSearchApps.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int i, int b, int c) { reapply.run(); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        Chip chipRecommended = dialogView.findViewById(R.id.chipRecommended);
        if (chipRecommended != null) {
            final Set<String> recommended = (module.recommendedApps != null)
                ? new HashSet<>(module.recommendedApps)
                : new HashSet<>();
            adapter.setRecommendedApps(recommended);
            chipRecommended.setVisibility(
                recommended.isEmpty() ? View.GONE : View.VISIBLE);
            chipRecommended.setOnClickListener(v -> {
                adapter.selectRecommended();
                Toast.makeText(requireContext(),
                    "Selected " + recommended.size() + " recommended app"
                        + (recommended.size() != 1 ? "s" : ""),
                    Toast.LENGTH_SHORT).show();
            });
        }

        if (chipSelectAll != null) chipSelectAll.setOnClickListener(v -> adapter.selectAll());
        if (chipClearAll != null) chipClearAll.setOnClickListener(v -> adapter.clearAll());
        if (chipSelectSystem != null) {
            chipSelectSystem.setOnClickListener(v -> {
                if (cbHideSystem != null && cbHideSystem.isChecked()) {
                    cbHideSystem.setChecked(false);
                }
                adapter.selectSystemApps();
            });
        }
        if (btnApply != null) btnApply.setVisibility(View.GONE);

        // ── Async populate. The dialog is already built; the list
        //    appears when this completes.
        final String selfPkg = module.packageName;
        if (appListExecutor != null && !appListExecutor.isShutdown()) {
            appListExecutor.execute(() -> {
                // Use the cache if warm, otherwise query.
                List<ApplicationInfo> all = getCachedApps();
                if (all == null) {
                    all = queryInstalledApps();
                }
                List<ApplicationInfo> filtered = filterForScopeEditor(all, selfPkg);

                // Split into user and system.
                List<ApplicationInfo> users = new ArrayList<>();
                for (ApplicationInfo app : filtered) {
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) users.add(app);
                }

                final List<ApplicationInfo> finalAll = filtered;
                final List<ApplicationInfo> finalUsers = users;

                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    if (selectAppsDialog == null || !selectAppsDialog.isShowing()) return;

                    allAppsRef.clear();
                    allAppsRef.addAll(finalAll);
                    userApps.clear();
                    userApps.addAll(finalUsers);

                    // Respect the current hide-system toggle state.
                    boolean hideSystem = cbHideSystem == null || cbHideSystem.isChecked();
                    source.clear();
                    source.addAll(hideSystem ? finalUsers : finalAll);
                    reapply.run();

                    if (dialogSpinner != null) dialogSpinner.setVisibility(View.GONE);
                    if (appRecyclerView != null) appRecyclerView.setVisibility(View.VISIBLE);
                });
            });
        } else {
            if (dialogSpinner != null) dialogSpinner.setVisibility(View.GONE);
            if (appRecyclerView != null) appRecyclerView.setVisibility(View.VISIBLE);
        }

        final ModuleInfo moduleFinal = module;

        selectAppsDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Select Apps for " + module.name)
            .setView(dialogView)
            .setPositiveButton("Apply", (d, w) -> {
                if (moduleLoader == null) return;
                Set<String> selected = adapter.getSelectedApps();
                moduleFinal.hookedApps = selected;
                moduleLoader.saveModule(moduleFinal);
                updateModuleRow(moduleFinal);
                int count = selected.size();
                String msg = count + " app" + (count != 1 ? "s" : "") + " selected";
                if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                if (logger != null) logger.i(msg + " for " + moduleFinal.name);
                requestModuleRepush("selectApps " + moduleFinal.packageName + " -> " + count);
                announceModuleStateChange(moduleFinal);
            })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(dialog -> selectAppsDialog = null)
            .create();

        selectAppsDialog.setCanceledOnTouchOutside(false);
        selectAppsDialog.show();
    }

    /**
     * Filter the full app list down to what the scope editor should
     * show. Removes the manager itself, the module being scoped, and
     * every other installed module.
     */
    private List<ApplicationInfo> filterForScopeEditor(List<ApplicationInfo> all,
                                                       String selfPackage) {
        List<ApplicationInfo> out = new ArrayList<>();
        if (all == null) return out;

        String managerSelf;
        try {
            managerSelf = requireContext().getPackageName();
        } catch (Throwable t) {
            managerSelf = "com.shizuposed.manager";
        }

        Set<String> modulePackages = new HashSet<>();
        try {
            if (moduleLoader != null) {
                for (ModuleInfo m : moduleLoader.getCachedModules()) {
                    if (m != null && m.packageName != null)
                        modulePackages.add(m.packageName);
                }
            }
        } catch (Throwable t) {
            if (logger != null) logger.w("Could not read module list: " + t.getMessage());
        }

        for (ApplicationInfo app : all) {
            if (app == null || app.packageName == null) continue;
            if (managerSelf.equals(app.packageName)) continue;

            boolean isSelf = selfPackage != null
                && selfPackage.equals(app.packageName);

            boolean allowSelf = isSelf
                && ModuleScanner.hasLauncherActivity(
                    requireContext(), selfPackage);

            if (modulePackages.contains(app.packageName)
                    && !(isSelf && allowSelf)) {
                continue;
            }

            out.add(app);
        }

        try {
            out.sort((a, b) -> a.loadLabel(packageManager).toString()
                    .compareToIgnoreCase(b.loadLabel(packageManager).toString()));
        } catch (Throwable ignored) {}

        return out;
    }

    // ═════════════════════════════════════════════════════════════
    // UNINSTALL
    // ═════════════════════════════════════════════════════════════

    public void uninstallModule(ModuleInfo module) {
        if (module == null || !isAdded() || !viewReady) return;

        if (XStealthModule.PACKAGE.equals(module.packageName)) {
            confirmDialog = new AlertDialog.Builder(requireContext())
                .setTitle("XStealth is built-in")
                .setMessage("XStealth ships with ShizuPosed and cannot be removed. "
                    + "Disable it instead?")
                .setPositiveButton("Disable", (dialog, which) -> {
                    XStealthPrefs.setEnabled(requireContext(), false);
                    if (isAdded()) Toast.makeText(requireContext(),
                        "XStealth disabled", Toast.LENGTH_SHORT).show();
                    loadModules();
                    requestModuleRepush("disable xstealth");
                })
                .setNegativeButton("Cancel", null)
                .create();
            confirmDialog.show();
            return;
        }

        confirmDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Uninstall Module")
            .setMessage("Remove " + (module.name != null ? module.name : module.packageName)
                + " from ShizuPosed?\n\nThe module APK itself is not touched.")
            .setPositiveButton("Remove", (dialog, which) -> {
                if (moduleLoader == null) return;
                try {
                    boolean removed = moduleLoader.uninstallModule(module.packageName);
                    if (removed) {
                        onModuleRemoved(module.packageName);
                        if (isAdded()) Toast.makeText(requireContext(),
                            "Removed from ShizuPosed", Toast.LENGTH_SHORT).show();
                        if (logger != null) logger.i("Removed module: " + module.packageName);
                    } else {
                        if (isAdded()) Toast.makeText(requireContext(),
                            "Failed to remove", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    if (isAdded()) Toast.makeText(requireContext(),
                        "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    if (logger != null) logger.e("Uninstall error: " + e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .create();
        confirmDialog.show();
    }

    // ═════════════════════════════════════════════════════════════
    // ADD MODULE
    // ═════════════════════════════════════════════════════════════

    private void showAddModuleDialog() {
        if (!isAdded() || !viewReady) return;
        if (addModuleDialog != null && addModuleDialog.isShowing()) addModuleDialog.dismiss();

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_module, null);

        etPackage = view.findViewById(R.id.etPackageName);
        etModuleName = view.findViewById(R.id.etModuleName);
        etEntry = view.findViewById(R.id.etEntryPoint);
        cbAutoDetect = view.findViewById(R.id.cbAutoDetect);
        Button btnBrowse = view.findViewById(R.id.btnBrowseApk);
        tvFilePath = view.findViewById(R.id.tvFilePath);

        selectedApkUri = null;
        selectedApkPath = null;
        selectedApkName = null;
        if (tvFilePath != null) tvFilePath.setText("No file selected");

        if (cbAutoDetect != null) {
            cbAutoDetect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (etEntry != null) {
                    etEntry.setEnabled(!isChecked);
                    if (isChecked) etEntry.setText("");
                }
            });
        }

        if (etPackage != null && etModuleName != null) {
            etPackage.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (etModuleName.getTag() == null || !(Boolean) etModuleName.getTag()) {
                        String pkgName = s.toString().trim();
                        if (!pkgName.isEmpty()) {
                            etModuleName.setText(generateModuleName(pkgName));
                        }
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });

            etModuleName.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) etModuleName.setTag(true);
            });
            etModuleName.setTag(false);
        }

        if (btnBrowse != null) {
            btnBrowse.setOnClickListener(v -> {
                if (isFilePickerActive) return;
                isFilePickerActive = true;
                openFilePicker();
            });
        }

        builder.setTitle("Add Module (manual)")
            .setView(view)
            .setPositiveButton("Add", (dialog, which) -> addModuleFromDialog())
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(dialog -> {
                addModuleDialog = null;
                isFilePickerActive = false;
            });

        addModuleDialog = builder.create();
        addModuleDialog.setCanceledOnTouchOutside(false);
        addModuleDialog.show();
    }

    private String generateModuleName(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        String[] parts = packageName.split("\\.");
        String name = parts[parts.length - 1];
        name = name.replace("module", "").replace("Module", "")
                   .replace("xposed", "").replace("Xposed", "")
                   .replace("hook", "").replace("Hook", "")
                   .replace("_", " ").replace("-", " ").trim();
        if (!name.isEmpty()) {
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    private void openFilePicker() {
        if (!isAdded() || getContext() == null) {
            isFilePickerActive = false;
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        }

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, "Select APK File"));
        } catch (Exception e) {
            isFilePickerActive = false;
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("application/vnd.android.package-archive");
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                filePickerLauncher.launch(Intent.createChooser(fallback, "Select APK File"));
            } catch (Exception e2) {
                isFilePickerActive = false;
                if (isAdded()) Toast.makeText(requireContext(),
                    "No file picker available", Toast.LENGTH_SHORT).show();
                if (logger != null) logger.e("File picker error: " + e2.getMessage());
            }
        }
    }

    private void handleSelectedApk(Uri uri) {
        isFilePickerActive = false;
        if (!isAdded() || getContext() == null || !viewReady) return;

        if (addModuleDialog == null || !addModuleDialog.isShowing()) {
            showAddModuleDialog();
            if (tvFilePath == null) return;
        }

        try {
            selectedApkUri = uri;
            String fileName = getFileNameFromUri(uri);
            if (fileName != null && !fileName.isEmpty()) {
                selectedApkName = fileName;
                if (tvFilePath != null) tvFilePath.setText("Selected: " + fileName);

                String filePath = getFilePathFromUri(uri);
                selectedApkPath = (filePath != null) ? filePath : copyApkToCache(uri, fileName);

                if (selectedApkPath != null && etPackage != null
                        && etPackage.getText().toString().isEmpty()) {
                    String pkgName = extractPackageName(selectedApkPath);
                    if (pkgName != null && !pkgName.isEmpty()) etPackage.setText(pkgName);
                }

                if (isAdded()) Toast.makeText(requireContext(),
                    "File selected: " + fileName, Toast.LENGTH_SHORT).show();
                if (logger != null) logger.i("Selected APK: " + fileName);
            } else if (tvFilePath != null) {
                tvFilePath.setText("Unknown file");
            }
        } catch (Exception e) {
            if (logger != null) logger.e("File selection error: " + e.getMessage());
            if (tvFilePath != null) tvFilePath.setText("Error: " + e.getMessage());
            if (isAdded()) Toast.makeText(requireContext(),
                "Failed to process file", Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        try {
            if (uri.getScheme() != null && uri.getScheme().equals("content")) {
                try (android.database.Cursor cursor = requireContext().getContentResolver()
                        .query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx != -1) fileName = cursor.getString(idx);
                    }
                }
            }
            if (fileName == null) fileName = uri.getLastPathSegment();
        } catch (Exception e) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }

    private String getFilePathFromUri(Uri uri) {
        try {
            return "file".equals(uri.getScheme()) ? uri.getPath() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String copyApkToCache(Uri uri, String fileName) {
        try {
            File cacheDir = requireContext().getCacheDir();
            File destFile = new File(cacheDir,
                "selected_module_" + System.currentTimeMillis() + ".apk");
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            if (logger != null) logger.e("Failed to copy APK: " + e.getMessage());
            return null;
        }
    }

    private String extractPackageName(String apkPath) {
        try {
            android.content.pm.PackageInfo info =
                packageManager.getPackageArchiveInfo(apkPath, 0);
            return info != null ? info.packageName : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void addModuleFromDialog() {
        if (etPackage == null || etModuleName == null) return;
        if (moduleLoader == null) return;

        String packageName = etPackage.getText().toString().trim();
        String moduleName = etModuleName.getText().toString().trim();
        String entryPoint = etEntry != null ? etEntry.getText().toString().trim() : "";

        if (packageName.isEmpty()) {
            if (isAdded()) Toast.makeText(requireContext(),
                "Package name is required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (moduleName.isEmpty()) moduleName = generateModuleName(packageName);
        if (selectedApkPath == null) {
            if (isAdded()) Toast.makeText(requireContext(),
                "Please select an APK file", Toast.LENGTH_SHORT).show();
            return;
        }

        ModuleInfo module = new ModuleInfo();
        module.packageName = packageName;
        module.name = moduleName;
        module.xposedInit = entryPoint.isEmpty() ? null : entryPoint;
        module.apkPath = selectedApkPath;
        module.enabled = true;
        module.hookedApps = new HashSet<>();

        try {
            moduleLoader.installModule(module);
            loadModules();

            if (selectedApkPath.startsWith(requireContext().getCacheDir().getAbsolutePath())) {
                File cacheFile = new File(selectedApkPath);
                if (cacheFile.exists()) cacheFile.delete();
            }

            if (isAdded()) Toast.makeText(requireContext(),
                "Module added", Toast.LENGTH_SHORT).show();
            if (logger != null) logger.i("Added module: " + module.packageName);

            if (addModuleDialog != null && addModuleDialog.isShowing()) addModuleDialog.dismiss();

            requestModuleRepush("add " + module.packageName);
            announceModuleStateChange(module);
        } catch (Exception e) {
            if (isAdded()) Toast.makeText(requireContext(),
                "Failed to add module: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            if (logger != null) logger.e("Add module error: " + e.getMessage());
        }
    }

    private void showLoading(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}