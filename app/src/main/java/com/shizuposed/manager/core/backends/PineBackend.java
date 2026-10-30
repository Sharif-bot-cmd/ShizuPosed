package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.AndroidCompat;
import com.shizuposed.manager.core.compat.CompatLog;
import com.shizuposed.manager.core.compat.HiddenApiBypass;

import java.lang.reflect.Constructor;
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
 *
 * ARGUMENT CONTRACT
 * -----------------
 * Pine's MethodHook gives us a CallFrame with `thisObject` and
 * `args`, and lets us read the result or throwable after the
 * original runs. The adapter translates between Pine's CallFrame
 * and Xposed's MethodHookParam, in both directions:
 *
 *   • Writes param.method so modules can identify the hooked
 *     member. Many modules read param.method.getName() or check
 *     its declaring class. A null param.method NPEs them.
 *
 *   • Reads thisObject / args from the frame into param.
 *
 *   • After beforeHookedMethod runs, writes back any mutations
 *     the callback made to param.args, param.thisObject, or the
 *     result/throwable slots. Without this, an
 *     XC_MethodHook that mutates args has no effect on the
 *     actual invocation.
 *
 *   • Same for afterHookedMethod — a callback that replaces the
 *     result post-invocation needs its write honored.
 *
 * CONSTRUCTOR HOOKS
 * -----------------
 * Pine supports constructor hooks via Pine.hook(Constructor,
 * MethodHook). The CallFrame for a constructor has a null
 * thisObject during beforeCall (the instance doesn't exist yet),
 * and a non-null thisObject during afterCall. The adapter
 * handles that: it doesn't read thisObject from a constructor's
 * beforeCall (it's always null), and it doesn't write thisObject
 * back in beforeCall (it can't be changed at that point).
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

    // ═════════════════════════════════════════════════════════════
    // METHOD HOOK
    // ═════════════════════════════════════════════════════════════

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

                        // Populate the fields a module reads.
                        mp.method = original;
                        mp.thisObject = callFrame.thisObject;
                        mp.args = callFrame.args;

                        callback.callBeforeHookedMethod(mp);

                        // Propagate the callback's writes back to
                        // Pine before the original runs.
                        if (mp.hasThrowable) {
                            throw mp.getThrowable();
                        }
                        if (mp.hasResult) {
                            callFrame.setResult(mp.getResult());
                            return;
                        }
                        if (mp.args != null) {
                            callFrame.args = mp.args;
                        }
                        // A hook can change the receiver. Pine
                        // honors this before the original runs.
                        if (mp.thisObject != null) {
                            callFrame.thisObject = mp.thisObject;
                        }
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                        XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();

                        mp.method = original;
                        mp.thisObject = callFrame.thisObject;
                        mp.args = callFrame.args;

                        if (callFrame.hasThrowable()) {
                            mp.setThrowable(callFrame.getThrowable());
                        } else {
                            mp.setResult(callFrame.getResult());
                        }

                        callback.callAfterHookedMethod(mp);

                        // Propagate post-invocation mutations.
                        if (mp.hasThrowable) {
                            throw mp.getThrowable();
                        }
                        if (mp.hasResult) {
                            callFrame.setResult(mp.getResult());
                        }
                        if (mp.args != null) {
                            callFrame.args = mp.args;
                        }
                        if (mp.thisObject != null) {
                            callFrame.thisObject = mp.thisObject;                                            
                        }
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

    // ═════════════════════════════════════════════════════════════
    // CONSTRUCTOR HOOK
    // ═════════════════════════════════════════════════════════════

    @Override
    public boolean hookConstructor(Constructor<?> original,
                                    XC_MethodHook callback) {
        if (original == null || callback == null) return false;
        if (!isAvailable()) return false;

        if (!HiddenApiBypass.forceAccessible(original)) {
            CompatLog.w(TAG, "Cannot make constructor "
                    + original.getDeclaringClass().getName()
                    + " accessible — refusing to hook", null);
            return false;
        }

        try {
            synchronized (PineModeLock.INSTANCE) {
                Pine.hook(original, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) throws Throwable {
                        XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();

                        // For constructors, thisObject is null in
                        // beforeCall — the instance doesn't exist
                        // yet. Don't read it (it would be a lie).
                        mp.method = original;
                        mp.thisObject = null;
                        mp.args = callFrame.args;

                        callback.callBeforeHookedMethod(mp);

                        if (mp.hasThrowable) {
                            throw mp.getThrowable();
                        }
                        if (mp.hasResult) {
                            // A constructor hook that sets a result
                            // is unusual — Pine treats it as a
                            // synthetic instance. Honor it: the
                            // caller sees the value the hook
                            // returned.
                            callFrame.setResult(mp.getResult());
                            return;
                        }
                        if (mp.args != null) {
                            callFrame.args = mp.args;
                        }
                        // Do NOT write thisObject here — Pine
                        // ignores it before the constructor runs.
                    }

                    @Override
                    public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                        XC_MethodHook.MethodHookParam mp =
                                new XC_MethodHook.MethodHookParam();

                        mp.method = original;
                        mp.thisObject = callFrame.thisObject;
                        mp.args = callFrame.args;

                        callback.callAfterHookedMethod(mp);

                        if (mp.hasThrowable) {
                            throw mp.getThrowable();
                        }
                        if (mp.hasResult) {
                            callFrame.setResult(mp.getResult());
                        }
                        if (mp.args != null) {
                            callFrame.args = mp.args;
                        }
                    }
                });
            }
            return true;
        } catch (Throwable t) {
            CompatLog.w(TAG, "Pine.hook constructor failed for "
                    + original.getDeclaringClass().getName(), t);
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