#!/usr/bin/env python3
"""Source-boundary controls only; neither Gradle variant evaluation nor Android evidence."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "app/src/cortexSingleAttemptTarget/kotlin/network/orion/app/ai/OrionCortexSingleAttemptBridge.kt"
RUNNER = ROOT / "app/src/cortexSingleAttemptAndroidTest/java/network/orion/app/compatibility/OrionCortexSingleAttemptInstrumentation.java"
KEEP = ROOT / "app/src/cortexSingleAttemptTarget/proguard-rules.pro"


class SingleAttemptR8SourceContractTest(unittest.TestCase):
    def setUp(self):
        self.bridge = BRIDGE.read_text()
        self.runner = RUNNER.read_text()
        self.build = (ROOT / "app/build.gradle.kts").read_text()
        self.foundation = (ROOT / "app/src/main/kotlin/network/orion/app/OrionFoundationRuntime.kt").read_text()

    def test_observation_flag_cannot_enable_normal_variants(self):
        default = self.build.split("defaultConfig {", 1)[1].split("buildTypes {", 1)[0]
        self.assertIn('buildConfigField("boolean", "ORION_CORTEX_SINGLE_ATTEMPT", "false")', default)
        self.assertNotIn("cortexSingleAttemptEnabled.toString()", default)
        qualification = self.build.split('create("qualificationRelease")', 1)[1].split("buildFeatures {", 1)[0]
        self.assertIn('applicationIdSuffix = ".qualification"', qualification)
        self.assertIn('buildConfigField("boolean", "ORION_CORTEX_SINGLE_ATTEMPT", cortexSingleAttemptEnabled.toString())', qualification)
        self.assertIn('cortexSingleAttemptFlag in setOf("true", "false")', self.build)
        self.assertIn("!cortexSingleAttemptEnabled || qualificationEnabled", self.build)
        self.assertIn("exitEvidenceCaptureEnabled, cortexSingleAttemptEnabled).count", self.build)

    def test_bridge_keep_runner_and_ci_are_scoped_without_enabling_metrics(self):
        mode = self.build.split("if (cortexSingleAttemptEnabled)", 1)[1].split("\n}", 1)[0]
        self.assertIn('sourceSets.getByName("qualificationRelease").kotlin.directories.add("src/cortexSingleAttemptTarget/kotlin")', mode)
        self.assertIn('getByName("qualificationRelease").proguardFiles("src/cortexSingleAttemptTarget/proguard-rules.pro")', mode)
        keep = KEEP.read_text()
        for name in ("prepare", "execute", "observe", "release"):
            self.assertIn(f"public static android.os.Bundle {name}(android.content.Context);", keep)
        self.assertNotIn("***", keep)
        for file in (ROOT / "app/src/main").rglob("*.kt"):
            self.assertNotIn("OrionCortexSingleAttemptBridge", file.read_text())
        ci = (ROOT / ".github/workflows/ci.yml").read_text().split("Build opt-in existing-owner single-attempt qualification artifacts", 1)[1].split("\n      - name:", 1)[0]
        self.assertIn("-PorionQualificationRelease=true -PorionCortexSingleAttempt=true", ci)
        self.assertNotIn("-PorionCortexGenerationMetrics=true", ci)

    def test_composition_only_attaches_bounded_observer_to_the_same_cached_owner(self):
        graph = self.foundation.split("private val cortexOwnershipLock", 1)[1].split("private var preferences", 1)[0]
        self.assertIn("if (!BuildConfig.ORION_CORTEX_SINGLE_ATTEMPT) null else try", graph)
        self.assertIn("OrionCortexCycleObserver(1)", graph)
        self.assertIn("catch (_: Throwable) { null }", graph)
        self.assertEqual(1, graph.count("OrionCortexRuntimeOwner("))
        self.assertIn("cortexOwner ?: network.orion.app.ai.OrionCortexRuntimeOwner", graph)
        self.assertIn("cycleObservations = cortexCycleObserver", graph)
        for token in ("qualifiedPlans =", "timing =", "attemptTemplate =", "OrionCortexAttemptTemplate("):
            self.assertNotIn(token, graph)
        self.assertEqual(1, self.bridge.count("OrionCortexSingleAttemptDriver(owner)"))

    def test_no_bridge_factory_configuration_native_work_clock_or_new_worker(self):
        for token in ("OrionCortexRuntimeOwner(", "OrionFoundationRuntime(", "OrionCortexClient(", "OrionDatabase", "Room.",
                      "OrionCortexAttemptTemplate(", "OrionInferenceResourcePlan(", "prepareSingleAttemptRequest(", "newRequestId(",
                      "getDeclaredField", "getDeclaredMethod", "initializeMainScheduler", ".start()", ".recover(", ".onCreate(",
                      "bindService(", "unbindService(", "loadModel(", "unloadModel(", "cancelInference(", "healthCheck(",
                      "OrionLlamaRuntime", "clock.", "SystemClock", "currentTimeMillis", "Executors", "Thread("):
            self.assertNotIn(token, self.bridge)
        self.assertNotIn("Executors", self.runner)
        self.assertNotIn("new Thread", self.runner)
        self.assertEqual(1, self.bridge.count("actual.prepare()"))
        self.assertEqual(1, self.bridge.count("actual.execute(selected, discard)"))
        self.assertEqual(1, self.bridge.count("actual.readPartial(selected)"))
        self.assertEqual(1, self.bridge.count("actual.release(selected)"))

    def test_real_application_uid_process_and_cold_main_publication_are_checked(self):
        for token in ("context.applicationContext as? OrionApplication", "app.packageName == TARGET", "Application.getProcessName() == TARGET",
                      "app.applicationInfo.uid == Process.myUid()", "Binder.getCallingUid() == Process.myUid()",
                      "app.packageManager.getApplicationInfo(TARGET, 0)", "installed.uid == Process.myUid()",
                      "installed.className == OrionApplication::class.java.name", "ApplicationInfo.FLAG_DEBUGGABLE",
                      "Looper.myLooper() != Looper.getMainLooper()"):
            self.assertIn(token, self.bridge)
        wait = self.bridge.split("private suspend fun existingOwner", 1)[1].split("private suspend fun sameRuntime", 1)[0]
        self.assertIn("withTimeout(12_000)", wait)
        self.assertIn("for (attempt in 0..200)", wait)
        self.assertIn("withContext(Dispatchers.Main.immediate) { app.mainRuntime }", wait)
        self.assertIn("main.health.first { it.core.readiness.isStartupBarrierOpen }", wait)
        self.assertEqual(1, self.bridge.count("main.cortexRuntime()"))
        same = self.bridge.split("private suspend fun sameRuntime", 1)[1].split("private fun observedEnvironment", 1)[0]
        self.assertIn("withTimeout(12_000)", same)
        self.assertIn("app.mainRuntime === selected", same)

    def test_phase_cas_does_not_hold_monitor_across_original_execution_or_manual_read(self):
        self.assertNotIn("synchronized(", self.bridge)
        for transition in ("NEW, PREPARING", "SELECTED, EXECUTING", "INVOKED, OBSERVING", "previous, RELEASING"):
            self.assertIn(f"compareAndSet({transition})", self.bridge)
        original = self.bridge.split("val result = try {", 1)[1].split("execution = result", 1)[0]
        self.assertIn("actual.execute(selected, discard)", original)
        self.assertIn("phase.set(THREW)", original)
        self.assertIn("throw failure", original)
        for token in ("reply(", "readPartial(", "release(", "observe("):
            self.assertNotIn(token, original)
        self.assertLess(self.bridge.index('metadataUnavailable = reply('), self.bridge.index("actual.execute(selected, discard)"))
        self.assertIn('discard = { _ -> }', self.bridge)

    def test_prepare_refusal_never_invokes_or_manufactures_an_owner_cycle(self):
        refused = self.runner.split('if ("PREPARE_REFUSED".equals', 1)[1].split('check("SELECTED"', 1)[0]
        self.assertIn("outcome = prepared", refused)
        for token in ('invoke(bridge, "execute"', 'invoke(bridge, "observe"', 'invoke(bridge, "release"', "OrionCortexRunResult"):
            self.assertNotIn(token, refused)
        self.assertIn("counts(initial, 1, 0, 0, 0)", self.runner)
        self.assertIn('check(arguments.isEmpty())', self.runner)
        self.assertLess(self.runner.index("check(arguments.isEmpty())"), self.runner.index("Class.forName("))

    def test_runner_preserves_observe_pair_and_separates_technical_release(self):
        self.assertIn('outcome = invoke(bridge, "observe", context)', self.runner)
        self.assertIn('Bundle released = invoke(bridge, "release", context)', self.runner)
        self.assertIn('outcome.putString(RELEASE, released.getString(HEADER))', self.runner)
        self.assertNotIn('outcome = released', self.runner)
        self.assertIn('"orionSingleAttemptReleaseStatus"', self.runner)
        self.assertIn('"CORTEX_SINGLE_ATTEMPT_RUNNER_FAILURE"', self.runner)
        self.assertIn("catch (InvocationTargetException wrapped)", self.runner)
        self.assertIn("Throwable original = wrapped.getCause()", self.runner)
        self.assertIn("if (original != null) throw original", self.runner)
        failure = self.runner.split("private static Bundle failure(", 1)[1].split("private static void framed", 1)[0]
        self.assertNotIn('put("prepareCalls"', failure)
        self.assertIn('put("ownerCallInvoked", JSONObject.NULL)', failure)
        for source in (self.bridge, self.runner):
            for token in ("getMessage()", "failure.message", "printStackTrace", 'put("requestId"', 'put("modelId"', 'put("pid"', 'put("bootCount"'):
                self.assertNotIn(token, source)

    def test_text_and_parcel_budgets_are_distinct_and_checked_after_functional_return(self):
        self.assertIn("headerBytes <= 1024", self.bridge)
        self.assertIn("partialBytes <= 8192", self.bridge)
        self.assertIn("<= 10 * 1024", self.bridge)
        self.assertIn("total <= 10 * 1024", self.runner)
        self.assertIn("parcel.dataSize() <= 24 * 1024", self.runner)
        self.assertLess(self.runner.index('invoke(bridge, "execute", context)'), self.runner.index("framed(outcome)"))
        self.assertNotIn("Parcel", self.bridge)
        self.assertIn('"counterScope", "DRIVER_METHOD_CALLS"', self.bridge)
        self.assertIn('"DRIVER_METHOD_CALLS".equals', self.runner)

    def test_normal_installed_artifact_tests_assert_real_singleton_and_no_observer_or_bridge(self):
        tests = (ROOT / "app/src/androidTest/kotlin/network/orion/app/FoundationRuntimeTest.kt").read_text()
        self.assertIn("assertSame(owner, runtime.cortexRuntime())", tests)
        self.assertIn("assertNull(owner.beginCycleObservation())", tests)
        self.assertIn('Class.forName("network.orion.app.ai.OrionCortexSingleAttemptBridge", false, app.classLoader)', tests)
        self.assertIn('assertEquals("network.orion", app.packageName)', tests)


if __name__ == "__main__":
    unittest.main()
