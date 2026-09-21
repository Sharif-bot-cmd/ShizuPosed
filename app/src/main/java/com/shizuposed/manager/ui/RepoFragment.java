package com.shizuposed.manager.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.adapter.RepoModuleAdapter;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.core.RepoMetadataReader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.model.RepoModuleInfo;
import com.shizuposed.manager.stealth.XStealthModule;
import com.shizuposed.manager.utils.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repo tab.
 *
 * Shows every installed module with its README and metadata. The
 * data source is the module APKs already registered with
 * ModuleLoader — there is no network repository yet.
 *
 * Architecture:
 *   • Load the installed modules synchronously (cheap: reads JSON
 *     descriptors ModuleLoader already has cached).
 *   • For each module, read metadata + README off the main thread
 *     via RepoMetadataReader. Populate the adapter as reads
 *     complete, so the list renders immediately and enriches.
 *   • XStealth is excluded: it has no APK to read.
 */
public class RepoFragment extends Fragment {

    private RecyclerView recyclerView;
    private ProgressBar progress;
    private TextView tvEmpty;
    private EditText etSearch;

    private RepoModuleAdapter adapter;
    private Logger logger;
    private ModuleLoader moduleLoader;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;

    private final List<RepoModuleInfo> allEntries = new ArrayList<>();

    private volatile boolean viewReady = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
        moduleLoader = ModuleLoader.getInstance(context);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_repo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        viewReady = true;

        recyclerView = v.findViewById(R.id.rvRepoModules);
        progress = v.findViewById(R.id.pbRepo);
        tvEmpty = v.findViewById(R.id.tvRepoEmpty);
        etSearch = v.findViewById(R.id.etRepoSearch);

        adapter = new RepoModuleAdapter(requireContext());
        adapter.setOnModuleClickListener(this::openDetail);
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
            recyclerView.setAdapter(adapter);
        }

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    applyFilter(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        loadModules();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        recyclerView = null;
        progress = null;
        tvEmpty = null;
        etSearch = null;
        adapter = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewReady) loadModules();
    }

    public void refresh() {
        if (viewReady) loadModules();
    }

    // ═════════════════════════════════════════════════════════════
    // LOAD
    // ═════════════════════════════════════════════════════════════

    private void loadModules() {
        if (!viewReady || moduleLoader == null) return;

        if (progress != null) progress.setVisibility(View.VISIBLE);

        List<ModuleInfo> modules = moduleLoader.loadModules();
        if (modules == null) modules = new ArrayList<>();

        // Filter out XStealth and any module without an APK.
        List<ModuleInfo> eligible = new ArrayList<>();
        for (ModuleInfo m : modules) {
            if (m == null || m.packageName == null) continue;
            if (XStealthModule.PACKAGE.equals(m.packageName)) continue;
            if (m.apkPath == null || m.apkPath.isEmpty()) continue;
            eligible.add(m);
        }

        allEntries.clear();
        for (ModuleInfo m : eligible) {
            // Seed with a bare entry so the list renders immediately.
            allEntries.add(new RepoModuleInfo(m));
        }
        sortAndPublish();

        // Enrich each entry off the main thread.
        final Context appCtx = requireContext().getApplicationContext();
        for (ModuleInfo m : eligible) {
            executor.execute(() -> {
                RepoModuleInfo enriched = RepoMetadataReader.read(appCtx, m);
                mainHandler.post(() -> mergeEnriched(enriched));
            });
        }

        if (progress != null) {
            mainHandler.postDelayed(() -> {
                if (viewReady && progress != null) {
                    progress.setVisibility(View.GONE);
                }
            }, 400);
        }
    }

    private void mergeEnriched(RepoModuleInfo enriched) {
        if (!viewReady || enriched == null || enriched.module == null) return;

        for (int i = 0; i < allEntries.size(); i++) {
            RepoModuleInfo existing = allEntries.get(i);
            if (existing.module != null
                    && existing.module.packageName != null
                    && existing.module.packageName.equals(enriched.module.packageName)) {
                allEntries.set(i, enriched);
                break;
            }
        }
        sortAndPublish();
    }

    private void sortAndPublish() {
        if (!viewReady || adapter == null) return;

        List<RepoModuleInfo> sorted = new ArrayList<>(allEntries);
        Collections.sort(sorted, (a, b) ->
            a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));

        applyFilterToList(sorted, etSearch != null ? etSearch.getText().toString() : "");
    }

    // ═════════════════════════════════════════════════════════════
    // FILTER
    // ═════════════════════════════════════════════════════════════

    private void applyFilter(String query) {
        if (!viewReady || adapter == null) return;
        List<RepoModuleInfo> sorted = new ArrayList<>(allEntries);
        Collections.sort(sorted, (a, b) ->
            a.getDisplayName().compareToIgnoreCase(b.getDisplayName()));
        applyFilterToList(sorted, query);
    }

    private void applyFilterToList(List<RepoModuleInfo> source, String query) {
        if (adapter == null) return;
        List<RepoModuleInfo> filtered = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);

        for (RepoModuleInfo info : source) {
            if (q.isEmpty()) {
                filtered.add(info);
                continue;
            }
            String name = info.getDisplayName().toLowerCase(Locale.US);
            String pkg = info.module != null && info.module.packageName != null
                ? info.module.packageName.toLowerCase(Locale.US) : "";
            String author = info.author != null
                ? info.author.toLowerCase(Locale.US) : "";
            String desc = info.description != null
                ? info.description.toLowerCase(Locale.US) : "";
            if (name.contains(q) || pkg.contains(q)
                    || author.contains(q) || desc.contains(q)) {
                filtered.add(info);
            }
        }

        adapter.updateData(filtered);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (recyclerView != null) {
            recyclerView.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DETAIL
    // ═════════════════════════════════════════════════════════════

    private void openDetail(RepoModuleInfo info) {
        if (!viewReady || info == null) return;
        RepoDetailSheet sheet = RepoDetailSheet.newInstance(info);
        sheet.show(getParentFragmentManager(), "repo_detail");
    }
}