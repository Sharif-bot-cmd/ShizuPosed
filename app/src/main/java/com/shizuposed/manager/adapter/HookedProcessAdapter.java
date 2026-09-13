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

/**
 * HookedProcessAdapter
 *
 * Renders the Home tab's process list. HomeFragment filters this to
 * processes that are in scope of an enabled module (or already
 * reported as hooked), so every row here is a process the framework
 * is currently working with.
 *
 * Status text intentionally shows only the process name, PID, UID,
 * scope time, and module name — no "Pending" / "Hooked" state. In the
 * current post-Application timing model, a marker file is the only
 * signal we get back from the spawned app_process, and it does not
 * reliably indicate that the process visible in /proc is the one that
 * got hooked. Showing a state derived from that signal is misleading.
 *
 * If/when the framework reports hook success for the exact pid shown
 * here, a state column can be reintroduced.
 */
public class HookedProcessAdapter
        extends RecyclerView.Adapter<HookedProcessAdapter.ProcessViewHolder> {

    private List<HookedProcess> processes;
    private final Context context;
    private final SimpleDateFormat dateFormat;

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
        if (process == null) return;

        holder.tvProcessName.setText(process.getProcessName() != null
                ? process.getProcessName() : "Unknown");

        holder.tvPid.setText("PID: " + process.getPid());
        holder.tvUid.setText("UID: " + process.getUid());

        // Hide the status column entirely. We do not have a reliable
        // signal about whether this exact pid has been hooked, so we
        // show nothing rather than something misleading.
        if (holder.tvStatus != null) {
            holder.tvStatus.setVisibility(View.GONE);
        }

        if (process.getHookedAt() > 0) {
            holder.tvTime.setText("Seen: "
                    + dateFormat.format(new Date(process.getHookedAt())));
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
            tvStatus = itemView.findViewById(R.id.tvStatus);   // may still exist in layout
            tvTime = itemView.findViewById(R.id.tvTime);
            tvModule = itemView.findViewById(R.id.tvModule);
        }
    }
}