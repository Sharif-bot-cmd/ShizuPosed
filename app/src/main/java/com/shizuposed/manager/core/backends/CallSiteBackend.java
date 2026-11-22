package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.NativeBridge;
import com.shizuposed.manager.core.compat.AndroidCompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * CallSiteBackend
 *
 * A HookDispatcher backend that intercepts calls to a target method
 * by patching ART's interpreter dispatch table, rather than by
 * replacing the method's ArtMethod entry point at hook-install time.
 *
 * WHY THIS EXISTS
 * ---------------
 * Some target apps inspect ArtMethod entries to detect hooking. The
 * standard backends (Pine, Amiru, Native) modify the entry pointer,
 * which those targets can see. This backend leaves the entry pointer
 * alone until the first interpreted invocation, and patches the
 * interpreter's dispatch table instead.
 *
 * HOW IT WORKS
 * ------------
 * CallSite does NOT have its own callback dispatch path. It is a
 * deferred trigger in front of the Native backend:
 *
 *   1. hook(Method, XC_MethodHook) registers the callback with the
 *      Native backend via NativeBridge.nRegisterCallback. That
 *      builds the callback stub and records (ArtMethod, slot).
 *
 *   2. hook() then calls nativeInstallHook, which arms CallSite's
 *      interpreter-slot intercept for this ArtMethod. The ArtMethod
 *      entry point is still untouched at this point.
 *
 *   3. On the first interpreted invocation, the trampoline calls
 *      shizuposed_lazy_install_artmethod, which performs the
 *      entry-point replacement exactly as nHookArtMethod would.
 *      After that, the callback stub from step 1 runs, routing
 *      through szp_dispatch_c — the SAME dispatcher Native hooks
 *      use. There is exactly one dispatch mechanism, not two.
 *
 *   4. Subsequent calls (interpreted or JIT-compiled) go through
 *      the entry-point replacement. CallSite's job is done.
 *
 * COVERAGE
 * --------
 * Detection-evasion: the ArtMethod entry pointer is untouched until
 * the first interpreted invocation of a hooked method. After that,
 * the Native backend owns the hook and JIT-compiled calls are also
 * intercepted.
 *
 * INTERFACE CONTRACT
 * ------------------
 * HookDispatcher.Backend.hook and .hookConstructor return void.
 * "Success" is communicated out of band; this backend returns true
 * when it has armed the intercept and registered the callback, and
 * false when it could not.
 */
public class CallSiteBackend implements HookDispatcher.Backend {

    private static final String TAG = "CallSiteBackend";

    private static volatile boolean sLoaded = false;
    private static volatile boolean sAvailable = false;
    private static volatile String  sUnavailableReason = "not loaded";

    /**
     * Set true once we have confirmed the native side can see
     * shizuposed_lazy_install_artmethod. Re-checked lazily on hook()
     * because CallSite may be initialized before libshizuposed.so is
     * loaded into the process; NativeBridge.nIsAvailable() tells us
     * the latter is ready.
     */
    private static volatile boolean sBridgeLinked = false;

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

        /*
         * nativeInit resolves shizuposed_lazy_install_artmethod via
         * dlsym(RTLD_DEFAULT, ...). If libshizuposed.so wasn't
         * loaded yet, the resolve fails and installs are no-ops
         * until we retry. Record the current state; hook() and
         * isAvailable() retry if it's false.
         */
        sBridgeLinked = nativeBridgeLinked();

        android.util.Log.i(TAG, "CallSiteBackend: loaded=" + sAvailable
            + " bridge=" + sBridgeLinked
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
        if (!sAvailable) return false;

        /*
         * CallSite is non-functional without the Native backend:
         * there is no callback dispatcher of our own. Require both
         * the NativeBridge surface (so nRegisterCallback works) and
         * the C-side bridge symbol (so the deferred install can
         * fire).
         */
        if (!NativeBridge.nIsAvailable()) {
            sBridgeLinked = false;
            return false;
        }
        if (!sBridgeLinked) {
            sBridgeLinked = nativeBridgeLinked();
        }
        if (!sBridgeLinked) return false;

        try {
            String d = nativeDescribe();
            return d != null && d.contains("active");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hook(Method method, XC_MethodHook callback) {
        if (!sAvailable || method == null || callback == null) return false;

        /*
         * Re-check the C-side bridge symbol. If libshizuposed.so
         * has loaded since our loadLibrary() ran, this succeeds
         * now and the intercept will actually fire.
         */
        if (!sBridgeLinked) {
            sBridgeLinked = nativeBridgeLinked();
        }

        if (!NativeBridge.nIsAvailable()) {
            android.util.Log.w(TAG,
                "hook: NativeBridge unavailable — CallSite cannot "
                + "dispatch callbacks without it");
            return false;
        }

        try {
            /*
             * Step 1: hand the callback to the Native backend's
             * registry. That builds the callback stub and records
             * (ArtMethod, slot). szp_dispatch_c is the single
             * dispatcher that will run it.
             */
            long stub = NativeBridge.nRegisterCallback(method, callback);
            if (stub == 0) {
                android.util.Log.w(TAG,
                    "hook: nRegisterCallback refused (szp_dispatch_c "
                    + "unresolved, layout invalid, or duplicate method)");
                return false;
            }

            /*
             * Step 2: arm the deferred trigger. This does not touch
             * the ArtMethod entry point — that happens on first
             * interpreted invocation, via shizuposed_lazy_install_artmethod.
             */
            boolean ok = nativeInstallHook(method);
            if (!ok) {
                NativeBridge.nUnregisterCallback(method);
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
    private static native boolean nativeInstallHook(Method method);
    private static native boolean nativeUninstallHook(Method method);
    private static native boolean nativeBridgeLinked();
    private static native String  nativeDescribe();
}