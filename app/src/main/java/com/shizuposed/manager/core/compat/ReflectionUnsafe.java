package com.shizuposed.manager.core.compat;

import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * ReflectionUnsafe
 *
 * A stable façade over whatever "unsafe memory access" primitive the
 * current ART exposes.
 *
 * On API 21–35 this is sun.misc.Unsafe. On future Android versions,
 * sun.misc.Unsafe may be moved, renamed, or removed; this class tries a
 * sequence of strategies and remembers which one worked.
 *
 * Callers never touch sun.misc.Unsafe directly. If a strategy isn't
 * available, methods return false / 0 / null instead of throwing — the
 * framework prefers to degrade gracefully.
 */
public final class ReflectionUnsafe {

    private static final String TAG = "ReflectionUnsafe";

    // Cached after first successful init
    private static volatile Object unsafeInstance;
    private static volatile Class<?> unsafeClass;
    private static volatile boolean initialized = false;
    private static volatile String lastStrategyName = "none";

    // Cached Method handles for the operations we actually use.
    private static volatile Method mGetLong;
    private static volatile Method mPutLong;
    private static volatile Method mGetInt;
    private static volatile Method mPutInt;
    private static volatile Method mGetObject;
    private static volatile Method mPutObject;
    private static volatile Method mObjectFieldOffset;
    private static volatile Method mArrayBaseOffset;
    private static volatile Method mCopyMemory;
    private static volatile Method mAllocateMemory;

    private ReflectionUnsafe() {}

    // ═════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Try every known strategy to obtain an Unsafe instance. Idempotent.
     * Returns true if any strategy succeeded.
     */
    public static boolean ensureInitialized() {
        if (initialized) return unsafeInstance != null;
        synchronized (ReflectionUnsafe.class) {
            if (initialized) return unsafeInstance != null;
            initialized = true;

            if (tryStrategy("sun.misc.Unsafe", "theUnsafe")) return true;
            if (tryStrategy("sun.misc.Unsafe", "THE_ONE")) return true;
            if (tryStrategy("jdk.internal.misc.Unsafe", "theUnsafe")) return true;
            if (tryStrategy("jdk.internal.misc.Unsafe", "THE_ONE")) return true;
            if (tryGetUnsafeMethod("sun.misc.Unsafe")) return true;
            if (tryGetUnsafeMethod("jdk.internal.misc.Unsafe")) return true;

            // Future Android might rename again. Try a couple of guesses.
            if (tryStrategy("dalvik.system.VMRuntime", "theUnsafe")) return true;
            if (tryStrategy("libcore.io.Memory", "unsafe")) return true;

            log("No Unsafe strategy succeeded on this device");
            lastStrategyName = "none";
            return false;
        }
    }

    private static boolean tryStrategy(String className, String fieldName) {
        try {
            Class<?> c = Class.forName(className);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object instance = f.get(null);
            if (instance == null) return false;
            adopt(c, instance, className + "." + fieldName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryGetUnsafeMethod(String className) {
        try {
            Class<?> c = Class.forName(className);
            Method getUnsafe = c.getDeclaredMethod("getUnsafe");
            getUnsafe.setAccessible(true);
            Object instance = getUnsafe.invoke(null);
            if (instance == null) return false;
            adopt(c, instance, className + ".getUnsafe()");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void adopt(Class<?> clazz, Object instance, String strategyName) {
        unsafeClass = clazz;
        unsafeInstance = instance;
        lastStrategyName = strategyName;

        // Cache the method handles we use. Any that fail stay null, and
        // the corresponding caller sees a graceful false / 0 / null.
        mGetLong          = find(clazz, "getLong",          Object.class, long.class);
        mPutLong          = find(clazz, "putLong",          Object.class, long.class, long.class);
        mGetInt           = find(clazz, "getInt",           Object.class, long.class);
        mPutInt           = find(clazz, "putInt",           Object.class, long.class, int.class);
        mGetObject        = find(clazz, "getObject",        Object.class, long.class);
        mPutObject        = find(clazz, "putObject",        Object.class, long.class, Object.class);
        mObjectFieldOffset= find(clazz, "objectFieldOffset", Field.class);
        mArrayBaseOffset  = find(clazz, "arrayBaseOffset",  Class.class);
        mCopyMemory       = find(clazz, "copyMemory",
                                 Object.class, long.class, Object.class, long.class, long.class);
        mAllocateMemory   = find(clazz, "allocateMemory",   long.class);

        log("Unsafe strategy adopted: " + strategyName);
    }

    private static Method find(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PUBLIC QUERIES
    // ═════════════════════════════════════════════════════════════

    public static boolean isAvailable() {
        ensureInitialized();
        return unsafeInstance != null;
    }

    public static String getStrategyName() {
        ensureInitialized();
        return lastStrategyName;
    }

    public static Object getUnsafe() {
        ensureInitialized();
        return unsafeInstance;
    }

    // ═════════════════════════════════════════════════════════════
    // FIELD OFFSET
    // ═════════════════════════════════════════════════════════════

    public static long objectFieldOffset(Field f) {
        if (mObjectFieldOffset == null || unsafeInstance == null) return -1L;
        try {
            Object r = mObjectFieldOffset.invoke(unsafeInstance, f);
            return r instanceof Number ? ((Number) r).longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static int arrayBaseOffset(Class<?> componentType) {
        if (mArrayBaseOffset == null || unsafeInstance == null) return -1;
        try {
            Object r = mArrayBaseOffset.invoke(unsafeInstance, componentType);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // RAW MEMORY ACCESS
    // ═════════════════════════════════════════════════════════════

    public static long getLong(Object base, long offset) {
        if (mGetLong == null || unsafeInstance == null) return 0L;
        try { return (Long) mGetLong.invoke(unsafeInstance, base, offset); }
        catch (Throwable t) { return 0L; }
    }

    public static boolean putLong(Object base, long offset, long value) {
        if (mPutLong == null || unsafeInstance == null) return false;
        try { mPutLong.invoke(unsafeInstance, base, offset, value); return true; }
        catch (Throwable t) { return false; }
    }

    public static int getInt(Object base, long offset) {
        if (mGetInt == null || unsafeInstance == null) return 0;
        try { return (Integer) mGetInt.invoke(unsafeInstance, base, offset); }
        catch (Throwable t) { return 0; }
    }

    public static boolean putInt(Object base, long offset, int value) {
        if (mPutInt == null || unsafeInstance == null) return false;
        try { mPutInt.invoke(unsafeInstance, base, offset, value); return true; }
        catch (Throwable t) { return false; }
    }

    public static Object getObject(Object base, long offset) {
        if (mGetObject == null || unsafeInstance == null) return null;
        try { return mGetObject.invoke(unsafeInstance, base, offset); }
        catch (Throwable t) { return null; }
    }

    public static boolean putObject(Object base, long offset, Object value) {
        if (mPutObject == null || unsafeInstance == null) return false;
        try { mPutObject.invoke(unsafeInstance, base, offset, value); return true; }
        catch (Throwable t) { return false; }
    }

    public static boolean copyMemory(long srcAddr, long dstAddr, long bytes) {
        if (mCopyMemory == null || unsafeInstance == null) return false;
        try {
            mCopyMemory.invoke(unsafeInstance, null, srcAddr, null, dstAddr, bytes);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static long allocateMemory(long bytes) {
        if (mAllocateMemory == null || unsafeInstance == null) return 0L;
        try {
            Object r = mAllocateMemory.invoke(unsafeInstance, bytes);
            return r instanceof Number ? ((Number) r).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LOG
    // ═════════════════════════════════════════════════════════════

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.d("[" + TAG + "] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[" + TAG + "] " + msg);
    }
}