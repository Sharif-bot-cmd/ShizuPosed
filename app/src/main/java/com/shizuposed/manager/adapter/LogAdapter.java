package com.shizuposed.manager.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.model.LogEntry;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
    private List<LogEntry> logs;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    public LogAdapter(List<LogEntry> logs) {
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
        
        // Time
        holder.tvTime.setText(timeFormat.format(entry.timestamp));
        
        // Level with color
        holder.tvLevel.setText(entry.level);
        setLevelColor(holder.tvLevel, entry.level);
        
        // Tag
        holder.tvTag.setText(entry.tag);
        
        // Message
        holder.tvMessage.setText(entry.message);
        
        // Package name (optional)
        if (!TextUtils.isEmpty(entry.packageName)) {
            holder.tvPackage.setText(entry.packageName);
            holder.tvPackage.setVisibility(View.VISIBLE);
        } else {
            holder.tvPackage.setVisibility(View.GONE);
        }
    }

    private void setLevelColor(TextView textView, String level) {
        int color;
        switch (level.toUpperCase()) {
            case "VERBOSE":
                color = android.R.color.darker_gray;
                break;
            case "DEBUG":
                color = android.R.color.holo_blue_light;
                break;
            case "INFO":
                color = android.R.color.holo_green_light;
                break;
            case "WARN":
                color = android.R.color.holo_orange_light;
                break;
            case "ERROR":
                color = android.R.color.holo_red_light;
                break;
            default:
                color = android.R.color.white;
                break;
        }
        textView.setTextColor(textView.getContext().getColor(color));
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    public void updateData(List<LogEntry> newLogs) {
        this.logs = newLogs != null ? newLogs : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void addLog(LogEntry entry) {
        logs.add(entry);
        notifyItemInserted(logs.size() - 1);
    }

    public void clear() {
        logs.clear();
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