package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Pine backend for HookDispatcher.
 * Uses top.canyie.pine.Pine.
 */
public final class PineBackend implements HookDispatcher.Backend {

    private volatile boolean available = false;
    private volatile boolean checked = false;

    @Override
    public String name() { return "Pine"; }

    @Override
    public boolean isAvailable() {
        if (checked) return available;
        checked = true;
        try {
            Pine.ensureInitialized();
            available = Pine.isInitialized();
            log("Pine availability: " + available);
        } catch (Throwable t) {
            log("Pine not available: " + t);
            available = false;
        }
        return available;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) throws Throwable {
        if (!available) return false;

        try {
            original.setAccessible(true);
        } catch (Throwable ignored) {}

        Pine.hook(original, new MethodHook() {

            @Override
            public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                XC_MethodHook.MethodHookParam mp = new XC_MethodHook.MethodHookParam();
                mp.thisObject = callFrame.thisObject;
                mp.args = callFrame.args;

                callback.callBeforeHookedMethod(mp);

                if (mp.hasThrowable) throw mp.getThrowable();
                if (mp.hasResult)   callFrame.setResult(mp.getResult());
            }

            @Override
            public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                XC_MethodHook.MethodHookParam mp = new XC_MethodHook.MethodHookParam();
                mp.thisObject = callFrame.thisObject;
                mp.args = callFrame.args;
                mp.setResult(callFrame.getResult());

                callback.callAfterHookedMethod(mp);

                if (mp.hasThrowable) throw mp.getThrowable();
                if (mp.hasResult)   callFrame.setResult(mp.getResult());
            }
        });

        return true;
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[PineBackend] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[PineBackend] " + msg);
    }
}