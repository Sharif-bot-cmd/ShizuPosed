package com.shizuposed.manager.core;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * XposedHook.java — In-process hook installer.
 *
 * ════════════════════════════════════════════════════════════════════
 * TIMING MODEL (accepted trade-off)
 * ════════════════════════════════════════════════════════════════════
 *
 * This class must be loaded INSIDE the target app's own process, and it
 * installs hooks AFTER Application#onCreate() has run. It is NOT zygote
 * timing and cannot hook code that runs during Application init.
 *
 * If this class is loaded into a process whose pid != the target pid,
 * it does nothing and exits with SKIPPED_NOT_IN_TARGET. It is not a
 * ptrace-style injector and cannot touch a running external process.
 *
 * The manager's ShizuPosedService.launchAppUnderShizuPosed(pkg) is the
 * launcher that arranges for this class to run inside the target.
 *
 * ════════════════════════════════════════════════════════════════════
 * HOOK BACKEND
 * ════════════════════════════════════════════════════════════════════
 *
 * me.weishu:epic — see HookEngine.installEpicBackend(). Every module
 * call to XposedHelpers.findAndHookMethod(...) routes through:
 *
 *     XposedHelpersImpl → XposedHookBridge → HookEngine (Epic backend)
 *
 * ════════════════════════════════════════════════════════════════════
 */
@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class XposedHook {

    // ─── paths (shell-side, written by ShizuPosedService) ────────────

    private static final String SHELL_FILES_DIR = "/data/user/0/com.android.shell/files";
    private static final String BASE_DIR        = SHELL_FILES_DIR + "/.syscall_cache";
    private static final String MODULES_DIR     = BASE_DIR + "/modules";
    private static final String STATUS_FILE     = BASE_DIR + "/status";
    private static final String LOG_FILE        = BASE_DIR + "/xposed.log";

    // ─── state ────────────────────────────────────────────────────────

    private static int myPid = 0;
    private static int myUid = 0;
    private static String targetPackage = null;

    private static final Map<String, Object> moduleInstances = new ConcurrentHashMap<>();

    // ─── data holder ──────────────────────────────────────────────────

    private static final class ModuleInfo {
        String packageName;
        String name;
        String xposedInit;
        String cachedDexPath;
        boolean enabled;
        Set<String> hookedApps = new HashSet<>();
        boolean hookAllApps;
        boolean hookSystemApps;
    }

    // ═════════════════════════════════════════════════════════════════
    // ENTRY POINT
    // ═════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        try {
            myPid = Process.myPid();
            myUid = Process.myUid();

            initDirectories();

            logBox("XposedHook", "pid=" + myPid + " uid=" + myUid
                + " api=" + Build.VERSION.SDK_INT + " args=" + java.util.Arrays.toString(args));

            // Parse arguments. Accepted forms:
            //   XposedHook <packageName> [expectedPid] [expectedUid]
            //
            // If expectedPid is provided and doesn't match Process.myPid(),
            // we refuse to run — the caller is trying to hook an external
            // process, which this class cannot do.
            if (args.length >= 1) {
                targetPackage = args[0];
            }

            int expectedPid = -1;
            if (args.length >= 2) {
                try { expectedPid = Integer.parseInt(args[1]); }
                catch (NumberFormatException ignored) {}
            }

            if (targetPackage == null || targetPackage.isEmpty()) {
                log("No target package provided — nothing to do.");
                writeStatus("IDLE", "no target package");
                return;
            }

            if (expectedPid > 0 && expectedPid != myPid) {
                log("Expected pid " + expectedPid + " but running as pid " + myPid);
                log("Refusing to run — cannot install hooks from an external process.");
                writeStatus("SKIPPED_NOT_IN_TARGET",
                    "myPid=" + myPid + " expectedPid=" + expectedPid);
                return;
            }

            // Make sure the Epic backend is installed *before* any module
            // calls XposedHelpers.findAndHookMethod(...).
            HookEngine.ensureBackendInstalled();

            performInjection(targetPackage);

        } catch (Throwable t) {
            log("Fatal: " + t);
            t.printStackTrace();
            try { writeStatus("ERROR", String.valueOf(t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // INJECTION
    // ═════════════════════════════════════════════════════════════════

    private static void performInjection(String pkg) {
        log("Injection requested for " + pkg);

        try {
            Application app = currentApplication();
            if (app == null) {
                log("Application not available yet — cannot install hooks.");
                writeStatus("ERROR", "no Application");
                return;
            }

            // Sanity: confirm the app's package matches what we were told.
            String appPkg = app.getPackageName();
            if (appPkg != null && !appPkg.equals(pkg)) {
                log("Application package is " + appPkg + ", expected " + pkg
                    + " — proceeding anyway, but this is unexpected.");
            }

            ClassLoader appLoader = app.getClassLoader();

            List<ModuleInfo> modules = loadApplicableModules(pkg);
            if (modules.isEmpty()) {
                log("No applicable modules for " + pkg);
                writeStatus("NO_MODULES", pkg);
                return;
            }

            XC_LoadPackage.LoadPackageParam param = new XC_LoadPackage.LoadPackageParam();
            param.packageName = pkg;
            param.processName = currentProcessName();
            param.classLoader = appLoader;
            param.isFirstApplication = true;
            try {
                param.appInfo = app.getApplicationInfo();
            } catch (Throwable ignored) {}

            int loaded = 0;
            for (ModuleInfo m : modules) {
                if (loadModule(m, app, appLoader, param)) loaded++;
            }

            log("Injection complete: " + loaded + " module(s) loaded into " + pkg
                + " (pid=" + myPid + ")");
            writeStatus("HOOKED", pkg + "|pid=" + myPid + "|modules=" + loaded);

        } catch (Throwable t) {
            log("performInjection failed: " + t);
            t.printStackTrace();
            try { writeStatus("ERROR", String.valueOf(t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MODULE DISCOVERY
    // ═════════════════════════════════════════════════════════════════

    private static List<ModuleInfo> loadApplicableModules(String pkg) {
        List<ModuleInfo> result = new ArrayList<>();

        File dir = new File(MODULES_DIR);
        if (!dir.exists()) {
            log("Modules dir missing: " + MODULES_DIR);
            return result;
        }

        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                String json = readAll(f);
                if (json == null) continue;

                ModuleInfo info = parseModule(json);
                if (info == null) continue;
                if (!info.enabled) continue;
                if (!shouldHookApp(info, pkg)) continue;

                if (info.cachedDexPath == null || !new File(info.cachedDexPath).exists()) {
                    log("Skipping module " + info.packageName + " — dex missing");
                    continue;
                }

                result.add(info);
            } catch (Throwable t) {
                log("parse failed for " + f.getName() + ": " + t.getMessage());
            }
        }
        return result;
    }

    private static boolean shouldHookApp(ModuleInfo module, String pkg) {
        if (!module.enabled) return false;
        if (module.hookAllApps) return true;
        if (module.hookSystemApps && isSystemPackage(pkg)) return true;
        return module.hookedApps != null && module.hookedApps.contains(pkg);
    }

    private static boolean isSystemPackage(String pkg) {
        return pkg.startsWith("android.")
            || pkg.startsWith("com.android.")
            || pkg.startsWith("com.google.android.");
    }

    // ═════════════════════════════════════════════════════════════════
    // MODULE LOADING
    // ═════════════════════════════════════════════════════════════════

    private static boolean loadModule(ModuleInfo info,
                                      Application app,
                                      ClassLoader appLoader,
                                      XC_LoadPackage.LoadPackageParam param) {
        try {
            log("Loading module: " + info.packageName);

            File cacheDir = app.getCacheDir();
            if (cacheDir == null) {
                log("No cache dir available for module dex opt");
                return false;
            }

            DexClassLoader loader = new DexClassLoader(
                info.cachedDexPath,
                cacheDir.getAbsolutePath(),
                null,
                appLoader);

            String entry = info.xposedInit;
            if (entry == null || entry.isEmpty()) {
                entry = guessEntryPoint(loader, info.packageName);
            }
            if (entry == null) {
                log("No entry point found for " + info.packageName);
                return false;
            }

            Class<?> moduleClass = loader.loadClass(entry);
            Constructor<?> ctor = moduleClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();

            boolean handled = false;

            if (instance instanceof IXposedHookLoadPackage) {
                ((IXposedHookLoadPackage) instance).handleLoadPackage(param);
                handled = true;
            } else {
                // Fallback: reflectively find handleLoadPackage(Object)
                for (Method m : moduleClass.getDeclaredMethods()) {
                    if (!m.getName().equals("handleLoadPackage")) continue;
                    if (m.getParameterCount() != 1) continue;
                    m.setAccessible(true);
                    m.invoke(instance, (Object) param);
                    handled = true;
                    break;
                }
            }

            if (handled) {
                moduleInstances.put(info.packageName, instance);
                log("Module loaded: " + info.packageName + " via " + entry);
            } else {
                log("Module has no handleLoadPackage: " + info.packageName);
            }
            return handled;

        } catch (Throwable t) {
            log("loadModule failed for " + info.packageName + ": " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static String guessEntryPoint(DexClassLoader loader, String pkg) {
        String[] candidates = {
            pkg + ".MainHook",
            pkg + ".XposedMain",
            pkg + ".Hook",
            pkg + ".XposedEntry",
            pkg + ".XposedModule",
            pkg + ".Module",
            pkg + ".Main",
            pkg + ".XposedInit"
        };
        for (String c : candidates) {
            try {
                loader.loadClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════════
    // APPLICATION / CONTEXT
    // ═════════════════════════════════════════════════════════════════

    private static Application currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentApplication = at.getMethod("currentApplication");
            return (Application) currentApplication.invoke(null);
        } catch (Throwable t) {
            log("currentApplication() failed: " + t.getMessage());
            return null;
        }
    }

    private static String currentProcessName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentProcessName");
            Object v = m.invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return targetPackage;
    }

    // ═════════════════════════════════════════════════════════════════
    // PROC HELPERS (only used when scanning, not for injection)
    // ═════════════════════════════════════════════════════════════════

    @SuppressWarnings("unused")
    private static String getProcessPackageName(int pid) {
        String raw = readAll(new File("/proc/" + pid + "/cmdline"));
        if (raw == null) return null;
        String clean = raw.replace("\0", "").replace("\n", "").trim();
        if (clean.isEmpty()) return null;
        if (clean.contains("/")) clean = clean.substring(clean.lastIndexOf('/') + 1);
        int colon = clean.indexOf(':');
        if (colon > 0) clean = clean.substring(0, colon);
        return clean;
    }

    // ═════════════════════════════════════════════════════════════════
    // JSON PARSING (small, self-contained)
    // ═════════════════════════════════════════════════════════════════

    private static ModuleInfo parseModule(String json) {
        try {
            ModuleInfo i = new ModuleInfo();
            i.packageName   = jval(json, "packageName");
            i.name          = jval(json, "name");
            i.xposedInit    = jval(json, "xposedInit");
            i.cachedDexPath = jval(json, "cachedDexPath");
            i.enabled       = "true".equals(jval(json, "enabled"));
            i.hookAllApps   = "true".equals(jval(json, "hookAllApps"));
            i.hookSystemApps= "true".equals(jval(json, "hookSystemApps"));

            String apps = jval(json, "hookedApps");
            if (apps != null && !apps.isEmpty() && !"null".equals(apps)) {
                apps = apps.replace("[", "").replace("]", "").replace("\"", "");
                for (String a : apps.split(",")) {
                    String t = a.trim();
                    if (!t.isEmpty()) i.hookedApps.add(t);
                }
            }
            return i;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String jval(String json, String key) {
        String needle = "\"" + key + "\":";
        int s = json.indexOf(needle);
        if (s < 0) return "";
        s += needle.length();
        while (s < json.length() && Character.isWhitespace(json.charAt(s))) s++;
        if (s >= json.length()) return "";
        char first = json.charAt(s);
        if (first == '"') {
            int e = json.indexOf('"', s + 1);
            return e < 0 ? "" : json.substring(s + 1, e);
        }
        int e = json.indexOf(',', s);
        if (e < 0) e = json.indexOf('}', s);
        if (e < 0) return "";
        return json.substring(s, e).trim();
    }

    // ═════════════════════════════════════════════════════════════════
    // FILE + LOGGING
    // ═════════════════════════════════════════════════════════════════

    private static void initDirectories() {
        try {
            new File(BASE_DIR).mkdirs();
            new File(MODULES_DIR).mkdirs();
        } catch (Throwable ignored) {}
    }

    private static String readAll(File f) {
        if (f == null || !f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        String line = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(new Date()) + "] [pid " + Process.myPid() + "] " + msg;

        // stdout (goes to logcat in an app_process; harmless in a plain app)
        System.out.println(line);

        // file
        try {
            File f = new File(LOG_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line);
                w.write('\n');
            }
        } catch (Throwable ignored) {}
    }

    private static void logBox(String title, String sub) {
        log("── " + title + " ──");
        log(sub);
    }

    private static void writeStatus(String status, String msg) {
        try {
            File f = new File(STATUS_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileWriter w = new FileWriter(f, false)) {
                w.write(status + "|" + System.currentTimeMillis() + "|" + msg);
            }
        } catch (Throwable ignored) {}
    }

    // silence unused-import warnings
    @SuppressWarnings("unused")
    private static final Map<String, String> __unused = new HashMap<>();
}