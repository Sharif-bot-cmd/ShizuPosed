package com.shizuposed.manager.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ModuleClassLoader
 *
 * A chained classloader for Xposed modules loaded under
 * ShizuPosed. Resolves BOTH the module's own classes and the
 * target app's classes, so that LoadPackageParam.classLoader
 * works for every pattern a module uses:
 *
 *   • findAndHookMethod("com.example.target.MainActivity",
 *       lpparam.classLoader, ...)   — the target class must resolve
 *
 *   • findAndHookMethod(MyHook.class.getName(),
 *       lpparam.classLoader, ...)   — the module's own class must
 *                                       resolve (self-hook pattern)
 *
 *   • XposedBridge.log(...)         — the framework shim classes
 *                                       must resolve
 *
 * DELEGATION ORDER
 * ----------------
 *   1. Bootstrap classes (java.*, javax.*, sun.*, dalvik.*,
 *      android.*) resolve through the standard parent chain.
 *
 *   2. Module loader — the module's own classes win here.
 *
 *   3. Target loader — the target app's classes.
 *
 *   4. Framework loader — the shim classes and anything else
 *      the framework itself loaded.
 *
 *   5. Parent — standard fallback.
 *
 * Resources follow the same order.
 *
 * WHY THIS ISN'T A DexClassLoader SUBCLASS
 * ----------------------------------------
 * DexClassLoader delegates parent-first. If the target loader
 * were the parent, every class name would resolve through the
 * target before the module's dex was consulted — breaking the
 * self-hook pattern. A fresh ClassLoader with an explicit
 * loadClass() override gives us the delegation order we need.
 */
public final class ModuleClassLoader extends ClassLoader {

    private final ClassLoader moduleLoader;
    private final ClassLoader targetLoader;
    private final ClassLoader frameworkLoader;

    public ModuleClassLoader(ClassLoader moduleLoader,
                             ClassLoader targetLoader,
                             ClassLoader frameworkLoader) {
        super(frameworkLoader != null
                ? frameworkLoader
                : ModuleClassLoader.class.getClassLoader());
        this.moduleLoader = moduleLoader;
        this.targetLoader = targetLoader;
        this.frameworkLoader = frameworkLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {

        // Bootstrap classes go through the standard parent chain.
        if (isBootstrapClass(name)) {
            return super.loadClass(name, resolve);
        }

        // 1. Module loader — the module's own classes win here.
        if (moduleLoader != null) {
            try {
                Class<?> c = moduleLoader.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {}
        }

        // 2. Target loader — the target app's classes.
        if (targetLoader != null) {
            try {
                Class<?> c = targetLoader.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {}
        }

        // 3. Framework loader — the shim classes.
        if (frameworkLoader != null
                && frameworkLoader != moduleLoader
                && frameworkLoader != targetLoader) {
            try {
                Class<?> c = frameworkLoader.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {}
        }

        // 4. Parent.
        return super.loadClass(name, resolve);
    }

    private static boolean isBootstrapClass(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.startsWith("java.")
            || name.startsWith("javax.")
            || name.startsWith("sun.")
            || name.startsWith("dalvik.")
            || name.startsWith("android.");
    }

    @Override
    public URL getResource(String name) {
        if (moduleLoader != null) {
            URL u = moduleLoader.getResource(name);
            if (u != null) return u;
        }
        if (targetLoader != null) {
            URL u = targetLoader.getResource(name);
            if (u != null) return u;
        }
        if (frameworkLoader != null) {
            URL u = frameworkLoader.getResource(name);
            if (u != null) return u;
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (moduleLoader != null) {
            for (Enumeration<URL> e = moduleLoader.getResources(name);
                    e.hasMoreElements(); ) {
                URL u = e.nextElement();
                if (seen.add(u.toString())) all.add(u);
            }
        }
        if (targetLoader != null) {
            for (Enumeration<URL> e = targetLoader.getResources(name);
                    e.hasMoreElements(); ) {
                URL u = e.nextElement();
                if (seen.add(u.toString())) all.add(u);
            }
        }
        if (frameworkLoader != null) {
            for (Enumeration<URL> e = frameworkLoader.getResources(name);
                    e.hasMoreElements(); ) {
                URL u = e.nextElement();
                if (seen.add(u.toString())) all.add(u);
            }
        }
        for (Enumeration<URL> e = super.getResources(name);
                e.hasMoreElements(); ) {
            URL u = e.nextElement();
            if (seen.add(u.toString())) all.add(u);
        }
        return Collections.enumeration(all);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (moduleLoader != null) {
            InputStream is = moduleLoader.getResourceAsStream(name);
            if (is != null) return is;
        }
        if (targetLoader != null) {
            InputStream is = targetLoader.getResourceAsStream(name);
            if (is != null) return is;
        }
        if (frameworkLoader != null) {
            InputStream is = frameworkLoader.getResourceAsStream(name);
            if (is != null) return is;
        }
        return super.getResourceAsStream(name);
    }

    @Override
    public String toString() {
        return "ModuleClassLoader{"
            + "module=" + (moduleLoader != null
                ? moduleLoader.getClass().getSimpleName() : "null")
            + ", target=" + (targetLoader != null
                ? targetLoader.getClass().getSimpleName() : "null")
            + ", framework=" + (frameworkLoader != null
                ? frameworkLoader.getClass().getSimpleName() : "null")
            + "}";
    }

    public ClassLoader getModuleLoader() {
        return moduleLoader;
    }

    public ClassLoader getTargetLoader() {
        return targetLoader;
    }
}