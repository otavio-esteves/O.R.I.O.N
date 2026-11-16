"""Additive v2 new-only invocation journal, reusing the v1 store's anchored filesystem primitives.

No v1 parser/state is changed. Inspection never authorizes work/resumption.
"""
import copy
import os
import stat
import threading
import uuid

import f5_heavy_soak_journal as base
import f5_instrumented_workload_protocol as P
from qualification_host_clock import HostClock


class InvocationJournal(base.JournalStore):
    def _publish(self, name, data, *, initial=False):
        """Pinned unnamed inode; acknowledge only the actual published content after fsync."""
        bound = P.MAX_BYTES if name == base.PLAN else P.MAX_STORE_BYTES
        P.require(type(data) is bytes and 0 < len(data) <= bound, 'OUTPUT_BOUND_EXCEEDED')
        self._check_chain()
        temporary_flag = getattr(os,'O_TMPFILE',None)
        P.require(type(temporary_flag) is int, 'UNNAMED_PUBLICATION_UNAVAILABLE')
        fd = os.open('.',os.O_RDWR|os.O_CLOEXEC|temporary_flag,0o600,dir_fd=self.fd)
        original = None; result = None
        try:
            meta = os.fstat(fd)
            P.require(stat.S_ISREG(meta.st_mode) and meta.st_nlink == 0 and meta.st_uid == os.geteuid() and
                      stat.S_IMODE(meta.st_mode) == 0o600, 'TEMPORARY_INODE_INVALID')
            owned = (meta.st_dev,meta.st_ino)
            pending = memoryview(data)
            while pending:
                written = os.write(fd,pending)
                P.require(type(written) is int and 0 < written <= len(pending), 'PUBLICATION_WRITE_INVALID')
                pending = pending[written:]
            os.fchmod(fd,0o400); os.fsync(fd); self._check_chain()
            def member(selected):
                current = os.stat(selected,dir_fd=self.fd,follow_symlinks=False)
                P.require(stat.S_ISREG(current.st_mode) and (current.st_dev,current.st_ino) == owned and
                          current.st_nlink == 1 and current.st_uid == os.geteuid() and
                          stat.S_IMODE(current.st_mode) == 0o400 and current.st_size == len(data), 'PUBLICATION_INODE_CHANGED')
            if initial:
                # Atomic no-overwrite link from our own descriptor. No named temporary can be swapped.
                os.link('/proc/self/fd/'+str(fd),name,dst_dir_fd=self.fd,follow_symlinks=True)
            else:
                temporary = '.pending-'+str(uuid.uuid4())
                os.link('/proc/self/fd/'+str(fd),temporary,dst_dir_fd=self.fd,follow_symlinks=True)
                member(temporary)
                os.replace(temporary,name,src_dir_fd=self.fd,dst_dir_fd=self.fd)
            member(name)
            actual = self._read(name,bound)
            P.require(actual == data, 'PUBLICATION_CONTENT_CHANGED')
            os.fsync(self.fd); self._check_chain(); member(name)
            result = P.sha(actual)
        except BaseException as error:
            original = error
        finally:
            # Never unlink a mutable pathname, or let descriptor cleanup replace an original interruption.
            try: os.close(fd)
            except BaseException as error:
                if original is None: original = error
        if original is not None:
            self.inspection_required = True
            if isinstance(original,Exception): raise base.CommitUncertain(P.sha(data),name) from None
            raise original
        return result

    def __init__(self, path, plan):
        P.validate_plan(plan)
        self._plan = copy.deepcopy(plan)
        self._clock = HostClock.linux()
        self._previous = None
        self._metadata = dict(self._clock.metadata)
        self._clock_value()
        try:
            super().__init__(path, create=True)
            self._initialize_v2()
        except BaseException:
            if hasattr(self, 'chain'):
                try: self.close()
                except BaseException: pass
            raise

    def _clock_value(self):
        now = self._clock.read_ns()
        P.require(self._clock.metadata == self._metadata and self._metadata['clockSource'] == 'CLOCK_BOOTTIME' and
                  self._metadata['evidence'] == 'HOST_CLOCK_ONLY' and self._metadata['bootIdentitySha256'] is not None,
                  'HOST_CLOCK_INVALID')
        P.integer(now, self._previous or 0); self._previous = now
        return now

    def _inventory(self):
        temporary = 0
        for name in self._names():
            prefix = next((v for v in ['runtime-','status-','assembly-'] if name.startswith(v) and name.endswith('.json')),None)
            runtime = prefix is not None
            P.require(name in (base.PLAN, base.STATE) or name.startswith('.pending-') or runtime, 'STORE_CONTENT_INVALID')
            metadata = os.stat(name, dir_fd=self.fd, follow_symlinks=False)
            P.require(stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1 and metadata.st_uid == os.geteuid(),
                      'STORE_FILE_INVALID')
            if runtime:
                P.identifier(name[len(prefix):-5])
                limit = {'runtime-':4*1024*1024,'status-':1024,'assembly-':4096}[prefix]
                P.require(metadata.st_size <= limit and stat.S_IMODE(metadata.st_mode) == 0o400)
            elif name.startswith('.pending-'):
                P.identifier(name.removeprefix('.pending-')); temporary += 1
        return temporary

    def _snapshot(self):
        self._check_chain(); temporary = self._inventory()
        plan_bytes = self._read(base.PLAN, P.MAX_BYTES); plan = P.parse(plan_bytes)
        P.validate_plan(plan); P.require(plan_bytes == P.encoded(plan))
        data = self._read(base.STATE, P.MAX_STORE_BYTES); state = P.parse(data, P.MAX_STORE_BYTES)
        P.fields(state, {'schemaVersion', 'kind', 'journal'})
        P.require(state['schemaVersion'] == 2 and state['kind'] == 'ORION_F5_INVOCATION_STORE')
        validation = P.validate_journal(plan, state['journal'])
        referenced = set()
        for event in state['journal']['entries']:
            if event['kind'] != 'RUNTIME_ARTIFACTS': continue
            item = event['data']
            for prefix,key,limit in [('runtime-','runtimeSha256',4*1024*1024),('status-','statusSha256',1024),('assembly-','reportSha256',4096)]:
                if item[key] is None: continue
                name = prefix+item['attemptId']+'.json'; referenced.add(name)
                P.require(P.sha(self._read(name,limit)) == item[key], 'ARTIFACT_CONTENT_CHANGED')
        retained = {name for name in self._names() if name.startswith(('runtime-','status-','assembly-'))}
        validation['unreferencedArtifactFiles'] = len(retained-referenced)
        P.require(data == P.encoded(state) and len(P.encoded(state['journal'])) <= P.MAX_JOURNAL_BYTES)
        self._check_chain()
        return plan, state, P.sha(data), validation, temporary

    def _initialize_v2(self):
        self._check_chain(); P.require(self.created and not self._names(), 'EXCLUSIVE_NEW_STORE_REQUIRED')
        journal = dict(schemaVersion=2, kind='ORION_F5_INVOCATION_JOURNAL', planSha256=P.sha(P.encoded(self._plan)),
                       hostTiming=self._metadata, entries=[])
        state = dict(schemaVersion=2, kind='ORION_F5_INVOCATION_STORE', journal=journal)
        P.validate_journal(self._plan, journal)
        self._publish(base.PLAN, P.encoded(self._plan), initial=True)
        self.digest = self._publish(base.STATE, P.encoded(state), initial=True)
        self.entries = 0
        self.initial_ack = self._ack('INITIALIZED_DURABLE')

    def _ack(self, status, previous=None):
        return dict(schemaVersion=2, kind='ORION_F5_INVOCATION_ACK', status=status, previousSnapshotSha256=previous,
                    snapshotSha256=self.digest, entries=self.entries, hostClockSource='CLOCK_BOOTTIME')

    def append(self, kind, data, expected):
        P.require(os.getpid() == self.owner_pid, 'STORE_OWNER_CHANGED')
        with self._mutex:
            self._check_chain(); P.require(not self.inspection_required, 'INSPECTION_REQUIRED')
            plan, state, current, _, _ = self._snapshot()
            P.require(current == expected == self.digest, 'CAS_CONFLICT')
            next_state = copy.deepcopy(state)
            entries = next_state['journal']['entries']
            entries.append(dict(sequence=len(entries), hostElapsedNanoseconds=self._clock_value(), kind=kind, data=copy.deepcopy(data)))
            P.validate_journal(plan, next_state['journal'])
            P.require(len(P.encoded(next_state['journal'])) <= P.MAX_JOURNAL_BYTES, 'OUTPUT_BOUND_EXCEEDED')
            P.require(self._snapshot()[2] == current, 'CONCURRENT_CONTENT_CHANGE')
            self.digest = self._publish(base.STATE, P.encoded(next_state))
            self.entries = len(entries)
            return self._ack('APPENDED_DURABLE', current)

    def retain_assembly(self, attempt_id, assembly):
        """Only #158-validated runtime/status/report bytes reach disk. Raw SOURCE is not persisted."""
        P.identifier(attempt_id)
        values = [('runtime-',assembly.runtime_json,4*1024*1024),('status-',assembly.status_json,1024),
                  ('assembly-',assembly.report_json,4096)]
        hashes = []
        with self._mutex:
            self._check_chain(); P.require(os.getpid() == self.owner_pid)
            for prefix,data,limit in values:
                if data is None:
                    P.require(prefix == 'runtime-'); hashes.append(None); continue
                P.require(type(data) is bytes and 0 < len(data) <= limit and data.isascii(), 'ARTIFACT_BOUND_INVALID')
                name = prefix + attempt_id + '.json'
                hashes.append(self._publish(name,data,initial=True))
            os.fsync(self.fd); self._check_chain()
            return dict(attemptId=attempt_id,runtimeSha256=hashes[0],statusSha256=hashes[1],reportSha256=hashes[2])

    def inspect(self):
        P.require(os.getpid() == self.owner_pid, 'STORE_OWNER_CHANGED')
        with self._mutex:
            _, state, digest, validation, temporary = self._snapshot()
            return dict(schemaVersion=2, kind='ORION_F5_INVOCATION_INSPECTION', snapshotSha256=digest,
                        journal=copy.deepcopy(state['journal']), validation=validation, temporaryFilesRetained=temporary,
                        automaticResume=False, qualification='NOT_QUALIFIED',**{key:False for key in P.FALSE_FLAGS})

    # No inherited v1 initialization/append selector may reinterpret this store.
    def initialize(self, _):
        raise P.Invalid('V1_OPERATION_FORBIDDEN')


def inspect_existing(path):
    """Read-only evidence inspection. Never restores live state or a WORK-capable object."""
    reader = InvocationJournal.__new__(InvocationJournal)
    try:
        base.JournalStore.__init__(reader, path, create=False)
        return reader.inspect()
    finally:
        if hasattr(reader, 'chain'): reader.close()
