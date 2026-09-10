package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Last-resort backend. Accepts every hook and does nothing.
 * Prevents findAndHookMethod from throwing when every real backend
 * has failed for a particular method.
 */
public final class NoopBackend implements HookDispatcher.Backend {

    @Override
    public String name() { return "Noop"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        log("(no-op) hook ignored: "
            + original.getDeclaringClass().getName() + "." + original.getName());
        return true;   // we "succeed" by doing nothing
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.d("[NoopBackend] " + msg); return; }
        } catch (Throwable ignored) {}
    }
}