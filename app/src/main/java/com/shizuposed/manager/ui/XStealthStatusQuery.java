package com.shizuposed.manager.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.shizuposed.manager.stealth.XStealthModule;

/**
 * XStealthStatusQuery
 *
 * Asks the ModuleStatusProvider whether XStealth has loaded into at
 * least one target. Same mechanism every third-party module uses for
 * its own UI.
 */
final class XStealthStatusQuery {

    private XStealthStatusQuery() {}

    static boolean isActiveInAnyTarget(Context c) {
        if (c == null) return false;
        try {
            ContentResolver cr = c.getContentResolver();
            Uri uri = Uri.parse("content://com.shizuposed.manager.status"
                + "/active/" + XStealthModule.PACKAGE);
            try (Cursor cur = cr.query(uri, null, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int idx = cur.getColumnIndex("active");
                    if (idx != -1) return cur.getInt(idx) == 1;
                    int v = cur.getColumnIndex("value");
                    if (v != -1) return "1".equals(cur.getString(v));
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}