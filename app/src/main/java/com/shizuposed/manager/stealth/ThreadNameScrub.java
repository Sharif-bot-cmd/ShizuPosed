package com.shizuposed.manager.stealth;

import de.robv.android.xposed.XposedBridge;

/**
 * ThreadNameScrub
 *
 * Renames ShizuPosed's own threads to innocuous names. Detection
 * kits sometimes enumerate threads via Thread.getAllStackTraces()
 * or Thread.enumerate() and look for suspicious thread names.
 *
 * ShizuPosed spawns threads named "ShizuPosedWorker" and
 * "ShizuPosed-launch". This class renames them to "pool-<n>-thread-<m>"
 * patterns that match the JVM's default thread pool naming.
 *
 * This runs in the shell-side process (XposedHook), not in the
 * target. It's called by XposedHook.main() before any module
 * loading begins, so any thread created after that point gets a
 * non-identifying name.
 *
 * Existing threads can't be renamed retroactively without
 * reflection into Thread's private `name` field. That reflection
 * is available on most ROMs but not universally.
 */
public final class ThreadNameScrub {

    private static final String TAG = "XStealth";

    private static final String[] WATCHED_PREFIXES = {
        "ShizuPosed",
        "XposedHook",
        "Xposed",
    };

    private ThreadNameScrub() {}

    /**
     * Rename every live thread whose name matches a watched
     * prefix. Called once from XposedHook.main() after the shell
     * base is resolved but before module loading.
     *
     * Uses Thread.enumerate() to find threads, then reflectively
     * sets the private `name` field on each match. If reflection
     * fails (rare), the thread stays with its original name —
     * which is worse for stealth but not fatal.
     */
    public static void scrub() {
        try {
            Thread[] threads = new Thread[
                Thread.activeCount() * 2 + 8];
            int n = Thread.enumerate(threads);
            int renamed = 0;

            for (int i = 0; i < n; i++) {
                Thread t = threads[i];
                if (t == null) continue;
                String name = t.getName();
                if (name == null) continue;

                boolean matches = false;
                for (String prefix : WATCHED_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;

                String sanitized = "pool-" + (i + 1) + "-thread-" + (i + 1);
                try {
                    java.lang.reflect.Field nameField =
                        Thread.class.getDeclaredField("name");
                    nameField.setAccessible(true);
                    nameField.set(t, sanitized);
                    renamed++;
                } catch (Throwable ignored) {}
            }

            if (renamed > 0) {
                XposedBridge.log(TAG + ": renamed " + renamed
                    + " thread(s)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ThreadNameScrub failed: "
                + t.getMessage());
        }
    }
}