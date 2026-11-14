package network.orion.app.compatibility;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Optional, platform-only raw-Binder probe; no target classes, MAIN JNI or production admission. */
final class OrionGenerationMetricsQualification {
    private static final String DESCRIPTOR = "network.orion.ai.api.IOrionCortex";
    private static final String CALLBACK_DESCRIPTOR = "network.orion.ai.api.IOrionInferenceCallback";
    private static final String SERVICE = "network.orion.ai.service.OrionCortexService";
    private static final String SHA = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    private static final long MODEL_BYTES = 491400032L;
    private static final int CONTEXT = 512;
    private static final int MAX_POLLS = 12000;
    // Original AIDL order, followed by the accepted ADR-0003 append. Never use R8 class names.
    private static final int PROTOCOL = 1, LOAD = 2, INFER = 3, REQUEST_STATUS = 5,
            STATUS = 6, UNLOAD = 7, HEALTH = 8, ISSUE = 9, CAPABILITIES = 10,
            METRICS_CAPABILITIES = 11, METRICS = 12;
    private static final String[] METRIC_KEYS = {
            "promptTokens", "sampledTokens", "eogTokens", "callbacksAttempted", "callbacksReturnedNormally",
            "prefillCalls", "prefillSuccessfulCalls", "prefillSubmittedTokens", "prefillTokensInSuccessfulCalls",
            "decodeCalls", "decodeSuccessfulCalls", "decodeSubmittedTokens", "decodeTokensInSuccessfulCalls",
            "tokenizationApiWallNanos", "prefillApiWallNanos", "decodeApiWallNanos" };
    private static final String[] HEADERS = { "schemaVersion", "status", "requestId", "servicePid",
            "snapshotSequence", "logicalTerminal", "nativeState", "nativeFinish", "slotState", "snapshotState",
            "partialWorkState", "completedCallStagesMask", "timeState", "availableMetricsMask", "metrics" };
    private static final String[] OPTIONAL_HEADERS = { "invocationId", "bootCount", "capturedElapsedMillis",
            "wrapperStartedElapsed", "wrapperFinishedElapsed" };

    static Bundle run(Context context, Bundle arguments) throws Exception {
        check("true".equals(arguments.get("orionGenerationMetricsProbe")), "Explicit metrics opt-in required");
        check("ANDROID_EMULATOR".equals(arguments.get("orionGenerationMetricsEnvironment")), "Emulator declaration required");
        check(context.getPackageName().equals(Application.getProcessName()), "MAIN fixture process required");
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        check((hardware.equals("ranchu") || hardware.equals("goldfish")) &&
                (fingerprint.contains("emulator") || fingerprint.contains("generic") || Build.PRODUCT.startsWith("sdk_")),
                "Emulator Build signals required");
        Object rawPath = arguments.get("orionGenerationMetricsModelPath");
        check(rawPath instanceof String, "Pinned private model path required");
        Budget budget = new Budget(context);
        ComponentName component = new ComponentName(context.getPackageName(), SERVICE);
        @SuppressWarnings("deprecation") ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
        check(!info.exported && (context.getPackageName() + ":ai").equals(info.processName), "Private AI service required");
        AtomicReference<IBinder> current = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { current.set(binder); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { current.set(null); }
            @Override public void onBindingDied(ComponentName name) { current.set(null); }
            @Override public void onNullBinding(ComponentName name) { current.set(null); connected.countDown(); }
        };
        boolean bound = false;
        boolean modelTouched = false;
        boolean unloadIssued = false;
        IBinder service = null;
        try (ParcelFileDescriptor model = pinnedModel(context, (String) rawPath, budget)) {
            bound = context.bindService(new Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE);
            check(bound, "Bind rejected");
            check(connected.await(10, TimeUnit.SECONDS), "Bind wait expired");
            service = current.get();
            check(service != null && DESCRIPTOR.equals(service.getInterfaceDescriptor()), "Wrong private descriptor");
            endpoint(current, service, budget);
            Bundle protocol = call(service, PROTOCOL, null, 16384);
            keys(protocol, new String[] { "schemaVersion", "status", "major", "minor" });
            check(text(protocol, "status").equals("OK") && integer(protocol, "major") == 1 && integer(protocol, "minor") >= 2,
                    "Optional metrics protocol unavailable");
            Bundle legacy = call(service, CAPABILITIES, null, 16384);
            keys(legacy, new String[] { "schemaVersion", "status", "capabilities" });
            check(text(legacy, "status").equals("OK") && text(legacy, "capabilities").equals("orion_issued_request_history_v1"),
                    "Legacy history changed");
            Bundle capability = call(service, METRICS_CAPABILITIES, null, 1024);
            keys(capability, new String[] { "schemaVersion", "status", "capability", "metricsSchemaVersion", "supportedMetricsMask" });
            long supported = number(capability, "supportedMetricsMask");
            check(text(capability, "status").equals("OK") && text(capability, "capability").equals("orion_generation_metrics_v1")
                    && integer(capability, "metricsSchemaVersion") == 1 && (supported & 8191) == 8191 && (supported & ~65535L) == 0,
                    "Compiled optional source unavailable");
            engineState(call(service, STATUS, null, 16384), "UNLOADED");
            int aiPid = identity(context, call(service, HEALTH, null, 16384), budget, null);
            String loadId = issue(service);
            Bundle load = specification(loadId, budget);
            load.putString("modelId", SHA); load.putString("sha256", SHA); load.putLong("sizeBytes", MODEL_BYTES);
            load.putInt("contextTokens", CONTEXT); load.putInt("threads", 1);
            endpoint(current, service, budget);
            modelTouched = true;
            accepted(call(service, LOAD, data -> { data.writeTypedObject(model, 0); data.writeTypedObject(load, 0); }, 16384), loadId);
            awaitLoaded(service, current, loadId, budget);
            Bundle loadedHealth = call(service, HEALTH, null, 16384);
            identity(context, loadedHealth, budget, aiPid);
            check(Boolean.TRUE.equals(loadedHealth.get("nativeRuntimeLoaded")) &&
                    Boolean.TRUE.equals(loadedHealth.get("nativeValidationCompleted")), "Native fixture load unavailable");
            String requestId = issue(service);
            ChunkReceiver receiver = new ChunkReceiver(requestId);
            byte[] input = "Hello".getBytes(StandardCharsets.UTF_8);
            Bundle inference = specification(requestId, budget);
            long requestStarted = number(inference, "startedElapsed");
            inference.putString("modelId", SHA); inference.putLong("inputSize", input.length);
            inference.putInt("maxTokens", 1); inference.putFloat("temperature", 0f);
            try (SharedMemory memory = SharedMemory.create("orion_metrics_probe", input.length)) {
                ByteBuffer mapped = memory.mapReadWrite();
                try { mapped.put(input); } finally { SharedMemory.unmap(mapped); Arrays.fill(input, (byte) 0); }
                check(memory.setProtect(OsConstants.PROT_READ), "Readonly input unavailable");
                endpoint(current, service, budget);
                accepted(call(service, INFER, data -> {
                    data.writeTypedObject(memory, 0); data.writeTypedObject(inference, 0); data.writeStrongBinder(receiver);
                }, 16384), requestId);
            }
            for (int poll = 0; !receiver.finished() && poll < MAX_POLLS; poll++) {
                endpoint(current, service, budget);
                receiver.awaitTick();
            }
            receiver.assertSuccess();
            Snapshot first = awaitFinal(service, current, requestId, supported, aiPid, requestStarted, receiver, budget);
            for (int repeat = 0; repeat < 2; repeat++) {
                endpoint(current, service, budget);
                Bundle cachedReply = query(service, requestId);
                endpoint(current, service, budget);
                Snapshot cached = snapshot(cachedReply, supported, requestId, aiPid, requestStarted, budget);
                check(first.facts.equals(cached.facts), "Cached FINAL changed");
                cached.assertFixtureCompletion();
            }
            unloadIssued = true;
            unload(service, current, budget);
            receiver.assertSuccess();
            identity(context, call(service, HEALTH, null, 16384), budget, aiPid);
            endpoint(current, service, budget);
            Bundle result = new Bundle();
            result.putString("orion_generation_metrics_result", "MINIFIED_PRIVATE_AI_SOURCE_OBSERVED");
            result.putString("orion_generation_metrics_environment", "ANDROID_EMULATOR");
            result.putString("orion_generation_metrics_qualification", "NOT_QUALIFIED");
            result.putString("orion_generation_metrics_admission", "SYNTHETIC_FIXTURE_ONLY");
            result.putBoolean("orion_generation_metrics_physical_verified", false);
            result.putBoolean("orion_generation_metrics_profile_approved", false);
            result.putBoolean("orion_generation_metrics_cached_final_unchanged", true);
            result.putBoolean("orion_generation_metrics_pinned_model_verified", true);
            result.putInt("orion_generation_metrics_context", CONTEXT);
            result.putInt("orion_generation_metrics_requested_tokens", 1);
            result.putInt("orion_generation_metrics_callback_chunks", receiver.chunks());
            result.putInt("orion_generation_metrics_logical_terminals", 1);
            result.putString("orion_generation_metrics_process", "SAME_BOUND_SEPARATE_AI_PID");
            result.putLong("orion_generation_metrics_supported_mask", supported);
            result.putLong("orion_generation_metrics_available_mask", first.mask);
            result.putString("orion_generation_metrics_native_finish", (String) first.facts.get("nativeFinish"));
            result.putString("orion_generation_metrics_time_state", (String) first.facts.get("timeState"));
            result.putString("orion_generation_metrics_duration_meaning", "API_WALL_NS_ONLY_NO_THROUGHPUT");
            for (Map.Entry<String, Long> entry : first.metrics.entrySet()) result.putLong("orion_metric_" + entry.getKey(), entry.getValue());
            return result;
        } finally {
            try {
                // One best-effort owned cleanup even after a failed load/inference. No retry/generation.
                if (modelTouched && !unloadIssued && service != null && current.get() == service) {
                    voidCall(service, UNLOAD);
                }
            } finally {
                if (bound) context.unbindService(connection);
            }
        }
    }

    private static final class Budget {
        final Context context;
        final int boot;
        final long deadline = SystemClock.elapsedRealtime() + 300000;
        Budget(Context context) throws Exception {
            this.context = context;
            boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
            check(boot >= 0, "Boot unavailable");
        }
        long now() { long now = SystemClock.elapsedRealtime(); check(now < deadline, "Probe budget expired"); return now; }
        void sameBoot() throws Exception {
            check(Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT) == boot, "Boot changed");
            now();
        }
    }

    private static ParcelFileDescriptor pinnedModel(Context context, String path, Budget budget) throws Exception {
        File requested = new File(path);
        File directory = context.getFilesDir().getCanonicalFile();
        check(requested.isAbsolute() && requested.equals(requested.getCanonicalFile()) && directory.equals(requested.getParentFile()),
                "Canonical direct private file required");
        StructStat before = Os.lstat(path);
        check(OsConstants.S_ISREG(before.st_mode) && before.st_size == MODEL_BYTES && before.st_uid == Process.myUid(), "Pinned file identity invalid");
        FileDescriptor original = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        ParcelFileDescriptor descriptor;
        try { descriptor = ParcelFileDescriptor.dup(original); } finally { Os.close(original); }
        try {
            StructStat opened = Os.fstat(descriptor.getFileDescriptor());
            check(OsConstants.S_ISREG(opened.st_mode) && opened.st_dev == before.st_dev && opened.st_ino == before.st_ino
                    && opened.st_size == MODEL_BYTES && opened.st_uid == Process.myUid(), "Opened file identity changed");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (long offset = 0; offset < MODEL_BYTES;) {
                budget.now();
                int read = Os.pread(descriptor.getFileDescriptor(), buffer, 0, (int) Math.min(buffer.length, MODEL_BYTES - offset), offset);
                check(read > 0, "Pinned file read incomplete");
                digest.update(buffer, 0, read); offset += read;
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value));
            check(SHA.contentEquals(hash), "Pinned model hash mismatch");
            StructStat after = Os.lstat(path);
            check(OsConstants.S_ISREG(after.st_mode) && after.st_ino == opened.st_ino && after.st_dev == opened.st_dev
                    && after.st_size == MODEL_BYTES, "Pinned file changed during verification");
            budget.sameBoot();
            return descriptor;
        } catch (Throwable failure) { descriptor.close(); throw failure; }
    }

    private static Bundle specification(String id, Budget budget) {
        long now = budget.now();
        Bundle value = new Bundle();
        value.putInt("schemaVersion", 1); value.putString("requestId", id); value.putInt("bootCount", budget.boot);
        value.putLong("startedElapsed", now); value.putLong("durationMillis", Math.min(120000, budget.deadline - now));
        return value;
    }

    private static void awaitLoaded(IBinder service, AtomicReference<IBinder> current, String id, Budget budget) throws Exception {
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            endpoint(current, service, budget);
            Bundle receipt = call(service, REQUEST_STATUS, data -> data.writeString(id), 16384);
            keys(receipt, new String[] { "schemaVersion", "status", "requestId", "state" });
            check(text(receipt, "status").equals("OK") && text(receipt, "requestId").equals(id), "Load receipt mismatch");
            String state = text(receipt, "state");
            check(state.equals("RUNNING") || state.equals("SUCCEEDED"), "Load did not succeed");
            if (state.equals("SUCCEEDED")) {
                Bundle engine = call(service, STATUS, null, 16384);
                if (text(engine, "status").equals("LOADED")) { engineState(engine, "LOADED"); return; }
                keys(engine, new String[] { "schemaVersion", "status" }, "modelId");
                check(text(engine, "status").equals("LOADING"), "Load slot state invalid");
                if (engine.containsKey("modelId")) check(text(engine, "modelId").equals(SHA), "Loading fixture mismatch");
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Load poll count exhausted");
    }

    private static Snapshot awaitFinal(IBinder service, AtomicReference<IBinder> current, String id, long supported,
            int pid, long started, ChunkReceiver receiver, Budget budget) throws Exception {
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            endpoint(current, service, budget); receiver.assertSuccess();
            Bundle reply = query(service, id);
            endpoint(current, service, budget);
            if (text(reply, "status").equals("UNAVAILABLE")) keys(reply, new String[] { "schemaVersion", "status" });
            else {
                Snapshot value = snapshot(reply, supported, id, pid, started, budget);
                if (value.facts.get("snapshotState").equals("FINAL")) { value.assertFixtureCompletion(); return value; }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Metrics poll count exhausted");
    }

    private static Bundle query(IBinder service, String id) throws Exception {
        Bundle query = new Bundle(); query.putInt("schemaVersion", 1); query.putString("requestId", id);
        return call(service, METRICS, data -> data.writeTypedObject(query, 0), 8192);
    }

    private static void unload(IBinder service, AtomicReference<IBinder> current, Budget budget) throws Exception {
        endpoint(current, service, budget); voidCall(service, UNLOAD);
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            endpoint(current, service, budget);
            Bundle state = call(service, STATUS, null, 16384);
            keys(state, new String[] { "schemaVersion", "status" });
            if (text(state, "status").equals("UNLOADED")) return;
            check(text(state, "status").equals("UNLOADING"), "Unload state invalid"); Thread.sleep(25);
        }
        throw new AssertionError("Unload poll count exhausted");
    }

    private static int identity(Context context, Bundle health, Budget budget, Integer expected) throws Exception {
        keys(health, new String[] { "schemaVersion", "status", "nativeRuntimeLoaded", "nativeValidationCompleted", "processName", "pid",
                "bootCount", "sampledElapsed", "totalPssBytes", "nativeIncluded" });
        int pid = integer(health, "pid");
        check(text(health, "status").equals("OK") && text(health, "processName").equals(context.getPackageName() + ":ai")
                && pid > 0 && pid != Process.myPid() && (expected == null || expected == pid)
                && integer(health, "bootCount") == budget.boot && number(health, "sampledElapsed") >= 0
                && number(health, "sampledElapsed") <= budget.now(), "AI identity changed");
        check(health.get("nativeRuntimeLoaded") instanceof Boolean && health.get("nativeValidationCompleted") instanceof Boolean
                && Boolean.TRUE.equals(health.get("nativeIncluded")) && number(health, "totalPssBytes") >= 0, "Health types invalid");
        budget.sameBoot(); return pid;
    }

    private static void endpoint(AtomicReference<IBinder> current, IBinder service, Budget budget) throws Exception {
        check(current.get() == service && service.isBinderAlive(), "Endpoint changed"); budget.now();
    }

    private static void engineState(Bundle state, String expected) {
        keys(state, expected.equals("LOADED") ? new String[] { "schemaVersion", "status", "modelId" } : new String[] { "schemaVersion", "status" });
        check(text(state, "status").equals(expected), "Engine state mismatch");
        if (expected.equals("LOADED")) check(text(state, "modelId").equals(SHA), "Loaded fixture mismatch");
    }

    private static void accepted(Bundle value, String id) {
        keys(value, new String[] { "schemaVersion", "status", "requestId" });
        check(text(value, "status").equals("ACCEPTED") && text(value, "requestId").equals(id), "Request not accepted");
    }

    private static String issue(IBinder service) throws Exception {
        Bundle value = call(service, ISSUE, null, 16384);
        keys(value, new String[] { "schemaVersion", "status", "requestId" });
        String id = text(value, "requestId");
        check(text(value, "status").equals("OK") && id.length() <= 128 && id.equals(id.trim())
                && id.matches("orion_request/[^/]+/[1-9][0-9]*"), "Issued identity invalid");
        boolean opaqueScopePresent = false;
        int scopeEnd = id.lastIndexOf('/');
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            check(!Character.isISOControl(character), "Issued identity control invalid");
            if (index >= "orion_request/".length() && index < scopeEnd
                    && !Character.isWhitespace(character) && !Character.isSpaceChar(character)) opaqueScopePresent = true;
        }
        check(opaqueScopePresent, "Issued scope invalid");
        String suffix = id.substring(scopeEnd + 1);
        check(suffix.equals(Long.toString(Long.parseLong(suffix))), "Issued sequence invalid"); return id;
    }

    private interface Writer { void write(Parcel data); }
    private static Bundle call(IBinder service, int transaction, Writer writer, int replyLimit) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); if (writer != null) writer.write(data);
            check(data.dataSize() <= (transaction >= 11 ? 1024 : 16384), "Request Parcel too large");
            check(service.transact(transaction, data, reply, 0), "Transaction unavailable");
            check(reply.dataSize() <= replyLimit, "Reply Parcel too large"); reply.readException();
            Bundle result = reply.readTypedObject(Bundle.CREATOR);
            check(result != null && reply.dataAvail() == 0, "Reply framing invalid"); return result;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void voidCall(IBinder service, int transaction) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); check(service.transact(transaction, data, reply, 0), "Cleanup unavailable");
            check(reply.dataSize() <= 1024, "Cleanup reply too large"); reply.readException();
            check(reply.dataAvail() == 0, "Cleanup framing invalid");
        } finally { data.recycle(); reply.recycle(); }
    }

    private static Snapshot snapshot(Bundle reply, long supported, String id, int pid, long started, Budget budget) {
        keys(reply, HEADERS, OPTIONAL_HEADERS);
        check(text(reply, "status").equals("SNAPSHOT") && text(reply, "requestId").equals(id)
                && integer(reply, "servicePid") == pid && number(reply, "snapshotSequence") > 0, "Snapshot identity invalid");
        oneOf(text(reply, "logicalTerminal"), "NONE", "SUCCEEDED", "CANCELLED", "TIMED_OUT", "FAILED", "PROCESS_DIED");
        String nativeState = text(reply, "nativeState"), finish = text(reply, "nativeFinish"), slot = text(reply, "slotState"),
                state = text(reply, "snapshotState"), time = text(reply, "timeState");
        oneOf(nativeState, "NOT_STARTED", "RUNNING", "JNI_RETURNED", "NOT_EXECUTED", "UNKNOWN");
        oneOf(finish, "NONE", "SUCCEEDED", "CANCELLED", "TOKEN_LIMIT", "UNKNOWN");
        oneOf(slot, "OWNED", "RELEASED", "UNKNOWN"); oneOf(state, "UPDATING", "FINAL", "UNKNOWN");
        oneOf(text(reply, "partialWorkState"), "NONE_OBSERVED", "POSSIBLE", "UNKNOWN");
        oneOf(time, "KNOWN_BOOT", "UNAVAILABLE", "DISCONTINUOUS");
        int stages = integer(reply, "completedCallStagesMask"); check((stages & ~7) == 0, "Unknown stage bit");
        if (reply.containsKey("invocationId")) check(number(reply, "invocationId") > 0, "Invocation invalid");
        if (nativeState.equals("RUNNING") || nativeState.equals("JNI_RETURNED")) check(reply.containsKey("invocationId"), "Invocation missing");
        if (!finish.equals("NONE") && !finish.equals("UNKNOWN")) check(nativeState.equals("JNI_RETURNED"), "Native finish premature");
        long mask = number(reply, "availableMetricsMask");
        check(mask >= 0 && (mask & ~65535L) == 0 && (mask & supported) == mask, "Metrics mask invalid");
        Object nested = reply.get("metrics"); check(nested instanceof Bundle, "Metrics Bundle required");
        Bundle metrics = (Bundle) nested;
        Set<String> selected = new HashSet<>(); Map<String, Long> values = new HashMap<>();
        for (int bit = 0; bit < METRIC_KEYS.length; bit++) if ((mask & (1L << bit)) != 0) {
            String key = METRIC_KEYS[bit]; selected.add(key); long value = number(metrics, key);
            check(value >= 0 && (bit >= 13 || value <= (bit == 2 ? 1 : bit == 0 || bit >= 5 && bit <= 8 ? 32768 : 4096)),
                    "Metric representation bound invalid");
            if (bit == 0) check(value > 0 && value <= CONTEXT - 1, "Actual prompt context bound invalid");
            if (bit >= 1 && bit <= 4) check(value <= 1, "Actual generation bound invalid");
            if (bit >= 9 && bit <= 12) check(value == 0, "Actual one-token decode bound invalid");
            values.put(key, value);
        }
        if ((mask & 0xe000) != 0) { selected.add("clockDomain"); check(text(metrics, "clockDomain").equals("STEADY_CLOCK_NS"), "Native clock domain invalid"); }
        check(metrics.keySet().equals(selected), "Metric field set invalid");
        le(values, "eogTokens", "sampledTokens"); le(values, "callbacksReturnedNormally", "callbacksAttempted");
        le(values, "callbacksAttempted", "sampledTokens");
        if (has(values, "sampledTokens", "eogTokens", "callbacksAttempted"))
            check(values.get("callbacksAttempted") <= values.get("sampledTokens") - values.get("eogTokens"), "EOG callback relation invalid");
        le(values, "prefillSuccessfulCalls", "prefillCalls"); le(values, "prefillSuccessfulCalls", "prefillTokensInSuccessfulCalls");
        le(values, "prefillTokensInSuccessfulCalls", "prefillSubmittedTokens");
        le(values, "prefillCalls", "prefillSubmittedTokens"); le(values, "prefillSubmittedTokens", "promptTokens");
        sameZero(values, "prefillCalls", "prefillSubmittedTokens"); sameZero(values, "prefillSuccessfulCalls", "prefillTokensInSuccessfulCalls");
        le(values, "decodeSuccessfulCalls", "decodeCalls"); le(values, "decodeTokensInSuccessfulCalls", "decodeSubmittedTokens");
        le(values, "decodeSubmittedTokens", "callbacksReturnedNormally");
        equal(values, "decodeSubmittedTokens", "decodeCalls"); equal(values, "decodeTokensInSuccessfulCalls", "decodeSuccessfulCalls");
        if (finish.equals("TOKEN_LIMIT")) {
            for (String key : new String[] { "sampledTokens", "callbacksReturnedNormally" })
                if (values.containsKey(key)) check(values.get(key) == 1, "Token-limit counter invalid");
            if (values.containsKey("eogTokens")) check(values.get("eogTokens") == 0, "Token-limit EOG invalid");
        }
        if ((stages & 1) != 0) check(values.containsKey("promptTokens"), "Tokenizer stage unproven");
        if ((stages & 2) != 0) check((stages & 1) != 0 && has(values, "promptTokens", "prefillTokensInSuccessfulCalls")
                && values.get("promptTokens").equals(values.get("prefillTokensInSuccessfulCalls")), "Prefill stage unproven");
        if ((stages & 4) != 0) check((stages & 2) != 0 && nativeState.equals("JNI_RETURNED") &&
                (finish.equals("SUCCEEDED") || finish.equals("TOKEN_LIMIT")), "Loop stage unproven");
        if (nativeState.equals("NOT_STARTED") || nativeState.equals("NOT_EXECUTED"))
            check(!reply.containsKey("invocationId") && finish.equals("NONE") && values.isEmpty() && stages == 0, "No-work state invalid");
        if (state.equals("FINAL")) check(slot.equals("RELEASED") && !text(reply, "logicalTerminal").equals("NONE")
                && (nativeState.equals("JNI_RETURNED") || nativeState.equals("NOT_EXECUTED")), "Finality invalid");
        if (time.equals("KNOWN_BOOT")) {
            check(integer(reply, "bootCount") == budget.boot && number(reply, "capturedElapsedMillis") >= started
                    && number(reply, "capturedElapsedMillis") <= budget.now(), "Capture clock invalid");
            long captured = number(reply, "capturedElapsedMillis");
            if (reply.containsKey("wrapperStartedElapsed")) check(number(reply, "wrapperStartedElapsed") >= started
                    && number(reply, "wrapperStartedElapsed") <= captured, "Wrapper start invalid");
            if (reply.containsKey("wrapperFinishedElapsed")) check(reply.containsKey("wrapperStartedElapsed") &&
                    number(reply, "wrapperFinishedElapsed") >= number(reply, "wrapperStartedElapsed")
                    && number(reply, "wrapperFinishedElapsed") <= captured, "Wrapper finish invalid");
        } else for (String field : new String[] { "bootCount", "capturedElapsedMillis", "wrapperStartedElapsed", "wrapperFinishedElapsed" })
            check(!reply.containsKey(field), "Unavailable clock has timestamps");
        Map<String, Object> facts = new HashMap<>();
        for (String key : reply.keySet()) if (!key.equals("metrics")) facts.put(key, reply.get(key));
        Map<String, Object> metricFacts = new HashMap<>();
        for (String key : metrics.keySet()) metricFacts.put(key, metrics.get(key));
        facts.put("metrics", metricFacts);
        return new Snapshot(facts, values, mask);
    }

    private static final class Snapshot {
        final Map<String, Object> facts;
        final Map<String, Long> metrics;
        final long mask;
        Snapshot(Map<String, Object> facts, Map<String, Long> metrics, long mask) { this.facts = facts; this.metrics = metrics; this.mask = mask; }
        void assertFixtureCompletion() {
            check(facts.get("snapshotState").equals("FINAL") && facts.get("slotState").equals("RELEASED")
                    && facts.get("nativeState").equals("JNI_RETURNED") && facts.get("logicalTerminal").equals("SUCCEEDED")
                    && facts.get("partialWorkState").equals("NONE_OBSERVED") && facts.get("completedCallStagesMask").equals(7),
                    "Fixture did not complete observed stages");
            check((mask & 8191) == 8191, "Required measured counters absent");
            check(metrics.get("sampledTokens") == 1 && metrics.get("prefillCalls") > 0
                    && metrics.get("prefillCalls").equals(metrics.get("prefillSuccessfulCalls"))
                    && metrics.get("promptTokens").equals(metrics.get("prefillSubmittedTokens"))
                    && metrics.get("promptTokens").equals(metrics.get("prefillTokensInSuccessfulCalls")), "Fixture work counts invalid");
            long eog = metrics.get("eogTokens");
            check((eog == 1 && facts.get("nativeFinish").equals("SUCCEEDED") || eog == 0 && facts.get("nativeFinish").equals("TOKEN_LIMIT"))
                    && metrics.get("callbacksAttempted") == 1 - eog && metrics.get("callbacksReturnedNormally") == 1 - eog,
                    "One-token finish semantics invalid");
            check(!metrics.containsKey("decodeApiWallNanos"), "No decode-call time can be measured without a call");
        }
    }

    private static final class ChunkReceiver extends Binder {
        private final String requestId;
        private final CountDownLatch completed = new CountDownLatch(1);
        private int chunks, terminals;
        private long nextSequence;
        private String terminal, failure;
        ChunkReceiver(String requestId) { this.requestId = requestId; attachInterface(null, CALLBACK_DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR); return true; }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            try {
                check((flags & FLAG_ONEWAY) != 0 && data.dataSize() <= 16384, "Callback framing invalid");
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                Bundle chunk = data.readTypedObject(Bundle.CREATOR);
                check(chunk != null && data.dataAvail() == 0, "Callback payload invalid");
                receive(chunk);
            } catch (Throwable ignored) { synchronized (this) { failure = "INVALID_CALLBACK"; completed.countDown(); } }
            return true;
        }
        private synchronized void receive(Bundle chunk) {
            keys(chunk, new String[] { "schemaVersion", "requestId", "sequence", "delta", "isFinal" }, "finishReason");
            check(text(chunk, "requestId").equals(requestId) && number(chunk, "sequence") == nextSequence
                    && chunks < 64 && terminals == 0 && chunk.get("delta") instanceof String
                    && ((String) chunk.get("delta")).length() <= 1024 && chunk.get("isFinal") instanceof Boolean, "Callback contract invalid");
            // The text is validated and discarded, never retained, logged or counted as tokens.
            nextSequence++; chunks++;
            if (Boolean.TRUE.equals(chunk.get("isFinal"))) {
                oneOf(text(chunk, "finishReason"), "SUCCEEDED", "CANCELLED", "TIMED_OUT", "FAILED", "PROCESS_DIED");
                terminal = text(chunk, "finishReason"); terminals++; completed.countDown();
            } else check(!chunk.containsKey("finishReason"), "Nonterminal finish invalid");
        }
        boolean finished() { return completed.getCount() == 0; }
        void awaitTick() throws InterruptedException { completed.await(25, TimeUnit.MILLISECONDS); }
        synchronized void assertSuccess() { check(failure == null && terminals == 1 && "SUCCEEDED".equals(terminal), "Single successful terminal unavailable"); }
        synchronized int chunks() { return chunks; }
    }

    private static void le(Map<String, Long> values, String a, String b) { if (has(values, a, b)) check(values.get(a) <= values.get(b), "Count relation invalid"); }
    private static void equal(Map<String, Long> values, String a, String b) { if (has(values, a, b)) check(values.get(a).equals(values.get(b)), "Count equality invalid"); }
    private static void sameZero(Map<String, Long> values, String a, String b) { if (has(values, a, b)) check((values.get(a) == 0) == (values.get(b) == 0), "Empty call relation invalid"); }
    private static boolean has(Map<String, Long> values, String... keys) { return values.keySet().containsAll(Arrays.asList(keys)); }
    private static void oneOf(String value, String... choices) { check(Arrays.asList(choices).contains(value), "Unknown enum value"); }
    private static String text(Bundle value, String key) { Object field = value.get(key); check(field instanceof String, "String field required"); return (String) field; }
    private static long number(Bundle value, String key) { Object field = value.get(key); check(field instanceof Long, "Long field required"); return (Long) field; }
    private static int integer(Bundle value, String key) { Object field = value.get(key); check(field instanceof Integer, "Int field required"); return (Integer) field; }
    private static void keys(Bundle value, String[] required, String... optional) {
        check(value != null, "Missing Bundle");
        Set<String> allowed = new HashSet<>(Arrays.asList(required)); allowed.addAll(Arrays.asList(optional));
        check(value.keySet().containsAll(Arrays.asList(required)) && allowed.containsAll(value.keySet())
                && integer(value, "schemaVersion") == 1, "Closed schema invalid");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
