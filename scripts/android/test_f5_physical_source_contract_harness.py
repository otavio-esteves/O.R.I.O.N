#!/usr/bin/env python3
"""HOST source-boundary contracts; actual environment rejection is checked on an owned emulator."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "app/src/physicalBaselineAndroidTest/java/network/orion/app/compatibility/OrionPhysicalBaselineInstrumentation.java"
BRIDGE = ROOT / "app/src/physicalBaselineTarget/kotlin/network/orion/app/resources/OrionPhysicalBaselineBridge.kt"


class PhysicalBaselineSourceContractTest(unittest.TestCase):
    def setUp(self):
        self.runner = RUNNER.read_text()
        self.bridge = BRIDGE.read_text()

    def test_requested_label_does_not_establish_observed_physical_environment(self):
        unknown = self.runner.index('String observedEnvironment = "UNVERIFIED";')
        verified = self.runner.index("checkPhysicalBuild();", unknown)
        claim = self.runner.index('observedEnvironment = "PHYSICAL_DEVICE";', verified)
        bind = self.runner.index("context.bindService(", claim)
        self.assertLess(unknown, verified)
        self.assertLess(verified, claim)
        self.assertLess(claim, bind)
        self.assertEqual(1, self.runner.count('observedEnvironment = "PHYSICAL_DEVICE";'))

    def test_all_runner_rejections_use_observed_environment_not_request_or_literal(self):
        summary = self.runner.split("private static Bundle summary(", 1)[1]
        self.assertIn('value.put("environment", observedEnvironment)', summary)
        self.assertNotIn('value.put("environment", "PHYSICAL_DEVICE")', self.runner)
        # There is one method declaration; every call must carry the observed state.
        self.assertEqual(self.runner.count("summary(") - 1,
                         self.runner.count("summary(observedEnvironment,"))

    def test_every_attempted_bind_reaches_cleanup_even_when_call_returns_false_or_throws(self):
        attempted = self.runner.index("bindAttempted = true;")
        invocation = self.runner.index("bound = context.bindService(", attempted)
        cleanup = self.runner.split("} finally {", 1)[1].split("boolean complete", 1)[0]
        self.assertLess(attempted, invocation)
        self.assertIn("if (bindAttempted)", cleanup)
        self.assertNotIn("if (bound)", cleanup)
        self.assertIn("context.unbindService(connection)", cleanup)

    def test_not_registered_is_an_explicit_non_successful_cleanup_result(self):
        cleanup = self.runner.split("} finally {", 1)[1].split("boolean complete", 1)[0]
        self.assertIn("catch (IllegalArgumentException notRegistered)", cleanup)
        self.assertIn('"CLEANUP_NOT_REGISTERED"', cleanup)
        self.assertIn('bound ? "INCOMPLETE" : "REJECTED"', cleanup)
        self.assertNotIn('"OBSERVATION_COMPLETE_REVIEW_REQUIRED"', cleanup)

    def test_direct_bridge_entry_revalidates_physical_build_before_any_receipt(self):
        validation = self.bridge.split("@JvmStatic fun validate(", 1)[1].split("@Suppress", 1)[0]
        self.assertLess(validation.index("requirePhysicalBuild()"), validation.index("inventory(context)"))
        self.assertIn('System.getProperty("os.arch") == "aarch64"', validation)
        self.assertIn('"PHYSICAL_ENVIRONMENT_REJECTED"', validation)
        collection = self.bridge.split("@JvmStatic fun collect(", 1)[1]
        self.assertLess(collection.index("validate(context, samples, intervalMillis)"),
                        collection.index("IOrionCortex.Stub.asInterface"))
        self.assertLess(collection.index("validate(context, samples, intervalMillis)"),
                        collection.index('put("environment", "PHYSICAL_DEVICE")'))

    def test_measurement_sources_never_issue_model_or_inference_work(self):
        for source in [self.runner, self.bridge]:
            for call in ["loadModel(", "startInference(", "unloadModel(", "fixtureDecision(", "OrionInferenceResourcePlan("]:
                self.assertNotIn(call, source)


if __name__ == "__main__":
    unittest.main()
