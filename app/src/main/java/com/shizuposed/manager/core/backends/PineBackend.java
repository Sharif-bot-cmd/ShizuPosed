package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.AndroidCompat;
import com.shizuposed.manager.core.compat.HiddenApiBypass;
import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Pine backend for HookDispatcher.
 *
 * Wraps top.canyie.pine.Pine. Availability is probed once via
 * Pine.ensureInitialized(); after that the flag is cached.
 *
 * Android 16+ compatibility is handled by two things:
 *   1. Access to the target Method is obtained through
 *      HiddenApiBypass.forceAccessible(...), which tries multiple
 *      strategies before giving up.
 *   2. If Pine's own isInitialized() returns false for any reason
 *      (missing .so, wrong ABI, unsupported ART layout), the
 *      dispatcher falls through to the next backend.
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
            // Log the effective SDK once so cross-version issues are
            // diagnosable from the log alone.
            log("Detected " + AndroidCompat.describe()
                + ", preRelease=" + AndroidCompat.IS_PRE_RELEASE);

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

        // Force-access via the compat layer. On API 28+ this handles the
        // hidden-API restrictions; on older versions it's a no-op
        // wrapper around setAccessible(true).
        boolean accessible = HiddenApiBypass.forceAccessible(original);
        if (!accessible) {
            log("Cannot make " + original.getDeclaringClass().getName()
                + "." + original.getName() + " accessible — refusing to hook");
            return false;
        }

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