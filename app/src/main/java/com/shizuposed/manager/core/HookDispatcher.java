package com.shizuposed.manager.core;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;

/**
 * HookDispatcher
 *
 * Sits between XposedHookBridge and the actual hook backends.
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
         * @return true on success, false if this method isn't the
         *         backend's responsibility, or throw on real failure.
         */
        boolean hook(Method original, XC_MethodHook callback) throws Throwable;

        /** Optional: constructor hooks. Default returns false. */
        default boolean hookConstructor(Constructor<?> original, XC_MethodHook callback) throws Throwable {
            return false;
        }

        /** Optional: called when the dispatcher wants to release resources. */
        default void shutdown() {}
    }

    /** Per-backend counters. */
    public static final class BackendStats {
        public final AtomicInteger installed = new AtomicInteger();
        public final AtomicInteger declined  = new AtomicInteger();
        public final AtomicInteger failed    = new AtomicInteger();
        public final AtomicInteger ctorInstalled = new AtomicInteger();
        public final AtomicInteger ctorDeclined  = new AtomicInteger();
        public final AtomicInteger ctorFailed    = new AtomicInteger();

        @Override public String toString() {
            return "installed=" + installed.get()
                    + " declined=" + declined.get()
                    + " failed=" + failed.get()
                    + " | ctor installed=" + ctorInstalled.get()
                    + " declined=" + ctorDeclined.get()
                    + " failed=" + ctorFailed.get();
        }
    }

    private final List<Backend> backends = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Backend, BackendStats> stats =
            new ConcurrentHashMap<>();

    private volatile Backend primary;
    private volatile boolean initialized = false;
    private volatile Backend lastInstalledBackend;
    private volatile Runnable lastInstalledReverse;

    private static volatile HookDispatcher instance;

    private HookDispatcher() {}

    public static HookDispatcher getInstance() {
        HookDispatcher local = instance;
        if (local == null) {
            synchronized (HookDispatcher.class) {
                local = instance;
                if (local == null) {
                    local = new HookDispatcher();
                    instance = local;
                }
            }
        }
        return local;
    }

    public String getLastInstalledBackendName() {
        Backend b = lastInstalledBackend;
        return b != null ? b.name() : null;
    }

    public Runnable getLastInstalledReverse() {
        return lastInstalledReverse;
    }

    // ─────────────────────────────────────────────────────────────
    // REGISTRATION
    // ─────────────────────────────────────────────────────────────

    /** Register a backend. Order matters — earlier = higher priority. */
    public synchronized void register(Backend backend) {
        if (backend == null) return;
        if (initialized) {
            log("register() after initialize(); ignoring " + backend.name());
            return;
        }
        backends.add(backend);
        stats.put(backend, new BackendStats());
        log("Registered backend: " + backend.name());
    }

    /**
     * Called once after all backends have been registered. Picks the
     * first one that reports itself available.
     */
    public synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        if (backends.isEmpty()) {
            log("initialize() with no backends registered");
            return;
        }

        for (Backend b : backends) {
            try {
                if (b.isAvailable()) {
                    primary = b;
                    log("Primary backend selected: " + b.name());
                    break;
                }
                log("Backend not available: " + b.name());
            } catch (Throwable t) {
                log("Backend " + b.name()
                        + " threw during availability check: " + t);
            }
        }

        if (primary == null) {
            log("No backend available — hooks will be no-ops");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DISPATCH
    // ─────────────────────────────────────────────────────────────

    public boolean installHook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;

        ensureInitialized();

        Backend p = primary;
        if (p != null && backends.indexOf(p) != 0) {
            if (tryHook(p, original, callback, true)) return true;
        }

        for (Backend b : backends) {
            if (b == p) continue;
            if (tryHook(b, original, callback, false)) return true;
        }

        if (p != null && backends.indexOf(p) == 0) {
            if (tryHook(p, original, callback, true)) return true;
        }

        log("All backends declined "
                + original.getDeclaringClass().getName() + "."
                + original.getName());
        return false;
    }

    public boolean installConstructorHook(Constructor<?> original,
                                          XC_MethodHook callback) {
        if (original == null || callback == null) return false;

        ensureInitialized();

        Backend p = primary;
        if (p != null && backends.indexOf(p) != 0) {
            if (tryCtorHook(p, original, callback, true)) return true;
        }

        for (Backend b : backends) {
            if (b == p) continue;
            if (tryCtorHook(b, original, callback, false)) return true;
        }

        if (p != null && backends.indexOf(p) == 0) {
            if (tryCtorHook(p, original, callback, true)) return true;
        }

        log("All backends declined ctor "
                + original.getDeclaringClass().getName());
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // INTERNALS
    // ─────────────────────────────────────────────────────────────

    private boolean tryHook(Backend b, Method original,
                            XC_MethodHook callback, boolean isPrimary) {
        BackendStats s = stats.get(b);
        try {
            if (b.hook(original, callback)) {
                if (s != null) s.installed.incrementAndGet();
                lastInstalledBackend = b;
                lastInstalledReverse = null;

                if (!isPrimary) {
                    log("Fallback backend " + b.name() + " hooked "
                            + original.getDeclaringClass().getName()
                            + "." + original.getName());
                }
                return true;
            }
            if (s != null) s.declined.incrementAndGet();
            if (isPrimary) {
                log("Primary " + b.name() + " declined "
                        + original.getDeclaringClass().getName()
                        + "." + original.getName());
            }
            return false;
        } catch (Throwable t) {
            if (s != null) s.failed.incrementAndGet();
            log("Backend " + b.name() + " threw for "
                    + original.getDeclaringClass().getName()
                    + "." + original.getName() + " — " + t);
            return false;
        }
    }

    private boolean tryCtorHook(Backend b, Constructor<?> original,
                                XC_MethodHook callback, boolean isPrimary) {
        BackendStats s = stats.get(b);
        try {
            if (b.hookConstructor(original, callback)) {
                if (s != null) s.ctorInstalled.incrementAndGet();
                lastInstalledBackend = b;
                lastInstalledReverse = null;

                if (!isPrimary) {
                    log("Fallback backend " + b.name() + " hooked ctor "
                            + original.getDeclaringClass().getName());
                }
                return true;
            }
            if (s != null) s.ctorDeclined.incrementAndGet();
            if (isPrimary) {
                log("Primary " + b.name() + " declined ctor "
                        + original.getDeclaringClass().getName());
            }
            return false;
        } catch (Throwable t) {
            if (s != null) s.ctorFailed.incrementAndGet();
            log("Backend " + b.name() + " ctor threw for "
                    + original.getDeclaringClass().getName() + " — " + t);
            return false;
        }
    }

    public Backend getPrimary() { return primary; }
    public boolean isInitialized() { return initialized; }

    /** Snapshot of registered backends, in registration order. */
    public List<Backend> getBackends() {
        return Collections.unmodifiableList(new ArrayList<>(backends));
    }

    /** Stats for a backend, or null if it was never registered. */
    public BackendStats getStats(Backend backend) {
        return backend == null ? null : stats.get(backend);
    }

    public String describeStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("HookDispatcher stats (primary=")
          .append(primary == null ? "none" : primary.name())
          .append("):");
        for (Backend b : backends) {
            BackendStats s = stats.get(b);
            sb.append("\n  ").append(b.name()).append(": ");
            sb.append(s == null ? "(no stats)" : s.toString());
        }
        return sb.toString();
    }

    public void shutdown() {
        for (Backend b : backends) {
            try { b.shutdown(); }
            catch (Throwable t) { log("Backend " + b.name()
                    + " threw during shutdown: " + t); }
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[HookDispatcher] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[HookDispatcher] " + msg);
    }
}