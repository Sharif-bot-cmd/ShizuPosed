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
 *   • Tapping the row body opens the scope editor.
 *   • Tapping the enable switch toggles the module.
 *   • Tapping the icon opens the detail sheet.
 *   • Long-pressing the row opens the module's own UI through
 *     ShizuPosed. This is the path that installs the self-hook
 *     activation checks that UI modules rely on.
 *
 * XStealth's row still has no enable switch. Its toggle lives in
 * the detail sheet. Tapping the row opens the detail sheet for it,
 * not the scope editor.
 *
 * The self-hook pattern
 * ---------------------
 * Some modules check their own activation state by hooking a
 * method on their own UI (e.g. MainActivity.isXposedEnabled).
 * The presence of that hook is the signal. Under LSPosed this
 * works automatically because LSPosed injects into every process.
 * Under ShizuPosed the module's own process only gets hooks if
 * the module's UI is launched through ShizuPosed.
 *
 * Long-press dispatches through the caller, which routes the
 * launch through ShizuPosedService instead of the launcher. That
 * is the difference between a module UI showing "Enabled" and
 * showing "Disabled."
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
        void onUninstall(ModuleInfo module);
        /**
         * Row body tapped. For normal modules this should open the
         * scope editor. For XStealth it should open the detail
         * sheet.
         */
        void onEditScope(ModuleInfo module);
        /**
         * Row long-pressed. Opens the module's own UI through
         * ShizuPosed, so its own process gets hooks and any
         * self-hook activation check fires.
         */
        void onOpenModuleApp(ModuleInfo module);
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

        // Enable switch
        if (holder.swEnabled != null) {
            holder.swEnabled.setVisibility(View.VISIBLE);
            holder.swEnabled.setOnCheckedChangeListener(null);
            holder.swEnabled.setChecked(module.enabled);
            holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                long now = System.currentTimeMillis();
                if (now - lastToggleTime < TOGGLE_DEBOUNCE) {
                    // Revert the visual state — the toggle was debounced.
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

        // Row tap → scope editor (or XStealth detail via the caller)
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEditScope(module);
        });

        // Long-press → open the module's own UI through ShizuPosed.
        // This is the gesture that installs the self-hook activation
        // check for modules that use one.
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onOpenModuleApp(module);
            return true;
        });

        // Icon tap → detail sheet
        if (holder.ivIcon != null) {
            holder.ivIcon.setOnClickListener(v -> {
                if (listener != null) listener.onDetail(module);
            });
        }
    }

    /**
     * XStealth row:
     *   • No enable switch (its toggle lives in the detail sheet).
     *   • Scope summary hidden.
     *   • Chevron visible but its semantics are "open detail", not
     *     "edit scope".
     *   • Row tap opens the detail sheet.
     *   • Long-press is the same as a tap, since XStealth has no
     *     launchable UI.
     */
    private void bindXStealthRow(ModuleViewHolder holder, ModuleInfo module) {
        boolean enabled = XStealthPrefs.isEnabled(context);
        holder.indicatorStatus.setBackgroundResource(
            enabled
                ? R.drawable.status_indicator_enabled
                : R.drawable.status_indicator_disabled
        );

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

        // Row tap → detail sheet (for XStealth, scope isn't editable)
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDetail(module);
        });

        // Long-press → detail sheet too. XStealth has no launchable
        // UI, so there's nothing to open through ShizuPosed.
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onDetail(module);
            return true;
        });

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
            ivChevron = itemView.findViewById(R.id.ivChevron);
            swEnabled = itemView.findViewById(R.id.swEnabled);
        }
    }
}