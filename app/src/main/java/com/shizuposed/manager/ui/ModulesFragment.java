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
import com.shizuposed.manager.R;
import com.shizuposed.manager.adapter.AppSelectionAdapter;
import com.shizuposed.manager.adapter.ModuleAdapter;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ModulesFragment extends Fragment {
    private RecyclerView moduleRecyclerView;
    private ProgressBar progressIndicator;
    private FloatingActionButton fabAddModule;
    private EditText searchView;
    private TextView tvEmptyState;
    
    private ModuleLoader moduleLoader;
    private Logger logger;
    private List<ModuleInfo> modules = new ArrayList<>();
    private ModuleAdapter moduleAdapter;
    private PackageManager packageManager;
    
    private AlertDialog addModuleDialog = null;
    private AlertDialog selectAppsDialog = null;
    private AlertDialog moduleDetailDialog = null;
    private AlertDialog confirmDialog = null;
    
    private TextView tvFilePath;
    private EditText etPackage, etModuleName, etEntry;
    private Uri selectedApkUri = null;
    private String selectedApkPath = null;
    private String selectedApkName = null;
    private boolean isFilePickerActive = false;
    
    // ✅ Handler for UI thread operations
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private final ActivityResultLauncher<Intent> filePickerLauncher = 
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    isFilePickerActive = false;
                    
                    if (!isAdded() || getContext() == null) {
                        return;
                    }
                    
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri != null) {
                            handleSelectedApk(uri);
                        }
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
        
        initViews(view);
        setupRecyclerView();
        setupListeners();
        loadModules();
        
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
            @Override
            public void onToggle(ModuleInfo module, boolean enable) {
                toggleModule(module, enable);
            }

            @Override
            public void onDetail(ModuleInfo module) {
                showModuleDetail(module);
            }

            @Override
            public void onUninstall(ModuleInfo module) {
                uninstallModule(module);
            }

            @Override
            public void onSelectApps(ModuleInfo module) {
                showSelectAppsDialog(module);
            }
        });
        
        moduleRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        moduleRecyclerView.setAdapter(moduleAdapter);
    }

    private void setupListeners() {
        fabAddModule.setOnClickListener(v -> {
            dismissAllDialogs();
            showAddModuleDialog();
        });
        
        searchView.setOnEditorActionListener((v, actionId, event) -> {
            filterModules(searchView.getText().toString());
            return true;
        });
    }

    private void dismissAllDialogs() {
        if (addModuleDialog != null && addModuleDialog.isShowing()) {
            addModuleDialog.dismiss();
        }
        if (selectAppsDialog != null && selectAppsDialog.isShowing()) {
            selectAppsDialog.dismiss();
        }
        if (moduleDetailDialog != null && moduleDetailDialog.isShowing()) {
            moduleDetailDialog.dismiss();
        }
        if (confirmDialog != null && confirmDialog.isShowing()) {
            confirmDialog.dismiss();
        }
    }

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
            Toast.makeText(requireContext(), "Failed to load modules", Toast.LENGTH_SHORT).show();
        }
        showLoading(false);
    }

    private void filterModules(String query) {
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
     * ✅ FIXED: Toggle without "RecyclerView is computing layout" error
     */
    private void toggleModule(ModuleInfo module, boolean enable) {
        try {
            module.enabled = enable;
            moduleLoader.saveModule(module);
            
            String msg = enable ? "Module enabled" : "Module disabled";
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            logger.i(msg + ": " + module.packageName);
            
            // ✅ Post to UI thread to avoid layout conflicts
            mainHandler.post(() -> {
                try {
                    int index = modules.indexOf(module);
                    if (index >= 0) {
                        // ✅ Use postDelayed to ensure layout is complete
                        mainHandler.postDelayed(() -> {
                            try {
                                modules.set(index, module);
                                moduleAdapter.notifyItemChanged(index);
                            } catch (Exception e) {
                                logger.e("Error updating item: " + e.getMessage());
                            }
                        }, 50);
                    } else {
                        // If not found, do a full reload after delay
                        mainHandler.postDelayed(this::loadModules, 100);
                    }
                } catch (Exception e) {
                    logger.e("Toggle update error: " + e.getMessage());
                }
            });
            
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to toggle module: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            logger.e("Toggle error: " + e.getMessage());
        }
    }

    private void showModuleDetail(ModuleInfo module) {
        View dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_module_detail, null);
        
        TextView tvName = dialogView.findViewById(R.id.tvModuleName);
        TextView tvPackage = dialogView.findViewById(R.id.tvModulePackage);
        TextView tvVersion = dialogView.findViewById(R.id.tvModuleVersion);
        TextView tvEntry = dialogView.findViewById(R.id.tvModuleEntry);
        TextView tvStatus = dialogView.findViewById(R.id.tvModuleStatus);
        TextView tvDescription = dialogView.findViewById(R.id.tvModuleDescription);
        TextView tvHookedApps = dialogView.findViewById(R.id.tvHookedApps);
        
        tvName.setText(module.name != null ? module.name : module.packageName);
        tvPackage.setText("Package: " + module.packageName);
        tvVersion.setText("Version: " + (module.version != null ? module.version : "Unknown"));
        tvEntry.setText("Entry: " + (module.xposedInit != null ? module.xposedInit : "Auto-detect"));
        tvStatus.setText("Status: " + (module.enabled ? "✅ Enabled" : "❌ Disabled"));
        tvDescription.setText(module.description != null ? module.description : "No description available");
        
        int hookedCount = module.hookedApps != null ? module.hookedApps.size() : 0;
        tvHookedApps.setText("Hooked Apps: " + hookedCount + 
            (module.hookAllApps ? " (All Apps)" : ""));
        
        moduleDetailDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Module Details")
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .create();
        
        moduleDetailDialog.show();
    }

    private void uninstallModule(ModuleInfo module) {
        confirmDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Uninstall Module")
            .setMessage("Are you sure you want to uninstall " + module.name + "?")
            .setPositiveButton("Uninstall", (dialog, which) -> {
                try {
                    boolean removed = moduleLoader.uninstallModule(module.packageName);
                    
                    if (removed) {
                        int index = modules.indexOf(module);
                        if (index >= 0) {
                            modules.remove(index);
                            moduleAdapter.notifyItemRemoved(index);
                        } else {
                            loadModules();
                        }
                        
                        if (modules.isEmpty()) {
                            tvEmptyState.setVisibility(View.VISIBLE);
                            moduleRecyclerView.setVisibility(View.GONE);
                        }
                        
                        Toast.makeText(requireContext(), "Module uninstalled", Toast.LENGTH_SHORT).show();
                        logger.i("Uninstalled module: " + module.packageName);
                    } else {
                        Toast.makeText(requireContext(), "Failed to uninstall module", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Failed to uninstall: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    logger.e("Uninstall error: " + e.getMessage());
                    loadModules();
                }
            })
            .setNegativeButton("Cancel", null)
            .create();
        
        confirmDialog.show();
    }

    // ============================================================
    // SELECT APPS DIALOG
    // ============================================================
    
    private void showSelectAppsDialog(ModuleInfo module) {
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
        
        List<ApplicationInfo> apps = getInstalledApps();
        
        Set<String> currentSelection = new HashSet<>();
        if (module.hookedApps != null) {
            currentSelection.addAll(module.hookedApps);
        }
        
        AppSelectionAdapter adapter = new AppSelectionAdapter(requireContext());
        adapter.setApps(apps);
        adapter.setSelectedApps(currentSelection);
        
        appRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        appRecyclerView.setAdapter(adapter);
        
        etSearchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase();
                List<ApplicationInfo> filtered = new ArrayList<>();
                for (ApplicationInfo app : apps) {
                    String label = app.loadLabel(packageManager).toString().toLowerCase();
                    if (label.contains(query) || app.packageName.toLowerCase().contains(query)) {
                        filtered.add(app);
                    }
                }
                adapter.setApps(filtered);
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
        
        btnSelectAll.setOnClickListener(v -> adapter.selectAll());
        btnClearAll.setOnClickListener(v -> adapter.clearAll());
        btnSelectSystem.setOnClickListener(v -> adapter.selectSystemApps());
        
        btnApply.setOnClickListener(v -> {
            Set<String> selected = new HashSet<>(adapter.getSelectedApps());
            
            module.hookedApps = selected;
            moduleLoader.saveModule(module);
            
            int index = modules.indexOf(module);
            if (index >= 0) {
                modules.set(index, module);
                moduleAdapter.notifyItemChanged(index);
            }
            
            int count = selected.size();
            String msg = count + " app" + (count != 1 ? "s" : "") + " selected for " + module.name;
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            logger.i(msg);
            
            if (selectAppsDialog != null && selectAppsDialog.isShowing()) {
                selectAppsDialog.dismiss();
            }
        });
        
        selectAppsDialog = new AlertDialog.Builder(requireContext())
            .setTitle("Select Apps for " + module.name)
            .setView(dialogView)
            .setPositiveButton("Apply", (d, w) -> {
                Set<String> selected = new HashSet<>(adapter.getSelectedApps());
                module.hookedApps = selected;
                moduleLoader.saveModule(module);
                int index = modules.indexOf(module);
                if (index >= 0) {
                    modules.set(index, module);
                    moduleAdapter.notifyItemChanged(index);
                }
                Toast.makeText(requireContext(), 
                    "Selected " + selected.size() + " apps", 
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(dialog -> {
                selectAppsDialog = null;
            })
            .create();
        
        selectAppsDialog.setCanceledOnTouchOutside(false);
        selectAppsDialog.show();
    }

    private List<ApplicationInfo> getInstalledApps() {
        try {
            return packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Exception e) {
            logger.e("Failed to get installed apps: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ============================================================
    // ADD MODULE DIALOG
    // ============================================================
    
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
        Button btnBrowse = view.findViewById(R.id.btnBrowseApk);
        tvFilePath = view.findViewById(R.id.tvFilePath);
        
        selectedApkUri = null;
        selectedApkPath = null;
        selectedApkName = null;
        tvFilePath.setText("No file selected");
        
        btnBrowse.setOnClickListener(v -> {
            if (isFilePickerActive) {
                return;
            }
            isFilePickerActive = true;
            openFilePicker();
        });
        
        builder.setTitle("Add Module")
            .setView(view)
            .setPositiveButton("Add", (dialog, which) -> {
                addModuleFromDialog();
            })
            .setNegativeButton("Cancel", null)
            .setOnDismissListener(dialog -> {
                addModuleDialog = null;
                isFilePickerActive = false;
            });
        
        addModuleDialog = builder.create();
        addModuleDialog.setCanceledOnTouchOutside(false);
        addModuleDialog.show();
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
        
        if (!isAdded() || getContext() == null) {
            return;
        }
        
        if (addModuleDialog == null || !addModuleDialog.isShowing()) {
            showAddModuleDialog();
            if (tvFilePath == null) {
                return;
            }
        }
        
        try {
            selectedApkUri = uri;
            
            String fileName = getFileNameFromUri(uri);
            if (fileName != null && !fileName.isEmpty()) {
                selectedApkName = fileName;
                if (tvFilePath != null) {
                    tvFilePath.setText("Selected: " + fileName);
                }
                
                String filePath = getFilePathFromUri(uri);
                if (filePath != null) {
                    selectedApkPath = filePath;
                } else {
                    selectedApkPath = copyApkToCache(uri, fileName);
                }
                
                if (selectedApkPath != null && etPackage != null && etPackage.getText().toString().isEmpty()) {
                    String pkgName = extractPackageName(selectedApkPath);
                    if (pkgName != null && !pkgName.isEmpty()) {
                        etPackage.setText(pkgName);
                    }
                }
                
                Toast.makeText(requireContext(), "File selected: " + fileName, Toast.LENGTH_SHORT).show();
                logger.i("Selected APK: " + fileName);
            } else {
                if (tvFilePath != null) {
                    tvFilePath.setText("Unknown file");
                }
            }
        } catch (Exception e) {
            logger.e("File selection error: " + e.getMessage());
            if (tvFilePath != null) {
                tvFilePath.setText("Error: " + e.getMessage());
            }
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
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex);
                        }
                    }
                }
            }
            if (fileName == null) {
                fileName = uri.getLastPathSegment();
            }
        } catch (Exception e) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }

    private String getFilePathFromUri(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                return uri.getPath();
            }
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
            if (pkgInfo != null) {
                return pkgInfo.packageName;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void addModuleFromDialog() {
        if (etPackage == null || etModuleName == null) {
            Toast.makeText(requireContext(), "Dialog not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String packageName = etPackage.getText().toString().trim();
        String moduleName = etModuleName.getText().toString().trim();
        String entryPoint = etEntry != null ? etEntry.getText().toString().trim() : "";
        
        if (packageName.isEmpty()) {
            Toast.makeText(requireContext(), "Package name is required", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (moduleName.isEmpty()) {
            Toast.makeText(requireContext(), "Module name is required", Toast.LENGTH_SHORT).show();
            return;
        }
        
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
            if (module.packageName == null || module.packageName.isEmpty()) {
                String extractedPkg = extractPackageName(selectedApkPath);
                if (extractedPkg != null) {
                    module.packageName = extractedPkg;
                }
            }
            
            moduleLoader.installModule(module);
            loadModules();
            
            if (selectedApkPath != null && selectedApkPath.startsWith(requireContext().getCacheDir().getAbsolutePath())) {
                File cacheFile = new File(selectedApkPath);
                if (cacheFile.exists()) {
                    cacheFile.delete();
                }
            }
            
            Toast.makeText(requireContext(), "Module added successfully", Toast.LENGTH_SHORT).show();
            logger.i("Added module: " + module.packageName);
            
            if (addModuleDialog != null && addModuleDialog.isShowing()) {
                addModuleDialog.dismiss();
            }
            
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to add module: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            logger.e("Add module error: " + e.getMessage());
        }
    }

    public void refresh() {
        loadModules();
    }

    private void showLoading(boolean show) {
        if (show) {
            progressIndicator.setVisibility(View.VISIBLE);
        } else {
            progressIndicator.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadModules();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissAllDialogs();
        addModuleDialog = null;
        selectAppsDialog = null;
        moduleDetailDialog = null;
        confirmDialog = null;
        isFilePickerActive = false;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        dismissAllDialogs();
        addModuleDialog = null;
        selectAppsDialog = null;
        moduleDetailDialog = null;
        confirmDialog = null;
    }
}
