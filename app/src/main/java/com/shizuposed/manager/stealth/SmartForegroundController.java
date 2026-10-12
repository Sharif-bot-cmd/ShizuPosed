package com.shizuposed.manager.stealth;

import android.content.Context;
import android.content.Intent;

import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.monitor.ForegroundMonitor;

import java.util.Set;

/**
 * SmartForegroundController
 *
 * Coordinates the smart-foreground feature.
 *
 * ACTIVATION (Design B)
 * ---------------------
 * There is no dedicated master toggle. The feature is active when
 * both of these are true in XStealthConfig:
 *
 *   • At least one of hideDevOptions / hideAdb is on.
 *   • smartForegroundScope is non-empty.
 *
 * The scope names the packages whose foreground triggers the state
 * change. Adding apps to the scope is the way a user opts in.
 *
 * STATE RECOVERY
 * --------------
 * If the manager is killed while a watched app is in the
 * foreground, the settings stay off (0) and there's no service
 * running to restore them. On next manager startup, this
 * controller reads a persisted flag from SharedPreferences:
 * "we last wrote 0, we haven't restored yet". If the flag is
 * set, the controller issues a restore action immediately, before
 * starting the monitor.
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

    /**
     * Called from ShizuPosedManagerApp.onCreate. Checks for a
     * pending-restore flag left behind by a killed manager, issues
     * the restore, then starts the monitor if the config enables
     * it.
     */
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
     * Called after the config changes (from the detail sheet).
     * Re-reads the config, stops the existing monitor, and starts
     * a new one if the config still enables the feature.
     *
     * The gate is isSmartForegroundActive(): a signal toggle must
     * be on, and the scope must be non-empty.
     */
    public synchronized void restartMonitor() {
        if (mMonitor != null) {
            mMonitor.stop();
            mMonitor = null;
        }

        XStealthConfig config = XStealthConfig.load();
        if (config == null || !config.isSmartForegroundActive()) {
            Logger.getInstance(mContext).i(
                TAG + ": smart foreground not active "
                + "(signals on? scope set?) — monitor not started");
            return;
        }

        Set<String> scope = config.smartForegroundScope;

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

        mMonitor = new ForegroundMonitor(mContext, scope, listener);
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

    private void recoverIfNeeded() {
        try {
            android.content.SharedPreferences prefs =
                mContext.getSharedPreferences(PREFS_NAME,
                                              Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_PENDING_RESTORE, false)) return;

            Logger.getInstance(mContext).w(
                TAG + ": pending restore flag set — issuing restore");

            prefs.edit().putBoolean(KEY_PENDING_RESTORE, false).apply();

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