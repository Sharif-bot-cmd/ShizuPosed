package com.shizuposed.manager.stealth.checks;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import com.shizuposed.manager.stealth.XStealthConfig;
import com.shizuposed.manager.stealth.XStealthRegistry;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.WeakHashMap;      
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SocketCheck
 *
 * Intercepts client-side LocalSocket connections to a small set of
 * daemons whose wire format we actually know, and serves canned
 * responses for known detection queries.
 *
 * ─────────────────────────────────────────────────────────────
 * SCOPE — read this before enabling
 * ─────────────────────────────────────────────────────────────
 *
 * This check ONLY covers apps that talk to a daemon directly over
 * a unix domain LocalSocket. It does NOT cover:
 *
 *   • Binder-based SettingsProvider access
 *     → already covered by AdbCheck / DevOptionsCheck's
 *       ContentResolver.query hook
 *   • Runtime.exec subprocess reads of the settings XML
 *     → covered by SettingsFileCheck
 *   • Direct reads of /data/system/users/0/settings_global.xml
 *     → SELinux denies this to app UIDs; no app can do it
 *   • TCP sockets
 *     → not covered; a separate, narrower check would be needed
 *   • Native connect() from JNI
 *     → not covered by this Java-layer check; a libc interposer
 *       would be needed (see XStealthNative)
 *
 * ─────────────────────────────────────────────────────────────
 * ACTIVATION
 * ─────────────────────────────────────────────────────────────
 *
 * Two gates, both in XStealthConfig:
 *
 *   1. hideSocketDaemons — master toggle. Default false.
 *   2. socketDaemons — list of daemon names to watch. Default empty.
 *
 * install() returns early and installs NO HOOKS if either gate is
 * closed. This is deliberate: installing hooks that only pass
 * through is pure overhead and pure risk. LocalSocket is used by
 * crash reporters, analytics SDKs, media pipelines, and custom
 * app IPC. Touching it without a reason is a fast way to break
 * apps.
 *
 * Even when daemons are configured, each one needs a Responder.
 * Until a Responder exists for a daemon, its traffic passes
 * through byte-for-byte via NoopResponder.
 *
 * ─────────────────────────────────────────────────────────────
 * RISK
 * ─────────────────────────────────────────────────────────────
 *
 * A bug in this check can crash the target app on launch, silently
 * drop telemetry, or break media playback. Test against a real
 * target before shipping. The passthrough path must be
 * byte-for-byte perfect.
 *
 * The stream wrappers below only intervene when a Responder
 * produces synthetic bytes. Everything else goes through
 * unchanged.
 */
public final class SocketCheck {

    private static final String TAG = "XStealth";

    /**
     * Daemon socket names we know how to lie to, mapped to their
     * Responder.
     *
     * Populated from XStealthConfig.socketDaemons at install time.
     * Never populated speculatively.
     *
     * A daemon with a NoopResponder installs hooks but passes all
     * traffic through — useful for observing a daemon's protocol
     * without changing behavior. Replace the NoopResponder with a
     * real one once the wire format is known.
     */
    private static final Map<String, Responder> WATCHED_DAEMONS =
        new ConcurrentHashMap<>();

    /**
     * Tracks which LocalSocket instances we've decided to shadow, so
     * getInputStream()/getOutputStream() know whether to wrap.
     *
     * WeakHashMap so we don't leak socket objects. Access must be
     * synchronized — WeakHashMap isn't thread-safe and sockets are
     * often created on background threads.
     *
     * Note: LocalSocket overrides equals()/hashCode() in some
     * Android versions based on the underlying fd. If you see
     * cross-talk between sockets, switch to a
     * ConcurrentHashMap<Integer, Responder> keyed on
     * System.identityHashCode(socket).
     */
    private static final Map<LocalSocket, Responder> SHADOWED_SOCKETS =
        new WeakHashMap<>();

    private SocketCheck() {}

    // ═════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════

    /**
     * Install SocketCheck for the given target process.
     *
     * Does nothing unless config.hideSocketDaemons is true AND
     * config.socketDaemons is non-empty.
     */
    public static void install(XC_LoadPackage.LoadPackageParam lpparam,
                               XStealthConfig config) {
        if (lpparam == null || lpparam.classLoader == null) return;

        if (config == null || !config.hideSocketDaemons) {
            XposedBridge.log(TAG + ": SocketCheck not installed "
                + "(hideSocketDaemons off)");
            return;
        }

        if (config.socketDaemons == null || config.socketDaemons.isEmpty()) {
            XposedBridge.log(TAG + ": SocketCheck not installed "
                + "(no daemons configured)");
            return;
        }

        // Register each configured daemon. If a real Responder has
        // been registered elsewhere (e.g. by a future subclass), keep
        // it. Otherwise install a NoopResponder, which installs hooks
        // but passes all traffic through.
        List<String> newlyWatched = new ArrayList<>();
        for (String daemon : config.socketDaemons) {
            if (daemon == null || daemon.isEmpty()) continue;
            Responder existing = WATCHED_DAEMONS.get(daemon);
            if (existing == null) {
                WATCHED_DAEMONS.put(daemon, new NoopResponder());
                newlyWatched.add(daemon);
            }
        }

        if (WATCHED_DAEMONS.isEmpty()) {
            XposedBridge.log(TAG + ": SocketCheck not installed "
                + "(no valid daemon names)");
            return;
        }

        hookLocalSocketConnect();
        hookLocalSocketStreams();

        XposedBridge.log(TAG + ": SocketCheck installed, watching "
            + WATCHED_DAEMONS.keySet()
            + (newlyWatched.isEmpty() ? "" : " (new: " + newlyWatched + ")"));
        XStealthRegistry.record("SocketCheck");
    }

    // ═════════════════════════════════════════════════════════════════
    // CONNECT HOOK
    // ═════════════════════════════════════════════════════════════════

    /**
     * Hook LocalSocket.connect(LocalSocketAddress, int).
     *
     * We do NOT fake the connection. We let the real connect proceed
     * and record the socket → responder mapping so the stream hooks
     * know which sockets to wrap.
     *
     * Rationale: faking a LocalSocket is fragile. The app expects a
     * live fd for select()/poll(), may call getPeerCredentials(),
     * may set file descriptors, may pass the fd to native code.
     * A shadow socket that isn't really connected breaks all of that.
     *
     * If connect() fails (daemon not running), we do nothing — there
     * is no socket to shadow, and the app will see the real error.
     */
    private static void hookLocalSocketConnect() {
        try {
            XposedHelpers.findAndHookMethod(
                LocalSocket.class, "connect",
                LocalSocketAddress.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            // Only proceed if connect() actually succeeded.
                            if (p.getThrowable() != null) return;

                            LocalSocketAddress addr =
                                (LocalSocketAddress) p.args[0];
                            if (addr == null) return;

                            String name = addr.getName();
                            if (name == null) return;

                            Responder responder = WATCHED_DAEMONS.get(name);
                            if (responder == null) return;

                            LocalSocket socket = (LocalSocket) p.thisObject;
                            if (socket == null) return;

                            synchronized (SHADOWED_SOCKETS) {
                                SHADOWED_SOCKETS.put(socket, responder);
                            }

                            XposedBridge.log(TAG + ": SocketCheck shadowing "
                                + "socket to daemon '" + name + "'");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": connect after-hook error (ignored): " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LocalSocket.connect hook failed: " + t);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // STREAM HOOKS
    // ═════════════════════════════════════════════════════════════════

    /**
     * Hook getInputStream() and getOutputStream() so that, for
     * shadowed sockets, we return wrapped streams that can
     * intercept and rewrite bytes.
     *
     * For non-shadowed sockets, we return the original stream
     * untouched — the hook is a no-op.
     */
    private static void hookLocalSocketStreams() {
        try {
            XposedHelpers.findAndHookMethod(
                LocalSocket.class, "getInputStream",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.getThrowable() != null) return;
                            Object result = p.getResult();
                            if (!(result instanceof InputStream)) return;

                            LocalSocket socket = (LocalSocket) p.thisObject;
                            Responder responder = lookupResponder(socket);
                            if (responder == null) return;

                            // Don't double-wrap. LocalSocket caches the
                            // stream internally on some versions and
                            // returns a fresh one on others.
                            if (result instanceof ShadowInputStream) return;

                            p.setResult(new ShadowInputStream(
                                (InputStream) result, responder, socket));
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": getInputStream after-hook error "
                                + "(ignored): " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG
                + ": LocalSocket.getInputStream hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                LocalSocket.class, "getOutputStream",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.getThrowable() != null) return;
                            Object result = p.getResult();
                            if (!(result instanceof OutputStream)) return;

                            LocalSocket socket = (LocalSocket) p.thisObject;
                            Responder responder = lookupResponder(socket);
                            if (responder == null) return;

                            if (result instanceof ShadowOutputStream) return;

                            p.setResult(new ShadowOutputStream(
                                (OutputStream) result, responder, socket));
                        } catch (Throwable t) {
                            XposedBridge.log(TAG
                                + ": getOutputStream after-hook error "
                                + "(ignored): " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG
                + ": LocalSocket.getOutputStream hook failed: " + t);
        }
    }

    private static Responder lookupResponder(LocalSocket socket) {
        if (socket == null) return null;
        synchronized (SHADOWED_SOCKETS) {
            return SHADOWED_SOCKETS.get(socket);
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // SHADOW STREAMS
    // ═════════════════════════════════════════════════════════════════

    /**
     * OutputStream wrapper.
     *
     * Captures outbound bytes and hands them to the responder for
     * inspection. If the responder recognizes a query, it may
     * queue a synthetic reply on the paired ShadowInputStream.
     *
     * Bytes are ALWAYS forwarded to the real daemon unchanged.
     * We are not modifying what the daemon sees; we are only
     * observing what the app sends so we can fake the reply.
     */
    private static final class ShadowOutputStream extends FilterOutputStream {

        private final Responder responder;
        private final LocalSocket socket;

        ShadowOutputStream(OutputStream out, Responder responder,
                           LocalSocket socket) {
            super(out);
            this.responder = responder;
            this.socket = socket;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            safeOnOutboundByte(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
            safeOnOutboundBytes(b, 0, b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            safeOnOutboundBytes(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            safeOnFlush();
            out.flush();
        }

        @Override
        public void close() throws IOException {
            safeOnClose();
            out.close();
        }

        // Responder methods must never break the stream. If a
        // Responder throws, we log and continue — the app's traffic
        // is more important than our interception.

        private void safeOnOutboundByte(int b) {
            try { responder.onOutboundByte(socket, (byte) b); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.onOutboundByte threw: " + t);
            }
        }

        private void safeOnOutboundBytes(byte[] b, int off, int len) {
            try { responder.onOutboundBytes(socket, b, off, len); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.onOutboundBytes threw: " + t);
            }
        }

        private void safeOnFlush() {
            try { responder.onFlush(socket); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.onFlush threw: " + t);
            }
        }

        private void safeOnClose() {
            try { responder.onClose(socket); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.onClose threw: " + t);
            }
        }
    }

    /**
     * InputStream wrapper.
     *
     * Before delegating to the real stream, asks the responder
     * whether a synthetic reply is queued for this socket. If so,
     * serves from the queue. When the queue empties, reads pass
     * through to the real daemon.
     *
     * read(byte[], int, int) is the hot path. read() is implemented
     * in terms of it.
     */
    private static final class ShadowInputStream extends FilterInputStream {

        private final Responder responder;
        private final LocalSocket socket;

        ShadowInputStream(InputStream in, Responder responder,
                          LocalSocket socket) {
            super(in);
            this.responder = responder;
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            if (n <= 0) return -1;
            return one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            // Ask the responder for synthetic bytes first.
            ByteBuffer synthetic = safePollReply(len);
            if (synthetic != null && synthetic.hasRemaining()) {
                int n = Math.min(len, synthetic.remaining());
                synthetic.get(b, off, n);
                return n;
            }

            // No synthetic reply queued — read from the real daemon.
            return in.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            ByteBuffer synthetic = safePeekReply();
            int pending = (synthetic != null) ? synthetic.remaining() : 0;
            return pending + in.available();
        }

        @Override
        public void close() throws IOException {
            safeOnClose();
            in.close();
        }

        private ByteBuffer safePollReply(int maxLen) {
            try { return responder.pollReply(socket, maxLen); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.pollReply threw: " + t);
                return null;
            }
        }

        private ByteBuffer safePeekReply() {
            try { return responder.peekReply(socket); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.peekReply threw: " + t);
                return null;
            }
        }

        private void safeOnClose() {
            try { responder.onClose(socket); }
            catch (Throwable t) {
                XposedBridge.log(TAG + ": Responder.onClose threw: " + t);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // RESPONDER INTERFACE
    // ═════════════════════════════════════════════════════════════════

    /**
     * Per-daemon protocol handler.
     *
     * Implementations are stateful — one instance per daemon name,
     * shared across all sockets to that daemon. They receive
     * outbound bytes as the app writes them and produce synthetic
     * inbound bytes the app will read.
     *
     * A Responder must be:
     *   • Thread-safe (sockets open on arbitrary threads)
     *   • Bounded (don't queue unbounded replies if the app never
     *     reads them)
     *   • Conservative (if unsure, do nothing — pass through)
     *
     * All methods have default no-op implementations, so a Responder
     * only needs to override what it actually does.
     */
    public interface Responder {

        /**
         * Called when the app writes a single byte to the daemon.
         * Called AFTER the byte has been forwarded. Do not block.
         */
        default void onOutboundByte(LocalSocket socket, byte b) {}

        /**
         * Called when the app writes a byte range to the daemon.
         * Called AFTER the bytes have been forwarded. Do not block.
         */
        default void onOutboundBytes(LocalSocket socket,
                                     byte[] b, int off, int len) {}

        /**
         * Called when the app flushes the outbound stream. This is
         * usually where a Responder decides that a complete query
         * has been written and queues a synthetic reply.
         */
        default void onFlush(LocalSocket socket) {}

        /**
         * Called when either stream is closed. Responder should
         * release any per-socket state.
         */
        default void onClose(LocalSocket socket) {}

        /**
         * Return a non-null ByteBuffer of synthetic bytes if a
         * reply is queued for this socket, consuming up to maxLen
         * bytes. Return null if no reply is queued.
         *
         * The returned buffer is drained by the caller. If the
         * reply is longer than maxLen, the remainder must be
         * retained for the next call.
         *
         * Called on the app's read thread.
         */
        default ByteBuffer pollReply(LocalSocket socket, int maxLen) {
            return null;
        }

        /**
         * Peek at the next queued reply without consuming it. Used
         * by available(). Return null if nothing is queued.
         */
        default ByteBuffer peekReply(LocalSocket socket) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // NOOP RESPONDER
    // ═════════════════════════════════════════════════════════════════

    /**
     * Pass-through Responder.
     *
     * Used when a daemon is configured but no protocol
     * implementation exists yet. The hooks install, but every byte
     * goes through unchanged — this class overrides nothing.
     *
     * This is useful in two ways:
     *
     *   1. Enabling SocketCheck for a daemon is a safe no-op until
     *      a real Responder is provided. No behavior change, so
     *      no risk of breaking the app.
     *   2. It's a hook point. Replace this class with a logging
     *      subclass temporarily to observe a daemon's traffic
     *      during reverse engineering, then replace it with a
     *      real Responder once the protocol is understood.
     *
     * Do NOT add speculative protocol logic here. If you don't
     * know the wire format, do nothing.
     */
    private static final class NoopResponder implements Responder {
        // All methods use the default no-op implementations.
        // Intentionally empty.
    }

    // ═════════════════════════════════════════════════════════════════
    // EXAMPLE RESPONDER — template only, not registered
    // ═════════════════════════════════════════════════════════════════

    /**
     * Example Responder showing the shape a real implementation
     * takes. Not registered in WATCHED_DAEMONS — this is a
     * template for when you reverse a real daemon.
     *
     * Assumes a newline-delimited text protocol:
     *   app → daemon:   "GET adb_enabled\n"
     *   daemon → app:   "0\n" or "1\n"
     *
     * If the daemon's protocol is different (length-prefixed,
     * binary, Binder-parcel), this template will not work as-is
     * and must be rewritten for that protocol.
     */
    @SuppressWarnings("unused")
    private static final class LineProtocolExampleResponder
            implements Responder {

        // Per-socket inbound buffer of outbound bytes, so we can
        // reassemble a line before deciding whether it's a query.
        private final Map<LocalSocket, StringBuilder> pending =
            new WeakHashMap<>();

        // Per-socket queued reply.
        private final Map<LocalSocket, ByteBuffer> replies =
            new WeakHashMap<>();

        @Override
        public void onOutboundBytes(LocalSocket socket,
                                    byte[] b, int off, int len) {
            StringBuilder sb;
            synchronized (pending) {
                sb = pending.get(socket);
                if (sb == null) {
                    sb = new StringBuilder();
                    pending.put(socket, sb);
                }
            }
            sb.append(new String(b, off, len, StandardCharsets.US_ASCII));
        }

        @Override
        public void onFlush(LocalSocket socket) {
            StringBuilder sb;
            synchronized (pending) {
                sb = pending.get(socket);
            }
            if (sb == null) return;

            // Handle one complete line per flush. Real protocols
            // may have multiple lines; expand this if needed.
            int nl = sb.indexOf("\n");
            if (nl < 0) return;

            String line = sb.substring(0, nl);
            sb.delete(0, nl + 1);

            String reply = replyFor(line);
            if (reply == null) return;

            synchronized (replies) {
                replies.put(socket,
                    ByteBuffer.wrap(reply.getBytes(StandardCharsets.US_ASCII)));
            }
        }

        private String replyFor(String line) {
            String trimmed = line.trim();
            // Example: hide adb_enabled if the app queries it.
            if (trimmed.equalsIgnoreCase("GET adb_enabled")) {
                return "0\n";
            }
            if (trimmed.equalsIgnoreCase("GET development_settings_enabled")) {
                return "0\n";
            }
            return null;
        }

        @Override
        public ByteBuffer pollReply(LocalSocket socket, int maxLen) {
            synchronized (replies) {
                ByteBuffer buf = replies.get(socket);
                if (buf == null || !buf.hasRemaining()) {
                    replies.remove(socket);
                    return null;
                }
                if (buf.remaining() <= maxLen) {
                    replies.remove(socket);
                    return buf;
                }
                // Return a slice, keep the rest for next call.
                int oldLimit = buf.limit();
                buf.limit(buf.position() + maxLen);
                ByteBuffer slice = buf.slice();
                buf.position(buf.limit());
                buf.limit(oldLimit);
                return slice;
            }
        }

        @Override
        public ByteBuffer peekReply(LocalSocket socket) {
            synchronized (replies) {
                return replies.get(socket);
            }
        }

        @Override
        public void onClose(LocalSocket socket) {
            synchronized (pending) { pending.remove(socket); }
            synchronized (replies) { replies.remove(socket); }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═════════════════════════════════════════════════════════════════

    /** For diagnostics: is a given daemon being watched? */
    public static boolean isWatching(String daemonName) {
        return daemonName != null && WATCHED_DAEMONS.containsKey(daemonName);
    }

    /** For diagnostics: list watched daemons. */
    public static Set<String> watchedDaemons() {
        return WATCHED_DAEMONS.keySet();
    }

    /** For diagnostics: how many sockets are currently shadowed? */
    public static int shadowedSocketCount() {
        synchronized (SHADOWED_SOCKETS) {
            return SHADOWED_SOCKETS.size();
        }
    }
}