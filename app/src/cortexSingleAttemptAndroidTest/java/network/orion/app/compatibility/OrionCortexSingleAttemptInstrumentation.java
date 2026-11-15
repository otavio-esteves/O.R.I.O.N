package network.orion.app.compatibility;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Parcel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** Framework-only single program; no configuration input, target DTO reflection or worker. */
public final class OrionCortexSingleAttemptInstrumentation extends Instrumentation {
    private static final String TARGET = "network.orion.qualification";
    private static final String BRIDGE = "network.orion.app.ai.OrionCortexSingleAttemptBridge";
    private static final String HEADER = "orionSingleAttemptStatus";
    private static final String PARTIAL = "orionSingleAttemptPartial";
    private static final String RELEASE = "orionSingleAttemptReleaseStatus";
    private static final String[] FLAGS = { "sourceAuthenticated", "physicalVerified", "profileApproved",
            "productionInferenceApproved", "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized" };
    private static final Set<String> HEADER_KEYS = keys("schemaVersion", "kind", "qualification", "phase", "failureCode", "refusal",
            "environment", "environmentSource", "counterScope", "prepareCalls", "executeCalls", "readCalls", "releaseCalls", "ownerCallInvoked", "partialPresent",
            "sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved", "runtimeEvidenceV1Complete",
            "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized");
    private static final Set<String> REFUSALS = keys("OWNER_UNAVAILABLE", "CLOSED", "NO_QUALIFIED_PROFILE", "RESOURCE_CONFIGURATION_UNAVAILABLE",
            "CORE_NOT_READY", "OBSERVER_UNAVAILABLE", "TEMPLATE_UNAVAILABLE", "TEMPLATE_INVALID", "MODEL_NOT_READY", "PROFILE_MISMATCH",
            "ALREADY_CONSUMED", "SELECTION_UNAVAILABLE");
    private static final Set<String> PHASES = keys("BRIDGE_REFUSED", "BRIDGE_UNAVAILABLE", "PREPARE_REFUSED", "SELECTED",
            "EXECUTE_RETURNED_METADATA_UNAVAILABLE", "EXECUTE_INVOKED", "EXECUTE_NOT_ATTEMPTED", "OBSERVED",
            "OBSERVATION_UNAVAILABLE", "RELEASED", "RELEASE_NOT_AVAILABLE");
    private Bundle arguments;

    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        start();
    }

    @Override public void onStart() {
        Bundle outcome;
        Context context = null;
        Class<?> bridge = null;
        boolean selected = false;
        boolean executeIssued = false;
        boolean releaseIssued = false;
        boolean observed = false;
        boolean notAttempted = false;
        String failureCode = "ARGUMENTS_REJECTED";
        try {
            check(arguments.isEmpty());
            context = getTargetContext();
            check(TARGET.equals(context.getPackageName()) && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
            failureCode = "BRIDGE_UNAVAILABLE";
            bridge = Class.forName(BRIDGE, true, context.getClassLoader());
            failureCode = "PREPARE_FAILED";
            Bundle prepared = invoke(bridge, "prepare", context);
            JSONObject initial = status(prepared);
            counts(initial, 1, 0, 0, 0);
            if ("PREPARE_REFUSED".equals(text(initial, "phase"))) {
                check(REFUSALS.contains(text(initial, "refusal")) && initial.isNull("ownerCallInvoked") && !prepared.containsKey(PARTIAL));
                outcome = prepared; // No execute, read, release, request or synthetic receipt follows this refusal.
                notAttempted = true;
            } else {
                check("SELECTED".equals(text(initial, "phase")) && initial.isNull("refusal") && !prepared.containsKey(PARTIAL));
                selected = true;
                failureCode = "EXECUTE_FAILED";
                executeIssued = true;
                Bundle executed = invoke(bridge, "execute", context);
                JSONObject execution = status(executed);
                counts(execution, 1, 1, 0, 0);
                if ("EXECUTE_NOT_ATTEMPTED".equals(text(execution, "phase"))) {
                    check(Boolean.FALSE.equals(execution.get("ownerCallInvoked")) && REFUSALS.contains(text(execution, "refusal")));
                    outcome = executed; // Driver reservation cleanup is already owned by127; no cycle exists to read.
                    notAttempted = true;
                } else {
                    check("EXECUTE_INVOKED".equals(text(execution, "phase")) && Boolean.TRUE.equals(execution.get("ownerCallInvoked")));
                    failureCode = "OBSERVE_FAILED";
                    // The original execute reflection call has returned normally before any manual source read.
                    outcome = invoke(bridge, "observe", context);
                    JSONObject observation = status(outcome);
                    check(("OBSERVED".equals(text(observation, "phase")) || "OBSERVATION_UNAVAILABLE".equals(text(observation, "phase")))
                            && Boolean.TRUE.equals(observation.get("ownerCallInvoked")));
                    check(integer(observation, "prepareCalls") == 1 && integer(observation, "executeCalls") == 1
                            && integer(observation, "releaseCalls") == 0);
                    failureCode = "RELEASE_FAILED";
                    releaseIssued = true;
                    Bundle released = invoke(bridge, "release", context);
                    JSONObject control = status(released);
                    check("RELEASED".equals(text(control, "phase")) || "RELEASE_NOT_AVAILABLE".equals(text(control, "phase")));
                    check(integer(control, "releaseCalls") == 1 && !released.containsKey(PARTIAL));
                    // Keep the observe pair unchanged. Release is a separate technical control header.
                    outcome.putString(RELEASE, released.getString(HEADER));
                }
            }
            observed = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); outcome = failure("INTERRUPTED");
        } catch (Throwable rejected) {
            // Never serialize reflection/native/callback errors, classes, messages or paths.
            outcome = failure(failureCode);
        }
        if (!observed && selected && executeIssued && !releaseIssued && bridge != null && context != null) {
            try {
                Bundle released = invoke(bridge, "release", context);
                JSONObject control = status(released);
                if ("RELEASED".equals(text(control, "phase")) || "RELEASE_NOT_AVAILABLE".equals(text(control, "phase")))
                    outcome.putString(RELEASE, released.getString(HEADER));
            } catch (Throwable ignored) { /* Technical release failure cannot replace the original runner failure. */ }
        }
        try {
            outcome.putString("stream", !observed ? "\nSINGLE_ATTEMPT_UNAVAILABLE / NOT_QUALIFIED\n"
                    : notAttempted ? "\nDRIVER_NOT_ATTEMPTED / NOT_QUALIFIED\n" : "\nSINGLE_ATTEMPT_PARTIAL / NOT_QUALIFIED\n");
            framed(outcome);
        } catch (Throwable rejected) {
            observed = false; outcome = failure("FRAMING_INVALID");
            outcome.putString("stream", "\nSINGLE_ATTEMPT_UNAVAILABLE / NOT_QUALIFIED\n");
        }
        finish(observed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, outcome);
    }

    private static Bundle invoke(Class<?> bridge, String phase, Context context) throws Throwable {
        Method method = bridge.getMethod(phase, Context.class);
        check(method.getReturnType() == Bundle.class);
        Object result;
        try { result = method.invoke(null, context); }
        catch (InvocationTargetException wrapped) {
            Throwable original = wrapped.getCause();
            if (original != null) throw original;
            throw wrapped;
        }
        check(result instanceof Bundle);
        Bundle bundle = (Bundle) result;
        check(bundle.keySet().equals(keys(HEADER)) || bundle.keySet().equals(keys(HEADER, PARTIAL)));
        return bundle;
    }

    private static JSONObject status(Bundle bundle) throws Exception {
        Object header = bundle.get(HEADER);
        check(header instanceof String && bytes((String) header) <= 1024);
        JSONObject value = new JSONObject((String) header);
        check(jsonKeys(value).equals(HEADER_KEYS) && integer(value, "schemaVersion") == 1
                && "CORTEX_SINGLE_ATTEMPT_STATUS".equals(text(value, "kind")) && "NOT_QUALIFIED".equals(text(value, "qualification")));
        check(keys("ANDROID_EMULATOR", "PHYSICAL_DEVICE", "UNVERIFIED").contains(text(value, "environment"))
                && "BUILD_SIGNALS_ONLY".equals(text(value, "environmentSource")) && "DRIVER_METHOD_CALLS".equals(text(value, "counterScope"))
                && PHASES.contains(text(value, "phase")));
        for (String flag : FLAGS) check(Boolean.FALSE.equals(value.get(flag)));
        for (String count : new String[] { "prepareCalls", "executeCalls", "readCalls", "releaseCalls" })
            check(integer(value, count) >= 0 && integer(value, count) <= 1);
        check(value.isNull("ownerCallInvoked") || value.get("ownerCallInvoked") instanceof Boolean);
        check(value.isNull("refusal") || REFUSALS.contains(text(value, "refusal")));
        check(value.isNull("failureCode") || keys("ALREADY_USED", "PHASE_ORDER", "OWNER_UNAVAILABLE", "METADATA_UNAVAILABLE",
                "OBSERVATION_UNAVAILABLE", "RELEASE_UNAVAILABLE").contains(text(value, "failureCode")));
        check(value.get("partialPresent") instanceof Boolean && value.getBoolean("partialPresent") == bundle.containsKey(PARTIAL));
        if (bundle.containsKey(PARTIAL)) {
            Object partial = bundle.get(PARTIAL);
            check(partial instanceof String && bytes((String) partial) <= 8192 && "OBSERVED".equals(text(value, "phase")));
            JSONObject projected = new JSONObject((String) partial);
            check(integer(projected, "schemaVersion") == 1 && "ORION_MAIN_CYCLE_PARTIAL".equals(text(projected, "format"))
                    && "NOT_QUALIFIED".equals(text(projected, "qualification")));
            for (String flag : new String[] { "sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved",
                    "runtimeProtocolV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized", "identifiersRetained", "timestampsRetained" })
                check(Boolean.FALSE.equals(projected.get(flag)));
        }
        return value;
    }

    private static void counts(JSONObject value, int prepare, int execute, int read, int release) throws Exception {
        check(integer(value, "prepareCalls") == prepare && integer(value, "executeCalls") == execute
                && integer(value, "readCalls") == read && integer(value, "releaseCalls") == release);
    }

    private static Bundle failure(String code) {
        try {
            JSONObject value = new JSONObject();
            value.put("schemaVersion", 1); value.put("kind", "CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE");
            value.put("qualification", "NOT_QUALIFIED"); value.put("phase", "RUNNER_FAILED"); value.put("failureCode", code);
            value.put("environment", "UNVERIFIED"); value.put("ownerCallInvoked", JSONObject.NULL);
            for (String flag : FLAGS) value.put(flag, false);
            Bundle result = new Bundle(); result.putString(HEADER, value.toString()); return result;
        } catch (Throwable unavailable) { throw new IllegalStateException("RECEIPT_UNAVAILABLE"); }
    }

    /** UTF-8 textual payload and UTF-16 Parcel are distinct bounds, checked only after functional return. */
    private static void framed(Bundle bundle) {
        check(bundle.keySet().contains(HEADER) && keys(HEADER, PARTIAL, RELEASE, "stream").containsAll(bundle.keySet()));
        int total = 0;
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key); check(value instanceof String);
            total += bytes(key) + bytes((String) value);
        }
        check(total <= 10 * 1024);
        Parcel parcel = Parcel.obtain();
        try { parcel.writeBundle(bundle); check(parcel.dataSize() <= 24 * 1024); } finally { parcel.recycle(); }
    }

    private static Set<String> jsonKeys(JSONObject value) {
        Set<String> result = new HashSet<>();
        Iterator<String> names = value.keys();
        while (names.hasNext()) result.add(names.next());
        return result;
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static int integer(JSONObject value, String key) throws Exception { Object item = value.get(key); check(item instanceof Integer); return (Integer) item; }
    private static String text(JSONObject value, String key) throws Exception { Object item = value.get(key); check(item instanceof String); return (String) item; }
    private static void check(boolean value) { if (!value) throw new IllegalStateException("SINGLE_ATTEMPT_CHECK_FAILED"); }
}
