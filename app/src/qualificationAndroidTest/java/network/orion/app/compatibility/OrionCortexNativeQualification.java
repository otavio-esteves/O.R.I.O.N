package network.orion.app.compatibility;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Platform-only black-box probe of the minified :ai service and its kept JNI.
 * Never constructs a native runtime in MAIN and never supplies a usable model.
 * Original AIDL transaction order plus the negotiated 1.1 append avoids R8 names.
 */
final class OrionCortexNativeQualification {
    private static final String DESCRIPTOR = "network.orion.ai.api.IOrionCortex";
    private static final String SERVICE = "network.orion.ai.service.OrionCortexService";
    private static final int PROTOCOL = IBinder.FIRST_CALL_TRANSACTION;
    private static final int LOAD = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int CANCEL = IBinder.FIRST_CALL_TRANSACTION + 3;
    private static final int REQUEST_STATUS = IBinder.FIRST_CALL_TRANSACTION + 4;
    private static final int STATUS = IBinder.FIRST_CALL_TRANSACTION + 5;
    private static final int HEALTH = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int ISSUE_ID = IBinder.FIRST_CALL_TRANSACTION + 8;
    private static final int CAPABILITIES = IBinder.FIRST_CALL_TRANSACTION + 9;

    static Bundle run(Context context) throws Exception {
        final int mainPid = Process.myPid();
        ComponentName component = new ComponentName(context.getPackageName(), SERVICE);
        @SuppressWarnings("deprecation")
        ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
        check(!info.exported && (context.getPackageName() + ":ai").equals(info.processName),
                "Cortex must remain private in the AI process");
        AtomicReference<IBinder> remote = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                remote.set(service); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { remote.set(null); }
        };
        check(context.bindService(new Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE),
                "Private Cortex bind rejected");
        File corrupt = null;
        try {
            check(connected.await(10, TimeUnit.SECONDS), "Cortex bind timed out");
            IBinder service = remote.get();
            check(service != null && DESCRIPTOR.equals(service.getInterfaceDescriptor()), "Wrong Cortex descriptor");
            Bundle protocol = call(service, PROTOCOL, null);
            // Original 1.0 envelope/transaction: a legacy client must accept it.
            envelope(protocol, "status", "major", "minor");
            check("OK".equals(protocol.getString("status")) && protocol.getInt("major") == 1
                    && protocol.getInt("minor") == 1, "Wrong Cortex protocol");
            Bundle capabilities = call(service, CAPABILITIES, null);
            envelope(capabilities, "status", "capabilities");
            check("OK".equals(capabilities.getString("status"))
                    && "orion_issued_request_history_v1".equals(capabilities.getString("capabilities")),
                    "Cortex issued history unavailable");
            String retired = issue(service);
            cancel(service, retired);
            for (int index = 0; index < 96; index++) cancel(service, issue(service));
            Bundle duplicate = call(service, REQUEST_STATUS, data -> data.writeString(retired));
            envelope(duplicate, "status");
            check("DUPLICATE_REQUEST".equals(duplicate.getString("status")), "Retired R8 identity became executable");

            // The real hash passes transport integrity checks; invalid GGUF bytes
            // must reach JNI metadata validation after the actual native constructor.
            byte[] bytes = new byte[32];
            corrupt = File.createTempFile("orion_r8_native_probe_", ".bin", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(corrupt)) { output.write(bytes); output.getFD().sync(); }
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", value));
            String requestId = issue(service);
            Bundle specification = new Bundle();
            specification.putInt("schemaVersion", 1);
            specification.putString("requestId", requestId);
            specification.putString("modelId", hash.toString());
            specification.putString("sha256", hash.toString());
            specification.putLong("sizeBytes", bytes.length);
            specification.putInt("contextTokens", 64);
            specification.putInt("threads", 1);
            specification.putInt("bootCount", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT));
            specification.putLong("startedElapsed", SystemClock.elapsedRealtime());
            specification.putLong("durationMillis", 10_000L);
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(corrupt, ParcelFileDescriptor.MODE_READ_ONLY)) {
                Bundle accepted = call(service, LOAD, data -> {
                    data.writeTypedObject(descriptor, 0); data.writeTypedObject(specification, 0);
                });
                envelope(accepted, "status", "requestId");
                check("ACCEPTED".equals(accepted.getString("status")), "Corrupt fixture did not reach asynchronous native validation");
            }

            long deadline = SystemClock.elapsedRealtime() + 15_000;
            String terminal = "RUNNING";
            while ("RUNNING".equals(terminal) && SystemClock.elapsedRealtime() < deadline) {
                Bundle status = call(service, REQUEST_STATUS, data -> data.writeString(requestId));
                envelope(status, "status", "requestId", "state");
                check("OK".equals(status.getString("status")) && requestId.equals(status.getString("requestId")),
                        "Wrong native probe request receipt");
                terminal = status.getString("state");
                if ("RUNNING".equals(terminal)) Thread.sleep(25);
            }
            check("FAILED".equals(terminal), "Corrupt model was not rejected by native validation");
            Bundle health = call(service, HEALTH, null);
            envelope(health, "status", "nativeRuntimeLoaded", "nativeValidationCompleted", "processName", "pid",
                    "bootCount", "sampledElapsed", "totalPssBytes", "nativeIncluded");
            check("OK".equals(health.getString("status")) && Boolean.TRUE.equals(health.get("nativeRuntimeLoaded"))
                    && Boolean.TRUE.equals(health.get("nativeValidationCompleted")),
                    "Actual kept JNI constructor and validation did not complete in the AI process");
            check((context.getPackageName() + ":ai").equals(health.getString("processName"))
                    && health.getInt("pid") != mainPid && health.getInt("pid") > 0,
                    "Native validation did not run in a separate AI process");
            boolean unloaded = false;
            while (!unloaded && SystemClock.elapsedRealtime() < deadline) {
                Bundle status = call(service, STATUS, null);
                envelope(status, "status", "modelId");
                unloaded = "UNLOADED".equals(status.getString("status"));
                if (!unloaded) Thread.sleep(25);
            }
            check(unloaded && mainPid == Process.myPid() && service.isBinderAlive(),
                    "Native rejection did not preserve the Core and private service");
            Bundle result = new Bundle();
            result.putString("orion_cortex_r8_jni", "AI_PROCESS_CONSTRUCTOR_AND_NATIVE_REJECTION_PASSED");
            result.putString("orion_cortex_r8_model", "CORRUPT_SYNTHETIC_ONLY_NO_INFERENCE");
            return result;
        } finally {
            context.unbindService(connection);
            if (corrupt != null) check(corrupt.delete(), "Native probe temporary cleanup failed");
        }
    }

    private interface Writer { void write(Parcel data); }

    private static String issue(IBinder service) throws Exception {
        Bundle receipt = call(service, ISSUE_ID, null);
        envelope(receipt, "status", "requestId");
        String id = receipt.getString("requestId");
        check("OK".equals(receipt.getString("status")) && id != null && id.startsWith("orion_request/"),
                "Issued R8 identity unavailable");
        return id;
    }

    private static void cancel(IBinder service, String id) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(id);
            check(service.transact(CANCEL, data, reply, 0), "Cortex cancellation unavailable");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static Bundle call(IBinder service, int transaction, Writer writer) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) writer.write(data);
            check(data.dataSize() <= 16 * 1024, "Native probe request exceeds inline guard");
            check(service.transact(transaction, data, reply, 0), "Cortex transaction unavailable");
            check(reply.dataSize() <= 16 * 1024, "Native probe reply exceeds inline guard");
            reply.readException();
            Bundle result = reply.readTypedObject(Bundle.CREATOR);
            check(result != null, "Missing Cortex reply");
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }

    @SuppressWarnings("deprecation")
    private static void envelope(Bundle value, String... fields) {
        Set<String> allowed = new HashSet<>(Arrays.asList(fields));
        allowed.add("schemaVersion");
        check(allowed.containsAll(value.keySet()) && Integer.valueOf(1).equals(value.get("schemaVersion")),
                "Invalid Cortex reply envelope");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
