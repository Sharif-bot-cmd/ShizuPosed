package com.shizuposed.manager.stealth;

import android.content.Context;
import android.content.Intent;

import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.monitor.ForegroundMonitor;

import java.util.HashSet;
import java.util.Set;

/**
 * SmartForegroundController
 *
 * Coordinates the smart-foreground feature.
 *
 * ACTIVATION
 * ----------
 * The feature is active when both of these are true:
 *
 *   • XStealthPrefs.isHideAdb()  (the only signal the service
 *     manages now — development_settings_enabled was dropped
 *     because on some ROMs it gets reverted by the system or by
 *     the target app's own anti-tamper logic, and managing it
 *     caused more problems than it solved. The hideDevOptions
 *     pref still gates XStealth's read interception; it just no
 *     longer drives the foreground state change.)
 *   • XStealthPrefs.getSmartForegroundScope() is non-empty
 *
 * The manager reads these from its own SharedPreferences. The
 * shell side reads the equivalent values from xstealth.json, which
 * XStealthStatusWriter writes from the same SharedPreferences.
 * There is one source of truth (XStealthPrefs) and one mirror
 * (xstealth.json). The manager never reads the shell-side mirror;
 * it can't reach that file.
 */
public final class SmartForegroundController {

    private static final String TAG = "SmartFg";

    private static final String PREFS_NAME = "shizuposed_smart_fg";
    private static final String KEY_PENDING_RESTORE = "pending_restore";

    private static volatile SmartForegroundController sInstance = null;

    private final Context mContext;
    private ForegroundMonitor mMonitor;

    private SmartForegroundController(Context context) {
        mContext = context.getApplicationContext();
    }

    public static SmartForegroundController getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SmartForegroundController.class) {
                if (sInstance == null) {
                    sInstance = new SmartForegroundController(context);
                }
            }
        }
        return sInstance;
    }

    public void start() {
        recoverIfNeeded();
        restartMonitor();
    }

    public synchronized void stop() {
        if (mMonitor != null) {
            mMonitor.stop();
            mMonitor = null;
        }
    }

    /**
     * Called after the config changes (from the detail sheet or
     * the scope picker). Stops any existing monitor and starts a
     * new one if the config still enables the feature.
     *
     * The gate reads from XStealthPrefs — the manager's own
     * SharedPreferences. XStealthConfig.load() reads a file the
     * manager cannot access; using it here was a bug.
     *
     * Only hideAdb is required. hideDevOptions no longer drives the
     * foreground state change — see the class doc. If the user has
     * hideDevOptions on but hideAdb off, the monitor does not start,
     * because there is nothing for it to do (the service only
     * writes adb_enabled).
     */
    public synchronized void restartMonitor() {
        if (mMonitor != null) {
            mMonitor.stop();
            mMonitor = null;
        }

        boolean hideAdb = XStealthPrefs.isHideAdb(mContext);
        Set<String> scope = XStealthPrefs.getSmartForegroundScope(mContext);

        if (scope == null) scope = new HashSet<>();
        int n = scope.size();

        Logger.getInstance(mContext).i(TAG + ": restartMonitor"
            + " hideAdb=" + hideAdb
            + " scope=" + n);

        if (!hideAdb) {
            Logger.getInstance(mContext).i(TAG
                + ": hideAdb off — monitor not started");
            return;
        }
        if (n == 0) {
            Logger.getInstance(mContext).i(TAG
                + ": scope empty — monitor not started");
            return;
        }

        final Set<String> finalScope = new HashSet<>(scope);

        ForegroundMonitor.Listener listener = new ForegroundMonitor.Listener() {
            @Override
            public void onForegroundEntered(String packageName) {
                Logger.getInstance(mContext).i(
                    TAG + ": entered " + packageName);
                sendServiceAction(ShizuPosedService.ACTION_DISABLE_DEV_SETTINGS,
                                  packageName);
            }

            @Override
            public void onForegroundExited(String packageName) {
                Logger.getInstance(mContext).i(
                    TAG + ": exited " + packageName);
                sendServiceAction(ShizuPosedService.ACTION_RESTORE_DEV_SETTINGS,
                                  packageName);
            }
        };

        mMonitor = new ForegroundMonitor(mContext, finalScope, listener);
        mMonitor.start();
    }

    private void sendServiceAction(String action, String packageName) {
        try {
            Intent i = new Intent(mContext, ShizuPosedService.class);
            i.setAction(action);
            i.putExtra(ShizuPosedService.EXTRA_SMART_FG_PACKAGE, packageName);
            mContext.startService(i);
        } catch (Throwable t) {
            Logger.getInstance(mContext).w(
                TAG + ": failed to send " + action + ": " + t.getMessage());
        }
    }

    /**
     * Issue a recovery restore if a previous process died while a
     * disable was in effect.
     *
     * The pending flag is NOT cleared here. It is cleared by
     * ShizuPosedService.restoreDevSettings() only after both
     * settings have been written back successfully. Clearing it
     * before the restore runs means a failure (Shizuku not yet
     * authorized, service killed mid-restore, watched app still
     * foreground) drops the retry, leaves smart_fg_state on disk,
     * and the next service start sees a stale state file. Letting
     * the service clear the flag keeps the retry alive until the
     * restore actually succeeds.
     *
     * If the service can't reach Shizuku yet, the flag stays set
     * and the next SmartForegroundController.start() or next
     * service boot retries. That's the intended behavior — a
     * failed restore must not be forgotten.
     */
    private void recoverIfNeeded() {
        try {
            android.content.SharedPreferences prefs =
                mContext.getSharedPreferences(PREFS_NAME,
                                              Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_PENDING_RESTORE, false)) return;

            Logger.getInstance(mContext).w(
                TAG + ": pending restore flag set — issuing restore");

            // Do not clear the flag here. ShizuPosedService's
            // restoreDevSettings() will call clearPendingRestore()
            // once the settings are actually written back. If the
            // restore is deferred (watched app still foreground,
            // grant not effective, service not ready), the flag
            // remains set and the retry happens on the next
            // controller start or service boot.
            Intent i = new Intent(mContext, ShizuPosedService.class);
            i.setAction(ShizuPosedService.ACTION_RESTORE_DEV_SETTINGS);
            i.putExtra(ShizuPosedService.EXTRA_SMART_FG_PACKAGE, "(recovery)");
            mContext.startService(i);
        } catch (Throwable t) {
            Logger.getInstance(mContext).w(
                TAG + ": recovery failed: " + t.getMessage());
        }
    }

    public static void markPendingRestore(Context context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit()
                   .putBoolean(KEY_PENDING_RESTORE, true)
                   .apply();
        } catch (Throwable t) {
            // non-fatal
        }
    }

    public static void clearPendingRestore(Context context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit()
                   .putBoolean(KEY_PENDING_RESTORE, false)
                   .apply();
        } catch (Throwable t) {
            // non-fatal
        }
    }
}