package network.orion.app.compatibility;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** One closed control operation. No UiAutomation, performance output, callbacks or model parameters. */
public final class OrionCortexInstrumentedWorkloadInstrumentation extends Instrumentation {
    private static final String CHECKPOINT = "orionWorkloadCheckpoint";
    private static final String STATUS = "orionRuntimeCaptureStatus";
    private static final String SOURCE = "orionRuntimeCaptureSource";
    private static final String[] FLAGS = {"sourceAuthenticated", "physicalEvidenceVerified", "real24HourDurationProven", "autonomyProven",
            "profileQualified", "productionInferenceEnabled", "fullF4GateClosed", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized"};
    private Bundle arguments;
    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        arguments = supplied == null ? new Bundle() : new Bundle(supplied);
        start();
    }
    @Override public void onStart() {
        Bundle result;
        int code = Activity.RESULT_OK;
        try {
            check(!isProfiling());
            Context target = getTargetContext();
            check("network.orion.qualification".equals(target.getPackageName()) &&
                    (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
            Class<?> bridge = Class.forName("network.orion.app.ai.OrionCortexInstrumentedWorkloadBridge", true, target.getClassLoader());
            Method run = bridge.getMethod("run", Instrumentation.class, Bundle.class);
            check(run.getReturnType() == Bundle.class);
            Object value;
            try { value = run.invoke(null, this, arguments); }
            catch (InvocationTargetException wrapped) { throw wrapped.getCause() == null ? wrapped : wrapped.getCause(); }
            check(value instanceof Bundle);
            result = (Bundle) value; framed(result);
        } catch (Throwable unavailable) {
            code = Activity.RESULT_CANCELED; result = unavailable();
        }
        arguments = null;
        // One final publication only. --no-restart is framework state, not an argument/permission here.
        finish(code, result);
    }
    private static void framed(Bundle result) throws Exception {
        check(result.keySet().equals(keys(CHECKPOINT, "stream")) || result.keySet().equals(keys(CHECKPOINT, "stream", STATUS)) ||
                result.keySet().equals(keys(CHECKPOINT, "stream", STATUS, SOURCE)));
        check("ORION_WORKLOAD_CHECKPOINT".equals(result.getString("stream")));
        Object raw = result.get(CHECKPOINT); check(raw instanceof String && ascii((String) raw, 16 * 1024));
        JSONObject value = new JSONObject((String) raw);
        Set<String> expected = keys("schemaVersion", "kind", "operation", "runId", "fixtureId", "attemptId", "sessionId", "stamp",
                "outcome", "refusal", "failureCode", "ownerCallInvoked", "executionState", "settlement", "resources", "observation", "resourceExit", "policyFacts",
                "modelSha256", "configurationSha256", "runtimeSourceSha256");
        expected.addAll(Arrays.asList(FLAGS)); check(jsonKeys(value).equals(expected));
        check(Integer.valueOf(2).equals(value.get("schemaVersion")) && "ORION_F5_INSTRUMENTED_CHECKPOINT".equals(value.get("kind")));
        for (String flag : FLAGS) check(Boolean.FALSE.equals(value.get(flag)));
        check(keys("OPEN", "BOOTSTRAP", "WORK", "OBSERVE", "CLEANUP").contains(value.getString("operation")));
        check(keys("IDLE", "ACTIVE", "UNCERTAIN").contains(value.getString("executionState")));
        check(keys("ORIGINAL_UNLOAD_CONFIRMED", "NO_OWNER_CALL", "UNPROVEN").contains(value.getString("settlement")));
        Object owner = value.get("ownerCallInvoked"); check(owner == JSONObject.NULL || owner instanceof Boolean);
        JSONObject stamp = value.getJSONObject("stamp");
        check(jsonKeys(stamp).equals(keys("bootCount", "deviceElapsedMillis", "deviceElapsedUpperMillis", "mainPid")));
        for (String key : new String[]{"bootCount", "deviceElapsedMillis", "deviceElapsedUpperMillis", "mainPid"}) {
            Object number = stamp.get(key); check((number instanceof Integer || number instanceof Long) && ((Number) number).longValue() >= 0);
        }
        check(stamp.getLong("mainPid") > 0 && stamp.getLong("deviceElapsedMillis") == stamp.getLong("deviceElapsedUpperMillis"));
        if (result.containsKey(STATUS)) check(result.get(STATUS) instanceof String && ascii(result.getString(STATUS), 1024));
        if (result.containsKey(SOURCE)) {
            check(result.get(SOURCE) instanceof String && ascii(result.getString(SOURCE), 96 * 1024));
            check(value.get("runtimeSourceSha256") instanceof String && value.getString("runtimeSourceSha256").matches("[0-9a-f]{64}"));
        } else check(value.isNull("runtimeSourceSha256"));
    }
    private static Bundle unavailable() {
        try {
            JSONObject value = new JSONObject().put("schemaVersion", 2).put("kind", "ORION_F5_WORKLOAD_RUNNER_FAILURE")
                    .put("qualification", "NOT_QUALIFIED").put("failureCode", "SOURCE_UNAVAILABLE");
            for (String flag : FLAGS) value.put(flag, false);
            Bundle result = new Bundle(); result.putString("orionWorkloadFailure", value.toString());
            result.putString("stream", "ORION_WORKLOAD_UNAVAILABLE"); return result;
        } catch (Throwable unavailable) { throw new IllegalStateException("WORKLOAD_UNAVAILABLE"); }
    }
    private static boolean ascii(String value, int limit) {
        if (value == null || value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return true;
    }
    private static Set<String> jsonKeys(JSONObject value) {
        Set<String> result = new HashSet<>(); Iterator<String> values = value.keys();
        while (values.hasNext()) result.add(values.next()); return result;
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void check(boolean value) { if (!value) throw new IllegalStateException("WORKLOAD_FRAME_UNAVAILABLE"); }
}
