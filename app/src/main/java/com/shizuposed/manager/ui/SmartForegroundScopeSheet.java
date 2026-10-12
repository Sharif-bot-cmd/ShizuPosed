package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.shizuposed.manager.R;
import com.shizuposed.manager.stealth.SmartForegroundController;
import com.shizuposed.manager.stealth.XStealthPrefs;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SmartForegroundScopeSheet
 *
 * The app picker for the smart-foreground scope. Lists every
 * installed app (system and user) with a checkbox, a search field,
 * and Select all / Clear buttons.
 *
 * Selected packages are written to XStealthPrefs when the user
 * taps "Save". On dismiss-without-save, nothing changes.
 *
 * NOTIFYING THE PARENT
 * --------------------
 * Both Save and Cancel set a fragment result before dismissing.
 * This is deliberate: BottomSheetDialogFragment does not reliably
 * resume the parent fragment when a child dialog dismisses — the
 * parent never paused, so onResume doesn't fire. The fragment
 * result is the reliable signal that lets the parent refresh its
 * scope summary and restart the foreground monitor.
 *
 * The result bundle is empty. The parent reads the current scope
 * from XStealthPrefs, which this sheet just wrote.
 *
 * The list is sorted by label using the locale collator, so
 * non-Latin app names sort correctly.
 *
 * Filtered out:
 *   • ShizuPosed's own package
 *
 * Not filtered:
 *   • System apps. A user might want to watch a system app that
 *     shows the same banking-style checks. The checkbox list
 *     marks them with a "System" chip.
 */
public class SmartForegroundScopeSheet extends BottomSheetDialogFragment {

    private static final String TAG = "SmartFgScope";

    /**
     * FragmentResult key. The parent sheet registers a
     * FragmentResultListener for this key. When the picker is
     * dismissed — either by Save or by Cancel — the listener
     * fires and the parent updates its smart-foreground UI and
     * restarts the monitor.
     */
    public static final String RESULT_KEY = "smart_fg_scope_result";

    /** The working set while the sheet is open. */
    private final Set<String> mSelected = new HashSet<>();

    /** The full list, filtered by the search field. */
    private final List<AppEntry> mAllApps = new ArrayList<>();
    private final List<AppEntry> mFiltered = new ArrayList<>();

    private AppAdapter mAdapter;

    public static SmartForegroundScopeSheet newInstance() {
        return new SmartForegroundScopeSheet();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_smart_fg_scope, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!isAdded()) return;
        Context ctx = requireContext();

        // Seed the working set from preferences.
        Set<String> initial = XStealthPrefs.getSmartForegroundScope(ctx);
        if (initial != null) mSelected.addAll(initial);

        // Load the app list.
        loadApps(ctx);
        rebuildFiltered("");

        // Search field.
        EditText search = view.findViewById(R.id.editSearch);
        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    rebuildFiltered(s == null ? "" : s.toString());
                    if (mAdapter != null) mAdapter.notifyDataSetChanged();
                    updateCount(view);
                }
            });
        }

        // RecyclerView.
        RecyclerView list = view.findViewById(R.id.recyclerApps);
        if (list != null) {
            list.setLayoutManager(new LinearLayoutManager(ctx));
            mAdapter = new AppAdapter();
            list.setAdapter(mAdapter);
        }

        // Select all / clear.
        MaterialButton selectAll = view.findViewById(R.id.buttonSelectAll);
        if (selectAll != null) {
            selectAll.setOnClickListener(v -> {
                for (AppEntry e : mFiltered) mSelected.add(e.packageName);
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                updateCount(view);
            });
        }

        MaterialButton clear = view.findViewById(R.id.buttonClear);
        if (clear != null) {
            clear.setOnClickListener(v -> {
                for (AppEntry e : mFiltered) mSelected.remove(e.packageName);
                if (mAdapter != null) mAdapter.notifyDataSetChanged();
                updateCount(view);
            });
        }

        // Cancel.
        MaterialButton cancel = view.findViewById(R.id.buttonCancel);
        if (cancel != null) {
            cancel.setOnClickListener(v -> {
                // Set the result so the parent refreshes. Nothing
                // was written, so the parent will read the same
                // scope it had before, and updateSmartFgUi will
                // render the same summary. Harmless and keeps the
                // code paths symmetric.
                setResultAndDismiss();
            });
        }

        // Save.
        MaterialButton save = view.findViewById(R.id.buttonSave);
        if (save != null) {
            save.setOnClickListener(v -> {
                try {
                    XStealthPrefs.setSmartForegroundScope(ctx, mSelected);
                    XStealthPushHelper.requestPush(ctx);
                    SmartForegroundController.getInstance(ctx).restartMonitor();
                    Toast.makeText(ctx,
                        mSelected.isEmpty()
                            ? "Smart foreground scope cleared"
                            : mSelected.size() + " app(s) will be watched",
                        Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.w(TAG, "save failed: " + t.getMessage());
                    Toast.makeText(ctx,
                        "Could not save scope",
                        Toast.LENGTH_SHORT).show();
                }

                // Notify the parent. The parent's onResume does
                // not fire when this dialog dismisses, because
                // the parent never paused. The fragment result is
                // the reliable notification path.
                setResultAndDismiss();
            });
        }

        updateCount(view);
    }

    /**
     * Set the fragment result on the parent's FragmentManager and
     * then dismiss.
     *
     * Called from both Save and Cancel. The result bundle is
     * empty; the parent reads the scope from XStealthPrefs.
     */
    private void setResultAndDismiss() {
        try {
            getParentFragmentManager().setFragmentResult(
                RESULT_KEY, new Bundle());
        } catch (Throwable t) {
            Log.w(TAG, "setFragmentResult failed: " + t.getMessage());
        }
        dismiss();
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
        } catch (Throwable t) {
            Log.w(TAG, "onStart: " + t.getMessage());
        }
    }

    private void loadApps(Context ctx) {
        mAllApps.clear();
        PackageManager pm = ctx.getPackageManager();

        List<ApplicationInfo> apps = pm.getInstalledApplications(
            PackageManager.GET_META_DATA);
        if (apps == null) return;

        String self = ctx.getPackageName();

        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            if (self.equals(ai.packageName)) continue;

            CharSequence labelCs = pm.getApplicationLabel(ai);
            String label = labelCs == null
                ? ai.packageName
                : labelCs.toString();

            AppEntry e = new AppEntry();
            e.packageName = ai.packageName;
            e.label = label;
            e.isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            try {
                e.icon = pm.getApplicationIcon(ai);
            } catch (Throwable ignored) {
                e.icon = null;
            }

            mAllApps.add(e);
        }

        // Sort by label using the locale collator.
        final Collator col = Collator.getInstance(Locale.getDefault());
        col.setStrength(Collator.PRIMARY);
        Collections.sort(mAllApps, (a, b) -> col.compare(a.label, b.label));
    }

    private void rebuildFiltered(String query) {
        mFiltered.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        if (q.isEmpty()) {
            mFiltered.addAll(mAllApps);
            return;
        }

        for (AppEntry e : mAllApps) {
            if (e.label.toLowerCase(Locale.ROOT).contains(q)
                    || e.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                mFiltered.add(e);
            }
        }
    }

    private void updateCount(View view) {
        MaterialTextView tv = view.findViewById(R.id.tvCount);
        if (tv == null) return;
        if (mSelected.isEmpty()) {
            tv.setText("No apps selected");
        } else {
            tv.setText(mSelected.size() + " app(s) selected");
        }
    }

    // ─── Data holder ─────────────────────────────────────────────

    private static final class AppEntry {
        String packageName;
        String label;
        boolean isSystem;
        Drawable icon;
    }

    // ─── Adapter ─────────────────────────────────────────────────

    private final class AppAdapter extends RecyclerView.Adapter<AppVH> {

        @NonNull
        @Override
        public AppVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_smart_fg_scope_app, parent, false);
            return new AppVH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull AppVH h, int position) {
            AppEntry e = mFiltered.get(position);

            h.label.setText(e.label);
            h.pkg.setText(e.packageName);
            h.systemChip.setVisibility(e.isSystem ? View.VISIBLE : View.GONE);

            // Clear any recycled icon first, then set the current
            // one. setImageDrawable(null) blanks the ImageView, so
            // a recycled row doesn't show the previous app's icon.
            h.icon.setImageDrawable(e.icon);

            // Detach listener before setChecked so we don't fire
            // on recycle.
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(mSelected.contains(e.packageName));
            h.check.setOnCheckedChangeListener((v, checked) -> {
                if (checked) mSelected.add(e.packageName);
                else mSelected.remove(e.packageName);
                // Update the count at the sheet level.
                View sheetView = getView();
                if (sheetView != null) updateCount(sheetView);
            });

            // Tapping the row toggles the checkbox.
            h.itemView.setOnClickListener(v -> h.check.toggle());
        }

        @Override
        public int getItemCount() {
            return mFiltered.size();
        }
    }

    private static final class AppVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final MaterialTextView label;
        final MaterialTextView pkg;
        final MaterialTextView systemChip;
        final CheckBox check;

        AppVH(View v) {
            super(v);
            icon = v.findViewById(R.id.imgAppIcon);
            label = v.findViewById(R.id.tvAppLabel);
            pkg = v.findViewById(R.id.tvAppPackage);
            systemChip = v.findViewById(R.id.tvSystemChip);
            check = v.findViewById(R.id.checkApp);
        }
    }
}