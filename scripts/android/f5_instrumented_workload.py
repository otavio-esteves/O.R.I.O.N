#!/usr/bin/env python3
"""Executable finite instrumented-foreground HOST workflow. Never a Core admission source."""
import argparse
import copy
import json
import os
from pathlib import Path
import threading
import time
import uuid

import f5_instrumented_workload_protocol as P
from f5_instrumented_workload_journal import InvocationJournal
from f5_instrumented_workload_adapters import FixedAdb, BoundDevice, InstrumentedProducer, TargetLock
from qualification_host_clock import HostClock


class Stop(RuntimeError):
    def __init__(self, reason): self.reason = reason; super().__init__(reason)


def unavailable_resources():
    return {k: ('UNAVAILABLE' if k in {'serviceState','memoryPressure'} else None) for k in P.RESOURCE_KEYS}


class InstrumentedWorkload:
    """One new run, one graph nonce, at most one WORK. Observations never replace work.

    The producer is a concrete InstrumentedProducer in the CLI. Synthetic test
    producers exercise this orchestration, not Android or admission authority.
    """
    def __init__(self, directory, plan, producer):
        P.validate_plan(plan)
        self.plan = copy.deepcopy(plan); self.producer = producer; self._pid = os.getpid()
        self.clock = HostClock.linux(); self._clock_lock = threading.Lock()
        self._meta = dict(self.clock.metadata); self._before = self._after = self._host = None
        self._now()
        self.journal = InvocationJournal(directory, self.plan)
        try: self._ack(self.journal.initial_ack, None, 0, 'INITIALIZED_DURABLE')
        except BaseException:
            self.journal.close(); raise
        self._digest = self.journal.digest; self._entries = 0
        self._remote = threading.Lock(); self._state = threading.RLock(); self._journal_lock = threading.Lock()
        self._used = False; self._active = False; self._unknown = False; self._halted = False
        self._opened = False; self._cleanup_selected = False; self._cleaned = False; self._interrupted = False
        self._failure = None; self._worker = None; self._start = self._last_complete = None
        self._fatal = None
        self._last_work = self._resource_fence = None; self._resource_deaths = 0
        self._incidents = set(); self._last_complete_device = None
        self._selected_offsets = set(); self._window_observed = False
        self._bootstrap_selected = False
        self._bootstrap_ready = self.plan['bootstrapOperation'] == 'NONE'

    def _now(self):
        with self._clock_lock:
            before = time.monotonic_ns(); now = self.clock.read_ns(); after = time.monotonic_ns()
            P.require(self.clock.metadata == self._meta and self._meta['clockSource'] == 'CLOCK_BOOTTIME' and
                      self._meta['evidence'] == 'HOST_CLOCK_ONLY' and self._meta['bootIdentitySha256'] is not None,
                      'HOST_CLOCK_INVALID')
            if self._host is not None:
                P.require(now >= self._host and before >= self._after and now-self._host <= after-self._before,
                          'HOST_SUSPEND_OR_DISCONTINUITY')
            self._host, self._before, self._after = now, before, after
            return now

    def _ack(self, ack, previous, entries, status):
        P.fields(ack, {'schemaVersion','kind','status','previousSnapshotSha256','snapshotSha256','entries','hostClockSource'})
        P.require(type(ack['schemaVersion']) is int and ack['schemaVersion'] == 2 and ack['kind'] == 'ORION_F5_INVOCATION_ACK' and
                  ack['status'] == status and ack['previousSnapshotSha256'] == previous and
                  type(ack['entries']) is int and ack['entries'] == entries and ack['hostClockSource'] == 'CLOCK_BOOTTIME', 'ACK_INVALID')
        P.digest(ack['snapshotSha256']); P.require(ack['snapshotSha256'] != previous, 'ACK_INVALID')

    def _append(self, kind, data):
        with self._journal_lock:
            old = self._digest
            ack = self.journal.append(kind, data, old)
            self._ack(ack, old, self._entries+1, 'APPENDED_DURABLE')
            self._digest, self._entries = ack['snapshotSha256'], ack['entries']

    def _check(self, *, effect=False, scheduled=None):
        P.require(os.getpid() == self._pid, 'OWNER_CHANGED')
        # A non-Exception from the real invocation must survive a later clock fault.
        with self._state:
            if self._fatal is not None: raise self._fatal
        now = self._now()
        with self._state:
            if self._fatal is not None: raise self._fatal
            if self._unknown or self._interrupted or self._halted: raise Stop(self._failure or 'INTERRUPTED')
            if effect and self._opened and self._last_complete is None: raise Stop('OBSERVATION_STALE')
            if self._start is not None:
                limits = self.plan['limits']
                baseline = self._last_complete if self._last_complete is not None else self._start
                if (effect and self._last_complete is None or
                    now-baseline > limits['maximumObservationGapMillis']*1000000): raise Stop('OBSERVATION_STALE')
                if effect and now-self._start >= limits['durationMillis']*1000000: raise Stop('WINDOW_ENDED')
                if scheduled is not None and now > self._start + (scheduled+limits['maximumStartLatenessMillis'])*1000000:
                    raise Stop('WINDOW_ENDED')
        return now

    def _halt(self, reason, *, unknown=False):
        with self._state:
            first = not self._halted
            self._halted = True; self._unknown |= unknown
            if first or self._failure is None: self._failure = reason
        if first:
            try: self._append('HALT',dict(reason=reason,unknown=self._unknown))
            except BaseException: self._unknown = True; self._failure = 'STORAGE_UNAVAILABLE'

    def report(self):
        return dict(schemaVersion=2,kind='ORION_F5_INSTRUMENTED_WORKLOAD_REPORT',qualification='NOT_QUALIFIED',
            environment='HOST',mode='INSTRUMENTED_FOREGROUND',status='UNKNOWN' if self._unknown else
            ('CLEANED' if self._cleaned else 'HALTED' if self._halted else 'IDLE'),reason=self._failure,
            entriesAcknowledged=self._entries,ownedCleanupComplete=self._cleaned,
            workInFlight=self._active,coverage='SCHEDULED_CHECKPOINTS_WITH_EXPLICIT_GAPS',
            targetWindowCoveredByCompleteCheckpoints=self._window_observed,
            **{k:False for k in P.FALSE_FLAGS})

    def _intent(self, operation, attempt, stamp):
        # Integrity reference only. It is never a resource decision, permit or capability.
        fence = dict(runId=self.plan['runId'],fixtureId=self.plan['ownedFixtureId'],attemptId=attempt,
                     operation=operation,stamp=stamp,planSha256=P.sha(P.encoded(self.plan)))
        self._append(operation+'_INTENT',dict(attemptId=attempt,fixtureId=self.plan['ownedFixtureId'],stamp=stamp,
                                             invocationFenceSha256=P.sha(P.encoded(fence))))

    def open(self):
        with self._state:
            P.require(not self._used and not self._opened); self._used = True
        self._check(effect=True)
        stamp = self.producer.revalidate()
        self._check(effect=True)
        self._intent('OPEN',None,stamp)
        try:
            self._check(effect=True)
            value, source = self.producer.call('OPEN')
            P.require(source is None)
            self._append('OPEN_RESULT',P.journal_checkpoint(value))
            if value['outcome'] != 'OPENED':
                self._halt('REFUSED',unknown=value['executionState'] != 'IDLE'); return False
            P.require(value['executionState'] == 'IDLE')
            self._opened = True
            # OPEN proves control continuity only. Actual complete OBSERVE starts the timed window.
            self._now()
            return True
        except BaseException:
            self._halt('TRANSPORT',unknown=True); raise

    def _work(self, attempt, scheduled):
        try:
            # ACK already returned; final HOST and actual-device fences precede exactly one Core request.
            self.producer.revalidate(); now = self._check(effect=True,scheduled=scheduled)
            self._last_work = now
            value, artifacts = self.producer.call('WORK',attempt)
            if self._interrupted or self._unknown: raise Stop('INTERRUPTED')
            if artifacts is not None:
                self._append('RUNTIME_ARTIFACTS',self.journal.retain_assembly(attempt,artifacts))
            self._append('WORK_RESULT',P.journal_checkpoint(value))
            if value['executionState'] != 'IDLE':
                self._halt('TRANSPORT',unknown=True); return
            if value['resourceExit'] is not None: self._resource_observed(value)
            elif value['outcome'] == 'PROCESS_DIED': self._halt('IDENTITY_CHANGED')
            if value['outcome'] in {'REFUSED','BUSY','UNAVAILABLE'}: self._halt('REFUSED')
        except BaseException as failure:
            # No replacement invocation and no synthetic Core TIMED_OUT/FAILED terminal.
            if not isinstance(failure,Exception): self._fatal = failure
            self._halt('INTERRUPTED' if self._interrupted else 'TRANSPORT',unknown=True)
        finally:
            with self._state: self._active = False
            self._remote.release()

    def bootstrap(self):
        """One explicitly selected original Core call outside the timed observation window."""
        with self._state:
            P.require(self.plan['bootstrapOperation'] == 'CORE_ONCE' and self._opened and
                      not self._bootstrap_selected and self._start is None and not self._selected_offsets,
                      'BOOTSTRAP_SELECTION_REUSED_OR_FOREIGN')
            self._bootstrap_selected = True
        self._check()
        P.require(self._remote.acquire(blocking=False), 'BOOTSTRAP_BUSY')
        self._active = True
        try:
            stamp = self.producer.revalidate(); self._check()
            attempt = str(uuid.uuid4()); self._intent('BOOTSTRAP',attempt,stamp)
            self.producer.revalidate(); self._last_work = self._check()
            value, artifacts = self.producer.call('BOOTSTRAP',attempt)
            if artifacts is not None:
                self._append('RUNTIME_ARTIFACTS',self.journal.retain_assembly(attempt,artifacts))
            self._append('BOOTSTRAP_RESULT',P.journal_checkpoint(value))
            if value['resourceExit'] is not None: self._resource_observed(value)
            settled = value['executionState'] == 'IDLE' and value['settlement'] == 'ORIGINAL_UNLOAD_CONFIRMED'
            if value['outcome'] != 'SUCCEEDED' or not settled:
                self._halt('REFUSED',unknown=value['executionState'] != 'IDLE' or value['settlement'] == 'UNPROVEN')
                return False
            # Cadence includes this actual invocation. Its time and samples never enter the measured window.
            self._last_work = self._now(); self._bootstrap_ready = True
            due = self._last_work + self.plan['limits']['minimumAttemptIntervalMillis']*1000000
            while self._check() < due:
                time.sleep(min(0.05,max(0,(due-self._now())/1000000000)))
            return True
        except BaseException as failure:
            self._halt('TRANSPORT',unknown=True)
            if not isinstance(failure,Exception): raise
            return False
        finally:
            self._active = False; self._remote.release()

    def select_work(self, scheduled):
        with self._state:
            P.require(self._opened, 'OWNER_UNAVAILABLE')
            P.require(self._bootstrap_ready, 'BOOTSTRAP_REQUIRED')
            P.require(scheduled in self.plan['schedule']['workOffsetsMillis'] and scheduled not in self._selected_offsets,
                      'SCHEDULE_SELECTION_REUSED_OR_FOREIGN')
            self._selected_offsets.add(scheduled)
        selected_at = self._check(effect=True,scheduled=scheduled)
        P.require(selected_at >= self._start + scheduled*1000000, 'SCHEDULE_NOT_DUE')
        attempt = str(uuid.uuid4()); stamp = self.producer.revalidate()
        now = self._check(effect=True,scheduled=scheduled)
        P.require(self._last_complete_device is not None)
        if stamp['deviceElapsedUpperMillis'] - self._last_complete_device > self.plan['limits']['maximumObservationGapMillis']:
            raise Stop('OBSERVATION_STALE')
        reason = None
        if self._resource_deaths >= self.plan['limits']['maximumResourceDeaths']: reason = 'RESOURCE_LIMIT'
        elif self._resource_fence is not None:
            host, device = self._resource_fence
            if min((now-host)//1000000,stamp['deviceElapsedMillis']-device) < self.plan['limits']['resourceExitCooldownMillis']:
                reason = 'RESOURCE_COOLDOWN'
        if reason is None and self._last_work is not None and now-self._last_work < self.plan['limits']['minimumAttemptIntervalMillis']*1000000:
            reason = 'CADENCE'
        if reason is None and not self._remote.acquire(blocking=False): reason = 'BUSY'
        if reason is not None:
            self._append('SELECTION_REFUSED',dict(attemptId=attempt,reason=reason,stamp=stamp)); return
        with self._state: self._active = True
        self._worker = None
        try:
            self._intent('WORK',attempt,stamp)
            worker = threading.Thread(target=self._work,args=(attempt,scheduled),daemon=True,name='orion_workload_once')
            self._worker = worker; worker.start()
        except BaseException:
            # Thread.start can throw after starting. Never release/reuse its slot from that exception.
            if self._worker is None:
                self._active = False; self._remote.release()
            self._halt('STORAGE_UNAVAILABLE',unknown=True); raise

    def _resource_observed(self, value):
        incident = value['resourceExit']; identity = P.incident_identity(incident)
        if incident['correlation'] != 'PAIRED_LIVE_WINDOW' or incident['windowBootCount'] != value['stamp']['bootCount']:
            self._halt('IDENTITY_CHANGED')
        elif identity not in self._incidents:
            P.require(len(self._incidents) < 256)
            self._incidents.add(identity)
            self._resource_deaths += 1
            self._resource_fence = (self._now(),value['stamp']['deviceElapsedMillis'])

    def observe(self):
        with self._state:
            if not self._opened or self._unknown or self._interrupted or self._halted:
                return self.report()
            P.require(self._bootstrap_ready, 'BOOTSTRAP_REQUIRED')
        observed_host_before = self._now()
        acquired = self._remote.acquire(blocking=False)
        if not acquired:
            # Independent OS queries only. Never concurrent instrumentation into the working MAIN.
            try: stamp = self.producer.stamp()
            except Exception: stamp = None
            self._append('OBSERVATION_GAP',dict(reason='WORK_INFLIGHT',stamp=stamp)); return
        try:
            value, source = self.producer.call('OBSERVE'); P.require(source is None)
            self._append('OBSERVATION',P.journal_checkpoint(value))
            if value['resourceExit'] is not None: self._resource_observed(value)
            if P.complete_observation(value):
                # Conservative HOST bracket, plus the actual source completion. No arrival retimestamp.
                self._last_complete = observed_host_before
                self._last_complete_device = value['observation']['completedElapsedMillis']
                if self._start is None: self._start = self._now()
            else: self._append('OBSERVATION_GAP',dict(reason='METRIC_UNAVAILABLE',stamp=value['stamp']))
        except Exception:
            self._append('OBSERVATION_GAP',dict(reason='TRANSPORT',stamp=None)); self._halt('TRANSPORT',unknown=True)
        finally: self._remote.release()

    def run(self):
        try:
            if not self.open(): return self.report()
            if self.plan['bootstrapOperation'] == 'CORE_ONCE' and not self.bootstrap(): return self.report()
            self.observe()  # The explicit offset-zero observation, not a fabricated OPEN sample.
            if self._start is None:
                self._halt('OBSERVATION_STALE'); return self.report()
            events = sorted([(v,0) for v in self.plan['schedule']['observationOffsetsMillis'][1:]] +
                            [(v,1) for v in self.plan['schedule']['workOffsetsMillis']])
            for offset, work in events:
                while self._check() < self._start + offset*1000000:
                    time.sleep(min(0.05,max(0,(self._start+offset*1000000-self._now())/1000000000)))
                self._check(effect=bool(work),scheduled=offset)
                if work: self.select_work(offset)
                else: self.observe()
            # No extra work/observation is generated to fill a missing or late schedule.
            while self._active:
                self._check(); time.sleep(0.05)
            covered = self.journal.inspect()['validation']['windowCoveredByCheckpoints']
            self._window_observed = covered
            self._halt('COMPLETED' if covered else 'SHORT_INCOMPLETE')
        except Stop as error: self._halt(error.reason,unknown=self._active)
        except KeyboardInterrupt:
            self._interrupted = True; self._halt('INTERRUPTED',unknown=self._active); raise
        except Exception: self._halt('TRANSPORT',unknown=self._active or not self._opened)
        return self.report()

    def cleanup(self):
        """Separate finite owned phase. Does not use the work deadline or grant another WORK."""
        P.require(os.getpid() == self._pid)
        with self._state:
            if self._cleanup_selected or not self._opened or self._unknown or self._active or self._interrupted:
                return self.report()
            self._cleanup_selected = True
        if not self._remote.acquire(blocking=False): return self.report()
        try:
            P.require(not self.journal.inspect()['validation']['pending'], 'UNRESOLVED_INVOCATION')
            stamp = self.producer.revalidate(); self._now()
            self._intent('CLEANUP',None,stamp)
            self.producer.revalidate(); self._now()
            value, source = self.producer.call('CLEANUP'); P.require(source is None and value['executionState'] == 'IDLE')
            self._append('CLEANUP_RESULT',P.journal_checkpoint(value))
            self._cleaned = value['outcome'] in {'CLEANED','ABSENT'}
            if self._cleaned: self._append('RUN_END',dict(reason=self._failure or 'COMPLETED',unknown=False))
            else: self._failure = 'CLEANUP_REFUSED'
        except BaseException as failure:
            with self._state: self._unknown = True; self._halted = True; self._failure = 'TRANSPORT'
            try: self._append('HALT',dict(reason='TRANSPORT',unknown=True))
            except BaseException: pass  # Preserve the original interruption even if its UNKNOWN record cannot be acknowledged.
            if not isinstance(failure,Exception): raise
        finally: self._remote.release()
        return self.report()

    def close(self):
        if self._active: return False
        self.journal.close(); return True


class ClosedArgumentParser(argparse.ArgumentParser):
    def error(self, _):
        self.exit(2,'CLI_ARGUMENTS_INVALID: fixed physical inputs and explicit schedule required.\n')


def main():
    parser = ClosedArgumentParser(description=__doc__)
    for name in ['adb','serial','source-root','source-manifest','app-apk','test-apk','evidence-dir']:
        parser.add_argument('--'+name,required=True)
    parser.add_argument('--plan',required=True,help='Closed reviewed v2 plan; bootstrapOperation must explicitly be NONE or CORE_ONCE, with no default.')
    parser.add_argument('--target',required=True,choices=['S21','API37'])
    parser.add_argument('--instrumented-foreground',required=True,action='store_true',help='Explicit --no-restart procedure; autonomy remains false.')
    args = parser.parse_args()
    executor = None; target_lock = None
    try:
        # Only a regular bounded input; no CLI profile/model/request parameters or arbitrary commands.
        selected = Path(args.plan); fd = os.open(selected,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(fd,'rb') as stream:
            import stat
            P.require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
            plan = P.parse(stream.read(P.MAX_BYTES+1))
        P.validate_plan(plan)
        target_lock = TargetLock(plan['target']['deviceIdentitySha256'])
        adb = FixedAdb(args.adb,args.serial,plan['limits']['transportTimeoutMillis'])
        device = BoundDevice(adb,plan,args.target,args.source_root,args.source_manifest,args.app_apk,args.test_apk)
        executor = InstrumentedWorkload(args.evidence_dir,plan,InstrumentedProducer(device))
        executor.run(); report = executor.cleanup()
    except KeyboardInterrupt:
        report = executor.report() if executor else dict(status='REFUSED',qualification='NOT_QUALIFIED')
    except Exception:
        report = executor.report() if executor else dict(status='REFUSED',qualification='NOT_QUALIFIED',**{k:False for k in P.FALSE_FLAGS})
    finally:
        if executor is not None: executor.close()
        if target_lock is not None: target_lock.close()
    print(json.dumps(report,sort_keys=True))
    return 0 if report.get('ownedCleanupComplete') else 2


if __name__ == '__main__': raise SystemExit(main())
