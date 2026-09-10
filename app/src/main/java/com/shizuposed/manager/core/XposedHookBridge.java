package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

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