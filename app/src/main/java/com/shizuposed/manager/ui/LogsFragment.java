package com.shizuposed.manager.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

public class LogsFragment extends Fragment {
    private RecyclerView logRecyclerView;
    private ProgressBar progressIndicator;
    private EditText etSearchLogs;
    private Button btnRefresh;
    private FloatingActionButton fabClearLogs, fabExportLogs;
    private TextView tvEmptyState;

    private Logger logger;
    private List<LogEntry> logs = new ArrayList<>();
    private LogAdapter logAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        initViews(view);
        setupRecyclerView();
        setupListeners();
        loadLogs();

        return view;
    }

    private void initViews(View view) {
        logRecyclerView = view.findViewById(R.id.logRecyclerView);
        progressIndicator = view.findViewById(R.id.progressIndicator);
        etSearchLogs = view.findViewById(R.id.etSearchLogs);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        fabClearLogs = view.findViewById(R.id.fabClearLogs);
        fabExportLogs = view.findViewById(R.id.fabExportLogs);
        tvEmptyState = view.findViewById(R.id.tvEmptyState);

        logger = Logger.getInstance(requireContext());
    }

    private void setupRecyclerView() {
        logAdapter = new LogAdapter(logs);
        logRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        logRecyclerView.setAdapter(logAdapter);
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> loadLogs());
        fabClearLogs.setOnClickListener(v -> clearLogs());
        fabExportLogs.setOnClickListener(v -> exportLogs());

        etSearchLogs.setOnEditorActionListener((v, actionId, event) -> {
            filterLogs(etSearchLogs.getText().toString());
            return true;
        });
    }

    private void loadLogs() {
        showLoading(true);
        try {
            logs = logger.getLogEntries();
            logAdapter.updateData(logs);

            if (logs.isEmpty()) {
                tvEmptyState.setVisibility(View.VISIBLE);
                logRecyclerView.setVisibility(View.GONE);
            } else {
                tvEmptyState.setVisibility(View.GONE);
                logRecyclerView.setVisibility(View.VISIBLE);
                logRecyclerView.scrollToPosition(logs.size() - 1);
            }

            logger.i("Loaded " + logs.size() + " log entries");
        } catch (Exception e) {
            logger.e("Error loading logs: " + e.getMessage());
            Toast.makeText(requireContext(), "Failed to load logs", Toast.LENGTH_SHORT).show();
        }
        showLoading(false);
    }

    private void filterLogs(String query) {
        if (query.isEmpty()) {
            logAdapter.updateData(logs);
            return;
        }

        List<LogEntry> filtered = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (LogEntry entry : logs) {
            if (entry.message.toLowerCase().contains(lowerQuery)
                || entry.tag.toLowerCase().contains(lowerQuery)
                || entry.level.toLowerCase().contains(lowerQuery)
                || (entry.packageName != null && entry.packageName.toLowerCase().contains(lowerQuery))) {
                filtered.add(entry);
            }
        }

        logAdapter.updateData(filtered);
        if (filtered.isEmpty()) {
            Toast.makeText(requireContext(), "No matching logs", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogs() {
        new AlertDialog.Builder(requireContext())
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all logs?")
            .setPositiveButton("Clear", (dialog, which) -> {
                logger.clearLogs();
                logs.clear();
                logAdapter.updateData(logs);
                tvEmptyState.setVisibility(View.VISIBLE);
                logRecyclerView.setVisibility(View.GONE);
                Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show();
                logger.i("Logs cleared");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void exportLogs() {
        try {
            String logContent = logger.exportLogs();
            String fileName = "shizuposed_logs_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date()) + ".txt";

            File logFile = new File(requireContext().getExternalFilesDir(null), fileName);
            com.shizuposed.manager.utils.FileUtils.writeFile(logFile, logContent);

            Toast.makeText(requireContext(),
                "Logs exported to " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            logger.i("Logs exported to: " + logFile.getAbsolutePath());

        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to export logs", Toast.LENGTH_SHORT).show();
            logger.e("Export error: " + e.getMessage());
        }
    }

    public void refresh() {
        loadLogs();
    }

    private void showLoading(boolean show) {
        progressIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
        private List<LogEntry> logs;
        private final java.text.SimpleDateFormat timeFormat =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault());

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

            holder.tvTime.setText(timeFormat.format(new java.util.Date(entry.timestamp)));
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
            switch (entry.level.toUpperCase()) {
                case "VERBOSE": color = android.R.color.darker_gray; break;
                case "DEBUG":   color = android.R.color.holo_blue_light; break;
                case "INFO":    color = android.R.color.holo_green_light; break;
                case "WARN":    color = android.R.color.holo_orange_light; break;
                case "ERROR":   color = android.R.color.holo_red_light; break;
                default:        color = android.R.color.white; break;
            }
            holder.tvLevel.setTextColor(holder.itemView.getContext().getColor(color));
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

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