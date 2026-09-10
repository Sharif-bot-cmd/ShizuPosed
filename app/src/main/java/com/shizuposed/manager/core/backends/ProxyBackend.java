package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.utils.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Proxy-based backend.
 *
 * Only works for interface methods. When a proxy is installed, callers
 * that get the proxy will invoke our InvocationHandler instead of the
 * original. But: we cannot replace the existing proxy instance that
 * callers already hold — so this only works if the caller re-queries.
 *
 * Kept as a fallback for cases where a module hooks a factory method
 * that returns an interface implementation.
 */
public final class ProxyBackend implements HookDispatcher.Backend {

    // original method → callback
    private final Map<Method, XC_MethodHook> hookedMethods = new ConcurrentHashMap<>();

    @Override
    public String name() { return "Proxy"; }

    @Override
    public boolean isAvailable() {
        // Always available — it's pure Java
        return true;
    }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (!original.getDeclaringClass().isInterface()) {
            // Proxy backend can't hook non-interface methods
            return false;
        }
        hookedMethods.put(original, callback);
        log("Proxy hook registered for " + original.getDeclaringClass().getName()
            + "." + original.getName());
        return true;
    }

    /**
     * Called by callers that want to wrap an object that implements
     * a hooked interface.
     */
    public Object wrap(Object target) {
        if (target == null) return null;
        Class<?>[] ifaces = target.getClass().getInterfaces();
        if (ifaces.length == 0) return target;

        return Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            ifaces,
            new ProxyHandler(target)
        );
    }

    private class ProxyHandler implements InvocationHandler {
        private final Object target;
        ProxyHandler(Object target) { this.target = target; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            XC_MethodHook callback = hookedMethods.get(method);
            if (callback == null) return method.invoke(target, args);

            XC_MethodHook.MethodHookParam mp = new XC_MethodHook.MethodHookParam();
            mp.thisObject = target;
            mp.args = args;

            callback.callBeforeHookedMethod(mp);
            if (mp.hasThrowable) throw mp.getThrowable();
            if (mp.hasResult)   return mp.getResult();

            Object result = method.invoke(target, args);
            mp.setResult(result);

            callback.callAfterHookedMethod(mp);
            if (mp.hasThrowable) throw mp.getThrowable();
            return mp.getResult();
        }
    }

    private static void log(String msg) {
        try {
            Logger l = Logger.getInstance(null);
            if (l != null) { l.i("[ProxyBackend] " + msg); return; }
        } catch (Throwable ignored) {}
        System.out.println("[ProxyBackend] " + msg);
    }
}