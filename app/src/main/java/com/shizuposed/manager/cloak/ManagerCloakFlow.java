package com.shizuposed.manager.cloak;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.ShizuPosedManagerApp;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ManagerCloakFlow {

    private static final String TAG = "ManagerCloakFlow";

    /** User both pm install and am start target. */
    private static final String TARGET_USER = "0";

    public interface ProgressListener {
        void onMessage(String text);
        void onSuccess(String newPackageName);
        void onError(String message);
    }

    private ManagerCloakFlow() {}

    // ═════════════════════════════════════════════════════════════
    // HIDE
    // ═════════════════════════════════════════════════════════════

    public static void hideShizuPosed(ProgressListener listener) {
        Context ctx = ShizuPosedManagerApp.getInstance();
        if (ctx == null) { listener.onError("No application context"); return; }

        String oldPackage = ctx.getPackageName();
        SharedPreferences settings = ctx.getSharedPreferences(
            "shizuposed_settings", Context.MODE_PRIVATE);
        String original = settings.getString("original_package_name", null);

        if (original == null) {
            listener.onError(
                "Original package name missing from preferences. "
                + "Reinstall the standard build to reseed it.");
            return;
        }
        if (!oldPackage.equals(original)) {
            listener.onError("Already cloaked as " + oldPackage
                + "; Unhide first.");
            return;
        }

        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(ctx);
            if (helper == null || !helper.isAuthorized()) {
                listener.onError("Shizuku is required");
                return;
            }

            String newPackage = PackageNameValidator
                .generateSameLengthPackageName(ctx, oldPackage.length());
            Log.i(TAG, "Hide: " + oldPackage + " -> " + newPackage
                + " (" + newPackage.length() + " chars)");

            listener.onMessage("Preparing migrate data…");
            File workDir = new File(ctx.getCacheDir(), "cloak");
            deleteRecursively(workDir);
            workDir.mkdirs();
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Patching manifest and re-signing…");
            File targetApk = ManagerCloak.buildCloakedApk(
                newPackage, migrateZip,
                new File(workDir, "cloaked-signed.apk"));

            installAndSwitch(ctx, helper, targetApk, oldPackage,
                newPackage, listener);

            Logger logger = Logger.getInstance(ctx);
            if (logger != null) {
                logger.i("Hid ShizuPosed: " + oldPackage + " -> " + newPackage);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hide failed", t);
            listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // UNHIDE
    // ═════════════════════════════════════════════════════════════

    public static void unhideShizuPosed(ProgressListener listener) {
        Context ctx = ShizuPosedManagerApp.getInstance();
        if (ctx == null) { listener.onError("No application context"); return; }

        String oldPackage = ctx.getPackageName();
        SharedPreferences settings = ctx.getSharedPreferences(
            "shizuposed_settings", Context.MODE_PRIVATE);
        String original = settings.getString("original_package_name", null);

        if (original == null) {
            listener.onError(
                "Original package name missing from preferences. "
                + "Cannot determine the restore target.");
            return;
        }
        if (oldPackage.equals(original)) {
            listener.onError("Already running as the original package");
            return;
        }
        if (oldPackage.length() != original.length()) {
            listener.onError(
                "Length mismatch: current=" + oldPackage.length()
                + " original=" + original.length()
                + ". The in-place patch requires equal lengths.");
            return;
        }

        try {
            ShizukuHelper helper = ShizukuHelper.getInstance(ctx);
            if (helper == null || !helper.isAuthorized()) {
                listener.onError("Shizuku is required");
                return;
            }

            listener.onMessage("Preparing migrate data…");
            File workDir = new File(ctx.getCacheDir(), "cloak-revert");
            deleteRecursively(workDir);
            workDir.mkdirs();
            File migrateZip = ManagerMigrate.createMigrateZip(
                ctx, new File(workDir, "migrate.zip"));

            listener.onMessage("Restoring original manifest and re-signing…");
            File targetApk = ManagerCloak.buildRestoredApk(
                original, migrateZip,
                new File(workDir, "restored-signed.apk"));

            installAndSwitch(ctx, helper, targetApk, oldPackage,
                original, listener);

            Logger logger = Logger.getInstance(ctx);
            if (logger != null) {
                logger.i("Unhid ShizuPosed: " + oldPackage + " -> " + original);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unhide failed", t);
            listener.onError(t.getMessage() != null ? t.getMessage() : t.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SHARED INSTALL SEQUENCE
    // ═════════════════════════════════════════════════════════════

    private static void installAndSwitch(Context ctx,
                                         ShizukuHelper helper,
                                         File targetApk,
                                         String oldPackage,
                                         String newPackage,
                                         ProgressListener listener) throws Exception {
        String base = ShizuPosedService.getResolvedShellBase();
        if (base == null) {
            listener.onError("Could not resolve shell base");
            return;
        }
        String shellApk = base + "/manager-target.apk";

        // ── Gate 1: stage ──
        listener.onMessage("Staging APK…");
        ShellUtils.CommandResult stage = helper.executeCommand(
            "cat '" + targetApk.getAbsolutePath() + "' > '" + shellApk + "'");
        if (stage == null || !stage.isSuccess()) {
            listener.onError("Could not stage APK: "
                + (stage != null ? stage.getStdoutString() : "null"));
            return;
        }

        // ── Cleanup: uninstall any stale install of newPackage ──
        ShellUtils.CommandResult stalePath = helper.executeCommand(
            "pm path '" + newPackage + "'");
        boolean staleExists = stalePath != null
            && stalePath.isSuccess()
            && stalePath.getStdoutString() != null
            && stalePath.getStdoutString().contains("package:");
        if (staleExists) {
            Log.i(TAG, "Removing stale install of " + newPackage
                + " from a previous attempt");
            helper.executeCommand("pm uninstall --user " + TARGET_USER
                + " '" + newPackage + "'");
        }

        // ── Gate 2: install (no -r, explicit user) ──
        listener.onMessage("Installing " + newPackage + "…");
        ShellUtils.CommandResult install = helper.executeCommand(
            "pm install --user " + TARGET_USER + " '" + shellApk + "'");
        String installOut = install != null ? install.getStdoutString() : null;
        boolean installOk = install != null && install.isSuccess()
            && installOut != null && installOut.contains("Success");
        Log.i(TAG, "pm install: stdout=[" + installOut + "]"
            + " success=" + installOk);
        if (!installOk) {
            helper.executeCommand("pm uninstall --user " + TARGET_USER
                + " '" + newPackage + "'");
            listener.onError("Install failed: " + installOut);
            return;
        }

        // ── Gate 3: read launcher class from the patched APK ──
        String launcherClass = readLauncherActivityFromApk(targetApk);
        if (launcherClass == null) {
            Log.w(TAG, "readLauncherActivityFromApk returned null for "
                + targetApk.getName() + "; rolling back");
            helper.executeCommand("pm uninstall --user " + TARGET_USER
                + " '" + newPackage + "'");
            listener.onError(
                "The patched APK has no launcher activity in its manifest. "
                + "The current package is still installed.");
            return;
        }

        // ── Gate 4: am start (explicit user, explicit component) ──
        String launcherComponent = newPackage + "/" + launcherClass;
        listener.onMessage("Launching " + newPackage + "…");
        ShellUtils.CommandResult start = helper.executeCommand(
            "am start --user " + TARGET_USER
            + " -n '" + launcherComponent + "'");
        String startOut = start != null ? start.getStdoutString() : null;
        Log.i(TAG, "am start: cmd=[am start -n " + launcherComponent + "]"
            + " stdout=[" + startOut + "]");

        boolean startFailed = start == null || !start.isSuccess()
            || startOut == null
            || startOut.contains("Error")
            || startOut.contains("does not exist");

        if (startFailed) {
            Log.w(TAG, "am start failed: " + startOut + "; rolling back");
            helper.executeCommand("pm uninstall --user " + TARGET_USER
                + " '" + newPackage + "'");
            listener.onError("Launch failed: " + startOut);
            return;
        }

        // ── Settle, then remove the original ──
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        listener.onMessage("Removing " + oldPackage + "…");
        ShellUtils.CommandResult uninstall = helper.executeCommand(
            "pm uninstall --user " + TARGET_USER + " '" + oldPackage + "'");
        if (uninstall == null || !uninstall.isSuccess()) {
            Log.w(TAG, "Uninstall old package failed: "
                + (uninstall != null ? uninstall.getStdoutString() : "null"));
        } else {
            Log.i(TAG, "Uninstalled old package " + oldPackage);
        }

        listener.onSuccess(newPackage);
    }

    // ═════════════════════════════════════════════════════════════
    // LAUNCHER ACTIVITY READER
    // ═════════════════════════════════════════════════════════════

    private static String readLauncherActivityFromApk(File apk) {
        if (apk == null || !apk.isFile()) return null;
        try {
            byte[] axml;
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry("AndroidManifest.xml");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] tmp = new byte[8192];
                    int n;
                    while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
                    axml = buf.toByteArray();
                }
            }
            return findLauncherActivity(axml);
        } catch (Throwable t) {
            Log.w(TAG, "readLauncherActivityFromApk(" + apk.getName()
                + ") failed: " + t.getMessage());
            return null;
        }
    }

    private static String findLauncherActivity(byte[] axml) {
        if (axml == null || axml.length < 0x24) return null;
        if (readU16(axml, 0) != 0x0003) return null;

        int poolOff = -1;
        int walkP = 8;
        while (walkP + 8 <= axml.length) {
            int type = readU16(axml, walkP);
            int headerSize = readU16(axml, walkP + 2);
            int chunkSize = readU32(axml, walkP + 4);
            if (headerSize < 8 || chunkSize < headerSize) break;
            if (walkP + chunkSize > axml.length) break;
            if (type == 0x0001) { poolOff = walkP; break; }
            walkP += chunkSize;
        }
        if (poolOff < 0) return null;

        int headerSize    = readU16(axml, poolOff + 2);
        int chunkSize     = readU32(axml, poolOff + 4);
        int stringCount   = readU32(axml, poolOff + 8);
        int flags         = readU32(axml, poolOff + 0x10);
        int stringsStart  = readU32(axml, poolOff + 0x14);
        boolean utf8      = (flags & 0x0100) != 0;

        if (headerSize < 0x1c) return null;
        if (poolOff + chunkSize > axml.length) return null;
        if (stringsStart < headerSize) return null;

        int offsetsStart     = poolOff + headerSize;
        int stringsDataStart = poolOff + stringsStart;
        int stringsEnd       = poolOff + chunkSize;

        String[] cache = new String[stringCount];
        String manifestPackage = null;

        Deque<ElementState> stack = new ArrayDeque<>();

        int p = poolOff + chunkSize;
        while (p + 8 <= axml.length) {
            int type  = readU16(axml, p);
            int hSize = readU16(axml, p + 2);
            int cSize = readU32(axml, p + 4);
            if (cSize < hSize || p + cSize > axml.length) break;

            if (type == 0x0102) {
                int extOff = p + hSize;
                int nameIdx   = readU32(axml, extOff + 4);
                int attrStart = readU16(axml, extOff + 8);
                int attrSize  = readU16(axml, extOff + 10);
                int attrCount = readU16(axml, extOff + 12);

                String elemName = resolveString(
                    axml, nameIdx, cache, offsetsStart,
                    stringsDataStart, stringsEnd, utf8);

                ElementState st = new ElementState(elemName);

                if ("manifest".equals(elemName)) {
                    for (int i = 0; i < attrCount; i++) {
                        int aoff = extOff + attrStart + i * attrSize;
                        int aNameIdx = readU32(axml, aoff + 4);
                        int aType    = axml[aoff + 15] & 0xFF;
                        int aData    = readU32(axml, aoff + 16);
                        String aName = resolveString(
                            axml, aNameIdx, cache, offsetsStart,
                            stringsDataStart, stringsEnd, utf8);
                        if ("package".equals(aName) && aType == 0x03) {
                            manifestPackage = resolveString(
                                axml, aData, cache, offsetsStart,
                                stringsDataStart, stringsEnd, utf8);
                        }
                    }
                } else if ("activity".equals(elemName)) {
                    for (int i = 0; i < attrCount; i++) {
                        int aoff = extOff + attrStart + i * attrSize;
                        int aNameIdx = readU32(axml, aoff + 4);
                        int aType    = axml[aoff + 15] & 0xFF;
                        int aData    = readU32(axml, aoff + 16);
                        String aName = resolveString(
                            axml, aNameIdx, cache, offsetsStart,
                            stringsDataStart, stringsEnd, utf8);
                        if ("name".equals(aName) && aType == 0x03) {
                            st.activityName = resolveString(
                                axml, aData, cache, offsetsStart,
                                stringsDataStart, stringsEnd, utf8);
                        }
                    }
                } else if ("action".equals(elemName)) {
                    for (int i = 0; i < attrCount; i++) {
                        int aoff = extOff + attrStart + i * attrSize;
                        int aNameIdx = readU32(axml, aoff + 4);
                        int aType    = axml[aoff + 15] & 0xFF;
                        int aData    = readU32(axml, aoff + 16);
                        String aName = resolveString(
                            axml, aNameIdx, cache, offsetsStart,
                            stringsDataStart, stringsEnd, utf8);
                        if ("name".equals(aName) && aType == 0x03) {
                            String action = resolveString(
                                axml, aData, cache, offsetsStart,
                                stringsDataStart, stringsEnd, utf8);
                            if ("android.intent.action.MAIN".equals(action)) {
                                for (ElementState ancestor : stack) {
                                    if ("intent-filter".equals(ancestor.name)) {
                                        ancestor.hasMain = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else if ("category".equals(elemName)) {
                    for (int i = 0; i < attrCount; i++) {
                        int aoff = extOff + attrStart + i * attrSize;
                        int aNameIdx = readU32(axml, aoff + 4);
                        int aType    = axml[aoff + 15] & 0xFF;
                        int aData    = readU32(axml, aoff + 16);
                        String aName = resolveString(
                            axml, aNameIdx, cache, offsetsStart,
                            stringsDataStart, stringsEnd, utf8);
                        if ("name".equals(aName) && aType == 0x03) {
                            String cat = resolveString(
                                axml, aData, cache, offsetsStart,
                                stringsDataStart, stringsEnd, utf8);
                            if ("android.intent.category.LAUNCHER".equals(cat)) {
                                for (ElementState ancestor : stack) {
                                    if ("intent-filter".equals(ancestor.name)) {
                                        ancestor.hasLauncher = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }

                stack.push(st);

            } else if (type == 0x0103) {
                int extOff = p + hSize;
                int nameIdx = readU32(axml, extOff + 4);
                String elemName = resolveString(
                    axml, nameIdx, cache, offsetsStart,
                    stringsDataStart, stringsEnd, utf8);

                if ("intent-filter".equals(elemName)) {
                    ElementState filter = stack.peek();
                    if (filter != null && filter.hasMain
                            && filter.hasLauncher) {
                        for (ElementState ancestor : stack) {
                            if ("activity".equals(ancestor.name)
                                    && ancestor.activityName != null) {
                                return expandRelativeName(
                                    ancestor.activityName, manifestPackage);
                            }
                        }
                    }
                }
                if (!stack.isEmpty()) stack.pop();
            }

            p += cSize;
        }
        return null;
    }

    private static String expandRelativeName(String name, String pkg) {
        if (name == null) return null;
        if (name.startsWith(".")) {
            if (pkg == null) return name;
            return pkg + name;
        }
        if (!name.contains(".")) {
            if (pkg == null) return name;
            return pkg + "." + name;
        }
        return name;
    }

    private static String resolveString(byte[] axml, int idx, String[] cache,
                                        int offsetsStart, int dataStart,
                                        int dataEnd, boolean utf8) {
        if (idx < 0 || idx >= cache.length) return null;
        String cached = cache[idx];
        if (cached != null) return cached;
        int abs = dataStart + readU32(axml, offsetsStart + idx * 4);
        cached = decodeAxmlString(axml, abs, dataEnd, utf8);
        cache[idx] = cached;
        return cached;
    }

    private static String decodeAxmlString(byte[] b, int start, int end,
                                           boolean utf8) {
        try {
            if (start >= end) return null;

            if (utf8) {
                int p = start;
                int charCount = 0;
                int shift = 0;
                while (p < end) {
                    int v = b[p++] & 0xFF;
                    charCount |= (v & 0x7F) << shift;
                    if ((v & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 28) return null;
                }
                if (p >= end) return null;

                int byteLen;
                int lenByte = b[p++] & 0xFF;
                if ((lenByte & 0x80) == 0) {
                    byteLen = lenByte;
                } else {
                    if (p >= end) return null;
                    byteLen = ((lenByte & 0x7F) << 8) | (b[p++] & 0xFF);
                }
                if (byteLen < 0 || p + byteLen > end) return null;
                return new String(b, p, byteLen,
                    java.nio.charset.StandardCharsets.UTF_8);
            } else {
                if (start + 2 > end) return null;
                int len = readU16(b, start);
                int p = start + 2;
                if ((len & 0x8000) != 0) {
                    if (start + 4 > end) return null;
                    len = ((len & 0x7FFF) << 16) | readU16(b, start + 2);
                    p = start + 4;
                }
                if (len < 0) return null;
                int byteLen = len * 2;
                if (byteLen < 0 || p + byteLen > end) return null;
                char[] chars = new char[len];
                for (int i = 0; i < len; i++) {
                    chars[i] = (char) readU16(b, p + i * 2);
                }
                return new String(chars);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] b, int off) {
        return (b[off] & 0xFF)
            | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16)
            | ((b[off + 3] & 0xFF) << 24);
    }

    private static final class ElementState {
        final String name;
        String activityName;
        boolean hasMain;
        boolean hasLauncher;
        ElementState(String name) { this.name = name; }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }
}