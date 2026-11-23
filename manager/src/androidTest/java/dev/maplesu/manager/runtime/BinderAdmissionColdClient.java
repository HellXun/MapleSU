package dev.maplesu.manager.runtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import org.json.JSONObject;
import org.lsposed.hiddenapibypass.HiddenApiBypass;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fresh ordinary test-UID app_process; discovers a raw Root handle and runs fixed admission cases. */
public final class BinderAdmissionColdClient {
    private static final String MANAGER = "dev.maplesu.manager";
    private static final String TEST_PACKAGE = MANAGER + ".test";
    private static final String DISCOVERY = "dev.maplesu.manager.binderAdmissionDiscovery";
    private static final String ROOT = "dev.maplesu.manager.binderAdmissionRoot";
    private static final byte[] INPUT = "maplesu-binder-admission\n".getBytes(StandardCharsets.UTF_8);
    private static final String ERROR = "maplesu-binder-stderr\n";

    private BinderAdmissionColdClient() {}

    public static void main(String[] args) {
        AtomicBoolean reported = new AtomicBoolean();
        AtomicReference<String> stage = new AtomicReference<>("arguments");
        CountDownLatch finished = new CountDownLatch(1);
        Thread deadline = new Thread(() -> {
            try {
                if (finished.await(60, TimeUnit.SECONDS)) return;
                JSONObject timedOut = emptyReport();
                timedOut.put("stage", stage.get());
                timedOut.put("error", "BINDER_ADMISSION_COLD_CLIENT_TIMEOUT");
                printOnce(reported, timedOut);
                System.exit(70);
            } catch (Exception error) {
                System.exit(70);
            }
        }, "binder-admission-deadline");
        deadline.setDaemon(true);
        deadline.start();

        JSONObject report = emptyReport();
        ParcelFileDescriptor[] stdin = null, stdout = null, stderr = null;
        Object boundConnection = null;
        int exit = 1;
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            if (args.length > 3 || (args.length >= 1 && !args[0].equals("true") && !args[0].equals("false")) ||
                (args.length == 3 && !args[2].equals("sessions") && !args[2].equals("echo") &&
                    !args[2].equals("extended") && !args[2].equals("hold")))
                throw new IllegalArgumentException("Expected foreground true|false, broadcast|service|bind|peek, optional sessions|echo|extended|hold");
            boolean foreground = args.length >= 1 && args[0].equals("true");
            String discovery = args.length >= 2 ? args[1] : "broadcast";
            if (!discovery.equals("broadcast") && !discovery.equals("service") && !discovery.equals("bind") && !discovery.equals("peek"))
                throw new IllegalArgumentException("Discovery must be broadcast|service|bind|peek");
            report.put("discovery", discovery);
            report.put("foreground", foreground);
            report.put("clientContext", new String(Files.readAllBytes(Paths.get("/proc/self/attr/current")),
                StandardCharsets.UTF_8).replace("\u0000", "").trim());
            stage.set("verify_real_test_uid");
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityManager;", "Landroid/app/IActivityManager;",
                "Landroid/app/AppGlobals;", "Landroid/app/IServiceConnection;", "Landroid/content/pm/IPackageManager;");
            Object packages = Class.forName("android.app.AppGlobals").getDeclaredMethod("getPackageManager").invoke(null);
            int user = Process.myUid() / 100000;
            int managerUid = packageUid(packages, MANAGER, user);
            int testUid = packageUid(packages, TEST_PACKAGE, user);
            if (Process.myUid() < 10000 || Process.myUid() != testUid || testUid == managerUid)
                throw new SecurityException("BINDER_ADMISSION_REQUIRES_DISTINCT_REAL_TEST_UID");
            report.put("managerUID", managerUid);

            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<IBinder> discovered = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            AtomicInteger callbackUid = new AtomicInteger(-1);
            Binder callback = new Binder() {
                @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags) {
                    if (code != FIRST_CALL_TRANSACTION || Binder.getCallingUid() != managerUid) return false;
                    if (ready.getCount() == 0) return true;
                    callbackUid.set(Binder.getCallingUid());
                    try {
                        request.enforceInterface(DISCOVERY);
                        IBinder endpoint = request.readStrongBinder();
                        String error = request.readString();
                        if (request.dataAvail() != 0) throw new IOException("BINDER_ADMISSION_DISCOVERY_TRAILING_DATA");
                        if (error != null) throw new IOException("Broker: " + bounded(error));
                        if (endpoint == null) throw new IOException("BINDER_ADMISSION_ROOT_HANDLE_MISSING");
                        discovered.set(endpoint);
                    } catch (Exception error) {
                        failure.set(error);
                    } finally {
                        ready.countDown();
                    }
                    return true;
                }
            };
            Intent intent = new Intent("dev.maplesu.manager.debug.BINDER_ADMISSION_DISCOVER")
                .setComponent(new ComponentName(MANAGER, "dev.maplesu.manager.runtime.BinderAdmissionBrokerReceiver"))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if (foreground) intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            Bundle extras = new Bundle();
            extras.putBinder("callback", callback);
            intent.putExtras(extras);
            if (discovery.equals("peek")) {
                stage.set("peek_published_root_service");
                intent = new Intent("dev.maplesu.manager.debug.BINDER_ADMISSION_PEEK")
                    .setComponent(new ComponentName(MANAGER, "dev.maplesu.manager.runtime.BinderAdmissionBrokerService"));
                report.put("callerApplicationThread", "not_required_peek_service");
                report.put("discoverySource", "system_server_existing_service_binding");
                IBinder endpoint = peekService(intent, report);
                if (endpoint == null) throw new IOException("BINDER_ADMISSION_PEEK_NOT_PUBLISHED");
                discovered.set(endpoint);
                ready.countDown();
            } else if (discovery.equals("bind")) {
                intent.setComponent(new ComponentName(MANAGER, "dev.maplesu.manager.runtime.BinderAdmissionBrokerService"));
                stage.set("ordinary_bind_service");
                report.put("callerApplicationThread", "absent_native_client");
                boundConnection = bindService(intent, callback, user, ready, failure, report);
            } else if (discovery.equals("service")) {
                intent.setComponent(new ComponentName(MANAGER, "dev.maplesu.manager.runtime.BinderAdmissionBrokerService"));
                stage.set("ordinary_start_service");
                report.put("serviceResult", startService(intent, user));
            } else {
                stage.set("explicit_broadcast");
                report.put("broadcastResult", broadcast(intent, user));
            }
            stage.set("wait_raw_root_binder");
            if (!ready.await(45, TimeUnit.SECONDS)) throw new IOException("BINDER_ADMISSION_DISCOVERY_TIMEOUT");
            report.put("callbackUID", callbackUid.get());
            if (failure.get() != null) throw failure.get();
            IBinder root = discovered.get();
            stage.set("direct_root_identity");
            Bundle identity = transact(root, 0, null);
            copyIdentity(report, identity);
            verifyIdentity(identity, -1);

            stage.set("create_three_fds");
            stdin = ParcelFileDescriptor.createPipe();
            stdout = ParcelFileDescriptor.createPipe();
            stderr = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor.AutoCloseOutputStream input = new ParcelFileDescriptor.AutoCloseOutputStream(stdin[1])) {
                input.write(INPUT);
            }
            stage.set("direct_root_three_fds");
            Bundle io = transact(root, 1, new ParcelFileDescriptor[] { stdin[0], stdout[1], stderr[1] });
            verifyIdentity(io, identity.getInt("rootPid", -1));
            stdout[1].close();
            stderr[1].close();
            stage.set("read_three_fd_result");
            String output = read(stdout[0]);
            String errors = read(stderr[0]);
            report.put("stdout", output);
            report.put("stderr", errors);
            report.put("exit37", io.getInt("exitCode", -1));
            if (!io.getBoolean("stdio") || io.getInt("exitCode", -1) != 37 ||
                !output.equals(new String(INPUT, StandardCharsets.UTF_8)) || !errors.equals(ERROR))
                throw new IOException("BINDER_ADMISSION_STDIO_MISMATCH: " + bounded(io.toString()));
            report.put("status", "pass");
            stage.set("complete");
            exit = 0;
        } catch (Throwable error) {
            try { report.put("error", bounded(error.toString())); } catch (Exception ignored) { }
        } finally {
            if (boundConnection != null) {
                try { unbindService(boundConnection); }
                catch (Exception error) {
                    exit = 1;
                    try { report.put("status", "failed"); report.put("unbindError", bounded(error.toString())); }
                    catch (Exception ignored) { }
                }
            }
            close(stdin); close(stdout); close(stderr);
            try {
                report.put("stage", stage.get());
                report.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
            } catch (Exception ignored) { }
            printOnce(reported, report);
            finished.countDown();
        }
        // app_process owns a Binder pool; explicitly end this single-use client.
        System.exit(exit);
    }

    private static int packageUid(Object packages, String name, int user) throws Exception {
        for (Method method : Class.forName("android.content.pm.IPackageManager").getDeclaredMethods()) {
            if (!method.getName().equals("getPackageUid")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 3 || types[0] != String.class || types[2] != int.class) continue;
            if (types[1] == long.class) return (Integer) method.invoke(packages, name, 0L, user);
            if (types[1] == int.class) return (Integer) method.invoke(packages, name, 0, user);
        }
        throw new IOException("BINDER_ADMISSION_ANDROID_PACKAGE_INTERFACE_UNSUPPORTED");
    }

    private static int broadcast(Intent intent, int user) throws Exception {
        Object activity = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        for (Method method : Class.forName("android.app.IActivityManager").getDeclaredMethods()) {
            if (!method.getName().equals("broadcastIntentWithFeature")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 16 || types[2] != Intent.class || types[7] != Bundle.class ||
                types[8] != String[].class || types[9] != String[].class || types[10] != String[].class ||
                types[11] != int.class || types[12] != Bundle.class || types[13] != boolean.class ||
                types[14] != boolean.class || types[15] != int.class) continue;
            return (Integer) method.invoke(activity, null, null, intent, null, null, 0, null, null,
                null, null, null, -1, null, false, false, user);
        }
        throw new IOException("BINDER_ADMISSION_ANDROID_BROADCAST_INTERFACE_UNSUPPORTED");
    }

    private static IBinder peekService(Intent intent, JSONObject report) throws Exception {
        Object activity = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        Method method = Class.forName("android.app.IActivityManager")
            .getDeclaredMethod("peekService", Intent.class, String.class, String.class);
        report.put("peekInterface", method.toGenericString());
        return (IBinder) method.invoke(activity, intent, null, TEST_PACKAGE);
    }

    private static String startService(Intent intent, int user) throws Exception {
        Object activity = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        for (Method method : Class.forName("android.app.IActivityManager").getDeclaredMethods()) {
            if (!method.getName().equals("startService")) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 7 || types[1] != Intent.class || types[2] != String.class ||
                types[3] != boolean.class || types[4] != String.class || types[5] != String.class ||
                types[6] != int.class) continue;
            ComponentName started = (ComponentName) method.invoke(activity, null, intent, null, false,
                TEST_PACKAGE, null, user);
            if (!intent.getComponent().equals(started))
                throw new IOException("BINDER_ADMISSION_ORDINARY_SERVICE_REJECTED: " + started);
            return started.flattenToShortString();
        }
        throw new IOException("BINDER_ADMISSION_ANDROID_SERVICE_INTERFACE_UNSUPPORTED");
    }

    private static Object bindService(Intent intent, IBinder callback, int user, CountDownLatch ready,
                                      AtomicReference<Exception> failure, JSONObject report) throws Exception {
        Class<?> connectionType = Class.forName("android.app.IServiceConnection");
        Binder connection = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != FIRST_CALL_TRANSACTION || Binder.getCallingUid() != Process.SYSTEM_UID) return false;
                try {
                    data.enforceInterface("android.app.IServiceConnection");
                    ComponentName name = data.readTypedObject(ComponentName.CREATOR);
                    IBinder service = data.readStrongBinder();
                    boolean dead = data.readInt() != 0;
                    if (!intent.getComponent().equals(name) || dead || service == null || data.dataAvail() != 0)
                        throw new IOException("BINDER_ADMISSION_BOUND_SERVICE_INVALID");
                    Parcel request = Parcel.obtain(), response = Parcel.obtain();
                    try {
                        request.writeInterfaceToken(DISCOVERY);
                        request.writeStrongBinder(callback);
                        if (!service.transact(FIRST_CALL_TRANSACTION, request, response, 0))
                            throw new IOException("BINDER_ADMISSION_BOUND_DISCOVERY_UNHANDLED");
                        response.readException();
                    } finally { request.recycle(); response.recycle(); }
                } catch (Exception error) { failure.set(error); ready.countDown(); }
                return true;
            }
        };
        Object proxy = Proxy.newProxyInstance(connectionType.getClassLoader(), new Class<?>[] { connectionType },
            (instance, method, values) -> {
                if (method.getName().equals("asBinder")) return connection;
                throw new UnsupportedOperationException(method.getName());
            });
        Object activity = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        for (Method method : Class.forName("android.app.IActivityManager").getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals("bindService") || types.length != 8 || types[2] != Intent.class ||
                types[4] != connectionType || (types[5] != long.class && types[5] != int.class) ||
                types[6] != String.class || types[7] != int.class) continue;
            report.put("bindMethod", method.toGenericString());
            Object bindFlags;
            if (types[5] == long.class) bindFlags = Long.valueOf(Context.BIND_AUTO_CREATE);
            else bindFlags = Integer.valueOf(Context.BIND_AUTO_CREATE);
            int result;
            try {
                // A native su child has no framework ProcessRecord. Do not borrow a parent thread
                // or substitute a Manager identity just to make this prerequisite pass.
                result = (Integer) method.invoke(activity, null, null, intent, null, proxy, bindFlags, TEST_PACKAGE, user);
            } catch (java.lang.reflect.InvocationTargetException error) {
                if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
                throw error;
            }
            report.put("bindResult", result);
            if (result <= 0) throw new IOException("BINDER_ADMISSION_ORDINARY_BIND_REJECTED: " + result);
            return proxy;
        }
        throw new IOException("BINDER_ADMISSION_ANDROID_BIND_INTERFACE_UNSUPPORTED");
    }

    private static void unbindService(Object connection) throws Exception {
        Object activity = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        Class.forName("android.app.IActivityManager").getDeclaredMethod("unbindService",
            Class.forName("android.app.IServiceConnection")).invoke(activity, connection);
    }

    private static Bundle transact(IBinder root, int operation, ParcelFileDescriptor[] streams) throws Exception {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(ROOT);
            if (streams != null) for (ParcelFileDescriptor stream : streams) request.writeTypedObject(stream, 0);
            if (root == null || !root.transact(IBinder.FIRST_CALL_TRANSACTION + operation, request, reply, 0))
                throw new IOException("BINDER_ADMISSION_ROOT_TRANSACTION_UNHANDLED");
            reply.readException();
            Bundle result = reply.readTypedObject(Bundle.CREATOR);
            if (result == null) throw new IOException("BINDER_ADMISSION_ROOT_REPLY_MISSING");
            return result;
        } finally { request.recycle(); reply.recycle(); }
    }

    private static void verifyIdentity(Bundle value, int expectedPid) throws IOException {
        if (value.getInt("rootUid", -1) != 0 || value.getInt("rootPid", -1) <= 1 ||
            value.getInt("callingUid", -1) != Process.myUid() ||
            (expectedPid != -1 && value.getInt("rootPid", -1) != expectedPid))
            throw new IOException("BINDER_ADMISSION_ROOT_IDENTITY_MISMATCH: " + bounded(value.toString()));
    }

    private static void copyIdentity(JSONObject report, Bundle identity) throws Exception {
        report.put("rootPID", identity.getInt("rootPid", -1));
        report.put("rootUID", identity.getInt("rootUid", -1));
        report.put("callingUID", identity.getInt("callingUid", -1));
        report.put("rootContext", identity.getString("rootContext"));
        report.put("registryPresent", identity.getBoolean("registryPresent"));
    }

    private static String read(ParcelFileDescriptor descriptor) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[128];
            int length;
            while ((length = input.read(bytes)) != -1) {
                if (output.size() + length > 128) throw new IOException("BINDER_ADMISSION_OUTPUT_TOO_LARGE");
                output.write(bytes, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject emptyReport() {
        JSONObject value = new JSONObject();
        try {
            value.put("rootPID", -1); value.put("rootUID", -1); value.put("callingUID", -1);
            value.put("clientPID", Process.myPid()); value.put("clientUID", Process.myUid());
            value.put("stdout", ""); value.put("stderr", ""); value.put("exit37", -1);
            value.put("status", "failed"); value.put("error", "");
        } catch (Exception error) { throw new IllegalStateException(error); }
        return value;
    }

    private static String bounded(String value) { return value.length() <= 1000 ? value : value.substring(0, 1000); }

    private static void printOnce(AtomicBoolean reported, JSONObject report) {
        if (reported.compareAndSet(false, true)) { System.out.println(report); System.out.flush(); }
    }

    private static void close(ParcelFileDescriptor[] descriptors) {
        if (descriptors != null) for (ParcelFileDescriptor descriptor : descriptors)
            try { descriptor.close(); } catch (Exception ignored) { }
    }
}
