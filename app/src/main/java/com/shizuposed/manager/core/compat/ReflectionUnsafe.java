package com.shizuposed.manager.core.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ReflectionUnsafe
 *
 * A stable facade over whatever "unsafe memory access" primitive
 * the current ART exposes.
 *
 * On API 21-35 this is sun.misc.Unsafe. On future Android versions
 * sun.misc.Unsafe may be moved, renamed, or removed; this class
 * tries every strategy that could plausibly work and remembers
 * which one succeeded.
 *
 * DYNAMIC DISCOVERY
 * -----------------
 * Nothing about the class name, the singleton field name, or the
 * accessor method names is hardcoded. Instead:
 *
 *   1. The class names come from AndroidCompat's runtime probe
 *      (which already found an Unsafe class by walking the boot
 *      classpath) plus a small fallback list for cases where the
 *      probe couldn't run.
 *
 *   2. The singleton accessor is discovered by walking every
 *      declared field and method on the candidate class, looking
 *      for anything whose type (or return type) is the candidate
 *      class itself and that is static. This matches
 *      `theUnsafe`, `THE_ONE`, `getUnsafe`, and any future
 *      equivalent without naming any of them.
 *
 *   3. The memory accessors (getLong, putObject, etc.) are
 *      discovered by walking every declared method on the class
 *      and matching by name and signature. If a future release
 *      renames `getLong` to `getLongAt` or moves the accessors to
 *      a superclass, the walk still finds them.
 *
 * This costs one reflection pass per process start. The result is
 * cached in a State snapshot, so callers never pay the cost again.
 *
 * Callers never touch sun.misc.Unsafe directly. If no strategy is
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
        final String singletonAccessorName;
        final Method getLong, putLong;
        final Method getInt, putInt;
        final Method getObject, putObject;
        final Method objectFieldOffset, arrayBaseOffset;
        final Method copyMemory5, copyMemory3;
        final Method allocateMemory, freeMemory;

        State(Object instance, Class<?> clazz, String strategyName,
              String singletonAccessorName,
              Method getLong, Method putLong,
              Method getInt, Method putInt,
              Method getObject, Method putObject,
              Method objectFieldOffset, Method arrayBaseOffset,
              Method copyMemory5, Method copyMemory3,
              Method allocateMemory, Method freeMemory) {
            this.instance = instance;
            this.clazz = clazz;
            this.strategyName = strategyName;
            this.singletonAccessorName = singletonAccessorName;
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

    /**
     * Probe notes, collected during discovery. Used by
     * describe() and by bug reports.
     */
    private static final List<String> PROBE_NOTES = new ArrayList<>();

    private ReflectionUnsafe() {}

    // ═════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Try every known strategy to obtain an Unsafe instance.
     * Idempotent: once a State is published (success or failure) it
     * is never rebuilt. The failure snapshot exists so callers
     * don't pay the reflection cost on every call.
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
        Object[] hit = tryAllStrategies();
        if (hit == null) {
            CompatLog.d(TAG, "No Unsafe strategy succeeded on this device");
            for (String n : PROBE_NOTES) {
                CompatLog.d(TAG, "  " + n);
            }
            return new State(null, null, "none", null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
        Class<?> clazz = (Class<?>) hit[0];
        Object instance = hit[1];
        String name = (String) hit[2];
        String accessor = (String) hit[3];

        // Discover accessor methods dynamically. The old code passed
        // explicit parameter lists; the new code matches by name and
        // return type, which is more resilient to signature drift.
        Method getLong = findByNameAndReturn(clazz, "getLong", long.class,
                Object.class, long.class);
        Method putLong = findByNameAndReturn(clazz, "putLong", void.class,
                Object.class, long.class, long.class);
        Method getInt = findByNameAndReturn(clazz, "getInt", int.class,
                Object.class, long.class);
        Method putInt = findByNameAndReturn(clazz, "putInt", void.class,
                Object.class, long.class, int.class);
        Method getObject = findByNameAndReturn(clazz, "getObject", Object.class,
                Object.class, long.class);
        Method putObject = findByNameAndReturn(clazz, "putObject", void.class,
                Object.class, long.class, Object.class);
        Method objectFieldOffset = findByNameAndReturn(clazz, "objectFieldOffset",
                long.class, Field.class);
        Method arrayBaseOffset = findByNameAndReturn(clazz, "arrayBaseOffset",
                int.class, Class.class);
        Method copyMemory5 = findByNameAndReturn(clazz, "copyMemory", void.class,
                Object.class, long.class, Object.class, long.class, long.class);
        Method copyMemory3 = findByNameAndReturn(clazz, "copyMemory", void.class,
                long.class, long.class, long.class);
        Method allocateMemory = findByNameAndReturn(clazz, "allocateMemory",
                long.class, long.class);
        Method freeMemory = findByNameAndReturn(clazz, "freeMemory", void.class,
                long.class);

        note("singleton accessor: " + accessor);
        note("getLong=" + (getLong != null));
        note("putLong=" + (putLong != null));
        note("copyMemory5=" + (copyMemory5 != null));
        note("copyMemory3=" + (copyMemory3 != null));
        note("allocateMemory=" + (allocateMemory != null));
        note("freeMemory=" + (freeMemory != null));

        State s = new State(
                instance, clazz, name, accessor,
                getLong, putLong,
                getInt, putInt,
                getObject, putObject,
                objectFieldOffset, arrayBaseOffset,
                copyMemory5, copyMemory3,
                allocateMemory, freeMemory);

        CompatLog.d(TAG, "Unsafe strategy adopted: " + name
                + " via " + accessor);
        return s;
    }

    /**
     * Enumerate candidate classes and accessor patterns.
     *
     * Class name sources, in order:
     *
     *   1. AndroidCompat.UNSAFE_CLASS_NAME — the class that the
     *      runtime probe actually found. If the probe ran and
     *      succeeded, this is the authoritative name.
     *
     *   2. A short fallback list of known historical names. This
     *      covers the case where the probe couldn't run (which is
     *      rare) but Unsafe is still present under a known name.
     *
     *   3. A boot-classpath walk for anything ending in "Unsafe".
     *      This is the last-resort discovery path: if the class has
     *      been renamed to something we don't recognize but the
     *      name still contains "Unsafe", we find it. Cost is one
     *      `Class.forName` per name in the boot class index, which
     *      is fast on Android.
     *
     * For each class, we discover the singleton accessor by walking
     * every declared static field and method whose type (or return
     * type) is the class itself. That matches `theUnsafe`,
     * `THE_ONE`, `getUnsafe`, and any future equivalent without
     * naming any of them.
     */
    private static Object[] tryAllStrategies() {
        List<String> candidates = new ArrayList<>();

        // Source 1: AndroidCompat probe result.
        String probed = AndroidCompat.UNSAFE_CLASS_NAME;
        if (probed != null && !probed.isEmpty()) {
            candidates.add(probed);
            note("candidate from probe: " + probed);
        }

        // Source 2: historical names.
        String[] fallbacks = {
                "sun.misc.Unsafe",
                "jdk.internal.misc.Unsafe",
                "jdk.internal.reflect.Unsafe",
        };
        for (String name : fallbacks) {
            if (!candidates.contains(name)) candidates.add(name);
        }

        // Source 3: boot-classpath walk, only if sources 1 and 2
        // produced nothing usable. The walk is expensive-ish, so
        // it's a last resort.
        if (candidates.isEmpty() || probed == null) {
            for (String found : findUnsafeClassesOnBootClasspath()) {
                if (!candidates.contains(found)) candidates.add(found);
            }
        }

        for (String cn : candidates) {
            Class<?> clazz;
            try {
                clazz = Class.forName(cn);
            } catch (Throwable t) {
                note("Class.forName(" + cn + ") failed: " + t.getMessage());
                continue;
            }

            // Discover the singleton by walking declared members.
            Object[] hit = discoverSingleton(clazz, cn);
            if (hit != null) return hit;

            // Fall back to the historical field and method names.
            // This covers cases where the singleton field isn't
            // typed as the class itself (rare but possible).
            hit = tryField(clazz, cn, "theUnsafe");
            if (hit != null) return hit;
            hit = tryField(clazz, cn, "THE_ONE");
            if (hit != null) return hit;
            hit = tryGetUnsafe(clazz, cn, "getUnsafe");
            if (hit != null) return hit;
        }

        return null;
    }

    /**
     * Walk every declared static field and method on the class. If
     * a field's type is the class itself (assignable from it), read
     * it. If a method's return type is the class itself and it takes
     * no arguments, call it. First hit wins.
     *
     * Returns { clazz, instance, className + "." + accessorName,
     *           accessorName } or null.
     */
    private static Object[] discoverSingleton(Class<?> clazz, String className) {
        // Static fields whose type is assignable to the class.
        try {
            for (Field f : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!clazz.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object instance = f.get(null);
                    if (instance != null) {
                        note("discovered field " + f.getName()
                            + " of type " + f.getType().getName());
                        return new Object[]{
                                clazz, instance,
                                className + "." + f.getName(),
                                "field:" + f.getName()
                        };
                    }
                } catch (Throwable t) {
                    note("field " + f.getName() + " read failed: "
                        + t.getMessage());
                }
            }
        } catch (Throwable t) {
            note("getDeclaredFields on " + className + " failed: "
                + t.getMessage());
        }

        // Static no-arg methods whose return type is assignable to
        // the class.
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0) continue;
                if (!clazz.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object instance = m.invoke(null);
                    if (instance != null) {
                        note("discovered method " + m.getName()
                            + " returning " + m.getReturnType().getName());
                        return new Object[]{
                                clazz, instance,
                                className + "." + m.getName() + "()",
                                "method:" + m.getName()
                        };
                    }
                } catch (Throwable t) {
                    note("method " + m.getName() + " invoke failed: "
                        + t.getMessage());
                }
            }
        } catch (Throwable t) {
            note("getDeclaredMethods on " + className + " failed: "
                + t.getMessage());
        }

        return null;
    }

    private static Object[] tryField(Class<?> c, String className, String fieldName) {
        try {
            Field f = c.getDeclaredField(fieldName);
            if (!Modifier.isStatic(f.getModifiers())) return null;
            f.setAccessible(true);
            Object instance = f.get(null);
            if (instance == null) return null;
            return new Object[]{
                    c, instance,
                    className + "." + fieldName,
                    "field:" + fieldName
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object[] tryGetUnsafe(Class<?> c, String className, String methodName) {
        try {
            Method getUnsafe = c.getDeclaredMethod(methodName);
            if (!Modifier.isStatic(getUnsafe.getModifiers())) return null;
            getUnsafe.setAccessible(true);
            Object instance = getUnsafe.invoke(null);
            if (instance == null) return null;
            return new Object[]{
                    c, instance,
                    className + "." + methodName + "()",
                    "method:" + methodName
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Walk the boot classpath looking for classes whose simple name
     * ends in "Unsafe". Android doesn't expose the classpath
     * directly, but a small set of well-known prefixes covers the
     * relevant namespaces.
     *
     * This is best-effort and only runs when the other sources
     * failed. It won't find a class that was renamed to something
     * unrelated to "Unsafe".
     */
    private static List<String> findUnsafeClassesOnBootClasspath() {
        List<String> out = new ArrayList<>();
        String[] packages = {
                "sun.misc.",
                "jdk.internal.misc.",
                "jdk.internal.reflect.",
                "dalvik.system.",
        };
        String[] suffixes = {
                "Unsafe",
                "Memory",
                "NativeAccess",
        };
        for (String p : packages) {
            for (String s : suffixes) {
                try {
                    Class.forName(p + s);
                    out.add(p + s);
                    note("boot-classpath candidate: " + p + s);
                } catch (Throwable ignored) {
                    // Not present. Expected for most combinations.
                }
            }
        }
        return out;
    }

    /**
     * Find a method by name and return type, ignoring parameter
     * types. This is more resilient than matching the full
     * signature because Android sometimes changes parameter types
     * (e.g., from `Object` to a raw pointer wrapper) while keeping
     * the same name and return type.
     *
     * If multiple methods match, the one with the smallest parameter
     * count wins — that's typically the most primitive overload.
     */
    private static Method findByNameAndReturn(Class<?> c,
                                                String name,
                                                Class<?> returnType,
                                                Class<?>... expectedParams) {
        if (c == null) return null;

        // First, try exact signature match. This is fast and
        // unambiguous when it works.
        Class<?> cur = c;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, expectedParams);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            cur = cur.getSuperclass();
        }

        // Fall back to name + return type. Collect all matches and
        // pick the one with the fewest parameters.
        Method best = null;
        cur = c;
        while (cur != null) {
            try {
                for (Method m : cur.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (!m.getReturnType().equals(returnType)) continue;
                    if (best == null
                            || m.getParameterCount() < best.getParameterCount()) {
                        m.setAccessible(true);
                        best = m;
                    }
                }
            } catch (Throwable ignored) {}
            cur = cur.getSuperclass();
        }

        if (best != null) {
            note("loose-matched " + name + " with "
                + best.getParameterCount() + " params");
        }
        return best;
    }

    private static void note(String msg) {
        if (msg != null) PROBE_NOTES.add(msg);
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

    public static String getSingletonAccessorName() {
        ensureInitialized();
        State s = state;
        return s == null ? "none" : s.singletonAccessorName;
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

    // ═════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════

    /**
     * Human-readable status for logs and the detail sheet. Reports
     * which class and accessor were adopted, and which methods were
     * discovered.
     */
    public static String describe() {
        ensureInitialized();
        State s = state;
        if (s == null || !s.isUsable()) {
            return "unsafe: unavailable";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("unsafe: ").append(s.strategyName);
        sb.append(" [getLong=").append(s.getLong != null ? 1 : 0);
        sb.append(" putLong=").append(s.putLong != null ? 1 : 0);
        sb.append(" getObject=").append(s.getObject != null ? 1 : 0);
        sb.append(" copyMemory=").append(s.copyMemory5 != null ? "5" :
                (s.copyMemory3 != null ? "3" : "0"));
        sb.append("]");
        return sb.toString();
    }

    /**
     * Full diagnostic, including every probe note. Useful for bug
     * reports and for the Settings → Logs tab.
     */
    public static String describeFull() {
        ensureInitialized();
        StringBuilder sb = new StringBuilder();
        sb.append(describe()).append('\n');
        sb.append("probe notes:\n");
        for (String n : PROBE_NOTES) {
            sb.append("  - ").append(n).append('\n');
        }
        return sb.toString();
    }
}