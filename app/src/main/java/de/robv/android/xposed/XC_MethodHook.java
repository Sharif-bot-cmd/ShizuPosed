package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    /** Public bridge — used by HookEngine's backend adapter. */
    public final void callBeforeHookedMethod(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    /** Public bridge — used by HookEngine's backend adapter. */
    public final void callAfterHookedMethod(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    // ═════════════════════════════════════════════════════════════
    // UNHOOK HANDLE (API 94+)
    // ═════════════════════════════════════════════════════════════

    public static class Unhook implements IXUnhook<XC_MethodHook> {
        private final Member hookedMember;
        private final XC_MethodHook callback;

        public Unhook(Member hookedMember) {
            this(hookedMember, null);
        }

        public Unhook(Member hookedMember, XC_MethodHook callback) {
            this.hookedMember = hookedMember;
            this.callback = callback;
        }

        @Override
        public Member getHookedMethod() {
            return hookedMember;
        }

        @Override
        public XC_MethodHook getCallback() {
            return callback;
        }

        @Override
        public void unhook() {
            if (hookedMember == null) return;
            try {
                com.shizuposed.manager.core.HookEngine.InstallRecord r =
                    com.shizuposed.manager.core.HookEngine
                        .getInstallRecord(hookedMember);
                if (r != null && r.reverse != null) {
                    r.reverse.run();
                } else {
                    android.util.Log.w("Xposed",
                        "Unhook: backend "
                        + (r != null ? r.backendName : "unknown")
                        + " cannot reverse; hook remains installed");
                }
            } catch (Throwable t) {
                android.util.Log.w("Xposed", "Unhook failed: " + t);
            }
        }

        @Override
        public String toString() {
            if (hookedMember == null) return "Unhook{null}";
            return "Unhook{" + hookedMember.getDeclaringClass().getName()
                + "." + hookedMember.getName() + "}";
        }
    }

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;

        /**
         * The Method or Constructor being hooked.
         *
         * Set by the framework's dispatch path before
         * beforeHookedMethod is called. Mirrors upstream Xposed's
         * MethodHookParam.method. Null if the dispatcher didn't
         * populate it — a hook that reads it must null-check.
         *
         * Consumers:
         *   • LibXposedHookerAdapter.ChainImpl.getExecutable()
         *     returns this for libxposed hooks.
         *   • Modules that want to identify the hooked member
         *     without capturing it at hook time.
         */
        public java.lang.reflect.Member method;

        private Object result;
        private Throwable throwable;
        public boolean hasResult;
        public boolean hasThrowable;

        public Object getResult() { return result; }

        public void setResult(Object r) {
            result = r; hasResult = true;
            throwable = null; hasThrowable = false;
        }

        public Throwable getThrowable() { return throwable; }

        public void setThrowable(Throwable t) {
            throwable = t; hasThrowable = true;
            result = null; hasResult = false;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        public void returnEarly(Object r) { setResult(r); }
        public void returnEarly() { setResult(null); }

        /**
         * API 96: "was the result explicitly set, even to null?"
         */
        public boolean isReturnEarly() {
            return hasResult;
        }
    }
}