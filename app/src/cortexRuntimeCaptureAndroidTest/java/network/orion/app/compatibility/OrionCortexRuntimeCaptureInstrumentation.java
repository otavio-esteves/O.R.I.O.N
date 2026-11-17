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

/** One framework-only program/result. No arguments, performance/status additions, profiling or UI automation. */
public final class OrionCortexRuntimeCaptureInstrumentation extends Instrumentation {
    private static final String STATUS = "orionRuntimeCaptureStatus";
    private static final String SOURCE = "orionRuntimeCaptureSource";
    private static final String[] FLAGS = {"sourceAuthenticated", "physicalVerified", "profileApproved",
            "productionInferenceApproved", "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized"};
    private static final Set<String> KEYS = keys("schemaVersion", "kind", "qualification", "recordState", "captureIntegrity", "failureCode",
            "ownerCallInvoked", "droppedAtLeast", "recordingFailuresAtLeast", "lossAccountingComplete", "sourceAuthenticated",
            "physicalVerified", "profileApproved", "productionInferenceApproved", "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized");
    private boolean emptyArguments;
    @Override public void onCreate(Bundle supplied) {
        super.onCreate(supplied);
        try { emptyArguments = supplied == null || supplied.isEmpty(); }
        catch (Throwable rejected) { emptyArguments = false; }
        start();
    }
    @Override public void onStart() {
        Bundle outcome;
        int code = Activity.RESULT_OK;
        try {
            check(emptyArguments && !isProfiling());
            Context target = getTargetContext();
            check("network.orion.qualification".equals(target.getPackageName()) &&
                    (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
            Class<?> bridge = Class.forName("network.orion.app.ai.OrionCortexRuntimeCaptureBridge", true, target.getClassLoader());
            Method run = bridge.getMethod("run", Instrumentation.class);
            check(run.getReturnType() == Bundle.class);
            Object value;
            try { value = run.invoke(null, this); }
            catch (InvocationTargetException wrapped) { throw wrapped.getCause() == null ? wrapped : wrapped.getCause(); }
            check(value instanceof Bundle);
            outcome = (Bundle) value;
            framed(outcome);
        } catch (Throwable unavailable) {
            code = Activity.RESULT_CANCELED;
            outcome = unavailable();
        }
        // One final call. No addResults/sendStatus/performance snapshot/UiAutomation or later Bundle mutation.
        finish(code, outcome);
    }
    private static void framed(Bundle bundle) throws Exception {
        check(bundle.keySet().equals(keys(STATUS, "stream")) || bundle.keySet().equals(keys(STATUS, SOURCE, "stream")));
        Object raw = bundle.get(STATUS); check(raw instanceof String && ascii((String) raw, 1024));
        JSONObject status = new JSONObject((String) raw);
        check(jsonKeys(status).equals(KEYS) && Integer.valueOf(1).equals(status.get("schemaVersion")) &&
                "ORION_RUNTIME_CAPTURE_STATUS".equals(status.get("kind")) && "NOT_QUALIFIED".equals(status.get("qualification")));
        for (String flag : FLAGS) check(Boolean.FALSE.equals(status.get(flag)));
        check(status.get("lossAccountingComplete") instanceof Boolean);
        for (String count : new String[]{"droppedAtLeast", "recordingFailuresAtLeast"}) {
            Object number = status.get(count); check((number instanceof Integer || number instanceof Long) && ((Number)number).longValue() >= 0);
        }
        Object owner = status.get("ownerCallInvoked"); check(owner == JSONObject.NULL || owner instanceof Boolean);
        String state = status.getString("recordState");
        check(keys("NO_LOAD_REFUSAL", "NO_V1_RECORD", "V1_SOURCE").contains(state));
        check(keys("COMPLETE", "INCOMPLETE", "UNAVAILABLE").contains(status.getString("captureIntegrity")));
        Object failure = status.get("failureCode");
        check(failure == JSONObject.NULL || failure instanceof String && keys("NO_QUALIFIED_PROFILE", "CONFIGURATION_UNAVAILABLE",
                "SOURCE_UNAVAILABLE", "IDENTITY_UNAVAILABLE", "IDENTITY_CHANGED", "CLOCK_UNAVAILABLE", "CLOCK_DISCONTINUOUS",
                "LOAD_TERMINAL_UNAVAILABLE", "INFERENCE_EVENT_UNAVAILABLE", "UNLOAD_UNCONFIRMED", "PHASE_MISSED", "CAPTURE_LOSS",
                "CAPTURE_LIMIT", "EXPORT_TOO_LARGE", "FRAMING_INVALID", "TRANSPORT_UNAVAILABLE", "INTERRUPTED").contains(failure));
        Object stream = bundle.get("stream"); check(stream instanceof String);
        if ("V1_SOURCE".equals(state)) {
            check(Boolean.TRUE.equals(owner) && bundle.containsKey(SOURCE) && "ORION_RUNTIME_SOURCE".equals(stream));
            Object source = bundle.get(SOURCE); check(source instanceof String && ascii((String)source, 96 * 1024));
        } else {
            check(!bundle.containsKey(SOURCE));
            if ("NO_LOAD_REFUSAL".equals(state)) check(Boolean.FALSE.equals(owner) && "ORION_RUNTIME_REFUSED".equals(stream));
            else check("ORION_RUNTIME_UNAVAILABLE".equals(stream));
        }
    }
    private static Bundle unavailable() {
        try {
            JSONObject status = new JSONObject();
            status.put("schemaVersion", 1); status.put("kind", "ORION_RUNTIME_CAPTURE_STATUS"); status.put("qualification", "NOT_QUALIFIED");
            status.put("recordState", "NO_V1_RECORD"); status.put("captureIntegrity", "UNAVAILABLE"); status.put("failureCode", "SOURCE_UNAVAILABLE");
            status.put("ownerCallInvoked", JSONObject.NULL); status.put("droppedAtLeast", 0L); status.put("recordingFailuresAtLeast", 0L);
            status.put("lossAccountingComplete", false);
            for (String flag : FLAGS) status.put(flag, false);
            Bundle result = new Bundle(); result.putString(STATUS, status.toString()); result.putString("stream", "ORION_RUNTIME_UNAVAILABLE");
            return result;
        } catch (Throwable unavailable) { throw new IllegalStateException("RUNTIME_CAPTURE_UNAVAILABLE"); }
    }
    private static boolean ascii(String value, int bound) {
        if (value.length() > bound) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return true;
    }
    private static Set<String> jsonKeys(JSONObject value) {
        Set<String> result = new HashSet<>(); Iterator<String> names = value.keys();
        while (names.hasNext()) result.add(names.next()); return result;
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void check(boolean value) { if (!value) throw new IllegalStateException("RUNTIME_CAPTURE_CHECK_FAILED"); }
}
