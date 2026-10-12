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
 * ACTIVATION (Design B)
 * ---------------------
 * The feature is active when both of these are true:
 *
 *   • XStealthPrefs.isHideDevOptions() || XStealthPrefs.isHideAdb()
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
     */
    public synchronized void restartMonitor() {
        if (mMonitor != null) {
            mMonitor.stop();
            mMonitor = null;
        }

        boolean hideDev = XStealthPrefs.isHideDevOptions(mContext);
        boolean hideAdb = XStealthPrefs.isHideAdb(mContext);
        Set<String> scope = XStealthPrefs.getSmartForegroundScope(mContext);

        if (scope == null) scope = new HashSet<>();
        int n = scope.size();

        Logger.getInstance(mContext).i(TAG + ": restartMonitor"
            + " hideDev=" + hideDev
            + " hideAdb=" + hideAdb
            + " scope=" + n);

        if (!hideDev && !hideAdb) {
            Logger.getInstance(mContext).i(TAG
                + ": both signal toggles off — monitor not started");
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