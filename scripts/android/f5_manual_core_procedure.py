#!/usr/bin/env python3
"""Finite explicit Core requests for an instrumented manual procedure. Never a soak or admission source."""
import argparse
import copy
import json
import os
from pathlib import Path
import threading
import time
import uuid

import f5_instrumented_workload as W
import f5_instrumented_workload_adapters as A
import f5_instrumented_workload_protocol as P
import f5_manual_core_procedure_protocol as M
import f5_runtime_qualification_sidecar as S
from f5_manual_core_procedure_adapters import ManualProducer
from f5_manual_core_procedure_journal import ManualInvocationJournal
from qualification_host_clock import HostClock


class Stop(RuntimeError):
    def __init__(self,reason):self.reason=reason;super().__init__(reason)


class ManualCoreProcedure:
    # These helpers implement only real BOOTTIME/suspend fences and durable ACK/CAS, not soak admission.
    _now=W.InstrumentedWorkload._now
    _ack=W.InstrumentedWorkload._ack
    _append=W.InstrumentedWorkload._append

    def __init__(self,directory,plan,producer):
        M.validate_plan(plan);self.plan=copy.deepcopy(plan);self.producer=producer;self._pid=os.getpid()
        self.clock=HostClock.linux();self._clock_lock=threading.Lock();self._meta=dict(self.clock.metadata)
        self._host=self._before=self._after=None;self._now()
        self.journal=ManualInvocationJournal(directory,self.plan)
        try:self._ack(self.journal.initial_ack,None,0,'INITIALIZED_DURABLE')
        except BaseException:
            try:self.journal.close()
            except BaseException:pass
            raise
        self._digest=self.journal.digest;self._entries=0;self._journal_lock=threading.Lock()
        self._state=threading.RLock();self._remote=threading.Lock();self._condition=threading.Condition()
        self._idle=threading.Event();self._idle.set();self._worker=None;self._job=None;self._stop_worker=False
        self._opened=self._used=self._active=self._unknown=self._halted=self._cleanup_selected=self._cleaned=False
        self._start=self._last_work=None;self._next_step=0;self._fatal=self._failure=None
        self._closed=False
        self._native='IDLE';self._safe=False;self._dead=None;self._incidents={};self._resource_fence=None
        self._result_acknowledged=False
        self._terminal_result_reason=None

    def _halt(self,reason,*,unknown=False):
        with self._state:
            first=not self._halted;self._halted=True;self._unknown|=unknown
            if first or unknown:self._failure=reason
        if first or unknown:
            try:self._append('HALT',dict(reason=reason,unknown=self._unknown))
            except BaseException:self._unknown=True;self._failure='STORAGE_UNAVAILABLE'

    def _check(self,step=None):
        P.require(os.getpid()==self._pid,'OWNER_CHANGED')
        with self._state:
            P.require(not self._closed and not self._cleaned,'PROCEDURE_CLOSED')
            if self._fatal is not None:raise self._fatal
            if self._unknown or self._halted:raise Stop(self._failure or 'INTERRUPTED')
            if self._terminal_result_reason is not None:raise Stop(self._terminal_result_reason)
        try:now=self._now()
        except BaseException:
            self._halt('IDENTITY_CHANGED',unknown=True);raise
        if self._start is not None:
            limits=self.plan['limits']
            if now-self._start>=limits['procedureDurationMillis']*1000000:raise Stop('PROCEDURE_ENDED')
            if step is not None:
                offset=self.plan['steps'][step]['offsetMillis']
                P.require(now>=self._start+offset*1000000,'STEP_NOT_DUE')
                if now>self._start+(offset+limits['maximumStartLatenessMillis'])*1000000:raise Stop('SCHEDULE_MISSED')
        return now

    def _intent(self,operation,attempt,stamp,step=None):
        self._append(operation+'_INTENT',dict(attemptId=attempt,fixtureId=self.plan['ownedFixtureId'],stamp=stamp,
            stepIndex=step,invocationFenceSha256=M.fence(self.plan,operation,attempt,stamp,step)))

    def report(self):
        with self._state:
            return dict(schemaVersion=2,kind='ORION_F5_MANUAL_CORE_PROCEDURE_REPORT',qualification='NOT_QUALIFIED',environment='HOST',
                mode='INSTRUMENTED_FOREGROUND_MANUAL',status='UNKNOWN' if self._unknown else 'CLEANED' if self._cleaned else
                'HALTED' if self._halted else 'PENDING_EVIDENCE' if self._native=='DEAD_AWAITING_EVIDENCE' else 'IDLE',
                reason=self._failure,nativeState=self._native,workInFlight=self._active,stepsConsumed=self._next_step,
                entriesAcknowledged=self._entries,confirmedAiDeaths=len(self._incidents),ownedCleanupComplete=self._cleaned,
                automaticResume=False,continuousCoverageClaimed=False,**{key:False for key in P.FALSE_FLAGS})

    def open(self):
        with self._state:P.require(not self._used);self._used=True
        try:
            self._check();stamp=self.producer.revalidate();self._check();self._intent('OPEN',None,stamp)
            self._check();value,artifacts=self.producer.call('OPEN',invocation_fence=self._check);P.require(artifacts is None)
            self._append('OPEN_RESULT',P.journal_checkpoint(value))
            if value['outcome']!='OPENED':self._halt('CORE_REFUSED',unknown=value['executionState']!='IDLE');return False
            P.require(value['executionState']=='IDLE' and value['settlement']=='NO_OWNER_CALL')
            self._opened=True;self._safe=True;self._start=self._now()
            self._append('PROCEDURE_START',dict(startedHostNanoseconds=self._start,stamp=self.producer.stamp()))
            return True
        except BaseException:
            self._halt('TRANSPORT',unknown=True);raise

    def _confirm_death(self,value,observed_host):
        P.require(self._dead is not None and M.death_matches(value,self._dead),'FOREIGN_DEATH_SETTLEMENT')
        proof=S.death(value);identity=proof['incidentIdentitySha256'];old=self._incidents.get(identity)
        P.require(old is None or old==self._dead,'INCIDENT_REUSED_FOR_OTHER_ATTEMPT')
        if old is None:
            P.require(len(self._incidents)<M.MAX_STEPS,'INCIDENT_BOUND_EXCEEDED')
            self._incidents[identity]=self._dead
            self._resource_fence=(observed_host,value['stamp']['deviceElapsedMillis'])
        self._native='IDLE';self._safe=True

    def _work(self,attempt,step):
        try:
            self.producer.revalidate();self._last_work=self._check(step)
            value,artifacts=self.producer.call('WORK',attempt,invocation_fence=lambda:self._check(step))
            if self._unknown:raise Stop('INTERRUPTED')
            if artifacts is not None:self._append('RUNTIME_ARTIFACTS',self.journal.retain_assembly(attempt,artifacts))
            # Serialize the durable result ACK and its terminal fact with step selection.
            # No caller can append a step in the interval before the worker publishes HALT.
            with self._state:
                self._append('WORK_RESULT',P.journal_checkpoint(value))
                self._result_acknowledged=True
                if value['outcome'] not in {'SUCCEEDED','PROCESS_DIED'}:
                    self._terminal_result_reason='CORE_REFUSED' if value['outcome']=='REFUSED' else 'CORE_FAILED'
            self._safe=False
            if value['outcome']=='PROCESS_DIED':
                self._dead=attempt
                if value['executionState']=='DEAD_AWAITING_EVIDENCE':self._native='DEAD_AWAITING_EVIDENCE'
                elif M.death_matches(value,attempt):self._confirm_death(value,self._now())
                else:self._native='UNCERTAIN';self._halt('TRANSPORT',unknown=True)
            elif value['executionState']=='IDLE' and value['settlement'] in {'NO_OWNER_CALL','ORIGINAL_UNLOAD_CONFIRMED'}:
                self._native='IDLE';self._safe=True;self._dead=None
            else:self._native='UNCERTAIN';self._halt('TRANSPORT',unknown=True)
            if value['outcome'] not in {'SUCCEEDED','PROCESS_DIED'}:
                self._halt('CORE_REFUSED' if value['outcome']=='REFUSED' else 'CORE_FAILED',unknown=not self._safe)
        except BaseException as failure:
            if not isinstance(failure,Exception):self._fatal=failure
            self._native='UNCERTAIN';self._halt('TRANSPORT',unknown=True)
        finally:
            self._remote.release()
            with self._state:self._active=False;self._idle.set()

    def _worker_loop(self):
        while True:
            with self._condition:
                while self._job is None and not self._stop_worker:self._condition.wait()
                if self._stop_worker:return
                job=self._job;self._job=None
            self._work(*job)

    def _dispatch(self,attempt,index):
        with self._condition:
            P.require(self._job is None and not self._stop_worker)
            self._job=(attempt,index)
            if self._worker is None:
                worker=threading.Thread(target=self._worker_loop,name='orion_manual_core_once',daemon=True)
                self._worker=worker;worker.start()
            else:
                P.require(self._worker.is_alive(),'WORKER_UNAVAILABLE');self._condition.notify()

    def execute_step(self,index):
        try:return self._execute_step(index)
        except Stop as failure:
            if self._terminal_result_reason is not None and not self._unknown:
                # The worker owns the required terminal publication and is not quiescent yet.
                raise
            if not self._unknown and not self._halted:self._halt(failure.reason,unknown=self._active)
            raise
        except BaseException:
            if not self._unknown:self._halt('IDENTITY_CHANGED',unknown=True)
            raise

    def _execute_step(self,index):
        with self._state:return self._execute_step_selected(index)

    def _execute_step_selected(self,index):
        with self._state:
            P.require(self._opened and index<len(self.plan['steps']),'STEP_BOUND_INVALID')
            P.integer(index,self._next_step,self._next_step)
        now=self._check(index);step=self.plan['steps'][index]
        self._next_step+=1
        if step['operation']=='OBSERVE':return self._observe(index)
        stamp=self.producer.revalidate();now=self._check(index);reason=None
        if self._native!='IDLE':reason='DEATH_EVIDENCE_PENDING'
        elif len(self._incidents)>=self.plan['limits']['maximumResourceDeaths']:reason='RESOURCE_LIMIT'
        elif self._resource_fence is not None:
            host,device=self._resource_fence
            if min((now-host)//1000000,stamp['deviceElapsedMillis']-device)<self.plan['limits']['resourceExitCooldownMillis']:reason='RESOURCE_COOLDOWN'
        if reason is None and self._last_work is not None and now-self._last_work<self.plan['limits']['minimumAttemptIntervalMillis']*1000000:reason='CADENCE'
        if reason is None and not self._remote.acquire(blocking=False):reason='BUSY'
        if reason is not None:
            self._append('STEP_REFUSED',dict(stepIndex=index,reason=reason,stamp=stamp));self._failure=reason
            if reason=='RESOURCE_LIMIT':self._halt(reason)
            return self.report()
        self._active=True;self._result_acknowledged=False;self._idle.clear()
        dispatched=False
        try:
            attempt=str(uuid.uuid4());self._intent('WORK',attempt,stamp,index)
            dispatched=True
            self._dispatch(attempt,index)
        except BaseException:
            # If dispatch assigned a job, Thread.start may have started before throwing. Never replace it.
            if not dispatched:
                self._remote.release();self._active=False;self._idle.set()
            self._halt('STORAGE_UNAVAILABLE',unknown=True);raise
        return self.report()

    def _observe(self,index):
        acquired=self._remote.acquire(blocking=False)
        if not acquired:
            try:stamp=self.producer.stamp()
            except Exception:stamp=None
            self._append('OBSERVATION_GAP',dict(stepIndex=index,reason='WORK_INFLIGHT',stamp=stamp));return self.report()
        try:
            before=self._now();value,artifacts=self.producer.call('OBSERVE',invocation_fence=lambda:self._check(index));P.require(artifacts is None)
            self._append('OBSERVATION',dict(stepIndex=index,checkpoint=P.journal_checkpoint(value)))
            if S.death(value) is not None:self._confirm_death(value,before)
            elif self._native=='DEAD_AWAITING_EVIDENCE':P.require(value['executionState']=='DEAD_AWAITING_EVIDENCE')
            elif value['executionState']!='IDLE':self._native='UNCERTAIN';self._halt('TRANSPORT',unknown=True)
        except BaseException as failure:
            self._halt('TRANSPORT',unknown=True)
            if not isinstance(failure,Exception):raise
        finally:self._remote.release()
        return self.report()

    def run(self):
        try:
            if not self.open():return self.report()
            for index,step in enumerate(self.plan['steps']):
                due=self._start+step['offsetMillis']*1000000
                while self._check()<due:time.sleep(min(0.05,max(0,(due-self._now())/1000000000)))
                self.execute_step(index)
            while self._active:self._check();time.sleep(0.05)
            self._halt('DEATH_EVIDENCE_PENDING' if self._native=='DEAD_AWAITING_EVIDENCE' else 'PROCEDURE_COMPLETED')
        except Stop as failure:
            # A Core result already acknowledged is not made ambiguous by remaining HOST HALT publication.
            if self._active and self._result_acknowledged:
                try:self._await_acknowledged_worker()
                except BaseException:
                    self._halt('IDENTITY_CHANGED',unknown=True);raise
            self._halt(failure.reason,unknown=self._active)
        except BaseException as failure:
            self._halt('INTERRUPTED' if not isinstance(failure,Exception) else 'TRANSPORT',unknown=self._active)
            if not isinstance(failure,Exception):raise
        return self.report()

    def _await_acknowledged_worker(self):
        deadline=self._now()+self.plan['limits']['transportTimeoutMillis']*1000000
        while self._active:
            remaining=deadline-self._now()
            if remaining<=0:break
            self._idle.wait(min(0.05,remaining/1000000000))

    def cleanup(self):
        P.require(os.getpid()==self._pid)
        with self._state:
            if self._cleanup_selected or not self._opened or self._unknown or self._active or not self._safe or self._native!='IDLE':return self.report()
            self._cleanup_selected=True
        if not self._remote.acquire(blocking=False):return self.report()
        original=None
        try:
            P.require(not self.journal.inspect()['validation']['pendingInvocation'])
            stamp=self.producer.revalidate();self._now();self._intent('CLEANUP',None,stamp)
            self.producer.revalidate();self._now();value,artifacts=self.producer.call('CLEANUP',invocation_fence=self._now)
            P.require(artifacts is None and value['executionState']=='IDLE')
            self._append('CLEANUP_RESULT',P.journal_checkpoint(value));self._cleaned=value['outcome'] in {'CLEANED','ABSENT'}
            if self._cleaned:self._append('RUN_END',dict(reason=self._failure or 'PROCEDURE_COMPLETED',unknown=False))
            else:self._failure='CLEANUP_REFUSED'
        except BaseException as failure:original=failure;self._halt('TRANSPORT',unknown=True)
        finally:
            try:self._remote.release()
            except BaseException as failure:
                if original is None:original=failure;self._halt('TRANSPORT',unknown=True)
        if original is not None and not isinstance(original,Exception):raise original
        return self.report()

    def close(self):
        if self._active:return False
        self._closed=True
        with self._condition:self._stop_worker=True;self._condition.notify()
        if self._worker is not None:self._worker.join(timeout=1)
        self.journal.close();return True


def main():
    parser=W.ClosedArgumentParser(description=__doc__)
    for name in ['adb','serial','source-root','source-manifest','app-apk','test-apk','evidence-dir']:
        parser.add_argument('--'+name,required=True)
    parser.add_argument('--plan',required=True,help='Closed manual v2 plan with explicit finite OBSERVE/WORK steps and limits; no defaults.')
    parser.add_argument('--target',required=True,choices=['S21','API37'])
    parser.add_argument('--manual-core-procedure',required=True,action='store_true')
    parser.add_argument('--instrumented-foreground',required=True,action='store_true',help='Explicit --no-restart; no autonomous/soak claim.')
    args=parser.parse_args();controller=target_lock=None;original=cleanup_error=None
    try:
        fd=os.open(args.plan,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        with os.fdopen(fd,'rb') as source:
            import stat
            P.require(stat.S_ISREG(os.fstat(source.fileno()).st_mode));plan=P.parse(source.read(P.MAX_BYTES+1))
        M.validate_plan(plan);target_lock=A.TargetLock(plan['target']['deviceIdentitySha256'])
        adb=A.FixedAdb(args.adb,args.serial,plan['limits']['transportTimeoutMillis'])
        device=A.BoundDevice(adb,plan,args.target,args.source_root,args.source_manifest,args.app_apk,args.test_apk)
        controller=ManualCoreProcedure(args.evidence_dir,plan,ManualProducer(device));controller.run();report=controller.cleanup()
    except BaseException as failure:
        original=failure
        report=controller.report() if controller else dict(status='REFUSED',qualification='NOT_QUALIFIED',**{k:False for k in P.FALSE_FLAGS})
    finally:
        for owned in [controller,target_lock]:
            if owned is None:continue
            try:owned.close()
            except BaseException as failure:
                if cleanup_error is None:cleanup_error=failure
    if original is not None and not isinstance(original,Exception):raise original
    if original is None and cleanup_error is not None:
        if not isinstance(cleanup_error,Exception):raise cleanup_error
        if controller is not None:
            controller._unknown=True;controller._failure='STORAGE_UNAVAILABLE';report=controller.report()
        else:report['status']='UNKNOWN'
    print(json.dumps(report,sort_keys=True));return 0 if report.get('ownedCleanupComplete') else 2


if __name__=='__main__':raise SystemExit(main())
