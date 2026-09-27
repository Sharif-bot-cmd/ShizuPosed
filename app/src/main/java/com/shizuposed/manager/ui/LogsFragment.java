package com.shizuposed.manager.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.shizuposed.manager.R;
import com.shizuposed.manager.model.LogEntry;
import com.shizuposed.manager.utils.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * LogsFragment
 *
 * The Logs tab. Shows the manager's own log with search.
 *
 * SEARCH
 * ------
 * Filters on text change (not just submit) so the list narrows as
 * the user types. Matches against message, tag, level, and package
 * name. An empty query resets to the full list.
 *
 * The search field has a clear (X) icon on the right. Tapping it
 * empties the field and restores the full list.
 *
 * The keyboard hides when the user starts scrolling the list, so
 * the log entries aren't obscured.
 *
 * EMPTY STATE
 * -----------
 * Two distinct empty states:
 *   • No logs at all     → "No log entries yet"
 *   • Query matches none → "No log entries match \"<query>\""
 */
public class LogsFragment extends Fragment {
    private RecyclerView logRecyclerView;
    private ProgressBar progressIndicator;
    private EditText etSearchLogs;
    private Button btnRefresh;
    private FloatingActionButton fabClearLogs, fabExportLogs;
    private TextView tvEmptyState;

    private Logger logger;
    private List<LogEntry> allLogs = new ArrayList<>();
    private LogAdapter logAdapter;

    private volatile boolean viewReady = false;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);
        initViews(view);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;
        setupRecyclerView();
        setupListeners();
        loadLogs();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
        logRecyclerView = null;
        logAdapter = null;
        progressIndicator = null;
        etSearchLogs = null;
        btnRefresh = null;
        fabClearLogs = null;
        fabExportLogs = null;
        tvEmptyState = null;
    }

    // ═════════════════════════════════════════════════════════════
    // VIEW SETUP
    // ═════════════════════════════════════════════════════════════

    private void initViews(View view) {
        logRecyclerView = view.findViewById(R.id.logRecyclerView);
        progressIndicator = view.findViewById(R.id.progressIndicator);
        etSearchLogs = view.findViewById(R.id.etSearchLogs);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        fabClearLogs = view.findViewById(R.id.fabClearLogs);
        fabExportLogs = view.findViewById(R.id.fabExportLogs);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);

        // Add a clear (X) icon to the search field. Tapping it
        // empties the query and restores the full list.
        if (etSearchLogs != null) {
            etSearchLogs.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                android.R.drawable.ic_menu_close_clear_cancel, 0);
            etSearchLogs.setOnTouchListener((v, event) -> {
                if (event.getAction() != MotionEvent.ACTION_UP) return false;
                android.graphics.drawable.Drawable[] d =
                    etSearchLogs.getCompoundDrawables();
                if (d[2] == null) return false;
                int drawableRight = etSearchLogs.getRight()
                    - etSearchLogs.getCompoundPaddingRight()
                    + d[2].getBounds().width();
                if (event.getRawX() >= drawableRight) {
                    etSearchLogs.setText("");
                    applyFilter("");
                    return true;
                }
                return false;
            });
        }
    }

    private void setupRecyclerView() {
        if (logRecyclerView == null) return;
        logAdapter = new LogAdapter(new ArrayList<>());
        logRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        logRecyclerView.setAdapter(logAdapter);

        // Hide the keyboard when the user starts scrolling.
        logRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING
                        && etSearchLogs != null) {
                    InputMethodManager imm = (InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(
                            etSearchLogs.getWindowToken(), 0);
                    }
                }
            }
        });
    }

    private void setupListeners() {
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> loadLogs());
        }
        if (fabClearLogs != null) {
            fabClearLogs.setOnClickListener(v -> clearLogs());
        }
        if (fabExportLogs != null) {
            fabExportLogs.setOnClickListener(v -> exportLogs());
        }
        if (etSearchLogs != null) {
            etSearchLogs.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    applyFilter(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LOAD + FILTER
    // ═════════════════════════════════════════════════════════════

    private void loadLogs() {
        if (!viewReady || logger == null || logAdapter == null) return;
        showLoading(true);
        try {
            allLogs = logger.getLogEntries();
            if (allLogs == null) allLogs = new ArrayList<>();

            String query = etSearchLogs != null
                ? etSearchLogs.getText().toString() : "";
            applyFilter(query);

            if (logger != null) {
                logger.i("Loaded " + allLogs.size() + " log entries");
            }
        } catch (Exception e) {
            if (logger != null) logger.e("Error loading logs: " + e.getMessage());
            if (isAdded()) {
                Toast.makeText(requireContext(), "Failed to load logs",
                    Toast.LENGTH_SHORT).show();
            }
        }
        showLoading(false);
    }

    /**
     * Filter the full log list by the given query. Matches against
     * message, tag, level, and package name. Empty query resets to
     * the full list.
     *
     * Updates the empty state: "no logs" vs "no matches".
     */
    private void applyFilter(String query) {
        if (logAdapter == null) return;

        List<LogEntry> filtered = new ArrayList<>();
        String q = (query != null) ? query.toLowerCase().trim() : "";

        if (q.isEmpty()) {
            filtered.addAll(allLogs);
        } else {
            for (LogEntry entry : allLogs) {
                if (entry == null) continue;
                boolean match = false;
                if (entry.message != null
                        && entry.message.toLowerCase().contains(q)) {
                    match = true;
                } else if (entry.tag != null
                        && entry.tag.toLowerCase().contains(q)) {
                    match = true;
                } else if (entry.level != null
                        && entry.level.toLowerCase().contains(q)) {
                    match = true;
                } else if (entry.packageName != null
                        && entry.packageName.toLowerCase().contains(q)) {
                    match = true;
                }
                if (match) filtered.add(entry);
            }
        }

        logAdapter.updateData(filtered);

        // Empty state: distinguish "no logs at all" from "no matches".
        if (tvEmptyState != null) {
            if (filtered.isEmpty()) {
                if (allLogs.isEmpty()) {
                    tvEmptyState.setText("No log entries yet");
                } else {
                    tvEmptyState.setText("No log entries match \""
                        + query + "\"");
                }
                tvEmptyState.setVisibility(View.VISIBLE);
            } else {
                tvEmptyState.setVisibility(View.GONE);
            }
        }

        if (logRecyclerView != null) {
            logRecyclerView.setVisibility(filtered.isEmpty()
                ? View.GONE : View.VISIBLE);
            // Auto-scroll to the newest entry only when the query
            // is empty. When filtering, leave the user where they
            // are so the list doesn't jump under them.
            if (!filtered.isEmpty() && q.isEmpty()) {
                logRecyclerView.scrollToPosition(filtered.size() - 1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIONS
    // ═════════════════════════════════════════════════════════════

    private void clearLogs() {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all logs?")
            .setPositiveButton("Clear", (dialog, which) -> {
                if (logger == null || logAdapter == null) return;
                logger.clearLogs();
                allLogs.clear();
                if (etSearchLogs != null) etSearchLogs.setText("");
                applyFilter("");
                if (isAdded()) {
                    Toast.makeText(requireContext(), "Logs cleared",
                        Toast.LENGTH_SHORT).show();
                }
                if (logger != null) logger.i("Logs cleared");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void exportLogs() {
        if (logger == null || !isAdded() || getContext() == null) return;
        try {
            String logContent = logger.exportLogs();
            String fileName = "shizuposed_logs_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.getDefault())
                    .format(new java.util.Date()) + ".txt";
            File logFile = new File(
                requireContext().getExternalFilesDir(null), fileName);
            com.shizuposed.manager.utils.FileUtils.writeFile(logFile, logContent);
            Toast.makeText(requireContext(),
                "Logs exported to " + logFile.getAbsolutePath(),
                Toast.LENGTH_LONG).show();
            if (logger != null) {
                logger.i("Logs exported to: " + logFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to export logs",
                Toast.LENGTH_SHORT).show();
            if (logger != null) logger.e("Export error: " + e.getMessage());
        }
    }

    public void refresh() {
        if (!viewReady) {
            if (logger != null) logger.d("refresh() skipped: view not ready");
            return;
        }
        loadLogs();
    }

    private void showLoading(boolean show) {
        if (progressIndicator != null) {
            progressIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ADAPTER
    // ═════════════════════════════════════════════════════════════

    private static class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
        private List<LogEntry> logs;
        private final java.text.SimpleDateFormat timeFormat =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                java.util.Locale.getDefault());

        LogAdapter(List<LogEntry> logs) {
            this.logs = logs != null ? logs : new ArrayList<>();
        }

        @NonNull
        @Override
        public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
            return new LogViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
            LogEntry entry = logs.get(position);
            holder.tvTime.setText(timeFormat.format(
                new java.util.Date(entry.timestamp)));
            holder.tvLevel.setText(entry.level);
            holder.tvTag.setText(entry.tag);
            holder.tvMessage.setText(entry.message);

            if (entry.packageName != null && !entry.packageName.isEmpty()) {
                holder.tvPackage.setText(entry.packageName);
                holder.tvPackage.setVisibility(View.VISIBLE);
            } else {
                holder.tvPackage.setVisibility(View.GONE);
            }

            int color;
            String level = entry.level != null ? entry.level.toUpperCase() : "";
            switch (level) {
                case "VERBOSE": color = android.R.color.darker_gray; break;
                case "DEBUG":   color = android.R.color.holo_blue_light; break;
                case "INFO":    color = android.R.color.holo_green_light; break;
                case "WARN":    color = android.R.color.holo_orange_light; break;
                case "ERROR":   color = android.R.color.holo_red_light; break;
                default:        color = android.R.color.white; break;
            }
            holder.tvLevel.setTextColor(
                holder.itemView.getContext().getColor(color));
        }

        @Override
        public int getItemCount() { return logs.size(); }

        void updateData(List<LogEntry> newLogs) {
            this.logs = newLogs != null ? newLogs : new ArrayList<>();
            notifyDataSetChanged();
        }

        static class LogViewHolder extends RecyclerView.ViewHolder {
            TextView tvTime, tvLevel, tvTag, tvMessage, tvPackage;
            LogViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTime = itemView.findViewById(R.id.tvTime);
                tvLevel = itemView.findViewById(R.id.tvLevel);
                tvTag = itemView.findViewById(R.id.tvTag);
                tvMessage = itemView.findViewById(R.id.tvMessage);
                tvPackage = itemView.findViewById(R.id.tvPackage);
            }
        }
    }
}