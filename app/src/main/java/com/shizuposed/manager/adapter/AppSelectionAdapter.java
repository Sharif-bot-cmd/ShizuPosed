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
 * The checkbox's onCheckedChangeListener is always detached before the
 * programmatic setChecked(...) call, and reattached afterwards. This
 * prevents a recycled ViewHolder from firing its stale callback against
 * the wrong package name, which was the cause of the "checkbox state
 * disappears after search" symptom.
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

    public AppSelectionAdapter(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.apps = new ArrayList<>();
        this.selectedApps = new HashSet<>();
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        this.selectionChangedListener = l;
    }

    /** Replace the displayed list. Does NOT touch the selection. */
    public void setApps(List<ApplicationInfo> apps) {
        this.apps = apps != null ? apps : new ArrayList<>();
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

    public void selectAll() {
        for (ApplicationInfo app : apps) {
            selectedApps.add(app.packageName);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void clearAll() {
        // Only clear the visible ones? Or everything? Clear everything
        // currently displayed — the user expects "clear the list I see".
        for (ApplicationInfo app : apps) {
            selectedApps.remove(app.packageName);
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public void selectSystemApps() {
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
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

        // ── Critical ordering ─────────────────────────────────────
        // 1. Detach the old listener first.
        holder.cbSelected.setOnCheckedChangeListener(null);

        // 2. Set the checked state from the source of truth.
        holder.cbSelected.setChecked(selectedApps.contains(pkg));

        // 3. Attach a NEW listener that closes over THIS app's package.
        holder.cbSelected.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedApps.add(pkg);
            } else {
                selectedApps.remove(pkg);
            }
            notifySelectionChanged();
        });

        // 4. Row click toggles the checkbox (which fires the listener above).
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
        TextView tvAppName, tvPackageName;
        CheckBox cbSelected;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            cbSelected = itemView.findViewById(R.id.cbSelected);
        }
    }
}