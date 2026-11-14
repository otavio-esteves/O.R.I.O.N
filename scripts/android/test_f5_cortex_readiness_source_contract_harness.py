#!/usr/bin/env python3
"""HOST source boundaries only; Android receipts require actual separate execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
OWNER = ROOT / "app/src/main/kotlin/network/orion/app/ai/OrionCortexRuntimeOwner.kt"
BRIDGE = ROOT / "app/src/cortexReadinessTarget/kotlin/network/orion/app/ai/OrionCortexReadinessBridge.kt"
RUNNER = ROOT / "app/src/cortexReadinessAndroidTest/java/network/orion/app/compatibility/OrionCortexReadinessInstrumentation.java"
KEEP = ROOT / "app/src/cortexReadinessTarget/proguard-rules.pro"


class CortexReadinessSourceContractTest(unittest.TestCase):
    def setUp(self):
        self.owner = OWNER.read_text()
        self.bridge = BRIDGE.read_text()
        self.runner = RUNNER.read_text()

    def test_run_and_diagnostic_share_ordered_lazy_early_predicates(self):
        helper = self.owner.split("internal inline fun evaluateCortexPreResourceReadiness(", 1)[1].split("internal sealed interface", 1)[0]
        predicates = ["isClosed()", "!hasQualifiedPlans()", "!hasTiming()", "!isCoreReady()"]
        positions = [helper.index(predicate) for predicate in predicates]
        self.assertEqual(positions, sorted(positions))
        query = self.owner.split("internal fun preResourceReadiness()", 1)[1].split("suspend fun run(", 1)[0]
        self.assertIn("evaluateCortexPreResourceReadiness(", query)
        self.assertIn("{ closed }, { plans.isNotEmpty() }, { timing != null }, coreReady", query)
        run = self.owner.split("suspend fun run(", 1)[1]
        self.assertLess(run.index("preResourceReadiness().unavailable"), run.index("val plan = byId[modelId]"))
        self.assertNotIn(".evaluate()", query + helper)

    def test_bridge_uses_only_real_core_port_and_read_only_owner_query(self):
        self.assertIn("context.applicationContext as? OrionApplication", self.bridge)
        self.assertIn("val main = app.mainRuntime", self.bridge)
        self.assertIn("val owner = main.cortexRuntime()", self.bridge)
        self.assertIn("readiness = owner.preResourceReadiness().name", self.bridge)
        self.assertEqual(1, self.bridge.count("owner.preResourceReadiness()"))
        self.assertIn('readiness = "CORE_PORT_UNAVAILABLE"', self.bridge)
        for token in ("OrionCortexRuntimeOwner(", "OrionCortexClient(", "OrionInferenceResourcePlan(", "OrionDatabase",
                      "Room.", "getDeclaredField", "getDeclaredMethod", "qualifiedPlans", "fixtureDecision"):
            self.assertNotIn(token, self.bridge)

    def test_neither_entrypoint_can_issue_request_model_bind_or_cleanup_work(self):
        for source in (self.bridge, self.runner):
            for token in (".run(", ".runInference(", ".newRequestId(", "loadModel(", "startInference(", "unloadModel(",
                          "cancelInference(", ".bindService(", ".unbindService(", "IOrionCortex", "importModel(",
                          "openReadOnly(", "owner.close(", "owner.cancel("):
                self.assertNotIn(token, source)

    def test_environment_is_observed_before_core_query_and_request_cannot_override_it(self):
        observed = self.bridge.index("val build = observedBuild()")
        mismatch = self.bridge.index("build.environment != requestedEnvironment")
        core = self.bridge.index("val main = app.mainRuntime")
        self.assertLess(observed, mismatch)
        self.assertLess(mismatch, core)
        self.assertIn('put("environment", build.environment)', self.bridge)
        self.assertNotIn('put("environment", requestedEnvironment)', self.bridge)
        classifier = self.bridge.split("private fun observedBuild()", 1)[1]
        self.assertNotIn("requestedEnvironment", classifier)
        self.assertLess(classifier.index('emulator -> "ANDROID_EMULATOR"'), classifier.index('-> "PHYSICAL_DEVICE"'))
        self.assertIn('else -> "UNVERIFIED"', classifier)

    def test_all_authority_flags_are_false_and_resources_are_not_evaluated(self):
        flags = ("physicalDeviceVerified", "provenanceAuthenticated", "runtimeRequestsIssued", "probeIpcBindIssued", "heavyWorkIssued", "admissionGranted", "profileQualified",
                 "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed")
        for flag in flags:
            for source in (self.bridge, self.runner):
                self.assertIn('put("' + flag + '", false)', source)
                self.assertNotIn('put("' + flag + '", true)', source)
        for field in ("aiObservation", "aggregateMemoryObservation", "resourceGovernorDecision", "restartDecision", "modelIdentity"):
            self.assertIn('put("' + field + '", "NOT_EVALUATED")', self.bridge)
        self.assertNotIn("PssBytes", self.bridge)

    def test_clock_and_core_wait_have_explicit_bounds_without_wall_time_substitution(self):
        self.assertIn("AndroidOrionClock(context.contentResolver)", self.bridge)
        self.assertIn("coreWaitMillis in 0..10_000", self.bridge)
        self.assertIn("withTimeout(12_000)", self.bridge)
        self.assertIn("for (attempt in 0..200)", self.bridge)
        self.assertIn("end.bootSessionId != start.bootSessionId", self.bridge)
        self.assertIn("end.elapsedSince(start)", self.bridge)
        self.assertIn("pending.get(15, TimeUnit.SECONDS)", self.runner)
        for source in (self.bridge, self.runner):
            for token in ("currentTimeMillis", "SystemClock.", "wallNow("):
                self.assertNotIn(token, source)

    def test_runner_rejects_extra_wrong_typed_or_unbounded_arguments(self):
        self.assertIn('Arrays.asList("orionEnvironment", "orionCoreWaitMillis")', self.runner)
        self.assertIn('"PHYSICAL_DEVICE".equals(declared) || "ANDROID_EMULATOR".equals(declared)', self.runner)
        self.assertIn("wait instanceof String", self.runner)
        self.assertIn("waitMillis >= 0 && waitMillis <= 10_000", self.runner)
        self.assertIn("arguments.keySet().equals(", self.runner)

    def test_cold_application_null_is_reread_on_main_inside_original_bounded_wait(self):
        loop = self.bridge.split("for (attempt in 0..200)", 1)[1].split("after = coreSnapshot(app).second", 1)[0]
        self.assertLess(loop.index("val snapshot = coreSnapshot(app)"), loop.index("val main = snapshot.first"))
        self.assertLess(loop.index("val main = snapshot.first"), loop.index("if (main != null)"))
        self.assertIn("val owner = main.cortexRuntime()", loop)
        self.assertIn("if (attempt == 0) before = snapshot.second", loop)
        self.assertIn("delay(50)", loop)
        self.assertNotIn("if (main != null) runBlocking", self.bridge)
        self.assertEqual(1, self.bridge.count("app.mainRuntime"))
        snapshot = self.bridge.split("private suspend fun coreSnapshot(", 1)[1].split("private data class", 1)[0]
        self.assertIn("withContext(Dispatchers.Main.immediate)", snapshot)
        self.assertIn("val main = app.mainRuntime", snapshot)
        self.assertIn("Looper.myLooper() != Looper.getMainLooper()", self.bridge)
        self.assertEqual(1, self.bridge.count("start.deadlineAfter(coreWaitMillis.toLong())"))
        self.assertNotIn("deadlineAfter(", loop)
        self.assertNotIn(".onCreate(", self.bridge)

    def test_reflection_is_limited_to_public_bridge_with_unverified_runner_failures(self):
        self.assertIn('getMethod("collect", Context.class, String.class, int.class)', self.runner)
        failure = self.runner.split("private static Bundle failure(", 1)[1]
        self.assertIn('put("environment", "UNVERIFIED")', failure)
        self.assertNotIn('"PHYSICAL_DEVICE"', failure)
        self.assertNotIn("getMessage()", self.runner + self.bridge)
        keep = KEEP.read_text()
        self.assertIn("public static android.os.Bundle collect(android.content.Context, java.lang.String, int);", keep)
        self.assertNotIn("***", keep)
        for source in (ROOT / "app/src/main").rglob("*"):
            if source.is_file():
                self.assertNotIn("OrionCortexReadinessBridge", source.read_text(errors="replace"))


if __name__ == "__main__":
    unittest.main()
