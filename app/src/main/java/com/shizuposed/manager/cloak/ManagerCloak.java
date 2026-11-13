package com.shizuposed.manager.cloak;

import android.os.StatFs;
import android.util.Log;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.shizuposed.manager.ShizuPosedManagerApp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ManagerCloak
 *
 * Builds a cloaked APK by patching the CURRENT APK's
 * AndroidManifest.xml package attribute in place, adding the
 * migrate zip, and re-signing with the bundled cloak keystore.
 *
 * No bundled sibling APKs. No manifest rewriting at the XML
 * level. No network. The manifest patch is a same-length
 * in-place string-pool rewrite (see AxmlPatcher) — the new
 * package name is exactly as long as the current one, so no
 * offsets shift and no chunk sizes change.
 *
 * THE SAME-LENGTH CONSTRAINT
 * --------------------------
 * The new package name MUST be the same length as the old one
 * (22 chars for com.shizuposed.manager). This is a string-pool
 * simplicity constraint, not a security feature. Same-length
 * lets the patch avoid rebuilding the AXML string pool, which
 * is the hard part.
 *
 * WHERE THE "OLD" NAME COMES FROM
 * -------------------------------
 * The patch target is the value of <manifest package="...">,
 * read by walking the XML nodes after the string pool. It is
 * NOT taken from getPackageName() or from a heuristic scan of
 * the pool. The manifest's own attribute is authoritative for
 * what's in the byte stream.
 *
 * STRING-POOL ENCODING
 * --------------------
 * The AXML string pool can be UTF-8 or UTF-16 (flags bit
 * 0x100). The two encodings have DIFFERENT length prefixes:
 *
 *   UTF-8:  char count (1-2 bytes, 7-bit chunks)
 *           + byte length (1-2 bytes, 7-bit chunks)
 *   UTF-16: char count (1 u16 = 2 bytes, or 2 u16s = 4 bytes
 *           if the first u16's high bit is set)
 *
 * Reading a UTF-16 prefix as 7-bit chunks (or vice versa) makes
 * every decode return null, which is what makes a patch "not
 * find" a string that is visibly in the pool.
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

    public static File buildCloakedApk(String newPackageName,
                                       File migrateZip,
                                       File output) throws Exception {
        return rebuildSelf(newPackageName, migrateZip, output);
    }

    public static File buildRestoredApk(String originalPackageName,
                                        File migrateZip,
                                        File output) throws Exception {
        return rebuildSelf(originalPackageName, migrateZip, output);
    }

    public static String readPackageName(File apk) {
        if (apk == null || !apk.isFile()) return null;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(MANIFEST);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] axml = readAll(in);
                return readPackageFromManifest(axml);
            }
        } catch (Throwable t) {
            Log.w(TAG, "readPackageName(" + apk.getName() + ") failed: "
                + t.getMessage());
        }
        return null;
    }

    // ═════════════════════════════════════════════════════════════
    // SELF-REBUILD
    // ═════════════════════════════════════════════════════════════

    private static File rebuildSelf(String newPackageName,
                                    File migrateZip,
                                    File output) throws Exception {
        ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
        if (app == null) throw new IllegalStateException("No app context");

        File selfApk = new File(app.getApplicationInfo().sourceDir);
        if (!selfApk.exists() || !selfApk.canRead()) {
            throw new IllegalStateException(
                "Cannot read the current APK at " + selfApk);
        }

        byte[] manifestBytes;
        try (ZipFile zip = new ZipFile(selfApk)) {
            ZipEntry me = zip.getEntry(MANIFEST);
            if (me == null) {
                throw new IllegalStateException(
                    "Current APK has no AndroidManifest.xml");
            }
            try (InputStream in = zip.getInputStream(me)) {
                manifestBytes = readAll(in);
            }
        }

        String oldPackage = readPackageFromManifest(manifestBytes);
        if (oldPackage == null || oldPackage.isEmpty()) {
            StringBuilder diag = new StringBuilder();
            diag.append("Could not read package name from the manifest. ")
                .append("manifestBytes.length=").append(manifestBytes.length)
                .append("\n");
            if (manifestBytes.length >= 8) {
                diag.append("  header: type=0x")
                    .append(Integer.toHexString(readU16(manifestBytes, 0)))
                    .append(" headerSize=").append(readU16(manifestBytes, 2))
                    .append(" size=").append(readU32(manifestBytes, 4))
                    .append("\n");
                int walkP = 8;
                int shown = 0;
                while (walkP + 8 <= manifestBytes.length && shown < 10) {
                    int type = readU16(manifestBytes, walkP);
                    int chs = readU16(manifestBytes, walkP + 2);
                    int cs = readU32(manifestBytes, walkP + 4);
                    diag.append("  chunk @").append(walkP)
                        .append(" type=0x").append(Integer.toHexString(type))
                        .append(" headerSize=").append(chs)
                        .append(" size=").append(cs).append("\n");
                    if (chs < 8 || cs < chs) break;
                    if (walkP + cs > manifestBytes.length) break;
                    walkP += cs;
                    shown++;
                }
            }
            throw new IllegalStateException(diag.toString());
        }

        Log.i(TAG, "Self-patching " + oldPackage + " -> " + newPackageName
            + " (old=" + oldPackage.length() + " chars, new="
            + newPackageName.length() + " chars)");

        if (oldPackage.length() != newPackageName.length()) {
            throw new IllegalArgumentException(
                "newPackageName must be " + oldPackage.length()
                + " chars (got " + newPackageName.length() + ")");
        }
        if (oldPackage.equals(newPackageName)) {
            throw new IllegalArgumentException(
                "newPackageName equals current package: " + oldPackage);
        }

        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException(
                "Could not delete stale output: " + output);
        }

        long srcSize = selfApk.length();
        checkFreeSpace(parent, srcSize * 3 + srcSize / 10);

        File unsigned = new File(parent, "self-unsigned.apk");
        if (unsigned.exists() && !unsigned.delete()) {
            throw new IllegalStateException(
                "Could not delete stale intermediate: " + unsigned);
        }

        try {
            byte[] patched = AxmlPatcher.replacePackageName(
                manifestBytes, oldPackage, newPackageName);
            Log.d(TAG, "Manifest patched: " + manifestBytes.length
                + " bytes in, " + patched.length + " bytes out");

            String patchedPackage = readPackageFromManifest(patched);
            Log.i(TAG, "Patched manifest declares: " + patchedPackage
                + " (expected " + newPackageName + ")");
            if (!newPackageName.equals(patchedPackage)) {
                throw new IllegalStateException(
                    "Patch verification failed: patched manifest says '"
                    + patchedPackage + "', expected '" + newPackageName
                    + "'. Aborting before install.");
            }

            try (ZFile dst = ZFile.openReadWrite(unsigned, Z_OPTIONS);
                 ZipFile zip = new ZipFile(selfApk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (isSignatureFile(name)) continue;
                    if (name.equals(MIGRATE_ASSET_PATH)) continue;

                    if (MANIFEST.equals(name)) {
                        try (InputStream in =
                                 new ByteArrayInputStream(patched)) {
                            dst.add(name, in, true);
                        }
                        continue;
                    }

                    try (InputStream in = zip.getInputStream(e)) {
                        boolean compress = !(RESOURCES_ARSC.equals(name)
                            || name.endsWith(".so"));
                        dst.add(name, in, compress);
                    }
                }

                if (migrateZip != null && migrateZip.isFile()) {
                    try (FileInputStream fis = new FileInputStream(migrateZip)) {
                        dst.add(MIGRATE_ASSET_PATH, fis, true);
                    }
                }

                dst.realign();
            }

            try (ZFile dst = ZFile.openReadWrite(output, Z_OPTIONS);
                 ZipFile zip = new ZipFile(unsigned)) {
                registerSigner(dst);

                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (isSignatureFile(name)) continue;

                    try (InputStream in = zip.getInputStream(e)) {
                        boolean compress = !(RESOURCES_ARSC.equals(name)
                            || name.endsWith(".so"));
                        dst.add(name, in, compress);
                    }
                }

                dst.realign();
            }

            Log.i(TAG, "Cloaked APK written: " + output.getName()
                + " (" + output.length() + " bytes)");
            return output;

        } finally {
            if (unsigned.exists()) unsigned.delete();
            if (output.exists() && output.length() == 0) output.delete();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
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
    // PACKAGE NAME READER — XML node walk
    // ═════════════════════════════════════════════════════════════

    /**
     * Read the value of <manifest package="..."> from AXML bytes.
     *
     * Walks the chunks after the string pool, finds the first
     * RES_XML_START_ELEMENT whose name resolves to "manifest",
     * then walks its attributes looking for the one named
     * "package" with a string-typed value. Returns that value.
     *
     * This is authoritative: it reads exactly what the manifest
     * declares, not a heuristic guess. The previous version
     * picked the shortest grammar-valid pool string, which on
     * the ShizuPosed manifest chose "moe.shizuku.manager"
     * (19 chars) over "com.shizuposed.manager" (22 chars) and
     * then tripped the length check in rebuildSelf.
     */
    public static String readPackageFromManifest(byte[] axml) {
        if (axml == null || axml.length < 0x24) return null;
        if (readU16(axml, 0) != 0x0003) return null;

        int poolOff = -1;
        int walkP = 8;
        while (walkP + 8 <= axml.length) {
            int type = readU16(axml, walkP);
            int chunkHeaderSize = readU16(axml, walkP + 2);
            int chunkSize = readU32(axml, walkP + 4);
            if (chunkHeaderSize < 8 || chunkSize < chunkHeaderSize) break;
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
        if (stringCount <= 0) return null;

        int offsetsStart     = poolOff + headerSize;
        int stringsDataStart = poolOff + stringsStart;
        int stringsEnd       = poolOff + chunkSize;

        String[] cache = new String[stringCount];

        int p = poolOff + chunkSize;
        while (p + 8 <= axml.length) {
            int type  = readU16(axml, p);
            int hSize = readU16(axml, p + 2);
            int cSize = readU32(axml, p + 4);
            if (cSize < hSize || p + cSize > axml.length) break;

            if (type == 0x0102) {  // RES_XML_START_ELEMENT
                int extOff = p + hSize;
                int nameIdx   = readU32(axml, extOff + 4);
                int attrStart = readU16(axml, extOff + 8);
                int attrSize  = readU16(axml, extOff + 10);
                int attrCount = readU16(axml, extOff + 12);

                String elemName = resolveString(
                    axml, nameIdx, cache, offsetsStart,
                    stringsDataStart, stringsEnd, utf8);

                if ("manifest".equals(elemName)) {
                    int attrBase = extOff + attrStart;
                    for (int i = 0; i < attrCount; i++) {
                        int aoff = attrBase + i * attrSize;
                        int aNameIdx = readU32(axml, aoff + 4);
                        int aType    = axml[aoff + 15] & 0xFF;
                        int aData    = readU32(axml, aoff + 16);

                        String aName = resolveString(
                            axml, aNameIdx, cache, offsetsStart,
                            stringsDataStart, stringsEnd, utf8);

                        if ("package".equals(aName) && aType == 0x03) {
                            return resolveString(
                                axml, aData, cache, offsetsStart,
                                stringsDataStart, stringsEnd, utf8);
                        }
                    }
                    return null;
                }
            }
            p += cSize;
        }
        return null;
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

    /**
     * Decode a single AXML string-pool entry.
     *
     * UTF-8:
     *   <charCount:1|2 bytes, 7-bit chunks><byteLen:1|2 bytes, 7-bit chunks><UTF-8 data>
     *
     * UTF-16:
     *   <charCount:1 or 2 u16s><UTF-16LE data, charCount*2 bytes>
     */
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