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

public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";
    private static final int SHIZUKU_CODE = 0xCA07A;
    private static ShizukuHelper instance;

    private Context context;
    private Logger logger;
    private volatile boolean isAvailable = false;
    private volatile boolean isAuthorized = false;
    private int shizukuVersion = 0;
    private boolean isSui = false;
    private boolean binderStatus = false;

    private static final String SHIZUKU_API_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager";

    private final AtomicBoolean permissionRequestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean listenersRegistered = new AtomicBoolean(false);
    private final AtomicBoolean inAutoStart = new AtomicBoolean(false);

    // ✅ Show the "Please grant permission" toast at most once per session.
    private final AtomicBoolean grantToastShown = new AtomicBoolean(false);

    public interface PermissionListener {
        void onPermissionGranted();
        void onPermissionDenied();
    }

    private final List<PermissionListener> permissionListeners = new CopyOnWriteArrayList<>();

    public void addPermissionListener(PermissionListener l) {
        if (l == null) return;
        if (!permissionListeners.contains(l)) {
            permissionListeners.add(l);
        }
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
            catch (Throwable t) { logger.e("PermissionListener.onPermissionGranted error: " + t.getMessage()); }
        }
    }

    private void notifyPermissionDenied() {
        for (PermissionListener l : permissionListeners) {
            try { l.onPermissionDenied(); }
            catch (Throwable t) { logger.e("PermissionListener.onPermissionDenied error: " + t.getMessage()); }
        }
    }

    private final Shizuku.OnBinderReceivedListener binderListener =
        new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            binderStatus = true;
            isAvailable = true;
            logger.i("Shizuku binder received");
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
            grantToastShown.set(false);   // allow the toast again next session
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
                grantToastShown.set(true);   // no need to nudge anymore
                logger.i("✅ Shizuku permission GRANTED!");
                notifyPermissionGranted();
                if (context instanceof ShizuPosedManagerApp) {
                    ShizuPosedManagerApp app = (ShizuPosedManagerApp) context;
                    if (app.getMainActivity() != null) {
                        app.getMainActivity().onShizukuPermissionGranted();
                    }
                }
            } else {
                isAuthorized = false;
                logger.w("❌ Shizuku permission DENIED");
                notifyPermissionDenied();
            }
        }
    };

    private ShizukuHelper(Context context) {
        this.context = context.getApplicationContext();
        this.logger = Logger.getInstance(context);
        initShizuku();
    }

    public static synchronized ShizukuHelper getInstance(Context context) {
        if (instance == null) {
            instance = new ShizukuHelper(context);
        }
        return instance;
    }

    private void initShizuku() {
        try {
            String packageName = context.getPackageName();
            try {
                isSui = Sui.init(packageName);
                if (isSui) {
                    logger.i("✅ Sui detected and initialized!");
                    isAvailable = true;
                    postRegisterListeners();
                    checkPermissionDeferred();
                    return;
                }
            } catch (NoClassDefFoundError e) {
                logger.w("Sui not found, continuing with Shizuku only");
                isSui = false;
            } catch (Exception e) {
                logger.w("Sui init failed: " + e.getMessage());
                isSui = false;
            }

            boolean shizukuApiInstalled = isShizukuApiInstalled();
            boolean shizukuManagerInstalled = isShizukuManagerInstalled();

            if (!shizukuApiInstalled && !shizukuManagerInstalled) {
                logger.w("Shizuku not installed");
                logger.w("Please install Shizuku from: https://github.com/RikkaApps/Shizuku");
                return;
            }

            if (shizukuApiInstalled) {
                logger.i("✅ Shizuku API installed (moe.shizuku.privileged.api)");
            }
            if (shizukuManagerInstalled) {
                logger.i("✅ Shizuku Manager installed (moe.shizuku.manager)");
            }

            binderStatus = Shizuku.pingBinder();

            if (binderStatus && !Shizuku.isPreV11()) {
                isAvailable = true;
                shizukuVersion = Shizuku.getVersion();
                logger.i("✅ Shizuku v" + shizukuVersion + " available");
            } else {
                isAvailable = false;
                logger.w("❌ Shizuku is not active");
                logger.w("   binder: " + binderStatus + ", preV11: " + Shizuku.isPreV11());
            }

            postRegisterListeners();
            checkPermissionDeferred();

        } catch (NoClassDefFoundError e) {
            logger.e("Shizuku API not found: " + e.getMessage());
            isAvailable = false;
        } catch (Exception e) {
            logger.e("Shizuku init failed: " + e.getMessage());
            isAvailable = false;
        }
    }

    private void postRegisterListeners() {
        new Handler(Looper.getMainLooper()).post(this::registerListeners);
    }

    private void registerListeners() {
        if (!listenersRegistered.compareAndSet(false, true)) return;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
            logger.d("Shizuku listeners registered");
        } catch (Throwable t) {
            logger.e("Failed to register Shizuku listeners: " + t.getMessage());
        }
    }

    private void checkPermissionDeferred() {
        new Handler(Looper.getMainLooper()).post(this::checkPermission);
    }

    private boolean isShizukuApiInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_API_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private boolean isShizukuManagerInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    public ShizukuStatus checkShizukuActive() {
        if (isSui) return ShizukuStatus.ACTIVE;

        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_API_PACKAGE, 0);
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) return ShizukuStatus.ACTIVE;
            return ShizukuStatus.NOT_ACTIVE;
        } catch (PackageManager.NameNotFoundException e) {
            try {
                context.getPackageManager().getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0);
                if (Shizuku.pingBinder() && !Shizuku.isPreV11()) return ShizukuStatus.ACTIVE;
                return ShizukuStatus.NOT_ACTIVE;
            } catch (PackageManager.NameNotFoundException e2) {
                return ShizukuStatus.NOT_INSTALLED;
            }
        }
    }

    private void checkPermission() {
        if (!isAvailable) return;

        try {
            if (isSui) {
                boolean was = isAuthorized;
                isAuthorized = true;
                logger.i("✅ Sui is active");
                if (!was) {
                    notifyPermissionGranted();
                    autoStartServiceIfPossible();
                }
                return;
            }

            int result = Shizuku.checkSelfPermission();
            boolean wasAuthorized = isAuthorized;
            isAuthorized = (result == PackageManager.PERMISSION_GRANTED);

            if (isAuthorized) {
                logger.i("✅ Shizuku permission GRANTED");
                if (!wasAuthorized) {
                    notifyPermissionGranted();
                    autoStartServiceIfPossible();
                }
            } else {
                logger.w("⚠️ Shizuku permission NOT GRANTED");
                if (permissionRequestInFlight.compareAndSet(false, true)) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        permissionRequestInFlight.set(false);
                        requestPermission();
                    }, 500);
                } else {
                    logger.d("Permission request already queued — skipping");
                }
            }
        } catch (Exception e) {
            logger.e("Failed to check permission: " + e.getMessage());
            isAuthorized = false;
        }
    }

    private void autoStartServiceIfPossible() {
        if (!inAutoStart.compareAndSet(false, true)) {
            return;
        }
        try {
            if (context instanceof ShizuPosedManagerApp) {
                ShizuPosedManagerApp app = (ShizuPosedManagerApp) context;
                new Handler(Looper.getMainLooper()).post(app::autoStartService);
            }
        } catch (Throwable t) {
            logger.e("Failed to auto-start service: " + t.getMessage());
        } finally {
            inAutoStart.set(false);
        }
    }

    public void requestPermission() {
        if (!isAvailable) {
            logger.w("Cannot request permission: Shizuku not available");
            return;
        }
        if (isAuthorized) {
            logger.i("Shizuku already authorized");
            return;
        }
        if (!permissionRequestInFlight.compareAndSet(false, true)) {
            logger.d("Shizuku permission request already in flight — skipping");
            return;
        }

        try {
            logger.i("📢 Requesting Shizuku permission...");
            Shizuku.requestPermission(SHIZUKU_CODE);
            logger.i("Shizuku permission requested");

            // ✅ Toast only once per session
            if (grantToastShown.compareAndSet(false, true)) {
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context,
                        "Please grant permission in Shizuku app",
                        Toast.LENGTH_LONG).show()
                );
            }

        } catch (Exception e) {
            permissionRequestInFlight.set(false);
            logger.e("Failed to request permission: " + e.getMessage());
        }
    }

    public boolean isAvailable() { return isAvailable; }
    public boolean isAuthorized() { return isAuthorized; }
    public int getVersion() { return shizukuVersion; }
    public boolean isSui() { return isSui; }

    public ShellUtils.CommandResult executeCommand(String command) {
        ShellUtils.CommandResult result = new ShellUtils.CommandResult();

        if (!isAvailable || !isAuthorized) {
            result.stderr.add("Shizuku not available or not authorized");
            result.exitCode = -1;
            return result;
        }

        Process process = null;
        try {
            process = spawnShellViaAidl(
                new String[]{"sh", "-c", command}, null, null);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) result.stdout.add(line);
            while ((line = errorReader.readLine()) != null) result.stderr.add(line);

            result.exitCode = process.waitFor();
            reader.close();
            errorReader.close();

            if (result.isSuccess()) {
                logger.d("Command executed via Shizuku: " + command);
            } else {
                logger.e("Command failed: " + result.getStderrString());
            }
        } catch (Exception e) {
            logger.e("Command execution failed: " + e.getMessage());
            result.stderr.add(e.getMessage());
            result.exitCode = -1;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
        return result;
    }

    private Process spawnShellViaAidl(String[] cmd, String[] env, String dir)
            throws Exception {
        android.os.IBinder binder = Shizuku.getBinder();
        if (binder == null) throw new IllegalStateException("Shizuku binder is null");

        moe.shizuku.server.IShizukuService service =
            moe.shizuku.server.IShizukuService.Stub.asInterface(binder);

        moe.shizuku.server.IRemoteProcess remote =
            service.newProcess(cmd, env, dir);

        if (remote == null) throw new IllegalStateException("Shizuku returned null process");
        return new AidlProcessAdapter(remote);
    }

    private static final class AidlProcessAdapter extends Process {
        private final moe.shizuku.server.IRemoteProcess remote;
        private InputStream in;
        private InputStream err;
        private OutputStream out;
        private ParcelFileDescriptor inPfd;
        private ParcelFileDescriptor errPfd;
        private ParcelFileDescriptor outPfd;
        private Integer cachedExit;

        AidlProcessAdapter(moe.shizuku.server.IRemoteProcess remote) { this.remote = remote; }

        @Override public synchronized OutputStream getOutputStream() {
            if (out == null) {
                try {
                    outPfd = remote.getOutputStream();
                    out = new ParcelFileDescriptor.AutoCloseOutputStream(outPfd);
                } catch (Exception e) { throw new RuntimeException("getOutputStream failed", e); }
            }
            return out;
        }
        @Override public synchronized InputStream getInputStream() {
            if (in == null) {
                try {
                    inPfd = remote.getInputStream();
                    in = new ParcelFileDescriptor.AutoCloseInputStream(inPfd);
                } catch (Exception e) { throw new RuntimeException("getInputStream failed", e); }
            }
            return in;
        }
        @Override public synchronized InputStream getErrorStream() {
            if (err == null) {
                try {
                    errPfd = remote.getErrorStream();
                    err = new ParcelFileDescriptor.AutoCloseInputStream(errPfd);
                } catch (Exception e) { throw new RuntimeException("getErrorStream failed", e); }
            }
            return err;
        }
        @Override public int waitFor() throws InterruptedException {
            try { int code = remote.waitFor(); cachedExit = code; return code; }
            catch (android.os.RemoteException e) { throw new RuntimeException(e); }
        }
        @Override public synchronized int exitValue() {
            if (cachedExit != null) return cachedExit;
            try { int code = remote.exitValue(); cachedExit = code; return code; }
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
        try {
            String cmd = String.format(
                "app_process -Xverify:none -Xallowinmemorycompilation " +
                "-Xcompiler-option --target-api=%d " +
                "-cp %s /system/bin XposedHook %s %d %d &",
                Build.VERSION.SDK_INT, dexPath, packageName, pid, uid);
            return executeCommand(cmd).isSuccess();
        } catch (Exception e) {
            logger.e("launchXposedHook error: " + e.getMessage());
            return false;
        }
    }

    public String getStatusString() {
        if (isSui) return "✅ Sui Active";
        if (!isAvailable) return "❌ Shizuku Not Available";
        if (!isAuthorized) return "⚠️ Shizuku Available (Not Authorized)";
        return "✅ Shizuku Authorized (v" + shizukuVersion + ")";
    }

    public void cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Exception ignored) {}
        listenersRegistered.set(false);
        permissionListeners.clear();
    }

    public enum ShizukuStatus { ACTIVE, NOT_ACTIVE, NOT_INSTALLED }
}