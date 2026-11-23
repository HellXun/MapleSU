package dev.maplesu.manager.runtime;

import android.app.Service;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.system.ErrnoException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;

/** Test APK runs independently of the target APK and its Kotlin runtime. */
public final class IdentityProbeService extends Service {
    public static final String DESCRIPTOR = "dev.maplesu.manager.identityProbe";

    @Override
    public IBinder onBind(Intent intent) {
        return new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == FIRST_CALL_TRANSACTION + 7) {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply == null) throw new IllegalArgumentException("Missing fixture reply");
                    binderBootstrapProbe(data, reply);
                    return true;
                }
                if (code == FIRST_CALL_TRANSACTION + 6) {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply == null) throw new IllegalArgumentException("Missing fixture reply");
                    socketContextProbe(data, reply);
                    return true;
                }
                if (code == FIRST_CALL_TRANSACTION + 5) {
                    data.enforceInterface(DESCRIPTOR);
                    if (reply == null) throw new IllegalArgumentException("Missing fixture reply");
                    IBinder target = data.readStrongBinder();
                    String marker = data.readString();
                    if (target == null || marker == null) throw new IllegalArgumentException("Missing root fixture input");
                    Bundle result = executeRootTask(target, marker);
                    reply.writeNoException();
                    reply.writeTypedObject(result, 0);
                    return true;
                }
                if (code < FIRST_CALL_TRANSACTION || code > FIRST_CALL_TRANSACTION + 3) {
                    return super.onTransact(code, data, reply, flags);
                }
                data.enforceInterface(DESCRIPTOR);
                IBinder target = data.readStrongBinder();
                if (target == null || reply == null) throw new IllegalArgumentException("Missing test Binder");
                Parcel request = Parcel.obtain();
                Parcel response = Parcel.obtain();
                boolean accepted;
                try {
                    request.writeInterfaceToken(code == FIRST_CALL_TRANSACTION ? DESCRIPTOR
                            : code == FIRST_CALL_TRANSACTION + 3
                            ? "dev.maplesu.manager.runtime.IRootRequestService"
                            : "dev.maplesu.manager.installer.IInstallerService");
                    int targetCode = code == FIRST_CALL_TRANSACTION + 2 ? FIRST_CALL_TRANSACTION + 1
                            : FIRST_CALL_TRANSACTION;
                    // openBootImage's snapshot is an out Bundle, so its request has no input argument.
                    if (code == FIRST_CALL_TRANSACTION + 3) {
                        request.writeStringArray(new String[]{"/system/bin/id", "-u"});
                        request.writeStrongBinder(new Binder());
                    }
                    if (!target.transact(targetCode, request, response, 0)) {
                        throw new IllegalStateException("Test Binder transaction was not handled");
                    }
                    response.readException();
                    if (code == FIRST_CALL_TRANSACTION + 2) {
                        try (ParcelFileDescriptor image = response.readTypedObject(ParcelFileDescriptor.CREATOR)) {
                            response.readTypedObject(Bundle.CREATOR);
                        } catch (IOException error) {
                            throw new IllegalStateException("Could not close the unexpected snapshot", error);
                        }
                    } else if (code == FIRST_CALL_TRANSACTION + 3) {
                        IBinder task = response.readStrongBinder();
                        if (task == null) throw new IllegalStateException("Root task was absent");
                        Parcel closeRequest = Parcel.obtain();
                        Parcel closeResponse = Parcel.obtain();
                        try {
                            closeRequest.writeInterfaceToken("dev.maplesu.manager.runtime.IRootTask");
                            if (!task.transact(FIRST_CALL_TRANSACTION + 5, closeRequest, closeResponse, 0)) {
                                throw new IllegalStateException("Unexpected root task did not close");
                            }
                            closeResponse.readException();
                        } finally {
                            closeRequest.recycle();
                            closeResponse.recycle();
                        }
                    }
                    accepted = true;
                } catch (SecurityException denied) {
                    accepted = false;
                } finally {
                    request.recycle();
                    response.recycle();
                }
                reply.writeNoException();
                reply.writeInt(Process.myUid());
                reply.writeInt(accepted ? 1 : 0);
                return true;
            }
        };
    }

    /** FIRST+7 only: an app_process without ProcessRecord discovers the exported Binder. */
    private void binderBootstrapProbe(Parcel request, Parcel reply) {
        java.io.File jar = null;
        java.lang.Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            int manager = getPackageManager().getApplicationInfo("dev.maplesu.manager", 0).uid;
            if (Binder.getCallingUid() != manager) throw new SecurityException("Fixture caller mismatch");
            byte[] dexJar = request.createByteArray();
            if (dexJar == null || dexJar.length == 0 || dexJar.length > 128 * 1024)
                throw new IllegalArgumentException("Fixture DEX size mismatch");
            jar = java.io.File.createTempFile("binder-bootstrap-probe-", ".jar", getCodeCacheDir());
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(jar)) {
                // Android 14+ dynamic code must be read-only before it is loaded.
                if (!jar.setReadOnly()) throw new IOException("Fixture DEX readonly failed");
                output.write(dexJar);
            }
            ProcessBuilder launch = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                    "dev.maplesu.fixture.BinderBootstrapProbe", Integer.toString(manager));
            launch.environment().put("CLASSPATH", jar.getAbsolutePath());
            process = launch.start();
            process.getOutputStream().close();
            final java.lang.Process running = process;
            Future<byte[]> stdout = readers.submit(() -> readProbeStream(running.getInputStream()));
            Future<byte[]> stderr = readers.submit(() -> readProbeStream(running.getErrorStream()));
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Fixture app_process timeout");
            Bundle result = new Bundle();
            result.putInt("fixtureUid", Process.myUid());
            result.putInt("exitCode", process.exitValue());
            result.putString("stdout", new String(stdout.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            result.putString("stderr", new String(stderr.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            reply.writeNoException(); reply.writeTypedObject(result, 0);
        } catch (Exception failure) {
            throw new IllegalStateException("Binder bootstrap fixture failed", failure);
        } finally {
            if (process != null) process.destroyForcibly();
            readers.shutdownNow();
            if (jar != null) jar.delete();
        }
    }

    private static byte[] readProbeStream(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    /** FIRST+6 only: fixed-message probe in the test APK's actual UID. */
    private void socketContextProbe(Parcel request, Parcel reply) {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        String stage = "caller_identity";
        try {
            int manager = getPackageManager().getApplicationInfo("dev.maplesu.manager", 0).uid;
            if (Binder.getCallingUid() != manager) throw new SecurityException("Fixture caller mismatch");
            String name = request.readString();
            if (name == null || !name.matches("maplesu\\.socketprobe\\.[abc]\\.[a-f0-9-]{36}"))
                throw new IllegalArgumentException("Fixture socket mismatch");
            stage = "connect";
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                result.putBoolean("connected", true);
                result.putInt("serverPeerUid", socket.getPeerCredentials().getUid());
                socket.setSoTimeout(5000);
                stage = "fixed_echo";
                socket.getOutputStream().write(new byte[]{'P', 'I', 'N', 'G'});
                socket.getOutputStream().flush();
                byte[] pong = new byte[4];
                new java.io.DataInputStream(socket.getInputStream()).readFully(pong);
                result.putBoolean("echo", Arrays.equals(new byte[]{'P', 'O', 'N', 'G'}, pong));
                stage = "complete";
            }
        } catch (Exception failure) {
            result.putString("failure", failure.toString());
            Throwable error = failure;
            while (error != null) {
                if (error instanceof ErrnoException) { result.putInt("errno", ((ErrnoException) error).errno); break; }
                error = error.getCause();
            }
        }
        result.putString("stage", stage);
        reply.writeNoException();
        reply.writeTypedObject(result, 0);
    }

    /** Every task transaction and all stdio I/O remain in the test APK's actual UID. */
    private static Bundle executeRootTask(IBinder target, String marker) throws RemoteException {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        IBinder owner = new Binder();
        IBinder task;
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken("dev.maplesu.manager.runtime.IRootRequestService");
            request.writeStringArray(new String[]{"/system/bin/sh", "-c",
                    "/system/bin/id -u; /system/bin/cat; printf err >&2; exit 37"});
            request.writeStrongBinder(owner);
            if (!target.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0)) {
                throw new IllegalStateException("Root start transaction was not handled");
            }
            try {
                response.readException();
            } catch (SecurityException denied) {
                result.putBoolean("accepted", false);
                return result;
            }
            task = response.readStrongBinder();
            if (task == null) throw new IllegalStateException("Root task was absent");
        } finally {
            request.recycle();
            response.recycle();
        }
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try (ParcelFileDescriptor stdin = openStream(task, 0);
             ParcelFileDescriptor stdout = openStream(task, 1);
             ParcelFileDescriptor stderr = openStream(task, 2)) {
            Future<byte[]> output = readers.submit(() -> readStream(stdout));
            Future<byte[]> error = readers.submit(() -> readStream(stderr));
            try (ParcelFileDescriptor.AutoCloseOutputStream input =
                         new ParcelFileDescriptor.AutoCloseOutputStream(stdin)) {
                input.write(marker.getBytes(StandardCharsets.UTF_8));
            }
            Bundle exit = awaitTask(task);
            result.putBoolean("accepted", true);
            result.putBoolean("finished", exit.getBoolean("finished"));
            result.putInt("exitCode", exit.getInt("exitCode"));
            result.putByteArray("stdout", output.get(5, TimeUnit.SECONDS));
            result.putByteArray("stderr", error.get(5, TimeUnit.SECONDS));
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Peer UID root task round trip failed", error);
        } finally {
            try { closeTask(task); }
            finally { readers.shutdownNow(); }
        }
    }

    private static ParcelFileDescriptor openStream(IBinder task, int stream) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken("dev.maplesu.manager.runtime.IRootTask");
            if (!task.transact(IBinder.FIRST_CALL_TRANSACTION + stream, request, response, 0)) {
                throw new IllegalStateException("Root stream transaction was not handled");
            }
            response.readException();
            ParcelFileDescriptor descriptor = response.readTypedObject(ParcelFileDescriptor.CREATOR);
            if (descriptor == null) throw new IllegalStateException("Root stream was absent");
            return descriptor;
        } finally {
            request.recycle();
            response.recycle();
        }
    }

    private static byte[] readStream(ParcelFileDescriptor descriptor) throws IOException {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static Bundle awaitTask(IBinder task) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken("dev.maplesu.manager.runtime.IRootTask");
            request.writeInt(10_000);
            if (!task.transact(IBinder.FIRST_CALL_TRANSACTION + 3, request, response, 0)) {
                throw new IllegalStateException("Root wait transaction was not handled");
            }
            response.readException();
            Bundle result = response.readTypedObject(Bundle.CREATOR);
            if (result == null) throw new IllegalStateException("Root exit result was absent");
            return result;
        } finally {
            request.recycle();
            response.recycle();
        }
    }

    private static void closeTask(IBinder task) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken("dev.maplesu.manager.runtime.IRootTask");
            if (!task.transact(IBinder.FIRST_CALL_TRANSACTION + 5, request, response, 0)) {
                throw new IllegalStateException("Root close transaction was not handled");
            }
            response.readException();
        } finally {
            request.recycle();
            response.recycle();
        }
    }
}
