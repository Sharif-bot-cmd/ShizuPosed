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

public class AppSelectionAdapter extends RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder> {
    private List<ApplicationInfo> apps;
    private Set<String> selectedApps;
    private Context context;
    private PackageManager packageManager;

    public AppSelectionAdapter(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.apps = new ArrayList<>();
        // ✅ Always use a new HashSet to avoid sharing references
        this.selectedApps = new HashSet<>();
    }

    public void setApps(List<ApplicationInfo> apps) {
        this.apps = apps != null ? apps : new ArrayList<>();
        notifyDataSetChanged();
    }

    /**
     * ✅ FIX: Copy the set instead of using the same reference
     */
    public void setSelectedApps(Set<String> selectedApps) {
        // ✅ Create a NEW HashSet with the contents
        this.selectedApps = new HashSet<>();
        if (selectedApps != null) {
            this.selectedApps.addAll(selectedApps);
        }
        notifyDataSetChanged();
    }

    public Set<String> getSelectedApps() {
        // ✅ Return a COPY of the set
        return new HashSet<>(selectedApps);
    }

    public void selectAll() {
        for (ApplicationInfo app : apps) {
            selectedApps.add(app.packageName);
        }
        notifyDataSetChanged();
    }

    public void clearAll() {
        selectedApps.clear();
        notifyDataSetChanged();
    }

    public void selectSystemApps() {
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                selectedApps.add(app.packageName);
            }
        }
        notifyDataSetChanged();
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
        
        holder.tvAppName.setText(app.loadLabel(packageManager));
        holder.tvPackageName.setText(app.packageName);
        
        Drawable icon = app.loadIcon(packageManager);
        if (icon != null) {
            holder.ivIcon.setImageDrawable(icon);
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_module);
        }
        
        // ✅ Check if this app is in the selected set
        boolean isChecked = selectedApps.contains(app.packageName);
        holder.cbSelected.setChecked(isChecked);
        
        // ✅ Remove any previous listener to avoid duplicate events
        holder.cbSelected.setOnCheckedChangeListener(null);
        
        holder.cbSelected.setOnCheckedChangeListener((buttonView, isChecked1) -> {
            if (isChecked1) {
                selectedApps.add(app.packageName);
            } else {
                selectedApps.remove(app.packageName);
            }
        });
        
        holder.itemView.setOnClickListener(v -> {
            boolean newState = !selectedApps.contains(app.packageName);
            if (newState) {
                selectedApps.add(app.packageName);
            } else {
                selectedApps.remove(app.packageName);
            }
            holder.cbSelected.setChecked(newState);
        });
    }

    @Override
    public int getItemCount() {
        return apps.size();
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