package com.shizuposed.manager.monitor;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.util.Set;

/**
 * ForegroundMonitor
 *
 * Polls UsageStatsManager for the current foreground package at a
 * fixed interval and notifies a listener on transition.
 *
 * UsageStatsManager is the only reliable way to observe foreground
 * transitions without root. The alternatives have drawbacks:
 *
 *   • getRunningTasks — restricted since Android 5, returns only
 *     the monitor's own tasks for untrusted apps.
 *   • Accessibility service — requires user enablement through
 *     Settings, has a persistent notification, and is a much
 *     heavier surface than a foreground observer needs.
 *   • ActivityManager.getRunningAppProcesses — filtered to the
 *     caller's own UID since Android 8.
 *
 * UsageStatsManager with PACKAGE_USAGE_STATS is the lightest
 * option. The permission is granted by the user through
 * Settings → Special app access → Usage access. The monitor
 * refuses to start if the permission isn't granted.
 *
 * The poll interval is 500 ms. That's fast enough to catch most
 * banking-app foreground transitions before the app's first
 * settings read fires, and slow enough that the battery cost is
 * negligible. A 100 ms interval catches earlier transitions at
 * ~5x the battery cost; a 2000 ms interval is cheaper but
 * misses the early checks entirely.
 */
public final class ForegroundMonitor {

    private static final String TAG = "ForegroundMonitor";

    /** Poll interval in milliseconds. */
    private static final long POLL_INTERVAL_MS = 250L;

    /** How far back to look when querying UsageStats events. */
    private static final long QUERY_WINDOW_MS = 60_000L;

    /**
     * Callback for foreground transitions. Both methods fire on
     * the monitor's worker thread, not the main thread.
     */
    public interface Listener {
        void onForegroundEntered(String packageName);
        void onForegroundExited(String packageName);
    }

    private final Context mContext;
    private final Listener mListener;
    private final Set<String> mWatchedPackages;
    private final UsageStatsManager mUsageStats;

    private HandlerThread mWorker;
    private Handler mHandler;
    private volatile boolean mRunning = false;
    private volatile String mLastForeground = null;

    public ForegroundMonitor(Context context,
                             Set<String> watchedPackages,
                             Listener listener) {
        mContext = context.getApplicationContext();
        mWatchedPackages = watchedPackages;
        mListener = listener;
        mUsageStats = (UsageStatsManager)
            mContext.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    /**
     * Returns true if the caller has been granted PACKAGE_USAGE_STATS.
     * UsageStatsManager.queryEvents returns an empty list when the
     * permission isn't granted, which is indistinguishable from
     * "no events". The only reliable check is to query a window we
     * know has events and see if we get anything back.
     */
    public boolean hasUsageStatsPermission() {
        if (mUsageStats == null) return false;

        long now = System.currentTimeMillis();
        UsageEvents events = mUsageStats.queryEvents(
            now - QUERY_WINDOW_MS, now);
        if (events == null) return false;

        // If the permission is granted, we'll see at least our own
        // package's activity in a 60-second window. If it isn't,
        // the iterator is empty.
        UsageEvents.Event e = new UsageEvents.Event();
        return events.hasNextEvent();
    }

    public synchronized void start() {
        if (mRunning) return;
        if (!hasUsageStatsPermission()) {
            Logger.getInstance(mContext).w(
                TAG + ": PACKAGE_USAGE_STATS not granted — monitor not started");
            return;
        }
        if (mWatchedPackages == null || mWatchedPackages.isEmpty()) {
            Logger.getInstance(mContext).i(
                TAG + ": no watched packages — monitor not started");
            return;
        }

        mWorker = new HandlerThread("ShizuPosed-fg",
                                   Process.THREAD_PRIORITY_BACKGROUND);
        mWorker.start();
        mHandler = new Handler(mWorker.getLooper());

        mRunning = true;
        mLastForeground = null;
        mHandler.post(mPollRunnable);
        Logger.getInstance(mContext).i(
            TAG + ": monitor started (" + mWatchedPackages.size()
            + " watched package(s), interval=" + POLL_INTERVAL_MS + "ms)");
    }

    public synchronized void stop() {
        if (!mRunning) return;
        mRunning = false;

        if (mHandler != null) {
            mHandler.removeCallbacks(mPollRunnable);
            mHandler = null;
        }
        if (mWorker != null) {
            mWorker.quitSafely();
            mWorker = null;
        }

        // If a watched app was in the foreground when we stopped,
        // fire the exit notification so state gets restored.
        String last = mLastForeground;
        mLastForeground = null;
        if (last != null && mListener != null) {
            try {
                mListener.onForegroundExited(last);
            } catch (Throwable t) {
                Logger.getInstance(mContext).w(
                    TAG + ": exit listener threw on stop: " + t.getMessage());
            }
        }

        Logger.getInstance(mContext).i(TAG + ": monitor stopped");
    }

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;

            try {
                String current = currentForegroundPackage();
                if (!java.util.Objects.equals(current, mLastForeground)) {
                    String previous = mLastForeground;
                    mLastForeground = current;

                    if (previous != null && isWatched(previous)) {
                        dispatchExit(previous);
                    }
                    if (current != null && isWatched(current)) {
                        dispatchEnter(current);
                    }
                }
            } catch (Throwable t) {
                Logger.getInstance(mContext).w(
                    TAG + ": poll failed: " + t.getMessage());
            }

            if (mRunning && mHandler != null) {
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private boolean isWatched(String pkg) {
        return pkg != null && mWatchedPackages.contains(pkg);
    }

    private void dispatchEnter(String pkg) {
        if (mListener == null) return;
        try {
            mListener.onForegroundEntered(pkg);
        } catch (Throwable t) {
            Logger.getInstance(mContext).w(
                TAG + ": enter listener threw for " + pkg + ": "
                + t.getMessage());
        }
    }

    private void dispatchExit(String pkg) {
        if (mListener == null) return;
        try {
            mListener.onForegroundExited(pkg);
        } catch (Throwable t) {
            Logger.getInstance(mContext).w(
                TAG + ": exit listener threw for " + pkg + ": "
                + t.getMessage());
        }
    }

    /**
     * Query UsageStatsManager for the current foreground package.
     *
     * The approach: request recent events, walk them backwards,
     * and find the most recent ACTIVITY_RESUMED event whose
     * package is not our own. That's the package the user is
     * currently interacting with.
     */
    private String currentForegroundPackage() {
        if (mUsageStats == null) return null;

        long now = System.currentTimeMillis();
        UsageEvents events = mUsageStats.queryEvents(
            now - QUERY_WINDOW_MS, now);
        if (events == null) return null;

        String latestPkg = null;
        long latestTs = 0;

        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) {
                continue;
            }
            if (e.getTimeStamp() > latestTs) {
                latestTs = e.getTimeStamp();
                latestPkg = e.getPackageName();
            }
        }

        // Filter out our own package — the manager shouldn't count
        // as foreground when the user has just opened it.
        if (mContext.getPackageName().equals(latestPkg)) return null;
        return latestPkg;
    }
}