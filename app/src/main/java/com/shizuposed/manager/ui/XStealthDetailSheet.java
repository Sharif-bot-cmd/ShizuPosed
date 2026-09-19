package com.shizuposed.manager.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;
import com.shizuposed.manager.R;
import com.shizuposed.manager.stealth.XStealthPrefs;

/**
 * XStealthDetailSheet
 *
 * The UI for XStealth, shown from the Modules tab. This sheet is
 * the ONLY place the master toggle lives. Settings does not mirror
 * it.
 *
 * SHEET HEIGHT
 * ------------
 * The sheet has ten toggles plus descriptions, which exceeds the
 * default BottomSheet height on most devices. onStart expands the
 * sheet to the full available height so the NestedScrollView inside
 * the layout has room to scroll. Without this, the bottom toggles
 * are clipped and taps land on the scrim instead of the switch.
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

        // ─── Per-check toggles ────────────────────────────────────
        bindSwitch(view, R.id.switchHideDev,
            XStealthPrefs.isHideDevOptions(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideDevOptions(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

        bindSwitch(view, R.id.switchHideAdb,
            XStealthPrefs.isHideAdb(ctx), checked -> {
                if (!sheetReady) return;
                XStealthPrefs.setHideAdb(ctx, checked);
                XStealthPushHelper.requestPush(ctx);
            });

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
        sheetReady = true;
    }

    /**
     * Expand the sheet to fill the available height.
     *
     * The default BottomSheet height is capped at half the screen on
     * some ROMs, and the XStealth sheet has more content than fits
     * in that space. The NestedScrollView inside the layout scrolls
     * once the sheet is tall enough, but the sheet has to actually
     * be tall enough first. This override does that.
     *
     * The behavior is set to EXPANDED so the sheet opens at full
     * height rather than waiting for the user to drag it up. The
     * state is not disabled, so the user can still collapse the
     * sheet by dragging it down, which matches the standard
     * BottomSheet interaction model.
     */
    @Override
    public void onStart() {
        super.onStart();
        try {
            View view = getView();
            if (view == null) return;

            // Walk up to the sheet's own container. The immediate
            // parent is a FrameLayout, and above that is the
            // CoordinatorLayout that holds the BottomSheetBehavior.
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
        if (view != null) refresh(view);
    }

    /**
     * Update the status line and the enabled state of the sub-toggles.
     * Called after every toggle change, and on resume.
     */
    private void refresh(View view) {
        if (!isAdded()) return;
        Context ctx = requireContext();

        boolean enabled = XStealthPrefs.isEnabled(ctx);
        boolean next    = XStealthPrefs.isNextEnabled(ctx);
        boolean active  = XStealthStatusQuery.isActiveInAnyTarget(ctx);

        applyEnabledState(view, enabled);

        MaterialTextView statusText = view.findViewById(R.id.tvXStealthStatus);
        if (statusText == null) return;

        if (!enabled) {
            statusText.setText("Inactive — turn the master toggle on");
        } else if (active) {
            statusText.setText(next
                ? "Active (Next) — running in at least one target"
                : "Active — running in at least one target process");
        } else {
            statusText.setText(next
                ? "Enabled (Next) — will activate on the next launch"
                : "Enabled — will activate on the next launch under ShizuPosed");
        }
    }

    /**
     * Enable or disable every sub-toggle based on the master state.
     *
     * When the master is off, the sub-toggles are greyed out and
     * not interactive. Tapping a disabled switch does not fire its
     * listener, so no preference is written. The values stored in
     * the sub-toggles are not cleared.
     */
    private void applyEnabledState(View view, boolean masterOn) {
        int[] ids = {
            R.id.switchNext,
            R.id.switchHideDev,
            R.id.switchHideAdb,
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