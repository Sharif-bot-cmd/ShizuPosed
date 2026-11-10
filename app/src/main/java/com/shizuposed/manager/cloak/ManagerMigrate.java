package com.shizuposed.manager.cloak;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ManagerMigrate
 *
 * Packages the current install's settings, keystore, and module
 * database into a zip that ManagerCloak embeds in the rebuilt
 * APK. On first launch of the new package, importIfNeeded()
 * reads that zip and restores the data.
 *
 * The migrate payload is deliberately narrow: the things that
 * are user state. The dex, native libs, and shell-side caches
 * are re-deployed by ShizuPosedService on first launch, so they
 * don't need to be carried across.
 */
public final class ManagerMigrate {

    private static final String TAG = "ManagerMigrate";
    private static final String MARKER = ".cloak_migrate_done";

    private static final String ENTRY_PREFS = "settings.xml";
    private static final String ENTRY_KEYSTORE = "keystore.bks";
    private static final String ENTRY_DB = "modules_config.db";
    private static final String ENTRY_DB_WAL = "modules_config.db-wal";
    private static final String ENTRY_DB_SHM = "modules_config.db-shm";

    /** The SharedPreferences file name used by ShizuPosed. */
    private static final String PREFS_FILE = "shizuposed_settings.xml";

    /** The database file name used by ShizuPosed. */
    private static final String DB_FILE = "modules_config.db";

    /** The keystore file the manager uses for module signing. */
    private static final String KEYSTORE_FILE = "keystore.bks";

    private ManagerMigrate() {}

    public static File createMigrateZip(Context context, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        if (dest.exists() && !dest.delete()) {
            throw new IllegalStateException("Could not delete stale migrate zip: " + dest);
        }

        try {
            File db = context.getDatabasePath(DB_FILE);
            if (db.exists()) {
                SQLiteDatabase sqlite = SQLiteDatabase.openDatabase(
                        db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                try {
                    android.database.Cursor c = sqlite.rawQuery(
                            "PRAGMA wal_checkpoint(FULL)", null);
                    try {
                        c.moveToFirst();
                    } finally {
                        c.close();
                    }
                } finally {
                    sqlite.close();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "wal_checkpoint failed: " + t.getMessage());
        }

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(dest)))) {

            putFile(zos, ENTRY_PREFS,
                new File(context.getApplicationInfo().dataDir,
                    "shared_prefs/" + PREFS_FILE));
            putFile(zos, ENTRY_KEYSTORE,
                new File(context.getFilesDir(), KEYSTORE_FILE));
            putFile(zos, ENTRY_DB,
                context.getDatabasePath(DB_FILE));
            putFile(zos, ENTRY_DB_WAL,
                new File(context.getDatabasePath(DB_FILE).getPath() + "-wal"));
            putFile(zos, ENTRY_DB_SHM,
                new File(context.getDatabasePath(DB_FILE).getPath() + "-shm"));
        }

        return dest;
    }

    private static void putFile(ZipOutputStream zos, String entryName, File file)
            throws Exception {
        if (!file.exists()) return;
        zos.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    /**
     * If this install came from a cloak flow, restore the previous
     * package's settings, keystore, and module DB. No-op on a
     * normal install (no migrate asset in the APK).
     */
    public static void importIfNeeded(Context context) {
        File marker = new File(context.getFilesDir(), MARKER);
        if (marker.exists()) return;

        File apk = new File(context.getApplicationInfo().sourceDir);
        if (!apk.exists()) return;

        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(ManagerCloak.MIGRATE_ASSET_PATH);
            if (entry == null) {
                context.getFilesDir().mkdirs();
                marker.createNewFile();
                return;
            }
            Log.i(TAG, "Importing migrate payload from manager APK");
            try (ZipInputStream zis = new ZipInputStream(zip.getInputStream(entry))) {
                ZipEntry e = zis.getNextEntry();
                while (e != null) {
                    String name = e.getName();
                    if (ENTRY_PREFS.equals(name)) {
                        write(zis, new File(context.getApplicationInfo().dataDir,
                            "shared_prefs/" + PREFS_FILE));
                    } else if (ENTRY_KEYSTORE.equals(name)) {
                        context.getFilesDir().mkdirs();
                        write(zis, new File(context.getFilesDir(), KEYSTORE_FILE));
                    } else if (ENTRY_DB.equals(name)) {
                        write(zis, context.getDatabasePath(DB_FILE));
                    } else if (ENTRY_DB_WAL.equals(name)) {
                        write(zis, new File(context.getDatabasePath(DB_FILE).getPath() + "-wal"));
                    } else if (ENTRY_DB_SHM.equals(name)) {
                        write(zis, new File(context.getDatabasePath(DB_FILE).getPath() + "-shm"));
                    }
                    zis.closeEntry();
                    e = zis.getNextEntry();
                }
            }
            context.getFilesDir().mkdirs();
            marker.createNewFile();
            Log.i(TAG, "Migrate import completed");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to import migrate payload", t);
        }
    }

    private static void write(ZipInputStream zis, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }
}