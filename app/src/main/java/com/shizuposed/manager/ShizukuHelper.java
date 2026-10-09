package com.shizuposed.manager;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import com.shizuposed.manager.utils.Logger;
import com.shizuposed.manager.utils.ShellUtils;

import java.io.BufferedReader;
import java.io.File;
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
 * APP_PROCESS RESOLUTION
 * ----------------------
 * app_process64 / app_process have fixed filesystem paths on every
 * Android device. Probing them with `command -v` is unreliable:
 * $PATH on Android's mksh does not always contain /system/bin, and
 * app_process invoked bare (no class argument) exits with a usage
 * error that a naive probe misreads as "not found."
 *
 * So the probe checks the filesystem directly, in this order:
 *
 *   1. Shell `test -x /system/bin/app_process64` — preferred on
 *      Android 10+, where the 64-bit binary is authoritative.
 *   2. Shell `test -x /system/bin/app_process` — older devices and
 *      some 32-bit-only ROMs.
 *   3. Direct java.io.File.canRead() on the same paths — used only
 *      if shell probing fails. The app cannot execute /system/bin
 *      binaries itself, but canRead() confirms the path exists,
 *      and Shizuku's shell will still run it on the launch.
 *
 * This resolves on real Android devices and removes any dependency
 * on how a particular Shizuku fork implements provider-side binary
 * lookup.
 */
public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final int SHIZUKU_CODE = 0xCA07A;
    private static ShizukuHelper instance;

    private static final long GRANT_SETTLE_MS = 5000L;

    private final Context context;
    private final Logger logger;

    private volatile boolean isAvailable = false;
    private volatile boolean isAuthorized = false;
    private volatile int shizukuVersion = 0;
    private volatile boolean isSui = false;
    private volatile boolean binderStatus = false;

    private volatile long lastGrantTimestamp = 0L;

    private volatile boolean realShizukuInstalled = false;
    private volatile boolean sheveryInstalled = false;

    private static final String SHIZUKU_API_PACKAGE     = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager";
    private static final String SHEVERY_PACKAGE         = "com.hamondev.shevery";

    private final AtomicBoolean permissionRequestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean listenersRegistered = new AtomicBoolean(false);
    private final AtomicBoolean inAutoStart = new AtomicBoolean(false);
    private final AtomicBoolean grantToastShown = new AtomicBoolean(false);

    /**
     * Absolute filesystem paths to check. Order matters: the 64-bit
     * binary is authoritative on Android 10+, but some ROMs ship
     * only the generic name.
     */
    private static final String[] APP_PROCESS_ABS_PATHS = {
        "/system/bin/app_process64",
        "/system/bin/app_process",
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
            checkPermission("binderReceived");
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
        new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            binderStatus = false;
            isAvailable = false;
            isAuthorized = false;
            lastGrantTimestamp = 0L;
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
                markAuthorized("onRequestPermissionResult");
                grantToastShown.set(true);
                logger.i("✅ Shizuku permission GRANTED via provider=" + providerName());
                notifyPermissionGranted();
                autoStartServiceIfPossible();
            } else {
                isAuthorized = false;
                lastGrantTimestamp = 0L;
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
        registerListeners();
        initShizuku();
    }

    public static synchronized ShizukuHelper getInstance(Context context) {
        if (instance == null) instance = new ShizukuHelper(context);
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
                listenersRegistered.set(true);
                logger.d("Shizuku listeners registered");
            } catch (Throwable t) {
                logger.e("Failed to register Shizuku listeners: " + t.getMessage());
            }
        }
    }

    private void initShizuku() {
        try {
            try {
                isSui = Sui.init(context.getPackageName());
                if (isSui) {
                    logger.i("✅ Sui detected — using Sui binder");
                    isAvailable = true;
                    return;
                }
            } catch (NoClassDefFoundError e) {
                logger.d("Sui not present");
                isSui = false;
            } catch (Throwable e) {
                logger.w("Sui init failed: " + e.getMessage());
                isSui = false;
            }

            sheveryInstalled = isPackageInstalled(SHEVERY_PACKAGE);
            realShizukuInstalled = !sheveryInstalled
                                && isPackageInstalled(SHIZUKU_MANAGER_PACKAGE)
                                && isPackageInstalled(SHIZUKU_API_PACKAGE);

            if (!realShizukuInstalled && !sheveryInstalled) {
                logger.w("Shizuku/Shevery not installed");
                isAvailable = false;
                return;
            }

            if (sheveryInstalled) {
                logger.i("✅ Shevery detected (com.hamondev.shevery) — provider=Shevery(stub)");
            } else if (realShizukuInstalled) {
                logger.i("✅ Real Shizuku detected — provider=Shizuku");
            }
            logBinderProvenance();

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
        if (sheveryInstalled) return "Shevery(stub)";
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
    // PERMISSION CHECK
    // ═════════════════════════════════════════════════════════════

    private void markAuthorized(String source) {
        boolean was = isAuthorized;
        isAuthorized = true;
        lastGrantTimestamp = System.currentTimeMillis();
        if (!was) {
            logger.i("markAuthorized (source=" + source
                    + ", provider=" + providerName() + ")");
        }
    }

    private void checkPermission(String source) {
        if (!isAvailable) {
            logger.d("checkPermission(" + source + "): binder not available, skipping");
            return;
        }

        try {
            int result = Shizuku.checkSelfPermission();
            boolean granted = (result == PackageManager.PERMISSION_GRANTED);
            boolean was = isAuthorized;

            if (granted) {
                markAuthorized(source);
                if (!was) {
                    notifyPermissionGranted();
                    autoStartServiceIfPossible();
                }
                return;
            }

            if (was) {
                long sinceGrant = System.currentTimeMillis() - lastGrantTimestamp;
                if (sinceGrant < GRANT_SETTLE_MS) {
                    logger.w("checkPermission(" + source + "): transient DENIED "
                            + "within settle window (" + sinceGrant + "ms since grant) "
                            + "— keeping isAuthorized=true (provider=" + providerName() + ")");
                    return;
                }
                logger.w("checkPermission(" + source + "): DENIED after settle window "
                        + "(" + sinceGrant + "ms since grant) — downgrading");
            } else {
                logger.w("checkPermission(" + source + "): NOT GRANTED "
                        + "(provider=" + providerName() + ")");
            }

            isAuthorized = false;
            lastGrantTimestamp = 0L;

        } catch (Throwable e) {
            logger.e("checkPermission(" + source + ") failed: " + e.getMessage());
        }
    }

    public void refreshFromBinder() {
        try {
            if (isSui) {
                checkPermission("refresh(Sui)");
                return;
            }
            if (!Shizuku.pingBinder()) {
                isAvailable = false;
                return;
            }
            isAvailable = true;
            if (shizukuVersion == 0) {
                try {
                    if (!Shizuku.isPreV11()) shizukuVersion = Shizuku.getVersion();
                } catch (Throwable ignored) {}
            }
            checkPermission("refresh");
        } catch (Throwable t) {
            logger.e("refreshFromBinder failed: " + t.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PERMISSION REQUEST
    // ═════════════════════════════════════════════════════════════

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

        logger.i("Force-requesting permission (provider=" + providerName() + ")");
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

    public boolean isAuthorizedFresh() {
        try {
            if (isSui) return isAuthorized;
            if (!Shizuku.pingBinder()) return false;
            int result = Shizuku.checkSelfPermission();
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            logger.e("isAuthorizedFresh failed: " + t.getMessage());
            return false;
        }
    }

    public boolean isSheveryInstalledPublic() { return sheveryInstalled; }

    public ShizukuStatus checkShizukuActive() {
        if (isSui) return ShizukuStatus.ACTIVE;
        boolean apiInstalled = isPackageInstalled(SHIZUKU_API_PACKAGE);
        boolean managerInstalled = isPackageInstalled(SHIZUKU_MANAGER_PACKAGE);
        boolean sheveryPresent = isPackageInstalled(SHEVERY_PACKAGE);
        sheveryInstalled = sheveryPresent;
        realShizukuInstalled = !sheveryPresent && managerInstalled && apiInstalled;
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

    public boolean isSheveryOnly() {
        return sheveryInstalled && !isSui;
    }

    public String getStatusString() {
        if (isSui) {
            return isAuthorized
                ? "🔑 Privileged (Sui)"
                : "⚠️ Sui Active (Not Authorized)";
        }

        final String provider = providerName();

        if (!isAvailable()) {
            return "❌ " + provider + " Not Active";
        }
        if (!isAuthorized) {
            return "⚠️ " + provider + " Available (Not Authorized)";
        }
        return "🔑 Privileged (" + provider + " v" + shizukuVersion + ")";
    }

    public String getAppProcessBinary() {
        String cached = cachedAppProcessBinary;
        if (cached != null) return cached;

        if (!isAvailable() || !isAuthorized) {
            logger.d("Cannot probe app_process: not ready");
            return null;
        }

        for (String absPath : APP_PROCESS_ABS_PATHS) {
            if (shellCanExecute(absPath)) {
                cachedAppProcessBinary = absPath;
                logger.i("app_process resolved via shell: " + absPath);
                return absPath;
            }
        }

        // Last resort: filesystem check. The manager cannot exec
        // /system/bin, but canRead() confirms the file exists.
        // Shizuku's shell exec on this path will succeed.
        for (String absPath : APP_PROCESS_ABS_PATHS) {
            File f = new File(absPath);
            if (f.exists() && f.canRead()) {
                cachedAppProcessBinary = absPath;
                logger.i("app_process resolved via filesystem (shell probe failed): "
                    + absPath);
                return absPath;
            }
        }

        logger.e("No usable app_process binary on this ROM "
            + "(checked " + java.util.Arrays.toString(APP_PROCESS_ABS_PATHS) + ")");
        return null;
    }

    /**
     * Ask shell whether the given absolute path exists and is
     * executable. Uses `test -x`, which is POSIX and works in every
     * Android shell. Returns false on any error.
     */
    private boolean shellCanExecute(String absPath) {
        try {
            String cmd = "test -x " + absPath + " && echo OK || echo NO";
            ShellUtils.CommandResult r = executeCommand(cmd);
            if (r == null) return false;

            String out = r.getStdoutString();
            boolean ok = out != null && out.contains("OK");

            if (!ok && logger != null) {
                logger.d("shellCanExecute(" + absPath + ") → NO "
                    + "(exit=" + r.exitCode
                    + ", stdout=" + out
                    + ", stderr=" + r.getStderrString() + ")");
            }
            return ok;
        } catch (Throwable t) {
            if (logger != null) {
                logger.w("shellCanExecute(" + absPath + ") threw: "
                    + t.getMessage());
            }
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

    public enum ShizukuStatus { ACTIVE, NOT_ACTIVE, NOT_INSTALLED }
}