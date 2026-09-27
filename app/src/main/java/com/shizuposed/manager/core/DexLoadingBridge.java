package com.shizuposed.manager.core;

import android.os.Build;

import com.shizuposed.manager.core.compat.AndroidCompat;
import com.shizuposed.manager.core.compat.HiddenApiBypass;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;

/**
 * DexLoadingBridge
 *
 * Single place where the framework loads a module's dex into a target
 * process. Tries three loading strategies in order, falling through
 * only when the previous one fails.
 *
 * STRATEGY LADDER
 * ---------------
 *
 *   1. InMemoryDexClassLoader (Android 8+). Loads dex directly from
 *      a ByteBuffer. No file write, no filesystem dependency. The
 *      most reliable path because nothing it does can be blocked by
 *      a filesystem or SELinux restriction on writable dex paths.
 *
 *   2. DexClassLoader. The classic path. Reads the dex from a file
 *      on disk and lets ART handle optimization into the opt dir.
 *      Used when InMemoryDexClassLoader isn't available (Android 7
 *      and below) or fails.
 *
 *   3. BaseDexClassLoader injection. Walks the target's existing
 *      classloader and inserts the module's dex as an additional
 *      DexPathList.Element. Used only when both loaders above fail
 *      — for instance, on a ROM where DexClassLoader is
 *      hidden-API-blocked but the target's loader is still
 *      manipulable via reflection.
 *
 * All three strategies return a ClassLoader whose parent is the
 * target's classloader, so the module's classes can see the target's
 * classes and vice versa.
 *
 * FAILURE POLICY
 * --------------
 * If every strategy fails, load() returns null and logs which
 * strategies were tried and why each one failed. The caller (the
 * module load path in XposedHook) treats null as "skip this module"
 * rather than crashing the target.
 */
public final class DexLoadingBridge {

    private static final String TAG = "DexLoadingBridge";

    /** Result of a load attempt, with provenance for diagnostics. */
    public static final class LoadResult {
        /** The classloader that loaded the dex, or null on failure. */
        public final ClassLoader loader;
        /** Which strategy succeeded. One of STRATEGY_* constants. */
        public final int strategy;
        /** Human-readable summary for logs. */
        public final String detail;

        LoadResult(ClassLoader loader, int strategy, String detail) {
            this.loader = loader;
            this.strategy = strategy;
            this.detail = detail;
        }
    }

    public static final int STRATEGY_NONE      = 0;
    public static final int STRATEGY_IN_MEMORY = 1;
    public static final int STRATEGY_DEX       = 2;
    public static final int STRATEGY_INJECT    = 3;

    private DexLoadingBridge() {}

    /**
     * Load a module's dex against the target's classloader.
     *
     * @param dexPath       path to the dex file on disk. If the dex
     *                      is already in memory, use the overload
     *                      that takes a ByteBuffer.
     * @param optDir        directory for optimized dex output. May be
     *                      null when using the in-memory path.
     * @param parentLoader  the target's classloader. Never null.
     * @return a LoadResult. Never null; check loader for success.
     */
    public static LoadResult load(String dexPath,
                                  String optDir,
                                  ClassLoader parentLoader) {
        if (dexPath == null || parentLoader == null) {
            return new LoadResult(null, STRATEGY_NONE,
                "dexPath or parentLoader is null");
        }

        File dexFile = new File(dexPath);
        if (!dexFile.exists() || dexFile.length() == 0) {
            return new LoadResult(null, STRATEGY_NONE,
                "dex file missing or empty: " + dexPath);
        }

        // ── Strategy 1: InMemoryDexClassLoader ────────────────────
        if (AndroidCompat.supportsInMemoryDexClassLoader()) {
            LoadResult r = tryInMemory(dexFile, parentLoader);
            if (r != null && r.loader != null) return r;
        }

        // ── Strategy 2: DexClassLoader ────────────────────────────
        LoadResult r2 = tryDexClassLoader(dexFile, optDir, parentLoader);
        if (r2 != null && r2.loader != null) return r2;

        // ── Strategy 3: BaseDexClassLoader injection ──────────────
        LoadResult r3 = tryClassLoaderInjection(dexFile, optDir, parentLoader);
        if (r3 != null && r3.loader != null) return r3;

        return new LoadResult(null, STRATEGY_NONE,
            "all strategies failed for " + dexPath);
    }

    /**
     * Load a module's dex from an in-memory ByteBuffer.
     * Only the InMemoryDexClassLoader path is available when
     * starting from memory; the other two need a file on disk.
     *
     * @param dexBytes     valid dex bytes
     * @param parentLoader the target's classloader
     * @return a LoadResult. Never null; check loader for success.
     */
    public static LoadResult loadFromBuffer(ByteBuffer dexBytes,
                                            ClassLoader parentLoader) {
        if (dexBytes == null || parentLoader == null) {
            return new LoadResult(null, STRATEGY_NONE,
                "dexBytes or parentLoader is null");
        }
        if (!AndroidCompat.supportsInMemoryDexClassLoader()) {
            return new LoadResult(null, STRATEGY_NONE,
                "InMemoryDexClassLoader not available on this Android version");
        }
        try {
            Constructor<InMemoryDexClassLoader> ctor =
                InMemoryDexClassLoader.class.getDeclaredConstructor(
                    ByteBuffer.class, ClassLoader.class);
            HiddenApiBypass.forceAccessible(ctor);
            InMemoryDexClassLoader loader = ctor.newInstance(dexBytes, parentLoader);
            return new LoadResult(loader, STRATEGY_IN_MEMORY,
                "loaded from buffer");
        } catch (Throwable t) {
            return new LoadResult(null, STRATEGY_NONE,
                "buffer load failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // STRATEGY 1 — InMemoryDexClassLoader
    // ═════════════════════════════════════════════════════════════

    private static LoadResult tryInMemory(File dexFile, ClassLoader parentLoader) {
        try {
            // Read the dex into memory. For a typical module dex this
            // is a few hundred KB; well within heap for any modern
            // process.
            long size = dexFile.length();
            if (size <= 0 || size > 64L * 1024 * 1024) {
                return new LoadResult(null, STRATEGY_NONE,
                    "dex size out of range: " + size);
            }

            byte[] bytes = new byte[(int) size];
            try (FileInputStream in = new FileInputStream(dexFile)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read != bytes.length) {
                    return new LoadResult(null, STRATEGY_NONE,
                        "dex read incomplete: " + read + "/" + bytes.length);
                }
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            // InMemoryDexClassLoader(ByteBuffer, ClassLoader)
            Constructor<InMemoryDexClassLoader> ctor =
                InMemoryDexClassLoader.class.getDeclaredConstructor(
                    ByteBuffer.class, ClassLoader.class);
            HiddenApiBypass.forceAccessible(ctor);
            InMemoryDexClassLoader loader = ctor.newInstance(buffer, parentLoader);

            return new LoadResult(loader, STRATEGY_IN_MEMORY,
                "in-memory load succeeded (" + size + " bytes)");
        } catch (Throwable t) {
            return new LoadResult(null, STRATEGY_NONE,
                "in-memory load failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // STRATEGY 2 — DexClassLoader
    // ═════════════════════════════════════════════════════════════

    private static LoadResult tryDexClassLoader(File dexFile,
                                                String optDir,
                                                ClassLoader parentLoader) {
        try {
            if (optDir == null || optDir.isEmpty()) {
                // Without an opt dir, DexClassLoader falls back to
                // the system's default for the target app. Give it
                // a directory under the target's cache if we can
                // reach one, otherwise leave it null.
                optDir = parentLoader == null ? null
                    : System.getProperty("java.io.tmpdir");
            }
            if (optDir != null) {
                File opt = new File(optDir);
                if (!opt.exists()) opt.mkdirs();
            }

            DexClassLoader loader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                optDir,
                null,
                parentLoader);

            return new LoadResult(loader, STRATEGY_DEX,
                "DexClassLoader succeeded (opt=" + optDir + ")");
        } catch (Throwable t) {
            return new LoadResult(null, STRATEGY_NONE,
                "DexClassLoader failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // STRATEGY 3 — BaseDexClassLoader injection
    // ═════════════════════════════════════════════════════════════

    /**
     * Inject the module's dex into the target's existing classloader
     * by appending to its DexPathList.dexElements array.
     *
     * This is the technique LSPosed uses to add its own dex to a
     * target process. It's the deepest fallback because it reaches
     * into hidden internals, but it's also the most universal: it
     * works on any classloader that extends BaseDexClassLoader,
     * which both DexClassLoader and PathClassLoader do.
     *
     * Returns a LoadResult wrapping the modified loader on success.
     * The "loader" is the same object the caller passed in, now
     * augmented to find the module's classes.
     */
    private static LoadResult tryClassLoaderInjection(File dexFile,
                                                      String optDir,
                                                      ClassLoader parentLoader) {
        if (parentLoader == null) {
            return new LoadResult(null, STRATEGY_NONE,
                "no parent loader for injection");
        }
        try {
            // The target loader must be a BaseDexClassLoader. On
            // modern Android this is always true for app loaders.
            Class<?> baseDexClass = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!baseDexClass.isAssignableFrom(parentLoader.getClass())) {
                return new LoadResult(null, STRATEGY_NONE,
                    "loader is not a BaseDexClassLoader: "
                    + parentLoader.getClass().getName());
            }

            // Reach the pathList field.
            Field pathListField = baseDexClass.getDeclaredField("pathList");
            HiddenApiBypass.forceAccessible(pathListField);
            Object dexPathList = pathListField.get(parentLoader);
            if (dexPathList == null) {
                return new LoadResult(null, STRATEGY_NONE,
                    "pathList is null");
            }

            // Reach the dexElements array inside pathList.
            Class<?> dexPathListClass = dexPathList.getClass();
            Field elementsField = dexPathListClass.getDeclaredField("dexElements");
            HiddenApiBypass.forceAccessible(elementsField);
            Object[] existingElements = (Object[]) elementsField.get(dexPathList);
            if (existingElements == null) existingElements = new Object[0];

            // Construct new Elements for the module dex.
            Object[] newElements = makeDexElements(
                dexPathList, dexFile.getAbsolutePath(), optDir);
            if (newElements == null || newElements.length == 0) {
                return new LoadResult(null, STRATEGY_NONE,
                    "makeDexElements produced no output");
            }

            // Concatenate. Existing first, new after, so the
            // target's own classes take priority over ours if
            // there are any collisions.
            Object[] combined = new Object[
                existingElements.length + newElements.length];
            System.arraycopy(existingElements, 0, combined, 0,
                existingElements.length);
            System.arraycopy(newElements, 0, combined,
                existingElements.length, newElements.length);

            // Write the combined array back.
            elementsField.set(dexPathList, combined);

            return new LoadResult(parentLoader, STRATEGY_INJECT,
                "injected " + newElements.length + " element(s) into "
                + parentLoader.getClass().getSimpleName());
        } catch (Throwable t) {
            return new LoadResult(null, STRATEGY_NONE,
                "injection failed: " + t.getMessage());
        }
    }

    /**
     * Build DexPathList.Element[] for the given dex path. Uses
     * reflection to call DexPathList.makeDexElements because its
     * signature has changed across Android versions.
     */
    private static Object[] makeDexElements(Object dexPathList,
                                            String dexPath,
                                            String optDir) {
        try {
            Class<?> dexPathListClass = dexPathList.getClass();
            for (Method m : dexPathListClass.getDeclaredMethods()) {
                if (!"makeDexElements".equals(m.getName())) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                try {
                    HiddenApiBypass.forceAccessible(m);
                    Object[] args = buildMakeDexElementsArgs(m, dexPath, optDir,
                        dexPathList);
                    if (args == null) continue;
                    Object result = m.invoke(null, args);
                    if (result instanceof Object[]) {
                        Object[] arr = (Object[]) result;
                        if (arr.length > 0) return arr;
                    }
                } catch (Throwable ignored) {
                    // Try the next overload. There's usually one.
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Construct the argument array for a makeDexElements call,
     * filling each position based on its declared type. The
     * parameter list has varied across versions, so we handle each
     * observed shape generically.
     */
    private static Object[] buildMakeDexElementsArgs(Method m,
                                                     String dexPath,
                                                     String optDir,
                                                     Object dexPathList) {
        try {
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == java.util.List.class) {
                    List<File> files = new ArrayList<>(1);
                    files.add(new File(dexPath));
                    args[i] = files;
                } else if (t == File.class) {
                    args[i] = (optDir != null && !optDir.isEmpty())
                        ? new File(optDir) : null;
                } else if (t == ClassLoader.class) {
                    args[i] = dexPathList.getClass().getClassLoader();
                } else if (t == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (t == java.util.List.class) {
                    args[i] = new ArrayList<>();
                } else {
                    // Unknown parameter. If the type is primitive we
                    // can't pass null; skip this overload.
                    if (t.isPrimitive()) return null;
                    args[i] = null;
                }
            }
            return args;
        } catch (Throwable t) {
            return null;
        }
    }
}