package network.orion.app.compatibility;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;

/** Framework-only runner for a read-only pre-resource query, never an inference request. */
public final class OrionCortexReadinessInstrumentation extends Instrumentation {
    private static final String TARGET = "network.orion.qualification";
    private static final String KEY = "orionCortexReadinessReceipt";
    private Bundle arguments;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        start();
    }

    @Override public void onStart() {
        ExecutorService executor = null;
        Future<Bundle> pending = null;
        Bundle outcome;
        boolean observed = false;
        String failureCode = "ARGUMENTS_REJECTED";
        try {
            check(arguments.keySet().equals(new HashSet<>(Arrays.asList("orionEnvironment", "orionCoreWaitMillis"))));
            Object declared = arguments.get("orionEnvironment");
            check("PHYSICAL_DEVICE".equals(declared) || "ANDROID_EMULATOR".equals(declared));
            Object wait = arguments.get("orionCoreWaitMillis");
            check(wait instanceof String && ((String) wait).matches("[0-9]{1,5}"));
            int waitMillis = Integer.parseInt((String) wait);
            check(waitMillis >= 0 && waitMillis <= 10_000);
            failureCode = "TARGET_IDENTITY_REJECTED";
            Context context = getTargetContext();
            check(TARGET.equals(context.getPackageName()));
            failureCode = "BRIDGE_UNAVAILABLE";
            Class<?> bridge = Class.forName("network.orion.app.ai.OrionCortexReadinessBridge", true, context.getClassLoader());
            java.lang.reflect.Method collect = bridge.getMethod("collect", Context.class, String.class, int.class);
            executor = Executors.newSingleThreadExecutor();
            pending = executor.submit(() -> (Bundle) collect.invoke(null, context, (String) declared, waitMillis));
            failureCode = "RECEIPT_INVALID";
            outcome = pending.get(15, TimeUnit.SECONDS);
            check(outcome != null && outcome.size() == 1 && outcome.get(KEY) instanceof String);
            String encoded = outcome.getString(KEY);
            check(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8 * 1024);
            JSONObject receipt = new JSONObject(encoded);
            check(receipt.getInt("schemaVersion") == 1 && "CORTEX_READINESS_RECEIPT".equals(receipt.getString("kind")) &&
                    TARGET.equals(receipt.getString("packageName")) && "NOT_QUALIFIED".equals(receipt.getString("qualification")));
            observed = "OBSERVED".equals(receipt.getString("result"));
        } catch (TimeoutException timeout) {
            outcome = failure("OBSERVATION_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome = failure("INTERRUPTED");
        } catch (Throwable rejected) {
            // No raw argument, Build value, path or exception detail leaves the runner.
            outcome = failure(failureCode);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (executor != null) executor.shutdownNow();
        }
        outcome.putString("stream", "\nREADINESS_ONLY / NOT_QUALIFIED\n");
        finish(observed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, outcome);
    }

    private static Bundle failure(String code) {
        try {
            JSONObject receipt = new JSONObject();
            receipt.put("schemaVersion", 1); receipt.put("kind", "CORTEX_READINESS_RUNNER_FAILURE");
            receipt.put("packageName", TARGET); receipt.put("environment", "UNVERIFIED");
            receipt.put("qualification", "NOT_QUALIFIED"); receipt.put("result", "UNAVAILABLE"); receipt.put("failureCode", code);
            receipt.put("physicalDeviceVerified", false); receipt.put("provenanceAuthenticated", false);
            receipt.put("admissionGranted", false); receipt.put("runtimeRequestsIssued", false);
            receipt.put("probeIpcBindIssued", false); receipt.put("heavyWorkIssued", false); receipt.put("profileQualified", false);
            receipt.put("productionInferenceEnabled", false); receipt.put("fullF5GateClosed", false); receipt.put("fullSoakGateClosed", false);
            Bundle data = new Bundle(); data.putString(KEY, receipt.toString()); return data;
        } catch (Throwable impossible) { throw new IllegalStateException("RECEIPT_UNAVAILABLE"); }
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("READINESS_CHECK_FAILED");
    }
}
