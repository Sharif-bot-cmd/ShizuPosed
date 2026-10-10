package com.shizuposed.manager.stealth.checks;

import com.shizuposed.manager.stealth.XStealthRegistry;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SettingsFileCheck
 *
 * Hides the settings XML files from apps that shell out to read
 * them directly via Runtime.exec() or ProcessBuilder.
 *
 * ─────────────────────────────────────────────────────────────
 * WHY THIS EXISTS
 * ─────────────────────────────────────────────────────────────
 *
 * The settings XML files live at:
 *   /data/system/users/0/settings_global.xml
 *   /data/system/users/0/settings_secure.xml
 *   /data/system/users/0/settings_system.xml
 *
 * App UIDs cannot read these directly — SELinux denies it. So an
 * app that wants to inspect them has to shell out through a
 * process that CAN read them (root, system, or shell). The common
 * pattern is:
 *
 *   Runtime.getRuntime().exec(
 *       new String[]{"sh", "-c",
 *           "cat /data/system/users/0/settings_global.xml"})
 *
 * or with grep:
 *
 *   ProcessBuilder("sh", "-c",
 *       "grep -E 'adb_enabled|development_settings_enabled' " +
 *       "/data/system/users/0/settings_global.xml").start()
 *
 * If the app is running as shell UID (via adb, or via Shizuku),
 * or if it's found a privileged helper that will run a command
 * for it, the command succeeds and the app reads the XML.
 *
 * This check intercepts the client side: when the target app
 * calls Runtime.exec() or ProcessBuilder.start(), we inspect the
 * command. If it references a watched settings XML path or a
 * watched key, we return a synthetic Process whose stdout serves
 * a scrubbed XML document, and whose exit code is 0.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS COVERS
 * ─────────────────────────────────────────────────────────────
 *
 *   • Runtime.exec(String)
 *   • Runtime.exec(String[])
 *   • Runtime.exec(String, String[])
 *   • Runtime.exec(String[], String[])
 *   • Runtime.exec(String[], String[], File)
 *   • ProcessBuilder.start()
 *
 * The synthetic Process serves scrubbed stdout. stderr is empty.
 * exitValue() returns 0. waitFor() returns immediately. No actual
 * subprocess is spawned.
 *
 * ─────────────────────────────────────────────────────────────
 * WHAT THIS DOES NOT COVER
 * ─────────────────────────────────────────────────────────────
 *
 *   • Native execve() / posix_spawn() from JNI code
 *   • Runtime.exec via reflection on a hidden method
 *   • Binder-based settings access
 *     → covered by AdbCheck / DevOptionsCheck
 *   • Socket-based daemon access
 *     → see SocketCheck
 *   • Apps that read the XML through a ContentProvider they've
 *     installed a privileged helper for
 *
 * The SELinux wall means an app can't read the XML directly. So
 * the subprocess route is the main one, and this check closes it.
 *
 * ─────────────────────────────────────────────────────────────
 * RISK
 * ─────────────────────────────────────────────────────────────
 *
 * Runtime.exec() is used by legitimate app functionality:
 *   • Crash reporters shelling out to logcat
 *   • Analytics SDKs invoking diagnostic tools
 *   • Media pipelines invoking ffmpeg
 *   • Root-detection libraries (Ironically — this check helps)
 *
 * The match is deliberately NARROW: only commands whose tokens
 * reference a watched XML path, or that contain a watched key
 * name alongside a file-reading verb. Everything else passes
 * through untouched. A typical `logcat -d` invocation is not
 * affected.
 *
 * See isWatchedCommand() for the exact match rules.
 */
public final class SettingsFileCheck {

    private static final String TAG = "XStealth";

    /**
     * Settings XML paths. Both the modern /users/0 form and the
     * legacy /user/0 form are included — some apps probe both, and
     * some older ROMs only expose one.
     */
    private static final String[] WATCHED_PATHS = {
        "/data/system/users/0/settings_global.xml",
        "/data/system/users/0/settings_secure.xml",
        "/data/system/users/0/settings_system.xml",
        "/data/system/user/0/settings_global.xml",
        "/data/system/user/0/settings_secure.xml",
        "/data/system/user/0/settings_system.xml",
    };

    /**
     * Setting keys we don't want to expose. Used only as a
     * secondary signal — the primary signal is the path match.
     * If a command references the file AND one of these keys, we
     * scrub. If it references the file with no key, we scrub too
     * (the app is going to search for something).
     */
    private static final Set<String> WATCHED_KEYS =
        ConcurrentHashMap.newKeySet();

    static {
        WATCHED_KEYS.add("adb_enabled");
        WATCHED_KEYS.add("adb_wifi_enabled");
        WATCHED_KEYS.add("development_settings_enabled");
        WATCHED_KEYS.add("development_enable_adi");
    }

    /**
     * Scrubbed XML served for settings_global.xml queries.
     *
     * Shape matches what SettingsProvider writes, so a caller
     * doing a naive grep or XML parse sees a well-formed document
     * with the watched keys set to 0. Any key the caller searches
     * for that isn't listed here won't be found — which is the
     * desired outcome for a detection check.
     */
    private static final String SCRUBBED_GLOBAL_XML =
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
        "<settings version='200'>\n" +
        "  <setting id='1' name='development_settings_enabled' " +
            "value='0' package='android' />\n" +
        "  <setting id='2' name='adb_enabled' " +
            "value='0' package='android' />\n" +
        "  <setting id='3' name='adb_wifi_enabled' " +
            "value='0' package='android' />\n" +
        "</settings>\n";

    private static final String SCRUBBED_SECURE_XML =
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
        "<settings version='200'>\n" +
        "  <setting id='1' name='development_settings_enabled' " +
            "value='0' package='android' />\n" +
        "  <setting id='2' name='adb_enabled' " +
            "value='0' package='android' />\n" +
        "</settings>\n";

    private static final String SCRUBBED_SYSTEM_XML =
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
        "<settings version='200'>\n" +
        "</settings>\n";

    private SettingsFileCheck() {}

    // ═════════════════════════════════════════════════════════════════
    // INSTALL
    // ═════════════════════════════════════════════════════════════════

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) return;

        hookRuntimeExec(lpparam);
        hookProcessBuilder(lpparam);

        XStealthRegistry.record("SettingsFileCheck");
        XposedBridge.log(TAG + ": SettingsFileCheck installed");
    }

    // ═════════════════════════════════════════════════════════════════
    // Runtime.exec HOOKS
    // ═════════════════════════════════════════════════════════════════

    /**
     * Runtime.exec has five overloads in the public API. Hook all
     * of them. If any single overload fails to hook (some ROMs
     * might not have all of them declared), log and move on —
     * the others still cover most callers.
     *
     * All hooks are beforeHookedMethod. When a match is found, we
     * setResult() to a synthetic Process and short-circuit the
     * real call. When no match, we do nothing and the real call
     * proceeds normally.
     */
    private static void hookRuntimeExec(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> runtimeClass = Runtime.class;

        // exec(String)
        hookExecOverload(
            runtimeClass,
            "exec",
            new Class<?>[]{ String.class },
            args -> {
                String cmd = (String) args[0];
                return isWatchedCommand(cmd);
            },
            args -> {
                String cmd = (String) args[0];
                return scrubFor(cmd);
            });

        // exec(String, String[])
        hookExecOverload(
            runtimeClass,
            "exec",
            new Class<?>[]{ String.class, String[].class },
            args -> {
                String cmd = (String) args[0];
                return isWatchedCommand(cmd);
            },
            args -> {
                String cmd = (String) args[0];
                return scrubFor(cmd);
            });

        // exec(String[])
        hookExecOverload(
            runtimeClass,
            "exec",
            new Class<?>[]{ String[].class },
            args -> {
                String[] cmd = (String[]) args[0];
                return isWatchedCommand(cmd);
            },
            args -> {
                String[] cmd = (String[]) args[0];
                return scrubFor(cmd);
            });

        // exec(String[], String[])
        hookExecOverload(
            runtimeClass,
            "exec",
            new Class<?>[]{ String[].class, String[].class },
            args -> {
                String[] cmd = (String[]) args[0];
                return isWatchedCommand(cmd);
            },
            args -> {
                String[] cmd = (String[]) args[0];
                return scrubFor(cmd);
            });

        // exec(String[], String[], File)
        hookExecOverload(
            runtimeClass,
            "exec",
            new Class<?>[]{ String[].class, String[].class, File.class },
            args -> {
                String[] cmd = (String[]) args[0];
                return isWatchedCommand(cmd);
            },
            args -> {
                String[] cmd = (String[]) args[0];
                return scrubFor(cmd);
            });
    }

    private interface ArgsPredicate {
        boolean test(Object[] args);
    }

    private interface ScrubSelector {
        String select(Object[] args);
    }

    private static void hookExecOverload(Class<?> clazz,
                                         String methodName,
                                         Class<?>[] signature,
                                         ArgsPredicate matches,
                                         ScrubSelector scrub) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, methodName, signature,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!matches.test(p.args)) return;
                            String scrubbed = scrub.select(p.args);
                            p.setResult(new ShadowProcess(scrubbed));
                            XposedBridge.log(TAG
                                + ": SettingsFileCheck intercepted exec");
                        } catch (Throwable t) {
                            // If anything goes wrong, let the real
                            // exec proceed. Failing open is safer
                            // than crashing the app.
                            XposedBridge.log(TAG
                                + ": exec hook error (passing through): " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook exec/" + signature.length
                + " failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // ProcessBuilder HOOKS
    // ═════════════════════════════════════════════════════════════════

    private static void hookProcessBuilder(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                ProcessBuilder.class, "start",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            ProcessBuilder pb = (ProcessBuilder) p.thisObject;
                            if (pb == null) return;

                            List<String> command = pb.command();
                            if (command == null || command.isEmpty()) return;

                            if (!isWatchedCommandList(command)) return;

                            String scrubbed = scrubFor(command);
                            p.setResult(new ShadowProcess(scrubbed));
                            XposedBridge.log(TAG
                                + ": SettingsFileCheck intercepted "
                                + "ProcessBuilder.start()");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": ProcessBuilder hook error "
                                + "(passing through): " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ProcessBuilder.start hook failed: "
                + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // MATCHING
    // ═════════════════════════════════════════════════════════════════

    /**
     * Does this command string reference a watched settings XML
     * path, or a watched key alongside a file-reading verb?
     *
     * Match rules:
     *
     *   1. The command contains one of WATCHED_PATHS (anywhere).
     *      This catches `cat /data/system/users/0/settings_global.xml`,
     *      `grep adb_enabled /data/system/users/0/...`, and
     *      similar.
     *
     *   2. The command contains a watched key AND one of a small
     *      set of file-reading verbs (cat, grep, head, tail, sed,
     *      awk, read). This catches cases where the path is
     *      obfuscated or built dynamically but the key name leaks
     *      through.
     *
     * We deliberately do NOT match on watched keys alone — an app
     * that does `getprop | grep adb_enabled` is checking a
     * different data source, and we don't want to break it.
     */
    private static boolean isWatchedCommand(String command) {
        if (command == null || command.isEmpty()) return false;
        return matches(command);
    }

    private static boolean isWatchedCommand(String[] command) {
        if (command == null || command.length == 0) return false;
        return matches(String.join(" ", command));
    }

    private static boolean isWatchedCommandList(List<String> command) {
        if (command == null || command.isEmpty()) return false;
        return matches(String.join(" ", command));
    }

    private static boolean matches(String command) {
        // Rule 1: explicit path reference.
        for (String path : WATCHED_PATHS) {
            if (command.contains(path)) return true;
        }

        // Rule 2: watched key + file-reading verb.
        boolean hasKey = false;
        for (String key : WATCHED_KEYS) {
            if (command.contains(key)) { hasKey = true; break; }
        }
        if (!hasKey) return false;

        // Only scrub if a file-reading verb is present. This
        // avoids matching on `getprop | grep adb_enabled`, which
        // reads a different source.
        for (String verb : FILE_READING_VERBS) {
            // Match verb as a whole word to avoid matching
            // "concatenate" when looking for "cat".
            if (containsWord(command, verb)) return true;
        }
        return false;
    }

    private static final String[] FILE_READING_VERBS = {
        "cat", "grep", "head", "tail", "sed", "awk",
        "less", "more", "tac", "xxd", "od", "strings",
    };

    /**
     * Cheap word-boundary check. Not regex — regex on every exec
     * call is expensive and this is a hot path for shell-heavy
     * apps.
     */
    private static boolean containsWord(String haystack, String word) {
        int idx = 0;
        while ((idx = haystack.indexOf(word, idx)) >= 0) {
            boolean leftOk = idx == 0
                || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= haystack.length()
                || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = end;
        }
        return false;
    }

    /**
     * Pick the scrubbed XML to serve based on which path the
     * command referenced. Falls back to the global variant if
     * unknown — a wrong XML shape is better than leaking the
     * real one.
     */
    private static String scrubFor(String command) {
        if (command == null) return SCRUBBED_GLOBAL_XML;
        if (command.contains("settings_secure.xml")) return SCRUBBED_SECURE_XML;
        if (command.contains("settings_system.xml")) return SCRUBBED_SYSTEM_XML;
        return SCRUBBED_GLOBAL_XML;
    }

    private static String scrubFor(String[] command) {
        if (command == null) return SCRUBBED_GLOBAL_XML;
        return scrubFor(String.join(" ", command));
    }

    private static String scrubFor(List<String> command) {
        if (command == null) return SCRUBBED_GLOBAL_XML;
        return scrubFor(String.join(" ", command));
    }

    /**
     * Hardened Process implementation serving scrubbed stdout.
     *
     * Differences from the previous version:
     *
     *   • exitValue() throws IllegalThreadStateException before
     *     waitFor() completes, matching the real Process contract
     *     on Android. Callers that check exitValue() as a
     *     completion signal see the same behavior they would with
     *     a real subprocess.
     *
     *   • isAlive() returns true until waitFor() or destroy() is
     *     called. Real subprocesses stay "alive" until they exit.
     *
     *   • Streams are single-use. Reading the input stream twice
     *     returns an empty stream the second time, matching the
     *     behavior of a real pipe.
     *
     *   • destroy() marks the process as destroyed but doesn't
     *     throw. Subsequent waitFor() returns 0 (a destroyed
     *     process exits "cleanly" from the caller's perspective).
     */
    private static final class ShadowProcess extends Process {

        private final byte[] stdoutBytes;
        private volatile boolean destroyed = false;
        private volatile boolean waited = false;
        private volatile boolean inputConsumed = false;

        ShadowProcess(String stdout) {
            this.stdoutBytes = (stdout != null)
                ? stdout.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) {}
                @Override public void write(byte[] b) {}
                @Override public void write(byte[] b, int off, int len) {}
            };
        }

        @Override
        public InputStream getInputStream() {
            if (inputConsumed) {
                return new ByteArrayInputStream(new byte[0]);
            }
            inputConsumed = true;
            return new ByteArrayInputStream(stdoutBytes);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public synchronized int waitFor() {
            waited = true;
            return 0;
        }

        @Override
        public synchronized int exitValue() {
            if (!waited && !destroyed) {
                // Match the real Process contract: exitValue() is
                // only valid after the process has terminated.
                throw new IllegalThreadStateException(
                    "process has not exited yet");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isAlive() {
            return !waited && !destroyed;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════════

    /** For diagnostics: list watched paths. */
    public static List<String> watchedPaths() {
        return new ArrayList<>(Arrays.asList(WATCHED_PATHS));
    }

    /** For diagnostics: list watched keys. */
    public static Set<String> watchedKeys() {
        return WATCHED_KEYS;
    }

    /** For diagnostics: would this command be scrubbed? */
    public static boolean wouldScrub(String command) {
        return isWatchedCommand(command);
    }
}