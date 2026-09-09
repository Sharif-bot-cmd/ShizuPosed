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

import java.util.List;
import java.util.ArrayList;

public class HookedProcessAdapter extends RecyclerView.Adapter<HookedProcessAdapter.ProcessViewHolder> {
    private List<HookedProcess> processes;
    private Context context;
    
    public HookedProcessAdapter(List<HookedProcess> processes, Context context) {
        this.processes = processes != null ? processes : new ArrayList<>();
        this.context = context;
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
        
        holder.tvPackage.setText(process.packageName);
        holder.tvPid.setText("PID: " + process.pid);
        holder.tvUid.setText("UID: " + process.uid);
        holder.tvStatus.setText(process.status);
        holder.tvTime.setText(process.getFormattedHookTime());
        
        // Status color
        int color;
        if (process.isHooked()) {
            color = context.getColor(android.R.color.holo_green_light);
        } else if (process.isFailed()) {
            color = context.getColor(android.R.color.holo_red_light);
        } else {
            color = context.getColor(android.R.color.holo_orange_light);
        }
        holder.tvStatus.setTextColor(color);
    }
    
    @Override
    public int getItemCount() {
        return processes.size();
    }
    
    public void updateData(List<HookedProcess> newProcesses) {
        this.processes = newProcesses != null ? newProcesses : new ArrayList<>();
        notifyDataSetChanged();
    }
    
    static class ProcessViewHolder extends RecyclerView.ViewHolder {
        TextView tvPackage, tvPid, tvUid, tvStatus, tvTime;
        
        ProcessViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvPid = itemView.findViewById(R.id.tvPid);
            tvUid = itemView.findViewById(R.id.tvUid);
            tvStatus = itemView.findViewById(R.id.tvStatus);
            tvTime = itemView.findViewById(R.id.tvTime);
        }
    }
}