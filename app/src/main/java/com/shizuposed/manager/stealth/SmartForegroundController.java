package com.shizuposed.manager.stealth;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

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
 *
 * NO RESTORE
 * ----------
 * The previous design saved the prior adb_enabled value on disable
 * and restored it on exit. That produced a flip-flop bug: if the
 * monitor mis-detected the exit, the restore ran while the app was
 * still open, ADB came back on mid-session, and the app's check
 * saw it.
 *
 * The current design does not restore. ADB goes to 0 on enter and
 * stays there. The user restarts Shizuku when they want ADB back
 * — starting Shizuku's ADB server is what sets adb_enabled to 1.
 * The user's own restart is the restore.
 *
 * This works transparently with Shizuku-Next's "ADB without
 * Developer options" switch, because Shizuku stays alive without
 * ADB in that mode. On upstream Shizuku, the user has to restart
 * Shizuku manually after each scoped session. That's the cost.
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
                // The exit still dispatches an action so the
                // service can clean up any stale state file left
                // by an older build. The service does not restore
                // adb_enabled — see the class doc.
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
     * Clear the pending-restore flag from a previous install.
     *
     * The old design set this flag on disable and cleared it on
     * restore. The new design never uses it. If an old install
     * left it set, clear it here so nothing tries to act on it.
     */
    private void recoverIfNeeded() {
        try {
            SharedPreferences prefs = mContext.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_PENDING_RESTORE, false)) {
                Logger.getInstance(mContext).i(TAG
                    + ": clearing stale pending-restore flag from "
                    + "previous install (old restore path is gone)");
                prefs.edit().putBoolean(KEY_PENDING_RESTORE, false).apply();
            }
        } catch (Throwable t) {
            Logger.getInstance(mContext).w(TAG
                + ": recoverIfNeeded failed: " + t.getMessage());
        }
    }

    /**
     * Kept as a no-op for API compatibility with callers that
     * still reference it. The new smart-foreground design does not
     * use the pending-restore flag.
     */
    public static void markPendingRestore(Context context) {
        // No-op. See class doc.
    }

    /**
     * Kept as a no-op for API compatibility with callers that
     * still reference it.
     */
    public static void clearPendingRestore(Context context) {
        // No-op. See class doc.
    }
}