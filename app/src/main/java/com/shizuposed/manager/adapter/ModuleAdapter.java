package com.shizuposed.manager.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.shizuposed.manager.R;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.stealth.XStealthPrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * ModuleAdapter
 *
 * Row adapter for the Modules tab, LSPosed-style.
 *
 * Row interaction model:
 *   • Tapping the row body opens the scope editor (or the detail
 *     sheet for XStealth).
 *   • Tapping the enable switch toggles the module.
 *   • Tapping the icon opens the detail sheet.
 *
 * Opening a module's own UI (with the self-hook activation
 * installed) is done from the detail sheet's "Open module app"
 * button.
 *
 * API VERSION BADGE
 * -----------------
 * Each normal module row shows an "API N" pill when the module
 * declared an Xposed API version in its manifest or module.prop.
 * Modules declaring an API above ShizuPosed's ceiling (100) get
 * "(unsupported)" appended so the user can see at a glance why
 * activation fails.
 *
 * XStealth's row shows no API badge (it's built-in, not a
 * third-party Xposed module).
 */
public class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.ModuleViewHolder> {

    private List<ModuleInfo> modules;
    private final Context context;
    private OnModuleActionListener listener;
    private long lastToggleTime = 0;
    private static final long TOGGLE_DEBOUNCE = 500;

    public interface OnModuleActionListener {
        void onToggle(ModuleInfo module, boolean enable);
        void onDetail(ModuleInfo module);
        void onEditScope(ModuleInfo module);
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
        boolean isXStealth = XStealthModule.PACKAGE.equals(module.packageName);

        // ─── Icon ─────────────────────────────────────────────────
        if (isXStealth) {
            holder.ivIcon.setImageResource(R.drawable.ic_xstealth);
        } else {
            Drawable icon = IconResolver.resolve(context, module.packageName, module.apkPath);
            if (icon != null) holder.ivIcon.setImageDrawable(icon);
            else holder.ivIcon.setImageResource(R.drawable.ic_module);
        }

        holder.tvName.setText(module.name != null ? module.name : module.packageName);
        holder.tvPackage.setText(module.packageName);

        if (isXStealth) {
            bindXStealthRow(holder, module);
        } else {
            bindNormalRow(holder, module);
        }
    }

    /**
     * Normal module row:
     *   • API version badge (if declared).
     *   • Enable switch visible.
     *   • Scope summary visible.
     *   • Chevron visible (hint: tap to edit scope).
     */
    private void bindNormalRow(ModuleViewHolder holder, ModuleInfo module) {
        // Status dot
        holder.indicatorStatus.setBackgroundResource(
            module.enabled
                ? R.drawable.status_indicator_enabled
                : R.drawable.status_indicator_disabled
        );

        // ── API version badge ─────────────────────────────────
        bindApiBadge(holder, module);

        // Enable switch
        if (holder.swEnabled != null) {
            holder.swEnabled.setVisibility(View.VISIBLE);
            holder.swEnabled.setOnCheckedChangeListener(null);
            holder.swEnabled.setChecked(module.enabled);
            holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                long now = System.currentTimeMillis();
                if (now - lastToggleTime < TOGGLE_DEBOUNCE) {
                    holder.swEnabled.setOnCheckedChangeListener(null);
                    holder.swEnabled.setChecked(!isChecked);
                    holder.swEnabled.setOnCheckedChangeListener((bv, c) -> {});
                    return;
                }
                lastToggleTime = now;
                if (listener != null) listener.onToggle(module, isChecked);
            });
        }

        if (holder.tvEnabledLabel != null) {
            holder.tvEnabledLabel.setText(module.enabled ? "Enabled" : "Disabled");
        }

        // Scope summary
        if (holder.tvScopeSummary != null) {
            int count = module.hookedApps != null ? module.hookedApps.size() : 0;
            if (module.hookAllApps) {
                holder.tvScopeSummary.setText("All apps scoped");
            } else if (count == 0) {
                holder.tvScopeSummary.setText("No apps scoped · tap to select");
            } else {
                holder.tvScopeSummary.setText(count + " app"
                    + (count != 1 ? "s" : "") + " scoped · tap to edit");
            }
        }

        // Recommended hint
        if (holder.tvRecommended != null) {
            int rec = module.getRecommendedAppCount();
            if (rec > 0) {
                holder.tvRecommended.setText("· " + rec + " recommended");
                holder.tvRecommended.setVisibility(View.VISIBLE);
            } else {
                holder.tvRecommended.setVisibility(View.GONE);
            }
        }

        // Chevron visible
        if (holder.ivChevron != null) {
            holder.ivChevron.setVisibility(View.VISIBLE);
        }

        // Row tap → scope editor
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEditScope(module);
        });

        holder.itemView.setOnLongClickListener(null);

        // Icon tap → detail sheet
        if (holder.ivIcon != null) {
            holder.ivIcon.setOnClickListener(v -> {
                if (listener != null) listener.onDetail(module);
            });
        }
    }

    private void bindApiBadge(ModuleViewHolder holder, ModuleInfo module) {
        if (holder.layoutApiBadge == null) return;

        int primary = -1;
        if (module.apiVersion > 0) primary = module.apiVersion;
        else if (module.xposedMinVersion > 0) primary = module.xposedMinVersion;
        else if (module.targetApiVersion > 0) primary = module.targetApiVersion;

        if (primary <= 0) {
            holder.layoutApiBadge.setVisibility(View.GONE);
            return;
        }

        holder.layoutApiBadge.setVisibility(View.VISIBLE);

        if (holder.tvApiVersion != null) {
            holder.tvApiVersion.setText("API " + primary);
        }   // ← missing closing brace was here

        if (holder.tvTargetApi != null) {
            // Only show the target suffix when it's different from
            // the primary number — otherwise it's redundant noise.
            if (module.targetApiVersion > 0
                    && module.targetApiVersion != primary) {
                holder.tvTargetApi.setText("· target " + module.targetApiVersion);
                holder.tvTargetApi.setVisibility(View.VISIBLE);
            } else {
                holder.tvTargetApi.setVisibility(View.GONE);
            }
        }
    }

    /**
     * XStealth row:
     *   • No enable switch.
     *   • No API badge (built-in).
     *   • Scope summary hidden.
     *   • Row tap opens the detail sheet.
     */
    private void bindXStealthRow(ModuleViewHolder holder, ModuleInfo module) {
        boolean enabled = XStealthPrefs.isEnabled(context);
        holder.indicatorStatus.setBackgroundResource(
            enabled
                ? R.drawable.status_indicator_enabled
                : R.drawable.status_indicator_disabled
        );

        // Hide the API badge for XStealth.
        if (holder.layoutApiBadge != null) {
            holder.layoutApiBadge.setVisibility(View.GONE);
        }

        if (holder.swEnabled != null) {
            holder.swEnabled.setOnCheckedChangeListener(null);
            holder.swEnabled.setVisibility(View.GONE);
        }

        if (holder.tvEnabledLabel != null) {
            holder.tvEnabledLabel.setText(enabled ? "Enabled" : "Disabled");
        }

        if (holder.tvScopeSummary != null) {
            holder.tvScopeSummary.setText(
                "Built-in · applies to every app ShizuPosed launches");
        }

        if (holder.tvRecommended != null) {
            holder.tvRecommended.setVisibility(View.GONE);
        }

        if (holder.ivChevron != null) {
            holder.ivChevron.setVisibility(View.VISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDetail(module);
        });

        holder.itemView.setOnLongClickListener(null);

        if (holder.ivIcon != null) {
            holder.ivIcon.setOnClickListener(v -> {
                if (listener != null) listener.onDetail(module);
            });
        }
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
        TextView tvName, tvPackage, tvEnabledLabel, tvScopeSummary, tvRecommended;
        View layoutApiBadge;                 // ← new
        TextView tvApiVersion, tvTargetApi;  // ← new
        ImageView ivChevron;
        MaterialSwitch swEnabled;

        ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
            indicatorStatus = itemView.findViewById(R.id.indicatorStatus);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvEnabledLabel = itemView.findViewById(R.id.tvEnabledLabel);
            tvScopeSummary = itemView.findViewById(R.id.tvScopeSummary);
            tvRecommended = itemView.findViewById(R.id.tvRecommended);
            layoutApiBadge = itemView.findViewById(R.id.layoutApiBadge);
            tvApiVersion = itemView.findViewById(R.id.tvApiVersion);
            tvTargetApi = itemView.findViewById(R.id.tvTargetApi);
            ivChevron = itemView.findViewById(R.id.ivChevron);
            swEnabled = itemView.findViewById(R.id.swEnabled);
        }
    }
}