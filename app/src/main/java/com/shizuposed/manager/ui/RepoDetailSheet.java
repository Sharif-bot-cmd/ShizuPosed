package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.shizuposed.manager.R;
import com.shizuposed.manager.adapter.IconResolver;
import com.shizuposed.manager.model.RepoModuleInfo;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.MarkdownRenderer;

/**
 * Detail sheet for a module in the Repo tab.
 *
 * Shows metadata, quick actions, and a tabbed content area with
 * README and Details views.
 *
 * The README, if present, is rendered from Markdown to Spannable.
 * Links inside it are clickable via LinkMovementMethod, which
 * forwards to the system browser.
 */
public class RepoDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_PACKAGE = "packageName";

    private RepoModuleInfo info;
    private Logger logger;
    private volatile boolean viewReady = false;

    public static RepoDetailSheet newInstance(RepoModuleInfo info) {
        RepoDetailSheet s = new RepoDetailSheet();
        Bundle b = new Bundle();
        b.putString(ARG_PACKAGE,
            info != null && info.module != null ? info.module.packageName : null);
        s.setArguments(b);
        // The full RepoModuleInfo is held statically to avoid
        // Serializable round-trips through the Bundle. Set before
        // show() by the fragment.
        s.pendingInfo = info;
        return s;
    }

    private RepoModuleInfo pendingInfo;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
        if (info == null) info = pendingInfo;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_repo_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        viewReady = true;

        if (info == null || info.module == null) {
            dismissAllowingStateLoss();
            return;
        }

        bindHeader(v);
        bindActions(v);
        bindTabs(v);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
    }

    // ═════════════════════════════════════════════════════════════
    // HEADER
    // ═════════════════════════════════════════════════════════════

    private void bindHeader(View v) {
        ImageView icon = v.findViewById(R.id.ivRepoDetailIcon);
        TextView name = v.findViewById(R.id.tvRepoDetailName);
        TextView sub = v.findViewById(R.id.tvRepoDetailSub);

        if (icon != null) {
            android.graphics.drawable.Drawable d = IconResolver.resolve(
                requireContext(), info.module.packageName, info.module.apkPath);
            if (d != null) icon.setImageDrawable(d);
            else icon.setImageResource(R.drawable.ic_module);
        }
        if (name != null) name.setText(info.getDisplayName());

        if (sub != null) {
            StringBuilder sb = new StringBuilder();
            if (info.module.version != null) sb.append("v").append(info.module.version);
            if (info.author != null && !info.author.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("by ").append(info.author);
            }
            sub.setText(sb.toString());
            sub.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIONS
    // ═════════════════════════════════════════════════════════════

    private void bindActions(View v) {
        MaterialButton btnHomepage = v.findViewById(R.id.btnRepoOpenHomepage);
        MaterialButton btnSupport = v.findViewById(R.id.btnRepoOpenSupport);

        if (btnHomepage != null) {
            if (info.homepage != null && !info.homepage.isEmpty()) {
                btnHomepage.setVisibility(View.VISIBLE);
                btnHomepage.setOnClickListener(x ->
                    openUrl(info.homepage));
            } else {
                btnHomepage.setVisibility(View.GONE);
            }
        }

        if (btnSupport != null) {
            if (info.supportUrl != null && !info.supportUrl.isEmpty()) {
                btnSupport.setVisibility(View.VISIBLE);
                btnSupport.setOnClickListener(x ->
                    openUrl(info.supportUrl));
            } else {
                btnSupport.setVisibility(View.GONE);
            }
        }
    }

    private void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                "Could not open link", Toast.LENGTH_SHORT).show();
            if (logger != null) logger.w("openUrl(" + url + ") failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TABS
    // ═════════════════════════════════════════════════════════════

    private void bindTabs(View v) {
        TabLayout tabs = v.findViewById(R.id.tlRepoDetailTabs);
        View readmeContainer = v.findViewById(R.id.svRepoReadmeContainer);
        View detailsContainer = v.findViewById(R.id.svRepoDetailsContainer);

        bindReadme(v);
        bindDetails(v);

        if (tabs != null) {
            tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    int pos = tab.getPosition();
                    if (readmeContainer != null) {
                        readmeContainer.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                    }
                    if (detailsContainer != null) {
                        detailsContainer.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                    }
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });

            // Default: README tab if the module has one, otherwise
            // Details. Nothing is worse than opening a detail sheet
            // onto an empty README tab.
            int defaultTab = info.hasReadme() ? 0 : 1;
            TabLayout.Tab tab = tabs.getTabAt(defaultTab);
            if (tab != null) tab.select();
        }
    }

    private void bindReadme(View v) {
        TextView readme = v.findViewById(R.id.tvRepoReadmeContent);
        TextView empty = v.findViewById(R.id.tvRepoReadmeEmpty);
        if (readme == null) return;

        if (info.hasReadme()) {
            CharSequence rendered = MarkdownRenderer.render(requireContext(), info.readme);
            readme.setText(rendered);
            readme.setMovementMethod(LinkMovementMethod.getInstance());
            readme.setVisibility(View.VISIBLE);
            if (empty != null) empty.setVisibility(View.GONE);
        } else {
            readme.setVisibility(View.GONE);
            if (empty != null) {
                empty.setText("No README bundled in this module.\n\n"
                    + "Module authors can include one by adding\n"
                    + "assets/README.md to the module APK.");
                empty.setVisibility(View.VISIBLE);
            }
        }
    }

    private void bindDetails(View v) {
        TextView tv = v.findViewById(R.id.tvRepoDetailsContent);
        if (tv == null) return;

        StringBuilder sb = new StringBuilder();
        append(sb, "Package", info.module.packageName);
        append(sb, "Version", info.module.version);
        append(sb, "Author", info.author);
        append(sb, "Entry point", info.module.xposedInit);
        append(sb, "Enabled", info.module.enabled ? "Yes" : "No");
        append(sb, "Scope", info.module.hookedApps != null
            ? info.module.hookedApps.size() + " app(s)" : "none");
        append(sb, "Recommended scope", info.module.recommendedApps != null
            && !info.module.recommendedApps.isEmpty()
            ? info.module.recommendedApps.size() + " app(s)" : null);
        append(sb, "APK path", info.module.apkPath);
        append(sb, "Has UI", info.module.hasUi ? "Yes" : "No");
        if (info.readmeSource != null) {
            append(sb, "README source", info.readmeSource);
        }
        append(sb, "Homepage", info.homepage);
        append(sb, "Support", info.supportUrl);

        tv.setText(sb.toString());
    }

    private void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(key).append(":  ").append(value).append('\n');
    }
}