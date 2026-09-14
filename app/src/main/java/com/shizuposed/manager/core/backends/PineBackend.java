package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.AndroidCompat;
import com.shizuposed.manager.core.compat.CompatLog;
import com.shizuposed.manager.core.compat.HiddenApiBypass;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Pine backend for HookDispatcher.
 *
 * Wraps top.canyie.pine.Pine. Availability is probed once via
 * Pine.ensureInitialized(); the result is cached.
 *
 * Threading: Pine.hook() may be called from arbitrary threads. The
 * global Pine hook mode is guarded by PineModeLock so PineBackend and
 * PineReplaceBackend cannot stomp on each other's mode.
 */
public final class PineBackend implements HookDispatcher.Backend {

    private static final String TAG = "PineBackend";

    /** Shared instance so the availability cache is actually shared. */
    static final PineBackend INSTANCE = new PineBackend();

    private volatile boolean available;
    private volatile boolean checked;

    @Override
    public String name() { return "Pine"; }

    @Override
    public boolean isAvailable() {
        if (checked) return available;
        synchronized (this) {
            if (checked) return available;
            checked = true;
            try {
                CompatLog.d(TAG, "Detected " + AndroidCompat.describe()
                        + ", preRelease=" + AndroidCompat.IS_PRE_RELEASE);
                Pine.ensureInitialized();
                available = Pine.isInitialized();
                CompatLog.d(TAG, "Pine availability: " + available);
            } catch (Throwable t) {
                CompatLog.w(TAG, "Pine not available", t);
                available = false;
            }
            return available;
        }
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;
        if (!isAvailable()) return false;

        if (!HiddenApiBypass.forceAccessible(original)) {
            CompatLog.w(TAG, "Cannot make " + original.getDeclaringClass().getName()
                    + "." + original.getName() + " accessible — refusing to hook", null);
            return false;
        }

        try {
            synchronized (PineModeLock.INSTANCE) {
                Pine.hook(original, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                        XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();
                        mp.thisObject = callFrame.thisObject;
                        mp.args = callFrame.args;

                        callback.callBeforeHookedMethod(mp);

                        if (mp.hasThrowable) throw mp.getThrowable();
                        if (mp.hasResult)   callFrame.setResult(mp.getResult());
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                        XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();
                        mp.thisObject = callFrame.thisObject;
                        mp.args = callFrame.args;
                        mp.setResult(callFrame.getResult());

                        callback.callAfterHookedMethod(mp);

                        if (mp.hasThrowable) throw mp.getThrowable();
                        if (mp.hasResult)   callFrame.setResult(mp.getResult());
                    }
                });
            }
            return true;
        } catch (Throwable t) {
            CompatLog.w(TAG, "Pine.hook failed for "
                    + original.getDeclaringClass().getName() + "."
                    + original.getName(), t);
            return false;
        }
    }

    /**
     * Global lock around Pine's mutable hook mode. Shared with
     * PineReplaceBackend.
     */
    static final class PineModeLock {
        static final Object INSTANCE = new Object();
    }
}