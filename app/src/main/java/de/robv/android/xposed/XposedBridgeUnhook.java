package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Concrete implementation of IXUnhook returned by the shim's hook
 * install methods.
 *
 * Knows which backend installed the hook so unhook() can either
 * reverse the install (when supported) or log a clear warning.
 *
 * Holds a reference to the callback and the hooked member, both of
 * which are returned by getCallback() and getHookedMethod()
 * unconditionally.
 */
public final class XposedBridgeUnhook<T extends XC_MethodHook>
        implements IXUnhook<T> {

    private static final String TAG = "Xposed";

    private final T callback;
    private final Member hookedMember;
    private final String backendName;
    private final Runnable reverseAction;   // nullable

    /**
     * @param callback       the callback installed
     * @param hookedMember   the Method or Constructor that was hooked
     * @param backendName    name of the backend that installed the
     *                       hook, for logging. May be null.
     * @param reverseAction  a runnable that undoes the install, or
     *                       null if the backend can't reverse. When
     *                       null, unhook() logs and returns.
     */
    public XposedBridgeUnhook(T callback,
                              Member hookedMember,
                              String backendName,
                              Runnable reverseAction) {
        this.callback = callback;
        this.hookedMember = hookedMember;
        this.backendName = backendName != null ? backendName : "unknown";
        this.reverseAction = reverseAction;
    }

    @Override
    public void unhook() {
        if (reverseAction != null) {
            try {
                reverseAction.run();
            } catch (Throwable t) {
                android.util.Log.w(TAG, "unhook threw in " + backendName
                    + ": " + t);
            }
            return;
        }
        android.util.Log.w(TAG, "unhook: backend " + backendName
            + " does not support reverse; hook remains installed");
    }

    @Override
    public T getCallback() {
        return callback;
    }

    @Override
    public Member getHookedMethod() {
        return hookedMember;
    }

    public String getBackendName() {
        return backendName;
    }
}