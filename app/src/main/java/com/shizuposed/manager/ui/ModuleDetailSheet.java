package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.adapter.IconResolver;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ModuleActivityLauncher;
import com.shizuposed.manager.utils.ModuleActivityResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * ModuleDetailSheet
 *
 * Bottom sheet showing a module's metadata, scope, and actions.
 *
 * HOST LOOKUP
 * -----------
 * The sheet is shown via show(getParentFragmentManager(), tag),
 * which attaches it to the ACTIVITY's FragmentManager — not to
 * the calling fragment's childFragmentManager. This means
 * getParentFragment() returns null even though the caller is
 * ModulesFragment.
 *
 * tryLaunchThroughShizuPosed() therefore uses
 * findHostModulesFragment() to locate the ModulesFragment by
 * walking the activity's fragment tree. Without this, the routed
 * launch path is skipped and self-hook activation never installs.
 *
 * The search recurses into child FragmentManagers so it also works
 * if ModulesFragment is nested inside a tab host or ViewPager2.
 */
public class ModuleDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_PACKAGE = "packageName";

    private ModuleInfo module;
    private Logger logger;
    private PackageManager pm;
    private ModuleLoader moduleLoader;

    private volatile boolean viewReady = false;
    private ModuleActivityResolver.Result resolvedActivity;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

        // Look up the module from the current cache first. Only fall
        // back to a disk reload if the cache doesn't have it — the
        // disk reload is expensive and forces a full JSON re-parse.
        if (moduleLoader != null) {
            module = moduleLoader.getModule(pkg);
            if (module == null) {
                try {
                    moduleLoader.loadModules();
                } catch (Throwable t) {
                    if (logger != null) {
                        logger.w("ModuleDetailSheet: loadModules failed: "
                            + t.getMessage());
                    }
                }
                module = moduleLoader.getModule(pkg);
            }
        } else {
            module = null;
        }

        if (module == null) {
            if (isAdded()) Toast.makeText(requireContext(),
                "Module not found", Toast.LENGTH_SHORT).show();
            dismissAllowingStateLoss();
            return;
        }

        bindHeader(view);
        bindActions(view);
        bindScope(view);
        bindRemove(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        resolvedActivity = null;
    }

    // ═════════════════════════════════════════════════════════════
    // HOST LOOKUP
    // ═════════════════════════════════════════════════════════════

    /**
     * Locate the ModulesFragment that owns this sheet.
     *
     * The sheet is added via show(getParentFragmentManager(), tag),
     * which puts it in the ACTIVITY's FragmentManager as a sibling
     * of ModulesFragment. So getParentFragment() returns null. We
     * have to search the activity's fragment tree instead.
     *
     * Order of preference:
     *   1. getParentFragment() — works if someone nested us via
     *      childFragmentManager.
     *   2. Any ModulesFragment in the activity's FragmentManager,
     *      recursing into child FragmentManagers up to depth 3.
     */
    @Nullable
    private ModulesFragment findHostModulesFragment() {
        // 1. Direct parent
        Fragment parent = getParentFragment();
        if (parent instanceof ModulesFragment) {
            if (logger != null) {
                logger.d("findHostModulesFragment: found via getParentFragment");
            }
            return (ModulesFragment) parent;
        }

        // 2. Scan the activity's fragment tree
        try {
            FragmentActivity activity = getActivity();
            if (activity == null) return null;

            FragmentManager fm = activity.getSupportFragmentManager();
            List<Fragment> all = fm.getFragments();
            ModulesFragment found = searchFragmentsForModules(all, 0);
            if (found != null) {
                if (logger != null) {
                    logger.d("findHostModulesFragment: found in "
                        + "activity FM (" + all.size() + " top-level fragments)");
                }
                return found;
            }

            if (logger != null) {
                logger.i("findHostModulesFragment: no ModulesFragment in "
                    + "activity FM or any child FMs");
            }
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("findHostModulesFragment failed: " + t.getMessage());
            }
        }

        return null;
    }

    /**
     * Recursively search a fragment list and its children for a
     * ModulesFragment. Depth-bounded to avoid pathological cases.
     */
    @Nullable
    private ModulesFragment searchFragmentsForModules(List<Fragment> fragments,
                                                     int depth) {
        if (fragments == null || depth > 3) return null;
        for (Fragment f : fragments) {
            if (f == null) continue;
            if (f instanceof ModulesFragment) return (ModulesFragment) f;

            // Recurse into child fragments
            try {
                FragmentManager childFm = f.getChildFragmentManager();
                ModulesFragment nested = searchFragmentsForModules(
                    childFm.getFragments(), depth + 1);
                if (nested != null) return nested;
            } catch (Throwable ignored) {}
        }
        return null;
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
    }

    // ═════════════════════════════════════════════════════════════
    // OPEN MODULE APP
    // ═════════════════════════════════════════════════════════════

    private void launchModuleActivity() {
        if (!isAdded()) return;
        if (resolvedActivity == null || resolvedActivity.component == null) {
            Toast.makeText(requireContext(),
                "No launchable activity for this module",
                Toast.LENGTH_SHORT).show();
            return;
        }

        // Path 1: launch through ShizuPosed (installs the self-hook).
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
            Toast.makeText(requireContext(),
                "Opened directly. If the module UI reports "
                + "\"Disabled\" or \"Not Activated,\" close it and "
                + "make sure Shizuku is running, then try again.",
                Toast.LENGTH_LONG).show();
            if (logger != null) {
                logger.i("Direct launch for " + module.packageName
                    + " — module UI opened without ShizuPosed in its "
                    + "process; self-hook activation checks will not fire");
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
     * Returns true if the launch was dispatched. When true, the
     * caller MUST NOT launch the UI by any other path.
     *
     * Uses findHostModulesFragment() rather than getParentFragment()
     * because the sheet is added to the activity's FragmentManager,
     * not nested inside ModulesFragment.
     */
    private boolean tryLaunchThroughShizuPosed() {
        try {
            if (module == null) {
                if (logger != null) {
                    logger.d("tryLaunchThroughShizuPosed: module is null");
                }
                return false;
            }

            // Repair cachedDexPath from disk if the in-memory record
            // is stale. Without this, a valid module can silently
            // fall through to the direct-launch path.
            boolean dexOk = module.cachedDexPath != null
                && !module.cachedDexPath.isEmpty()
                && new java.io.File(module.cachedDexPath).exists();

            if (!dexOk && moduleLoader != null) {
                dexOk = moduleLoader.ensureCachedDex(module);
            }

            if (!dexOk) {
                if (logger != null) {
                    logger.i("tryLaunchThroughShizuPosed: no dex available for "
                        + module.packageName
                        + " (apkPath=" + module.apkPath + ")");
                }
                return false;
            }

            // Locate the host fragment. This is the fix — the old code
            // used getParentFragment(), which returned null.
            ModulesFragment host = findHostModulesFragment();
            if (host == null) {
                if (logger != null) {
                    logger.i("tryLaunchThroughShizuPosed: no ModulesFragment "
                        + "host for " + module.packageName
                        + " — cannot route");
                }
                return false;
            }

            ShizukuHelper sh = ShizukuHelper.getInstance(requireContext());
            if (sh == null || !sh.isAvailable() || !sh.isAuthorized()) {
                if (logger != null) {
                    logger.i("tryLaunchThroughShizuPosed: Shizuku not "
                        + "available for " + module.packageName);
                }
                return false;
            }

            if (logger != null) {
                logger.i("Opening " + module.packageName
                    + " under ShizuPosed (self-hook activation)");
            }

            host.launchUnderShizuPosed(module.packageName);
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

    // ═════════════════════════════════════════════════════════════
    // SCOPE (read-only summary)
    // ═════════════════════════════════════════════════════════════

    private void bindScope(View v) {
        if (!viewReady || module == null) return;

        TextView count = v.findViewById(R.id.tvScopeCount);
        TextView list = v.findViewById(R.id.tvScopeList);

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
    }

    // ═════════════════════════════════════════════════════════════
    // REMOVE MODULE
    // ═════════════════════════════════════════════════════════════

    private void bindRemove(View v) {
        if (!viewReady || module == null) return;

        Button remove = v.findViewById(R.id.btnRemoveModule);
        if (remove == null) return;

        if (XStealthModule.PACKAGE.equals(module.packageName)) {
            remove.setVisibility(View.GONE);
            return;
        }

        remove.setOnClickListener(x -> confirmRemove());
    }

    private void confirmRemove() {
        if (!isAdded() || module == null) return;

        final String display = module.name != null ? module.name : module.packageName;

        new AlertDialog.Builder(requireContext())
            .setTitle("Remove module?")
            .setMessage("This will stop ShizuPosed from loading "
                + display
                + " and ask the system to uninstall the app.\n\n"
                + "The uninstall prompt will appear next.")
            .setPositiveButton("Remove", (d, w) -> doRemove())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void doRemove() {
        if (!isAdded() || module == null || moduleLoader == null) return;

        final String pkg = module.packageName;

        moduleLoader.uninstallModuleCompletely(
            requireActivity(),
            pkg,
            (code, message) -> mainHandler.post(() -> {
                if (!isAdded()) return;

                ModulesFragment host = findHostModulesFragment();
                if (host != null) {
                    host.onModuleRemoved(pkg);
                }

                if (message != null) {
                    Toast.makeText(requireContext(), message,
                        Toast.LENGTH_LONG).show();
                }

                if (logger != null) {
                    logger.i("Remove module " + pkg
                        + " result code=" + code + " msg=" + message);
                }

                if (code == ModuleLoader.UNINSTALL_OK
                        || code == ModuleLoader.UNINSTALL_DEREGISTERED_ONLY) {
                    dismissAllowingStateLoss();
                }
            }));
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