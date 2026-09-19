package com.shizuposed.manager.adapter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
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
 * The comparator is built by recommendedFirst() on each call rather
 * than stored as a field. A field initializer cannot capture `this`,
 * so a lambda that reads `packageManager` is rejected by javac when
 * it appears in a field initializer — regardless of field order.
 * Building it in a method sidesteps the rule. The comparator is
 * cheap, so caching it would save nothing.
 *
 * The checkbox's onCheckedChangeListener is always detached before the
 * programmatic setChecked(...) call, and reattached afterwards. This
 * prevents a recycled ViewHolder from firing its stale callback against
 * the wrong package name.
 */
public class AppSelectionAdapter extends RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder> {

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
     */
    public void setApps(List<ApplicationInfo> apps) {
        List<ApplicationInfo> copy = (apps != null)
            ? new ArrayList<>(apps)
            : new ArrayList<>();
        try {
            copy.sort(recommendedFirst());
        } catch (Throwable ignored) {}
        this.apps = copy;
        notifyDataSetChanged();
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

    /**
     * Set the packages the module declares as its recommended scope.
     * Copies the incoming set. Re-sorts the current list so the
     * badge and the ordering stay in sync.
     */
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

    /**
     * Returns a comparator that sorts recommended apps to the top,
     * then alphabetically. Built on each call rather than stored as
     * a field, because a field initializer cannot capture `this`.
     */
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

    /** Is this package in the recommended set? */
    public boolean isRecommended(String packageName) {
        return packageName != null && recommendedApps.contains(packageName);
    }

    /** How many recommended apps are currently visible? */
    public int getRecommendedVisibleCount() {
        int n = 0;
        for (ApplicationInfo app : apps) {
            if (app != null && recommendedApps.contains(app.packageName)) n++;
        }
        return n;
    }

    /**
     * Select every recommended app that is currently in the
     * displayed list. Like the other quick actions, this operates
     * on the visible list — which, given recommended-first sorting,
     * is the set the user is looking at.
     */
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

        // Recommended badge visibility.
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