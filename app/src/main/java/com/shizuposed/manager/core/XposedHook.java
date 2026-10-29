package com.shizuposed.manager.core;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;

import com.shizuposed.manager.core.compat.AndroidCompat;
import com.shizuposed.manager.core.compat.HiddenApiBypass;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XResources;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
public class XposedHook {

    private static final String SHELL_BASE_PRIMARY =
        "/data/user/0/com.android.shell/files/.syscall_cache";

    private static final String SHELL_BASE_FALLBACK =
        "/data/local/tmp/shizuposed";

    private static final String BASE_DIR;
    private static final String MODULES_DIR;
    private static final String HOOKED_DIR;
    private static final String LIBS_DIR;
    private static final String STATUS_FILE;
    private static final String LOG_FILE;

    static {
        String base = resolveShellBaseFromProperty();
        BASE_DIR     = base;
        MODULES_DIR  = base + "/modules";
        HOOKED_DIR   = base + "/hooked";
        LIBS_DIR     = base + "/libs";
        STATUS_FILE  = base + "/status";
        LOG_FILE     = base + "/xposed.log";
    }

    private static final String LIB_AMIRU       = "libamiru.so";
    private static final String LIB_SHIZUPOSED  = "libshizuposed.so";
    private static final String LIB_CALLSITE    = "libcallsite.so";
    private static final String LIB_SYSKU       = "libsysku.so";

    /**
     * Heartbeat freshness threshold. The Sysku ADB module's
     * service.sh writes <shell-base>/sysku/heartbeat.json every
     * 60 seconds. A heartbeat older than this is treated as
     * "module not running".
     */
    private static final long SYSKU_HEARTBEAT_FRESH_MS = 5 * 60 * 1000L;

    private static int myPid = 0;
    private static int myUid = 0;
    private static String targetPackage = null;
    private static int targetUid = -1;

    private static final Map<String, Object> moduleInstances = new ConcurrentHashMap<>();
    private static final List<String> loadedModuleNames = new ArrayList<>();

    private static volatile boolean amiruLoaded = false;
    private static volatile boolean shizuposedLoaded = false;
    private static volatile boolean syskuLoadOk = false;
    private static volatile boolean syskuFallback = false;

    private static final class ModuleInfo {
        String packageName;
        String name;
        String xposedInit;
        String cachedDexPath;
        String apkPath;
        boolean enabled;
        Set<String> hookedApps = new HashSet<>();
        boolean hookAllApps;
        boolean hookSystemApps;
    }

    // ═════════════════════════════════════════════════════════════════
    // SHELL BASE RESOLUTION
    // ═════════════════════════════════════════════════════════════════

    private static String resolveShellBaseFromProperty() {
        String fromProp = System.getProperty("shizuposed.shell.base");
        if (fromProp != null && !fromProp.isEmpty()) {
            return fromProp;
        }

        if (isDirWritable(SHELL_BASE_PRIMARY)) return SHELL_BASE_PRIMARY;
        if (isDirWritable(SHELL_BASE_FALLBACK)) return SHELL_BASE_FALLBACK;

        return SHELL_BASE_PRIMARY;
    }

    private static boolean isDirWritable(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".probe_" + Process.myPid());
            if (!probe.createNewFile()) return false;
            return probe.delete();
        } catch (Throwable t) {
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // ENTRY POINT
    // ═════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        try {
            myPid = Process.myPid();
            myUid = Process.myUid();

            synchronized (loadedModuleNames) {
                loadedModuleNames.clear();
            }
            moduleInstances.clear();

            initDirectories();

            preloadXStealthNative();

            try {
                com.shizuposed.manager.stealth.ThreadNameScrub.scrub();
            } catch (Throwable t) {
                log("ThreadNameScrub failed: " + t.getMessage());
            }

            logBox("XposedHook", "pid=" + myPid + " uid=" + myUid
                + " " + AndroidCompat.describe()
                + " args=" + java.util.Arrays.toString(args));
            log("Shell base: " + BASE_DIR);

            if (args.length >= 1) {
                targetPackage = args[0];
            }

            int expectedPid = -1;
            if (args.length >= 2) {
                try { expectedPid = Integer.parseInt(args[1]); }
                catch (NumberFormatException ignored) {}
            }

            if (args.length >= 3) {
                try { targetUid = Integer.parseInt(args[2]); }
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

            loadNativeLibs();

            boolean useBootstrap = (targetUid > 0);

            HookEngine.ensureBackendInstalled();

            try {
                int hookDelayMs = Integer.parseInt(
                    System.getProperty("shizuposed.hook.delay", "0"));
                if (hookDelayMs > 0) {
                    log("Hook delay: sleeping " + hookDelayMs + "ms before bootstrap");
                    try {
                        Thread.sleep(hookDelayMs);
                    } catch (InterruptedException ignored) {}
                    log("Hook delay: done");
                }
            } catch (Throwable t) {
                log("Hook delay parse failed: " + t.getMessage());
            }

            if (useBootstrap) {
                log("Mode: BOOTSTRAP (target uid " + targetUid + ")");
                boolean bootstrapped = runBootstrapMode(targetPackage);
                if (!bootstrapped) {
                    log("Bootstrap failed — falling back to post-Application mode");
                    writeStatus("BOOTSTRAP_FAILED", targetPackage);
                    runPostApplicationMode(targetPackage);
                }
            } else {
                log("Mode: POST-APPLICATION (no target uid provided)");
                runPostApplicationMode(targetPackage);
            }

        } catch (Throwable t) {
            log("Fatal: " + t);
            t.printStackTrace();
            try { writeStatus("ERROR", String.valueOf(t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // NATIVE LIBRARY LOADING
    // ═════════════════════════════════════════════════════════════════

    private static void preloadXStealthNative() {
        String libDir = System.getProperty("shizuposed.shell.libs", LIBS_DIR);
        log("Preloading XStealth native engines from: " + libDir);

        if (Build.VERSION.SDK_INT >= 35) {
            log("Skipping libxstealth.so preload on Android 15+ to prevent dlsym linker recursion");
        } else {
            try {
                boolean ok = com.shizuposed.manager.stealth.XStealthNative.load(libDir);
                if (ok) {
                    com.shizuposed.manager.stealth.XStealthNative.setActive(true);
                    log("Preloaded libxstealth.so");
                } else {
                    log("libxstealth.so preload returned false");
                }
            } catch (Throwable t) {
                log("libxstealth.so preload failed: " + t.getMessage());
            }
        }

        try {
            boolean ok = com.shizuposed.manager.stealth.XStealthNativeNext.load(libDir);
            if (ok) {
                com.shizuposed.manager.stealth.XStealthNativeNext.setActive(true);
                log("Preloaded libxstealth_next.so");
            } else {
                log("libxstealth_next.so preload returned false");
            }
        } catch (Throwable t) {
            log("libxstealth_next.so preload failed: " + t.getMessage());
        }

        try {
            boolean ok = com.shizuposed.manager.stealth.XStealthBridge.load(libDir);
            if (ok) {
                com.shizuposed.manager.stealth.XStealthBridge.setActive(true);
                log("Preloaded libxstealth_bridge.so");
            } else {
                log("libxstealth_bridge.so preload returned false");
            }
        } catch (Throwable t) {
            log("libxstealth_bridge.so preload failed: " + t.getMessage());
        }
    }

    private static void loadNativeLibs() {
        String libDir = System.getProperty("shizuposed.shell.libs", LIBS_DIR);
        log("Loading native libs from: " + libDir);

        String szpPath = libDir + "/" + LIB_SHIZUPOSED;
        try {
            System.load(szpPath);
            shizuposedLoaded = true;
            log("Loaded " + LIB_SHIZUPOSED + " from " + szpPath);
        } catch (Throwable t) {
            shizuposedLoaded = false;
            log(LIB_SHIZUPOSED + " not loaded: " + t.getMessage());
        }

        String amiruPath = libDir + "/" + LIB_AMIRU;
        try {
            System.load(amiruPath);
            amiruLoaded = true;
            log("Loaded " + LIB_AMIRU + " from " + amiruPath);
        } catch (Throwable t) {
            amiruLoaded = false;
            log(LIB_AMIRU + " not loaded (optional): " + t.getMessage());
        }

        // ── Sysku: tiered load ────────────────────────────────
        // Two conditions must both hold before System.load:
        //   • state.json says enabled: true (user's choice)
        //   • libsysku.so is present in <shell-base>/libs/
        //
        // The "is the module running" question is NOT a gate for
        // loading. It's passed as the second argument to
        // nativeInit. If the module isn't running, libsysku
        // loads in FALLBACK MODE — syscall() filtering only,
        // no getdents64 interposer. See libsysku.c's nativeInit
        // for what the flag does.
        String syskuPath = libDir + "/" + LIB_SYSKU;
        if (new File(syskuPath).exists()) {
            boolean userEnabled = isSyskuUserEnabled();
            boolean moduleRunning = isSyskuModuleRunning();

            if (!userEnabled) {
                syskuLoadOk = false;
                syskuFallback = false;
                log(LIB_SYSKU + " present but state.json says disabled — "
                    + "Sysku is off; skipping load");
            } else {
                try {
                    boolean loaded = com.shizuposed.manager.stealth.SyskuNative
                        .load(libDir);
                    if (loaded) {
                        String base = System.getProperty("shizuposed.shell.base",
                            "/data/user/0/com.android.shell/files/.syscall_cache");
                        int ready = com.shizuposed.manager.stealth.SyskuNative
                            .nativeInit(base, moduleRunning);
                        syskuFallback = !moduleRunning;
                        log("Loaded " + LIB_SYSKU + " from " + syskuPath
                            + " (nativeInit returned " + ready
                            + ", moduleRunning=" + moduleRunning
                            + (moduleRunning ? "" : ", FALLBACK MODE") + ")");
                        syskuLoadOk = (ready == 1);
                    } else {
                        syskuLoadOk = false;
                        syskuFallback = false;
                        log(LIB_SYSKU + " present but load() refused — check "
                            + "that libxstealth.so loaded first");
                    }
                } catch (Throwable t) {
                    syskuLoadOk = false;
                    syskuFallback = false;
                    log(LIB_SYSKU + " failed to load: " + t.getMessage());
                }
            }
        } else {
            syskuLoadOk = false;
            syskuFallback = false;
            log(LIB_SYSKU + " not present at " + syskuPath
                + " — syscall interposer unavailable");
        }

        String callsitePath = libDir + "/" + LIB_CALLSITE;
        if (new File(callsitePath).exists()) {
            log(LIB_CALLSITE + " present at " + callsitePath
                + " (load happens in HookEngine)");
        } else {
            log(LIB_CALLSITE + " not present — CallSite backend "
                + "will report unavailable");
        }

        log("Native engines: shizuposed=" + shizuposedLoaded
            + " amiru=" + amiruLoaded
            + " sysku=" + syskuLoadOk
            + (syskuLoadOk && syskuFallback ? " (fallback)" : ""));
    }

    /**
     * Does state.json say the user wants Sysku on?
     *
     * This is the user's choice, written by ShizuPosed →
     * Settings (via SyskuController.setEnabled()). It is NOT the
     * runtime signal — that's heartbeat freshness, checked by
     * isSyskuModuleRunning().
     *
     * Fails closed: any error returns false.
     */
    private static boolean isSyskuUserEnabled() {
        try {
            String base = System.getProperty("shizuposed.shell.base");
            if (base == null || base.isEmpty()) base = BASE_DIR;

            java.io.File state = new java.io.File(base, "sysku/state.json");
            if (!state.exists() || state.length() == 0) return false;

            byte[] buf = new byte[(int) state.length()];
            int n;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(state)) {
                n = fis.read(buf);
            }
            if (n <= 0) return false;

            String json = new String(buf, 0, n,
                java.nio.charset.StandardCharsets.UTF_8);
            if (!json.contains("\"schema\":1")) return false;
            return json.contains("\"enabled\":true");
        } catch (Throwable t) {
            log("isSyskuUserEnabled threw: " + t.getMessage());
            return false;
        }
    }

    /**
     * Is the Sysku ADB module running?
     *
     * Signal: <shell-base>/sysku/heartbeat.json, written by the
     * module's service.sh every 60 seconds. Absent, empty, or
     * older than SYSKU_HEARTBEAT_FRESH_MS means the module isn't
     * running.
     *
     * This is a load-mode hint, not a load gate. When false,
     * libsysku loads in fallback mode.
     *
     * Fails closed.
     */
    private static boolean isSyskuModuleRunning() {
        try {
            String base = System.getProperty("shizuposed.shell.base");
            if (base == null || base.isEmpty()) base = BASE_DIR;

            java.io.File hb = new java.io.File(base, "sysku/heartbeat.json");
            if (!hb.exists()) {
                log("isSyskuModuleRunning: heartbeat.json absent — "
                    + "module not running");
                return false;
            }
            if (hb.length() == 0) {
                log("isSyskuModuleRunning: heartbeat.json empty — "
                    + "module not running");
                return false;
            }

            long ageMs = System.currentTimeMillis() - hb.lastModified();
            if (ageMs < 0) {
                log("isSyskuModuleRunning: heartbeat mtime is in the future "
                    + "(ageMs=" + ageMs + ") — assuming running");
                return true;
            }
            if (ageMs > SYSKU_HEARTBEAT_FRESH_MS) {
                log("isSyskuModuleRunning: heartbeat stale (ageMs=" + ageMs
                    + ", threshold=" + SYSKU_HEARTBEAT_FRESH_MS + ") — "
                    + "module not running");
                return false;
            }
            return true;
        } catch (Throwable t) {
            log("isSyskuModuleRunning threw: " + t.getMessage());
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MODE 1 — BOOTSTRAP
    // ═════════════════════════════════════════════════════════════════

    private static boolean runBootstrapMode(String pkg) {
        try {
            if (targetUid > 0 && targetUid != myUid) {
                boolean switched = switchToUser(targetUid);
                if (switched) {
                    myUid = Process.myUid();
                    log("Switched to target uid " + myUid);
                } else {
                    log("Continuing as uid " + myUid
                        + " (target uid " + targetUid + ")");
                }
            }

            ApplicationInfo appInfo = resolveApplicationInfo(pkg);
            if (appInfo == null) {
                log("Could not resolve ApplicationInfo for " + pkg);
                return false;
            }
            log("Resolved ApplicationInfo for " + pkg
                + " (uid=" + appInfo.uid
                + ", sourceDir=" + appInfo.sourceDir + ")");

            Class<?> at = Class.forName("android.app.ActivityThread");
            Object activityThread = obtainActivityThread(at);
            if (activityThread == null) {
                log("Could not obtain ActivityThread");
                return false;
            }
            log("ActivityThread obtained: " + activityThread.getClass().getName());

            ClassLoader appLoader = resolveClassLoader(activityThread, appInfo);
            if (appLoader == null) {
                log("Could not resolve classloader for " + pkg);
                return false;
            }
            log("Using classloader: " + appLoader.getClass().getName());

            int hooksFrom = installModuleHooks(pkg, appLoader, appInfo);

            try {
                ClassLoadingBridge.install(appLoader);
                int preloaded = ClassLoadingBridge.preloadComponentClasses(
                    pkg, appInfo, appLoader);
                log("ClassLoadingBridge installed (preloaded "
                    + preloaded + " component class(es))");
            } catch (Throwable t) {
                log("ClassLoadingBridge setup failed: " + t.getMessage());
            }

            try {
                Resources targetResources = resolveTargetResources(activityThread, appInfo);
                if (targetResources != null) {
                    callInitPackageResources(pkg, targetResources);
                } else {
                    log("Could not resolve target Resources — resource hooks skipped");
                }
            } catch (Throwable t) {
                log("Resource hooking setup failed: " + t.getMessage());
            }

            try {
                com.shizuposed.manager.core.backends.InstrumentationBackend.install(pkg);
            } catch (Throwable t) {
                log("Instrumentation install failed: " + t.getMessage());
            }

            try {
                boolean atInstalled =
                    com.shizuposed.manager.core.backends.ApplicationThreadBackend
                        .install(activityThread);
                if (atInstalled) {
                    log("ApplicationThreadBackend installed");
                } else {
                    log("ApplicationThreadBackend installed no hooks");
                }
            } catch (Throwable t) {
                log("ApplicationThreadBackend failed: " + t.getMessage());
            }

            tryInstallContentProviders(activityThread, appInfo, appLoader);

            boolean bound = bindApplication(activityThread, appInfo, pkg);
            if (!bound) {
                log("handleBindApplication failed");
                return false;
            }

            log("Application bound; hooks live for everything after this point");

            try {
                Application boundApp = currentApplication();
                if (boundApp != null) {
                    com.shizuposed.manager.core.libxposed.LibXposedContext
                        .set(boundApp);

                    for (Map.Entry<String, Object> entry
                            : moduleInstances.entrySet()) {
                        Object inst = entry.getValue();
                        if (inst == null) continue;
                        if (!com.shizuposed.manager.core.libxposed.LibXposedDriver
                                .isLibXposedModule(inst.getClass())) {
                            continue;
                        }
                        com.shizuposed.manager.core.libxposed.LibXposedDriver
                            .firePackageReady(inst, inst.getClass(), appLoader,
                                              pkg, appInfo, true, boundApp, appLoader);
                    }
                }
            } catch (Throwable t) {
                log("libxposed onPackageReady pass failed: " + t.getMessage());
            }

            writeStatus("HOOKED",
                pkg + "|pid=" + myPid + "|modules=" + hooksFrom + "|bootstrap=true");

            writeHookedMarker(pkg, hooksFrom, "bootstrap");

            log("Entering Looper.loop()");
            Class<?> looperClass = Class.forName("android.os.Looper");
            try {
                Method prepare = looperClass.getDeclaredMethod("prepareMainLooper");
                HiddenApiBypass.forceAccessible(prepare);
                prepare.invoke(null);
            } catch (Throwable ignored) {}

            Method loop = looperClass.getMethod("loop");
            loop.invoke(null);

            log("Looper.loop() returned — process is exiting");
            return true;

        } catch (Throwable t) {
            log("runBootstrapMode failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static Object obtainActivityThread(Class<?> at) {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            if (looperClass.getMethod("myLooper").invoke(null) == null) {
                try {
                    Method prep = looperClass.getDeclaredMethod("prepareMainLooper");
                    HiddenApiBypass.forceAccessible(prep);
                    prep.invoke(null);
                } catch (Throwable ignored) {
                    try {
                        Method prep = looperClass.getDeclaredMethod("prepare");
                        HiddenApiBypass.forceAccessible(prep);
                        prep.invoke(null);
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            Constructor<?> ctor = at.getDeclaredConstructor();
            HiddenApiBypass.forceAccessible(ctor);
            Object t = ctor.newInstance();
            log("ActivityThread via constructor");

            try {
                Class<?> ccClass = Class.forName("android.app.ConfigurationController");
                Constructor<?> ccCtor = null;
                for (Constructor<?> c : ccClass.getDeclaredConstructors()) {
                    if (c.getParameterCount() == 1) {
                        ccCtor = c;
                        break;
                    }
                }
                if (ccCtor != null) {
                    HiddenApiBypass.forceAccessible(ccCtor);
                    Object cc = ccCtor.newInstance(t);
                    setFieldByShape(t, cc, "mConfigurationController", "configurationController");
                    log("Attached ConfigurationController to ActivityThread");
                }
            } catch (Throwable t2) {
                log("ConfigurationController setup failed: " + t2);
            }

            try {
                setFieldByShape(t, new android.os.Bundle(), "mCoreSettings", "coreSettings");
                log("Initialized ActivityThread.mCoreSettings");
            } catch (Throwable t2) {
                log("mCoreSettings setup failed: " + t2);
            }

            return t;
        } catch (Throwable t) {
            log("ActivityThread constructor threw: " + t + (t.getCause() != null ? " cause=" + t.getCause() : ""));
        }

        try {
            Method systemMain = findMethodByShape(at, "systemMain");
            if (systemMain != null) {
                Object t = systemMain.invoke(null);
                if (t != null) {
                    log("ActivityThread via systemMain()");
                    return t;
                }
            }
        } catch (Throwable t) {
            log("systemMain threw: " + t + (t.getCause() != null ? " cause=" + t.getCause() : ""));
        }

        try {
            Method current = findMethodByShape(at, "currentActivityThread");
            if (current != null) {
                Object t = current.invoke(null);
                if (t != null) {
                    log("ActivityThread via currentActivityThread()");
                    return t;
                }
            }
        } catch (Throwable t) {
            log("currentActivityThread threw: " + t + (t.getCause() != null ? " cause=" + t.getCause() : ""));
        }

        return null;
    }

    private static ClassLoader resolveClassLoader(Object activityThread,
                                                   ApplicationInfo appInfo) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Class<?> compatInfoClass =
                Class.forName("android.content.res.CompatibilityInfo");

            Object loadedApk = null;
            try {
                Method m1 = findMethodByShape(at, "getPackageInfoNoCheck",
                    appInfo, null);
                if (m1 != null) {
                    loadedApk = m1.invoke(activityThread, appInfo, null);
                }
            } catch (Throwable ignored) {}
            if (loadedApk == null) {
                try {
                    Method m2 = findMethodByShape(at, "getPackageInfoNoCheck",
                        appInfo);
                    if (m2 != null) {
                        loadedApk = m2.invoke(activityThread, appInfo);
                    }
                } catch (Throwable ignored) {}
            }
            if (loadedApk == null) {
                log("getPackageInfoNoCheck: no working overload");
                return null;
            }

            Method getClassLoader =
                findMethodByShape(loadedApk.getClass(), "getClassLoader");
            if (getClassLoader == null) {
                log("LoadedApk.getClassLoader not found");
                return null;
            }
            Object cl = getClassLoader.invoke(loadedApk);
            if (cl instanceof ClassLoader) return (ClassLoader) cl;
        } catch (Throwable t) {
            log("resolveClassLoader (via LoadedApk) failed: " + t.getMessage());
        }

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method getSystemContext =
                findMethodByShape(at, "getSystemContext");
            if (getSystemContext != null) {
                Object sysCtx = getSystemContext.invoke(activityThread);
                if (sysCtx != null) {
                    Method getClassLoader =
                        findMethodByShape(sysCtx.getClass(), "getClassLoader");
                    if (getClassLoader != null) {
                        Object cl = getClassLoader.invoke(sysCtx);
                        if (cl instanceof ClassLoader) {
                            log("Using fallback classloader from system context");
                            return (ClassLoader) cl;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("resolveClassLoader (fallback) failed: " + t.getMessage());
        }

        return XposedHook.class.getClassLoader();
    }

    private static Resources resolveTargetResources(Object activityThread,
                                                     ApplicationInfo appInfo) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Class<?> compatInfoClass = Class.forName(
                "android.content.res.CompatibilityInfo");

            Object loadedApk = null;
            try {
                Method m = findMethodByShape(at, "getPackageInfoNoCheck",
                    appInfo, null);
                if (m != null) {
                    loadedApk = m.invoke(activityThread, appInfo, null);
                }
            } catch (Throwable ignored) {}
            if (loadedApk == null) {
                try {
                    Method m = findMethodByShape(at, "getPackageInfoNoCheck",
                        appInfo);
                    if (m != null) {
                        loadedApk = m.invoke(activityThread, appInfo);
                    }
                } catch (Throwable ignored) {}
            }
            if (loadedApk == null) return null;

            Object res = null;
            try {
                Method m = findMethodByShape(loadedApk.getClass(), "getResources");
                if (m != null) {
                    res = m.invoke(loadedApk);
                }
            } catch (Throwable ignored) {}
            if (res == null) {
                try {
                    Method m = findMethodByShape(loadedApk.getClass(),
                        "getResources", (Object) null);
                    if (m != null) {
                        res = m.invoke(loadedApk, (Object) null);
                    }
                } catch (Throwable ignored) {}
            }

            if (res instanceof Resources) {
                log("Resolved target Resources via LoadedApk");
                return (Resources) res;
            }
        } catch (Throwable t) {
            log("resolveTargetResources failed: " + t.getMessage());
        }
        return null;
    }

    private static boolean bindApplication(Object activityThread,
                                            ApplicationInfo appInfo,
                                            String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Class<?> dataClass = Class.forName(
                "android.app.ActivityThread$AppBindData");

            Object data = buildAppBindData(dataClass, appInfo, pkg);

            Method bind = null;
            try {
                bind = findMethodByShape(at, "handleBindApplication", data);
            } catch (Throwable ignored) {}
            if (bind == null) {
                try {
                    Method getSystemContext =
                        findMethodByShape(at, "getSystemContext");
                    Object sysCtx = getSystemContext == null
                        ? null : getSystemContext.invoke(activityThread);
                    if (sysCtx != null) {
                        bind = findMethodByShape(at, "handleBindApplication",
                            sysCtx, data);
                    }
                } catch (Throwable ignored) {}
            }
            if (bind == null) {
                bind = findMethodByShape(at, "bindApplication", data);
            }
            if (bind == null) {
                log("handleBindApplication / bindApplication: no usable form");
                return false;
            }

            Object[] invokeArgs;
            Class<?>[] params = bind.getParameterTypes();
            if (params.length == 1) {
                invokeArgs = new Object[]{ data };
            } else if (params.length == 2) {
                Method getSystemContext =
                    findMethodByShape(at, "getSystemContext");
                Object sysCtx = getSystemContext == null
                    ? null : getSystemContext.invoke(activityThread);
                invokeArgs = new Object[]{ sysCtx, data };
            } else {
                log("handleBindApplication has " + params.length
                    + " parameters — unknown form");
                return false;
            }

            bind.invoke(activityThread, invokeArgs);

            boolean bound = verifyApplicationBound(activityThread);
            if (!bound) {
                log("handleBindApplication returned but no Application "
                    + "was created — bind did not take effect");
                return false;
            }
            log("Verified: Application bound successfully");
            return true;

        } catch (Throwable t) {
            log("bindApplication failed: " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static boolean verifyApplicationBound(Object activityThread) {
        try {
            Class<?> c = activityThread.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("mInitialApplication");
                    HiddenApiBypass.forceAccessible(f);
                    Object app = f.get(activityThread);
                    if (app instanceof Application) return true;
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            log("verifyApplicationBound: field read failed: " + t.getMessage());
        }

        try {
            Application app = currentApplication();
            if (app != null) return true;
        } catch (Throwable t) {
            log("verifyApplicationBound: currentApplication failed: "
                + t.getMessage());
        }

        return false;
    }

    private static Object buildAppBindData(Class<?> dataClass,
                                            ApplicationInfo appInfo,
                                            String pkg) throws Throwable {
        Constructor<?> ctor = dataClass.getDeclaredConstructor();
        HiddenApiBypass.forceAccessible(ctor);
        Object data = ctor.newInstance();

        setFieldByShape(data, appInfo, "appInfo", "info");
        setFieldByShape(data, pkg, "processName", "procName", "process");
        setFieldByShape(data, null, "providers");
        setFieldByShape(data, 0, "debugMode", "debug");
        setFieldByShape(data, 0, "mDebugMode");

        try {
            long[] emptyChanges = new long[0];
            setFieldByShape(data, emptyChanges, "disabledCompatChanges", "mDisabledCompatChanges");
            setFieldByShape(data, emptyChanges, "loggableCompatChanges", "mLoggableCompatChanges");
        } catch (Throwable ignored) {}

        try {
            setFieldByShape(data, new android.os.Bundle(), "instrumentationArgs", "mInstrumentationArgs");
        } catch (Throwable ignored) {}

        try {
            android.content.res.Configuration config = new android.content.res.Configuration();
            config.setToDefaults();
            java.util.Locale defaultLocale = java.util.Locale.getDefault();
            if (defaultLocale == null) defaultLocale = java.util.Locale.US;
            config.setLocales(new android.os.LocaleList(defaultLocale));
            setFieldByShape(data, config, "config", "mConfig");
        } catch (Throwable ignored) {}

        try {
            Class<?> tf = Class.forName("android.graphics.Typeface");
            Class<?> sf = Class.forName("android.graphics.fonts.SystemFonts");

            Object fontConfig = null;
            try {
                Method getLegacyConfig = findMethodByShape(sf, "getSystemPreinstalledFontConfigFromLegacyXml");
                if (getLegacyConfig != null) {
                    HiddenApiBypass.forceAccessible(getLegacyConfig);
                    fontConfig = getLegacyConfig.invoke(null);
                }
            } catch (Throwable t) {
                log("getSystemPreinstalledFontConfigFromLegacyXml failed: " + t.getMessage());
            }

            if (fontConfig == null) {
                try {
                    Method getPreConfig = findMethodByShape(sf, "getSystemPreinstalledFontConfig");
                    if (getPreConfig != null) {
                        HiddenApiBypass.forceAccessible(getPreConfig);
                        fontConfig = getPreConfig.invoke(null);
                    }
                } catch (Throwable t) {
                    log("getSystemPreinstalledFontConfig failed: " + t.getMessage());
                }
            }

            if (fontConfig != null) {
                Method buildFallback = findMethodByShape(sf, "buildSystemFallback", fontConfig);
                if (buildFallback != null) {
                    HiddenApiBypass.forceAccessible(buildFallback);
                    Object fallbackMap = buildFallback.invoke(null, fontConfig);
                    Method buildTypefaces = findMethodByShape(sf, "buildSystemTypefaces", fontConfig, fallbackMap);
                    if (buildTypefaces != null) {
                        HiddenApiBypass.forceAccessible(buildTypefaces);
                        Object typefaceMap = buildTypefaces.invoke(null, fontConfig, fallbackMap);
                        if (typefaceMap instanceof java.util.Map) {
                            Method setMap = findMethodByShape(tf, "setSystemFontMap", typefaceMap);
                            if (setMap != null) {
                                HiddenApiBypass.forceAccessible(setMap);
                                setMap.invoke(null, typefaceMap);
                                log("Initialized Typeface.sSystemFontMap from system fonts");
                            }
                            Method serializeMap = findMethodByShape(tf, "serializeFontMap", typefaceMap);
                            if (serializeMap != null) {
                                HiddenApiBypass.forceAccessible(serializeMap);
                                Object shm = serializeMap.invoke(null, typefaceMap);
                                if (shm != null) {
                                    setFieldByShape(data, shm, "mSerializedSystemFontMap", "serializedSystemFontMap");
                                    log("Populated AppBindData.mSerializedSystemFontMap");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("Font map pre-initialization failed: " + t.getMessage());
        }

        try {
            Class<?> compatInfoClass = Class.forName(
                "android.content.res.CompatibilityInfo");
            Field defaultCompat = compatInfoClass.getField(
                "DEFAULT_COMPATIBILITY_INFO");
            Object compat = defaultCompat.get(null);
            setFieldByShape(data, compat,
                "compatInfo", "compatibilityInfo", "mCompatInfo");
        } catch (Throwable ignored) {}

        try {
            appInfo.processName = pkg;
        } catch (Throwable ignored) {}

        return data;
    }

    private static void tryInstallContentProviders(Object activityThread,
                                                    ApplicationInfo appInfo,
                                                    ClassLoader appLoader) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");

            Method getProviderList =
                findMethodByShape(at, "getProviderList", appInfo);
            if (getProviderList == null) {
                log("getProviderList not found");
                return;
            }
            Object providers = getProviderList.invoke(activityThread, appInfo);
            if (!(providers instanceof List)) {
                log("getProviderList returned no providers");
                return;
            }
            List<?> providerList = (List<?>) providers;
            if (providerList.isEmpty()) {
                log("Target has no ContentProviders");
                return;
            }

            Method getSystemContext =
                findMethodByShape(at, "getSystemContext");
            android.content.Context sysCtx = null;
            if (getSystemContext != null) {
                Object o = getSystemContext.invoke(activityThread);
                if (o instanceof android.content.Context) {
                    sysCtx = (android.content.Context) o;
                }
            }

            Method install = null;
            if (sysCtx != null) {
                install = findMethodByShape(at, "installContentProviders",
                    sysCtx, providerList);
            }
            if (install == null) {
                install = findMethodByShape(at, "installContentProviders",
                    providerList);
            }
            if (install == null) {
                log("installContentProviders not found");
                return;
            }

            if (install.getParameterCount() == 2) {
                install.invoke(activityThread, sysCtx, providerList);
            } else {
                install.invoke(activityThread, providerList);
            }
            log("Installed " + providerList.size() + " ContentProvider(s)");
        } catch (Throwable t) {
            log("installContentProviders skipped: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // BOOTSTRAP HELPERS
    // ═════════════════════════════════════════════════════════════════

    private static boolean switchToUser(int uid) {
        if (uid <= 0) return false;
        try {
            Class<?> osProcess = Class.forName("android.os.Process");
            Method setUid = osProcess.getDeclaredMethod("setUid", int.class);
            Method setGid = osProcess.getDeclaredMethod("setGid", int.class);
            HiddenApiBypass.forceAccessible(setUid);
            HiddenApiBypass.forceAccessible(setGid);

            setGid.invoke(null, uid);
            setUid.invoke(null, uid);

            Method myUid = osProcess.getMethod("myUid");
            int current = (Integer) myUid.invoke(null);
            return current == uid;
        } catch (Throwable t) {
            log("switchToUser(" + uid + ") failed: " + t.getMessage());
            return false;
        }
    }

    private static ApplicationInfo resolveApplicationInfo(String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method getPM = at.getMethod("getPackageManager");
            Object pm = getPM.invoke(null);

            // 1. Android 13+ (API 33-35): getApplicationInfo(String, long, int)
            try {
                Method getAppInfoModern = pm.getClass().getMethod(
                    "getApplicationInfo", String.class, long.class, int.class);
                int userId = (targetUid > 0) ? (targetUid / 100000) : 0;
                Object info = getAppInfoModern.invoke(pm, pkg, 0L, userId);
                if (info instanceof ApplicationInfo) {
                    return (ApplicationInfo) info;
                }
            } catch (NoSuchMethodException ignored) {}

            // 2. Legacy: getApplicationInfo(String, int)
            try {
                Method getApplicationInfo = pm.getClass().getMethod(
                    "getApplicationInfo", String.class, int.class);
                Object info = getApplicationInfo.invoke(pm, pkg, 0);
                if (info instanceof ApplicationInfo) {
                    return (ApplicationInfo) info;
                }
            } catch (NoSuchMethodException ignored) {}

            // 3. Fallback: reflectively find any getApplicationInfo method
            for (Method m : pm.getClass().getMethods()) {
                if ("getApplicationInfo".equals(m.getName())) {
                    Class<?>[] pTypes = m.getParameterTypes();
                    if (pTypes.length == 3 && pTypes[0] == String.class) {
                        int userId = (targetUid > 0) ? (targetUid / 100000) : 0;
                        Object res = m.invoke(pm, pkg, 0L, userId);
                        if (res instanceof ApplicationInfo) return (ApplicationInfo) res;
                    } else if (pTypes.length == 2 && pTypes[0] == String.class) {
                        Object res = m.invoke(pm, pkg, 0);
                        if (res instanceof ApplicationInfo) return (ApplicationInfo) res;
                    }
                }
            }
        } catch (Throwable t) {
            log("resolveApplicationInfo IPC failed: " + t.getMessage());
        }

        // 4. Fallback: construct ApplicationInfo from package path if IPC fails
        try {
            java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"pm", "path", pkg});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && line.startsWith("package:")) {
                    String baseApk = line.substring("package:".length()).trim();
                    ApplicationInfo ai = new ApplicationInfo();
                    ai.packageName = pkg;
                    ai.sourceDir = baseApk;
                    ai.publicSourceDir = baseApk;
                    ai.dataDir = "/data/user/0/" + pkg;
                    ai.uid = (targetUid > 0) ? targetUid : 10000;
                    log("resolveApplicationInfo synthesized from pm path: " + baseApk);
                    return ai;
                }
            }
        } catch (Throwable t) {
            log("resolveApplicationInfo pm path fallback failed: " + t.getMessage());
        }

        return null;
    }

    // ═════════════════════════════════════════════════════════════════
    // LOADPACKAGEPARAM BUILDER
    // ═════════════════════════════════════════════════════════════════

    private static XC_LoadPackage.LoadPackageParam buildLoadPackageParam(
            String pkg, ClassLoader appLoader, ApplicationInfo appInfo) {

        XC_LoadPackage.LoadPackageParam param =
            new XC_LoadPackage.LoadPackageParam();
        param.packageName = pkg;
        param.processName = pkg;
        param.classLoader = appLoader;
        param.isFirstApplication = true;

        if (appInfo == null) {
            appInfo = resolveApplicationInfo(pkg);
        }
        param.appInfo = appInfo;

        try {
            param.args = new Object[0];
        } catch (Throwable ignored) {}

        log("LoadPackageParam: pkg=" + param.packageName
            + " process=" + param.processName
            + " loader=" + (param.classLoader != null
                ? param.classLoader.getClass().getSimpleName() : "null")
            + " appInfo=" + (param.appInfo != null ? "set" : "null")
            + " isFirst=" + param.isFirstApplication);

        return param;
    }

    private static int installModuleHooks(String pkg,
                                          ClassLoader appLoader,
                                          ApplicationInfo appInfo) {
        try {
            List<ModuleInfo> modules = loadApplicableModules(pkg);
            if (modules.isEmpty()) {
                log("No applicable modules for " + pkg);
                try {
                    ResourceHooking.getInstanceSafe()
                        .installGlobalLayoutHook(pkg, appLoader);
                } catch (Throwable t) {
                    log("installGlobalLayoutHook failed: " + t.getMessage());
                }
                return 0;
            }

            int loaded = 0;
            for (ModuleInfo m : modules) {
                XC_LoadPackage.LoadPackageParam param =
                    buildLoadPackageParam(pkg, appLoader, appInfo);
                if (loadModule(m, null, appLoader, param)) loaded++;
            }

            try {
                ResourceHooking.getInstanceSafe()
                    .installGlobalLayoutHook(pkg, appLoader);
            } catch (Throwable t) {
                log("installGlobalLayoutHook failed: " + t.getMessage());
            }

            log("Installed hooks from " + loaded
                + " module(s) before Application start");
            return loaded;
        } catch (Throwable t) {
            log("installModuleHooks failed: " + t);
            t.printStackTrace();
            return 0;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // IXposedHookInitPackageResources
    // ═════════════════════════════════════════════════════════════════

    private static void callInitPackageResources(String pkg, Resources resources) {
        if (resources == null) {
            log("callInitPackageResources: resources is null, skipping");
            return;
        }

        ResourceHooking resourceHooking = ResourceHooking.getInstanceSafe();
        XResources xResources = resourceHooking.getOrCreateXResources(pkg, resources);

        XC_InitPackageResources.InitPackageResourcesParam param =
            new XC_InitPackageResources.InitPackageResourcesParam();
        param.packageName = pkg;
        param.res = xResources;

        int handled = 0;

        for (Map.Entry<String, Object> entry : moduleInstances.entrySet()) {
            Object instance = entry.getValue();
            if (instance == null) continue;

            if (com.shizuposed.manager.core.libxposed.LibXposedDriver
                    .isLibXposedModule(instance.getClass())) {
                continue;
            }

            try {
                Class<?> moduleClass = instance.getClass();
                Method target = null;
                for (Method m : moduleClass.getDeclaredMethods()) {
                    if (!m.getName().equals("handleInitPackageResources")) continue;
                    if (m.getParameterCount() != 1) continue;
                    target = m;
                    break;
                }
                if (target == null) continue;

                HiddenApiBypass.forceAccessible(target);
                log("Calling handleInitPackageResources on "
                    + moduleClass.getName() + " for pkg=" + pkg);
                target.invoke(instance, param);
                handled++;

            } catch (Throwable t) {
                log("handleInitPackageResources failed for " + entry.getKey()
                    + ": " + t.getMessage());
            }
        }

        log("handleInitPackageResources handled by " + handled + " module(s)");
        log("XResources for " + pkg + " has " + xResources.replacementCount()
            + " replacement(s)");

        if (xResources.replacementCount() > 0) {
            resourceHooking.installResourcesHooks(pkg, resources);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MODE 2 — POST-APPLICATION
    // ═════════════════════════════════════════════════════════════════

    private static void runPostApplicationMode(String pkg) {
        log("Post-application injection for " + pkg);

        try {
            Application app = currentApplication();
            if (app == null) {
                log("Application not available yet — cannot install hooks.");
                writeStatus("ERROR", "no Application");
                return;
            }

            String appPkg = app.getPackageName();
            if (appPkg != null && !appPkg.equals(pkg)) {
                log("Application package is " + appPkg + ", expected " + pkg);
            }

            ClassLoader appLoader = app.getClassLoader();

            List<ModuleInfo> modules = loadApplicableModules(pkg);
            if (modules.isEmpty()) {
                log("No applicable modules for " + pkg);
                writeStatus("NO_MODULES", pkg);
                return;
            }

            ApplicationInfo appInfo = null;
            try {
                appInfo = app.getApplicationInfo();
            } catch (Throwable ignored) {}

            int loaded = 0;
            for (ModuleInfo m : modules) {
                XC_LoadPackage.LoadPackageParam param =
                    buildLoadPackageParam(pkg, appLoader, appInfo);
                if (loadModule(m, app, appLoader, param)) loaded++;
            }

            try {
                com.shizuposed.manager.core.libxposed.LibXposedContext.set(app);

                ApplicationInfo postAppInfo = app.getApplicationInfo();
                for (Map.Entry<String, Object> entry : moduleInstances.entrySet()) {
                    Object inst = entry.getValue();
                    if (inst == null) continue;
                    if (!com.shizuposed.manager.core.libxposed.LibXposedDriver
                            .isLibXposedModule(inst.getClass())) {
                        continue;
                    }
                    com.shizuposed.manager.core.libxposed.LibXposedDriver
                        .firePackageReady(inst, inst.getClass(), appLoader,
                                          pkg, postAppInfo, true, app, appLoader);
                }
            } catch (Throwable t) {
                log("libxposed onPackageReady pass failed (post-app): "
                    + t.getMessage());
            }

            try {
                ClassLoadingBridge.install(appLoader);
                if (appInfo != null) {
                    ClassLoadingBridge.preloadComponentClasses(
                        pkg, appInfo, appLoader);
                }
            } catch (Throwable t) {
                log("ClassLoadingBridge setup failed: " + t.getMessage());
            }

            try {
                Resources appResources = app.getResources();
                if (appResources != null) {
                    callInitPackageResources(pkg, appResources);
                } else {
                    log("No Resources object on Application");
                }
            } catch (Throwable t) {
                log("Resource hooking setup failed: " + t.getMessage());
            }

            try {
                ResourceHooking.getInstanceSafe()
                    .installGlobalLayoutHook(pkg, appLoader);
            } catch (Throwable t) {
                log("installGlobalLayoutHook failed: " + t.getMessage());
            }

            log("Injection complete: " + loaded
                + " module(s) loaded into " + pkg + " (pid=" + myPid + ")");
            writeStatus("HOOKED", pkg + "|pid=" + myPid + "|modules=" + loaded
                + "|bootstrap=false");

            writeHookedMarker(pkg, loaded, "post-application");

        } catch (Throwable t) {
            log("runPostApplicationMode failed: " + t);
            t.printStackTrace();
            try { writeStatus("ERROR", String.valueOf(t.getMessage())); }
            catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // HOOKED MARKER
    // ═════════════════════════════════════════════════════════════════

    private static void writeHookedMarker(String pkg, int modulesLoaded, String mode) {
        try {
            File dir = new File(HOOKED_DIR);
            if (!dir.exists()) dir.mkdirs();

            File f   = new File(dir, pkg + ".json");
            File tmp = new File(dir, pkg + ".json.tmp");

            JSONArray list = new JSONArray();
            synchronized (loadedModuleNames) {
                for (String name : loadedModuleNames) {
                    if (name != null) list.put(name);
                }
            }

            JSONObject engines = new JSONObject();
            engines.put("shizuposed", shizuposedLoaded);
            engines.put("amiru", amiruLoaded);
            engines.put("sysku", syskuLoadOk);
            engines.put("sysku_fallback", syskuFallback);

            JSONObject obj = new JSONObject();
            obj.put("pkg", pkg);
            obj.put("pid", myPid);
            obj.put("uid", myUid);
            obj.put("modules", modulesLoaded);
            obj.put("mode", mode == null ? "" : mode);
            obj.put("ts", System.currentTimeMillis());
            obj.put("moduleList", list);
            obj.put("engines", engines);

            String json = obj.toString();

            try (FileWriter w = new FileWriter(tmp, false)) {
                w.write(json);
                w.flush();
            }

            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) {
                    log("writeHookedMarker: rename failed for " + pkg);
                    tmp.delete();
                    return;
                }
            }

            try { f.setReadable(true, false); } catch (Throwable ignored) {}

            log("Wrote hooked marker: " + f.getAbsolutePath()
                + " (" + modulesLoaded + " modules, mode=" + mode
                + ", shizuposed=" + shizuposedLoaded
                + ", amiru=" + amiruLoaded
                + ", sysku=" + syskuLoadOk
                + (syskuFallback ? " (fallback)" : "")
                + ", list=" + list + ")");
        } catch (Throwable t) {
            log("writeHookedMarker failed: " + t.getMessage());
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

                boolean isBuiltIn = isBuiltInModule(info.packageName);

                if (!isBuiltIn) {
                    boolean isSelf = info.packageName != null
                        && info.packageName.equals(pkg);

                    if (!isSelf && !shouldHookApp(info, pkg)) continue;

                    if (info.cachedDexPath == null
                            || !new File(info.cachedDexPath).exists()) {
                        log("Skipping module " + info.packageName
                            + " — dex missing");
                        continue;
                    }

                    if (isSelf) {
                        log("loadApplicableModules: including module "
                            + info.packageName
                            + " for its own launch (self-hook)");
                    }
                }

                result.add(info);
            } catch (Throwable t) {
                log("parse failed for " + f.getName() + ": " + t.getMessage());
            }
        }
        return result;
    }

    private static boolean isBuiltInModule(String pkg) {
        return "com.shizuposed.manager.xstealth".equals(pkg);
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

            Class<?> moduleClass;
            String entry = info.xposedInit;
            ClassLoader moduleLoader = appLoader;

            if (info.cachedDexPath == null) {
                if (entry == null || entry.isEmpty()) {
                    log("Built-in module " + info.packageName
                        + " has no entry point");
                    return false;
                }
                moduleClass = Class.forName(entry, true,
                    XposedHook.class.getClassLoader());
                moduleLoader = XposedHook.class.getClassLoader();
            } else {
                String optDir;
                if (app != null && app.getCacheDir() != null) {
                    optDir = app.getCacheDir().getAbsolutePath();
                } else {
                    optDir = BASE_DIR + "/dexopt/" + info.packageName;
                    new File(optDir).mkdirs();
                }

                ClassLoader hookLoader = XposedHook.class.getClassLoader();
                ClassLoader combinedParent = new ClassLoader(appLoader) {
                    @Override
                    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        if (name.startsWith("de.robv.android.xposed.") || name.startsWith("com.shizuposed.")) {
                            try {
                                if (hookLoader != null) {
                                    return hookLoader.loadClass(name);
                                }
                            } catch (ClassNotFoundException ignored) {}
                        }
                        try {
                            return super.loadClass(name, resolve);
                        } catch (ClassNotFoundException e) {
                            if (hookLoader != null) {
                                return hookLoader.loadClass(name);
                            }
                            throw e;
                        }
                    }
                };

                DexClassLoader loader = new DexClassLoader(
                    info.cachedDexPath,
                    optDir,
                    null,
                    combinedParent);

                if (entry == null || entry.isEmpty()) {
                    entry = readXposedInitFromZip(info.cachedDexPath);
                }
                if (entry == null || entry.isEmpty()) {
                    entry = guessEntryPoint(loader, info.packageName);
                }
                if (entry == null) {
                    log("No entry point found for " + info.packageName);
                    return false;
                }

                moduleClass = loader.loadClass(entry);
                moduleLoader = loader;
            }

            if (param != null && moduleLoader != null) {
                ClassLoader prev = param.classLoader;
                param.classLoader = moduleLoader;
                if (prev != moduleLoader) {
                    log("Using module classloader for "
                        + info.packageName + ": "
                        + moduleLoader.getClass().getSimpleName()
                        + " (was: "
                        + (prev != null
                            ? prev.getClass().getSimpleName() : "null")
                        + ")");
                }
            }

            Constructor<?> ctor = moduleClass.getDeclaredConstructor();
            HiddenApiBypass.forceAccessible(ctor);
            Object instance = ctor.newInstance();

            tryEnableModuleDebug(moduleClass);

            boolean handled = false;

            if (instance instanceof de.robv.android.xposed.IXposedHookZygoteInit) {
                try {
                    log("Calling initZygote on " + instance.getClass().getName());
                    de.robv.android.xposed.IXposedHookZygoteInit.StartupParam sp =
                        new de.robv.android.xposed.IXposedHookZygoteInit.StartupParam();
                    sp.modulePath = info.apkPath;
                    sp.startsSystemServer = false;
                    ((de.robv.android.xposed.IXposedHookZygoteInit) instance).initZygote(sp);
                } catch (Throwable t) {
                    log("initZygote failed for " + info.packageName + ": " + t);
                }
            }

            if (com.shizuposed.manager.core.libxposed.LibXposedDriver
                    .isLibXposedModule(moduleClass)) {
                log("Module " + info.packageName + " is a libxposed module "
                    + "(API 101); driving through libxposed lifecycle");
                handled = com.shizuposed.manager.core.libxposed.LibXposedDriver
                    .driveModule(instance, moduleClass, moduleLoader,
                                 appLoader, param, info);
            } else if (instance instanceof IXposedHookLoadPackage) {
                log("Calling handleLoadPackage on "
                    + instance.getClass().getName()
                    + " for pkg=" + param.packageName);
                ((IXposedHookLoadPackage) instance).handleLoadPackage(param);
                handled = true;
            } else {
                for (Method m : moduleClass.getDeclaredMethods()) {
                    if (!m.getName().equals("handleLoadPackage")) continue;
                    if (m.getParameterCount() != 1) continue;
                    HiddenApiBypass.forceAccessible(m);
                    log("Calling handleLoadPackage (reflective) on "
                        + moduleClass.getName() + " for pkg=" + param.packageName);
                    m.invoke(instance, (Object) param);
                    handled = true;
                    break;
                }
            }

            if (param != null) {
                param.classLoader = appLoader;
            }

            if (handled) {
                moduleInstances.put(info.packageName, instance);

                synchronized (loadedModuleNames) {
                    if (!loadedModuleNames.contains(info.packageName)) {
                        loadedModuleNames.add(info.packageName);
                    }
                }

                log("Module loaded: " + info.packageName
                    + " via " + entry
                    + " (entry returned normally)");
            } else {
                log("Module has no recognized entry point: "
                    + info.packageName);
            }
            return handled;

        } catch (Throwable t) {
            log("loadModule failed for " + info.packageName + ": " + t);
            t.printStackTrace();
            return false;
        }
    }

    private static String readXposedInitFromZip(String dexPath) {
        if (dexPath == null) return null;
        File f = new File(dexPath);
        if (!f.exists()) return null;
        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry entry = zip.getEntry("assets/xposed_init");
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry);
                 BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line = r.readLine();
                if (line != null && !line.isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Throwable t) {
            log("readXposedInitFromZip(" + dexPath + ") failed: " + t.getMessage());
        }
        return null;
    }

    private static void tryEnableModuleDebug(Class<?> moduleClass) {
        String[] names = {"DEBUG", "debug", "LOGGING", "VERBOSE"};
        for (String name : names) {
            try {
                Field f = moduleClass.getDeclaredField(name);
                if (f.getType() != boolean.class) continue;
                HiddenApiBypass.forceAccessible(f);
                f.setBoolean(null, true);
                log("Enabled module debug flag: " + name
                    + " on " + moduleClass.getSimpleName());
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                log("Could not enable debug flag " + name + ": " + t.getMessage());
            }
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
            } catch (Throwable ignored) {}
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
            HiddenApiBypass.forceAccessible(currentApplication);
            Object result = currentApplication.invoke(null);
            return (Application) result;
        } catch (Throwable t) {
            log("currentApplication() failed: " + t.getMessage());
            return null;
        }
    }

    private static String currentProcessName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentProcessName");
            HiddenApiBypass.forceAccessible(m);
            Object v = m.invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return targetPackage;
    }

    // ═════════════════════════════════════════════════════════════════
    // REFLECTION HELPERS
    // ═════════════════════════════════════════════════════════════════

    private static Method findMethodByShape(Class<?> clazz,
                                            String name,
                                            Object... args) {
        if (clazz == null || name == null) return null;

        Method best = null;
        int bestScore = Integer.MAX_VALUE;

        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != args.length) continue;

            int score = 0;
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                Object arg = args[i];
                Class<?> param = params[i];

                if (arg == null) {
                    if (param.isPrimitive()) { ok = false; break; }
                    score += 1000;
                    continue;
                }

                Class<?> argClass = arg.getClass();

                if (param.isAssignableFrom(argClass)) {
                    score += distance(argClass, param);
                    continue;
                }

                if (isBoxedMatch(param, argClass)) {
                    score += 5;
                    continue;
                }

                ok = false;
                break;
            }

            if (!ok) continue;

            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }

        if (best != null) {
            try {
                HiddenApiBypass.forceAccessible(best);
            } catch (Throwable ignored) {}
            log("findMethodByShape: " + clazz.getSimpleName() + "."
                + name + "(" + argsToTypeNames(args) + ") -> "
                + describeMethod(best));
            return best;
        }

        log("findMethodByShape: no match for " + clazz.getSimpleName()
            + "." + name + "(" + argsToTypeNames(args) + ")");
        log("Available methods named " + name + ":\n"
            + describeMethods(clazz, name));
        return null;
    }

    private static boolean setFieldByShape(Object target,
                                           Object value,
                                           String... nameHints) {
        if (target == null) return false;

        Class<?> clazz = target.getClass();
        Class<?> valueClass = value == null ? null : value.getClass();

        List<Field> candidates = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Field[] fields = c.getDeclaredFields();
                if (fields != null) {
                    for (Field f : fields) candidates.add(f);
                }
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }

        for (String hint : nameHints) {
            if (hint == null) continue;
            for (Field f : candidates) {
                if (!f.getName().equals(hint)) continue;
                if (!isAssignable(valueClass, f.getType())) continue;
                if (setFieldQuiet(target, f, value)) {
                    log("setFieldByShape: " + clazz.getSimpleName() + "."
                        + hint + " (" + f.getType().getSimpleName()
                        + ") = " + brief(value));
                    return true;
                }
            }
        }

        if (valueClass != null) {
            List<Field> typeMatches = new ArrayList<>();
            for (Field f : candidates) {
                if (f.getName().startsWith("$")) continue;
                if (f.isSynthetic()) continue;
                if (isAssignable(valueClass, f.getType())) {
                    typeMatches.add(f);
                }
            }
            if (typeMatches.size() == 1) {
                Field f = typeMatches.get(0);
                if (setFieldQuiet(target, f, value)) {
                    log("setFieldByShape: " + clazz.getSimpleName() + "."
                        + f.getName() + " (" + f.getType().getSimpleName()
                        + ") = " + brief(value) + " [by type]");
                    return true;
                }
            }
        }

        log("setFieldByShape: no match on " + clazz.getSimpleName()
            + " for " + brief(value)
            + " (hints=" + java.util.Arrays.toString(nameHints) + ")");
        log("Available fields:\n" + describeFields(clazz));
        return false;
    }

    private static boolean setFieldQuiet(Object target, Field f, Object value) {
        try {
            HiddenApiBypass.forceAccessible(f);
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            log("setFieldQuiet(" + f.getName() + ") failed: "
                + t.getMessage());
            return false;
        }
    }

    private static int distance(Class<?> from, Class<?> to) {
        if (from == to) return 0;
        int d = 1;
        Class<?> c = from.getSuperclass();
        while (c != null) {
            if (c == to) return d;
            d++;
            c = c.getSuperclass();
        }
        for (Class<?> i : from.getInterfaces()) {
            if (i == to) return 100;
        }
        return 100;
    }

    private static boolean isAssignable(Class<?> valueClass, Class<?> param) {
        if (param == null) return false;
        if (valueClass == null) {
            return !param.isPrimitive();
        }
        if (param.isAssignableFrom(valueClass)) return true;
        return isBoxedMatch(param, valueClass);
    }

    private static boolean isBoxedMatch(Class<?> param, Class<?> valueClass) {
        return (param == int.class && valueClass == Integer.class)
            || (param == Integer.class && valueClass == int.class)
            || (param == long.class && valueClass == Long.class)
            || (param == Long.class && valueClass == long.class)
            || (param == boolean.class && valueClass == Boolean.class)
            || (param == Boolean.class && valueClass == boolean.class)
            || (param == short.class && valueClass == Short.class)
            || (param == Short.class && valueClass == short.class)
            || (param == byte.class && valueClass == Byte.class)
            || (param == Byte.class && valueClass == byte.class)
            || (param == char.class && valueClass == Character.class)
            || (param == Character.class && valueClass == char.class)
            || (param == float.class && valueClass == Float.class)
            || (param == Float.class && valueClass == float.class)
            || (param == double.class && valueClass == Double.class)
            || (param == Double.class && valueClass == double.class);
    }

    private static String argsToTypeNames(Object... args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            sb.append(a == null ? "null" : a.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static String describeMethod(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getName()).append("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        sb.append(")");
        return sb.toString();
    }

    private static String describeMethods(Class<?> clazz, String name) {
        StringBuilder sb = new StringBuilder();
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            sb.append("    ").append(describeMethod(m)).append("\n");
        }
        if (sb.length() == 0) sb.append("    (none)\n");
        return sb.toString();
    }

    private static String describeFields(Class<?> clazz) {
        StringBuilder sb = new StringBuilder();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                sb.append("    ")
                  .append(f.getType().getSimpleName())
                  .append(" ")
                  .append(f.getName())
                  .append("  [").append(c.getSimpleName()).append("]")
                  .append("\n");
            }
            c = c.getSuperclass();
        }
        return sb.toString();
    }

    private static String brief(Object o) {
        if (o == null) return "null";
        try {
            String s = String.valueOf(o);
            return s.length() > 60 ? s.substring(0, 57) + "..." : s;
        } catch (Throwable t) {
            return "<" + o.getClass().getSimpleName() + ">";
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // JSON PARSING
    // ═════════════════════════════════════════════════════════════════

    private static ModuleInfo parseModule(String json) {
        try {
            JSONObject o = new JSONObject(json);
            ModuleInfo i = new ModuleInfo();
            i.packageName    = o.optString("packageName", null);
            i.name           = o.optString("name", null);
            i.xposedInit     = o.optString("xposedInit", null);
            i.cachedDexPath  = o.optString("cachedDexPath", null);
            i.apkPath        = o.optString("apkPath", null);
            i.enabled        = o.optBoolean("enabled", false);
            i.hookAllApps    = o.optBoolean("hookAllApps", false);
            i.hookSystemApps = o.optBoolean("hookSystemApps", false);

            JSONArray apps = o.optJSONArray("hookedApps");
            if (apps != null) {
                for (int k = 0; k < apps.length(); k++) {
                    String a = apps.optString(k, null);
                    if (a != null && !a.isEmpty()) i.hookedApps.add(a);
                }
            }

            if (i.packageName == null || i.packageName.isEmpty()) return null;
            return i;
        } catch (Throwable t) {
            log("parseModule failed: " + t.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // FILE + LOGGING
    // ═════════════════════════════════════════════════════════════════

    private static void initDirectories() {
        try {
            new File(BASE_DIR).mkdirs();
            new File(MODULES_DIR).mkdirs();
            new File(HOOKED_DIR).mkdirs();
            new File(LIBS_DIR).mkdirs();
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

        System.out.println(line);

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
}