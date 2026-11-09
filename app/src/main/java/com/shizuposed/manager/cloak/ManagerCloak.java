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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ManagerCloak
 *
 * Rebuilds the currently-installed ShizuPosed APK with a new
 * package name and launcher label, re-signs it with the bundled
 * keystore, and returns the result.
 *
 * MANIFEST REWRITE — BYTE PATCHING
 * --------------------------------
 * The manifest rewrite does not use a binary-XML rewriter
 * library. Both ManifestEditor and pxb1988/axml:3.0 have bugs
 * that corrupt the <manifest package> attribute or drop the
 * <intent-filter> on <activity>. The byte-patching approach
 * sidesteps both:
 *
 *   1. Parse the AXML header to find the string pool.
 *   2. Decode every string in the pool (UTF-8 or UTF-16LE,
 *      depending on the pool's flags).
 *   3. Find the strings that reference the old package name:
 *      the bare "com.shizuposed.manager" (the package
 *      attribute's value), and every string that starts with
 *      "com.shizuposed.manager." (class names, authorities,
 *      permission names, etc.).
 *   4. Rewrite those strings **in place** in the string pool
 *      buffer, padding with NUL bytes to preserve the pool's
 *      fixed-size layout.
 *   5. Return the patched AXML file.
 *
 * The new package name is required to be **no longer than** the
 * old one, so the padding always fits. PackageNameValidator
 * enforces this by rejecting names longer than 23 characters
 * (the length of "com.shizuposed.manager").
 *
 * This is the same technique apktool uses for its "package
 * rename" feature. It is mechanically simple and does not
 * depend on any third-party binary-XML library being bug-free.
 *
 * Two-pass rebuild: the first pass strips the APK Signing Block
 * (apkzlib does not reliably strip it on openReadWrite), and
 * the second pass rewrites the manifest and signs. The
 * intermediate is deleted on every exit path.
 *
 * The keystore at assets/keystore is PKCS12, loaded explicitly
 * via KeyStore.getInstance("PKCS12") rather than
 * KeyStore.getDefaultType() — the default is "BKS" on Android
 * 4.x and "PKCS12" on 9+, and we want the same behavior on
 * every supported device. It is cached at class level so a
 * second cloak in the same process does not re-read the asset.
 *
 * Signing is v2-only (the vendored apkzlib has no v3 API).
 * Android 9+ accepts v2. A target app that checks for a v3
 * signature scheme sees a mismatch — documented in the README's
 * ceiling section.
 *
 * The trade-off: every cloaked install shares the signature of
 * the bundled key, so a target app that fingerprints the
 * signature can identify cloaked ShizuPosed installs regardless
 * of package name.
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

    private static final String LAUNCHER_CATEGORY =
        "android.intent.category.LAUNCHER";

    /**
     * The maximum length of a new package name. The byte patcher
     * requires the new name to be no longer than the old one, and
     * "com.shizuposed.manager" is 23 characters.
     */
    public static final int MAX_PACKAGE_NAME_LEN = 23;

    private static final String[] RESERVED_PREFIXES = {
        "android.",
        "androidx.",
        "com.android.",
        "com.google.android.",
    };

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

    public static File cloak(String newPackageName,
                             File migrateZip,
                             File output) throws Exception {
        ShizuPosedManagerApp app = ShizuPosedManagerApp.getInstance();
        if (app == null) {
            throw new IllegalStateException("No app context");
        }
        validateNewPackageName(newPackageName);

        String[] splits = app.getApplicationInfo().splitSourceDirs;
        if (splits != null && splits.length > 0) {
            throw new IllegalStateException(
                "Split APK installs cannot be cloaked; install a single-APK build");
        }

        File src = new File(app.getApplicationInfo().sourceDir);
        if (!src.isFile()) {
            throw new IllegalStateException("Manager source APK not found");
        }
        return rebuild(src, output, migrateZip, newPackageName);
    }

    public static File prepareStockWithMigrate(File src,
                                               File migrateZip,
                                               File output) throws Exception {
        if (!src.isFile()) {
            throw new IllegalStateException("Source APK not found");
        }
        return rebuild(src, output, migrateZip, BuildConfig.ORIGINAL_PACKAGE_NAME);
    }

    // ═════════════════════════════════════════════════════════════
    // PACKAGE-NAME VALIDATION
    // ═════════════════════════════════════════════════════════════

    private static void validateNewPackageName(String pkg) {
        if (!PackageNameValidator.isValidPackageName(pkg)) {
            throw new IllegalArgumentException("Invalid package name: " + pkg);
        }
        String lower = pkg.toLowerCase(Locale.ROOT);
        for (String reserved : RESERVED_PREFIXES) {
            String prefixNoDot = reserved.substring(0, reserved.length() - 1);
            if (lower.equals(prefixNoDot) || lower.startsWith(reserved)) {
                throw new IllegalArgumentException(
                    "Package name uses a reserved prefix: " + pkg
                    + " (reserved: " + reserved + "*)");
            }
        }
        if (pkg.length() > MAX_PACKAGE_NAME_LEN) {
            throw new IllegalArgumentException(
                "Package name is too long: " + pkg + " ("
                + pkg.length() + " chars). The cloak flow requires "
                + "at most " + MAX_PACKAGE_NAME_LEN + " characters, "
                + "because the new name is patched in place over "
                + "\"com.shizuposed.manager\" and must fit in the "
                + "same string pool slot.");
        }
    }

    // ═════════════════════════════════════════════════════════════
    // TWO-PASS REBUILD
    // ═════════════════════════════════════════════════════════════

    private static File rebuild(File src,
                                File output,
                                File migrateZip,
                                String rewritePackageTo) throws Exception {
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException(
                "Could not delete stale output: " + output);
        }

        Log.i(TAG, "Rebuilding manager -> " + rewritePackageTo);

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

            Log.d(TAG, "Pass 2: rewriting manifest and signing");
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
                            if (MANIFEST.equals(name)) {
                                byte[] modified = modifyManifest(
                                    in, rewritePackageTo);
                                try (ByteArrayInputStream bis =
                                         new ByteArrayInputStream(modified)) {
                                    dst.add(name, bis, true);
                                }
                            } else if (RESOURCES_ARSC.equals(name)
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
    // MANIFEST REWRITE (byte patching)
    // ═════════════════════════════════════════════════════════════

    private static byte[] modifyManifest(InputStream input,
                                         String newPackageName) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = input.read(tmp)) > 0) buf.write(tmp, 0, n);
        byte[] src = buf.toByteArray();

        byte[] patched = patchAxmlStringPool(
            src, BuildConfig.ORIGINAL_PACKAGE_NAME, newPackageName);

        validateManifestBytes(patched);
        validateLauncherSurvived(patched);
        return patched;
    }

    /**
     * Patch every occurrence of oldPkg (and oldPkg.*) in the
     * AXML's string pool with newPkg (and newPkg.*), padding the
     * replacement with NUL bytes to preserve the string's original
     * length in the pool.
     *
     * AXML string pool layout (little-endian):
     *
     *   0x00  uint16  chunkType (0x0001 = RES_STRING_POOL_TYPE)
     *   0x02  uint16  headerSize (0x001c = 28)
     *   0x04  uint32  chunkSize
     *   0x08  uint32  stringCount
     *   0x0c  uint32  styleCount
     *   0x10  uint32  flags (0x0100 = UTF-8, 0x0000 = UTF-16LE)
     *   0x14  uint32  stringsStart (offset from chunk start)
     *   0x18  uint32  stylesStart
     *   0x1c  uint32[] offsets   (stringCount entries)
     *   ...   uint8[]  stringData
     *
     * Each string in stringData is length-prefixed:
     *   UTF-8:   [uleb128 charCount] [uleb128 byteCount] [bytes] [\0]
     *   UTF-16:  [uleb128 charCount] [uint16 chars[]] [\0\0]
     *
     * Because the new package name is the same length as (or
     * shorter than) the old one, and we pad with NULs, the pool's
     * offsets, sizes, and overall layout are unchanged. No
     * header fields need to be recomputed.
     *
     * Only the FIRST string pool in the file is patched.
     */
    private static byte[] patchAxmlStringPool(byte[] src,
                                              String oldPkg,
                                              String newPkg) throws Exception {
        if (src.length < 0x24) {
            throw new IllegalStateException("AXML too short");
        }

        int fileType = readU16(src, 0);
        if (fileType != 0x0003) {
            throw new IllegalStateException(
                "Not an AXML file (type=0x" + Integer.toHexString(fileType) + ")");
        }

        int poolOff = 8;
        int poolType = readU16(src, poolOff);
        if (poolType != 0x0001) {
            throw new IllegalStateException(
                "First chunk is not a string pool (type=0x"
                + Integer.toHexString(poolType) + ")");
        }

        int headerSize = readU16(src, poolOff + 2);
        int chunkSize = readU32(src, poolOff + 4);
        int stringCount = readU32(src, poolOff + 8);
        int flags = readU32(src, poolOff + 0x10);
        int stringsStart = readU32(src, poolOff + 0x14);
        boolean utf8 = (flags & 0x0100) != 0;  // UTF8_FLAG

        if (headerSize < 0x1c) {
            throw new IllegalStateException(
                "String pool header too small: " + headerSize);
        }
        if (poolOff + chunkSize > src.length) {
            throw new IllegalStateException(
                "String pool extends past end of file");
        }
        if (stringsStart < headerSize
                || poolOff + stringsStart > src.length) {
            throw new IllegalStateException(
                "Invalid stringsStart: " + stringsStart);
        }

        int offsetsStart = poolOff + headerSize;
        int stringsDataStart = poolOff + stringsStart;

        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            offsets[i] = readU32(src, offsetsStart + i * 4);
        }

        int stringsEnd = poolOff + chunkSize;

        Set<String> oldStrings = new HashSet<>();
        List<int[]> replacements = new ArrayList<>();

        for (int i = 0; i < stringCount; i++) {
            int absOff = stringsDataStart + offsets[i];
            int absEnd = (i + 1 < stringCount)
                ? stringsDataStart + offsets[i + 1]
                : stringsEnd;

            if (absEnd <= absOff || absEnd > src.length) continue;

            String decoded = decodeAxmlString(src, absOff, absEnd, utf8);
            if (decoded == null) continue;

            if (decoded.equals(oldPkg)
                    || decoded.startsWith(oldPkg + ".")) {
                String rewritten = decoded.equals(oldPkg)
                    ? newPkg
                    : newPkg + decoded.substring(oldPkg.length());
                if (rewritten.length() > decoded.length()) {
                    throw new IllegalStateException(
                        "Replacement \"" + rewritten + "\" is longer than \""
                        + decoded + "\"; byte patching requires a shorter or "
                        + "equal replacement.");
                }
                replacements.add(new int[]{i, absOff, absEnd});
                oldStrings.add(decoded);
            }
        }

        Log.i(TAG, "AXML pool: count=" + stringCount
            + " utf8=" + utf8
            + " stringsDataStart=0x" + Integer.toHexString(stringsDataStart)
            + " stringsEnd=0x" + Integer.toHexString(stringsEnd));
        Log.i(TAG, "Matching strings: " + oldStrings);

        if (replacements.isEmpty()) {
            Log.w(TAG, "No string references to \"" + oldPkg
                + "\" found in the AXML string pool");
        } else {
            Log.i(TAG, "Patching " + replacements.size() + " AXML "
                + "string(s) for package rename");
        }

        byte[] out = src.clone();

        for (int[] r : replacements) {
            int absOff = r[1];
            int absEnd = r[2];
            String orig = decodeAxmlString(src, absOff, absEnd, utf8);
            if (orig == null) continue;
            String rewritten = orig.equals(oldPkg)
                ? newPkg
                : newPkg + orig.substring(oldPkg.length());

            byte[] encoded = encodeAxmlString(rewritten, utf8);
            int originalEncodedLen = absEnd - absOff;

            if (encoded.length > originalEncodedLen) {
                throw new IllegalStateException(
                    "Encoded replacement longer than original");
            }

            System.arraycopy(encoded, 0, out, absOff, encoded.length);
            for (int j = encoded.length; j < originalEncodedLen; j++) {
                out[absOff + j] = 0;
            }
        }

        return out;
    }

    // ── AXML string encoding helpers ──────────────────────────────

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] b, int off) {
        return (b[off] & 0xFF)
            | ((b[off + 1] & 0xFF) << 8)
            | ((b[off + 2] & 0xFF) << 16)
            | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * Decode an AXML string. Returns null on malformed input.
     *
     * Format (little-endian):
     *   UTF-8:   [uleb128 charCount] [uleb128 byteCount] [bytes] [\0]
     *   UTF-16:  [uleb128 charCount] [uint16 chars[]] [\0\0]
     *
     * The charCount prefix is the number of UTF-16 code units in
     * the string (not bytes). For UTF-8 it's informational — the
     * byteCount is what actually delimits the encoded data. For
     * UTF-16 it's the number of uint16 values that follow, and
     * there is NO second length prefix.
     */
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
                return new String(b, p, byteLen, StandardCharsets.UTF_8);
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

    /**
     * Encode a string in the same format AXML uses:
     *   UTF-8:   uleb128(charCount) uleb128(byteCount) [bytes]
     *   UTF-16:  uleb128(charCount) [uint16 chars[]]
     */
    private static byte[] encodeAxmlString(String s, boolean utf8) {
        if (utf8) {
            byte[] utf8Bytes = s.getBytes(StandardCharsets.UTF_8);
            int charCount = s.length();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeUleb128(out, charCount);
            if (utf8Bytes.length < 0x80) {
                out.write(utf8Bytes.length);
            } else {
                out.write(0x80 | ((utf8Bytes.length >> 8) & 0x7F));
                out.write(utf8Bytes.length & 0xFF);
            }
            try {
                out.write(utf8Bytes);
            } catch (Exception e) {
                throw new IllegalStateException("UTF-8 encode failed", e);
            }
            return out.toByteArray();
        } else {
            int charCount = s.length();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeUleb128(out, charCount);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                out.write(c & 0xFF);
                out.write((c >> 8) & 0xFF);
            }
            return out.toByteArray();
        }
    }

    private static void writeUleb128(ByteArrayOutputStream out, int v) {
        do {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) b |= 0x80;
            out.write(b);
        } while (v != 0);
    }

    // ═════════════════════════════════════════════════════════════
    // MANIFEST VALIDATION
    // ═════════════════════════════════════════════════════════════

    private static void validateManifestBytes(byte[] manifestBytes)
            throws Exception {
        if (manifestBytes == null || manifestBytes.length < 8) {
            throw new IllegalStateException(
                "Rewritten manifest is too short");
        }
        int magic = ((manifestBytes[0] & 0xFF) << 0)
                  | ((manifestBytes[1] & 0xFF) << 8)
                  | ((manifestBytes[2] & 0xFF) << 16)
                  | ((manifestBytes[3] & 0xFF) << 24);
        if (magic != 0x00080003) {
            throw new IllegalStateException(
                "Rewritten manifest is not binary XML (magic=0x"
                + Integer.toHexString(magic) + ")");
        }
    }

    private static void validateLauncherSurvived(byte[] manifestBytes)
            throws Exception {
        if (manifestBytes == null) {
            throw new IllegalStateException("Manifest is null");
        }
        byte[] needleUtf16;
        byte[] needleUtf8;
        try {
            needleUtf16 = LAUNCHER_CATEGORY.getBytes("UTF-16LE");
            needleUtf8 = LAUNCHER_CATEGORY.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException uee) {
            throw new IllegalStateException(
                "Encoding unavailable: " + uee.getMessage(), uee);
        }
        boolean found = indexOf(manifestBytes, needleUtf16) >= 0
                     || indexOf(manifestBytes, needleUtf8) >= 0;
        if (!found) {
            throw new IllegalStateException(
                "Rewritten manifest has no LAUNCHER category — the "
                + "launcher activity was dropped during the rewrite. "
                + "This would produce an APK with no app icon, so "
                + "the cloak aborted before signing. The old package "
                + "is still installed.");
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle == null || needle.length == 0) return -1;
        if (haystack == null || haystack.length < needle.length) return -1;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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

        Log.i(TAG, "APK signed with v2 scheme "
            + "(v3 not supported by vendored apkzlib)");
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