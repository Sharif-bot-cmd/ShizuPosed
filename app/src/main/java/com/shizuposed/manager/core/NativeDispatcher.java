package com.shizuposed.manager.core;

import com.shizuposed.manager.core.compat.CompatLog;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Dynamic argument marshaling for the native dispatch path.
 *
 * The C dispatcher (szp_dispatch_c) forwards the raw argument
 * registers to this class. We decode them into a real Object[]
 * using Method.getParameterTypes(), build a MethodHookParam, run
 * the callback, and tell the C side whether the callback supplied
 * a replacement return value.
 *
 * This is signature-agnostic: any method, any parameter list, any
 * return type. No per-shorty codegen. The cost is reflection on
 * every hook call.
 *
 * Registers vs. stack:
 *   arm64 passes the first 8 arguments in x0..x7 and the rest on
 *   the stack. This router only reads the register slice. Anything
 *   beyond the first 8 arguments (or 7, for instance methods, since
 *   x0 holds `this`) decodes to null. In practice, hooks rarely
 *   target methods with more than 7 primitive arguments.
 *
 * thisObject and object arguments:
 *   A raw register value that holds an object reference is a JVM-
 *   internal pointer, not a jobject handle. Reconstructing a valid
 *   Java reference from it requires per-ABI native work that this
 *   shim does not do. Object arguments and `thisObject` are null
 *   in the callback. Primitive arguments work.
 */
public final class NativeDispatcher {

    private static final String TAG = "NativeDispatcher";

    private NativeDispatcher() {}

    /**
     * Called from libshizuposed.so. Must stay public static with this
     * exact signature — the C side resolves it by name and descriptor
     * via GetStaticMethodID.
     *
     * @param method    the reflect Method (global ref, valid)
     * @param callback  the XC_MethodHook (global ref, valid)
     * @param regs      the raw argument register values (x0..x7)
     */
    public static void dispatch(Method method, XC_MethodHook callback, long[] regs) {
        if (method == null || callback == null || regs == null) {
            NativeBridge.setDispatchNoResult();
            return;
        }

        try {
            Class<?>[] paramTypes = method.getParameterTypes();
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            int regCount = regs.length;

            // Instance methods: thisObject occupies x0, args start at x1.
            // Static methods: args start at x0.
            int argStart = isStatic ? 0 : 1;

            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; ++i) {
                int slot = argStart + i;
                if (slot >= regCount) {
                    // Argument lives on the stack; we do not read stack
                    // args in this version. Leave it null.
                    args[i] = null;
                    continue;
                }
                args[i] = decodeRegister(paramTypes[i], regs[slot]);
            }

            XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
            // Note: the Xposed API's MethodHookParam has no `method`
            // field. Modules get the Method from the closure they
            // registered with findAndHookMethod, not from the param.
            p.thisObject = null;   // see class doc: raw refs unsupported
            p.args = args;

            try {
                callback.callBeforeHookedMethod(p);
            } catch (Throwable t) {
                CompatLog.w(TAG, "before-hook threw for "
                        + method.getName(), t);
            }

            if (p.hasThrowable) {
                // The C dispatcher has no throw channel back into ART.
                // Log the cause and fall through to the original so
                // the app does not see an injected exception it cannot
                // handle.
                CompatLog.w(TAG, "callback set throwable for "
                        + method.getName() + " — falling through",
                        p.getThrowable());
                NativeBridge.setDispatchNoResult();
                return;
            }

            if (p.hasResult) {
                // setDispatchPrimitiveResult encodes the value per the
                // declared return type and pushes it to the C side.
                // Returns false for void / object returns, in which
                // case the C side falls through to the original.
                NativeBridge.setDispatchPrimitiveResult(
                        p.getResult(), method.getReturnType());
                return;
            }

            NativeBridge.setDispatchNoResult();

        } catch (Throwable t) {
            CompatLog.w(TAG, "dispatch failed for "
                    + (method == null ? "?" : method.getName()), t);
            NativeBridge.setDispatchNoResult();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ARGUMENT DECODING
    // ═════════════════════════════════════════════════════════════

    /**
     * Decode a raw register value into the Java type declared by the
     * target method's parameter list.
     *
     * Primitive types are read directly from the register bits.
     * Object types (String, Bundle, custom classes) cannot be
     * reconstructed from a raw register value in Java, so they
     * return null. See class doc for why.
     */
    private static Object decodeRegister(Class<?> type, long raw) {
        if (type == null) return null;

        // Primitives and their boxed forms.
        if (type == boolean.class || type == Boolean.class) {
            return raw != 0L;
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) raw;
        }
        if (type == char.class || type == Character.class) {
            return (char) raw;
        }
        if (type == short.class || type == Short.class) {
            return (short) raw;
        }
        if (type == int.class || type == Integer.class) {
            return (int) raw;
        }
        if (type == long.class || type == Long.class) {
            return raw;
        }
        if (type == float.class || type == Float.class) {
            return Float.intBitsToFloat((int) raw);
        }
        if (type == double.class || type == Double.class) {
            return Double.longBitsToDouble(raw);
        }

        // Object types: cannot reconstruct from a raw register value.
        // A valid jobject handle is not the same as the JVM's internal
        // reference representation. Returning null is honest; the
        // callback fires but sees null for the arg.
        return null;
    }
}