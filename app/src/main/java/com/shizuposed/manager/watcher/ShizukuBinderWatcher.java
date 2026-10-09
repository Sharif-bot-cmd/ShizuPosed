package com.shizuposed.manager.watcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import rikka.shizuku.Shizuku;

/**
 * ShizukuBinderWatcher
 *
 * A no-op ContentProvider whose only purpose is to give the app
 * a component that Android creates eagerly and keeps alive for
 * the process lifetime. Inside onCreate it registers listeners
 * on Shizuku's binder, so the app is notified whenever Shizuku's
 * server comes up or goes down — regardless of why.
 *
 * The specific case this exists for:
 *
 *   Shizuku-Next's "ADB without Developer options" switch turns
 *   Developer Options and USB debugging off while a target app is
 *   in the foreground, and turns them back on when the app exits.
 *   During that window, Shizuku's server is off, ShizuPosedService
 *   loses its binder, and — without this watcher — nothing tells
 *   it to come back when the server returns.
 *
 * The watcher closes that gap: when Shizuku's binder is received
 * again, it sends ACTION_ENSURE_RUNNING to ShizuPosedService. The
 * service's handler for that action is idempotent and restarts the
 * worker path if needed.
 *
 * The provider is exported="false" and has no public URI surface.
 * Android only needs it to exist so it will be instantiated.
 */
public class ShizukuBinderWatcher extends ContentProvider {

    private static final String TAG = "ShizukuBinderWatcher";

    // Shizuku's listener API is available from Shizuku 12+.
    // These are stable across the upstream and the forks.
    private final Shizuku.OnBinderReceivedListener mBinderReceived =
        () -> {
            logInfo("Shizuku binder received");
            ensureServiceRunning(getContext());
        };

    private final Shizuku.OnBinderDeadListener mBinderDead =
        () -> logInfo("Shizuku binder died");

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;

        // Registering twice is harmless; Shizuku deduplicates.
        try {
            Shizuku.addBinderReceivedListenerSticky(mBinderReceived);
            Shizuku.addBinderDeadListener(mBinderDead);
            logInfo("listeners registered");
        } catch (Throwable t) {
            logError("listener registration failed: " + t);
        }

        // If the binder is already alive when we start (app was
        // launched, Shizuku had been authorized before), kick the
        // service now. The sticky listener above also fires in that
        // case, but starting here is idempotent and makes the
        // behavior deterministic.
        ensureServiceRunning(ctx);

        return true;
    }

    private void ensureServiceRunning(Context ctx) {
        if (ctx == null) return;
        try {
            // Only start if Shizuku is actually usable. Otherwise
            // we'd start the service only for it to immediately
            // report "not authorized" and stop.
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return;
            if (Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                logInfo("Shizuku alive but permission not granted — "
                    + "skip start");
                return;
            }

            Intent intent = new Intent(ctx, ShizuPosedService.class);
            intent.setAction(ShizuPosedService.ACTION_ENSURE_RUNNING);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }

            logInfo("requested service start");
        } catch (Throwable t) {
            logError("ensureServiceRunning failed: " + t);
        }
    }

    // ─── logging helpers ────────────────────────────────────────
    // Logger.e takes (String, String) in this tree, not
    // (String, Throwable). Wrapping the calls here keeps the
    // throwable's toString attached to the message without
    // needing an overload.

    private static void logInfo(String msg) {
        try {
            Logger.getInstance(null).i(TAG + ": " + msg);
        } catch (Throwable ignored) {}
    }

    private static void logError(String msg) {
        try {
            Logger.getInstance(null).e(TAG + ": " + msg);
        } catch (Throwable ignored) {}
    }

    // ─── ContentProvider boilerplate ────────────────────────────
    // This provider is not meant to be queried. Every method
    // either returns null or throws.

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) { return null; }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}