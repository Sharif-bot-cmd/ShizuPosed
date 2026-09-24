package com.shizuposed.manager.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.shizuposed.manager.R;
import com.shizuposed.manager.model.RepoModuleInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Row adapter for the Repo tab. Each row shows a module's icon,
 * name, version, author, and description.
 *
 * The list is not filterable here — filtering happens in the
 * fragment, which passes the filtered list to updateData(). Keeps
 * the adapter simple.
 */
public class RepoModuleAdapter
        extends RecyclerView.Adapter<RepoModuleAdapter.RepoViewHolder> {

    public interface OnModuleClickListener {
        void onModuleClick(RepoModuleInfo info);
    }

    private List<RepoModuleInfo> modules = new ArrayList<>();
    private final Context context;
    private OnModuleClickListener listener;

    public RepoModuleAdapter(Context context) {
        this.context = context;
    }

    public void setOnModuleClickListener(OnModuleClickListener l) {
        this.listener = l;
    }

    public void updateData(List<RepoModuleInfo> newModules) {
        this.modules = newModules != null ? newModules : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RepoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
            .inflate(R.layout.item_repo_module, parent, false);
        return new RepoViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RepoViewHolder h, int position) {
        RepoModuleInfo info = modules.get(position);

        h.tvName.setText(info.getDisplayName());

        if (info.module != null && info.module.version != null) {
            h.tvVersion.setText("v" + info.module.version);
            h.tvVersion.setVisibility(View.VISIBLE);
        } else {
            h.tvVersion.setVisibility(View.GONE);
        }

        String pkg = info.module != null ? info.module.packageName : null;
        h.tvPackage.setText(pkg != null ? pkg : "");

        if (info.author != null && !info.author.isEmpty()) {
            h.tvAuthor.setText("by " + info.author);
            h.tvAuthor.setVisibility(View.VISIBLE);
        } else {
            h.tvAuthor.setVisibility(View.GONE);
        }

        if (info.description != null && !info.description.isEmpty()) {
            h.tvDescription.setText(info.description);
            h.tvDescription.setVisibility(View.VISIBLE);
        } else {
            h.tvDescription.setVisibility(View.GONE);
        }

        if (info.hasReadme()) {
            h.tvReadmeBadge.setVisibility(View.VISIBLE);
        } else {
            h.tvReadmeBadge.setVisibility(View.GONE);
        }

        Drawable icon = IconResolver.resolve(context,
            info.module != null ? info.module.packageName : null,
            info.module != null ? info.module.apkPath : null);
        if (icon != null) h.ivIcon.setImageDrawable(icon);
        else h.ivIcon.setImageResource(R.drawable.ic_module);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onModuleClick(info);
        });
    }

    @Override
    public int getItemCount() {
        return modules.size();
    }

    static class RepoViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName, tvVersion, tvPackage, tvAuthor, tvDescription, tvReadmeBadge;

        RepoViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivRepoModuleIcon);
            tvName = itemView.findViewById(R.id.tvRepoModuleName);
            tvVersion = itemView.findViewById(R.id.tvRepoModuleVersion);
            tvPackage = itemView.findViewById(R.id.tvRepoModulePackage);
            tvAuthor = itemView.findViewById(R.id.tvRepoModuleAuthor);
            tvDescription = itemView.findViewById(R.id.tvRepoModuleDescription);
            tvReadmeBadge = itemView.findViewById(R.id.tvRepoReadmeBadge);
        }
    }
}