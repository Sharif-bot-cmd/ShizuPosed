package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Last-resort backend. Accepts every hook and does nothing.
 *
 * Returning true here means findAndHookMethod will not throw, but the
 * module's callback will never run. That is a silent failure, so we
 * log at WARN to make it visible in logcat. If you see NoopBackend in
 * the logs, the hook did not install — investigate the earlier
 * backends in the chain.
 */
public final class NoopBackend implements HookDispatcher.Backend {

    private static final String TAG = "NoopBackend";

    @Override
    public String name() { return "Noop"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (original == null) {
            CompatLog.w(TAG, "hook(null) ignored", null);
            return true;
        }
        CompatLog.w(TAG, "(no-op) hook ignored: "
                + original.getDeclaringClass().getName() + "." + original.getName(),
                null);
        return true;
    }
}