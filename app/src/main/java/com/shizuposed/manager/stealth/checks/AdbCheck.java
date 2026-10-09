package com.shizuposed.manager.stealth.checks;

import android.content.ContentResolver;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Parcel;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AdbCheck
 *
 * Hides ADB being enabled. Overlaps with DevOptionsCheck but covers
 * Settings.Secure lookups that DevOptionsCheck does not touch, plus
 * the Settings.Global keys that some detectors read via different
 * call sites.
 *
 * Keys hidden:
 *   • adb_enabled
 *   • adb_wifi_enabled
 *
 * The key list is delegated to DevOptionsCheck.hidesKey so the two
 * checks can never drift.
 *
 * THREE LEVELS
 * ------------
 * Level 1 — Java API hooks
 *   Settings.Global/Secure static getters and ContentResolver.query.
 *
 * Level 2 — Binder.transact hook, IContentProvider
 *   The settings provider is reached over Binder as
 *   android.content.IContentProvider. The transaction code for
 *   query is stable across Android versions. The hook reads the
 *   URI from the parcel, extracts the key, and refuses the
 *   transaction for hidden keys by throwing RemoteException.
 *
 * Level 3 — Binder.transact hook, DUMP_TRANSACTION
 *   A target that shells out `dumpsys settings get global <key>`
 *   via Binder.dump(...) sends a DUMP_TRANSACTION whose parcel
 *   starts with a file descriptor. The parser reads past it into
 *   the args array and refuses if the args name a hidden key.
 *
 * SCOPE
 * -----
 * The Binder hooks only see calls that go through the Java
 * IBinder.transact method in *this process*. They do not see:
 *
 *   • Calls from a helper process the target spawned.
 *   • Native code that opens /dev/binder directly.
 *   • Calls made before the hooks are installed.
 *
 * The Java-level hooks still cover the ContentResolver path, and
 * the shell-out interposers in libxstealth.so still cover the
 * Runtime.exec path for `cat /data/system/.../settings_*.xml`.
 */
public final class AdbCheck {

    private static final String TAG = "XStealth";

    /** Binder descriptor for the content provider interface. */
    private static final String PROVIDER_DESCRIPTOR =
        "android.content.IContentProvider";

    /**
     * IContentProvider.query's transaction code. The interface's
     * method table has been stable since API 1; query is the first
     * method, so FIRST_CALL_TRANSACTION + 0 == 1.
     */
    private static final int QUERY_TRANSACTION = 1;

    /** IBinder.DUMP_TRANSACTION — see PackageCheck for the value. */
    private static final int DUMP_TRANSACTION = 1598311760;

    private static final AtomicLong sTransactSeen    = new AtomicLong();
    private static final AtomicLong sTransactRefused = new AtomicLong();
    private static final AtomicLong sDumpRefused     = new AtomicLong();

    private AdbCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam,
                               XStealthConfig config) {
        if (lpparam == null || config == null) return;
        if (!config.hideAdb) return;

        hookSettingsClass(lpparam, "android.provider.Settings$Global",
            config);
        hookSettingsClass(lpparam, "android.provider.Settings$Secure",
            config);
        hookContentResolverQuery(config);
        hookBinderTransact();
        XStealthRegistry.record("AdbCheck");
    }

    /** Snapshot for the detail sheet. */
    public static String describeBinder() {
        return "transact=" + sTransactSeen.get()
            + " queryRefused=" + sTransactRefused.get()
            + " dumpRefused=" + sDumpRefused.get();
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 2 + 3 — BINDER TRANSACT HOOK
    // ═════════════════════════════════════════════════════════════

    private static void hookBinderTransact() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.BinderProxy", null, "transact",
                int.class, Parcel.class, Parcel.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        int code = (Integer) p.args[0];
                        Parcel data = (Parcel) p.args[1];
                        if (data == null) return;

                        // ── DUMP_TRANSACTION ────────────────────
                        if (code == DUMP_TRANSACTION) {
                            checkDump(data);
                            return;
                        }

                        // ── IContentProvider.query ──────────────
                        if (code != QUERY_TRANSACTION) return;

                        String descriptor = readInterfaceDescriptor(data);
                        if (descriptor == null
                                || !descriptor.equals(PROVIDER_DESCRIPTOR)) {
                            return;
                        }

                        sTransactSeen.incrementAndGet();

                        int pos = data.dataPosition();
                        try {
                            data.readString();   // callingPackage
                            data.readString();   // attributionTag (nullable)
                            Uri uri = data.readTypedObject(Uri.CREATOR);
                            if (uri == null) return;

                            String key = extractSettingsKey(uri);
                            if (key == null) return;
                            if (!hidesKey(key)) return;

                            sTransactRefused.incrementAndGet();
                            XposedBridge.log(TAG + ": Binder "
                                + "IContentProvider.query refused for key "
                                + key);
                            throw new android.os.RemoteException(
                                "settings query denied");
                        } catch (android.os.RemoteException re) {
                            throw re;
                        } catch (Throwable parseErr) {
                            // Parcel shape not what we expected.
                        } finally {
                            try { data.setDataPosition(pos); }
                            catch (Throwable ignored) {}
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AdbCheck Binder.transact hook failed: " + t);
        }
    }

    /**
     * Parse a DUMP_TRANSACTION parcel and refuse if the args array
     * names a hidden settings key alongside a settings service.
     *
     * Matches `dumpsys settings get global adb_enabled` and the
     * secure-namespace equivalent. Doesn't match `dumpsys settings
     * list` (no key) — that shape doesn't reveal a hidden value.
     *
     * Uses Parcel.createStringArray() rather than readStringArray():
     * readStringArray takes a pre-allocated out-parameter on some
     * Android versions and returns an array on others, and the
     * stub the compiler sees is inconsistent about which overload
     * is available. createStringArray() is the one that has always
     * returned the array directly.
     */
    private static void checkDump(Parcel data)
            throws android.os.RemoteException {
        int pos = data.dataPosition();
        try {
            // Descriptor string (present when called through
            // BinderProxy.dump, absent for a hand-rolled
            // transact(DUMP_TRANSACTION, ...)).
            try { data.readString(); } catch (Throwable ignored) {}

            // File descriptor.
            try { data.readFileDescriptor(); } catch (Throwable ignored) {}

            // Args array.
            String[] args = null;
            try { args = data.createStringArray(); } catch (Throwable ignored) {}
            if (args == null || args.length == 0) return;

            // Is `settings` in the args?
            boolean isSettingsDump = false;
            for (String a : args) {
                if ("settings".equals(a)) { isSettingsDump = true; break; }
            }
            if (!isSettingsDump) return;

            // Does any arg name a hidden key?
            String hit = null;
            for (String a : args) {
                if (a == null) continue;
                if (hidesKey(a)) { hit = a; break; }
            }
            if (hit == null) return;

            sDumpRefused.incrementAndGet();
            XposedBridge.log(TAG + ": Binder DUMP refused for key " + hit);
            throw new android.os.RemoteException(
                "settings key " + hit + " not found");
        } catch (android.os.RemoteException re) {
            throw re;
        } catch (Throwable parseErr) {
            // Parcel shape not what we expected. Pass through.
        } finally {
            try { data.setDataPosition(pos); }
            catch (Throwable ignored) {}
        }
    }

    private static String readInterfaceDescriptor(Parcel data) {
        int pos = data.dataPosition();
        try {
            return data.readString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { data.setDataPosition(pos); } catch (Throwable ignored) {}
        }
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 1 — JAVA API HOOKS
    // ═════════════════════════════════════════════════════════════

    private static void hookSettingsClass(XC_LoadPackage.LoadPackageParam lpparam,
                                          String className,
                                          XStealthConfig config) {
        Class<?> clazz = XposedHelpers.findClassIfExists(
            className, lpparam.classLoader);
        if (clazz == null) return;

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getInt",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + ".getInt hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + ".getString hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getLong",
                ContentResolver.class, String.class, long.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0L);
                    }
                });
        } catch (Throwable t) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getIntForUser",
                ContentResolver.class, String.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                clazz, "getStringForUser",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
        }

        XposedBridge.log(TAG + ": AdbCheck installed on " + className);
    }

    private static void hookContentResolverQuery(XStealthConfig config) {
        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver.class, "query",
                Uri.class, String[].class, String.class,
                String[].class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p)
                            throws Throwable {
                        Uri uri = (Uri) p.args[0];
                        if (uri == null) return;
                        String key = extractSettingsKey(uri);
                        if (key == null) return;
                        if (!hidesKey(key)) return;

                        String selection = (String) p.args[2];
                        String[] selArgs = (String[]) p.args[3];
                        if (!selectionReferencesKey(selection, selArgs, key)) {
                            p.setResult(new MatrixCursor(
                                new String[]{"_id", "name", "value"}));
                            return;
                        }

                        MatrixCursor c = new MatrixCursor(
                            new String[]{"_id", "name", "value"});
                        c.addRow(new Object[]{0, key, "0"});
                        p.setResult(c);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AdbCheck query hook failed: " + t);
        }
    }

    private static String extractSettingsKey(Uri uri) {
        if (uri == null) return null;
        String authority = uri.getAuthority();
        if (!"settings".equals(authority)) return null;
        String path = uri.getPath();
        if (path == null) return null;
        if (!path.startsWith("/global/") && !path.startsWith("/secure/")) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) return null;
        return path.substring(slash + 1);
    }

    static boolean selectionReferencesKey(String selection,
                                          String[] selectionArgs,
                                          String key) {
        if (selection == null || selection.isEmpty()) return true;
        if (selection.contains(key)) return true;
        if (selectionArgs != null) {
            for (String a : selectionArgs) {
                if (key.equals(a)) return true;
            }
        }
        return false;
    }

    static boolean hidesKey(String keyOrUri) {
        return DevOptionsCheck.hidesKey(keyOrUri);
    }
}