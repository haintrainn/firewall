package io.github.haintrainn.firewall.shizuku;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal dependency-free Shizuku client. Transaction codes mirror the AIDL in
 * RikkaApps/Shizuku-API (aidl/.../IShizukuService.aidl): code = FIRST_CALL_TRANSACTION + id.
 */
public final class Shizuku {

    private static final String TAG = "FirewallShizuku";

    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";

    private static final String SERVICE_DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static final String APPLICATION_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    private static final String PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";

    // IShizukuService
    private static final int TX_NEW_PROCESS = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int TX_REQUEST_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 14;
    private static final int TX_CHECK_SELF_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 15;
    private static final int TX_ATTACH_APPLICATION = IBinder.FIRST_CALL_TRANSACTION + 17;

    // IShizukuApplication (server -> us)
    private static final int TX_BIND_APPLICATION = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TX_PERMISSION_RESULT = IBinder.FIRST_CALL_TRANSACTION + 2;

    // IRemoteProcess (no explicit ids: declaration order)
    private static final int TX_PROC_OUTPUT = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TX_PROC_INPUT = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TX_PROC_ERROR = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TX_PROC_WAIT_FOR = IBinder.FIRST_CALL_TRANSACTION + 3;

    private static final int API_VERSION = 13;

    public interface Listener {
        void onShizukuStateChanged();
    }

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean ok() {
            return exitCode == 0;
        }

        public String message() {
            String s = stderr.trim().isEmpty() ? stdout.trim() : stderr.trim();
            return s.isEmpty() ? "exit code " + exitCode : s;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile IBinder binder;
    private static volatile boolean permissionGranted;

    private static final IBinder.DeathRecipient DEATH = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            binder = null;
            permissionGranted = false;
            notifyListeners();
        }
    };

    private static final Binder APPLICATION = new Binder() {
        {
            attachInterface(null, APPLICATION_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TX_BIND_APPLICATION: {
                    data.enforceInterface(APPLICATION_DESCRIPTOR);
                    Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                    if (b != null) {
                        permissionGranted = b.getBoolean("shizuku:attach-reply-permission-granted", false);
                    }
                    notifyListeners();
                    return true;
                }
                case TX_PERMISSION_RESULT: {
                    data.enforceInterface(APPLICATION_DESCRIPTOR);
                    data.readInt(); // request code
                    Bundle b = data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
                    permissionGranted = b != null && b.getBoolean("shizuku:request-permission-reply-allowed", false);
                    notifyListeners();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    private Shizuku() {
    }

    static void onBinderReceived(IBinder newBinder, String packageName) {
        if (newBinder == null || newBinder == binder) {
            return;
        }
        IBinder old = binder;
        if (old != null) {
            try {
                old.unlinkToDeath(DEATH, 0);
            } catch (Throwable ignored) {
            }
        }
        binder = newBinder;
        try {
            newBinder.linkToDeath(DEATH, 0);
        } catch (RemoteException e) {
            binder = null;
            return;
        }
        try {
            attachApplication(newBinder, packageName);
        } catch (Throwable t) {
            Log.w(TAG, "attachApplication failed", t);
        }
        refreshPermission();
        notifyListeners();
    }

    private static void attachApplication(IBinder b, String packageName) throws RemoteException {
        Bundle args = new Bundle();
        args.putInt("shizuku:attach-api-version", API_VERSION);
        args.putString("shizuku:attach-package-name", packageName);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(APPLICATION);
            data.writeInt(1);
            args.writeToParcel(data, 0);
            b.transact(TX_ATTACH_APPLICATION, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void refreshPermission() {
        IBinder b = binder;
        if (b == null) {
            permissionGranted = false;
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            b.transact(TX_CHECK_SELF_PERMISSION, data, reply, 0);
            reply.readException();
            permissionGranted = reply.readInt() != 0;
        } catch (Throwable t) {
            Log.w(TAG, "checkSelfPermission failed", t);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static boolean isRunning() {
        IBinder b = binder;
        return b != null && b.pingBinder();
    }

    public static boolean hasPermission() {
        return isRunning() && permissionGranted;
    }

    public static boolean isReady() {
        return hasPermission();
    }

    public static void requestPermission() {
        IBinder b = binder;
        if (b == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(1);
            b.transact(TX_REQUEST_PERMISSION, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Runs a command as the Shizuku server's uid (shell or root). Blocking; call off the main thread. */
    public static Result exec(String... cmd) throws IOException, RemoteException {
        IBinder b = binder;
        if (b == null || !permissionGranted) {
            throw new IllegalStateException("Shizuku is not available");
        }
        IBinder process;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStringArray(cmd);
            data.writeStringArray(null);
            data.writeString(null);
            b.transact(TX_NEW_PROCESS, data, reply, 0);
            reply.readException();
            process = reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (process == null) {
            throw new IOException("Shizuku returned no process");
        }

        ParcelFileDescriptor stdin = processFd(process, TX_PROC_OUTPUT);
        if (stdin != null) {
            stdin.close();
        }
        ParcelFileDescriptor out = processFd(process, TX_PROC_INPUT);
        ParcelFileDescriptor err = processFd(process, TX_PROC_ERROR);

        final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        Thread errReader = null;
        if (err != null) {
            final ParcelFileDescriptor errFd = err;
            errReader = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain(errFd, errBytes);
                }
            }, "shizuku-stderr");
            errReader.start();
        }
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        if (out != null) {
            drain(out, outBytes);
        }
        if (errReader != null) {
            try {
                errReader.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int exit;
        data = Parcel.obtain();
        reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROCESS_DESCRIPTOR);
            process.transact(TX_PROC_WAIT_FOR, data, reply, 0);
            reply.readException();
            exit = reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
        return new Result(exit, outBytes.toString(), errBytes.toString());
    }

    private static ParcelFileDescriptor processFd(IBinder process, int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PROCESS_DESCRIPTOR);
            process.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(reply) : null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void drain(ParcelFileDescriptor fd, ByteArrayOutputStream sink) {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sink.write(buf, 0, n);
            }
        } catch (IOException ignored) {
        }
    }

    public static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void notifyListeners() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) {
                    l.onShizukuStateChanged();
                }
            }
        });
    }
}
