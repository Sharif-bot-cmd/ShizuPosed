package com.shizuposed.manager.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.model.HookedProcess;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HookedProcessAdapter extends RecyclerView.Adapter<HookedProcessAdapter.ProcessViewHolder> {
    private List<HookedProcess> processes;
    private Context context;
    private SimpleDateFormat dateFormat;

    public HookedProcessAdapter(List<HookedProcess> processes, Context context) {
        this.processes = processes;
        this.context = context;
        this.dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    }

    @NonNull
    @Override
    public ProcessViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context)
                .inflate(R.layout.item_hooked_process, parent, false);
        return new ProcessViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProcessViewHolder holder, int position) {
        HookedProcess process = processes.get(position);
        
        holder.tvProcessName.setText(process.getProcessName() != null ? 
                process.getProcessName() : "Unknown");
        
        holder.tvPid.setText("PID: " + process.getPid());
        holder.tvUid.setText("UID: " + process.getUid());
        
        if (process.isHooked()) {
            holder.tvStatus.setText("Hooked");
            holder.tvStatus.setTextColor(context.getColor(android.R.color.holo_green_light));
        } else {
            holder.tvStatus.setText("Pending");
            holder.tvStatus.setTextColor(context.getColor(android.R.color.holo_orange_light));
        }
        
        if (process.getHookedAt() > 0) {
            holder.tvTime.setText("Time: " + dateFormat.format(new Date(process.getHookedAt())));
        } else {
            holder.tvTime.setText("");
        }
        
        if (process.getModuleName() != null && !process.getModuleName().isEmpty()) {
            holder.tvModule.setText("Module: " + process.getModuleName());
        } else {
            holder.tvModule.setText("");
        }
    }

    @Override
    public int getItemCount() {
        return processes != null ? processes.size() : 0;
    }

    public void updateData(List<HookedProcess> newProcesses) {
        this.processes = newProcesses;
        notifyDataSetChanged();
    }

    static class ProcessViewHolder extends RecyclerView.ViewHolder {
        TextView tvProcessName, tvPid, tvUid, tvStatus, tvTime, tvModule;

        ProcessViewHolder(@NonNull View itemView) {
            super(itemView);
            tvProcessName = itemView.findViewById(R.id.tvProcessName);
            tvPid = itemView.findViewById(R.id.tvPid);
            tvUid = itemView.findViewById(R.id.tvUid);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvModule = itemView.findViewById(R.id.tvModule);
        }
    }
}