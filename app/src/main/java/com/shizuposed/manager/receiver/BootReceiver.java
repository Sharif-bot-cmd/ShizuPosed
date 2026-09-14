package com.shizuposed.manager.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.shizuposed.manager.service.ShizuPosedService;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final long RETRY_DELAY_MS = 15_000L;
    private static final int MAX_ATTEMPTS = 5;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(
            "shizuposed_settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_start", false)) {
            Log.i(TAG, "auto_start disabled — nothing to do");
            return;
        }

        Log.i(TAG, "BOOT_COMPLETED received, scheduling deferred service start");
        final Context appCtx = context.getApplicationContext();
        scheduleDeferredStart(appCtx, 0);
    }

    private static void scheduleDeferredStart(final Context ctx, final int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            Log.w(TAG, "Giving up after " + attempt + " attempts");
            return;
        }

        // Use a background Handler. The receiver returns immediately, so
        // Android won't hold a wakelock, but the delay is short enough that
        // the process will usually survive long enough to try.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = ctx.getSharedPreferences(
                "shizuposed_settings", Context.MODE_PRIVATE);
            if (!prefs.getBoolean("auto_start", false)) {
                Log.i(TAG, "auto_start disabled at attempt " + attempt + " — aborting");
                return;
            }

            try {
                Intent svc = new Intent(ctx, ShizuPosedService.class);
                ctx.startForegroundService(svc);
                Log.i(TAG, "Deferred start attempt " + attempt + " succeeded");
            } catch (Throwable t) {
                Log.w(TAG, "Deferred start attempt " + attempt
                    + " refused: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
                // Try again after the delay. In practice, once the user
                // opens the app the foreground start will succeed; this
                // loop just gives the OS a few chances after boot.
                scheduleDeferredStart(ctx, attempt + 1);
            }
        }, attempt == 0 ? RETRY_DELAY_MS : RETRY_DELAY_MS);
    }
}