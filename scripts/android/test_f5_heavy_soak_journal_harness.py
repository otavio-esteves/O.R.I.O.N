#!/usr/bin/env python3
"""Real Linux HOST filesystem/process tests; no device, executor or physical evidence."""
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import warnings
from unittest import mock

import f5_heavy_soak_journal as STORE
from test_f5_heavy_soak_evidence_protocol_harness import plan, FIXTURE_ID

SCRIPT = Path(STORE.__file__).resolve()


def event(kind="RUN_BEGIN", data=None):
    return {"kind": kind, "data": {} if data is None else data, "bootCount": 7, "deviceElapsedMillis": 100}


class HeavyJournalStoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.run = self.root / "run"
        self.selected = plan()
        self.initial = STORE.operation("init", self.run, plan=self.selected)
        self.assertEqual("INITIALIZED_DURABLE", self.initial["status"])
        self.digest = self.initial["snapshotSha256"]

    def inspect(self, path=None):
        return STORE.operation("inspect", path or self.run)

    def state(self):
        return json.loads((self.run / STORE.STATE).read_bytes())

    def unqualified(self, value):
        self.assertEqual("UNVERIFIED_INPUT", value["result"])
        self.assertEqual("NOT_QUALIFIED", value["qualification"])
        for key in ("physicalEvidenceVerified", "real24HourDurationProven", "autonomyProven", "profileQualified",
                    "productionInferenceEnabled", "fullF5GateClosed", "fullSoakGateClosed", "commandsDispatched"):
            self.assertIs(False, value[key])

    def test_initialize_freezes_plan_and_empty_journal_without_android_or_run_begin(self):
        value = self.state()
        self.assertEqual([], value["journal"]["entries"])
        self.assertEqual(STORE.PROTOCOL.plan_digest(self.selected), value["planSha256"])
        self.assertEqual(self.digest, hashlib.sha256((self.run / STORE.STATE).read_bytes()).hexdigest())
        for name in (STORE.PLAN, STORE.STATE):
            self.assertEqual(0o400, stat.S_IMODE((self.run / name).stat().st_mode))
        self.selected["limits"]["maximumAttempts"] = 5
        self.assertNotEqual(self.selected, json.loads((self.run / STORE.PLAN).read_bytes()))
        self.unqualified(self.initial)
        inspected = self.inspect()
        self.assertIn("END_UNAVAILABLE", inspected["validation"]["issues"])
        self.assertFalse(inspected["writerLivenessVerified"])
        self.assertFalse(inspected["durabilityProvenByInspection"])

    def test_exclusive_init_never_overwrites_existing_evidence(self):
        before = (self.run / STORE.STATE).read_bytes()
        value = STORE.operation("init", self.run, plan=plan())
        self.assertEqual("REJECTED", value["status"])
        self.assertEqual(before, (self.run / STORE.STATE).read_bytes())

    def test_append_assigns_actual_host_sequence_clock_and_process_metadata(self):
        lower = time.monotonic_ns() // 1_000_000
        with STORE.JournalStore(self.run) as owned:
            ack = owned.append(event(), self.digest)
            second = owned.append(event("FIXTURE_INTENT", {"fixtureId": FIXTURE_ID}), ack["snapshotSha256"])
        upper = time.monotonic_ns() // 1_000_000
        value = self.state()
        self.assertEqual([0, 1], [item["sequence"] for item in value["journal"]["entries"]])
        self.assertTrue(all(lower <= item["hostElapsedMillis"] <= upper for item in value["journal"]["entries"]))
        self.assertEqual(1, len({item["hostSessionId"] for item in value["journal"]["entries"]}))
        self.assertEqual(os.getpid(), value["writers"][-1]["hostPid"])
        self.assertEqual(STORE.CLOCK_SOURCE, value["writers"][-1]["clockSource"])
        self.assertEqual(64, len(value["writers"][-1]["hostBootIdSha256"]))
        self.assertEqual("APPENDED_DURABLE", second["status"])
        self.unqualified(second)

    def test_supplied_host_metadata_commands_and_invalid_event_never_publish(self):
        for mutation in ({"hostElapsedMillis": 999}, {"hostSessionId": "private"}, {"sequence": 3},
                         {"command": "PRIVATE_SECRET_SENTINEL"}, {"kind": "RUN_COMMAND"}, {"bootCount": True}):
            candidate = event(); candidate.update(mutation)
            value = STORE.operation("append", self.run, event=candidate, expected=self.digest)
            with self.subTest(mutation=mutation):
                self.assertEqual("REJECTED", value["status"])
                self.assertEqual(self.digest, self.inspect()["snapshotSha256"])
                self.assertNotIn("PRIVATE_SECRET_SENTINEL", json.dumps(value))

    def test_cas_stale_digest_and_lost_ack_retry_never_append_twice(self):
        ack = STORE.operation("append", self.run, event=event(), expected=self.digest)
        for expected in ("0" * 64, self.digest):
            value = STORE.operation("append", self.run, event=event(), expected=expected)
            self.assertEqual("CONFLICT", value["status"])
            self.assertEqual(ack["snapshotSha256"], value["snapshotSha256"])
        self.assertEqual(1, self.inspect()["entries"])

    def test_changed_plan_or_noncanonical_state_is_rejected_before_append(self):
        path = self.run / STORE.PLAN
        path.chmod(0o600)
        selected = plan(); selected["limits"]["maximumAttempts"] += 1
        path.write_bytes(STORE.encoded(selected)); path.chmod(0o400)
        value = STORE.operation("append", self.run, event=event(), expected=self.digest)
        self.assertEqual("PLAN_CONTENT_MISMATCH", value["failureCode"])
        self.assertEqual(0, len(self.state()["journal"]["entries"]))

    def test_file_fsync_failure_retains_previous_snapshot_and_temporary(self):
        original = STORE.os.fsync
        def fsync(descriptor):
            if stat.S_ISREG(os.fstat(descriptor).st_mode):
                raise OSError("private injected failure")
            return original(descriptor)
        with mock.patch.object(STORE.os, "fsync", side_effect=fsync):
            value = STORE.operation("append", self.run, event=event(), expected=self.digest)
        self.assertEqual("REJECTED", value["status"])
        self.assertNotIn("private injected", json.dumps(value))
        inspected = self.inspect()
        self.assertEqual(self.digest, inspected["snapshotSha256"])
        self.assertEqual(1, inspected["temporaryFilesRetained"])
        self.assertEqual(0, inspected["entries"])

    def test_directory_fsync_failure_after_rename_is_uncertain_not_ack(self):
        original = STORE.os.fsync
        def fsync(descriptor):
            if stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise OSError("private directory failure")
            return original(descriptor)
        with mock.patch.object(STORE.os, "fsync", side_effect=fsync):
            value = STORE.operation("append", self.run, event=event(), expected=self.digest)
        self.assertEqual("COMMIT_UNCERTAIN", value["status"])
        self.assertEqual("INSPECT_DO_NOT_REPLAY", value["nextStep"])
        inspected = self.inspect()
        self.assertEqual(value["candidateSha256"], inspected["snapshotSha256"])
        self.assertEqual(1, inspected["entries"])
        self.assertEqual("CONFLICT", STORE.operation("append", self.run, event=event(), expected=self.digest)["status"])
        self.unqualified(value)

    def test_live_owner_requires_inspection_after_uncertain_publication(self):
        original = STORE.os.fsync
        def fsync(descriptor):
            if stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise OSError("directory flush unavailable")
            return original(descriptor)
        with STORE.JournalStore(self.run) as owned:
            with mock.patch.object(STORE.os, "fsync", side_effect=fsync):
                with self.assertRaises(STORE.CommitUncertain):
                    owned.append(event(), self.digest)
            with self.assertRaisesRegex(STORE.StoreError, "INSPECTION_REQUIRED"):
                owned.append(event(), self.digest)
            inspected = owned.inspect()
            conflict = owned.append(event(), self.digest)
            self.assertEqual("CONFLICT", conflict["status"])
            self.assertEqual(inspected["snapshotSha256"], conflict["snapshotSha256"])

    def test_rename_error_after_success_is_uncertain_and_never_blindly_repeated(self):
        original = STORE.os.replace
        def replace(*args, **kwargs):
            original(*args, **kwargs)
            raise OSError("lost rename acknowledgement")
        with mock.patch.object(STORE.os, "replace", side_effect=replace):
            value = STORE.operation("append", self.run, event=event(), expected=self.digest)
        self.assertEqual("COMMIT_UNCERTAIN", value["status"])
        self.assertEqual(value["candidateSha256"], self.inspect()["snapshotSha256"])

    def test_rename_failure_without_change_is_still_uncertain_until_inspected(self):
        with mock.patch.object(STORE.os, "replace", side_effect=OSError("rename unavailable")):
            value = STORE.operation("append", self.run, event=event(), expected=self.digest)
        self.assertEqual("COMMIT_UNCERTAIN", value["status"])
        inspected = self.inspect()
        self.assertEqual(self.digest, inspected["snapshotSha256"])
        self.assertEqual(1, inspected["temporaryFilesRetained"])

    def test_close_failure_after_durable_publication_never_returns_plain_rejection(self):
        original = STORE.os.close
        failed = False
        def close(descriptor):
            nonlocal failed
            original(descriptor)
            if not failed:
                failed = True
                raise OSError("late close failure")
        with mock.patch.object(STORE.os, "close", side_effect=close):
            value = STORE.operation("append", self.run, event=event(), expected=self.digest)
        self.assertEqual("COMMIT_UNCERTAIN", value["status"])
        self.assertEqual(STORE.STATE, value["candidateFile"])
        self.assertEqual(value["candidateSha256"], self.inspect()["snapshotSha256"])

    def test_interrupted_initialization_is_not_completed_from_temporary_files(self):
        other = self.root / 'initializing'
        original = STORE.JournalStore._publish
        def publish(owned, name, data, **kwargs):
            if name == STORE.STATE:
                raise OSError("interrupted before state")
            return original(owned, name, data, **kwargs)
        with mock.patch.object(STORE.JournalStore, '_publish', new=publish):
            self.assertEqual("REJECTED", STORE.operation("init", other, plan=plan())["status"])
        before = set(other.iterdir())
        inspected = self.inspect(other)
        self.assertEqual("INITIALIZATION_INCOMPLETE", inspected["status"])
        self.assertFalse(inspected["automaticAdoption"])
        self.assertEqual(before, set(other.iterdir()))

    def test_preexisting_parent_and_leaf_links_are_refused_without_outside_writes(self):
        outside = self.root / 'outside'; outside.mkdir()
        link = self.root / 'alias'; link.symlink_to(outside, target_is_directory=True)
        for path in (link / 'run', link):
            value = STORE.operation("init", path, plan=plan())
            self.assertEqual("REJECTED", value["status"])
        self.assertEqual([], list(outside.iterdir()))
        self.assertEqual("REJECTED", self.inspect(link)["status"])

    def test_state_symlink_hardlink_fifo_and_foreign_content_are_never_followed(self):
        original = (self.run / STORE.STATE).read_bytes()
        outside = self.root / 'external'; outside.write_bytes(b'PRIVATE_EXTERNAL')
        path = self.run / STORE.STATE
        path.unlink(); path.symlink_to(outside)
        self.assertEqual("REJECTED", self.inspect()["status"])
        path.unlink(); os.link(outside, path)
        self.assertEqual("REJECTED", self.inspect()["status"])
        path.unlink(); os.mkfifo(path)
        self.assertEqual("REJECTED", self.inspect()["status"])
        path.unlink(); path.write_bytes(original); path.chmod(0o400)
        (self.run / 'unexpected').write_bytes(b'private')
        self.assertEqual("STORE_CONTENT_INVALID", self.inspect()["failureCode"])
        self.assertEqual(b'PRIVATE_EXTERNAL', outside.read_bytes())

    def test_parent_replacement_at_rename_only_changes_pinned_original_store(self):
        parent = self.root / 'parent'; parent.mkdir()
        moved = self.root / 'parent-original'
        target = parent / 'run'
        initial = STORE.operation('init', target, plan=plan())
        outside = self.root / 'outside'; (outside / 'run').mkdir(parents=True)
        external = outside / 'run' / STORE.STATE; external.write_bytes(b'EXTERNAL_UNCHANGED')
        original = STORE.os.replace
        def replace(*args, **kwargs):
            parent.rename(moved)
            parent.symlink_to(outside, target_is_directory=True)
            return original(*args, **kwargs)
        with mock.patch.object(STORE.os, 'replace', side_effect=replace):
            value = STORE.operation('append', target, event=event(), expected=initial['snapshotSha256'])
        self.assertEqual('COMMIT_UNCERTAIN', value['status'])
        self.assertEqual(b'EXTERNAL_UNCHANGED', external.read_bytes())
        self.assertEqual(1, self.inspect(moved / 'run')['entries'])
        self.assertEqual('REJECTED', self.inspect(target)['status'])

    def test_parent_replacement_before_rename_refuses_and_preserves_previous_snapshot(self):
        parent = self.root / 'parent'; parent.mkdir()
        moved = self.root / 'parent-original'; target = parent / 'run'
        initial = STORE.operation('init', target, plan=plan())
        outside = self.root / 'outside'; (outside / 'run').mkdir(parents=True)
        external = outside / 'run' / STORE.STATE; external.write_bytes(b'EXTERNAL_UNCHANGED')
        original = STORE.os.fsync
        def fsync(descriptor):
            original(descriptor)
            if stat.S_ISREG(os.fstat(descriptor).st_mode):
                parent.rename(moved); parent.symlink_to(outside, target_is_directory=True)
        with mock.patch.object(STORE.os, 'fsync', side_effect=fsync):
            value = STORE.operation('append', target, event=event(), expected=initial['snapshotSha256'])
        self.assertEqual('REJECTED', value['status'])
        self.assertEqual(initial['snapshotSha256'], self.inspect(moved / 'run')['snapshotSha256'])
        self.assertEqual(b'EXTERNAL_UNCHANGED', external.read_bytes())
        self.assertEqual(1, self.inspect(moved / 'run')['temporaryFilesRetained'])

    def test_other_process_lock_excludes_append_and_inspection(self):
        code = "import sys; sys.path.insert(0,sys.argv[1]); import f5_heavy_soak_journal as s\nwith s.JournalStore(sys.argv[2]):\n print('LOCKED',flush=True); sys.stdin.read()\n"
        child = subprocess.Popen([sys.executable, '-B', '-c', code, str(SCRIPT.parent), str(self.run)],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: child.kill() if child.poll() is None else None)
        self.assertEqual('LOCKED', child.stdout.readline().strip())
        self.assertEqual('STORE_BUSY', self.inspect()['failureCode'])
        self.assertEqual('STORE_BUSY', STORE.operation('append', self.run, event=event(), expected=self.digest)['failureCode'])
        child.communicate(input='', timeout=5)
        self.assertEqual(self.digest, self.inspect()['snapshotSha256'])

    def test_same_instance_threaded_cas_serializes_publication_and_keeps_both_acknowledgements_honest(self):
        with STORE.JournalStore(self.run) as owned:
            initial = owned.append(event(), self.digest)
            entered, release, second_done = threading.Event(), threading.Event(), threading.Event()
            outcomes = []
            original = owned._publish
            def publish(*args, **kwargs):
                if threading.current_thread().name == "first-writer":
                    entered.set()
                    if not release.wait(5): raise AssertionError("release timeout")
                return original(*args, **kwargs)
            def append(reason, done=None):
                try:
                    outcomes.append(owned.append(event("OBSERVATION_GAP", {"reason": reason}), initial["snapshotSha256"]))
                finally:
                    if done: done.set()
            with mock.patch.object(owned, "_publish", side_effect=publish):
                first = threading.Thread(target=append, args=("TRANSPORT",), name="first-writer")
                second = threading.Thread(target=append, args=("CADENCE", second_done))
                first.start()
                self.assertTrue(entered.wait(5))
                second.start()
                try:
                    self.assertFalse(second_done.wait(0.1), "second append escaped the instance lock")
                finally:
                    release.set(); first.join(5); second.join(5)
                self.assertFalse(first.is_alive() or second.is_alive())
            self.assertEqual(["APPENDED_DURABLE", "CONFLICT"], sorted(item["status"] for item in outcomes))
            self.assertEqual(2, owned.inspect()["entries"])
            self.assertEqual("TRANSPORT", self.state()["journal"]["entries"][-1]["data"]["reason"])

    def test_inspect_and_close_wait_for_inflight_append_before_descriptor_lifecycle_changes(self):
        owned = STORE.JournalStore(self.run)
        entered, release, inspected, closed = (threading.Event() for _ in range(4))
        original = owned._publish
        outcomes = []
        def publish(*args, **kwargs):
            entered.set()
            if not release.wait(5): raise AssertionError("release timeout")
            return original(*args, **kwargs)
        def inspect():
            try: outcomes.append(owned.inspect())
            except STORE.StoreError as error: outcomes.append(str(error))
            finally: inspected.set()
        def close():
            owned.close(); closed.set()
        with mock.patch.object(owned, "_publish", side_effect=publish):
            writer = threading.Thread(target=lambda: outcomes.append(owned.append(event(), self.digest)))
            reader = threading.Thread(target=inspect)
            closer = threading.Thread(target=close)
            writer.start(); self.assertTrue(entered.wait(5)); reader.start(); closer.start()
            try:
                self.assertFalse(inspected.wait(0.1))
                self.assertFalse(closed.is_set())
            finally:
                release.set()
                for thread in (writer, reader, closer): thread.join(5)
                owned.close()
            self.assertFalse(any(thread.is_alive() for thread in (writer, reader, closer)))
        self.assertTrue(closed.is_set() and inspected.is_set())
        self.assertEqual(1, self.inspect()["entries"])
        self.assertTrue(any(type(value) is dict and value["status"] == "APPENDED_DURABLE" for value in outcomes))

    def test_initialize_is_serialized_and_cannot_overwrite_another_thread_initialization(self):
        target = self.root / "new-threaded"
        with STORE.JournalStore(target, create=True) as owned:
            start = threading.Barrier(3)
            outcomes = []
            def initialize():
                start.wait()
                try: outcomes.append(owned.initialize(plan())["status"])
                except STORE.StoreError as error: outcomes.append(str(error))
            threads = [threading.Thread(target=initialize) for _ in range(2)]
            for thread in threads: thread.start()
            start.wait()
            for thread in threads: thread.join(5)
            self.assertEqual(["EXCLUSIVE_NEW_STORE_REQUIRED", "INITIALIZED_DURABLE"], sorted(outcomes))
            self.assertEqual(0, owned.inspect()["entries"])

    def test_fork_child_checks_pid_before_inherited_mutex_and_can_close_its_descriptors(self):
        with STORE.JournalStore(self.run) as owned:
            held, release = threading.Event(), threading.Event()
            def hold():
                with owned._mutex:
                    held.set(); release.wait(5)
            thread = threading.Thread(target=hold)
            thread.start(); self.assertTrue(held.wait(5))
            reader, writer = os.pipe()
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", DeprecationWarning)
                pid = os.fork()
            if pid == 0:
                os.close(reader)
                signal.alarm(3)
                try:
                    try: owned.inspect()
                    except STORE.StoreError as error: os.write(writer, str(error).encode())
                    owned.close()
                finally: os._exit(0)
            os.close(writer)
            try:
                self.assertEqual(b"STORE_OWNER_CHANGED", os.read(reader, 100))
                _, status = os.waitpid(pid, 0)
                self.assertEqual(0, status)
            finally:
                os.close(reader); release.set(); thread.join(5)
            self.assertEqual(self.digest, owned.inspect()["snapshotSha256"])

    def test_inherited_open_store_is_not_owned_by_forked_child(self):
        with STORE.JournalStore(self.run) as owned:
            reader, writer = os.pipe()
            pid = os.fork()
            if pid == 0:
                os.close(reader)
                try:
                    owned.inspect()
                    os.write(writer, b'BAD')
                except STORE.StoreError as error:
                    os.write(writer, str(error).encode())
                finally:
                    os._exit(0)
            os.close(writer)
            try:
                self.assertEqual(b'STORE_OWNER_CHANGED', os.read(reader, 100))
            finally:
                os.close(reader); os.waitpid(pid, 0)
            self.assertEqual(self.digest, owned.inspect()['snapshotSha256'])

    def crash_writer(self, stage):
        marker = self.root / ('checkpoint-' + stage)
        code = r'''
import os,signal,stat,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import f5_heavy_soak_journal as s
path,digest,stage,marker=sys.argv[2:]
def stop():
    Path(marker).write_text(str(os.getpid()))
    while True: signal.pause()
replace=s.os.replace
fsync=s.os.fsync
def replacement(*args,**kwargs):
    if stage=='before_rename': stop()
    replace(*args,**kwargs)
    if stage=='after_rename': stop()
def sync(descriptor):
    fsync(descriptor)
    if stage=='after_directory_fsync' and stat.S_ISDIR(os.fstat(descriptor).st_mode): stop()
s.os.replace=replacement
s.os.fsync=sync
with s.JournalStore(path) as owned:
    owned.append({'kind':'RUN_BEGIN','data':{},'bootCount':7,'deviceElapsedMillis':100},digest)
'''
        child = subprocess.Popen([sys.executable, '-B', '-c', code, str(SCRIPT.parent), str(self.run), self.digest, stage, str(marker)],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while not marker.exists() and child.poll() is None and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(marker.exists(), child.communicate(timeout=1) if child.poll() is not None else 'checkpoint timeout')
            self.assertEqual(child.pid, int(marker.read_text()))
            child.kill()
            child.communicate(timeout=5)
            self.assertEqual(-signal.SIGKILL, child.returncode)
        finally:
            if child.poll() is None:
                child.kill(); child.communicate(timeout=5)
        return self.inspect()

    def test_actual_host_sigkill_before_rename_preserves_old_snapshot_and_temporary(self):
        inspected = self.crash_writer('before_rename')
        self.assertEqual(self.digest, inspected['snapshotSha256'])
        self.assertEqual(0, inspected['entries'])
        self.assertEqual(1, inspected['temporaryFilesRetained'])
        self.unqualified(inspected)

    def test_actual_host_sigkill_after_rename_requires_inspection_and_cas_conflict(self):
        inspected = self.crash_writer('after_rename')
        self.assertNotEqual(self.digest, inspected['snapshotSha256'])
        self.assertEqual(1, inspected['entries'])
        self.assertEqual('CONFLICT', STORE.operation('append', self.run, event=event(), expected=self.digest)['status'])
        self.assertFalse(inspected['durabilityProvenByInspection'])
        self.unqualified(inspected)

    def test_actual_host_sigkill_after_directory_fsync_lost_ack_never_replays(self):
        inspected = self.crash_writer('after_directory_fsync')
        self.assertEqual(1, inspected['entries'])
        self.assertEqual('CONFLICT', STORE.operation('append', self.run, event=event(), expected=self.digest)['status'])
        self.assertFalse(inspected['automaticResume'])

    def test_new_process_session_is_real_and_cannot_claim_old_continuity(self):
        ack = STORE.operation('append', self.run, event=event(), expected=self.digest)
        selected = self.root / 'event.json'
        selected.write_text(json.dumps(event('OBSERVATION_GAP', {'reason': 'HOST_INTERRUPTED'})))
        completed = subprocess.run([sys.executable, '-B', str(SCRIPT), 'append', '--directory', str(self.run),
                                    '--event', str(selected), '--expected-snapshot-sha256', ack['snapshotSha256']],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=False)
        self.assertEqual(0, completed.returncode)
        self.unqualified(json.loads(completed.stdout))
        inspected = self.inspect()
        self.assertIn('HOST_SESSION_DISCONTINUITY', inspected['validation']['issues'])
        entries = self.state()['journal']['entries']
        self.assertNotEqual(entries[0]['hostSessionId'], entries[1]['hostSessionId'])

    def test_closed_cli_inspect_never_changes_files_or_echoes_raw_errors(self):
        before = {item.name: item.read_bytes() for item in self.run.iterdir()}
        completed = subprocess.run([sys.executable, '-B', str(SCRIPT), 'inspect', '--directory', str(self.run)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=False)
        self.assertEqual(0, completed.returncode)
        self.unqualified(json.loads(completed.stdout))
        self.assertEqual(before, {item.name: item.read_bytes() for item in self.run.iterdir()})
        self.assertNotIn(str(self.root).encode(), completed.stdout + completed.stderr)


if __name__ == '__main__':
    unittest.main()
