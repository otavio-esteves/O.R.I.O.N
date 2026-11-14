package network.orion.app.compatibility;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Explicit measurement scope only: framework API runner, no model or qualification authority. */
public final class OrionPhysicalBaselineInstrumentation extends Instrumentation {
    private static final String TARGET = "network.orion.qualification";
    private static final String BRIDGE = "network.orion.app.resources.OrionPhysicalBaselineBridge";
    private static final String SUMMARY = "orionPhysicalBaselineSummary";
    private Bundle arguments;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        start();
    }

    @Override public void onStart() {
        int requested = 0;
        int interval = 0;
        final AtomicInteger received = new AtomicInteger();
        final AtomicBoolean accepting = new AtomicBoolean(true);
        final AtomicBoolean disconnected = new AtomicBoolean();
        final AtomicReference<IBinder> endpoint = new AtomicReference<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                endpoint.set(binder); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                disconnected.set(true); connected.countDown();
            }
            @Override public void onBindingDied(ComponentName name) {
                disconnected.set(true); connected.countDown();
            }
            @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
        };
        Context context = null;
        boolean bound = false;
        ExecutorService collector = null;
        Future<Bundle> collection = null;
        Bundle outcome = null;
        String failureCode = "ARGUMENTS_REJECTED";
        try {
            check(arguments.keySet().equals(new HashSet<>(Arrays.asList(
                    "orionEnvironment", "orionSampleCount", "orionSampleIntervalMs"))));
            check("PHYSICAL_DEVICE".equals(arguments.get("orionEnvironment")));
            requested = integerArgument("orionSampleCount", 1, 10);
            interval = integerArgument("orionSampleIntervalMs", 100, 1000);
            failureCode = "TARGET_IDENTITY_REJECTED";
            context = getTargetContext();
            check(TARGET.equals(context.getPackageName()));
            failureCode = "PHYSICAL_ENVIRONMENT_REJECTED";
            checkPhysicalBuild();
            failureCode = "TARGET_IDENTITY_REJECTED";
            Class<?> bridge = Class.forName(BRIDGE, true, context.getClassLoader());
            // Validate sample bounds and the actual complete manifest inventory before binding.
            bridge.getMethod("validate", Context.class, int.class, int.class).invoke(null, context, requested, interval);
            Method collect = bridge.getMethod("collect", Context.class, IBinder.class, int.class, int.class, ResultReceiver.class);
            failureCode = "BIND_FAILED";
            Intent intent = new Intent().setComponent(new ComponentName(TARGET, "network.orion.ai.service.OrionCortexService"));
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            check(bound && connected.await(5, TimeUnit.SECONDS) && endpoint.get() != null && !disconnected.get());
            final Context ownedContext = context;
            final int sampleCount = requested;
            final int sampleInterval = interval;
            final IBinder ownedEndpoint = endpoint.get();
            ResultReceiver receiver = new ResultReceiver(null) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (!accepting.get()) return;
                    check(code == 0 && data != null && data.size() == 1 && data.get("orionPhysicalBaselineSample") instanceof String);
                    Bundle selected = new Bundle();
                    selected.putString("orionPhysicalBaselineSample", data.getString("orionPhysicalBaselineSample"));
                    sendStatus(2, selected);
                    received.incrementAndGet();
                }
            };
            collector = Executors.newSingleThreadExecutor();
            failureCode = "OBSERVATION_FAILED";
            collection = collector.submit(() -> (Bundle) collect.invoke(null,
                    ownedContext, ownedEndpoint, sampleCount, sampleInterval, receiver));
            outcome = collection.get(30, TimeUnit.SECONDS);
            check(outcome != null && outcome.size() == 1 && outcome.get(SUMMARY) instanceof String);
            if (disconnected.get()) outcome = summary(requested, received.get(), interval, "INCOMPLETE", "SERVICE_DISCONNECTED");
        } catch (TimeoutException timeout) {
            outcome = summary(requested, received.get(), interval, "INCOMPLETE", "OBSERVATION_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            outcome = summary(requested, received.get(), interval, bound ? "INCOMPLETE" : "REJECTED", "INTERRUPTED");
        } catch (Throwable failure) {
            // No exception messages, stack traces, file paths or user content leave the probe.
            outcome = summary(requested, received.get(), interval, bound ? "INCOMPLETE" : "REJECTED", failureCode);
        } finally {
            accepting.set(false);
            if (collection != null && !collection.isDone()) collection.cancel(true);
            if (collector != null) collector.shutdownNow();
            if (bound) {
                try { context.unbindService(connection); }
                catch (Throwable failure) { outcome = summary(requested, received.get(), interval, "INCOMPLETE", "CLEANUP_FAILED"); }
            }
        }
        boolean complete = false;
        try { complete = "OBSERVATION_COMPLETE_REVIEW_REQUIRED".equals(new JSONObject(outcome.getString(SUMMARY)).getString("result")); }
        catch (Throwable failure) { outcome = summary(requested, received.get(), interval, "INCOMPLETE", "OBSERVATION_FAILED"); }
        outcome.putString("stream", "\nMEASUREMENT_ONLY / NOT_QUALIFIED\n");
        finish(complete ? Activity.RESULT_OK : Activity.RESULT_CANCELED, outcome);
    }

    private int integerArgument(String key, int minimum, int maximum) {
        Object value = arguments.get(key);
        check(value instanceof String && ((String) value).matches("[0-9]{1,4}"));
        int parsed = Integer.parseInt((String) value);
        check(parsed >= minimum && parsed <= maximum);
        return parsed;
    }

    private static void checkPhysicalBuild() {
        String identity = (Build.FINGERPRINT + " " + Build.HARDWARE + " " + Build.PRODUCT + " " + Build.DEVICE).toLowerCase(Locale.ROOT);
        check(Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT <= 37 && "aarch64".equals(System.getProperty("os.arch")));
        for (String marker : new String[] { "emulator", "qemu", "ranchu", "goldfish", "cuttlefish", "sdk_gphone", "generic", "aosp_cf" })
            check(!identity.contains(marker));
        check(Build.FINGERPRINT != null && Build.FINGERPRINT.length() >= 10 && Build.FINGERPRINT.length() <= 512);
    }

    private static Bundle summary(int requested, int collected, int interval, String result, String failure) {
        try {
            JSONObject value = new JSONObject();
            value.put("schemaVersion", 1); value.put("kind", "PHYSICAL_BASELINE_SUMMARY");
            value.put("environment", "PHYSICAL_DEVICE"); value.put("qualification", "NOT_QUALIFIED"); value.put("packageName", TARGET);
            value.put("result", result); value.put("samplesRequested", requested); value.put("samplesCollected", collected);
            value.put("sampleIntervalMillis", interval); value.put("profileQualified", false); value.put("productionInferenceEnabled", false);
            value.put("fullF5GateClosed", false); value.put("fullSoakGateClosed", false); value.put("heavyWorkIssued", false);
            value.put("failureCode", failure == null ? JSONObject.NULL : failure);
            Bundle data = new Bundle(); data.putString(SUMMARY, value.toString()); return data;
        } catch (Throwable impossible) { throw new IllegalStateException("SUMMARY_UNAVAILABLE"); }
    }

    private static void check(boolean condition) {
        if (!condition) throw new IllegalStateException("BASELINE_CHECK_FAILED");
    }
}
