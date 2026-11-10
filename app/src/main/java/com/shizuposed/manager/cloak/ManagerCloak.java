package com.shizuposed.manager.cloak;

import android.os.StatFs;
import android.util.Log;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.shizuposed.manager.BuildConfig;
import com.shizuposed.manager.ShizuPosedManagerApp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * ManagerCloak
 *
 * Extracts a bundled APK (stealth or standard) from the
 * manager's assets, embeds the migrate zip, and re-signs it
 * with the bundled keystore.
 *
 * No manifest rewriting. No byte patching. No network. The
 * bundled APK already has the correct package name and launcher
 * label from the build. The cloak flow only:
 *
 *   1. Extracts the bundled APK from assets
 *   2. Strips its signing block (two-pass rebuild)
 *   3. Adds the migrate zip as assets/cloak_migrate.zip
 *   4. Re-signs with the bundled keystore
 *
 * The result installs as a fresh package (the bundled APK's
 * package name), so pm install treats it as a new install and
 * signature mismatch never applies.
 */
public final class ManagerCloak {

    private static final String TAG = "ManagerCloak";

    private static final String MANIFEST = "AndroidManifest.xml";
    private static final String RESOURCES_ARSC = "resources.arsc";

    public static final String MIGRATE_ASSET_PATH = "assets/cloak_migrate.zip";

    private static final String KEYSTORE_ASSET = "keystore";
    private static final String KEYSTORE_PASS = "Hasani4510!";
    private static final String KEYSTORE_ALIAS = "key0";
    private static final String KEYSTORE_TYPE = "PKCS12";

    private static final ZFileOptions Z_OPTIONS = new ZFileOptions().setAlignmentRule(
        AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096),
            AlignmentRules.constantForSuffix(RESOURCES_ARSC, 4)
        )
    );

    private static volatile KeyStore cachedKeyStore = null;
    private static volatile KeyStore.PrivateKeyEntry cachedEntry = null;

    private ManagerCloak() {}

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════

    /**
     * Build the stealth APK from the bundled asset, add the
     * migrate zip, sign with the bundled keystore.
     */
    public static File buildCloakedApk(File migrateZip,
                                       File output) throws Exception {
        return rebuildBundled(
            BuildConfig.STEALTH_APK_ASSET, output, migrateZip);
    }

    /**
     * Build the standard APK from the bundled asset, add the
     * migrate zip, sign with the bundled keystore.
     */
    public static File buildStandardApk(File migrateZip,
                                        File output) throws Exception {
        return rebuildBundled(
            BuildConfig.STANDARD_APK_ASSET, output, migrateZip);
    }

    /**
     * Read the package name from a bundled APK asset. Used by
     * the cloak flow to log the actual target package (which
     * may differ from BuildConfig.STEALTH_PACKAGE_NAME if the
     * build was produced with a different -PstealthSeed).
     *
     * Returns null if the asset is missing or the manifest
     * can't be parsed.
     */
    public static String readBundledPackageName(String assetName) {
        ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
        if (app == null) return null;
        if (assetName == null || assetName.isEmpty()) return null;

        try (InputStream in = app.getAssets().open(assetName)) {
            ZipInputStream zis = new ZipInputStream(in);
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (MANIFEST.equals(entry.getName())) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] tmp = new byte[8192];
                    int n;
                    while ((n = zis.read(tmp)) > 0) buf.write(tmp, 0, n);
                    return readPackageFromManifest(buf.toByteArray());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "readBundledPackageName(" + assetName + ") failed: "
                + t.getMessage());
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // BUNDLED APK EXTRACTION
    // ═════════════════════════════════════════════════════════════

    private static File rebuildBundled(String assetName,
                                       File output,
                                       File migrateZip) throws Exception {
        ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
        if (app == null) throw new IllegalStateException("No app context");
        if (assetName == null || assetName.isEmpty()) {
            throw new IllegalStateException(
                "No bundled APK asset configured for this build");
        }

        File extracted = new File(app.getCacheDir(), assetName);
        if (extracted.exists() && extracted.length() > 0) {
            // Already extracted — reuse. The asset hash is
            // checked by the caller, not here.
            Log.d(TAG, "Reusing extracted asset: " + extracted.getAbsolutePath());
        } else {
            try (InputStream in = app.getAssets().open(assetName);
                 FileOutputStream out = new FileOutputStream(extracted)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.i(TAG, "Extracted bundled APK: " + extracted.getAbsolutePath()
                + " (" + extracted.length() + " bytes)");
        }

        return rebuild(extracted, output, migrateZip);
    }

    // ═════════════════════════════════════════════════════════════
    // TWO-PASS REBUILD
    // ═════════════════════════════════════════════════════════════

    private static File rebuild(File src,
                                File output,
                                File migrateZip) throws Exception {
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException(
                "Could not delete stale output: " + output);
        }

        Log.i(TAG, "Rebuilding APK -> " + output.getName());

        long srcSize = src.length();
        checkFreeSpace(parent, srcSize * 3 + srcSize / 10);

        File stripped = new File(parent, "source-stripped.apk");
        if (stripped.exists() && !stripped.delete()) {
            throw new IllegalStateException(
                "Could not delete stale intermediate: " + stripped);
        }

        try {
            Log.d(TAG, "Pass 1: stripping APK Signing Block");
            try (ZFile pre = ZFile.openReadWrite(stripped, Z_OPTIONS)) {
                try (ZipFile zip = new ZipFile(src)) {
                    java.util.Enumeration<? extends ZipEntry> entries =
                        zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String name = entry.getName();
                        if (isSignatureFile(name)) continue;
                        if (name.equals(MIGRATE_ASSET_PATH)) continue;

                        try (InputStream in = zip.getInputStream(entry)) {
                            if (RESOURCES_ARSC.equals(name)
                                    || name.endsWith(".so")) {
                                pre.add(name, in, false);
                            } else {
                                pre.add(name, in,
                                    entry.getMethod() != ZipEntry.STORED);
                            }
                        }
                    }
                }
                pre.realign();
            }

            Log.d(TAG, "Pass 2: adding migrate zip and signing");
            try (ZFile dst = ZFile.openReadWrite(output, Z_OPTIONS)) {
                registerSigner(dst);

                try (ZipFile zip = new ZipFile(stripped)) {
                    java.util.Enumeration<? extends ZipEntry> entries =
                        zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String name = entry.getName();
                        if (isSignatureFile(name)) continue;
                        if (name.equals(MIGRATE_ASSET_PATH)) continue;

                        try (InputStream in = zip.getInputStream(entry)) {
                            if (RESOURCES_ARSC.equals(name)
                                    || name.endsWith(".so")) {
                                dst.add(name, in, false);
                            } else {
                                dst.add(name, in,
                                    entry.getMethod() != ZipEntry.STORED);
                            }
                        }
                    }
                }

                if (migrateZip != null && migrateZip.isFile()) {
                    try (FileInputStream fis = new FileInputStream(migrateZip)) {
                        dst.add(MIGRATE_ASSET_PATH, fis, true);
                    }
                }

                dst.realign();
            }

            return output;

        } finally {
            if (stripped.exists()) stripped.delete();
            if (output.exists() && output.length() == 0) output.delete();
        }
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        return name.endsWith(".SF")
            || name.endsWith(".MF")
            || name.endsWith(".RSA")
            || name.endsWith(".DSA")
            || name.endsWith(".EC")
            || name.endsWith(".IMG");
    }

    // ═════════════════════════════════════════════════════════════
    // DISK SPACE
    // ═════════════════════════════════════════════════════════════

    private static void checkFreeSpace(File dir, long requiredBytes)
            throws Exception {
        if (dir == null) {
            dir = ShizuPosedManagerApp.getInstance().getCacheDir();
        }
        try {
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long available = stat.getAvailableBytes();
            if (available < requiredBytes) {
                long needMb = requiredBytes / (1024L * 1024L);
                long haveMb = available / (1024L * 1024L);
                throw new IllegalStateException(
                    "Not enough free space: need " + needMb
                    + " MB, have " + haveMb + " MB");
            }
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Throwable t) {
            Log.w(TAG, "checkFreeSpace failed, proceeding: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE NAME READER (minimal AXML scan)
    // ═════════════════════════════════════════════════════════════

    private static String readPackageFromManifest(byte[] axml) {
        if (axml == null || axml.length < 0x24) return null;
        if (readU16(axml, 0) != 0x0003) return null;

        int poolOff = 8;
        if (readU16(axml, poolOff) != 0x0001) return null;

        int headerSize = readU16(axml, poolOff + 2);
        int chunkSize = readU32(axml, poolOff + 4);
        int stringCount = readU32(axml, poolOff + 8);
        int flags = readU32(axml, poolOff + 0x10);
        int stringsStart = readU32(axml, poolOff + 0x14);
        boolean utf8 = (flags & 0x0100) != 0;

        if (headerSize < 0x1c) return null;
        if (poolOff + chunkSize > axml.length) return null;
        if (stringsStart < headerSize) return null;

        int offsetsStart = poolOff + headerSize;
        int stringsDataStart = poolOff + stringsStart;
        int stringsEnd = poolOff + chunkSize;

        java.util.regex.Pattern pkgPattern = java.util.regex.Pattern.compile(
            "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

        String best = null;
        for (int i = 0; i < stringCount; i++) {
            int absOff = stringsDataStart + readU32(axml, offsetsStart + i * 4);
            if (absOff < stringsDataStart || absOff >= stringsEnd) continue;
            String s = decodeAxmlString(axml, absOff, stringsEnd, utf8);
            if (s == null) continue;
            if (!pkgPattern.matcher(s).matches()) continue;
            if (best == null || s.length() < best.length()) best = s;
        }
        return best;
    }

    private static String decodeAxmlString(byte[] b, int start, int end,
                                           boolean utf8) {
        try {
            if (start >= end) return null;
            int p = start;
            int charCount = 0;
            int shift = 0;
            while (p < end) {
                int v = b[p++] & 0xFF;
                charCount |= (v & 0x7F) << shift;
                if ((v & 0x80) == 0) break;
                shift += 7;
            }
            if (p >= end) return null;

            if (utf8) {
                int byteLen;
                int lenByte = b[p++] & 0xFF;
                if ((lenByte & 0x80) == 0) {
                    byteLen = lenByte;
                } else {
                    if (p >= end) return null;
                    byteLen = ((lenByte & 0x7F) << 8) | (b[p++] & 0xFF);
                }
                if (byteLen < 0 || p + byteLen > end) return null;
                return new String(b, p, byteLen, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                int byteLen = charCount * 2;
                if (byteLen <= 0 || p + byteLen > end) return null;
                char[] chars = new char[charCount];
                for (int i = 0; i < charCount; i++) {
                    chars[i] = (char)(
                        ((b[p + i * 2] & 0xFF))
                        | ((b[p + i * 2 + 1] & 0xFF) << 8));
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

    // ═════════════════════════════════════════════════════════════
    // KEYSTORE + SIGNER
    // ═════════════════════════════════════════════════════════════

    private static synchronized void ensureKeyStoreLoaded() throws Exception {
        if (cachedEntry != null) return;

        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (InputStream is = ShizuPosedManagerApp.getInstance()
                .getAssets().open(KEYSTORE_ASSET)) {
            ks.load(is, KEYSTORE_PASS.toCharArray());
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
            ks.getEntry(KEYSTORE_ALIAS,
                new KeyStore.PasswordProtection(
                    KEYSTORE_PASS.toCharArray()));
        if (entry == null) {
            throw new IllegalStateException(
                "Keystore alias missing: " + KEYSTORE_ALIAS);
        }
        cachedKeyStore = ks;
        cachedEntry = entry;

        @SuppressWarnings("unchecked")
        X509Certificate[] certs =
            (X509Certificate[]) entry.getCertificateChain();
        Log.i(TAG, "Signing cert SHA-256: " + certFingerprint(certs[0]));
    }

    private static void registerSigner(ZFile dst) throws Exception {
        ensureKeyStoreLoaded();
        @SuppressWarnings("unchecked")
        X509Certificate[] certs =
            (X509Certificate[]) cachedEntry.getCertificateChain();

        new SigningExtension(
            SigningOptions.builder()
                .setMinSdkVersion(28)
                .setV2SigningEnabled(true)
                .setCertificates(certs)
                .setKey(cachedEntry.getPrivateKey())
                .build()
        ).register(dst);

        Log.i(TAG, "APK signed with v2 scheme");
    }

    private static String certFingerprint(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02X:", b));
            }
            if (sb.length() > 0) sb.setLength(sb.length() - 1);
            return sb.toString();
        } catch (Throwable t) {
            return "(unavailable)";
        }
    }
}