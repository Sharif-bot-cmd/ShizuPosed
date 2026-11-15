package com.shizuposed.manager.adapter;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the "Select Apps" dialog.
 *
 * `selectedApps` is the single source of truth for the user's selection.
 * The displayed list (`apps`) can be replaced at any time by the search
 * filter, but the selection survives because it is keyed by package
 * name — not by position, and not by whether the app is currently
 * visible in the filtered list.
 *
 * RECOMMENDED APPS
 * ----------------
 * A module can declare a recommended scope via assets/scope.list in
 * its APK. ModuleLoader reads that list and stores it on
 * ModuleInfo.recommendedApps. The dialog hands it to this adapter,
 * which:
 *   • renders a "Recommended" badge on those rows,
 *   • sorts them to the top of the list,
 *   • exposes selectRecommended() for the Recommended chip.
 *
 * PROTECTED-UID FILTERING
 * -----------------------
 * setApps() filters out packages the framework cannot actually
 * reach: any package whose uid is below 10000 (SYSTEM_UID = 1000
 * and the reserved system UID pool 1001-1999), and the manager's
 * own package. See isSelectable().
 *
 * SYSTEM APPS WITH DEDICATED UIDs
 * -------------------------------
 * A preinstalled app that the OEM assigned a uid >= 10000 is a
 * normal userspace target from the shell's point of view. The
 * shell can setuid to it, so the framework can hook it. These are
 * listed. They render with the "System" chip (the FLAG_SYSTEM bit
 * is still set) but they are otherwise indistinguishable from a
 * user app.
 *
 * UI-LESS + IDLE PACKAGE FILTERING
 * --------------------------------
 * Any package (system or user, any uid) that exposes neither a
 * launcher activity nor a live process is filtered out — the
 * foreground monitor can never observe it.
 *
 * Signals used (no extra permission, no Shizuku):
 *   • "No UI"      — no activity matching ACTION_MAIN + CATEGORY_LAUNCHER.
 *   • "No process" — no process in ActivityManager.getRunningAppProcesses()
 *                    matching ai.processName or ai.packageName.
 *
 * Fails open: any error in the UI or process probe keeps the
 * package listed.
 */
public class AppSelectionAdapter extends RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder> {

    private static final String TAG = "AppSelectionAdapter";

    /**
     * The uid boundary below which the shell cannot assume the
     * target's identity. SYSTEM_UID is 1000; the reserved system
     * UID pool is 1001-1999. Ordinary app UIDs start at 10000.
     */
    private static final int FIRST_APP_UID = 10000;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount);
    }

    private List<ApplicationInfo> apps;
    private final Set<String> selectedApps;

    private final Context context;
    private final PackageManager packageManager;

    private OnSelectionChangedListener selectionChangedListener;

    /** Packages the module declares as its recommended scope. */
    private final Set<String> recommendedApps = new HashSet<>();

    public AppSelectionAdapter(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.apps = new ArrayList<>();
        this.selectedApps = new HashSet<>();
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        this.selectionChangedListener = l;
    }

    /**
     * Replace the displayed list. Does NOT touch the selection.
     * Recommended apps are sorted to the top.
     *
     * Packages the framework cannot reach (protected UIDs, the
     * manager itself) and packages with neither a launcher UI nor
     * a live process are filtered out before the list is stored.
     */
    public void setApps(List<ApplicationInfo> apps) {
        // Snapshot the running-process set once per call rather than
        // once per row. On a device with 200+ packages this is the
        // difference between one ActivityManager query and 200.
        Set<String> runningProcs = snapshotRunningProcessNames();

        List<ApplicationInfo> copy = new ArrayList<>();
        int droppedProtected = 0;
        int droppedNoUi = 0;
        if (apps != null) {
            for (ApplicationInfo app : apps) {
                if (app == null) continue;
                if (!isSelectable(app)) {
                    droppedProtected++;
                    continue;
                }
                if (isIdleUiLess(app, runningProcs)) {
                    droppedNoUi++;
                    Log.d(TAG, "dropped ui-less idle pkg: "
                        + app.packageName
                        + " (uid=" + app.uid
                        + ", processName=" + app.processName + ")");
                    continue;
                }
                copy.add(app);
            }
        }
        try {
            copy.sort(recommendedFirst());
        } catch (Throwable ignored) {}
        this.apps = copy;
        Log.d(TAG, "setApps: kept=" + copy.size()
            + " droppedProtected=" + droppedProtected
            + " droppedNoUi=" + droppedNoUi);
        notifyDataSetChanged();
    }

    /**
     * True if this app can be offered as a hook target.
     *
     * The scope editor is a list of apps a module may be applied
     * to. Not every installed package belongs there:
     *
     *   • Protected UIDs. ShizuPosed's only injection primitive is
     *     app_process as the target UID. The shell UID (2000) can
     *     setuid to ordinary app UIDs (>= 10000), but cannot setuid
     *     to SYSTEM_UID (1000) or the reserved system UID pool
     *     (1001-1999) — those are protected.
     *
     *   • The manager's own package.
     *
     * The UID test is authoritative. The flag test is a fallback
     * for the rare case where ApplicationInfo.uid is reported as 0.
     */
    private boolean isSelectable(ApplicationInfo app) {
        if (app == null || app.packageName == null) return false;

        // Never offer the manager itself.
        if (app.packageName.equals(context.getPackageName())) return false;

        int uid = app.uid;

        // Authoritative: the shell can only assume app UIDs.
        if (uid > 0) {
            return uid >= FIRST_APP_UID;
        }

        // Fallback when uid is not reported.
        boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        if (!system) return true;

        String pkg = app.packageName;
        return !(pkg.startsWith("android.")
                || pkg.startsWith("com.android.")
                || pkg.startsWith("com.google.android."));
    }

    /**
     * True if this package has neither a launcher activity nor a
     * live process. Applies to BOTH system and user packages,
     * regardless of uid. Such a package can never be observed by
     * the foreground monitor, so it is skipped.
     *
     * Fails open: any probe error returns false (keep the package).
     */
    private boolean isIdleUiLess(ApplicationInfo app,
                                 Set<String> runningProcs) {
        try {
            if (app.processName != null
                    && runningProcs != null
                    && runningProcs.contains(app.processName)) {
                return false;
            }
            if (runningProcs != null
                    && runningProcs.contains(app.packageName)) {
                return false;
            }
            return !hasLauncherActivity(app.packageName);
        } catch (Throwable t) {
            // Fail open: keep the package.
            return false;
        }
    }

    /**
     * True if the package exposes at least one activity a launcher
     * would show: ACTION_MAIN + CATEGORY_LAUNCHER.
     *
     * Fails open: on error, returns true so we never hide a
     * package that might actually be useful.
     */
    private boolean hasLauncherActivity(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            List<ResolveInfo> ris = packageManager.queryIntentActivities(
                intent, 0);
            if (ris == null || ris.isEmpty()) return false;
            for (ResolveInfo ri : ris) {
                if (ri == null) continue;
                ActivityInfo a = ri.activityInfo;
                if (a == null) continue;
                if (a.exported && a.name != null) return true;
            }
            return false;
        } catch (Throwable t) {
            // Fail open: assume it has UI so we never hide it.
            return true;
        }
    }

    /**
     * Snapshot the set of process names ActivityManager reports as
     * running.
     */
    private Set<String> snapshotRunningProcessNames() {
        Set<String> names = new HashSet<>();
        try {
            ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return names;
            List<ActivityManager.RunningAppProcessInfo> procs =
                am.getRunningAppProcesses();
            if (procs == null) return names;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p == null) continue;
                if (p.processName != null) names.add(p.processName);
                if (p.pkgList != null) {
                    for (String pkg : p.pkgList) {
                        if (pkg != null) names.add(pkg);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return names;
    }

    /** Seed the selection. Copies the incoming set. */
    public void setSelectedApps(Set<String> selectedApps) {
        this.selectedApps.clear();
        if (selectedApps != null) {
            this.selectedApps.addAll(selectedApps);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    /** Returns a COPY of the current selection. */
    public Set<String> getSelectedApps() {
        return new HashSet<>(selectedApps);
    }

    // ═════════════════════════════════════════════════════════════
    // RECOMMENDED
    // ═════════════════════════════════════════════════════════════

    public void setRecommendedApps(Set<String> recommended) {
        this.recommendedApps.clear();
        if (recommended != null) {
            this.recommendedApps.addAll(recommended);
        }
        try {
            this.apps.sort(recommendedFirst());
        } catch (Throwable ignored) {}
        notifyDataSetChanged();
    }

    private Comparator<ApplicationInfo> recommendedFirst() {
        return (a, b) -> {
            boolean ra = recommendedApps.contains(a.packageName);
            boolean rb = recommendedApps.contains(b.packageName);
            if (ra != rb) return ra ? -1 : 1;
            try {
                return a.loadLabel(packageManager).toString()
                    .compareToIgnoreCase(b.loadLabel(packageManager).toString());
            } catch (Throwable t) {
                return 0;
            }
        };
    }

    public boolean isRecommended(String packageName) {
        return packageName != null && recommendedApps.contains(packageName);
    }

    public int getRecommendedVisibleCount() {
        int n = 0;
        for (ApplicationInfo app : apps) {
            if (app != null && recommendedApps.contains(app.packageName)) n++;
        }
        return n;
    }

    public void selectRecommended() {
        for (ApplicationInfo app : apps) {
            if (app == null || app.packageName == null) continue;
            if (recommendedApps.contains(app.packageName)) {
                selectedApps.add(app.packageName);
            }
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    // ═════════════════════════════════════════════════════════════
    // QUICK ACTIONS
    // ═════════════════════════════════════════════════════════════

    public void selectAll() {
        for (ApplicationInfo app : apps) {
            if (app != null && app.packageName != null) {
                selectedApps.add(app.packageName);
            }
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void clearAll() {
        selectedApps.clear();
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void selectSystemApps() {
        for (ApplicationInfo app : apps) {
            if (app == null || app.packageName == null) continue;
            int flags = app.flags;
            boolean isSystem = (flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (isSystem) {
                selectedApps.add(app.packageName);
            }
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_app_selection, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        ApplicationInfo app = apps.get(position);
        final String pkg = app.packageName;

        holder.tvAppName.setText(app.loadLabel(packageManager));
        holder.tvPackageName.setText(pkg);

        try {
            Drawable icon = app.loadIcon(packageManager);
            if (icon != null) {
                holder.ivIcon.setImageDrawable(icon);
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_module);
            }
        } catch (Throwable t) {
            holder.ivIcon.setImageResource(R.drawable.ic_module);
        }

        if (holder.tvRecommendedBadge != null) {
            holder.tvRecommendedBadge.setVisibility(
                recommendedApps.contains(pkg) ? View.VISIBLE : View.GONE);
        }

        // ── Critical ordering ─────────────────────────────────────
        holder.cbSelected.setOnCheckedChangeListener(null);
        holder.cbSelected.setChecked(selectedApps.contains(pkg));
        holder.cbSelected.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedApps.add(pkg);
            } else {
                selectedApps.remove(pkg);
            }
            notifySelectionChanged();
        });

        holder.itemView.setOnClickListener(v -> holder.cbSelected.toggle());
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    private void notifySelectionChanged() {
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedApps.size());
        }
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvAppName, tvPackageName, tvRecommendedBadge;
        CheckBox cbSelected;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            tvRecommendedBadge = itemView.findViewById(R.id.tvRecommendedBadge);
            cbSelected = itemView.findViewById(R.id.cbSelected);
        }
    }
}