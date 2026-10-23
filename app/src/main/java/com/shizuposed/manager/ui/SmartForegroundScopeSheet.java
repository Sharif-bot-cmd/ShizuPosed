package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * ASYNC LOADING
 * -------------
 * getInstalledApplications + getApplicationLabel + getApplicationIcon
 * take 2–3 seconds on a device with 200+ packages. That work has to
 * happen off the UI thread, because otherwise the sheet doesn't
 * appear until it completes — the user sees a blank screen for a
 * couple of seconds before the picker opens.
 *
 * The sheet now appears immediately. onViewCreated seeds the
 * selected set, builds the adapter with an empty list, and posts
 * the loading to a background thread. The adapter renders a single
 * "Loading…" row while mLoading is true. When the load finishes,
 * mLoading flips to false, mAllApps is populated, rebuildFiltered
 * runs, and notifyDataSetChanged swaps the loading row for the
 * real list.
 *
 * The user can dismiss the sheet at any point. If the activity
 * is torn down before the background load finishes, the result is
 * dropped — no crash, no leak.
 *
 * FILTERED OUT
 * ------------
 *   • ShizuPosed's own package
 *   • Packages with uid < 10000 — SYSTEM_UID (1000) and the
 *     reserved system UID pool (1001-1999). These are the packages
 *     the shell UID cannot setuid to, so a smart-foreground scope
 *     entry for them would never fire. System UI, Settings, Phone,
 *     Bluetooth, NFC, Shell, and system_server all live there.
 *
 * NOT FILTERED
 * ------------
 *   • System apps with their own app UID (>= 10000). A preinstalled
 *     app that the OEM assigned a dedicated uid is a normal
 *     userspace target from the shell's point of view. Those are
 *     still listed, marked with a "System" chip.
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

    /** True while the background app load is running. The adapter
     *  renders a single "Loading…" row in this state. */
    private volatile boolean mLoading = true;

    /** True if the sheet has been destroyed. The background task
     *  checks this before posting back to the main thread, so a
     *  late result is dropped rather than crashing. */
    private volatile boolean mDestroyed = false;

    /** Single-thread executor for the background load. Daemon so
     *  it doesn't keep the process alive. Shutdown in onDestroyView. */
    private ExecutorService mLoadExecutor;

    private AppAdapter mAdapter;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

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

        // Seed the working set from preferences. Cheap, main thread.
        Set<String> initial = XStealthPrefs.getSmartForegroundScope(ctx);
        if (initial != null) mSelected.addAll(initial);

        // Set up the RecyclerView with an empty list. The adapter
        // renders the "Loading…" row because mLoading is true.
        RecyclerView list = view.findViewById(R.id.recyclerApps);
        if (list != null) {
            list.setLayoutManager(new LinearLayoutManager(ctx));
            mAdapter = new AppAdapter();
            list.setAdapter(mAdapter);
        }

        // Search field. Wired up before the load finishes so the
        // user can start typing immediately. rebuildFiltered is a
        // no-op while mAllApps is empty.
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

        // Select all / clear. Both operate on mFiltered, which is
        // empty while loading — clicking them during the load does
        // nothing, which is the right behavior.
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

                setResultAndDismiss();
            });
        }

        updateCount(view);

        // Kick off the background load. The sheet is already
        // visible; the adapter is showing the "Loading…" row.
        startAppLoad(ctx);
    }

    /**
     * Load the installed-app list on a background thread and hand
     * it back to the adapter when it's ready.
     *
     * The three expensive calls — getInstalledApplications,
     * getApplicationLabel, getApplicationIcon — run here, off the
     * UI thread. The result posts back to the main thread through
     * mMainHandler, checked against mDestroyed so a late result
     * from a dismissed sheet is dropped.
     */
    private void startAppLoad(Context ctx) {
        final Context appCtx = ctx.getApplicationContext();
        mLoadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SmartFgScope-load");
            t.setDaemon(true);
            return t;
        });

        mLoadExecutor.execute(() -> {
            List<AppEntry> loaded;
            try {
                loaded = loadAppsBlocking(appCtx);
            } catch (Throwable t) {
                Log.w(TAG, "loadAppsBlocking failed: " + t.getMessage());
                loaded = new ArrayList<>();
            }

            final List<AppEntry> result = loaded;
            mMainHandler.post(() -> {
                if (mDestroyed) {
                    Log.d(TAG, "load finished after destroy — dropping "
                        + result.size() + " entries");
                    return;
                }
                if (!isAdded()) return;

                mAllApps.clear();
                mAllApps.addAll(result);
                mLoading = false;
                rebuildFiltered("");

                if (mAdapter != null) mAdapter.notifyDataSetChanged();

                View v = getView();
                if (v != null) updateCount(v);

                Log.i(TAG, "loaded " + result.size() + " apps");
            });
        });
    }

    /**
     * Blocking version of the load. Runs on the background thread.
     * Touches only appCtx and the package manager, so it's safe to
     * call from any thread.
     */
    private List<AppEntry> loadAppsBlocking(Context appCtx) {
        List<AppEntry> out = new ArrayList<>();
        PackageManager pm = appCtx.getPackageManager();

        List<ApplicationInfo> apps = pm.getInstalledApplications(
            PackageManager.GET_META_DATA);
        if (apps == null) return out;

        String self = appCtx.getPackageName();

        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            if (self.equals(ai.packageName)) continue;

            // Skip protected-UID packages. The shell UID cannot
            // setuid to uid 1000 or the reserved pool 1001-1999.
            int uid = ai.uid;
            if (uid > 0) {
                if (uid < 10000) continue;
            } else {
                boolean system = (ai.flags
                    & (ApplicationInfo.FLAG_SYSTEM
                       | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (system) {
                    String pkg = ai.packageName;
                    if (pkg.startsWith("android.")
                            || pkg.startsWith("com.android.")
                            || pkg.startsWith("com.google.android.")) {
                        continue;
                    }
                }
            }

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

            out.add(e);
        }

        // Sort by label using the locale collator.
        final Collator col = Collator.getInstance(Locale.getDefault());
        col.setStrength(Collator.PRIMARY);
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));

        return out;
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
        if (mLoading) {
            tv.setText("Loading apps…");
        } else if (mSelected.isEmpty()) {
            tv.setText("No apps selected");
        } else {
            tv.setText(mSelected.size() + " app(s) selected");
        }
    }

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
    public void onDestroyView() {
        super.onDestroyView();
        mDestroyed = true;

        if (mLoadExecutor != null) {
            mLoadExecutor.shutdownNow();
            mLoadExecutor = null;
        }

        mAdapter = null;
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

    // ─── Data holder ─────────────────────────────────────────────

    private static final class AppEntry {
        String packageName;
        String label;
        boolean isSystem;
        Drawable icon;
    }

    // ─── Adapter ─────────────────────────────────────────────────

    private final class AppAdapter extends RecyclerView.Adapter<AppVH> {

        /** View type for the loading row. Distinct from the real
         *  row type so the loader doesn't need a fake AppEntry. */
        private static final int TYPE_LOADING = 0;
        private static final int TYPE_APP     = 1;

        @Override
        public int getItemViewType(int position) {
            return mLoading ? TYPE_LOADING : TYPE_APP;
        }

        @NonNull
        @Override
        public AppVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_smart_fg_scope_app, parent, false);
            return new AppVH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull AppVH h, int position) {
            if (mLoading) {
                // Loading row. Hide every field except the label,
                // and show "Loading…" in the label. The icon slot
                // is blanked. The checkbox is hidden because
                // toggling it would do nothing useful.
                h.icon.setImageDrawable(null);
                h.icon.setVisibility(View.GONE);
                h.label.setText("Loading…");
                h.label.setVisibility(View.VISIBLE);
                h.pkg.setVisibility(View.GONE);
                h.systemChip.setVisibility(View.GONE);
                h.check.setVisibility(View.GONE);
                h.check.setOnCheckedChangeListener(null);
                h.itemView.setOnClickListener(null);
                return;
            }

            // Restore any views the loading row hid.
            h.icon.setVisibility(View.VISIBLE);
            h.label.setVisibility(View.VISIBLE);
            h.pkg.setVisibility(View.VISIBLE);
            h.check.setVisibility(View.VISIBLE);

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
                View sheetView = getView();
                if (sheetView != null) updateCount(sheetView);
            });

            // Tapping the row toggles the checkbox.
            h.itemView.setOnClickListener(v -> h.check.toggle());
        }

        @Override
        public int getItemCount() {
            if (mLoading) return 1;
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