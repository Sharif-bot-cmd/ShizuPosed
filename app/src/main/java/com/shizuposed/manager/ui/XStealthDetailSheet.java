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
import com.shizuposed.manager.stealth.XStealthPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
 * inside the layout has room to scroll.
 *
 * SUB-TOGGLE STATE
 * ----------------
 * Every sub-toggle is only meaningful when the master toggle is
 * on. When the master is off, the engine doesn't run and the
 * sub-toggles' values are ignored at load time. They are greyed
 * out and non-interactive while the master is off.
 *
 * SMART FOREGROUND (Design B)
 * ---------------------------
 * There is no dedicated master toggle for smart foreground
 * hiding. The two signal toggles — Hide Developer Options and
 * Hide ADB enabled — gate both the read interception and the
 * state change. The state change fires only for apps in the
 * smart-foreground scope, which the user edits through the
 * "Watch apps for foreground hiding" button under those toggles.
 *
 * The manager reads the scope from XStealthPrefs (its own
 * SharedPreferences). It does NOT read XStealthConfig — that's
 * the shell-side loader and it can't see files the manager can't
 * reach.
 *
 * ONRESUME RESTART
 * ----------------
 * onResume calls SmartForegroundController.restartMonitor(). This
 * is deliberate and load-bearing:
 *
 *   • When the user returns from the system Usage access screen,
 *     the monitor that was created earlier (while the permission
 *     was still denied) has cached the "no permission" answer and
 *     is idle. A fresh restartMonitor() creates a new monitor that
 *     sees the newly-granted permission.
 *
 *   • When the user returns from the app picker sheet, the scope
 *     has changed. restartMonitor() picks up the new scope.
 *
 * The call is idempotent: if the monitor is already running with
 * the correct config, it stops it and starts a new one with the
 * same config. That's cheap.
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

        // ─── Smart foreground controls ────────────────────────────
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

        try {
            getParentFragmentManager().setFragmentResultListener(
                SmartForegroundScopeSheet.RESULT_KEY,
                getViewLifecycleOwner(),
                (requestKey, result) -> {
                    Log.w(TAG, "smart fg picker result: " + requestKey);
                    if (!isAdded()) return;
                    View v = getView();
                    if (v != null) {
                        updateSmartFgUi(v);
                    }
                    try {
                        SmartForegroundController.getInstance(requireContext())
                            .restartMonitor();
                    } catch (Throwable t) {
                        Log.w(TAG, "restartMonitor after picker failed: "
                            + t.getMessage());
                    }
                });
        } catch (Throwable t) {
            Log.w(TAG, "setFragmentResultListener failed: "
                + t.getMessage());
        }

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
        if (view == null || !isAdded()) return;

        refresh(view);
        updateSmartFgUi(view);

        // If the user granted Usage access while away, or the
        // scope changed via the picker, the monitor needs to be
        // re-created. A monitor that was built while the
        // permission was still denied has cached the "no
        // permission" answer and is idle. restartMonitor() stops
        // any existing monitor and starts a fresh one; the fresh
        // one re-checks the permission and sees the new answer.
        //
        // Idempotent: if the monitor is already running with the
        // right config, this stops and restarts it with the same
        // config. Cheap.
        try {
            SmartForegroundController.getInstance(requireContext())
                .restartMonitor();
        } catch (Throwable t) {
            Log.w(TAG, "onResume: restartMonitor failed: " + t.getMessage());
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
     * preferences.
     *
     * The scope button is only meaningful when a signal toggle is
     * on AND the master is on. The warning under the summary
     * appears only when the scope is non-empty and a signal toggle
     * is on. The hint below the summary appears only when the
     * scope is empty and a signal toggle is on.
     *
     * The grant button is visible only when PACKAGE_USAGE_STATS
     * hasn't been granted.
     */
    private void updateSmartFgUi(View view) {
        if (!isAdded()) return;
        Context ctx = requireContext();

        MaterialButton sfScopeButton = view.findViewById(
            R.id.buttonSmartForegroundScope);
        MaterialTextView sfScopeText = view.findViewById(
            R.id.tvSmartForegroundScope);
        MaterialTextView sfHint = view.findViewById(
            R.id.tvSmartForegroundHint);
        MaterialTextView sfWarning = view.findViewById(
            R.id.tvSmartForegroundWarning);
        MaterialButton sfGrantButton = view.findViewById(
            R.id.buttonSmartForegroundGrant);

        boolean masterOn = XStealthPrefs.isEnabled(ctx);
        boolean signalOn = XStealthPrefs.isHideDevOptions(ctx)
                        || XStealthPrefs.isHideAdb(ctx);

        Set<String> scopeSet = XStealthPrefs.getSmartForegroundScope(ctx);
        List<String> scope = new ArrayList<>(scopeSet);
        int n = scope.size();

        if (sfScopeButton != null) {
            sfScopeButton.setEnabled(masterOn && signalOn);
            sfScopeButton.setAlpha(masterOn && signalOn ? 1.0f : 0.5f);
            sfScopeButton.setText(
                n == 0
                    ? "Watch apps for foreground hiding"
                    : "Edit watched apps");
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

        if (sfHint != null) {
            boolean showHint = masterOn && signalOn && n == 0;
            sfHint.setVisibility(showHint ? View.VISIBLE : View.GONE);
        }

        if (sfWarning != null) {
            boolean showWarning = masterOn && signalOn && n > 0;
            sfWarning.setVisibility(
                showWarning ? View.VISIBLE : View.GONE);
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

    /**
     * Open the app picker for the smart-foreground scope.
     *
     * The picker is SmartForegroundScopeSheet, in the same package.
     * It writes the selection into XStealthPrefs and pushes the
     * config, so the shell side sees the same list. On return,
     * the parent sheet's onResume updates the summary and
     * restarts the foreground monitor.
     */
    private void openAppPickerForSmartForegroundScope() {
        if (!isAdded()) return;
        try {
            SmartForegroundScopeSheet sheet =
                SmartForegroundScopeSheet.newInstance();
            sheet.show(getParentFragmentManager(), "smart_fg_scope");
        } catch (Throwable t) {
            Log.w(TAG, "open picker failed: " + t.getMessage());
            Toast.makeText(requireContext(),
                "Could not open app picker",
                Toast.LENGTH_SHORT).show();
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
        };
        for (int id : ids) {
            View v = view.findViewById(id);
            if (v != null) {
                v.setEnabled(masterOn);
                v.setAlpha(masterOn ? 1.0f : 0.5f);
            }
        }
        // The smart-foreground button and grant button are handled
        // in updateSmartFgUi, which considers signal toggles as
        // well as the master.
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