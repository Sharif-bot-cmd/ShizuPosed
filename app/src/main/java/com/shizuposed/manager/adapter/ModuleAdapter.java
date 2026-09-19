package com.shizuposed.manager.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.stealth.XStealthPrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * ModuleAdapter
 *
 * Row adapter for the Modules tab. Every module renders the same
 * base layout, but XStealth gets a reduced variant:
 *
 *   • Shield icon from ic_xstealth instead of a resolved launcher
 *     icon. XStealth has no APK to resolve against.
 *   • No enable toggle — its toggle lives in the detail sheet.
 *     The row switch is hidden.
 *   • No "Hooked Apps: N" line — XStealth has no scope.
 *   • No "Select Apps" button — same reason.
 *   • A status label replaces the toggle area, showing whether
 *     XStealth is enabled.
 *
 * RECOMMENDED SCOPE
 * -----------------
 * A normal module row shows a "· N recommended" hint next to the
 * hooked-apps count when the module declares a recommended scope
 * (assets/scope.list in its APK). The hint is informational: it
 * tells the user the module has an opinion about which apps it
 * should be scoped to. The actual selection happens in the scope
 * editor.
 *
 * RecyclerView reuses ViewHolders across rows, so the XStealth
 * branch must explicitly set every hidden widget back to VISIBLE
 * for the next non-XStealth row. Otherwise, scrolling causes the
 * icon, toggle, or count to disappear from unrelated modules.
 */
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
        boolean isXStealth = XStealthModule.PACKAGE.equals(module.packageName);

        // ─── Icon ─────────────────────────────────────────────────
        // XStealth has no launcher icon. Render the shield vector
        // directly. IconResolver has no APK to resolve against, so
        // binding the drawable is the only option.
        if (isXStealth) {
            holder.ivIcon.setVisibility(View.VISIBLE);
            holder.ivIcon.setImageResource(R.drawable.ic_xstealth);
        } else {
            holder.ivIcon.setVisibility(View.VISIBLE);
            Drawable icon = IconResolver.resolve(context, module.packageName, module.apkPath);
            if (icon != null) {
                holder.ivIcon.setImageDrawable(icon);
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_module);
            }
        }

        // ─── Common text fields ───────────────────────────────────
        holder.tvName.setText(module.name != null ? module.name : module.packageName);
        holder.tvPackage.setText(module.packageName);

        if (module.version != null) {
            holder.tvVersion.setText("v" + module.version);
            holder.tvVersion.setVisibility(View.VISIBLE);
        } else {
            holder.tvVersion.setVisibility(View.GONE);
        }

        holder.tvEntry.setText(module.xposedInit != null ? module.xposedInit : "Auto-detect");

        // Row tap → detail. Same for every module.
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDetail(module);
        });

        // Long-press → uninstall. Same for every module, though
        // XStealth refuses the removal in ModulesFragment.
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onUninstall(module);
            return true;
        });

        // ─── XStealth: reduced row ────────────────────────────────
        if (isXStealth) {
            bindXStealthRow(holder, module);
            return;
        }

        // ─── Normal module: full row ──────────────────────────────
        bindNormalRow(holder, module);
    }

    /**
     * XStealth row: no toggle, no hooked-apps count, no select-apps
     * button. A status label takes the toggle's place.
     */
    private void bindXStealthRow(ModuleViewHolder holder, ModuleInfo module) {
        // Status indicator dot follows the enabled state.
        boolean enabled = XStealthPrefs.isEnabled(context);
        holder.indicatorStatus.setBackgroundResource(
            enabled
                ? R.drawable.status_indicator_enabled
                : R.drawable.status_indicator_disabled
        );

        // Status label: Enabled / Disabled, matching the state in
        // the detail sheet.
        if (holder.tvHookedApps != null) {
            holder.tvHookedApps.setText(enabled ? "Enabled" : "Disabled");
            holder.tvHookedApps.setVisibility(View.VISIBLE);
        }

        // ── CHANGE: XStealth has no recommended scope. Explicitly
        // hide the hint so a recycled ViewHolder from a normal
        // module does not leak its text into this row.
        if (holder.tvRecommended != null) {
            holder.tvRecommended.setVisibility(View.GONE);
        }

        // Hide the toggle. RecyclerView may have reused a ViewHolder
        // that was showing it, so this must be explicit.
        if (holder.swEnabled != null) {
            holder.swEnabled.setOnCheckedChangeListener(null);
            holder.swEnabled.setVisibility(View.GONE);
        }

        // Hide the select-apps button.
        if (holder.btnSelectApps != null) {
            holder.btnSelectApps.setVisibility(View.GONE);
        }
    }

    /**
     * Normal module row: toggle, hooked-apps count, select-apps
     * button. Every hidden widget is restored to VISIBLE because
     * the ViewHolder may have been recycled from an XStealth row.
     */
    private void bindNormalRow(ModuleViewHolder holder, ModuleInfo module) {
        holder.indicatorStatus.setBackgroundResource(
            module.enabled
                ? R.drawable.status_indicator_enabled
                : R.drawable.status_indicator_disabled
        );

        // Toggle
        if (holder.swEnabled != null) {
            holder.swEnabled.setVisibility(View.VISIBLE);
            holder.swEnabled.setOnCheckedChangeListener(null);
            holder.swEnabled.setChecked(module.enabled);
            holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastToggleTime < TOGGLE_DEBOUNCE) return;
                lastToggleTime = currentTime;
                if (listener != null) listener.onToggle(module, isChecked);
            });
        }

        // Hooked-apps label
        if (holder.tvHookedApps != null) {
            holder.tvHookedApps.setVisibility(View.VISIBLE);
            int hookedCount = module.hookedApps != null ? module.hookedApps.size() : 0;
            holder.tvHookedApps.setText("Hooked Apps: " + hookedCount
                + (module.hookAllApps ? " (All)" : ""));
        }

        // ── CHANGE: recommended count hint.
        // Shows "· N recommended" only when the module declares a
        // scope.list. Hidden otherwise, so modules that predate the
        // feature render exactly as before.
        if (holder.tvRecommended != null) {
            int recommendedCount = module.getRecommendedAppCount();
            if (recommendedCount > 0) {
                holder.tvRecommended.setText("· " + recommendedCount + " recommended");
                holder.tvRecommended.setVisibility(View.VISIBLE);
            } else {
                holder.tvRecommended.setVisibility(View.GONE);
            }
        }

        // Select-apps button
        if (holder.btnSelectApps != null) {
            holder.btnSelectApps.setVisibility(View.VISIBLE);
            holder.btnSelectApps.setOnClickListener(v -> {
                if (listener != null) listener.onSelectApps(module);
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
        TextView tvName, tvPackage, tvVersion, tvEntry, tvHookedApps;
        // ── CHANGE: new recommended hint view.
        TextView tvRecommended;
        MaterialSwitch swEnabled;
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
            // ── CHANGE: bind the new view.
            tvRecommended = itemView.findViewById(R.id.tvRecommended);
            swEnabled = itemView.findViewById(R.id.swEnabled);
            btnSelectApps = itemView.findViewById(R.id.btnSelectApps);
        }
    }
}