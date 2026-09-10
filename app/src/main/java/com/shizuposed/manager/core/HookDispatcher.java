package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * HookDispatcher
 *
 * Sits between XposedHookBridge and the actual hook backends.
 *
 * - At init, backends are tried in priority order. The first one that
 *   reports itself as "available" becomes the primary.
 * - At hook time, the primary is tried first. If it throws, subsequent
 *   backends are tried in order. The first success wins.
 * - If every backend fails, the hook is logged and dropped — the caller
 *   does not get an exception.
 *
 * This means: as long as *any* backend can handle a given method, that
 * method gets hooked. No single backend is a single point of failure.
 */
public final class HookDispatcher {

    /** One backend that can install hooks. */
    public interface Backend {
        /** Human-readable name, for logs. */
        String name();

        /** Called once. Return false if this backend isn't usable on this device. */
        boolean isAvailable();

        /**
         * Install a hook.
         * @return true on success, false or throw on failure.
         */
        boolean hook(Method original, XC_MethodHook callback) throws Throwable;

        /** Optional: constructor hooks. Default returns false. */
        default boolean hookConstructor(Constructor<?> original, XC_MethodHook callback) throws Throwable {
            return false;
        }

        /** Optional: called when the dispatcher wants to release resources. */
        default void shutdown() {}
    }

    private final List<Backend> backends = new CopyOnWriteArrayList<>();
    private volatile Backend primary;
    private volatile boolean initialized = false;

    private static HookDispatcher instance;

    private HookDispatcher() {}

    public static synchronized HookDispatcher getInstance() {
        if (instance == null) instance = new HookDispatcher();
        return instance;
    }

    // ─────────────────────────────────────────────────────────────
    // REGISTRATION
    // ─────────────────────────────────────────────────────────────

    /** Register a backend. Order matters — earlier = higher priority. */
    public void register(Backend backend) {
        if (backend == null) return;
        backends.add(backend);
        log("Registered backend: " + backend.name());
    }

    /**
     * Called once after all backends have been registered. Picks the
     * first one that reports itself available.
     */
    public synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        for (Backend b : backends) {
            try {
                if (b.isAvailable()) {
                    primary = b;
                    log("Primary backend selected: " + b.name());
                    return;
                } else {
                    log("Backend not available: " + b.name());
                }
            } catch (Throwable t) {
                log("Backend " + b.name() + " threw during availability check: " + t);
            }
        }

        log("No backend available — hooks will be no-ops");
    }

    // ─────────────────────────────────────────────────────────────
    // DISPATCH
    // ─────────────────────────────────────────────────────────────

    /**
     * Try each backend in order until one succeeds.
     * Never throws — returns true on success, false if all failed.
     */
    public boolean installHook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;

        ensureInitialized();

        // 1. Try the primary first
        Backend p = primary;
        if (p != null) {
            try {
                if (p.hook(original, callback)) {
                    return true;
                }
                log("Primary backend " + p.name()
                    + " returned false for "
                    + original.getDeclaringClass().getName() + "." + original.getName());
            } catch (Throwable t) {
                log("Primary backend " + p.name() + " threw for "
                    + original.getDeclaringClass().getName() + "." + original.getName()
                    + " — " + t);
            }
        }

        // 2. Fall through the rest in registration order
        for (Backend b : backends) {
            if (b == p) continue;
            try {
                if (b.hook(original, callback)) {
                    log("Fallback backend " + b.name() + " hooked "
                        + original.getDeclaringClass().getName() + "." + original.getName());
                    return true;
                }
            } catch (Throwable t) {
                log("Fallback backend " + b.name() + " threw for "
                    + original.getName() + " — " + t);
            }
        }

        log("All backends failed for "
            + original.getDeclaringClass().getName() + "." + original.getName());
        return false;
    }

    public boolean installConstructorHook(Constructor<?> original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;

        ensureInitialized();

        Backend p = primary;
        if (p != null) {
            try {
                if (p.hookConstructor(original, callback)) return true;
            } catch (Throwable t) {
                log("Primary backend " + p.name() + " ctor threw: " + t);
            }
        }

        for (Backend b : backends) {
            if (b == p) continue;
            try {
                if (b.hookConstructor(original, callback)) {
                    log("Fallback backend " + b.name() + " hooked ctor "
                        + original.getDeclaringClass().getName());
                    return true;
                }
            } catch (Throwable t) {
                log("Fallback backend " + b.name() + " ctor threw: " + t);
            }
        }
        return false;
    }

    public Backend getPrimary() { return primary; }
    public boolean isInitialized() { return initialized; }

    private void ensureInitialized() {
        if (!initialized) initialize();
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[HookDispatcher] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[HookDispatcher] " + msg);
    }
}