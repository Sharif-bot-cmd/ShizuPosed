package de.robv.android.xposed;

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
    }
}