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
 * DevOptionsCheck
 *
 * Hides that Developer Options are enabled. Hooks every common
 * path an app uses to read the setting:
 *
 *   Level 1 — Java API
 *     • Settings.Global.getInt / getString / getLong / getFloat
 *     • Settings.Global.getIntForUser / getStringForUser /
 *       getLongForUser
 *     • ContentResolver.query on content://settings/global/<key>
 *
 *   Level 2 — Binder.transact
 *     • IContentProvider.query — refuses a query against a hidden
 *       key. Same shape as AdbCheck's hook; the two install
 *       independently so a device with hideDevOptions on and
 *       hideAdb off still covers the DevOptions keys over Binder.
 *     • DUMP_TRANSACTION — refuses `dumpsys settings get global
 *       <key>` for hidden keys. Same shape as AdbCheck.
 *
 * Keys hidden:
 *   • development_settings_enabled
 *   • adb_enabled
 *   • adb_wifi_enabled
 *   • development_enable_adi
 *
 * This class is the canonical source of the hidden-key list.
 * AdbCheck delegates to hidesKey() so the two checks can never
 * disagree about what is hidden.
 *
 * No state change. The class sanitizes reads only. The underlying
 * value in the settings database is unchanged.
 */
public final class DevOptionsCheck {

    private static final String TAG = "XStealth";

    /** Binder descriptor for the content provider interface. */
    private static final String PROVIDER_DESCRIPTOR =
        "android.content.IContentProvider";

    /**
     * IContentProvider.query's transaction code. Stable across
     * Android versions — query is the first method in the table.
     */
    private static final int QUERY_TRANSACTION = 1;

    /** IBinder.DUMP_TRANSACTION. */
    private static final int DUMP_TRANSACTION = 1598311760;

    private static final String[] HIDDEN_KEYS = {
        "development_settings_enabled",
        "adb_enabled",
        "adb_wifi_enabled",
        "development_enable_adi",
    };

    /** Diagnostics counters, per-process. */
    private static final AtomicLong sTransactRefused = new AtomicLong();
    private static final AtomicLong sDumpRefused     = new AtomicLong();

    private DevOptionsCheck() {}

    public static void install(XC_LoadPackage.LoadPackageParam lpparam,
                               XStealthConfig config) {
        if (lpparam == null || config == null) return;
        if (!config.hideDevOptions) return;

        // ── Level 1: Java API ──────────────────────────────────
        Class<?> settingsGlobal = XposedHelpers.findClassIfExists(
            "android.provider.Settings$Global", lpparam.classLoader);
        if (settingsGlobal != null) {
            hookStaticGetters(settingsGlobal);
            hookForUserGetters(settingsGlobal);
        }
        hookContentResolverQuery();

        // ── Level 2: Binder ────────────────────────────────────
        hookBinderTransact();

        XposedBridge.log(TAG + ": DevOptionsCheck installed "
            + "(java+binder)");
        XStealthRegistry.record("DevOptionsCheck");
    }

    /** Snapshot for the detail sheet. */
    public static String describeBinder() {
        return "queryRefused=" + sTransactRefused.get()
            + " dumpRefused=" + sDumpRefused.get();
    }

    // ═════════════════════════════════════════════════════════════
    // LEVEL 2 — BINDER TRANSACT HOOK
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
            XposedBridge.log(TAG + ": DevOptionsCheck Binder.transact "
                + "hook failed: " + t);
        }
    }

    /**
     * Parse a DUMP_TRANSACTION parcel and refuse if the args array
     * names a hidden settings key alongside a settings service.
     *
     * Uses Parcel.createStringArray() rather than
     * readStringArray(): readStringArray takes a pre-allocated
     * out-parameter on some Android versions and returns an array
     * on others, and the stub the compiler sees is inconsistent
     * about which overload is available.
     */
    private static void checkDump(Parcel data)
            throws android.os.RemoteException {
        int pos = data.dataPosition();
        try {
            try { data.readString(); } catch (Throwable ignored) {}
            try { data.readFileDescriptor(); } catch (Throwable ignored) {}

            String[] args = null;
            try { args = data.createStringArray(); } catch (Throwable ignored) {}
            if (args == null || args.length == 0) return;

            boolean isSettingsDump = false;
            for (String a : args) {
                if ("settings".equals(a)) { isSettingsDump = true; break; }
            }
            if (!isSettingsDump) return;

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
            // Pass through.
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
    // Settings.Global static getters
    // ═════════════════════════════════════════════════════════════

    private static void hookStaticGetters(Class<?> settingsGlobal) {
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getInt",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getInt hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getString",
                ContentResolver.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getString hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getLong",
                ContentResolver.class, String.class, long.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0L);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getLong hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getFloat",
                ContentResolver.class, String.class, float.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0f);
                    }
                });
        } catch (Throwable t) {
            // getFloat isn't always declared. Not fatal.
        }
    }

    // ═════════════════════════════════════════════════════════════
    // Settings.Global ForUser variants
    // ═════════════════════════════════════════════════════════════

    private static void hookForUserGetters(Class<?> settingsGlobal) {
        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getIntForUser",
                ContentResolver.class, String.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getIntForUser hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getStringForUser",
                ContentResolver.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult("0");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getStringForUser hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                settingsGlobal, "getLongForUser",
                ContentResolver.class, String.class, long.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (hidesKey((String) p.args[1])) p.setResult(0L);
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Global.getLongForUser hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ContentResolver.query
    // ═════════════════════════════════════════════════════════════

    private static void hookContentResolverQuery() {
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
                        if (!AdbCheck.selectionReferencesKey(
                                selection, selArgs, key)) {
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
            XposedBridge.log(TAG + ": ContentResolver.query hook failed: " + t);
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

    // ═════════════════════════════════════════════════════════════
    // Key matching — canonical for the whole package
    // ═════════════════════════════════════════════════════════════

    static boolean hidesKey(String keyOrUri) {
        if (keyOrUri == null) return false;
        String key = keyOrUri;
        int slash = keyOrUri.lastIndexOf('/');
        if (slash >= 0 && slash < keyOrUri.length() - 1) {
            key = keyOrUri.substring(slash + 1);
        }
        for (String k : HIDDEN_KEYS) {
            if (k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    static String[] hiddenKeys() {
        return HIDDEN_KEYS.clone();
    }
}