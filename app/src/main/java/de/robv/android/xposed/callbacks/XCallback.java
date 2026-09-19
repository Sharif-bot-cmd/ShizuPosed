package de.robv.android.xposed.callbacks;

/**
 * Base class for all Xposed callback parameter types.
 *
 * Upstream Xposed declares this as the common parent of every
 * XC_*Param class. ShizuPosed ships it for link compatibility: a
 * module that references the type — as a method parameter, a
 * wildcard bound, or via XCallback.Priority — must find it at
 * class-load time, or the module fails with NoClassDefFoundError.
 *
 * IMPLEMENTATION DIFFERENCE, NOT API DIFFERENCE
 * ---------------------------------------------
 * Upstream XCallback also carries the callback-chain registry:
 * a per-type list of registered handlers, ordered by Priority, that
 * XposedBridge.hookAllMethods() and friends traverse. ShizuPosed
 * does not route hook dispatch through such a registry. Its
 * HookDispatcher drives hooks directly through the backend chain
 * (Pine -> Amiru -> Native -> ...), and multiple modules hooking the
 * same method are held in the backend's own per-method callback
 * list.
 *
 * The observable behavior matches: if two modules hook the same
 * method, both fire, in the order they were installed. The internal
 * mechanism differs. A module that only calls the public API cannot
 * tell the difference.
 *
 * WHY THIS IS A MARKER AND NOT A FULL CHAIN
 * -----------------------------------------
 * Shipping the chain here would create two sources of truth for
 * "which hooks fire" — the XCallback registry and the HookDispatcher
 * chain. Keeping XCallback as a marker avoids that, at the cost of
 * not supporting modules that reach into the registry directly.
 * Such modules are rare. The ones that exist are out of scope for
 * ShizuPosed and would need LSPosed anyway.
 *
 * DO NOT ADD ABSTRACT METHODS
 * ---------------------------
 * A module compiled against upstream Xposed does not implement any
 * method declared on XCallback or XCallback.Param, because upstream
 * declares none. Adding an abstract method here would break
 * verification for every such module. Add concrete helper methods
 * only if you are certain upstream also has them.
 */
public abstract class XCallback {

    /**
     * Callback priority. Lower values run first.
     *
     * Mirror of upstream. The numeric values are load-bearing:
     * modules pass `XCallback.Priority.HIGHEST`, `.NORMAL`, etc. as
     * constants, and a module that sorts by Priority.value would
     * misbehave if the numbers differed from upstream.
     *
     * Upstream also declares `public final int value` on the enum
     * constant. Do not remove it. Do not renumber.
     */
    public enum Priority {
        LOWEST(0),
        LOW(2500),
        NORMAL(5000),
        HIGH(7500),
        HIGHEST(10000);

        /** Numeric priority. Mirrors upstream. */
        public final int value;

        Priority(int value) {
            this.value = value;
        }
    }

    /**
     * Marker interface implemented by every callback parameter type.
     *
     * Upstream uses it to type parameters in the chain-registration
     * methods (XCallback.register / unregister). ShizuPosed ships
     * the interface so a module that types a parameter as
     * XCallback.Param compiles and verifies.
     *
     * Upstream declares no methods here. Do not add any.
     */
    public interface Param {
    }
}