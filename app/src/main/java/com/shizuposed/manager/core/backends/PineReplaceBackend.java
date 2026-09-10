package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Pine in REPLACEMENT hook mode. Same as PineBackend but forces
 * Pine to use REPLACEMENT strategy instead of the default.
 */
public final class PineReplaceBackend implements HookDispatcher.Backend {

    private volatile boolean available = false;
    private volatile boolean checked = false;
    private int savedHookMode = -1;

    @Override
    public String name() { return "Pine (REPLACEMENT)"; }

    @Override
    public boolean isAvailable() {
        if (checked) return available;
        checked = true;
        try {
            Pine.ensureInitialized();
            if (!Pine.isInitialized()) { available = false; return false; }
            if (Pine.getHookMode() != Pine.HookMode.REPLACEMENT) {
                savedHookMode = Pine.getHookMode();
                Pine.setHookMode(Pine.HookMode.REPLACEMENT);
            }
            available = true;
            log("Pine REPLACEMENT available");
        } catch (Throwable t) {
            log("Pine REPLACEMENT not available: " + t);
            available = false;
        }
        return available;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) throws Throwable {
        if (!available) return false;

        // Save/restore the current hook mode around this call so we don't
        // leak REPLACEMENT into concurrent primary-mode hooks.
        int prev = Pine.getHookMode();
        try {
            Pine.setHookMode(Pine.HookMode.REPLACEMENT);
            return new PineBackend().hook(original, callback);
        } finally {
            Pine.setHookMode(prev);
        }
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[PineReplace] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[PineReplace] " + msg);
    }
}