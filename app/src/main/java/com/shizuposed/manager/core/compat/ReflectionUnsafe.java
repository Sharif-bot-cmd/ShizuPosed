package com.shizuposed.manager.core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * ReflectionUnsafe
 *
 * A stable facade over whatever "unsafe memory access" primitive the
 * current ART exposes.
 *
 * On API 21-35 this is sun.misc.Unsafe. On future Android versions
 * sun.misc.Unsafe may be moved, renamed, or removed; this class tries
 * a sequence of strategies and remembers which one worked.
 *
 * Callers never touch sun.misc.Unsafe directly. If a strategy isn't
 * available, methods return false / 0 / null instead of throwing —
 * the framework prefers to degrade gracefully.
 *
 * Threading: initialization is one-shot and safe to call from any
 * thread. The publish is a single volatile write of {@link #state},
 * so a reader never observes a half-built method-handle table.
 */
public final class ReflectionUnsafe {

    private static final String TAG = "ReflectionUnsafe";

    /** All mutable state published as one immutable snapshot. */
    private static final class State {
        final Object instance;
        final Class<?> clazz;
        final String strategyName;
        final Method getLong, putLong;
        final Method getInt, putInt;
        final Method getObject, putObject;
        final Method objectFieldOffset, arrayBaseOffset;
        final Method copyMemory5, copyMemory3;
        final Method allocateMemory, freeMemory;

        State(Object instance, Class<?> clazz, String strategyName,
              Method getLong, Method putLong,
              Method getInt, Method putInt,
              Method getObject, Method putObject,
              Method objectFieldOffset, Method arrayBaseOffset,
              Method copyMemory5, Method copyMemory3,
              Method allocateMemory, Method freeMemory) {
            this.instance = instance;
            this.clazz = clazz;
            this.strategyName = strategyName;
            this.getLong = getLong;
            this.putLong = putLong;
            this.getInt = getInt;
            this.putInt = putInt;
            this.getObject = getObject;
            this.putObject = putObject;
            this.objectFieldOffset = objectFieldOffset;
            this.arrayBaseOffset = arrayBaseOffset;
            this.copyMemory5 = copyMemory5;
            this.copyMemory3 = copyMemory3;
            this.allocateMemory = allocateMemory;
            this.freeMemory = freeMemory;
        }

        boolean isUsable() { return instance != null; }
    }

    /** Snapshot. null before first init; may hold a "failed" State after. */
    private static volatile State state;

    private ReflectionUnsafe() {}

    // ═════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Try every known strategy to obtain an Unsafe instance. Idempotent:
     * once a State is published (success or failure) it is never
     * rebuilt. The failure snapshot exists so callers don't pay the
     * reflection cost on every call.
     */
    public static boolean ensureInitialized() {
        State s = state;
        if (s == null) {
            synchronized (ReflectionUnsafe.class) {
                s = state;
                if (s == null) {
                    s = buildState();
                    state = s; // single volatile publish
                }
            }
        }
        return s.isUsable();
    }

    private static State buildState() {
        // Ordered by likelihood. Each entry is (class, field-or-method).
        Object[] hit = tryAllStrategies();
        if (hit == null) {
            CompatLog.d(TAG, "No Unsafe strategy succeeded on this device");
            return new State(null, null, "none",
                    null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
        Class<?> clazz = (Class<?>) hit[0];
        Object instance = hit[1];
        String name = (String) hit[2];

        State s = new State(
                instance, clazz, name,
                find(clazz, "getLong", Object.class, long.class),
                find(clazz, "putLong", Object.class, long.class, long.class),
                find(clazz, "getInt", Object.class, long.class),
                find(clazz, "putInt", Object.class, long.class, int.class),
                find(clazz, "getObject", Object.class, long.class),
                find(clazz, "putObject", Object.class, long.class, Object.class),
                find(clazz, "objectFieldOffset", Field.class),
                find(clazz, "arrayBaseOffset", Class.class),
                find(clazz, "copyMemory",
                        Object.class, long.class, Object.class, long.class, long.class),
                find(clazz, "copyMemory", long.class, long.class, long.class),
                find(clazz, "allocateMemory", long.class),
                find(clazz, "freeMemory", long.class));

        CompatLog.d(TAG, "Unsafe strategy adopted: " + name);
        return s;
    }

    /**
     * Probe the known Unsafe class names and accessor patterns. The
     * order reflects real-world availability:
     *
     *   • sun.misc.Unsafe            — present on every Android release
     *                                  through API 35. First choice.
     *   • jdk.internal.misc.Unsafe   — the JDK-internal equivalent.
     *                                  Present but access-restricted on
     *                                  recent Android; kept as a
     *                                  fallback in case sun.misc is
     *                                  removed in a future version.
     *
     * For each class we try the known static-field names first
     * (theUnsafe, THE_ONE), then the getUnsafe() factory. Older
     * builds only expose one or the other; newer builds often expose
     * both, and either works.
     *
     * Deliberately not probed:
     *   • dalvik.system.VMRuntime — does not expose an Unsafe instance.
     *     Including it in the probe would cost three reflective calls
     *     per process start for a guaranteed miss.
     */
    private static Object[] tryAllStrategies() {
        String[] classes = {
                "sun.misc.Unsafe",
                "jdk.internal.misc.Unsafe",
        };
        String[] fields = { "theUnsafe", "THE_ONE" };
        for (String cn : classes) {
            for (String fn : fields) {
                Object[] hit = tryField(cn, fn);
                if (hit != null) return hit;
            }
            Object[] hit = tryGetUnsafe(cn);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Object[] tryField(String className, String fieldName) {
        try {
            Class<?> c = Class.forName(className);
            Field f = c.getDeclaredField(fieldName);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            f.setAccessible(true);
            Object instance = f.get(null);
            if (instance == null) return null;
            return new Object[]{ c, instance, className + "." + fieldName };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object[] tryGetUnsafe(String className) {
        try {
            Class<?> c = Class.forName(className);
            Method getUnsafe = c.getDeclaredMethod("getUnsafe");
            if (!Modifier.isStatic(getUnsafe.getModifiers())) return null;
            getUnsafe.setAccessible(true);
            Object instance = getUnsafe.invoke(null);
            if (instance == null) return null;
            return new Object[]{ c, instance, className + ".getUnsafe()" };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Look up a method on the class or any superclass. */
    private static Method find(Class<?> c, String name, Class<?>... params) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC QUERIES
    // ═════════════════════════════════════════════════════════════

    public static boolean isAvailable() {
        return ensureInitialized();
    }

    public static String getStrategyName() {
        ensureInitialized();
        State s = state;
        return s == null ? "none" : s.strategyName;
    }

    public static Object getUnsafe() {
        ensureInitialized();
        State s = state;
        return s == null ? null : s.instance;
    }

    // ═════════════════════════════════════════════════════════════
    // FIELD OFFSET
    // ═════════════════════════════════════════════════════════════

    public static long objectFieldOffset(Field f) {
        State s = state;
        if (s == null || s.objectFieldOffset == null || s.instance == null) return -1L;
        try {
            Object r = s.objectFieldOffset.invoke(s.instance, f);
            return r instanceof Number ? ((Number) r).longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static int arrayBaseOffset(Class<?> componentType) {
        State s = state;
        if (s == null || s.arrayBaseOffset == null || s.instance == null) return -1;
        try {
            Object r = s.arrayBaseOffset.invoke(s.instance, componentType);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // RAW MEMORY ACCESS
    // ═════════════════════════════════════════════════════════════

    public static long getLong(Object base, long offset) {
        State s = state;
        if (s == null || s.getLong == null || s.instance == null) return 0L;
        try { return (Long) s.getLong.invoke(s.instance, base, offset); }
        catch (Throwable t) { return 0L; }
    }

    public static boolean putLong(Object base, long offset, long value) {
        State s = state;
        if (s == null || s.putLong == null || s.instance == null) return false;
        try { s.putLong.invoke(s.instance, base, offset, value); return true; }
        catch (Throwable t) { return false; }
    }

    public static int getInt(Object base, long offset) {
        State s = state;
        if (s == null || s.getInt == null || s.instance == null) return 0;
        try { return (Integer) s.getInt.invoke(s.instance, base, offset); }
        catch (Throwable t) { return 0; }
    }

    public static boolean putInt(Object base, long offset, int value) {
        State s = state;
        if (s == null || s.putInt == null || s.instance == null) return false;
        try { s.putInt.invoke(s.instance, base, offset, value); return true; }
        catch (Throwable t) { return false; }
    }

    public static Object getObject(Object base, long offset) {
        State s = state;
        if (s == null || s.getObject == null || s.instance == null) return null;
        try { return s.getObject.invoke(s.instance, base, offset); }
        catch (Throwable t) { return null; }
    }

    public static boolean putObject(Object base, long offset, Object value) {
        State s = state;
        if (s == null || s.putObject == null || s.instance == null) return false;
        try { s.putObject.invoke(s.instance, base, offset, value); return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * Copy raw memory. Prefers the 5-arg (base, offset, base, offset, len)
     * form, falls back to the 3-arg (addr, addr, len) form.
     */
    public static boolean copyMemory(long srcAddr, long dstAddr, long bytes) {
        State s = state;
        if (s == null || s.instance == null) return false;
        try {
            if (s.copyMemory5 != null) {
                s.copyMemory5.invoke(s.instance, null, srcAddr, null, dstAddr, bytes);
                return true;
            }
            if (s.copyMemory3 != null) {
                s.copyMemory3.invoke(s.instance, srcAddr, dstAddr, bytes);
                return true;
            }
        } catch (Throwable t) {
            CompatLog.w(TAG, "copyMemory failed", t);
        }
        return false;
    }

    public static long allocateMemory(long bytes) {
        State s = state;
        if (s == null || s.allocateMemory == null || s.instance == null) return 0L;
        try {
            Object r = s.allocateMemory.invoke(s.instance, bytes);
            return r instanceof Number ? ((Number) r).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Release memory previously returned by {@link #allocateMemory(long)}.
     * Returns false if the underlying Unsafe has no freeMemory.
     */
    public static boolean freeMemory(long address) {
        State s = state;
        if (s == null || s.freeMemory == null || s.instance == null || address == 0L) {
            return false;
        }
        try {
            s.freeMemory.invoke(s.instance, address);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}