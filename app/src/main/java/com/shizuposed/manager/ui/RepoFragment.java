package com.shizuposed.manager.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.shizuposed.manager.R;
import com.shizuposed.manager.utils.Logger;

/**
 * RepoFragment
 *
 * Placeholder for the module repository tab. When the repository backend
 * ships, this fragment will host a list of browsable modules with
 * install / update actions, similar to LSPosed's Repo tab.
 *
 * For now it displays a "Not yet implemented" message so the tab is
 * discoverable in the bottom navigation without suggesting that a
 * feature is broken.
 */
public class RepoFragment extends Fragment {

    private Logger logger;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_repo, container, false);
        logger = Logger.getInstance(requireContext());
        logger.d("RepoFragment opened (not yet implemented)");
        return view;
    }

    /** Called by MainActivity.refreshAll(). No-op for now. */
    public void refresh() {
        // Nothing to refresh yet.
    }
}