package com.shizuposed.manager.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.shizuposed.manager.ui.HomeFragment;
import com.shizuposed.manager.ui.LogsFragment;
import com.shizuposed.manager.ui.ModulesFragment;
import com.shizuposed.manager.ui.RepoFragment;
import com.shizuposed.manager.ui.SettingsFragment;

import java.util.ArrayList;
import java.util.List;

public class MainPagerAdapter extends FragmentStateAdapter {

    private static final int PAGE_COUNT = 5;

    private final List<Fragment> fragments = new ArrayList<>(PAGE_COUNT);

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
        fragments.add(new HomeFragment());      // 0
        fragments.add(new ModulesFragment());   // 1
        fragments.add(new RepoFragment());      // 2
        fragments.add(new LogsFragment());      // 3
        fragments.add(new SettingsFragment());  // 4
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position >= 0 && position < fragments.size()) {
            return fragments.get(position);
        }
        return new HomeFragment();
    }

    @Override
    public int getItemCount() {
        return PAGE_COUNT;
    }

    public Fragment getFragment(int position) {
        if (position >= 0 && position < fragments.size()) {
            return fragments.get(position);
        }
        return null;
    }
}