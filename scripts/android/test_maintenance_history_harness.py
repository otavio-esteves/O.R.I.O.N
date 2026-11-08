#!/usr/bin/env python3
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

SPEC = importlib.util.spec_from_file_location("maintenance_history", Path(__file__).with_name("record-maintenance-history.py"))
HISTORY = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(HISTORY)
FIXTURE = Path(__file__).with_name("testdata") / "maintenance-snapshot-v1.json"


class MaintenanceHistoryTest(unittest.TestCase):
    def event(self, sequence=1, pid=123, boot=4, wall=1000):
        value = json.loads(FIXTURE.read_text())
        value.update(correlationId=str(uuid.uuid4()), sequence=sequence, processId=pid, bootCount=boot,
                     elapsedMillis=1000 + sequence, timestampMillis=wall)
        return value

    def test_actual_android_protocol_fixture_and_empty_buffer_have_distinct_meaning(self):
        self.assertEqual(json.loads(FIXTURE.read_text()), HISTORY.records(json.dumps(json.loads(FIXTURE.read_text())))[0])
        self.assertEqual([], HISTORY.records("--------- beginning of main\n"))

    def test_missing_extra_or_sensitive_content_is_rejected_without_export(self):
        for name, value in (("origin", "SECRET_COMMAND"), ("schemaVersion", True), ("healthy", "true"),
                            ("correlationId", "../unsafe"), ("processId", 0), ("sequence", -1)):
            event = self.event(); event[name] = value
            with self.subTest(name=name), self.assertRaises(ValueError): HISTORY.record(event)
        for mutation in (lambda v: v.update(payload="SECRET_CONTENT"), lambda v: v.pop("metrics"),
                         lambda v: v["metrics"].update(actionUnknown=-1), lambda v: v["metrics"].update(actionUnknown=True),
                         lambda v: v["metrics"].update(actionUnknown=10000)):
            event = self.event(); mutation(event)
            with self.assertRaises(ValueError): HISTORY.record(event)
        with self.assertRaises(ValueError): HISTORY.records("SNAPSHOT_UNAVAILABLE")
        with self.assertRaises(ValueError): HISTORY.records("x" * 4097)
        with self.assertRaises(ValueError): HISTORY.records('{"schemaVersion":1,"schemaVersion":1}')

    def test_duplicates_do_not_create_progress_and_conflicts_do_not_advance_cursor(self):
        history = HISTORY.History(); event = self.event()
        self.assertEqual([event], history.accept([event], baseline=True))
        self.assertEqual([], history.accept([event]))
        self.assertEqual(1, history.duplicates)
        conflicting = copy.deepcopy(event); conflicting["healthy"] = False; conflicting["severity"] = "WARN"
        with self.assertRaises(ValueError): history.accept([self.event(2), conflicting])
        self.assertEqual(1, len(history.seen))

    def test_sequence_loss_is_explicit_and_new_boot_does_not_reuse_monotonic_order(self):
        history = HISTORY.History()
        history.accept([self.event(5)], baseline=True)
        history.accept([self.event(7)])
        self.assertEqual(1, history.sequence_gaps)
        history.accept([self.event(1, boot=5)])
        self.assertEqual(1, history.sequence_gaps)
        history.accept([self.event(1, pid=124, boot=5)])
        self.assertEqual(1, history.sequence_gaps)

    def test_wall_rollback_is_allowed_but_same_boot_elapsed_regression_is_a_gap(self):
        history = HISTORY.History(); history.accept([self.event(1, wall=1000)], baseline=True)
        history.accept([self.event(2, wall=1)])
        self.assertEqual(0, history.sequence_gaps)
        late = self.event(3); late["elapsedMillis"] = 1
        history.accept([late]); self.assertEqual(1, history.sequence_gaps)

    def test_primary_application_uid_cannot_match_other_packages(self):
        self.assertEqual(10148, HISTORY.own_uid(lambda *a: "package:network.orion.test uid:10149\npackage:network.orion uid:10148"))
        with self.assertRaises(ValueError): HISTORY.own_uid(lambda *a: "package:network.orion.test uid:10149")

    def exercise(self, gap=False, interrupted=False, empty=False, mismatch=False):
        clock = [0.0]; event = self.event(); event["origin"] = "HEARTBEAT"
        later = self.event(2); later["origin"] = "HEARTBEAT"
        calls = []; baseline_hash = "a" * 64
        def adb(*args):
            calls.append(args)
            if args[-1] == "ro.kernel.qemu": return "1"
            if args[-1] == "ro.serialno": return "synthetic"
            if args[:3] == ("shell", "pm", "path"): return "package:/data/app/synthetic/base.apk"
            if args[:2] == ("shell", "sha256sum"):
                return ("b" if mismatch and clock[0] >= 10 else "a") * 64 + "  /data/app/synthetic/base.apk"
            if args[:4] == ("shell", "cmd", "package", "list"): return "package:network.orion uid:10148"
            if args[:3] == ("shell", "settings", "get"): return "4"
            if args[0] == "logcat":
                self.assertIn("--uid=10148", args); self.assertIn("orion_maintenance:I", args)
                if gap and clock[0] == 5: raise subprocess.TimeoutExpired("synthetic", 10)
                if empty: return ""
                return json.dumps(event) + ("\n" + json.dumps(later) if clock[0] >= 5 else "")
            self.fail("Unexpected non-read-only command: " + repr(args))
        def sleep(seconds):
            if interrupted: raise KeyboardInterrupt()
            clock[0] += seconds
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "history"
            report = HISTORY.observe(adb, directory, baseline_hash, "ANDROID_EMULATOR", 10, 5,
                                     monotonic=lambda: clock[0], sleep=sleep)
            content = (directory / "maintenance-history.jsonl").read_text()
            self.assertFalse(report["fullSoakGateClosed"]); self.assertFalse(report["fullF4GateClosed"])
            self.assertFalse(report["heartbeatAutonomyProven"]); self.assertFalse(report["continuousCoverageProven"])
            self.assertEqual(hashlib.sha256(content.encode()).hexdigest(), report["historySha256"])
            return report, content

    def test_collection_counts_only_first_seen_after_baseline_and_never_approves(self):
        report, content = self.exercise()
        self.assertEqual("HISTORY_COLLECTION_COMPLETE_REVIEW_REQUIRED", report["result"])
        self.assertEqual(1, report["baselineEvents"])
        self.assertEqual(1, report["heartbeatPassesFirstSeenAfterBaseline"])
        self.assertEqual(1, report["firstSeenSubsequentEvents"])
        self.assertTrue(report["queueHistoryAvailable"])
        self.assertNotIn("SECRET", content)

    def test_empty_history_cannot_invent_zero_queues_or_heartbeat_progress(self):
        report, content = self.exercise(empty=True)
        self.assertFalse(report["queueHistoryAvailable"])
        self.assertEqual(0, report["heartbeatPassesFirstSeenAfterBaseline"])
        self.assertNotIn("actionUnknown", content)

    def test_transport_identity_loss_and_interruptions_remain_incomplete(self):
        for options in ({"gap": True}, {"interrupted": True}, {"mismatch": True}):
            with self.subTest(options=options):
                report, _ = self.exercise(**options)
                self.assertEqual("INCOMPLETE", report["result"])


if __name__ == "__main__": unittest.main()
