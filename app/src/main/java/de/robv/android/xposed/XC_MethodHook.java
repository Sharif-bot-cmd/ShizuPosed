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
    //
    // Returned as the element type of Set<Unhook> from
    // XposedBridge.hookAllMethods and hookAllConstructors. API 94
    // introduced returning these handles so modules can unhook an
    // entire set at once.
    //
    // Functionally equivalent to IXUnhook<XC_MethodHook>, but
    // declared as a nested class to match the LSPosed shape.
    // ═════════════════════════════════════════════════════════════

    public static class Unhook {
        private final Member hookedMember;

        public Unhook(Member hookedMember) {
            this.hookedMember = hookedMember;
        }

        public Member getHookedMethod() {
            return hookedMember;
        }

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
         *
         * Distinct from getResult() != null because setting the
         * result to null is a legitimate early return and modules
         * rely on the distinction.
         */
        public boolean isReturnEarly() {
            return hasResult;
        }
    }
}