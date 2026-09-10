package com.shizuposed.manager.adapter;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
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

    // Icon cache: packageName -> resolved Drawable.
    // Sized to comfortably hold a few dozen module icons.
    private final LruCache<String, Drawable> iconCache = new LruCache<>(64);

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

        // ─── Icon ────────────────────────────────────────────────────
        Drawable icon = resolveIcon(module.packageName, module.apkPath);
        if (icon != null) {
            holder.ivIcon.setImageDrawable(icon);
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_module);
        }

        // ─── Text ────────────────────────────────────────────────────
        holder.tvName.setText(module.name != null ? module.name : module.packageName);
        holder.tvPackage.setText(module.packageName);

        if (module.version != null) {
            holder.tvVersion.setText("v" + module.version);
            holder.tvVersion.setVisibility(View.VISIBLE);
        } else {
            holder.tvVersion.setVisibility(View.GONE);
        }

        holder.tvEntry.setText(module.xposedInit != null ? module.xposedInit : "Auto-detect");

        // ─── Toggle ──────────────────────────────────────────────────
        // Reset listener before setting checked state so we don't fire onToggle
        holder.swEnabled.setOnCheckedChangeListener(null);
        holder.swEnabled.setChecked(module.enabled);

        holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastToggleTime < TOGGLE_DEBOUNCE) return;
            lastToggleTime = currentTime;
            if (listener != null) listener.onToggle(module, isChecked);
        });

        // ─── Hooked apps count ──────────────────────────────────────
        int hookedCount = module.hookedApps != null ? module.hookedApps.size() : 0;
        holder.tvHookedApps.setText("Hooked Apps: " + hookedCount
            + (module.hookAllApps ? " (All)" : ""));

        // ─── Click handlers ─────────────────────────────────────────
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

    // ═════════════════════════════════════════════════════════════════
    // ICON RESOLUTION
    // ═════════════════════════════════════════════════════════════════

    /**
     * Resolve the module's icon.
     *
     * Strategy:
     *   1. If the package is installed on the device → use its launcher icon.
     *   2. Otherwise → read the icon out of the APK file at apkPath.
     *   3. Cache the result so we don't redo this on every bind.
     *
     * Returns null if neither source yields an icon; caller falls back
     * to R.drawable.ic_module.
     */
    private Drawable resolveIcon(String packageName, String apkPath) {
        if (packageName == null) return null;

        Drawable cached = iconCache.get(packageName);
        if (cached != null) return cached;

        PackageManager pm = context.getPackageManager();
        Drawable icon = null;

        // ── Path 1: installed package ────────────────────────────────
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            icon = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException ignored) {
        } catch (Throwable ignored) {
        }

        // ── Path 2: read from the APK file ───────────────────────────
        if (icon == null && apkPath != null) {
            try {
                PackageInfo pi = pm.getPackageArchiveInfo(apkPath, 0);
                if (pi != null && pi.applicationInfo != null) {
                    // Both sourceDir and publicSourceDir MUST be set before
                    // calling loadIcon(), otherwise it returns null on most
                    // Android versions.
                    pi.applicationInfo.sourceDir = apkPath;
                    pi.applicationInfo.publicSourceDir = apkPath;
                    icon = pi.applicationInfo.loadIcon(pm);
                }
            } catch (Throwable ignored) {
            }
        }

        if (icon != null) iconCache.put(packageName, icon);
        return icon;
    }

    // ═════════════════════════════════════════════════════════════════
    // VIEW HOLDER
    // ═════════════════════════════════════════════════════════════════

    static class ModuleViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName, tvPackage, tvVersion, tvEntry, tvHookedApps;
        Switch swEnabled;
        Button btnSelectApps;

        ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
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