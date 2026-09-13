package com.shizuposed.manager.ui;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.CheckBox;
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

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.adapter.AppSelectionAdapter;
import com.shizuposed.manager.adapter.ModuleAdapter;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.ModuleScanner;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
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

public class ModulesFragment extends Fragment {
    private RecyclerView moduleRecyclerView;
    private ProgressBar progressIndicator;
    private FloatingActionButton fabAddModule;
    private EditText searchView;
    private TextView tvEmptyState;

    private ModuleLoader moduleLoader;
    private Logger logger;
    private ShizukuHelper shizukuHelper;
    private List<ModuleInfo> modules = new ArrayList<>();
    private ModuleAdapter moduleAdapter;
    private PackageManager packageManager;

    private AlertDialog addModuleDialog = null;
    private AlertDialog selectAppsDialog = null;
    private AlertDialog confirmDialog = null;

    private TextView tvFilePath;
    private EditText etPackage, etModuleName, etEntry;
    private CheckBox cbAutoDetect;
    private Uri selectedApkUri = null;
    private String selectedApkPath = null;
    private String selectedApkName = null;
    private boolean isFilePickerActive = false;

    private long lastToggleTime = 0;
    private static final long TOGGLE_DEBOUNCE = 500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scannerExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean scanInProgress = false;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    isFilePickerActive = false;
                    if (!isAdded() || getContext() == null) return;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri != null) handleSelectedApk(uri);
                    } else {
                        Toast.makeText(requireContext(), "No file selected", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_modules, container, false);

        packageManager = requireContext().getPackageManager();
        logger = Logger.getInstance(requireContext());
        moduleLoader = ModuleLoader.getInstance(requireContext());
        shizukuHelper = ShizukuHelper.getInstance(requireContext());

        initViews(view);
        setupRecyclerView();
        setupListeners();

        loadModules();
        startBackgroundScan();

        return view;
    }

    private void initViews(View view) {
        moduleRecyclerView = view.findViewById(R.id.moduleRecyclerView);
        progressIndicator = view.findViewById(R.id.progressIndicator);
        fabAddModule = view.findViewById(R.id.fabAddModule);
        searchView = view.findViewById(R.id.searchView);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);
    }

    private void setupRecyclerView() {
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
        fabAddModule.setOnClickListener(v -> {
            dismissAllDialogs();
            showAddModuleDialog();
        });

        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filterModules(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void dismissAllDialogs() {
        if (addModuleDialog != null && addModuleDialog.isShowing()) addModuleDialog.dismiss();
        if (selectAppsDialog != null && selectAppsDialog.isShowing()) selectAppsDialog.dismiss();
        if (confirmDialog != null && confirmDialog.isShowing()) confirmDialog.dismiss();
    }

    // ═════════════════════════════════════════════════════════════════
    // MODULE AUTO-DETECTION + PURGE
    // ═════════════════════════════════════════════════════════════════

    /**
     * Scan installed packages for Xposed modules in the background.
     * The scan also purges any module whose APK was removed externally
     * (e.g. via Settings → Apps), so uninstalling a module that way
     * cleans it out of the Modules tab on the next scan.
     */
    private void startBackgroundScan() {
        if (scanInProgress) return;
        scanInProgress = true;

        final android.content.Context appCtx = requireContext().getApplicationContext();
        scannerExecutor.execute(() -> {
            try {
                ModuleScanner.ScanResult result =
                    ModuleScanner.scanInstalledModules(appCtx);

                if (!isAdded()) return;
                mainHandler.post(() -> {
                    scanInProgress = false;
                    loadModules();

                    if (getView() == null) return;

                    int newCount = result.newlyRegistered;
                    int purged = result.purgedCount;

                    if (purged > 0 && newCount > 0) {
                        Snackbar.make(getView(),
                            purged + " removed, " + newCount + " added",
                            Snackbar.LENGTH_LONG).show();
                    } else if (purged > 0) {
                        Snackbar.make(getView(),
                            purged + " module"
                                + (purged != 1 ? "s" : "") + " removed",
                            Snackbar.LENGTH_LONG).show();
                    } else if (newCount > 0) {
                        Snackbar.make(getView(),
                            newCount + " new module"
                                + (newCount != 1 ? "s" : "") + " detected",
                            Snackbar.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable t) {
                scanInProgress = false;
                if (isAdded()) {
                    mainHandler.post(() -> logger.w("Module scan failed: " + t.getMessage()));
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════
    // LOAD / FILTER
    // ═════════════════════════════════════════════════════════════════

    private void loadModules() {
        showLoading(true);
        try {
            modules = moduleLoader.loadModules();
            moduleAdapter.updateData(modules);

            if (modules.isEmpty()) {
                tvEmptyState.setVisibility(View.VISIBLE);
                moduleRecyclerView.setVisibility(View.GONE);
            } else {
                tvEmptyState.setVisibility(View.GONE);
                moduleRecyclerView.setVisibility(View.VISIBLE);
            }
            logger.i("Loaded " + modules.size() + " modules");
        } catch (Exception e) {
            logger.e("Error loading modules: " + e.getMessage());
        }
        showLoading(false);
    }

    private void filterModules(String query) {
        if (query == null || query.isEmpty()) {
            moduleAdapter.updateData(modules);
            return;
        }
        List<ModuleInfo> filtered = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (ModuleInfo module : modules) {
            if (module.packageName.toLowerCase().contains(lowerQuery) ||
                (module.name != null && module.name.toLowerCase().contains(lowerQuery))) {
                filtered.add(module);
            }
        }
        moduleAdapter.updateData(filtered);
    }

    /**
     * Update the RecyclerView row for a module, matching by package name.
     */
    private void updateModuleRow(ModuleInfo updated) {
        if (updated == null || updated.packageName == null) return;

        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo existing = modules.get(i);
            if (existing != null
                    && updated.packageName.equals(existing.packageName)) {
                modules.set(i, updated);
                if (moduleAdapter != null) {
                    moduleAdapter.notifyItemChanged(i);
                }
                return;
            }
        }

        loadModules();
    }

    // ═════════════════════════════════════════════════════════════════
    // SERVICE SYNC
    // ═════════════════════════════════════════════════════════════════

    public void requestModuleRepush(String reason) {
        try {
            if (!ShizuPosedService.isServiceRunning()) {
                logger.d("Service not running — skipping repush (" + reason + ")");
                return;
            }
            Intent i = new Intent(requireContext(), ShizuPosedService.class);
            i.setAction(ShizuPosedService.ACTION_REPUSH_MODULES);
            requireContext().startForegroundService(i);
            logger.d("Requested module repush: " + reason);
        } catch (Throwable t) {
            logger.w("requestModuleRepush failed: " + t.getMessage());
        }
    }

    private void notifyProviderChanged(String packageName, boolean enabled) {
        try {
            if (packageName != null) {
                requireContext().getContentResolver().notifyChange(
                    com.shizuposed.manager.status.ModuleStatusProvider
                        .moduleUri(packageName),
                    null);
            }
            requireContext().getContentResolver().notifyChange(
                com.shizuposed.manager.status.ModuleStatusProvider.MODULES_URI,
                null);
            requireContext().getContentResolver().notifyChange(
                com.shizuposed.manager.status.ModuleStatusProvider.INFO_URI,
                null);
        } catch (Throwable t) {
            logger.d("notifyChange failed: " + t.getMessage());
        }
    }

    private void broadcastModuleStateChange(ModuleInfo module) {
        if (module == null || module.packageName == null) return;
        try {
            Intent i = new Intent(module.enabled
                ? "de.robv.android.xposed.action.MODULE_ENABLED"
                : "de.robv.android.xposed.action.MODULE_DISABLED");
            i.putExtra("module", module.packageName);
            i.setPackage(module.packageName);
            requireContext().sendBroadcast(i);
            logger.d("Broadcast module state: " + module.packageName
                + " -> " + module.enabled);
        } catch (Throwable t) {
            logger.w("broadcastModuleStateChange failed: " + t.getMessage());
        }
    }

    private void announceModuleStateChange(ModuleInfo module) {
        if (module == null) return;
        notifyProviderChanged(module.packageName, module.enabled);
        broadcastModuleStateChange(module);
    }

    // ═════════════════════════════════════════════════════════════════
    // TOGGLE
    // ═════════════════════════════════════════════════════════════════

    private void toggleModule(ModuleInfo module, boolean enable) {
        long now = System.currentTimeMillis();
        if (now - lastToggleTime < TOGGLE_DEBOUNCE) return;
        lastToggleTime = now;

        try {
            module.enabled = enable;
            moduleLoader.saveModule(module);

            String msg = enable ? "Module enabled" : "Module disabled";
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            logger.i(msg + ": " + module.packageName);

            updateModuleRow(module);

            requestModuleRepush("toggle " + module.packageName + " -> " + enable);
            announceModuleStateChange(module);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to toggle module: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            logger.e("Toggle error: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MODULE DETAIL
    // ═════════════════════════════════════════════════════════════════

    private void showModuleDetail(ModuleInfo module) {
        if (module == null || module.packageName == null) return;
        ModuleDetailSheet sheet = ModuleDetailSheet.newInstance(module.packageName);
        sheet.show(getParentFragmentManager(), "module_detail");
    }

    public void onModuleUpdated(ModuleInfo updated) {
        if (updated == null) return;

        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo existing = modules.get(i);
            if (existing != null && updated.packageName.equals(existing.packageName)) {
                modules.set(i, updated);
                moduleAdapter.notifyItemChanged(i);
                break;
            }
        }
        requestModuleRepush("updated " + updated.packageName);
        announceModuleStateChange(updated);
    }

    public void onModuleRemoved(String packageName) {
        if (packageName == null) return;
        for (int i = 0; i < modules.size(); i++) {
            ModuleInfo existing = modules.get(i);
            if (existing != null && packageName.equals(existing.packageName)) {
                modules.remove(i);
                moduleAdapter.notifyItemRemoved(i);
                break;
            }
        }

        if (modules.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            moduleRecyclerView.setVisibility(View.GONE);
        }
        requestModuleRepush("removed " + packageName);

        try {
            Intent i = new Intent("de.robv.android.xposed.action.MODULE_DISABLED");
            i.putExtra("module", packageName);
            i.setPackage(packageName);
            requireContext().sendBroadcast(i);
        } catch (Throwable ignored) {}
        notifyProviderChanged(packageName, false);
    }

    public void launchUnderShizuPosed(String packageName) {
        if (packageName == null) return;

        if (!shizukuHelper.isAvailable() || !shizukuHelper.isAuthorized()) {
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
            logger.i("Requested launch under ShizuPosed: " + packageName);
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                "Failed to launch: " + t.getMessage(),
                Toast.LENGTH_LONG).show();
            logger.e("launchUnderShizuPosed failed: " + t.getMessage());
        }
    }

    public void openScopeEditor(ModuleInfo module) {
        if (module == null) return;
        showSelectAppsDialog(module);
    }

    // ═════════════════════════════════════════════════════════════════
    // UNINSTALL
    // ═════════════════════════════════════════════════════════════════

    public void uninstallModule(ModuleInfo module) {
        confirmDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Uninstall Module")
            .setMessage("Remove " + (module.name != null ? module.name : module.packageName)
                + " from ShizuPosed?\n\nThe module APK itself is not touched.")
            .setPositiveButton("Remove", (dialog, which) -> {
                try {
                    boolean removed = moduleLoader.uninstallModule(module.packageName);
                    if (removed) {
                        onModuleRemoved(module.packageName);
                        Toast.makeText(requireContext(), "Removed from ShizuPosed", Toast.LENGTH_SHORT).show();
                        logger.i("Removed module: " + module.packageName);
                    } else {
                        Toast.makeText(requireContext(), "Failed to remove", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    logger.e("Uninstall error: " + e.getMessage());
                }
            })
            .setNegativeButton("Cancel", null)
            .create();

        confirmDialog.show();
    }

    // ═════════════════════════════════════════════════════════════════
    // SELECT APPS DIALOG
    // ═════════════════════════════════════════════════════════════════

    public void showSelectAppsDialog(ModuleInfo module) {
        if (selectAppsDialog != null && selectAppsDialog.isShowing()) {
            selectAppsDialog.dismiss();
        }

        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_select_apps, null);

        RecyclerView appRecyclerView = dialogView.findViewById(R.id.appRecyclerView);
        EditText etSearchApps = dialogView.findViewById(R.id.etSearchApps);
        Button btnSelectAll = dialogView.findViewById(R.id.btnSelectAll);
        Button btnClearAll = dialogView.findViewById(R.id.btnClearAll);
        Button btnSelectSystem = dialogView.findViewById(R.id.btnSelectSystem);
        Button btnApply = dialogView.findViewById(R.id.btnApply);
        TextView tvSelectedCount = dialogView.findViewById(R.id.tvSelectedCount);
        CheckBox cbHideSystem = dialogView.findViewById(R.id.cbHideSystem);

        List<ApplicationInfo> allApps = getInstalledApps();

        List<ApplicationInfo> userApps = new ArrayList<>();
        List<ApplicationInfo> systemApps = new ArrayList<>();
        for (ApplicationInfo app : allApps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) systemApps.add(app);
            else userApps.add(app);
        }

        final List<ApplicationInfo> source = new ArrayList<>(userApps);

        Set<String> currentSelection = new HashSet<>();
        if (module.hookedApps != null) currentSelection.addAll(module.hookedApps);

        AppSelectionAdapter adapter = new AppSelectionAdapter(requireContext());

        if (tvSelectedCount != null) {
            tvSelectedCount.setText(currentSelection.size() + " selected");
            adapter.setOnSelectionChangedListener(count ->
                tvSelectedCount.setText(count + " selected"));
        }

        adapter.setSelectedApps(currentSelection);

        appRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        appRecyclerView.setAdapter(adapter);

        final Runnable reapply = () -> {
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
                source.addAll(checked ? userApps : allApps);
                reapply.run();
            });
        } else {
            source.clear();
            source.addAll(allApps);
        }

        etSearchApps.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int i, int b, int c) { reapply.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        reapply.run();

        btnSelectAll.setOnClickListener(v -> adapter.selectAll());
        btnClearAll.setOnClickListener(v -> adapter.clearAll());
        btnSelectSystem.setOnClickListener(v -> {
            if (cbHideSystem != null && cbHideSystem.isChecked()) {
                cbHideSystem.setChecked(false);
            }
            adapter.selectSystemApps();
        });

        btnApply.setVisibility(View.GONE);

        selectAppsDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Select Apps for " + module.name)
            .setView(dialogView)
            .setPositiveButton("Apply", (d, w) -> {
                Set<String> selected = adapter.getSelectedApps();
                module.hookedApps = selected;
                moduleLoader.saveModule(module);

                updateModuleRow(module);

                int count = selected.size();
                String msg = count + " app" + (count != 1 ? "s" : "") + " selected";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                logger.i(msg + " for " + module.name);

                requestModuleRepush("selectApps " + module.packageName + " -> " + count);
                announceModuleStateChange(module);
            })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(dialog -> selectAppsDialog = null)
            .create();

        selectAppsDialog.setCanceledOnTouchOutside(false);
        selectAppsDialog.show();
    }

    /**
     * Enumerate every hookable app.
     */
    private List<ApplicationInfo> getInstalledApps() {
        try {
            try { moduleLoader.loadModules(); } catch (Throwable ignored) {}

            List<ApplicationInfo> all = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS
            );
            if (all == null) return new ArrayList<>();

            String self = requireContext().getPackageName();

            Set<String> modulePackages = new HashSet<>();
            try {
                for (ModuleInfo m : moduleLoader.getCachedModules()) {
                    if (m != null && m.packageName != null) {
                        modulePackages.add(m.packageName);
                    }
                }
            } catch (Throwable t) {
                logger.w("Could not read module list: " + t.getMessage());
            }

            List<ApplicationInfo> filtered = new ArrayList<>(all.size());
            int skippedSelf = 0;
            int skippedModules = 0;
            for (ApplicationInfo app : all) {
                if (app == null || app.packageName == null) continue;

                if (self.equals(app.packageName)) {
                    skippedSelf++;
                    continue;
                }
                if (modulePackages.contains(app.packageName)) {
                    skippedModules++;
                    continue;
                }
                filtered.add(app);
            }

            try {
                filtered.sort((a, b) -> {
                    String la = a.loadLabel(packageManager).toString();
                    String lb = b.loadLabel(packageManager).toString();
                    return la.compareToIgnoreCase(lb);
                });
            } catch (Throwable ignored) {}

            logger.d("Listed " + filtered.size() + " hookable apps (skipped "
                + skippedSelf + " self, " + skippedModules + " module packages)");

            return filtered;
        } catch (Exception e) {
            logger.e("Failed to get installed apps: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // ADD MODULE DIALOG
    // ═════════════════════════════════════════════════════════════════

    private void showAddModuleDialog() {
        if (addModuleDialog != null && addModuleDialog.isShowing()) {
            addModuleDialog.dismiss();
        }

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
        tvFilePath.setText("No file selected");

        cbAutoDetect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etEntry.setEnabled(!isChecked);
            if (isChecked) etEntry.setText("");
        });

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

        btnBrowse.setOnClickListener(v -> {
            if (isFilePickerActive) return;
            isFilePickerActive = true;
            openFilePicker();
        });

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
                Intent fallbackIntent = new Intent(Intent.ACTION_GET_CONTENT);
                fallbackIntent.setType("application/vnd.android.package-archive");
                fallbackIntent.addCategory(Intent.CATEGORY_OPENABLE);
                filePickerLauncher.launch(Intent.createChooser(fallbackIntent, "Select APK File"));
            } catch (Exception e2) {
                isFilePickerActive = false;
                Toast.makeText(requireContext(), "No file picker available", Toast.LENGTH_SHORT).show();
                logger.e("File picker error: " + e2.getMessage());
            }
        }
    }

    private void handleSelectedApk(Uri uri) {
        isFilePickerActive = false;
        if (!isAdded() || getContext() == null) return;

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
                if (filePath != null) {
                    selectedApkPath = filePath;
                } else {
                    selectedApkPath = copyApkToCache(uri, fileName);
                }

                if (selectedApkPath != null && etPackage != null
                        && etPackage.getText().toString().isEmpty()) {
                    String pkgName = extractPackageName(selectedApkPath);
                    if (pkgName != null && !pkgName.isEmpty()) {
                        etPackage.setText(pkgName);
                    }
                }

                Toast.makeText(requireContext(), "File selected: " + fileName, Toast.LENGTH_SHORT).show();
                logger.i("Selected APK: " + fileName);
            } else {
                if (tvFilePath != null) tvFilePath.setText("Unknown file");
            }
        } catch (Exception e) {
            logger.e("File selection error: " + e.getMessage());
            if (tvFilePath != null) tvFilePath.setText("Error: " + e.getMessage());
            Toast.makeText(requireContext(), "Failed to process file", Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        try {
            if (uri.getScheme().equals("content")) {
                try (android.database.Cursor cursor = requireContext().getContentResolver()
                        .query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex);
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
            if ("file".equals(uri.getScheme())) return uri.getPath();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String copyApkToCache(Uri uri, String fileName) {
        try {
            File cacheDir = requireContext().getCacheDir();
            File destFile = new File(cacheDir, "selected_module_" + System.currentTimeMillis() + ".apk");
            try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
                 FileOutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
            }
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            logger.e("Failed to copy APK: " + e.getMessage());
            return null;
        }
    }

    private String extractPackageName(String apkPath) {
        try {
            android.content.pm.PackageInfo pkgInfo = packageManager.getPackageArchiveInfo(apkPath, 0);
            if (pkgInfo != null) return pkgInfo.packageName;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void addModuleFromDialog() {
        if (etPackage == null || etModuleName == null) return;

        String packageName = etPackage.getText().toString().trim();
        String moduleName = etModuleName.getText().toString().trim();
        String entryPoint = etEntry != null ? etEntry.getText().toString().trim() : "";

        if (packageName.isEmpty()) {
            Toast.makeText(requireContext(), "Package name is required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (moduleName.isEmpty()) moduleName = generateModuleName(packageName);
        if (selectedApkPath == null) {
            Toast.makeText(requireContext(), "Please select an APK file", Toast.LENGTH_SHORT).show();
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

            if (selectedApkPath != null
                    && selectedApkPath.startsWith(requireContext().getCacheDir().getAbsolutePath())) {
                File cacheFile = new File(selectedApkPath);
                if (cacheFile.exists()) cacheFile.delete();
            }

            Toast.makeText(requireContext(), "Module added", Toast.LENGTH_SHORT).show();
            logger.i("Added module: " + module.packageName);

            if (addModuleDialog != null && addModuleDialog.isShowing()) {
                addModuleDialog.dismiss();
            }

            requestModuleRepush("add " + module.packageName);
            announceModuleStateChange(module);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to add module: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            logger.e("Add module error: " + e.getMessage());
        }
    }

    public void refresh() {
        loadModules();
    }

    private void showLoading(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadModules();
        startBackgroundScan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissAllDialogs();
        addModuleDialog = null;
        selectAppsDialog = null;
        confirmDialog = null;
        isFilePickerActive = false;
        try { scannerExecutor.shutdownNow(); } catch (Throwable ignored) {}
    }

    @Override
    public void onDetach() {
        super.onDetach();
        dismissAllDialogs();
        addModuleDialog = null;
        selectAppsDialog = null;
        confirmDialog = null;
    }
}