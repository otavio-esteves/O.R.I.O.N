package network.orion.app.compatibility;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Framework-only opt-in runner. One flags0 binding; an absent AI is never started. */
public final class OrionExitEvidenceCaptureInstrumentation extends Instrumentation {
    private static final String TARGET = "network.orion.qualification";
    private static final String BRIDGE = "network.orion.app.resources.OrionExitEvidenceCaptureBridge";
    private static final String RECEIPT = "orionExitCaptureReceipt";
    private Bundle arguments;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        start();
    }

    @Override public void onStart() {
        final AtomicReference<IBinder> endpoint = new AtomicReference<>();
        final AtomicBoolean acceptingConnection = new AtomicBoolean(true);
        final CancellationSignal liveEnded = new CancellationSignal();
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (acceptingConnection.compareAndSet(true, false)) endpoint.set(binder);
                else liveEnded.cancel();
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { liveEnded.cancel(); connected.countDown(); }
            @Override public void onBindingDied(ComponentName name) { liveEnded.cancel(); connected.countDown(); }
            @Override public void onNullBinding(ComponentName name) { liveEnded.cancel(); connected.countDown(); }
        };
        Context context = null;
        boolean bindAttempted = false;
        ExecutorService worker = null;
        Future<Bundle> pending = null;
        CancellationSignal cancellation = new CancellationSignal();
        Bundle outcome = null;
        String failureCode = "ARGUMENTS_REJECTED";
        String cleanup = "NO_BIND_ATTEMPT";
        try {
            check(arguments.keySet().equals(new HashSet<>(Arrays.asList(
                    "orionEnvironment", "orionObservationCount", "orionObservationIntervalMs", "orionHistoryLimit"))));
            Object declared = arguments.get("orionEnvironment");
            check(declared instanceof String && ("PHYSICAL_DEVICE".equals(declared) || "ANDROID_EMULATOR".equals(declared)));
            String environment = (String) declared;
            int count = integerArgument("orionObservationCount", 1, 10);
            int interval = integerArgument("orionObservationIntervalMs", 100, 1000);
            int limit = integerArgument("orionHistoryLimit", 1, 16);
            failureCode = "TARGET_IDENTITY_REJECTED";
            context = getTargetContext();
            check(TARGET.equals(context.getPackageName()) && (TARGET + ".test").equals(getContext().getPackageName()));
            Class<?> bridge = Class.forName(BRIDGE, true, context.getClassLoader());
            failureCode = "ENVIRONMENT_OR_PLATFORM_REJECTED";
            bridge.getMethod("validate", Context.class, String.class, int.class, int.class, int.class)
                    .invoke(null, context, environment, count, interval, limit);
            Method collect = bridge.getMethod("collect", Context.class, IBinder.class, String.class, String.class,
                    int.class, int.class, int.class, CancellationSignal.class, CancellationSignal.class);
            failureCode = "BIND_UNAVAILABLE";
            Intent intent = new Intent().setComponent(new ComponentName(TARGET, "network.orion.ai.service.OrionCortexService"));
            bindAttempted = true;
            // Deliberately no AUTO_CREATE, no startService and no second binding.
            boolean bound = context.bindService(intent, connection, 0);
            boolean timedOut = bound && !connected.await(5, TimeUnit.SECONDS);
            if (timedOut) {
                // No late callback may replace this explicit unavailable endpoint.
                acceptingConnection.set(false);
                endpoint.set(null);
            }
            acceptingConnection.set(false);
            final IBinder pinnedEndpoint = bound ? endpoint.get() : null;
            final String bindingOutcome = !bound ? "NOT_BOUND" : timedOut ? "CONNECTION_TIMEOUT" :
                    pinnedEndpoint == null ? "NO_ENDPOINT" : "CONNECTED";
            final Context ownedContext = context;
            worker = Executors.newSingleThreadExecutor();
            failureCode = "CAPTURE_FAILED";
            pending = worker.submit(() -> (Bundle) collect.invoke(null,
                    ownedContext, pinnedEndpoint, environment, bindingOutcome, count, interval, limit, cancellation, liveEnded));
            outcome = pending.get(30, TimeUnit.SECONDS);
            check(outcome != null && outcome.size() == 1 && outcome.get(RECEIPT) instanceof String);
        } catch (TimeoutException timeout) {
            outcome = rejected("CAPTURE_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome = rejected("INTERRUPTED");
        } catch (Throwable failure) {
            outcome = rejected(failureCode);
        } finally {
            acceptingConnection.set(false);
            cancellation.cancel();
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (worker != null) worker.shutdownNow();
            if (bindAttempted) {
                try { context.unbindService(connection); cleanup = "UNBOUND"; }
                catch (IllegalArgumentException notRegistered) { cleanup = "NOT_REGISTERED"; }
                catch (Throwable failure) { cleanup = "FAILED"; }
            }
        }
        boolean captured = false;
        try {
            JSONObject value = new JSONObject(outcome.getString(RECEIPT));
            value.put("cleanup", cleanup);
            outcome.putString(RECEIPT, value.toString());
            captured = "ORION_F5_EXIT_CAPTURE_ONLY".equals(value.getString("kind")) && !"FAILED".equals(cleanup)
                    && !"FAILED".equals(value.optString("binderDeathCleanup"));
        } catch (Throwable failure) {
            outcome = rejected("CAPTURE_FAILED", cleanup);
        }
        outcome.putString("stream", "\nCAPTURE_ONLY / NON_DEFINITIVE / NOT_QUALIFIED\n");
        finish(captured ? Activity.RESULT_OK : Activity.RESULT_CANCELED, outcome);
    }

    private int integerArgument(String key, int minimum, int maximum) {
        Object raw = arguments.get(key);
        check(raw instanceof String && ((String) raw).matches("[0-9]{1,4}"));
        int value = Integer.parseInt((String) raw);
        check(value >= minimum && value <= maximum);
        return value;
    }

    private static Bundle rejected(String code) {
        return rejected(code, "NO_BIND_ATTEMPT");
    }

    private static Bundle rejected(String code, String cleanup) {
        try {
            JSONObject value = new JSONObject();
            value.put("schemaVersion", 1); value.put("kind", "ORION_F5_EXIT_CAPTURE_REJECTION");
            value.put("environment", "UNVERIFIED"); value.put("qualification", "NOT_QUALIFIED");
            value.put("runtimeEvidence", "NON_DEFINITIVE"); value.put("result", "REJECTED");
            value.put("failureCode", code); value.put("cleanup", cleanup);
            for (String flag : new String[] { "provenanceAuthenticated", "physicalDeviceVerified", "memoryLimiterVerified",
                    "profileQualified", "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed", "heavyWorkIssued",
                    "policyMutationIssued" }) value.put(flag, false);
            Bundle result = new Bundle(); result.putString(RECEIPT, value.toString()); return result;
        } catch (Throwable impossible) { throw new IllegalStateException("RECEIPT_UNAVAILABLE"); }
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("CAPTURE_CHECK_FAILED");
    }
}
