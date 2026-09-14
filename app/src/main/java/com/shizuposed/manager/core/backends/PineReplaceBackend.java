package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import top.canyie.pine.Pine;

/**
 * Pine in REPLACEMENT hook mode. Same as PineBackend but forces
 * Pine's global hook mode to REPLACEMENT for the duration of the
 * install call.
 *
 * The mode save/restore is guarded by PineBackend.PineModeLock so a
 * concurrent PineBackend.hook on another thread does not see
 * REPLACEMENT mode by accident.
 */
public final class PineReplaceBackend implements HookDispatcher.Backend {

    private static final String TAG = "PineReplace";

    private volatile boolean available;
    private volatile boolean checked;

    @Override
    public String name() { return "Pine (REPLACEMENT)"; }

    @Override
    public boolean isAvailable() {
        if (checked) return available;
        synchronized (this) {
            if (checked) return available;
            checked = true;
            try {
                if (!PineBackend.INSTANCE.isAvailable()) {
                    available = false;
                    return false;
                }
                available = true;
                CompatLog.d(TAG, "Pine REPLACEMENT available");
            } catch (Throwable t) {
                CompatLog.w(TAG, "Pine REPLACEMENT not available", t);
                available = false;
            }
            return available;
        }
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;
        if (!isAvailable()) return false;

        synchronized (PineBackend.PineModeLock.INSTANCE) {
            int prev;
            try {
                prev = Pine.getHookMode();
            } catch (Throwable t) {
                CompatLog.w(TAG, "Pine.getHookMode failed", t);
                return false;
            }
            if (prev == Pine.HookMode.REPLACEMENT) {
                // Already in REPLACEMENT; PineBackend's shared instance
                // will use REPLACEMENT directly.
                return PineBackend.INSTANCE.hook(original, callback);
            }
            try {
                Pine.setHookMode(Pine.HookMode.REPLACEMENT);
                return PineBackend.INSTANCE.hook(original, callback);
            } catch (Throwable t) {
                CompatLog.w(TAG, "REPLACEMENT hook failed", t);
                return false;
            } finally {
                try {
                    Pine.setHookMode(prev);
                } catch (Throwable t) {
                    CompatLog.w(TAG, "Failed to restore Pine hook mode", t);
                }
            }
        }
    }
}