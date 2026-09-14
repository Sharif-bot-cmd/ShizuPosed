package com.shizuposed.manager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

/**
 * Single source of truth for Shizuku / Shevery / Sui state.
 *
 * Design:
 *   - Listeners are registered synchronously in the constructor,
 *     before any state check. Nothing can be missed.
 *   - checkPermission() runs ONLY from onBinderReceived. Never
 *     eagerly. This eliminates the cold-start false "denied" that
 *     occurs when checkSelfPermission() is called before the binder
 *     is delivered by the provider.
 *   - requestPermission() is idempotent. forceRequestPermission()
 *     resets in-flight state so the menu action always works.
 *   - Shevery detection: Shevery ships a legacy stub under
 *     moe.shizuku.privileged.api so apps built against the original
 *     Shizuku API still bind. That stub does NOT always reflect
 *     grants made in Shevery's own UI, so when Shevery is the only
 *     provider present, we log a warning and prefer the "sticky
 *     listener + explicit recheck" path over the eager path.
 *
 * Anything that needs Shizuku state should call into this class.
 * Do not call Shizuku.* directly from elsewhere in the app.
 */
public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final int SHIZUKU_CODE = 0xCA07A;
    private static ShizukuHelper instance;

    private final Context context;
    private final Logger logger;

    // State updated only by Shizuku callbacks, read by everyone else.
    private volatile boolean isAvailable = false;
    private volatile boolean isAuthorized = false;
    private volatile int shizukuVersion = 0;
    private volatile boolean isSui = false;
    private volatile boolean binderStatus = false;

    // Shevery / Shizuku identification. These are metadata reads and
    // do not race the binder.
    private volatile boolean realShizukuInstalled = false;
    private volatile boolean sheveryInstalled = false;

    private static final String SHIZUKU_API_PACKAGE     = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager";
    private static final String SHEVERY_PACKAGE         = "com.hamondev.shevery";

    private final AtomicBoolean permissionRequestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean listenersRegistered = new AtomicBoolean(false);
    private final AtomicBoolean inAutoStart = new AtomicBoolean(false);
    private final AtomicBoolean grantToastShown = new AtomicBoolean(false);

    private static final String[] APP_PROCESS_CANDIDATES = {
        "app_process64", "app_process", "app_process32",
    };

    private volatile String cachedAppProcessBinary = null;

    public interface PermissionListener {
        void onPermissionGranted();
        void onPermissionDenied();
    }

    private final List<PermissionListener> permissionListeners = new CopyOnWriteArrayList<>();

    // ═════════════════════════════════════════════════════════════
    // SHIZUKU CALLBACKS
    // ═════════════════════════════════════════════════════════════

    private final Shizuku.OnBinderReceivedListener binderListener =
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            binderStatus = true;
            isAvailable = true;
            try {
                if (!Shizuku.isPreV11()) shizukuVersion = Shizuku.getVersion();
            } catch (Throwable ignored) {}
            logger.i("Shizuku binder received (v" + shizukuVersion
                    + ", provider=" + providerName() + ")");

            // The ONLY place checkPermission() is allowed to run.
            checkPermission();
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
        new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            binderStatus = false;
            isAvailable = false;
            isAuthorized = false;
            permissionRequestInFlight.set(false);
            grantToastShown.set(false);
            cachedAppProcessBinary = null;
            logger.w("Shizuku binder dead");
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != SHIZUKU_CODE) return;

            permissionRequestInFlight.set(false);

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                isAuthorized = true;
                grantToastShown.set(true);
                logger.i("✅ Shizuku permission GRANTED via provider=" + providerName());
                notifyPermissionGranted();
                autoStartServiceIfPossible();
            } else {
                isAuthorized = false;
                logger.w("❌ Shizuku permission DENIED via provider=" + providerName());
                notifyPermissionDenied();
            }
        }
    };

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    private ShizukuHelper(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(this.context);

        // Register listeners FIRST, synchronously, before anything
        // else. Nothing binder-related runs before this.
        registerListeners();

        initShizuku();
    }

    public static synchronized ShizukuHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ShizukuHelper(context);
        }
        return instance;
    }

    private void registerListeners() {
        if (listenersRegistered.get()) return;
        synchronized (this) {
            if (listenersRegistered.get()) return;
            try {
                Shizuku.addBinderReceivedListenerSticky(binderListener);
                Shizuku.addBinderDeadListener(binderDeadListener);
                Shizuku.addRequestPermissionResultListener(permissionResultListener);
                // Only after all adds succeed.
                listenersRegistered.set(true);
                logger.d("Shizuku listeners registered");
            } catch (Throwable t) {
                logger.e("Failed to register Shizuku listeners: " + t.getMessage());
                // leave flag false so we retry
            }
        }
    }

    private void initShizuku() {
        try {
            // ── Sui path ──
            try {
                isSui = Sui.init(context.getPackageName());
                if (isSui) {
                    logger.i("✅ Sui detected — using Sui binder");
                    isAvailable = true;
                    // Do NOT set isAuthorized here. Sui delivers its
                    // binder through the same listener; checkPermission
                    // will run from onBinderReceived.
                    return;
                }
            } catch (NoClassDefFoundError e) {
                logger.d("Sui not present");
                isSui = false;
            } catch (Throwable e) {
                logger.w("Sui init failed: " + e.getMessage());
                isSui = false;
            }

            // ── Package presence checks (metadata only) ──
            realShizukuInstalled = isPackageInstalled(SHIZUKU_MANAGER_PACKAGE)
                                && isPackageInstalled(SHIZUKU_API_PACKAGE);
            sheveryInstalled     = isPackageInstalled(SHEVERY_PACKAGE);

            if (!realShizukuInstalled && !sheveryInstalled) {
                logger.w("Shizuku/Shevery not installed");
                logger.w("Install Shizuku from https://shizuku.rikka.app/");
                logger.w("Or Shevery from " + SHEVERY_PACKAGE);
                isAvailable = false;
                return;
            }

            if (realShizukuInstalled) logger.i("✅ Real Shizuku detected");
            if (sheveryInstalled && !realShizukuInstalled) {
                logger.w("⚠️ Shevery detected (no real Shizuku). Shevery's "
                        + "legacy compat stub under " + SHIZUKU_API_PACKAGE
                        + " may not reflect grants made in Shevery's UI. If "
                        + "the grant fails to stick, use real Shizuku.");
            } else if (sheveryInstalled) {
                logger.w("⚠️ Both real Shizuku and Shevery are installed. "
                        + "This can cause binder conflicts. Uninstall one.");
            }
            logBinderProvenance();

            // Note: we do NOT call Shizuku.pingBinder() here. The sticky
            // listener already fired (or will fire) with the correct
            // state. Calling pingBinder() before the provider delivers
            // the binder returns false and would mark us unavailable.

        } catch (NoClassDefFoundError e) {
            logger.e("Shizuku API not found: " + e.getMessage());
            isAvailable = false;
        } catch (Throwable t) {
            logger.e("Shizuku init failed: " + t.getMessage());
            isAvailable = false;
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String providerName() {
        if (isSui) return "Sui";
        if (sheveryInstalled && !realShizukuInstalled) return "Shevery(stub)";
        if (sheveryInstalled && realShizukuInstalled) return "Shevery+Shizuku";
        return "Shizuku";
    }

    private void logBinderProvenance() {
        try {
            int uid = android.os.Process.myUid();
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            logger.d("Binder provenance: uid=" + uid
                    + " packages=" + (packages == null ? "[]" : java.util.Arrays.toString(packages))
                    + " provider=" + providerName());
        } catch (Throwable ignored) {}
    }

    // ═════════════════════════════════════════════════════════════
    // PERMISSION CHECK AND REQUEST
    // ═════════════════════════════════════════════════════════════

    /**
     * Only called from the binder listener or via refreshFromBinder().
     * Never called eagerly during construction.
     */
    private void checkPermission() {
        if (!isAvailable) {
            logger.d("checkPermission: binder not available, skipping");
            return;
        }

        try {
            int result = Shizuku.checkSelfPermission();
            boolean wasAuthorized = isAuthorized;
            isAuthorized = (result == PackageManager.PERMISSION_GRANTED);

            if (isAuthorized) {
                logger.i("✅ Shizuku permission GRANTED (provider=" + providerName() + ")");
                if (!wasAuthorized) {
                    notifyPermissionGranted();
                    autoStartServiceIfPossible();
                }
            } else {
                logger.w("⚠️ Shizuku permission NOT GRANTED (provider=" + providerName() + ")");
                // Do NOT auto-request. The Application and MainActivity
                // call requestPermission() explicitly when they want the
                // dialog. Auto-request from a listener produces
                // duplicate dialogs and races with the menu action.
            }
        } catch (Throwable e) {
            logger.e("checkPermission failed: " + e.getMessage());
            isAuthorized = false;
        }
    }

    /**
     * Public recheck. Call from onResume so returning from the Shizuku
     * app refreshes our state.
     */
    public void refreshFromBinder() {
        if (isSui) {
            checkPermission();
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                isAvailable = false;
                isAuthorized = false;
                return;
            }
            isAvailable = true;
            if (shizukuVersion == 0) {
                try {
                    if (!Shizuku.isPreV11()) shizukuVersion = Shizuku.getVersion();
                } catch (Throwable ignored) {}
            }
            checkPermission();
        } catch (Throwable t) {
            logger.e("refreshFromBinder failed: " + t.getMessage());
        }
    }

    public void requestPermission() {
        if (!isAvailable) {
            logger.w("Cannot request permission: Shizuku/Shevery not available");
            return;
        }
        if (isAuthorized) {
            logger.i("Shizuku already authorized");
            return;
        }
        if (!permissionRequestInFlight.compareAndSet(false, true)) {
            logger.d("Permission request already in flight — skipping");
            return;
        }

        try {
            logger.i("📢 Requesting Shizuku/Shevery permission...");
            Shizuku.requestPermission(SHIZUKU_CODE);

            if (grantToastShown.compareAndSet(false, true)) {
                final String target = providerName();
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context,
                        "Please grant permission in " + target,
                        Toast.LENGTH_LONG).show());
            }
        } catch (Throwable e) {
            permissionRequestInFlight.set(false);
            logger.e("requestPermission failed: " + e.getMessage());
        }
    }

    /**
     * Menu-driven "Grant Shizuku permission". Resets in-flight state
     * and issues a fresh request so the dialog always appears. Safe
     * to call repeatedly. Refreshes the binder state first in case the
     * user already granted while we weren't looking.
     */
    public void forceRequestPermission() {
        permissionRequestInFlight.set(false);
        grantToastShown.set(false);

        refreshFromBinder();

        if (isAuthorized) {
            logger.i("Already authorized — no request needed");
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Already authorized", Toast.LENGTH_SHORT).show());
            return;
        }
        if (!isAvailable) {
            logger.w("Cannot force-request: binder unavailable");
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Shizuku is not running", Toast.LENGTH_SHORT).show());
            return;
        }

        logger.i("Force-requesting permission from menu (provider=" + providerName() + ")");
        requestPermission();
    }

    // ═════════════════════════════════════════════════════════════
    // LISTENERS
    // ═════════════════════════════════════════════════════════════

    public void addPermissionListener(PermissionListener l) {
        if (l == null) return;
        if (!permissionListeners.contains(l)) permissionListeners.add(l);
        if (isAuthorized) {
            new Handler(Looper.getMainLooper()).post(l::onPermissionGranted);
        }
    }

    public void removePermissionListener(PermissionListener l) {
        permissionListeners.remove(l);
    }

    private void notifyPermissionGranted() {
        for (PermissionListener l : permissionListeners) {
            try { l.onPermissionGranted(); }
            catch (Throwable t) { logger.e("onPermissionGranted: " + t.getMessage()); }
        }
    }

    private void notifyPermissionDenied() {
        for (PermissionListener l : permissionListeners) {
            try { l.onPermissionDenied(); }
            catch (Throwable t) { logger.e("onPermissionDenied: " + t.getMessage()); }
        }
    }

    private void autoStartServiceIfPossible() {
        if (!inAutoStart.compareAndSet(false, true)) return;
        try {
            if (context instanceof ShizuPosedManagerApp) {
                ShizuPosedManagerApp app = (ShizuPosedManagerApp) context;
                new Handler(Looper.getMainLooper()).post(app::autoStartService);
            }
        } catch (Throwable t) {
            logger.e("autoStartServiceIfPossible: " + t.getMessage());
        } finally {
            inAutoStart.set(false);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // QUERIES
    // ═════════════════════════════════════════════════════════════

    public boolean isAvailable() {
        if (isSui) return isAvailable;
        return isAvailable && binderStatus;
    }

    public boolean isAuthorized() { return isAuthorized; }
    public int getVersion() { return shizukuVersion; }
    public boolean isSui() { return isSui; }

    public boolean isSheveryInstalledPublic() { return sheveryInstalled; }

    public boolean refreshSheveryPresence() {
        sheveryInstalled = isPackageInstalled(SHEVERY_PACKAGE);
        realShizukuInstalled = isPackageInstalled(SHIZUKU_MANAGER_PACKAGE)
                            && isPackageInstalled(SHIZUKU_API_PACKAGE);
        return sheveryInstalled;
    }

    /**
     * True if the only provider present is Shevery's legacy stub under
     * the original Shizuku package name. In this case, permission
     * grants may not stick and the caller should surface a warning.
     */
    public boolean isSheveryOnly() {
        return sheveryInstalled && !realShizukuInstalled && !isSui;
    }

    public ShizukuStatus checkShizukuActive() {
        if (isSui) return ShizukuStatus.ACTIVE;

        boolean apiInstalled = isPackageInstalled(SHIZUKU_API_PACKAGE);
        boolean managerInstalled = isPackageInstalled(SHIZUKU_MANAGER_PACKAGE);
        boolean sheveryPresent = isPackageInstalled(SHEVERY_PACKAGE);
        sheveryInstalled = sheveryPresent;
        realShizukuInstalled = managerInstalled && apiInstalled;

        if (!apiInstalled && !managerInstalled && !sheveryPresent) {
            return ShizukuStatus.NOT_INSTALLED;
        }
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                return ShizukuStatus.ACTIVE;
            }
        } catch (Throwable ignored) {}
        return ShizukuStatus.NOT_ACTIVE;
    }

    public String getStatusString() {
        if (isSui) return "✅ Sui Active";

        if (!isAvailable()) {
            if (sheveryInstalled && !realShizukuInstalled) return "❌ Shevery Not Active";
            return "❌ Shizuku Not Available";
        }
        if (!isAuthorized) {
            if (sheveryInstalled && !realShizukuInstalled) {
                return "⚠️ Shevery Available (Not Authorized — grant may not stick)";
            }
            return "⚠️ Shizuku Available (Not Authorized)";
        }
        if (sheveryInstalled && !realShizukuInstalled) {
            return "✅ Shevery Authorized (v" + shizukuVersion + ")";
        }
        return "✅ Shizuku Authorized (v" + shizukuVersion + ")";
    }

    // ═════════════════════════════════════════════════════════════
    // COMMAND EXECUTION
    // ═════════════════════════════════════════════════════════════

    public String getAppProcessBinary() {
        String cached = cachedAppProcessBinary;
        if (cached != null) return cached;

        if (!isAvailable() || !isAuthorized) {
            logger.d("Cannot probe app_process: not ready");
            return null;
        }

        for (String candidate : APP_PROCESS_CANDIDATES) {
            if (probeAppProcess(candidate)) {
                cachedAppProcessBinary = candidate;
                logger.i("app_process binary selected: " + candidate);
                return candidate;
            }
        }
        logger.e("No usable app_process binary on this ROM");
        return null;
    }

    public void invalidateAppProcessCache() { cachedAppProcessBinary = null; }

    private boolean probeAppProcess(String name) {
        try {
            String cmd = "command -v " + name + " >/dev/null 2>&1 && "
                       + name + " 2>&1 | head -1";
            ShellUtils.CommandResult r = executeCommand(cmd);
            if (r == null) return false;
            String out = r.getStdoutString();
            String err = r.getStderrString();
            boolean found = (out != null && !out.isEmpty())
                         || (err != null && err.contains(name));
            if (!found) return false;
            String probe = (out == null ? "" : out) + " " + (err == null ? "" : err);
            if (probe.contains("Permission denied")) return false;
            if (probe.contains("not found")) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public ShellUtils.CommandResult executeCommand(String command) {
        ShellUtils.CommandResult result = new ShellUtils.CommandResult();
        if (!isAvailable() || !isAuthorized) {
            result.stderr.add("Shizuku not available or not authorized");
            result.exitCode = -1;
            return result;
        }

        Process process = null;
        try {
            process = spawnShellViaAidl(new String[]{"sh", "-c", command}, null, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) result.stdout.add(line);
            while ((line = errorReader.readLine()) != null) result.stderr.add(line);
            result.exitCode = process.waitFor();
            reader.close();
            errorReader.close();
        } catch (Exception e) {
            logger.e("Command execution failed: " + e.getMessage());
            result.stderr.add(e.getMessage());
            result.exitCode = -1;
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
        }
        return result;
    }

    private Process spawnShellViaAidl(String[] cmd, String[] env, String dir) throws Exception {
        android.os.IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");
        moe.shizuku.server.IShizukuService service =
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder);
        moe.shizuku.server.IRemoteProcess remote = service.newProcess(cmd, env, dir);
        if (remote == null) throw new IllegalStateException("Shizuku returned null process");
        return new AidlProcessAdapter(remote);
    }

    private static final class AidlProcessAdapter extends Process {
        private final moe.shizuku.server.IRemoteProcess remote;
        private InputStream in; private InputStream err; private OutputStream out;
        private ParcelFileDescriptor inPfd, errPfd, outPfd;
        private Integer cachedExit;
        AidlProcessAdapter(moe.shizuku.server.IRemoteProcess remote) { this.remote = remote; }
        @Override public synchronized OutputStream getOutputStream() {
            if (out == null) try {
                outPfd = remote.getOutputStream();
                out = new ParcelFileDescriptor.AutoCloseOutputStream(outPfd);
            } catch (Exception e) { throw new RuntimeException(e); }
            return out;
        }
        @Override public synchronized InputStream getInputStream() {
            if (in == null) try {
                inPfd = remote.getInputStream();
                in = new ParcelFileDescriptor.AutoCloseInputStream(inPfd);
            } catch (Exception e) { throw new RuntimeException(e); }
            return in;
        }
        @Override public synchronized InputStream getErrorStream() {
            if (err == null) try {
                errPfd = remote.getErrorStream();
                err = new ParcelFileDescriptor.AutoCloseInputStream(errPfd);
            } catch (Exception e) { throw new RuntimeException(e); }
            return err;
        }
        @Override public int waitFor() throws InterruptedException {
            try { int c = remote.waitFor(); cachedExit = c; return c; }
            catch (android.os.RemoteException e) { throw new RuntimeException(e); }
        }
        @Override public synchronized int exitValue() {
            if (cachedExit != null) return cachedExit;
            try { int c = remote.exitValue(); cachedExit = c; return c; }
            catch (android.os.RemoteException e) { throw new RuntimeException(e); }
        }
        @Override public void destroy() {
            try { remote.destroy(); } catch (android.os.RemoteException ignored) {}
        }
    }

    public boolean launchXposedHook(String packageName, int pid, int uid, String dexPath) {
        if (!isAuthorized()) {
            logger.w("Cannot launch: Shizuku not authorized");
            return false;
        }
        String binary = getAppProcessBinary();
        if (binary == null) {
            logger.e("Cannot launch: no usable app_process binary");
            return false;
        }
        try {
            String cmd = String.format(
                "%s -Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-cp %s /system/bin XposedHook %s %d %d &",
                binary, Build.VERSION.SDK_INT, dexPath, packageName, pid, uid);
            return executeCommand(cmd).isSuccess();
        } catch (Exception e) {
            logger.e("launchXposedHook error: " + e.getMessage());
            return false;
        }
    }

    public void cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Exception ignored) {}
        listenersRegistered.set(false);
        permissionListeners.clear();
        cachedAppProcessBinary = null;
    }

    public enum ShizukuStatus { ACTIVE, NOT_ACTIVE, NOT_INSTALLED }
}