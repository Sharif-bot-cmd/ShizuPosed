package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;
import com.shizuposed.manager.R;
import com.shizuposed.manager.monitor.ForegroundMonitor;
import com.shizuposed.manager.stealth.SmartForegroundController;
import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * XStealthDetailSheet
 *
 * The UI for XStealth, shown from the Modules tab. This sheet is
 * the ONLY place the master toggle lives. Settings does not mirror
 * it.
 *
 * SHEET HEIGHT
 * ------------
 * The sheet has fifteen toggles plus descriptions, which exceeds
 * the default BottomSheet height on most devices. onStart expands
 * the sheet to the full available height so the NestedScrollView
 * inside the layout has room to scroll. Without this, the bottom
 * toggles are clipped and taps land on the scrim instead of the
 * switch.
 *
 * SUB-TOGGLE STATE
 * ----------------
 * Every sub-toggle is only meaningful when the master toggle is
 * on. When the master is off, the engine doesn't run and the
 * sub-toggles' values are ignored at load time. They are greyed
 * out and non-interactive while the master is off.
 *
 * The sub-toggle values themselves are NOT reset when the master
 * is turned off. Turning the master back on restores them to
 * whatever the user last set.
 *
 * SMART FOREGROUND (Design B)
 * ---------------------------
 * There is no dedicated master toggle for smart foreground
 * hiding. The two signal toggles — Hide Developer Options and
 * Hide ADB enabled — gate both the read interception and the
 * state change. The state change fires only for apps in the
 * smart-foreground scope, which the user edits through the
 * "Watch apps for foreground hiding" button that sits under
 * those two toggles.
 *
 * When the scope is non-empty and at least one signal toggle is
 * on, a warning appears under the scope summary. Adding apps to
 * the scope is the way the user opts into the state change.
 *
 * TOGGLE LAYERS
 * -------------
 *   Master          — enabled
 *   Next            — nextEnabled
 *   Bridge          — bridgeEnabled
 *   Settings        — hideDevOptions, hideAdb (+ smart-fg scope)
 *   System props    — hideSystemProperties, hideBuildFields
 *   Framework props — scrubShizuPosedProperties, scrubThreadNames
 *   Package/process — hideShizukuPackage, hideShizuPosedPackage,
 *                     hideRunningProcesses, hideProcFs
 *   API/dex         — apiProtection, dexOptimize
 */
public class XStealthDetailSheet extends BottomSheetDialogFragment {

    private static final String TAG = "XStealthSheet";

    private volatile boolean sheetReady = false;

    public static XStealthDetailSheet newInstance() {
        return new XStealthDetailSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_xstealth_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!isAdded()) return;
        Context ctx = requireContext();

        sheetReady = false;

        // ─── Master toggle ────────────────────────────────────────
        MaterialSwitch masterSwitch = view.findViewById(R.id.switchMaster);
        if (masterSwitch != null) {
            masterSwitch.setOnCheckedChangeListener(null);
            masterSwitch.setChecked(XStealthPrefs.isEnabled(ctx));
            masterSwitch.setOnCheckedChangeListener((v, checked) -> {
                if (!sheetReady) return;
                if (!isAdded()) return;
                Log.i(TAG, "master toggle: " + checked);
                XStealthPrefs.setEnabled(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
                refresh(view);
                updateSmartFgUi(view);
                Toast.makeText(ctx,
                    checked ? "XStealth enabled" : "XStealth disabled",
                    Toast.LENGTH_SHORT).show();
            });
        }

        // ─── Next toggle ──────────────────────────────────────────
        MaterialSwitch nextSwitch = view.findViewById(R.id.switchNext);
        if (nextSwitch != null) {
            nextSwitch.setOnCheckedChangeListener(null);
            nextSwitch.setChecked(XStealthPrefs.isNextEnabled(ctx));
            nextSwitch.setOnCheckedChangeListener((v, checked) -> {
                if (!sheetReady) return;
                if (!isAdded()) return;
                Log.i(TAG, "next toggle: " + checked);
                XStealthPrefs.setNextEnabled(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
                refresh(view);
                Toast.makeText(ctx,
                    checked ? "XStealth Next enabled" : "XStealth Next disabled",
                    Toast.LENGTH_SHORT).show();
                try {
                    ctx.getContentResolver().notifyChange(
                        com.shizuposed.manager.status.ModuleStatusProvider.MODULES_URI,
                        null);
                } catch (Throwable ignored) {}
            });
        }

        // ─── Bridge toggle ────────────────────────────────────────
        MaterialSwitch bridgeSwitch = view.findViewById(R.id.switchBridge);
        if (bridgeSwitch != null) {
            bridgeSwitch.setOnCheckedChangeListener(null);
            bridgeSwitch.setChecked(XStealthPrefs.isBridgeEnabled(ctx));
            bridgeSwitch.setOnCheckedChangeListener((v, checked) -> {
                if (!sheetReady) return;
                if (!isAdded()) return;
                Log.i(TAG, "bridge toggle: " + checked);
                XStealthPrefs.setBridgeEnabled(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
                refresh(view);
                Toast.makeText(ctx,
                    checked ? "Bridge enabled" : "Bridge disabled",
                    Toast.LENGTH_SHORT).show();
                try {
                    ctx.getContentResolver().notifyChange(
                        com.shizuposed.manager.status.ModuleStatusProvider.MODULES_URI,
                        null);
                } catch (Throwable ignored) {}
            });
        }

        // ─── Settings-based checks ────────────────────────────────
        bindSwitch(view, R.id.switchHideDev,
            XStealthPrefs.isHideDevOptions(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideDevOptions(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
                updateSmartFgUi(view);
                SmartForegroundController.getInstance(ctx).restartMonitor();
            });

        bindSwitch(view, R.id.switchHideAdb,
            XStealthPrefs.isHideAdb(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideAdb(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
                updateSmartFgUi(view);
                SmartForegroundController.getInstance(ctx).restartMonitor();
            });

        // ─── Smart foreground controls (Design B) ─────────────────
        //
        // These are not a separate feature section. They modify what
        // the two signal toggles above do: when a scoped app is in
        // the foreground, the settings the toggles cover are written
        // to 0 through Shizuku.
        //
        // Button, scope summary, warning, and grant button are all
        // wired below. The grant button is only visible when the
        // user hasn't yet granted PACKAGE_USAGE_STATS.

        MaterialButton sfScopeButton = view.findViewById(
            R.id.buttonSmartForegroundScope);
        if (sfScopeButton != null) {
            sfScopeButton.setOnClickListener(v -> {
                if (!sheetReady) return;
                openAppPickerForSmartForegroundScope();
            });
        }

        MaterialButton sfGrantButton = view.findViewById(
            R.id.buttonSmartForegroundGrant);
        if (sfGrantButton != null) {
            sfGrantButton.setOnClickListener(v -> openUsageAccessSettings());
        }

        // ─── System property / Build sanitization ─────────────────
        bindSwitch(view, R.id.switchHideSystemProperties,
            XStealthPrefs.isHideSystemProperties(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideSystemProperties(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchHideBuildFields,
            XStealthPrefs.isHideBuildFields(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideBuildFields(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        // ─── Framework property / thread scrubbing ────────────────
        bindSwitch(view, R.id.switchScrubProperties,
            XStealthPrefs.isScrubShizuPosedProperties(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setScrubShizuPosedProperties(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchScrubThreads,
            XStealthPrefs.isScrubThreadNames(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setScrubThreadNames(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        // ─── Package / process / /proc ────────────────────────────
        bindSwitch(view, R.id.switchHideShizuku,
            XStealthPrefs.isHideShizukuPackage(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideShizukuPackage(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchHideShizuPosed,
            XStealthPrefs.isHideShizuPosedPackage(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideShizuPosedPackage(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchHideProcesses,
            XStealthPrefs.isHideRunningProcesses(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideRunningProcesses(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchHideProcFs,
            XStealthPrefs.isHideProcFs(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideProcFs(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        // ─── API protection / dex optimization ────────────────────
        bindSwitch(view, R.id.switchApiProtection,
            XStealthPrefs.isApiProtectionEnabled(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setApiProtectionEnabled(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchDexOptimize,
            XStealthPrefs.isDexOptimizeEnabled(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setDexOptimizeEnabled(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        refresh(view);
        updateSmartFgUi(view);
        sheetReady = true;
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            View view = getView();
            if (view == null) return;

            View parent = (View) view.getParent();
            while (parent != null && !(parent instanceof CoordinatorLayout)) {
                parent = (View) parent.getParent();
            }
            if (parent == null) return;

            View sheet = parent.findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;

            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setLayoutParams(params);

            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
            behavior.setDraggable(true);

        } catch (Throwable t) {
            Log.w(TAG, "onStart: could not expand sheet", t);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view != null) {
            refresh(view);
            // Reload the smart-fg UI on resume so the grant button
            // updates after the user returns from the usage-access
            // settings screen.
            updateSmartFgUi(view);
        }
    }

    private void refresh(View view) {
        if (!isAdded()) return;
        Context ctx = requireContext();

        boolean enabled = XStealthPrefs.isEnabled(ctx);
        boolean next    = XStealthPrefs.isNextEnabled(ctx);
        boolean bridge  = XStealthPrefs.isBridgeEnabled(ctx);
        boolean active  = XStealthStatusQuery.isActiveInAnyTarget(ctx);

        applyEnabledState(view, enabled);

        MaterialTextView statusText = view.findViewById(R.id.tvXStealthStatus);
        if (statusText == null) return;

        StringBuilder layers = new StringBuilder();
        if (next)   layers.append("Next");
        if (bridge) {
            if (layers.length() > 0) layers.append(" + ");
            layers.append("Bridge");
        }
        String layersSuffix = (layers.length() > 0)
            ? " (" + layers + ")"
            : "";

        if (!enabled) {
            statusText.setText("Inactive — turn the master toggle on");
        } else if (active) {
            statusText.setText("Active" + layersSuffix
                + " — running in at least one target");
        } else {
            statusText.setText("Enabled" + layersSuffix
                + " — will activate on the next launch");
        }
    }

    /**
     * Update the smart-foreground controls to match the current
     * config.
     *
     * Design B: the state-change path is active when (a) at least
     * one of the signal toggles is on, and (b) the scope is
     * non-empty. The scope button is only meaningful when a signal
     * toggle is on — if both are off, there's nothing for the
     * foreground state change to write.
     *
     * The warning under the scope summary appears only when the
     * scope is non-empty and a signal toggle is on. The grant
     * button is visible only when PACKAGE_USAGE_STATS hasn't been
     * granted.
     */
    private void updateSmartFgUi(View view) {
        if (!isAdded()) return;
        Context ctx = requireContext();

        MaterialButton sfScopeButton = view.findViewById(
            R.id.buttonSmartForegroundScope);
        MaterialTextView sfScopeText = view.findViewById(
            R.id.tvSmartForegroundScope);
        MaterialTextView sfWarning = view.findViewById(
            R.id.tvSmartForegroundWarning);
        MaterialButton sfGrantButton = view.findViewById(
            R.id.buttonSmartForegroundGrant);

        boolean signalOn = XStealthPrefs.isHideDevOptions(ctx)
                        || XStealthPrefs.isHideAdb(ctx);
        boolean masterOn = XStealthPrefs.isEnabled(ctx);

        // Scope list comes from the shell-side config. It's not in
        // XStealthPrefs because it's not a single boolean — the
        // manager reads it from the same xstealth.json the shell
        // side reads, via a small loader.
        List<String> scope = loadSmartForegroundScope(ctx);
        int n = scope.size();

        if (sfScopeButton != null) {
            sfScopeButton.setEnabled(masterOn && signalOn);
            sfScopeButton.setAlpha(masterOn && signalOn ? 1.0f : 0.5f);
        }

        if (sfScopeText != null) {
            if (n == 0) {
                sfScopeText.setText("No apps selected");
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(n).append(n == 1 ? " app: " : " apps: ");
                int shown = 0;
                for (String p : scope) {
                    if (shown >= 2) {
                        sb.append(", +").append(n - 2).append(" more");
                        break;
                    }
                    if (shown > 0) sb.append(", ");
                    sb.append(p);
                    shown++;
                }
                sfScopeText.setText(sb.toString());
            }
        }

        if (sfWarning != null) {
            boolean show = n > 0 && signalOn && masterOn;
            sfWarning.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        if (sfGrantButton != null) {
            boolean hasUsage = hasUsageAccess(ctx);
            sfGrantButton.setVisibility(
                hasUsage ? View.GONE : View.VISIBLE);
            sfGrantButton.setEnabled(masterOn);
            sfGrantButton.setAlpha(masterOn ? 1.0f : 0.5f);
        }
    }

    /**
     * Read the smart-foreground scope from the shell-side config.
     *
     * The scope lives in xstealth.json, which the manager writes
     * before launching a target. XStealthConfig.load() reads that
     * file. It's the same loader the shell side uses.
     *
     * If the file isn't readable (for example, XStealth has never
     * been pushed), returns an empty list.
     */
    private List<String> loadSmartForegroundScope(Context ctx) {
        try {
            XStealthConfig config = XStealthConfig.load();
            if (config == null) return Collections.emptyList();
            return new ArrayList<>(config.smartForegroundScope);
        } catch (Throwable t) {
            Log.d(TAG, "loadSmartForegroundScope: " + t.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Check whether PACKAGE_USAGE_STATS has been granted.
     *
     * Uses a throwaway ForegroundMonitor with an empty scope — the
     * permission check doesn't need a scope, only the
     * UsageStatsManager.
     */
    private boolean hasUsageAccess(Context ctx) {
        try {
            ForegroundMonitor mon = new ForegroundMonitor(
                ctx, Collections.emptySet(), null);
            return mon.hasUsageStatsPermission();
        } catch (Throwable t) {
            Log.d(TAG, "hasUsageAccess: " + t.getMessage());
            return false;
        }
    }

    /**
     * Open the system Usage access screen.
     *
     * Most ROMs expose ACTION_USAGE_ACCESS_SETTINGS, which takes
     * the user straight to the per-app list. A few OEM ROMs omit
     * it; the fallback is the top-level Settings app.
     */
    private void openUsageAccessSettings() {
        if (!isAdded()) return;
        try {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable ignored) {
                Toast.makeText(requireContext(),
                    "Could not open Settings",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAppPickerForSmartForegroundScope() {
        if (!isAdded()) return;
        try {
            SmartForegroundScopeSheet sheet =
                SmartForegroundScopeSheet.newInstance();
            sheet.show(getParentFragmentManager(), "smart_fg_scope");
        } catch (Throwable t) {
            Log.w(TAG, "open picker failed: " + t.getMessage());
        }
    }

    private void applyEnabledState(View view, boolean masterOn) {
        int[] ids = {
            R.id.switchNext,
            R.id.switchBridge,
            R.id.switchHideDev,
            R.id.switchHideAdb,
            R.id.switchHideSystemProperties,
            R.id.switchHideBuildFields,
            R.id.switchScrubProperties,
            R.id.switchScrubThreads,
            R.id.switchHideShizuku,
            R.id.switchHideShizuPosed,
            R.id.switchHideProcesses,
            R.id.switchHideProcFs,
            R.id.switchApiProtection,
            R.id.switchDexOptimize,
            R.id.buttonSmartForegroundScope,
            R.id.buttonSmartForegroundGrant,
        };
        for (int id : ids) {
            View v = view.findViewById(id);
            if (v != null) {
                v.setEnabled(masterOn);
                v.setAlpha(masterOn ? 1.0f : 0.5f);
            }
        }
    }

    private void bindSwitch(View parent, int id, boolean initial,
                            SwitchCallback cb) {
        MaterialSwitch sw = parent.findViewById(id);
        if (sw == null) return;
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((v, checked) -> cb.onChanged(checked));
    }

    private interface SwitchCallback {
        void onChanged(boolean checked);
    }
}