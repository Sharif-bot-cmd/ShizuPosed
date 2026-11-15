package com.shizuposed.manager.cloak;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.shizuposed.manager.ShizuPosedManagerApp;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ManagerCloak
 *
 * Builds a cloaked APK by patching the CURRENT APK's
 * AndroidManifest.xml in place, adding the migrate zip, and
 * re-signing with the per-install signing key.
 *
 * WHAT'S PATCHED
 * --------------
 *   • <manifest package="...">       — in-place AXML string-pool
 *     rewrite, same-length required.
 *   • applicationId-derived suffixes — same-length AXML strings
 *     (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, etc.).
 *   • <application android:label="..."> — in-place AXML
 *     string-pool rewrite, same-length required. The manifest
 *     ships with android:label as a LITERAL ("ShizuPosed"),
 *     not a @string/ reference, precisely so this patch can
 *     work. See the label comment in AndroidManifest.xml.
 *
 * WHAT'S NOT PATCHED
 * ------------------
 *   • resources.arsc. The launcher reads android:label from
 *     the manifest, so the ARSC value (if @string/app_name is
 *     still referenced elsewhere) does not affect the launcher
 *     label. A detector that reads ARSC directly sees the
 *     original resource value; the cloak's threat model is
 *     runtime detection, not APK forensics.
 *   • Class names (MainActivity, ShizuPosedManagerApp, etc.).
 *     The dex still defines them under the original package
 *     name.
 *   • The launcher icon. Same reason as the label: it's an
 *     ARSC resource reference, not a manifest literal.
 *
 * THE SAME-LENGTH CONSTRAINT
 * --------------------------
 * Every patched string must be the same length as the original:
 *
 *   • Package name: 22 chars ("com.shizuposed.manager").
 *   • Label: 10 chars ("ShizuPosed").
 *
 * This is a string-pool simplicity constraint, not a security
 * feature. Same-length lets the patch avoid rebuilding the AXML
 * string pool, which is the hard part.
 *
 * PER-INSTALL SIGNING KEY
 * -----------------------
 * On the first cloak, a fresh RSA-4096 keypair is generated,
 * self-signed, and stored in files/cloak-install.p12. Every
 * subsequent cloak and every unhide uses that key. The key
 * survives Unhide because ManagerMigrate carries
 * cloak-install.p12 across the cloak.
 */
public final class ManagerCloak {

    private static final String TAG = "ManagerCloak";

    private static final String MANIFEST = "AndroidManifest.xml";
    private static final String RESOURCES_ARSC = "resources.arsc";

    public static final String MIGRATE_ASSET_PATH = "assets/cloak_migrate.zip";

    /**
     * Per-install key file, under filesDir. Not in the APK.
     * Carried across the cloak by ManagerMigrate.
     */
    public static final String PER_INSTALL_KEY_FILE = "cloak-install.p12";
    private static final String PER_INSTALL_KEY_PASS = "per-install-cloak";
    private static final String PER_INSTALL_KEY_ALIAS = "cloak";

    /**
     * Innocuous launcher labels, each exactly 10 characters, for
     * the label patch. The original label ("ShizuPosed") is 10
     * chars; the in-place rewrite requires the same length.
     *
     * Add more labels here if you want more variety. Each entry
     * MUST be exactly 10 characters, or the patch will reject it.
     */
    private static final String[] INNOCUOUS_LABELS_10 = {
        "Calculator",
        "File Tools",
        "Sync Notes",
        "Clock Work",
        "Task Board",
        "Note Keeper",
        "Cloud Sync",
        "Photo Fixs",
        "Screen Cap",
        "Quick Scan",
        "Backup Kit",
        "Daily Plan",
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
            // ── Pass 1: patch manifest, write fresh unsigned APK ──
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

            // ── Label patch ────────────────────────────────────
            // The manifest ships android:label as a literal
            // ("ShizuPosed", 10 chars). Patch it to a random
            // innocuous 10-char label so the launcher shows
            // something plausible.
            //
            // If the label is missing (older build, or a build
            // where android:label is still @string/app_name),
            // skip the patch and log why. The package-name
            // patch is not affected.
            String oldLabel = readManifestLabel(patched);
            if (oldLabel != null && !oldLabel.isEmpty()) {
                String newLabel = innocuousLabelFor(oldLabel.length());
                if (newLabel != null) {
                    try {
                        patched = AxmlPatcher.replaceLabel(
                            patched, oldLabel, newLabel);
                        String verifyLabel = readManifestLabel(patched);
                        Log.i(TAG, "Label patched: '" + oldLabel
                            + "' -> '" + newLabel
                            + "' (verified='" + verifyLabel + "')");
                        if (!newLabel.equals(verifyLabel)) {
                            Log.w(TAG, "Label patch verification failed; "
                                + "continuing with package-only patch");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Label patch failed for '" + oldLabel
                            + "': " + t.getMessage()
                            + " — continuing with package-only patch");
                    }
                } else {
                    Log.w(TAG, "No innocuous label of length "
                        + oldLabel.length()
                        + " — label left at '" + oldLabel + "'");
                }
            } else {
                Log.i(TAG, "No android:label literal in manifest — "
                    + "label patch skipped (package-only cloak)");
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

            // ── Pass 2: re-sign into output ──
            try (ZFile dst = ZFile.openReadWrite(output, Z_OPTIONS);
                 ZipFile zip = new ZipFile(unsigned)) {
                registerSigner(dst, app);

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
    // LABEL GENERATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Return an innocuous label of exactly `length` characters,
     * or null if there's no pool entry of that length.
     *
     * For the current build (label "ShizuPosed", 10 chars), the
     * pool INNOCUOUS_LABELS_10 has several entries. A future
     * label of a different length would need a pool entry of the
     * same length; if none exists, this returns null and the
     * cloak falls back to package-only patching (label
     * unchanged).
     *
     * The pool is deterministic (SecureRandom, but only used to
     * pick from a fixed list), so the same label won't repeat
     * across two cloaks if the list has more than one entry.
     */
    private static String innocuousLabelFor(int length) {
        if (length == 10) {
            if (INNOCUOUS_LABELS_10.length == 0) return null;
            int idx = new SecureRandom().nextInt(INNOCUOUS_LABELS_10.length);
            return INNOCUOUS_LABELS_10[idx];
        }
        Log.w(TAG, "innocuousLabelFor: no pool for length " + length
            + " (only 10-char pool is populated)");
        return null;
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
    // MANIFEST NODE WALKERS — package and label
    // ═════════════════════════════════════════════════════════════

    /**
     * Read the value of <manifest package="..."> from AXML bytes.
     */
    public static String readPackageFromManifest(byte[] axml) {
        return readManifestAttribute(axml, "manifest", "package");
    }

    /**
     * Read the value of <application android:label="..."> from
     * AXML bytes. Returns null if the label is a resource
     * reference (@string/app_name) or not present.
     *
     * The label MUST be a literal string for the cloak to patch
     * it. A @string/ reference resolves through resources.arsc,
     * which the cloak does not patch — the launcher would still
     * show the resource value after a cloak.
     */
    public static String readManifestLabel(byte[] axml) {
        return readManifestAttribute(axml, "application", "label");
    }

    /**
     * Walk the AXML chunk tree and return the value of the
     * named attribute on the named element.
     *
     * Only handles TYPE_STRING attribute values. A resource
     * reference (TYPE_REFERENCE) returns null — the caller
     * can't patch what it can't read.
     */
    private static String readManifestAttribute(byte[] axml,
                                                String elementName,
                                                String attributeName) {
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

            if (type == 0x0102) {
                int extOff = p + hSize;
                int nameIdx   = readU32(axml, extOff + 4);
                int attrStart = readU16(axml, extOff + 8);
                int attrSize  = readU16(axml, extOff + 10);
                int attrCount = readU16(axml, extOff + 12);

                String elemName = resolveString(
                    axml, nameIdx, cache, offsetsStart,
                    stringsDataStart, stringsEnd, utf8);

                if (elementName.equals(elemName)) {
                    int attrBase = extOff + attrStart;
                    for (int i = 0; i < attrCount; i++) {
                        int aoff = attrBase + i * attrSize;
                        int aNameIdx = readU32(axml, aoff + 4);
                        int aType    = axml[aoff + 15] & 0xFF;
                        int aData    = readU32(axml, aoff + 16);

                        String aName = resolveString(
                            axml, aNameIdx, cache, offsetsStart,
                            stringsDataStart, stringsEnd, utf8);

                        // Only TYPE_STRING (0x03) is patchable.
                        // TYPE_REFERENCE (0x01) is a resource
                        // reference; the cloak can't touch it.
                        if (attributeName.equals(aName) && aType == 0x03) {
                            return resolveString(
                                axml, aData, cache, offsetsStart,
                                stringsDataStart, stringsEnd, utf8);
                        }
                    }
                    // Element found but attribute missing or not
                    // a literal. Return null — caller decides
                    // whether to skip.
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
    // PER-INSTALL KEYSTORE
    // ═════════════════════════════════════════════════════════════

    private static synchronized void ensurePerInstallKey(Context ctx)
            throws Exception {
        if (cachedEntry != null) return;

        File keyFile = new File(ctx.getFilesDir(), PER_INSTALL_KEY_FILE);
        char[] pass = PER_INSTALL_KEY_PASS.toCharArray();

        if (keyFile.isFile()) {
            try {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (InputStream in = new FileInputStream(keyFile)) {
                    ks.load(in, pass);
                }
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                    ks.getEntry(PER_INSTALL_KEY_ALIAS,
                        new KeyStore.PasswordProtection(pass));
                if (entry == null) {
                    throw new IllegalStateException(
                        "Per-install keystore alias missing: "
                        + PER_INSTALL_KEY_ALIAS);
                }
                cachedKeyStore = ks;
                cachedEntry = entry;
                Log.i(TAG, "Loaded per-install key from " + keyFile);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "Per-install key unreadable ("
                    + t.getMessage() + ") — regenerating");
            }
        }

        Log.i(TAG, "Generating per-install signing key");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(4096, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate cert = selfSign(kp);
        X509Certificate[] chain = new X509Certificate[]{ cert };

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, pass);
        ks.setEntry(PER_INSTALL_KEY_ALIAS,
            new KeyStore.PrivateKeyEntry(kp.getPrivate(), chain),
            new KeyStore.PasswordProtection(pass));

        File parent = keyFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(keyFile)) {
            ks.store(out, pass);
        }
        keyFile.setReadable(true, true);
        keyFile.setWritable(true, true);

        cachedKeyStore = ks;
        cachedEntry = (KeyStore.PrivateKeyEntry) ks.getEntry(
            PER_INSTALL_KEY_ALIAS, new KeyStore.PasswordProtection(pass));

        Log.i(TAG, "Generated per-install key; cert SHA-256: "
            + certFingerprint(cert));
    }

    private static X509Certificate selfSign(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - TimeUnit.DAYS.toMillis(1));
        Date notAfter  = new Date(now + TimeUnit.DAYS.toMillis(365 * 20));

        X500Name subject = new X500Name("CN=Cloak, O=ShizuPosed, C=XX");
        BigInteger serial = BigInteger.valueOf(now);

        JcaX509v3CertificateBuilder builder =
            new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter,
                subject, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true,
            new BasicConstraints(false));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .build(kp.getPrivate());

        return new JcaX509CertificateConverter().getCertificate(
            builder.build(signer));
    }

    private static void registerSigner(ZFile dst,
                                       ShizuPosedManagerApp app)
            throws Exception {
        ensurePerInstallKey(app);
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

        Log.i(TAG, "APK signed with per-install key");
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