package com.shizuposed.manager.core.libxposed;

import android.content.Context;

/**
 * LibXposedContext
 *
 * A process-wide, best-effort Context holder for the libxposed
 * runtime. Populated by XposedHook once an Application exists (i.e.
 * after bindApplication, or after currentApplication() in
 * post-application mode). Used by ShizuPosedXposedInterface to
 * resolve getModuleApplicationInfo() without needing a
 * framework-side Context reference.
 *
 * Deliberately independent of LSPosedManager's fallback context:
 * that one is keyed on the classic shim being present, and we want
 * libxposed to work even in a build where the classic shim has been
 * stripped out.
 */
public final class LibXposedContext {

    private static volatile Context sContext;

    private LibXposedContext() {}

    public static void set(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        sContext = (app != null) ? app : ctx;
    }

    public static Context peek() {
        return sContext;
    }

    public static void clear() {
        sContext = null;
    }
}