package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.AndroidCompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * CallSiteBackend
 *
 * A HookDispatcher backend that intercepts calls to a target method
 * by patching ART's interpreter dispatch, rather than by replacing
 * the method's ArtMethod entry point.
 *
 * WHY THIS EXISTS
 * ---------------
 * Some target apps inspect ArtMethod entries to detect hooking. The
 * standard backends (Pine, Amiru, Native) modify the entry pointer,
 * which those targets can see. This backend leaves the entry pointer
 * alone and patches the interpreter's dispatch table instead.
 *
 * COVERAGE
 * --------
 * Interpreted execution only. Once ART JIT-compiles a method, the
 * interpreter is bypassed and this backend has no effect.
 *
 * INTERFACE CONTRACT
 * ------------------
 * HookDispatcher.Backend.hook and .hookConstructor return void.
 * "Success" is not communicated by the return value; the dispatcher
 * tracks which backend installed a hook through its own state.
 * This backend installs when it can and does nothing when it can't.
 */
public class CallSiteBackend implements HookDispatcher.Backend {

    private static final String TAG = "CallSiteBackend";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;
    private static volatile String  sUnavailableReason = "not loaded";

    public static synchronized boolean loadLibrary(String libDir) {
        if (sLoaded) return sAvailable;
        sLoaded = true;

        if (!AndroidCompat.supportsInterpreterSymbolAnchor()) {
            sUnavailableReason = "Android " + AndroidCompat.SDK
                + " not supported (needs >= 29)";
            android.util.Log.w(TAG, sUnavailableReason);
            return false;
        }

        if (libDir == null || libDir.isEmpty()) {
            sUnavailableReason = "no lib dir provided";
            return false;
        }

        try {
            System.load(libDir + "/libcallsite.so");
        } catch (Throwable t) {
            sUnavailableReason = "load failed: " + t.getMessage();
            android.util.Log.w(TAG, sUnavailableReason);
            return false;
        }

        try {
            sAvailable = nativeInit();
            sUnavailableReason = sAvailable ? null : "nativeInit returned false";
        } catch (Throwable t) {
            sAvailable = false;
            sUnavailableReason = "nativeInit threw: " + t.getMessage();
            android.util.Log.w(TAG, sUnavailableReason);
        }

        android.util.Log.i(TAG, "CallSiteBackend: loaded=" + sAvailable
            + " reason=" + (sUnavailableReason == null ? "ok" : sUnavailableReason)
            + " art=" + AndroidCompat.artFamilyName());
        return sAvailable;
    }

    public static boolean isLibraryLoaded() { return sAvailable; }
    public static String getUnavailableReason() { return sUnavailableReason; }

    public static String describe() {
        if (!sLoaded) return "callsite: not loaded";
        if (!sAvailable) return "callsite: unavailable (" + sUnavailableReason + ")";
        try {
            return nativeDescribe();
        } catch (Throwable t) {
            return "callsite: describe failed (" + t.getMessage() + ")";
        }
    }

    // ═════════════════════════════════════════════════════════════
    // HookDispatcher.Backend
    // ═════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return "CallSite";
    }

    @Override
    public boolean isAvailable() {
        return sAvailable;
    }

    @Override
    public boolean hook(Method method, XC_MethodHook callback) {
        if (!sAvailable || method == null || callback == null) return false;
        try {
            int callbackId = CallSiteCallbackRegistry.register(callback);
            boolean ok = nativeInstallHook(method, callbackId);
            if (!ok) {
                CallSiteCallbackRegistry.unregister(callbackId);
                return false;
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "hook failed: " + t.getMessage());
            return false;
        }
    }

    @Override
    public boolean hookConstructor(Constructor<?> ctor, XC_MethodHook callback) {
        // Not supported by this backend. Return false so the
        // dispatcher moves on to a backend that can handle
        // constructors.
        return false;
    }

    // ═════════════════════════════════════════════════════════════
    // JNI
    // ═════════════════════════════════════════════════════════════

    private static native boolean nativeInit();
    private static native boolean nativeInstallHook(Method method, int callbackId);
    private static native String  nativeDescribe();
}