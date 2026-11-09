package com.shizuposed.manager.cloak;

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
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ManagerCloak
 *
 * Rebuilds the currently-installed ShizuPosed APK with a new
 * package name and launcher label, re-signs it with the bundled
 * keystore, and returns the result.
 *
 * This is the on-device equivalent of the separate stealth
 * build. It reads the APK that's actually installed, so it works
 * for both the standard build and any prior cloaked build.
 *
 * The trade-off: the rebuilt APK is signed with the keystore
 * bundled at assets/keystore. Every cloaked install shares that
 * signature, so a target app that fingerprints the signature
 * can identify cloaked ShizuPosed installs regardless of package
 * name. The README's "Hide ShizuPosed app" section says so.
 */
public final class ManagerCloak {

    private static final String TAG = "ManagerCloak";
    private static final String MANIFEST = "AndroidManifest.xml";
    private static final String RESOURCES_ARSC = "resources.arsc";

    /** Asset path for the migrate zip embedded in a cloaked APK. */
    public static final String MIGRATE_ASSET_PATH = "assets/cloak_migrate.zip";

    private static final String KEYSTORE_ASSET = "keystore";
    private static final String KEYSTORE_PASS = "123456";
    private static final String KEYSTORE_ALIAS = "key0";

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

    private ManagerCloak() {}

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
        if (app == null) throw new IllegalStateException("No app context");

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
        if (!src.isFile()) throw new IllegalStateException("Source APK not found");
        return rebuild(src, output, migrateZip, BuildConfig.ORIGINAL_PACKAGE_NAME);
    }

    private static File rebuild(File src,
                                File output,
                                File migrateZip,
                                String rewritePackageTo) throws Exception {
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("Could not delete stale output: " + output);
        }

        Log.i(TAG, "Rebuilding manager -> " + rewritePackageTo);

        try (ZFile dst = ZFile.openReadWrite(output, Z_OPTIONS)) {
            registerSigner(dst);

            try (ZipFile zip = new ZipFile(src)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (isSignatureFile(name)) continue;
                    if (name.equals(MIGRATE_ASSET_PATH)) continue;

                    try (InputStream in = zip.getInputStream(entry)) {
                        if (MANIFEST.equals(name)) {
                            byte[] modified = modifyManifest(in, rewritePackageTo);
                            try (ByteArrayInputStream bis = new ByteArrayInputStream(modified)) {
                                dst.add(name, bis, true);
                            }
                        } else if (RESOURCES_ARSC.equals(name) || name.endsWith(".so")) {
                            dst.add(name, in, false);
                        } else {
                            dst.add(name, in, entry.getMethod() != ZipEntry.STORED);
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
        property.setPermissionMapper((node, permission) -> remap(permission, oldPackages, newPackageName));
        property.setAuthorityMapper((authority) -> remap(authority, oldPackages, newPackageName));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ManifestEditor(input, out, property).processManifest();
        return out.toByteArray();
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

    private static void registerSigner(ZFile dst) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream ks = ShizuPosedManagerApp.getInstance()
                .getAssets().open(KEYSTORE_ASSET)) {
            keyStore.load(ks, KEYSTORE_PASS.toCharArray());
        }
        KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
            keyStore.getEntry(KEYSTORE_ALIAS,
                new KeyStore.PasswordProtection(KEYSTORE_PASS.toCharArray()));
        if (entry == null) {
            throw new IllegalStateException("Keystore alias missing: " + KEYSTORE_ALIAS);
        }

        @SuppressWarnings("unchecked")
        X509Certificate[] certs = (X509Certificate[]) entry.getCertificateChain();

        new SigningExtension(
            SigningOptions.builder()
                .setMinSdkVersion(28)
                .setV2SigningEnabled(true)
                .setCertificates(certs)
                .setKey(entry.getPrivateKey())
                .build()
        ).register(dst);
    }
}