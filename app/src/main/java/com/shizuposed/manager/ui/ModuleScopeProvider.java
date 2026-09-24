package com.shizuposed.manager.ui;

import android.content.Context;

import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * ScopeProvider backed by ModuleLoader, for normal modules.
 *
 * Empty scope means "nothing selected," matching the historical
 * behavior of per-module scope.
 */
public final class ModuleScopeProvider implements ScopeProvider {

    private final Context context;
    private final ModuleInfo module;

    public ModuleScopeProvider(Context context, ModuleInfo module) {
        this.context = context.getApplicationContext();
        this.module = module;
    }

    @Override
    public String getDisplayName() {
        if (module == null) return "Module";
        return module.name != null ? module.name : module.packageName;
    }

    @Override
    public String getModulePackageName() {
        return module != null ? module.packageName : null;
    }

    @Override
    public Set<String> getScope() {
        if (module == null || module.hookedApps == null) return new HashSet<>();
        return new HashSet<>(module.hookedApps);
    }

    @Override
    public void setScope(Set<String> scope) {
        if (module == null) return;
        module.hookedApps = (scope != null) ? new HashSet<>(scope) : new HashSet<>();
        ModuleLoader.getInstance(context).saveModule(module);
    }

    @Override
    public boolean emptyMeansAllApps() {
        return false;
    }
}