package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXUnhook;
import de.robv.android.xposed.XC_MethodHook;

/**
 * XposedHookBridge
 *
 * The single seam where the actual ART hook backend gets installed.
 * Everything above this class (the Xposed API shims, XposedHelpersImpl,
 * ResourceHooking) calls installHook(...) and never knows which backend
 * is active.
 */
public final class XposedHookBridge {

    public interface HookBackend {
        void hook(Method original, XC_MethodHook callback);
        void hookConstructor(Constructor<?> original, XC_MethodHook callback);
    }

    private static volatile HookBackend backend = new LoggingBackend();

    private XposedHookBridge() {}

    public static void setBackend(HookBackend b) {
        if (b != null) {
            backend = b;
            log("Hook backend installed: " + b.getClass().getSimpleName());
        }
    }

    public static HookBackend getBackend() {
        return backend;
    }

    public static void installHook(Method original, XC_MethodHook callback) {
        try {
            backend.hook(original, callback);
        } catch (Throwable t) {
            log("installHook failed on "
                + original.getDeclaringClass().getName() + "." + original.getName()
                + " — " + t.getMessage());
        }
    }

    public static void installConstructorHook(Constructor<?> original, XC_MethodHook callback) {
        try {
            backend.hookConstructor(original, callback);
        } catch (Throwable t) {
            log("installConstructorHook failed on " + original.getDeclaringClass().getName()
                + " — " + t.getMessage());
        }
    }

    /**
     * Install a constructor hook and return an IXUnhook handle.
     *
     * The handle's unhook() runs the reverse if HookEngine tracked
     * one (CallSite, Proxy, Noop) and is a no-op if it didn't
     * (Pine, Amiru, Native, Instrumentation). That matches the
     * contract documented in IXUnhook.
     */
    public static IXUnhook<XC_MethodHook> installConstructorHookWithHandle(
            final Constructor<?> original,
            final XC_MethodHook callback) {
        installConstructorHook(original, callback);
        return new IXUnhook<XC_MethodHook>() {
            @Override
            public void unhook() {
                Runnable reverse = getLastInstallReverse(original);
                if (reverse != null) {
                    try {
                        reverse.run();
                    } catch (Throwable t) {
                        log("constructor unhook failed on "
                            + original.getDeclaringClass().getName()
                            + " — " + t.getMessage());
                    }
                }
            }
            @Override
            public XC_MethodHook getCallback() {
                return callback;
            }
            @Override
            public Member getHookedMethod() {
                return original;
            }
        };
    }

    /**
     * Same as installConstructorHookWithHandle, but for methods.
     * Kept alongside the constructor version so findAndHookMethod
     * can return the same shape of handle.
     */
    public static IXUnhook<XC_MethodHook> installHookWithHandle(
            final Method original,
            final XC_MethodHook callback) {
        installHook(original, callback);
        return new IXUnhook<XC_MethodHook>() {
            @Override
            public void unhook() {
                Runnable reverse = getLastInstallReverse(original);
                if (reverse != null) {
                    try {
                        reverse.run();
                    } catch (Throwable t) {
                        log("method unhook failed on "
                            + original.getDeclaringClass().getName()
                            + "." + original.getName()
                            + " — " + t.getMessage());
                    }
                }
            }
            @Override
            public XC_MethodHook getCallback() {
                return callback;
            }
            @Override
            public Member getHookedMethod() {
                return original;
            }
        };
    }

    public static String getLastInstallBackend(Member member) {
        try {
            HookEngine.InstallRecord r = HookEngine.getInstallRecord(member);
            return r != null ? r.backendName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Runnable getLastInstallReverse(Member member) {
        try {
            HookEngine.InstallRecord r = HookEngine.getInstallRecord(member);
            return r != null ? r.reverse : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ─── default backend ─────────────────────────────────────────────

    private static final class LoggingBackend implements HookBackend {
        @Override
        public void hook(Method original, XC_MethodHook callback) {
            log("(no-op) hook requested: "
                + original.getDeclaringClass().getName() + "." + original.getName());
        }
        @Override
        public void hookConstructor(Constructor<?> original, XC_MethodHook callback) {
            log("(no-op) ctor hook requested: " + original.getDeclaringClass().getName());
        }
    }

    private static void log(String msg) {
        try { Logger.getInstance(null).i("[Bridge] " + msg); }
        catch (Throwable ignored) {}
    }
}