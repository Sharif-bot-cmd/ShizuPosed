package com.shizuposed.manager.core.libxposed;

import androidx.annotation.NonNull;

import com.shizuposed.manager.model.ModuleInfo;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * LibXposedHookerAdapter
 *
 * Turns a libxposed Hooker.intercept(Chain) call into the classic
 * beforeHookedMethod / afterHookedMethod shape, so the same backend
 * pipeline can dispatch it.
 *
 * CHAIN SEMANTICS
 * ---------------
 * libxposed's Chain.proceed() is documented to invoke the original
 * method and return its result, synchronously, inside the hooker.
 * This is different from the classic API, which splits the
 * invocation into "before" and "after" callbacks with the framework
 * invoking the original in between.
 *
 * To give the hooker the semantics it expects, this adapter invokes
 * the original through XposedBridge.invokeOriginalMethod() inside
 * beforeHookedMethod() and sets the result on the MethodHookParam so
 * the framework does not invoke it again. That means:
 *
 *   Object r = chain.proceed();
 *   return r.toString();          // ← works, r is the real result
 *
 * would not work with the "flag-and-defer" approach, because
 * proceed() would return null. It works here.
 *
 * EXCEPTION MODES
 * ---------------
 *   DEFAULT     — exceptions from the hooker propagate, same as
 *                 the original exception would.
 *   PROTECTIVE  — exceptions are swallowed; the original runs as if
 *                 the hook had not fired.
 *   PASSTHROUGH — exceptions propagate unchanged.
 *
 * PROTECTIVE and PASSTHROUGH differ only in whether the original
 * runs after a swallowed exception. PASSTHROUGH is equivalent to
 * DEFAULT for our purposes — both rethrow.
 */
final class LibXposedHookerAdapter extends XC_MethodHook {

    private final ModuleInfo module;
    private final Executable executable;
    private final io.github.libxposed.api.XposedInterface.Hooker hooker;
    private final io.github.libxposed.api.XposedInterface.ExceptionMode mode;

    LibXposedHookerAdapter(
            ModuleInfo module,
            Executable executable,
            io.github.libxposed.api.XposedInterface.Hooker hooker,
            io.github.libxposed.api.XposedInterface.ExceptionMode mode,
            int priority) {
        this.module = module;
        this.executable = executable;
        this.hooker = hooker;
        this.mode = mode;
        // priority is accepted for interface compatibility but not
        // used — the current HookDispatcher runs callbacks in
        // install order. If priority support is added later, plumb
        // it here.
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        ChainImpl chain = new ChainImpl(param, executable);
        Object result;
        try {
            result = hooker.intercept(chain);
        } catch (Throwable t) {
            switch (mode) {
                case PROTECTIVE:
                    // Swallow, let the original run.
                    return;
                case PASSTHROUGH:
                case DEFAULT:
                default:
                    throw t;
            }
        }

        if (chain.proceeded) {
            // The hooker already invoked the original through
            // chain.proceed(...). The result was captured and set
            // on param; nothing else to do.
            return;
        }

        // Returning a value without calling proceed() means
        // "replace the return value".
        param.setResult(result);
    }

    // ═════════════════════════════════════════════════════════════
    // CHAIN IMPLEMENTATION
    // ═════════════════════════════════════════════════════════════

    private static final class ChainImpl
            implements io.github.libxposed.api.XposedInterface.Chain {

        private final MethodHookParam param;
        private final Executable executable;
        private boolean proceeded;

        ChainImpl(MethodHookParam param, Executable executable) {
            this.param = param;
            this.executable = executable;
        }

        @NonNull
        @Override
        public Executable getExecutable() {
            return executable;
        }

        @Override
        public Object getThisObject() {
            return param.thisObject;
        }

        @NonNull
        @Override
        public List<Object> getArgs() {
            if (param.args == null) return Collections.emptyList();
            return Arrays.asList(param.args);
        }

        @Override
        public Object getArg(int i) {
            if (param.args == null || i < 0 || i >= param.args.length) {
                throw new IndexOutOfBoundsException("arg " + i
                    + " (args length="
                    + (param.args == null ? 0 : param.args.length) + ")");
            }
            return param.args[i];
        }

        @Override
        public Object proceed() throws Throwable {
            proceeded = true;
            Object r = XposedBridge.invokeOriginalMethod(
                executable, param.thisObject, param.args);
            param.setResult(r);
            return r;
        }

        @Override
        public Object proceed(@NonNull Object[] args) throws Throwable {
            param.args = args;
            return proceed();
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject) throws Throwable {
            param.thisObject = thisObject;
            return proceed();
        }

        @Override
        public Object proceedWith(@NonNull Object thisObject,
                                  @NonNull Object[] args) throws Throwable {
            param.thisObject = thisObject;
            param.args = args;
            return proceed();
        }
    }
}