#!/usr/bin/env python3
"""Linux HOST durable evidence store. No executor, device commands or qualification."""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import threading
import time
import uuid

import f5_heavy_soak_evidence_protocol as PROTOCOL

PLAN = "plan.json"
STATE = "state.json"
MAX_STATE_BYTES = 4 * 1024 * 1024
CLOCK_SOURCE = "PYTHON_TIME_MONOTONIC_NS"
_SESSION = None
_DIRECTORY = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


class StoreError(ValueError):
    pass


class CommitUncertain(StoreError):
    def __init__(self, candidate, name=STATE):
        super().__init__("COMMIT_UNCERTAIN")
        self.candidate = candidate
        self.name = name


def require(condition, code):
    if not condition:
        raise StoreError(code)


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def host_writer():
    """Fresh interpreter/PID session; fork never inherits the parent's clock ownership."""
    global _SESSION
    descriptor = os.open("/proc/sys/kernel/random/boot_id", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        boot = stream.read(129).strip()
    require(len(boot) == 36 and str(uuid.UUID(boot.decode("ascii"))).encode("ascii") == boot, "HOST_BOOT_UNAVAILABLE")
    boot_digest = sha(boot)
    if _SESSION is None or _SESSION[0] != os.getpid() or _SESSION[1] != boot_digest:
        _SESSION = (os.getpid(), boot_digest, str(uuid.uuid4()))
    return {"hostSessionId": _SESSION[2], "hostBootIdSha256": boot_digest, "hostPid": os.getpid(), "clockSource": CLOCK_SOURCE}


def result(status, **values):
    return {"schemaVersion": 1, "result": "UNVERIFIED_INPUT", "qualification": "NOT_QUALIFIED",
            "environment": "HOST", "status": status, "physicalEvidenceVerified": False,
            "real24HourDurationProven": False, "autonomyProven": False, "profileQualified": False,
            "productionInferenceEnabled": False, "fullF5GateClosed": False, "fullSoakGateClosed": False,
            "commandsDispatched": False, **values}


def _parse(data):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, "DUPLICATE_FIELD")
            value[key] = item
        return value
    def invalid(_):
        raise StoreError("NUMBER_INVALID")
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique, parse_float=invalid, parse_constant=invalid)
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise StoreError("JSON_INVALID") from None


class JournalStore:
    """One live lock owner. Only initialize/append/inspect mutate or observe owned HOST files."""
    def __init__(self, path, *, create=False):
        self._mutex = threading.RLock()
        self.chain = []
        self.locked = False
        self.fd = None
        self.created = False
        self.owner_pid = os.getpid()
        self.inspection_required = False
        self.path = Path(path)
        try:
            absolute = self.path.absolute()
            require(".." not in absolute.parts and len(absolute.parts) > 1, "STORE_PATH_INVALID")
            root = os.open("/", _DIRECTORY)
            self.chain.append((None, None, root))
            parent = root
            for index, part in enumerate(absolute.parts[1:]):
                if create and index == len(absolute.parts) - 2:
                    os.mkdir(part, mode=0o700, dir_fd=parent)
                    self.created = True
                    os.fsync(parent)
                child = os.open(part, _DIRECTORY, dir_fd=parent)
                self.chain.append((parent, part, child))
                parent = child
            self.fd = parent
            metadata = os.fstat(self.fd)
            require(metadata.st_uid == os.geteuid() and stat.S_IMODE(metadata.st_mode) == 0o700, "STORE_OWNERSHIP_INVALID")
            try:
                fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.locked = True
            except BlockingIOError:
                raise StoreError("STORE_BUSY") from None
            self._check_chain()
        except Exception:
            self.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        # A fork child must never wait for a lock inherited from a vanished thread.
        if self.owner_pid != os.getpid():
            self._close_descriptors()
            return
        with self._mutex:
            self._close_descriptors()

    def _close_descriptors(self):
        failure = None
        for _, _, descriptor in reversed(self.chain):
            try:
                os.close(descriptor)
            except OSError as error:
                failure = error
        self.chain = []
        self.fd = None
        self.locked = False
        if failure is not None:
            raise failure

    def _check_chain(self):
        require(self.locked and self.fd is not None, "STORE_CLOSED")
        require(self.owner_pid == os.getpid(), "STORE_OWNER_CHANGED")
        for parent, name, child in self.chain[1:]:
            linked = os.stat(name, dir_fd=parent, follow_symlinks=False)
            pinned = os.fstat(child)
            require(stat.S_ISDIR(linked.st_mode) and (linked.st_dev, linked.st_ino) == (pinned.st_dev, pinned.st_ino),
                    "STORE_PATH_REPLACED")

    def _read(self, name, maximum):
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=self.fd)
        with os.fdopen(descriptor, "rb") as stream:
            metadata = os.fstat(stream.fileno())
            require(stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1 and metadata.st_uid == os.geteuid() and
                    stat.S_IMODE(metadata.st_mode) == 0o400 and 0 < metadata.st_size <= maximum, "STORE_FILE_INVALID")
            data = stream.read(maximum + 1)
            require(len(data) <= maximum, "STORE_FILE_INVALID")
        return data

    def _names(self):
        names = []
        with os.scandir(self.fd) as entries:
            for item in entries:
                require(len(names) < PROTOCOL.MAX_ENTRIES + 4, "STORE_FILE_BOUND_EXCEEDED")
                names.append(item.name)
        return names

    def _inventory(self):
        temporary_count = 0
        for name in self._names():
            require(name in (PLAN, STATE) or name.startswith(".pending-"), "STORE_CONTENT_INVALID")
            metadata = os.stat(name, dir_fd=self.fd, follow_symlinks=False)
            require(stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1 and metadata.st_uid == os.geteuid(),
                    "STORE_FILE_INVALID")
            if name.startswith(".pending-"):
                PROTOCOL.identifier(name.removeprefix(".pending-"))
                temporary_count += 1
        return temporary_count

    def _snapshot(self):
        self._check_chain()
        temporary_count = self._inventory()
        plan_data = self._read(PLAN, PROTOCOL.MAX_BYTES)
        plan = _parse(plan_data)
        PROTOCOL.validate_plan(plan)
        require(plan_data == encoded(plan), "PLAN_NOT_CANONICAL")
        data = self._read(STATE, MAX_STATE_BYTES)
        state = _parse(data)
        PROTOCOL.fields(state, {"schemaVersion", "kind", "planSha256", "writers", "journal"})
        PROTOCOL.integer(state["schemaVersion"], 1, 1)
        PROTOCOL.one_of(state["kind"], {"HEAVY_SOAK_HOST_STORE"})
        require(state["planSha256"] == PROTOCOL.plan_digest(plan), "PLAN_CONTENT_MISMATCH")
        writers = state["writers"]
        require(type(writers) is list and 1 <= len(writers) <= PROTOCOL.MAX_ENTRIES + 1, "WRITER_BOUND_INVALID")
        sessions = set()
        for writer in writers:
            PROTOCOL.fields(writer, {"hostSessionId", "hostBootIdSha256", "hostPid", "clockSource"})
            PROTOCOL.identifier(writer["hostSessionId"])
            PROTOCOL.digest(writer["hostBootIdSha256"])
            PROTOCOL.integer(writer["hostPid"], 1, 2**31 - 1)
            require(writer["clockSource"] == CLOCK_SOURCE and writer["hostSessionId"] not in sessions, "WRITER_INVALID")
            sessions.add(writer["hostSessionId"])
        validation = PROTOCOL.validate(plan, state["journal"])
        require(validation["structuralStatus"] == "VALID", "JOURNAL_INVALID")
        require(all(event["hostSessionId"] in sessions for event in state["journal"]["entries"]), "WRITER_REFERENCE_INVALID")
        require(len(encoded(state["journal"])) <= PROTOCOL.MAX_BYTES and data == encoded(state), "STATE_NOT_CANONICAL_OR_OVERSIZED")
        self._check_chain()
        return plan, state, sha(data), validation, temporary_count

    def _publish(self, name, data, *, initial=False):
        """No unlink/adoption of temporary evidence; a failed operation keeps whatever was written."""
        require(len(data) <= (PROTOCOL.MAX_BYTES if name == PLAN else MAX_STATE_BYTES), "OUTPUT_BOUND_EXCEEDED")
        self._check_chain()
        temporary = ".pending-" + str(uuid.uuid4())
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=self.fd)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fchmod(stream.fileno(), 0o400)
            os.fsync(stream.fileno())
        self._check_chain()
        if initial:
            try:
                os.stat(name, dir_fd=self.fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise StoreError("DESTINATION_EXISTS")
        try:
            os.replace(temporary, name, src_dir_fd=self.fd, dst_dir_fd=self.fd)
            os.fsync(self.fd)
            self._check_chain()
        except (OSError, StoreError):
            # A rename attempt can have completed before its failure/ACK is observed.
            self.inspection_required = True
            raise CommitUncertain(sha(data), name) from None
        return sha(data)

    def initialize(self, plan):
        require(self.owner_pid == os.getpid(), "STORE_OWNER_CHANGED")
        with self._mutex:
            return self._initialize(plan)

    def _initialize(self, plan):
        self._check_chain()
        require(self.created and not self._names(), "EXCLUSIVE_NEW_STORE_REQUIRED")
        PROTOCOL.validate_plan(plan)
        frozen = copy.deepcopy(plan)
        writer = host_writer()
        journal = {"schemaVersion": 1, "kind": "POST_CORTEX_HEAVY_SOAK_JOURNAL", "planId": frozen["planId"],
                   "planSha256": PROTOCOL.plan_digest(frozen), "identity": copy.deepcopy(frozen["identity"]), "entries": []}
        state = {"schemaVersion": 1, "kind": "HEAVY_SOAK_HOST_STORE", "planSha256": PROTOCOL.plan_digest(frozen),
                 "writers": [writer], "journal": journal}
        require(PROTOCOL.validate(frozen, journal)["structuralStatus"] == "VALID", "JOURNAL_INVALID")
        self._publish(PLAN, encoded(frozen), initial=True)
        current = self._publish(STATE, encoded(state), initial=True)
        return result("INITIALIZED_DURABLE", snapshotSha256=current, planSha256=state["planSha256"], entries=0,
                      hostClockSource=CLOCK_SOURCE)

    def append(self, event, expected_snapshot_sha256):
        require(self.owner_pid == os.getpid(), "STORE_OWNER_CHANGED")
        with self._mutex:
            return self._append(event, expected_snapshot_sha256)

    def _append(self, event, expected_snapshot_sha256):
        self._check_chain()
        require(not self.inspection_required, "INSPECTION_REQUIRED")
        PROTOCOL.digest(expected_snapshot_sha256)
        PROTOCOL.fields(event, {"kind", "data", "bootCount", "deviceElapsedMillis"})
        plan, state, current, _, _ = self._snapshot()
        if current != expected_snapshot_sha256:
            return result("CONFLICT", snapshotSha256=current, entries=len(state["journal"]["entries"]))
        writer = host_writer()
        candidate = copy.deepcopy(state)
        known = {item["hostSessionId"]: item for item in candidate["writers"]}
        if writer["hostSessionId"] in known:
            require(known[writer["hostSessionId"]] == writer, "WRITER_INVALID")
        else:
            candidate["writers"].append(writer)
        entries = candidate["journal"]["entries"]
        entries.append({**copy.deepcopy(event), "sequence": len(entries), "hostSessionId": writer["hostSessionId"],
                        "hostElapsedMillis": time.monotonic_ns() // 1_000_000})
        validation = PROTOCOL.validate(plan, candidate["journal"])
        require(validation["structuralStatus"] == "VALID", "APPEND_INVALID")
        require(len(encoded(candidate["journal"])) <= PROTOCOL.MAX_BYTES and len(candidate["writers"]) <= PROTOCOL.MAX_ENTRIES + 1,
                "OUTPUT_BOUND_EXCEEDED")
        # Recheck the exact committed files before publication; cooperative writers also hold flock.
        _, _, reread, _, _ = self._snapshot()
        require(reread == current, "CONCURRENT_CONTENT_CHANGE")
        new_digest = self._publish(STATE, encoded(candidate))
        return result("APPENDED_DURABLE", previousSnapshotSha256=current, snapshotSha256=new_digest,
                      entries=len(entries), hostClockSource=CLOCK_SOURCE)

    def inspect(self):
        require(self.owner_pid == os.getpid(), "STORE_OWNER_CHANGED")
        with self._mutex:
            return self._inspect()

    def _inspect(self):
        self._check_chain()
        count = self._inventory()
        names = self._names()
        if PLAN not in names or STATE not in names:
            return result("INITIALIZATION_INCOMPLETE", temporaryFilesRetained=count,
                          automaticAdoption=False, automaticResume=False)
        _, state, current, validation, count = self._snapshot()
        self.inspection_required = False
        return result("SNAPSHOT_UNVERIFIED", snapshotSha256=current, planSha256=state["planSha256"],
                      entries=len(state["journal"]["entries"]), temporaryFilesRetained=count,
                      lastRecordedWriter=state["writers"][-1], writerLivenessVerified=False,
                      durabilityProvenByInspection=False, validation=validation,
                      automaticAdoption=False, automaticResume=False)


def operation(kind, path, *, plan=None, event=None, expected=None):
    """Closed operation selector; no caller-supplied callable or command is executed."""
    acknowledged = None
    try:
        require(kind in ("init", "append", "inspect"), "OPERATION_INVALID")
        if kind == "init":
            PROTOCOL.validate_plan(plan)
        with JournalStore(path, create=kind == "init") as store:
            if kind == "init":
                acknowledged = store.initialize(plan)
            elif kind == "append":
                acknowledged = store.append(event, expected)
            else:
                acknowledged = store.inspect()
        return acknowledged
    except CommitUncertain as error:
        return result("COMMIT_UNCERTAIN", candidateFile=error.name, candidateSha256=error.candidate, nextStep="INSPECT_DO_NOT_REPLAY")
    except (StoreError, PROTOCOL.Invalid) as error:
        return result("REJECTED", failureCode=str(error))
    except (OSError, ValueError, RecursionError):
        if acknowledged and acknowledged["status"] in ("INITIALIZED_DURABLE", "APPENDED_DURABLE"):
            return result("COMMIT_UNCERTAIN", candidateFile=STATE, candidateSha256=acknowledged["snapshotSha256"],
                          nextStep="INSPECT_DO_NOT_REPLAY")
        return result("REJECTED", failureCode="HOST_STORAGE_UNAVAILABLE")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    initialize = commands.add_parser("init")
    initialize.add_argument("--directory", type=Path, required=True)
    initialize.add_argument("--plan", type=Path, required=True)
    append = commands.add_parser("append")
    append.add_argument("--directory", type=Path, required=True)
    append.add_argument("--event", type=Path, required=True)
    append.add_argument("--expected-snapshot-sha256", required=True)
    inspect = commands.add_parser("inspect")
    inspect.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        options = {"plan": PROTOCOL.document(args.plan)} if args.operation == "init" else (
            {"event": PROTOCOL.document(args.event), "expected": args.expected_snapshot_sha256} if args.operation == "append" else {})
        report = operation(args.operation, args.directory, **options)
    except (OSError, ValueError, RecursionError):
        report = result("REJECTED", failureCode="INPUT_UNAVAILABLE_OR_INVALID")
    print(json.dumps(report, sort_keys=True, indent=2))
    return 0 if report["status"] in ("INITIALIZED_DURABLE", "APPENDED_DURABLE", "SNAPSHOT_UNVERIFIED", "INITIALIZATION_INCOMPLETE") else 2


if __name__ == "__main__":
    raise SystemExit(main())
