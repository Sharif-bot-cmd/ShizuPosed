package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.adapter.IconResolver;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ModuleActivityLauncher;
import com.shizuposed.manager.utils.ModuleActivityResolver;

import java.util.ArrayList;
import java.util.List;

public class ModuleDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_PACKAGE = "packageName";

    private ModuleInfo module;
    private Logger logger;
    private PackageManager pm;
    private ModuleLoader moduleLoader;

    private volatile boolean viewReady = false;
    private ModuleActivityResolver.Result resolvedActivity;

    public static ModuleDetailSheet newInstance(String packageName) {
        ModuleDetailSheet s = new ModuleDetailSheet();
        Bundle b = new Bundle();
        b.putString(ARG_PACKAGE, packageName);
        s.setArguments(b);
        return s;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
        pm = context.getPackageManager();
        moduleLoader = ModuleLoader.getInstance(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_module_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;

        String pkg = getArguments() != null ? getArguments().getString(ARG_PACKAGE) : null;
        if (pkg == null) {
            if (logger != null) logger.w("ModuleDetailSheet: no package argument");
            dismissAllowingStateLoss();
            return;
        }

        module = moduleLoader != null ? moduleLoader.getModule(pkg) : null;

        if (module == null) {
            if (isAdded()) Toast.makeText(requireContext(),
                "Module not found", Toast.LENGTH_SHORT).show();
            dismissAllowingStateLoss();
            return;
        }

        bindHeader(view);
        bindActions(view);
        bindScope(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        resolvedActivity = null;
    }

    // ═════════════════════════════════════════════════════════════
    // HEADER
    // ═════════════════════════════════════════════════════════════

    private void bindHeader(View v) {
        if (!viewReady || module == null) return;
        if (!isAdded()) return;

        ImageView icon = v.findViewById(R.id.ivModuleIcon);
        TextView name = v.findViewById(R.id.tvModuleName);
        TextView pkgv = v.findViewById(R.id.tvModulePackage);
        TextView status = v.findViewById(R.id.tvModuleStatus);

        if (icon != null) {
            Drawable d = IconResolver.resolve(requireContext(),
                module.packageName, module.apkPath);
            if (d != null) icon.setImageDrawable(d);
            else icon.setImageResource(R.drawable.ic_module);
        }

        if (name != null) {
            name.setText(module.name != null ? module.name : module.packageName);
        }
        if (pkgv != null) {
            pkgv.setText(module.packageName
                + (module.version != null ? " • v" + module.version : ""));
        }
        if (status != null) {
            status.setText(module.enabled ? "✅ Enabled" : "❌ Disabled");
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIONS
    // ═════════════════════════════════════════════════════════════

    private void bindActions(View v) {
        if (!viewReady || module == null || pm == null) return;
        if (!isAdded()) return;

        Button openApp = v.findViewById(R.id.btnOpenModuleApp);
        Button forceStop = v.findViewById(R.id.btnForceStopScoped);
        Button uninstall = v.findViewById(R.id.btnUninstallModule);

        if (openApp != null) {
            resolvedActivity = ModuleActivityResolver.resolve(
                requireContext(), module.packageName);

            if (resolvedActivity != null && resolvedActivity.component != null) {
                openApp.setEnabled(true);
                openApp.setText("Open module app");

                String reason = resolvedActivity.reason;
                if ("main-no-launcher".equals(reason)) {
                    openApp.setText("Open module app (no launcher)");
                } else if ("first-exported".equals(reason)
                        || "first-activity".equals(reason)) {
                    openApp.setText("Open module app (fallback)");
                }

                openApp.setOnClickListener(x -> launchModuleActivity());
            } else {
                openApp.setEnabled(false);
                openApp.setText("Module has no UI");
            }
        }

        if (forceStop != null) {
            forceStop.setOnClickListener(x -> forceStopScopedApps());
        }

        if (uninstall != null) {
            uninstall.setOnClickListener(x -> {
                Fragment parent = getParentFragment();
                if (parent instanceof ModulesFragment) {
                    ((ModulesFragment) parent).uninstallModule(module);
                    dismissAllowingStateLoss();
                } else {
                    if (moduleLoader == null) return;
                    boolean removed = moduleLoader.uninstallModule(module.packageName);
                    if (removed) {
                        try {
                            if (isAdded()) {
                                Intent i = new Intent(requireContext(),
                                    ShizuPosedService.class);
                                i.setAction(ShizuPosedService.ACTION_REPUSH_MODULES);
                                requireContext().startForegroundService(i);
                            }
                        } catch (Throwable ignored) {}
                        if (isAdded()) Toast.makeText(requireContext(),
                            "Module uninstalled", Toast.LENGTH_SHORT).show();
                    } else {
                        if (isAdded()) Toast.makeText(requireContext(),
                            "Uninstall failed", Toast.LENGTH_SHORT).show();
                    }
                    dismissAllowingStateLoss();
                }
            });
        }
    }

    // ═════════════════════════════════════════════════════════════
    // OPEN MODULE APP
    // ═════════════════════════════════════════════════════════════

    /**
     * Launch the module's own configuration UI.
     *
     * Three paths, in order of preference:
     *
     *   1. Launch through ShizuPosed. This runs the module's UI
     *      inside app_process with hooks installed, which is what
     *      self-hook-based activation checks need to see. Modules
     *      hook one of their own UI methods and use the presence
     *      of that hook as the activation signal. Without this
     *      path, they always show "Disabled" even when their
     *      target hooks work.
     *
     *   2. Exported activity, launched directly. Fast, no Shizuku
     *      round-trip. Used as a fallback when path 1 isn't
     *      available. The module's UI will open, but its own
     *      process has no hooks — self-hook checks return the
     *      original value. The user is warned.
     *
     *   3. Non-exported activity, launched via Shizuku's `am start`.
     *      Same caveat as path 2.
     */
    private void launchModuleActivity() {
        if (!isAdded()) return;
        if (resolvedActivity == null || resolvedActivity.component == null) {
            Toast.makeText(requireContext(),
                "No launchable activity for this module",
                Toast.LENGTH_SHORT).show();
            return;
        }

        // Path 1: launch through ShizuPosed. This is the one that
        // installs the self-hook for modules that check their own
        // activation state.
        if (tryLaunchThroughShizuPosed()) {
            return;
        }

        // Path 2: exported activity, direct launch.
        boolean launched = false;
        if (resolvedActivity.isExported) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(resolvedActivity.component);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                launched = true;
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("direct start failed for "
                        + module.packageName + ": " + t.getMessage());
                }
            }
        }

        // Path 3: non-exported activity via Shizuku.
        if (!launched) {
            launched = ModuleActivityLauncher.launch(requireContext(),
                module.packageName);
        }

        if (launched) {
            // Warn the user. The UI opened, but without ShizuPosed
            // in its process, self-hook activation checks don't
            // fire. A module that checks MainActivity.isXposedEnabled
            // will show "Disabled" even though it's working.
            Toast.makeText(requireContext(),
                "Opened directly. If the module UI reports "
                + "\"Disabled\" or \"Not Activated,\" close it and "
                + "make sure Shizuku is running, then try again.",
                Toast.LENGTH_LONG).show();
            if (logger != null) {
                logger.i("Direct launch for " + module.packageName
                    + " — self-hook not installed");
            }
        } else {
            String reason = resolvedActivity.isExported
                ? "startActivity threw, and am start was unavailable"
                : "activity is not exported and Shizuku could not start it";
            Toast.makeText(requireContext(),
                "Failed to open module app: " + reason,
                Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Try to launch the module's own UI through ShizuPosed.
     *
     * Returns true if the launch was dispatched successfully. When
     * it returns true, the caller must not launch the UI by any
     * other path.
     */
    private boolean tryLaunchThroughShizuPosed() {
        try {
            Fragment parent = getParentFragment();
            if (!(parent instanceof ModulesFragment)) {
                if (logger != null) {
                    logger.d("tryLaunchThroughShizuPosed: no ModulesFragment parent");
                }
                return false;
            }

            if (module == null
                    || module.cachedDexPath == null
                    || module.cachedDexPath.isEmpty()) {
                if (logger != null) {
                    logger.d("tryLaunchThroughShizuPosed: no cached dex for "
                        + (module != null ? module.packageName : "null"));
                }
                return false;
            }

            ShizukuHelper sh = ShizukuHelper.getInstance(requireContext());
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                if (logger != null) {
                    logger.d("tryLaunchThroughShizuPosed: Shizuku not available");
                }
                return false;
            }

            if (logger != null) {
                logger.i("Opening " + module.packageName
                    + " under ShizuPosed (self-hook activation)");
            }

            ((ModulesFragment) parent).launchUnderShizuPosed(module.packageName);
            dismissAllowingStateLoss();
            return true;

        } catch (Throwable t) {
            if (logger != null) {
                logger.w("tryLaunchThroughShizuPosed failed: " + t.getMessage());
            }
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SCOPE
    // ═════════════════════════════════════════════════════════════

    private void bindScope(View v) {
        if (!viewReady || module == null) return;

        TextView count = v.findViewById(R.id.tvScopeCount);
        TextView list = v.findViewById(R.id.tvScopeList);
        Button edit = v.findViewById(R.id.btnEditScope);

        List<String> apps = module.hookedApps != null
            ? new ArrayList<>(module.hookedApps)
            : new ArrayList<>();

        if (count != null) {
            count.setText("Scope (" + apps.size() + " app"
                + (apps.size() != 1 ? "s" : "") + ")");
        }

        if (list != null) {
            if (apps.isEmpty()) {
                list.setText("No apps scoped");
            } else {
                StringBuilder sb = new StringBuilder();
                int shown = Math.min(apps.size(), 6);
                for (int i = 0; i < shown; i++) {
                    sb.append("• ").append(apps.get(i));
                    if (i < shown - 1) sb.append('\n');
                }
                if (apps.size() > shown) {
                    sb.append("\n• +").append(apps.size() - shown).append(" more");
                }
                list.setText(sb.toString());
            }
        }

        if (edit != null) {
            edit.setOnClickListener(x -> {
                Fragment parent = getParentFragment();
                if (parent instanceof ModulesFragment) {
                    dismissAllowingStateLoss();
                    ((ModulesFragment) parent).openScopeEditor(module);
                } else if (isAdded()) {
                    Toast.makeText(requireContext(),
                        "Edit scope from the Modules tab",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FORCE STOP
    // ═════════════════════════════════════════════════════════════

    private void forceStopScopedApps() {
        if (!isAdded()) return;
        if (module == null || module.hookedApps == null || module.hookedApps.isEmpty()) {
            Toast.makeText(requireContext(),
                "No apps scoped — nothing to stop", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ShizukuHelper h = ShizukuHelper.getInstance(requireContext());
            if (!h.isAvailable() || !h.isAuthorized()) {
                Toast.makeText(requireContext(),
                    "Shizuku not authorized", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder("am force-stop");
            for (String p : module.hookedApps) {
                if (p != null) sb.append(' ').append(p);
            }
            h.executeCommand(sb.toString());
            Toast.makeText(requireContext(),
                "Force-stopped " + module.hookedApps.size() + " app(s)",
                Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                "Force-stop failed: " + t.getMessage(),
                Toast.LENGTH_SHORT).show();
        }
    }
}