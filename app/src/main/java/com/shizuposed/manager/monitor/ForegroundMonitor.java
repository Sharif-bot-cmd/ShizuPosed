package com.shizuposed.manager.monitor;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.shizuposed.manager.utils.Logger;

import java.util.HashMap;
import java.util.Map;
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
 * The poll interval is 250 ms. That's fast enough to catch most
 * banking-app foreground transitions before the app's first
 * settings read fires, and slow enough that the battery cost is
 * negligible.
 *
 * CORRECTNESS NOTE (state detection)
 * ----------------------------------
 * Earlier versions of this class picked the most recent
 * ACTIVITY_RESUMED event in the query window as "the foreground
 * package". That is wrong: the IME process and SystemUI both emit
 * ACTIVITY_RESUMED events that are newer than the user-facing
 * app's last resume, so the poll would return the IME or
 * SystemUI and the watched app would never be reported as
 * foreground. The exit path would still fire (because the
 * previously-foreground watched app was no longer "the latest"),
 * which produced spurious restores and no disables.
 *
 * The corrected logic walks events in order and maintains a
 * per-package "last event type" map. A package is considered
 * foreground if its most recent event in the window was
 * ACTIVITY_RESUMED. This handles the IME/SystemUI noise because
 * those packages resume and pause in quick succession, and their
 * last event is almost always ACTIVITY_PAUSED, not
 * ACTIVITY_RESUMED.
 *
 * CORRECTNESS NOTE (transition timing)
 * ------------------------------------
 * Even with correct state detection, the poll sees every
 * foreground change as an "exit + enter" pair. During an app's
 * own launch, the SplashActivity → MainActivity handoff, an IME
 * pop-up, a permission dialog, or a Toast can all briefly
 * displace the watched package from the foreground between two
 * polls. Firing the exit listener on those brief gaps caused the
 * restore to run mid-launch — the user would see Dev Options
 * disable and then immediately re-enable while still inside the
 * banking app.
 *
 * The fix is asymmetric debouncing:
 *
 *   • Enter fires immediately, because the disable must land
 *     before the banking app reads its settings.
 *
 *   • Exit is deferred. When a watched package drops out of the
 *     foreground, we record the time and wait EXIT_DWELL_MS
 *     before firing the exit listener. If the watched package
 *     returns to the foreground during that window, the pending
 *     exit is cancelled and no restore is sent.
 *
 * EXIT_DWELL_MS is 1500 ms: long enough to cover the launch-time
 * activity handoffs and IME/dialog interruptions, short enough
 * that the user has to genuinely leave the app before the
 * restore fires.
 */
public final class ForegroundMonitor {

    private static final String TAG = "ForegroundMonitor";

    /** Poll interval in milliseconds. */
    private static final long POLL_INTERVAL_MS = 250L;

    /** How far back to look when querying UsageStats events. */
    private static final long QUERY_WINDOW_MS = 60_000L;

    /**
     * How long a watched package must remain out of the
     * foreground before the exit listener fires. Covers
     * SplashActivity → MainActivity handoffs, IME pop-ups,
     * permission dialogs, and Toast interruptions during launch.
     */
    private static final long EXIT_DWELL_MS = 1500L;

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

    /**
     * The watched package that is currently considered
     * "entered", i.e. the disable is in effect for it. Null when
     * no watched package is in the foreground.
     */
    private String mActiveWatched = null;

    /**
     * When mActiveWatched dropped out of the foreground, or 0 if
     * it is still in the foreground. Used to debounce the exit.
     */
    private long mExitPendingSince = 0L;

    public ForegroundMonitor(Context context,
                             Set<String> watchedPackages,
                             Listener listener) {
        mContext = context.getApplicationContext();
        mWatchedPackages = watchedPackages;
        mListener = listener;
        mUsageStats = (UsageStatsManager)
            mContext.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public boolean hasUsageStatsPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager)
                mContext.getSystemService(Context.APP_OPS_SERVICE);

            if (appOps == null) return false;

            int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                mContext.getPackageName()
            );

            return mode == AppOpsManager.MODE_ALLOWED;

        } catch (Throwable t) {
            Logger.getInstance(mContext).w(
                TAG + ": permission check threw: " + t.getMessage());
            return false;
        }
    }

    /**
     * Isolates the API 29 deprecated call so the @SuppressWarnings
     * annotation is scoped to the smallest possible region and the
     * rest of hasUsageStatsPermission() stays clean.
     */
    @SuppressWarnings("deprecation")
    private int unsafeCheckOpNoThrowQ(AppOpsManager appOps) {
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            mContext.getPackageName()
        );
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
        mActiveWatched = null;
        mExitPendingSince = 0L;
        mHandler.post(mPollRunnable);
        Logger.getInstance(mContext).i(
            TAG + ": monitor started (" + mWatchedPackages.size()
            + " watched package(s), interval=" + POLL_INTERVAL_MS
            + "ms, exitDwell=" + EXIT_DWELL_MS + "ms)");
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
        String last = mActiveWatched;
        mActiveWatched = null;
        mExitPendingSince = 0L;
        if (last != null && mListener != null) {
            try {
                Logger.getInstance(mContext).i(
                    TAG + ": monitor stopping with active watched app "
                    + last + " — dispatching exit");
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
                tick();
            } catch (Throwable t) {
                Logger.getInstance(mContext).w(
                    TAG + ": poll failed: " + t.getMessage());
            }

            if (mRunning && mHandler != null) {
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    /**
     * One poll cycle. Computes the current foreground package,
     * then applies the enter/exit state machine with the exit
     * dwell debounce.
     */
    private void tick() {
        String current = currentForegroundPackage();
        boolean currentIsWatched = isWatched(current);

        long now = System.currentTimeMillis();

        if (currentIsWatched) {
            // A watched package is in the foreground right now.
            //
            // If we were waiting out an exit for a DIFFERENT
            // watched package, cancel that pending exit and
            // confirm it — the previous app's exit is real, we
            // are just switching between two watched apps.
            //
            // If we were waiting out an exit for the SAME watched
            // package, the exit was a launch-time blip — cancel
            // it and keep the disable in place.
            if (mActiveWatched != null
                    && !mActiveWatched.equals(current)
                    && mExitPendingSince != 0L) {
                String previous = mActiveWatched;
                Logger.getInstance(mContext).i(
                    TAG + ": watched switch confirmed " + previous
                    + " -> " + current);
                mActiveWatched = null;
                mExitPendingSince = 0L;
                dispatchExit(previous);
            } else if (mActiveWatched != null
                    && mActiveWatched.equals(current)
                    && mExitPendingSince != 0L) {
                Logger.getInstance(mContext).i(
                    TAG + ": exit cancelled for " + current
                    + " (returned within " + EXIT_DWELL_MS + "ms)");
                mExitPendingSince = 0L;
            }

            if (mActiveWatched == null) {
                // Fresh enter.
                mActiveWatched = current;
                mExitPendingSince = 0L;
                dispatchEnter(current);
            } else if (!mActiveWatched.equals(current)) {
                // Two watched apps in scope and the current one
                // is different from the active one. Guard branch:
                // shouldn't normally be reached given the
                // branches above, but keep the state machine
                // consistent if it is.
                String previous = mActiveWatched;
                mActiveWatched = current;
                mExitPendingSince = 0L;
                dispatchExit(previous);
                dispatchEnter(current);
            }
            // else: same watched app still in foreground. No-op.

            return;
        }

        // No watched package is in the foreground right now.
        if (mActiveWatched == null) {
            // Nothing to do — we never entered.
            return;
        }

        if (mExitPendingSince == 0L) {
            // Watched app just dropped out of the foreground.
            // Start the dwell timer instead of firing exit
            // immediately.
            mExitPendingSince = now;
            Logger.getInstance(mContext).i(
                TAG + ": " + mActiveWatched
                + " left foreground — starting "
                + EXIT_DWELL_MS + "ms exit dwell");
            return;
        }

        long elapsed = now - mExitPendingSince;
        if (elapsed >= EXIT_DWELL_MS) {
            // Dwell elapsed without the watched app returning.
            // Fire the real exit.
            String exiting = mActiveWatched;
            mActiveWatched = null;
            mExitPendingSince = 0L;
            Logger.getInstance(mContext).i(
                TAG + ": exit confirmed for " + exiting
                + " after " + elapsed + "ms");
            dispatchExit(exiting);
        }
        // else: still within the dwell window. Wait for the
        // next poll.
    }

    private boolean isWatched(String pkg) {
        return pkg != null && mWatchedPackages.contains(pkg);
    }

    private void dispatchEnter(String pkg) {
        if (mListener == null) return;
        try {
            Logger.getInstance(mContext).i(
                TAG + ": dispatchEnter " + pkg);
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
            Logger.getInstance(mContext).i(
                TAG + ": dispatchExit " + pkg);
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
     * Walks events in chronological order and maintains a per-package
     * "last event type" map. A package is considered foreground if
     * its most recent event in the window was ACTIVITY_RESUMED.
     *
     * This handles the IME/SystemUI noise correctly: those packages
     * resume and pause in rapid succession, so their last event is
     * almost always ACTIVITY_PAUSED, and they never appear as the
     * foreground package.
     *
     * The most recently-resumed package that is still resumed wins.
     * Ties are broken by the timestamp of the most recent
     * ACTIVITY_RESUMED event.
     */
    private String currentForegroundPackage() {
        if (mUsageStats == null) return null;

        long now = System.currentTimeMillis();
        UsageEvents events = mUsageStats.queryEvents(
            now - QUERY_WINDOW_MS, now);
        if (events == null) return null;

        // Map of package -> most recent event type
        // (ACTIVITY_RESUMED or ACTIVITY_PAUSED or ACTIVITY_STOPPED)
        Map<String, Integer> lastEvent = new HashMap<>();
        // Map of package -> timestamp of the most recent
        // ACTIVITY_RESUMED event (only meaningful for packages
        // whose last event is ACTIVITY_RESUMED)
        Map<String, Long> lastResumeTs = new HashMap<>();

        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);

            int type = e.getEventType();
            if (type != UsageEvents.Event.ACTIVITY_RESUMED
                    && type != UsageEvents.Event.ACTIVITY_PAUSED
                    && type != UsageEvents.Event.ACTIVITY_STOPPED) {
                continue;
            }

            String pkg = e.getPackageName();
            if (pkg == null) continue;

            lastEvent.put(pkg, type);
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                Long prev = lastResumeTs.get(pkg);
                long ts = e.getTimeStamp();
                if (prev == null || ts > prev) {
                    lastResumeTs.put(pkg, ts);
                }
            }
        }

        // Find the package whose last event is ACTIVITY_RESUMED
        // with the most recent resume timestamp.
        String bestPkg = null;
        long bestTs = 0L;

        for (Map.Entry<String, Integer> entry : lastEvent.entrySet()) {
            if (entry.getValue() != UsageEvents.Event.ACTIVITY_RESUMED) {
                continue;
            }
            String pkg = entry.getKey();
            if (mContext.getPackageName().equals(pkg)) {
                // Skip our own package. The manager shouldn't count
                // as the foreground app when the user just opened it.
                continue;
            }
            Long ts = lastResumeTs.get(pkg);
            if (ts == null) continue;
            if (ts > bestTs) {
                bestTs = ts;
                bestPkg = pkg;
            }
        }

        return bestPkg;
    }
}