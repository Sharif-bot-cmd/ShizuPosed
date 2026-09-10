package com.shizuposed.manager.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.shizuposed.manager.ui.HomeFragment;
import com.shizuposed.manager.ui.LogsFragment;
import com.shizuposed.manager.ui.ModulesFragment;
import com.shizuposed.manager.ui.SettingsFragment;

import java.util.HashMap;
import java.util.Map;

public class MainPagerAdapter extends FragmentStateAdapter {
    private static final int NUM_PAGES = 4;
    private Map<Integer, Fragment> fragmentCache = new HashMap<>();
    
    public MainPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }
    
    @NonNull
    @Override
    public Fragment createFragment(int position) {
        Fragment fragment;
        switch (position) {
            case 0:
                fragment = new HomeFragment();
                break;
            case 1:
                fragment = new ModulesFragment();
                break;
            case 2:
                fragment = new LogsFragment();
                break;
            case 3:
                fragment = new SettingsFragment();
                break;
            default:
                fragment = new HomeFragment();
                break;
        }
        fragmentCache.put(position, fragment);
        return fragment;
    }
    
    @Override
    public int getItemCount() {
        return NUM_PAGES;
    }
    
    public Fragment getFragment(int position) {
        return fragmentCache.get(position);
    }
}
