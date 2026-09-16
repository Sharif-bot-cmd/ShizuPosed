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

    // Set in onViewCreated, cleared in onDestroyView.
    private volatile boolean viewReady = false;

    // Resolved once in bindActions. Null if the module has no launchable UI.
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
        Button launchScopedApp = v.findViewById(R.id.btnLaunchScopedApp);
        Button uninstall = v.findViewById(R.id.btnUninstallModule);

        if (openApp != null) {
            // Resolve a launchable activity. This handles modules with
            // no LAUNCHER entry and modules whose config activity is
            // not exported. See ModuleActivityResolver.
            resolvedActivity = ModuleActivityResolver.resolve(
                requireContext(), module.packageName);

            if (resolvedActivity != null && resolvedActivity.component != null) {
                openApp.setEnabled(true);
                openApp.setText("Open module app");

                // Small hint in the button label if we had to fall
                // back to a non-launcher path. Harmless on normal
                // modules, informative on the ones that need it.
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

        if (launchScopedApp != null) {
            boolean hasScopedApps = module.hookedApps != null && !module.hookedApps.isEmpty();
            launchScopedApp.setEnabled(hasScopedApps);
            launchScopedApp.setOnClickListener(x -> chooseScopedAppAndLaunch());
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
                                Intent i = new Intent(requireContext(), ShizuPosedService.class);
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

    private void chooseScopedAppAndLaunch() {
        if (!isAdded() || module == null || module.hookedApps == null
                || module.hookedApps.isEmpty()) {
            Toast.makeText(requireContext(), "No apps are in this module's scope",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> apps = new ArrayList<>(module.hookedApps);
        if (apps.size() == 1) {
            launchScopedApp(apps.get(0));
            return;
        }

        String[] choices = apps.toArray(new String[0]);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Choose an app to launch")
                .setItems(choices, (dialog, which) -> launchScopedApp(choices[which]))
                .show();
    }

    private void launchScopedApp(String packageName) {
        if (!isAdded() || packageName == null) return;
        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(requireContext());
            if (!helper.isAvailable() || !helper.isAuthorized()) {
                Toast.makeText(requireContext(), "Shizuku is not authorized",
                        Toast.LENGTH_LONG).show();
                return;
            }

            Intent service = new Intent(requireContext(), ShizuPosedService.class);
            service.setAction(ShizuPosedService.ACTION_LAUNCH_APP);
            service.putExtra(ShizuPosedService.EXTRA_LAUNCH_PACKAGE, packageName);
            requireContext().startForegroundService(service);
            Toast.makeText(requireContext(),
                    "Launching " + packageName + " under ShizuPosed",
                    Toast.LENGTH_SHORT).show();
            if (logger != null) logger.i("Requested scoped launch: " + packageName);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "Launch failed: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
            if (logger != null) logger.e("Scoped launch failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // OPEN MODULE APP
    // ═════════════════════════════════════════════════════════════

    /**
     * Launch the module's own configuration UI.
     *
     * Two paths:
     *   1. Exported activity — start it directly via Context.startActivity.
     *      Fast, no Shizuku round-trip.
     *   2. Non-exported activity — the manager cannot start it directly,
     *      so we go through Shizuku with `am start`. Shell UID has
     *      permission to start components of other apps.
     */
    private void launchModuleActivity() {
        if (!isAdded()) return;
        if (resolvedActivity == null || resolvedActivity.component == null) {
            Toast.makeText(requireContext(),
                "No launchable activity for this module",
                Toast.LENGTH_SHORT).show();
            return;
        }

        // Path 1: exported activity, launch directly.
        if (resolvedActivity.isExported) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(resolvedActivity.component);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable t) {
                if (logger != null) {
                    logger.w("direct start failed for "
                        + module.packageName + ": " + t.getMessage());
                }
                // fall through to Shizuku
            }
        }

        // Path 2: non-exported activity (or direct start failed).
        // Route through Shizuku so shell UID can start it.
        boolean ok = ModuleActivityLauncher.launch(requireContext(),
            module.packageName);

        if (!ok) {
            String reason = resolvedActivity.isExported
                ? "startActivity threw, and am start was unavailable"
                : "activity is not exported and Shizuku could not start it";
            Toast.makeText(requireContext(),
                "Failed to open module app: " + reason,
                Toast.LENGTH_LONG).show();
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