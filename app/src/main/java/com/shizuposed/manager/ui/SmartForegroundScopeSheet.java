package com.shizuposed.manager.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
 * SYSTEM APPS WITH DEDICATED UIDs
 * -------------------------------
 * A preinstalled app that the OEM assigned a uid >= 10000 is a
 * normal userspace target. The shell can setuid to it, so the
 * framework can hook it. These are listed, with the "System"
 * chip.
 *
 * FILTERED OUT
 * ------------
 *   • ShizuPosed's own package.
 *
 *   • Packages with uid < 10000 — SYSTEM_UID (1000) and the
 *     reserved system UID pool (1001-1999). These are the packages
 *     the shell UID cannot setuid to, so a smart-foreground scope
 *     entry for them would never fire.
 *
 *   • ANY package (system or user, any uid) that exposes neither a
 *     user-facing UI nor a running process. The monitor can never
 *     observe such a package come to the foreground, so it is
 *     skipped. See hasNoUiAndNoProcess().
 *
 * NOT FILTERED
 * ------------
 *   • Packages with a launcher activity, system or user.
 *
 *   • Packages with no launcher activity but a live background
 *     process. The process probe says they are reachable, so they
 *     stay listed.
 *
 * ASYNC LOADING
 * -------------
 * getInstalledApplications + getApplicationLabel + getApplicationIcon
 * take 2-3 seconds on a device with 200+ packages. That work has to
 * happen off the UI thread, because otherwise the sheet doesn't
 * appear until it completes.
 *
 * NOTIFYING THE PARENT
 * --------------------
 * Both Save and Cancel set a fragment result before dismissing.
 */
public class SmartForegroundScopeSheet extends BottomSheetDialogFragment {

    private static final String TAG = "SmartFgScope";

    /**
     * The uid boundary below which the shell cannot assume the
     * target's identity. SYSTEM_UID is 1000; the reserved system
     * UID pool is 1001-1999. Ordinary app UIDs start at 10000.
     */
    private static final int FIRST_APP_UID = 10000;

    public static final String RESULT_KEY = "smart_fg_scope_result";

    private final Set<String> mSelected = new HashSet<>();

    private final List<AppEntry> mAllApps = new ArrayList<>();
    private final List<AppEntry> mFiltered = new ArrayList<>();

    private volatile boolean mLoading = true;
    private volatile boolean mDestroyed = false;

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

        Set<String> initial = XStealthPrefs.getSmartForegroundScope(ctx);
        if (initial != null) mSelected.addAll(initial);

        RecyclerView list = view.findViewById(R.id.recyclerApps);
        if (list != null) {
            list.setLayoutManager(new LinearLayoutManager(ctx));
            mAdapter = new AppAdapter();
            list.setAdapter(mAdapter);
        }

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

        MaterialButton cancel = view.findViewById(R.id.buttonCancel);
        if (cancel != null) {
            cancel.setOnClickListener(v -> setResultAndDismiss());
        }

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

        startAppLoad(ctx);
    }

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

    private List<AppEntry> loadAppsBlocking(Context appCtx) {
        List<AppEntry> out = new ArrayList<>();
        PackageManager pm = appCtx.getPackageManager();

        // MATCH_DISABLED_COMPONENTS makes the query return
        // preinstalled apps that are flagged as disabled until
        // first launch. Without it, some OEM preinstalls with
        // dedicated uids are missing from the list even though
        // they are installed and hookable.
        List<ApplicationInfo> apps = pm.getInstalledApplications(
            PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS);
        if (apps == null) return out;

        String self = appCtx.getPackageName();

        Set<String> runningProcs = snapshotRunningProcessNames(appCtx);

        int droppedProtected = 0;
        int droppedNoUi = 0;

        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            if (self.equals(ai.packageName)) continue;

            int uid = ai.uid;

            // Protected-UID packages: the shell cannot setuid to
            // them, so a scope entry would never fire.
            if (uid > 0) {
                if (uid < FIRST_APP_UID) {
                    droppedProtected++;
                    continue;
                }
            } else {
                boolean system = (ai.flags
                    & (ApplicationInfo.FLAG_SYSTEM
                       | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (system) {
                    String pkg = ai.packageName;
                    if (pkg.startsWith("android.")
                            || pkg.startsWith("com.android.")
                            || pkg.startsWith("com.google.android.")) {
                        droppedProtected++;
                        continue;
                    }
                }
            }

            // Skip ANY package (system or user, any uid) that has
            // neither a launcher UI nor a live process. The
            // foreground monitor can never observe such a package,
            // so listing it is noise.
            if (hasNoUiAndNoProcess(ai, pm, runningProcs)) {
                droppedNoUi++;
                Log.d(TAG, "dropped ui-less idle pkg: "
                    + ai.packageName + " (uid=" + ai.uid + ")");
                continue;
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

        Log.d(TAG, "loadAppsBlocking: kept=" + out.size()
            + " droppedProtected=" + droppedProtected
            + " droppedNoUi=" + droppedNoUi);

        final Collator col = Collator.getInstance(Locale.getDefault());
        col.setStrength(Collator.PRIMARY);
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));

        return out;
    }

    private Set<String> snapshotRunningProcessNames(Context appCtx) {
        Set<String> names = new HashSet<>();
        try {
            ActivityManager am = (ActivityManager)
                appCtx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return names;
            List<ActivityManager.RunningAppProcessInfo> procs =
                am.getRunningAppProcesses();
            if (procs == null) return names;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p == null) continue;
                if (p.processName != null) names.add(p.processName);
                if (p.pkgList != null) {
                    for (String pkg : p.pkgList) {
                        if (pkg != null) names.add(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "snapshotRunningProcessNames failed: "
                + t.getMessage());
        }
        return names;
    }

    /**
     * True if this package has no user-facing UI AND no process
     * currently running. Applies to BOTH system and user packages,
     * regardless of uid. The foreground monitor can never observe
     * such a package, so it is skipped.
     *
     * "Has UI"  = at least one exported ACTION_MAIN + CATEGORY_LAUNCHER
     *             activity (the same signal the launcher uses).
     * "Has proc" = the package's processName or packageName appears in
     *             the ActivityManager snapshot.
     *
     * Fails open: if a probe throws, the package is KEPT.
     */
    private boolean hasNoUiAndNoProcess(ApplicationInfo ai,
                                        PackageManager pm,
                                        Set<String> runningProcs) {
        try {
            // A live process keeps the package regardless of UI.
            if (ai.processName != null
                    && runningProcs.contains(ai.processName)) {
                return false;
            }
            if (runningProcs.contains(ai.packageName)) {
                return false;
            }

            // No process. Keep it if it has a launcher UI.
            return !hasLauncherActivity(ai.packageName, pm);
        } catch (Throwable t) {
            // Fail open: keep the package.
            return false;
        }
    }

    /**
     * True if the package exposes at least one activity that a
     * launcher would show: ACTION_MAIN + CATEGORY_LAUNCHER. This
     * is the same signal the launcher uses, so it is the most
     * reliable "has a user-facing UI" test available without
     * extra permission.
     *
     * Fails open: on error, returns true so we never hide a
     * package that might actually be useful.
     */
    private boolean hasLauncherActivity(String packageName,
                                        PackageManager pm) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            List<ResolveInfo> ris = pm.queryIntentActivities(
                intent, 0);
            if (ris == null || ris.isEmpty()) return false;
            for (ResolveInfo ri : ris) {
                if (ri == null) continue;
                ActivityInfo a = ri.activityInfo;
                if (a == null) continue;
                // Exported + has a real class name is the launcher
                // contract. A non-exported activity can't be
                // started by the launcher, so it doesn't count.
                if (a.exported && a.name != null) return true;
            }
            return false;
        } catch (Throwable t) {
            // Fail open: assume it has UI so we never hide it.
            return true;
        }
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