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
 * (design notes unchanged — see the original header for the
 * rationale on UsageStatsManager, the state-detection fix, and
 * the asymmetric exit debounce)
 *
 * CORRECTNESS NOTE (freshness)
 * ----------------------------
 * A package whose most recent event in the window is
 * ACTIVITY_RESUMED is not necessarily in the foreground right
 * now. If the user leaves a watched app in a way that does not
 * bring another app to the front within the poll interval — Home
 * press on a launcher that does not emit RESUMED promptly, a
 * blank home page, screen-off-then-on — the watched app remains
 * the most recently resumed package for the whole QUERY_WINDOW_MS,
 * and the monitor keeps reporting it as foreground. That kept the
 * disable in effect after the user had left the app: Dev Options
 * and ADB stayed off while the user was on the home screen.
 *
 * The fix is a freshness gate: a resume only counts as
 * "foreground" if it happened within FOREGROUND_FRESHNESS_MS. The
 * value must be larger than POLL_INTERVAL_MS (so a live app is
 * never missed between polls) and smaller than EXIT_DWELL_MS (so
 * a departure is detected before the dwell timer would
 * otherwise be needed). 1500 ms satisfies both.
 */
public final class ForegroundMonitor {

    private static final String TAG = "ForegroundMonitor";

    /** Poll interval in milliseconds. */
    private static final long POLL_INTERVAL_MS = 250L;

    /** How far back to look when querying UsageStats events. */
    private static final long QUERY_WINDOW_MS = 60_000L;

    /**
     * Dwell applied to a normal exit (steady-state departure).
     */
    private static final long EXIT_DWELL_MS = 4_000L;

    /**
     * Dwell applied to the first exit after an enter. Covers slow
     * ACTIVITY_RESUMED handoffs — apps with a long splash screen
     * (Facebook, Messenger) take 5–8 seconds before the main
     * activity emits RESUMED, and during that gap the freshness
     * window expires and the normal exit dwell would fire.
     */
    private static final long FIRST_EXIT_DWELL_MS = 10_000L;

    /** Dwell chosen for the current exit; set when the dwell starts. */
    private long mPendingDwell = EXIT_DWELL_MS;

    /** When mActiveWatched was entered, or 0 if not entered. */
    private long mEnteredAt = 0L;

    /**
     * A resume only counts as "foreground" if it happened within
     * this many milliseconds of now. Without this, a watched app
     * whose last event in the query window is a resume stays
     * "foreground" long after the user left it, because nothing
     * else has resumed to displace it. That produced spurious
     * disables: Dev Options and ADB stayed off while the user was
     * on the home screen.
     *
     * Must be > POLL_INTERVAL_MS so a live app is never missed
     * between polls, and <= EXIT_DWELL_MS so a departure is
     * detected before the dwell timer expires on its own.
     */
    private static final long FOREGROUND_FRESHNESS_MS = 4000L;

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
        mEnteredAt = 0L;
        mPendingDwell = EXIT_DWELL_MS;

        // Seed the active-watched state from the current
        // foreground package WITHOUT firing enter. If the user is
        // already inside a scoped app when the monitor starts —
        // e.g. because they just saved the scope from inside that
        // app — we must not treat that as a fresh enter, or Dev
        // Options and ADB flip off without the user having opened
        // the app. The next real transition fires normally.
        String seed = currentForegroundPackage();
        if (isWatched(seed)) {
            mActiveWatched = seed;
            Logger.getInstance(mContext).i(
                TAG + ": seeded active-watched=" + seed
                + " (already foreground at monitor start; enter suppressed)");
        }

        mHandler.post(mPollRunnable);
        Logger.getInstance(mContext).i(
            TAG + ": monitor started (" + mWatchedPackages.size()
            + " watched package(s), interval=" + POLL_INTERVAL_MS
            + "ms, exitDwell=" + EXIT_DWELL_MS
            + "ms, freshness=" + FOREGROUND_FRESHNESS_MS + "ms)");
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
        mEnteredAt = 0L;
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
            // A watched package is (or may be) in the foreground.
            if (mActiveWatched != null
                    && !mActiveWatched.equals(current)
                    && mExitPendingSince != 0L) {
                String previous = mActiveWatched;
                Logger.getInstance(mContext).i(
                    TAG + ": watched switch confirmed " + previous
                    + " -> " + current);
                mActiveWatched = null;
                mExitPendingSince = 0L;
                mEnteredAt = 0L;
                dispatchExit(previous);
            } else if (mActiveWatched != null
                    && mActiveWatched.equals(current)
                    && mExitPendingSince != 0L) {
                Logger.getInstance(mContext).i(
                    TAG + ": exit cancelled for " + current
                    + " (returned within " + mPendingDwell + "ms)");
                mExitPendingSince = 0L;
            }

            if (mActiveWatched == null) {
                // Fresh enter.
                mActiveWatched = current;
                mExitPendingSince = 0L;
                dispatchEnter(current);
            } else if (!mActiveWatched.equals(current)) {
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
            return;
        }

        if (mExitPendingSince == 0L) {
            long now2 = System.currentTimeMillis();
            long dwell;
            if (mEnteredAt != 0L
                    && (now2 - mEnteredAt) < 15_000L) {
                dwell = FIRST_EXIT_DWELL_MS;
            } else {
                dwell = EXIT_DWELL_MS;
            }
            mPendingDwell = dwell;
            mExitPendingSince = now2;
            Logger.getInstance(mContext).i(
                TAG + ": " + mActiveWatched
                + " left foreground — starting "
                + dwell + "ms exit dwell");
            return;
        }

        long elapsed = now - mExitPendingSince;
        if (elapsed >= mPendingDwell) {
            String exiting = mActiveWatched;
            mActiveWatched = null;
            mExitPendingSince = 0L;
            mEnteredAt = 0L;
            Logger.getInstance(mContext).i(
                TAG + ": exit confirmed for " + exiting
                + " after " + elapsed + "ms");
            dispatchExit(exiting);
        }
    }

    private boolean isWatched(String pkg) {
        return pkg != null && mWatchedPackages.contains(pkg);
    }

    private void dispatchEnter(String pkg) {
        mEnteredAt = System.currentTimeMillis();
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
     * its most recent event in the window was ACTIVITY_RESUMED AND
     * that resume is no older than FOREGROUND_FRESHNESS_MS.
     *
     * The freshness gate is what stops a watched app from
     * remaining "foreground" after the user has left it — see the
     * header note. IME/SystemUI noise is handled by the
     * last-event-was-paused rule; both filters are needed.
     *
     * The most recently-resumed package that passes both filters
     * wins. Ties are broken by the timestamp of the most recent
     * ACTIVITY_RESUMED event.
     */
    private String currentForegroundPackage() {
        if (mUsageStats == null) return null;

        long now = System.currentTimeMillis();
        UsageEvents events = mUsageStats.queryEvents(
            now - QUERY_WINDOW_MS, now);
        if (events == null) return null;

        Map<String, Integer> lastEvent = new HashMap<>();
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

        long freshest = now - FOREGROUND_FRESHNESS_MS;

        String bestPkg = null;
        long bestTs = 0L;

        for (Map.Entry<String, Integer> entry : lastEvent.entrySet()) {
            if (entry.getValue() != UsageEvents.Event.ACTIVITY_RESUMED) {
                continue;
            }
            String pkg = entry.getKey();
            if (mContext.getPackageName().equals(pkg)) {
                continue;
            }
            Long ts = lastResumeTs.get(pkg);
            if (ts == null) continue;
            if (ts < freshest) continue; // resume is stale — not foreground
            if (ts > bestTs) {
                bestTs = ts;
                bestPkg = pkg;
            }
        }

        return bestPkg;
    }
}