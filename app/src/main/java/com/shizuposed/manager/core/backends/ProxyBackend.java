package com.shizuposed.manager.core.backends;

import com.shizuposed.manager.core.HookDispatcher;
import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Proxy-based backend.
 *
 * Only works for interface methods. When a caller wraps a target with
 * wrap(), the returned proxy routes method calls through the
 * registered callbacks. This backend cannot replace an instance that
 * callers already hold — it only produces a new proxy when asked.
 *
 * Multiple callbacks per method are supported and run in order.
 */
public final class ProxyBackend implements HookDispatcher.Backend {

    private static final String TAG = "ProxyBackend";

    // Value type is CopyOnWriteArrayList so addIfAbsent is visible.
    private final Map<Method, CopyOnWriteArrayList<XC_MethodHook>> hookedMethods =
            new ConcurrentHashMap<>();

    @Override
    public String name() { return "Proxy"; }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public boolean hook(Method original, XC_MethodHook callback) {
        if (original == null || callback == null) return false;
        if (!original.getDeclaringClass().isInterface()) return false;

        CopyOnWriteArrayList<XC_MethodHook> list =
                hookedMethods.computeIfAbsent(original,
                        k -> new CopyOnWriteArrayList<>());
        list.addIfAbsent(callback);

        CompatLog.d(TAG, "Proxy hook registered for "
                + original.getDeclaringClass().getName() + "." + original.getName());
        return true;
    }

    /**
     * Wrap a target so its interface methods route through this
     * backend's callbacks. Returns the original target unchanged if
     * it implements no interfaces.
     */
    public Object wrap(Object target) {
        if (target == null) return null;
        Class<?>[] ifaces = target.getClass().getInterfaces();
        if (ifaces.length == 0) return target;

        return Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                ifaces,
                new ProxyHandler(target));
    }

    private final class ProxyHandler implements InvocationHandler {
        private final Object target;

        ProxyHandler(Object target) { this.target = target; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            List<XC_MethodHook> cbs = hookedMethods.get(method);
            if (cbs == null || cbs.isEmpty()) {
                return invokeOriginal(method, args);
            }

            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            p.thisObject = target;
            p.args = args;

            for (XC_MethodHook cb : cbs) {
                try {
                    cb.callBeforeHookedMethod(p);
                } catch (Throwable t) {
                    CompatLog.w(TAG, "before hook threw for " + method.getName(), t);
                }
            }
            if (p.hasThrowable) throw p.getThrowable();
            if (p.hasResult)    return p.getResult();

            Object result = invokeOriginal(method, args);
            p.setResult(result);

            for (XC_MethodHook cb : cbs) {
                try {
                    cb.callAfterHookedMethod(p);
                } catch (Throwable t) {
                    CompatLog.w(TAG, "after hook threw for " + method.getName(), t);
                }
            }
            if (p.hasThrowable) throw p.getThrowable();
            return p.getResult();
        }

        private Object invokeOriginal(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                throw cause != null ? cause : ite;
            }
        }
    }
}