package com.shizuposed.manager.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

    public interface OnModuleActionListener {
        void onToggle(ModuleInfo module, boolean enable);
        void onDetail(ModuleInfo module);
        void onUninstall(ModuleInfo module);
        void onSelectApps(ModuleInfo module);  // ← ADD THIS!
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
        
        holder.tvName.setText(module.name != null ? module.name : module.packageName);
        holder.tvPackage.setText(module.packageName);
        
        if (module.version != null) {
            holder.tvVersion.setText("v" + module.version);
            holder.tvVersion.setVisibility(View.VISIBLE);
        } else {
            holder.tvVersion.setVisibility(View.GONE);
        }
        
        holder.tvEntry.setText(module.xposedInit != null ? module.xposedInit : "Auto-detect");
        holder.swEnabled.setChecked(module.enabled);
        
        // Show hooked apps count
        int hookedCount = module.hookedApps != null ? module.hookedApps.size() : 0;
        holder.tvHookedApps.setText("Hooked Apps: " + hookedCount + 
            (module.hookAllApps ? " (All)" : ""));
        
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDetail(module);
        });
        
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onUninstall(module);
            return true;
        });
        
        holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (listener != null) listener.onToggle(module, isChecked);
        });
        
        // ← ADD THIS: btnSelectApps click listener
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
        TextView tvName, tvPackage, tvVersion, tvEntry, tvHookedApps;
        Switch swEnabled;
        Button btnSelectApps;  // ← ADD THIS!

        ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvVersion = itemView.findViewById(R.id.tvVersion);
            tvEntry = itemView.findViewById(R.id.tvEntry);
            tvHookedApps = itemView.findViewById(R.id.tvHookedApps);
            swEnabled = itemView.findViewById(R.id.swEnabled);
            btnSelectApps = itemView.findViewById(R.id.btnSelectApps);  // ← ADD THIS!
        }
    }
}
