// IModuleManager.aidl
// Location: app/src/main/aidl/com/shizuposed/manager/IModuleManager.aidl

package com.shizuposed.manager;

import com.shizuposed.manager.model.ModuleInfo;

interface IModuleManager {
    List<ModuleInfo> getModules();
    ModuleInfo getModule(String packageName);
    boolean installModule(in ModuleInfo module);
    boolean uninstallModule(String packageName);
    boolean enableModule(String packageName, boolean enable);
    boolean isModuleEnabled(String packageName);
    int getHookedProcessCount();
    int getHookedAppCount(String modulePackage);
}