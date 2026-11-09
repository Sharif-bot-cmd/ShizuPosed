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
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.NodeValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ManagerCloak
 *
 * Rebuilds the currently-installed ShizuPosed APK with a new
 * package name and launcher label, re-signs it with the bundled
 * keystore, and returns the result.
 *
 * This is the on-device equivalent of the separate stealth build.
 * It reads the APK that's actually installed, so it works for
 * both the standard build and any prior cloaked build.
 *
 * THE TWO-PASS REBUILD
 * --------------------
 * apkzlib's ZFile.openReadWrite() does not reliably strip the
 * APK Signing Block (the v2/v3 signature block that sits between
 * the ZIP Central Directory and the End of Central Directory).
 * If the source APK has one — and every signed APK does — and
 * we append a new signature block, Android sees two and refuses
 * to install with a generic "keystore integrity check failed."
 *
 * The fix is a two-pass rebuild:
 *
 *   Pass 1 (strip):  copy every non-signature entry from the
 *                    source APK into a fresh ZFile with no signer.
 *                    The output has no signing block.
 *
 *   Pass 2 (sign):   copy every non-signature entry from the
 *                    stripped APK into a fresh ZFile, rewriting
 *                    AndroidManifest.xml and registering the
 *                    v2 signer before close.
 *
 * The intermediate is deleted on every exit path, including
 * failures, so the cache stays bounded.
 *
 * THE KEYSTORE
 * ------------
 * The bundled keystore at assets/keystore is PKCS12, loaded
 * explicitly via KeyStore.getInstance("PKCS12") rather than
 * KeyStore.getDefaultType() — the default is "BKS" on Android
 * 4.x and "PKCS12" on 9+, and we want the same behavior on
 * every supported device. It is cached at class level so a
 * second cloak in the same process does not re-read the asset.
 *
 * SIGNING SCHEME
 * --------------
 * The vendored apkzlib only supports v1 and v2 signing. There
 * is no v3 API in SigningOptions.Builder. The rebuilt APK is
 * v2-only, which Android 9+ accepts. A target app that checks
 * for a v3 signature scheme sees a mismatch — documented in the
 * README's ceiling section.
 *
 * The trade-off: every cloaked install shares the signature of
 * the bundled key, so a target app that fingerprints the
 * signature can identify cloaked ShizuPosed installs regardless
 * of package name. The README's "Hide ShizuPosed app" section
 * says so.
 */
public final class ManagerCloak {

    private static final String TAG = "ManagerCloak";

    private static final String MANIFEST = "AndroidManifest.xml";
    private static final String RESOURCES_ARSC = "resources.arsc";

    /** Asset path for the migrate zip embedded in a cloaked APK. */
    public static final String MIGRATE_ASSET_PATH = "assets/cloak_migrate.zip";

    private static final String KEYSTORE_ASSET = "keystore";
    private static final String KEYSTORE_PASS = "Hasani4510!";
    private static final String KEYSTORE_ALIAS = "key0";
    private static final String KEYSTORE_TYPE = "PKCS12";

    /** Package-name prefixes Android reserves for the framework. */
    private static final String[] RESERVED_PREFIXES = {
        "android.",
        "androidx.",
        "com.android.",
        "com.google.android.",
    };

    /**
     * Android R+ requires resources.arsc STORED and 4-byte aligned.
     * .so files must also be stored uncompressed so apkzlib can
     * re-sign without re-deflating them. Both rules are set here.
     */
    private static final ZFileOptions Z_OPTIONS = new ZFileOptions().setAlignmentRule(
        AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096),
            AlignmentRules.constantForSuffix(RESOURCES_ARSC, 4)
        )
    );

    // Cached keystore state — loaded once per process.
    private static volatile KeyStore cachedKeyStore = null;
    private static volatile KeyStore.PrivateKeyEntry cachedEntry = null;

    private ManagerCloak() {}

    // ═════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════

    /**
     * Cloak the currently-installed APK to [newPackageName].
     *
     * @param newPackageName the package name to rewrite to
     * @param migrateZip     optional migrate payload to embed (may be null)
     * @param output         the file to write the rebuilt APK to
     * @return output, for chaining
     */
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

    /**
     * Take a stock ShizuPosed APK (e.g. downloaded from GitHub),
     * embed a migrate zip, and re-sign it with the original
     * package name. Used by the "unhide" path to restore the
     * original package from a cloaked install.
     */
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
            throw new IllegalArgumentException(
                "Invalid package name: " + pkg);
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

        // Ensure we have free space for two full APK copies plus
        // headroom. A mid-write ENOSPC leaves the output corrupt
        // and the intermediate orphaned.
        long srcSize = src.length();
        checkFreeSpace(parent, srcSize * 3 + srcSize / 10);

        // Intermediate file for the strip pass.
        File stripped = new File(parent, "source-stripped.apk");
        if (stripped.exists() && !stripped.delete()) {
            throw new IllegalStateException(
                "Could not delete stale intermediate: " + stripped);
        }

        try {
            // ── Pass 1: strip the APK Signing Block ───────────────
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

            // ── Pass 2: rewrite the manifest and sign ─────────────
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
    // MANIFEST REWRITE + VALIDATION
    // ═════════════════════════════════════════════════════════════

    private static byte[] modifyManifest(InputStream input,
                                         String newPackageName) throws Exception {
        List<String> oldPackages = Arrays.asList(
            BuildConfig.ORIGINAL_PACKAGE_NAME,
            ShizuPosedManagerApp.getInstance().getPackageName()
        );

        ModificationProperty property = new ModificationProperty();
        property.addManifestAttribute(
            new AttributeItem(NodeValue.Manifest.PACKAGE, newPackageName)
                .setNamespace(null));

        // Rewrite app-scoped custom permissions (e.g. the dynamic
        // receiver permission) and provider authorities so install
        // does not clash with a still-installed original package.
        property.setPermissionMapper((node, permission) ->
            remap(permission, oldPackages, newPackageName));
        property.setAuthorityMapper((authority) ->
            remap(authority, oldPackages, newPackageName));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ManifestEditor(input, out, property).processManifest();
        byte[] result = out.toByteArray();

        validateManifestBytes(result);
        return result;
    }

    private static String remap(String value,
                                List<String> oldPackages,
                                String newPackageName) {
        if (value == null) return null;
        for (String old : oldPackages) {
            if (value.equals(old) || value.startsWith(old + ".")) {
                return newPackageName + value.substring(old.length());
            }
        }
        return value;
    }

    /**
     * Validate that the rewritten manifest is binary XML.
     *
     * ManifestEditor can silently produce a text manifest when
     * the input has features it doesn't understand (newer
     * attributes, multi-provider setups). A text manifest fails
     * at install time with an opaque "install failed" that
     * doesn't mention the manifest — this catches it here.
     */
    private static void validateManifestBytes(byte[] manifestBytes)
            throws Exception {
        if (manifestBytes == null || manifestBytes.length < 8) {
            throw new IllegalStateException(
                "Rewritten manifest is too short: "
                + (manifestBytes == null
                    ? "null" : manifestBytes.length + " bytes"));
        }
        int magic = ((manifestBytes[0] & 0xFF) << 0)
                  | ((manifestBytes[1] & 0xFF) << 8)
                  | ((manifestBytes[2] & 0xFF) << 16)
                  | ((manifestBytes[3] & 0xFF) << 24);
        // AXML file magic is 0x00080003.
        if (magic != 0x00080003) {
            throw new IllegalStateException(
                "Rewritten manifest is not binary XML (magic=0x"
                + Integer.toHexString(magic) + ")");
        }
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

        // The vendored apkzlib only supports v1 and v2 signing.
        // SigningOptions.Builder has no setV3SigningEnabled method
        // in this version. The rebuilt APK is v2-only, which
        // Android 9+ accepts. A target app that checks for a v3
        // signature scheme sees a mismatch — documented in the
        // README's ceiling section.
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