package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;

import com.shizuposed.manager.service.ShizuPosedService;

/**
 * XStealthPushHelper
 *
 * Requests a module repush from ShizuPosedService. The service
 * already knows how to write the shell-side config; this just
 * triggers the work.
 *
 * Silent on failure. The push happens on the service's worker
 * thread, so a slow Shizuku round-trip doesn't block the UI.
 */
final class XStealthPushHelper {

    private XStealthPushHelper() {}

    static void requestPush(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, ShizuPosedService.class);
            i.setAction(ShizuPosedService.ACTION_REPUSH_MODULES);
            c.startForegroundService(i);
        } catch (Throwable ignored) {}
    }
}