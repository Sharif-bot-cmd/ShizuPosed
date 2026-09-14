package com.shizuposed.manager.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.model.ModuleInfo;

import java.util.ArrayList;
import java.util.List;

public class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.ModuleViewHolder> {
    private List<ModuleInfo> modules;
    private Context context;
    private OnModuleActionListener listener;
    private long lastToggleTime = 0;
    private static final long TOGGLE_DEBOUNCE = 500;

    public interface OnModuleActionListener {
        void onToggle(ModuleInfo module, boolean enable);
        void onDetail(ModuleInfo module);
        void onUninstall(ModuleInfo module);
        void onSelectApps(ModuleInfo module);
    }

    public ModuleAdapter(List<ModuleInfo> modules, Context context) {
        this.modules = modules != null ? modules : new ArrayList<>();
        this.context = context;
    }

    public void setOnModuleActionListener(OnModuleActionListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ModuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_module, parent, false);
        return new ModuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModuleViewHolder holder, int position) {
        ModuleInfo module = modules.get(position);

        // Real launcher icon — same resolution as the detail sheet uses.
        Drawable icon = IconResolver.resolve(context, module.packageName, module.apkPath);
        if (icon != null) {
            holder.ivIcon.setImageDrawable(icon);
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_module);
        }

        holder.indicatorStatus.setBackgroundResource(
            module.enabled
                ? R.drawable.status_indicator_enabled
                : R.drawable.status_indicator_disabled
        );

        holder.tvName.setText(module.name != null ? module.name : module.packageName);
        holder.tvPackage.setText(module.packageName);

        if (module.version != null) {
            holder.tvVersion.setText("v" + module.version);
            holder.tvVersion.setVisibility(View.VISIBLE);
        } else {
            holder.tvVersion.setVisibility(View.GONE);
        }

        holder.tvEntry.setText(module.xposedInit != null ? module.xposedInit : "Auto-detect");

        holder.swEnabled.setOnCheckedChangeListener(null);
        holder.swEnabled.setChecked(module.enabled);

        holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastToggleTime < TOGGLE_DEBOUNCE) return;
            lastToggleTime = currentTime;
            if (listener != null) listener.onToggle(module, isChecked);
        });

        int hookedCount = module.hookedApps != null ? module.hookedApps.size() : 0;
        holder.tvHookedApps.setText("Hooked Apps: " + hookedCount
            + (module.hookAllApps ? " (All)" : ""));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDetail(module);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onUninstall(module);
            return true;
        });

        holder.btnSelectApps.setOnClickListener(v -> {
            if (listener != null) listener.onSelectApps(module);
        });
    }

    @Override
    public int getItemCount() {
        return modules.size();
    }

    public void updateData(List<ModuleInfo> newModules) {
        this.modules = newModules != null ? newModules : new ArrayList<>();
        notifyDataSetChanged();
    }

    static class ModuleViewHolder extends RecyclerView.ViewHolder {
        View indicatorStatus;
        ImageView ivIcon;
        TextView tvName, tvPackage, tvVersion, tvEntry, tvHookedApps;
        Switch swEnabled;
        Button btnSelectApps;

        ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
            indicatorStatus = itemView.findViewById(R.id.indicatorStatus);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvVersion = itemView.findViewById(R.id.tvVersion);
            tvEntry = itemView.findViewById(R.id.tvEntry);
            tvHookedApps = itemView.findViewById(R.id.tvHookedApps);
            swEnabled = itemView.findViewById(R.id.swEnabled);
            btnSelectApps = itemView.findViewById(R.id.btnSelectApps);
        }
    }
}