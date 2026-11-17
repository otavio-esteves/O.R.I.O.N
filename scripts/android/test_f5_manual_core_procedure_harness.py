"""Real fsync/new-only journals, scripted Core results and synthetic time. No qualification evidence."""
import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest import mock

import f5_manual_core_procedure as C
import f5_manual_core_procedure_protocol as M
import f5_manual_core_procedure_journal as J
import f5_instrumented_workload_protocol as P
from qualification_host_clock import HostClock
from test_f5_instrumented_workload_harness import Time
from test_f5_manual_core_procedure_protocol_harness import plan,checkpoint,death_confirmation,stamp


class Producer:
    def __init__(self,clock,path):
        self.clock=clock;self.path=path;self.calls=[];self.worker_ids=[];self.outcomes=['SUCCEEDED','PROCESS_DIED','REFUSED']
        self.work_count=0;self.dead=None;self.confirm=False;self.foreign=False;self.failure=None;self.pid=100;self.boot=7
        self.entered=self.release=None
    def stamp(self):return dict(stamp(self.clock.ns//1000000),mainPid=self.pid,bootCount=self.boot)
    def revalidate(self):
        P.require(self.pid==100 and self.boot==7,'OWNER_CHANGED');return self.stamp()
    def call(self,operation,attempt_id=None,*,invocation_fence):
        invocation_fence()
        entries=json.loads((self.path/'state.json').read_text())['journal']['entries']
        if operation in {'OPEN','WORK','CLEANUP'}:assert entries[-1]['kind']==operation+'_INTENT'
        self.calls.append(operation)
        if operation=='WORK':
            self.worker_ids.append(threading.get_ident())
            if self.entered:self.entered.set();self.release.wait(2)
            if self.failure:raise self.failure
            outcome=self.outcomes[min(self.work_count,len(self.outcomes)-1)];self.work_count+=1
            value=checkpoint(operation,outcome,attempt_id,self.clock.ns//1000000)
            if outcome=='PROCESS_DIED':
                self.dead=attempt_id;value.update(executionState='DEAD_AWAITING_EVIDENCE',settlement='UNPROVEN')
        elif operation=='OBSERVE' and self.dead:
            if self.confirm:
                value=death_confirmation(str(uuid.UUID(int=909)) if self.foreign else self.dead,self.clock.ns//1000000)
            else:value=checkpoint(operation,'OBSERVED',at=self.clock.ns//1000000);value.update(executionState='DEAD_AWAITING_EVIDENCE',settlement='UNPROVEN')
        else:value=checkpoint(operation,{'OPEN':'OPENED','OBSERVE':'OBSERVED','CLEANUP':'CLEANED'}[operation],at=self.clock.ns//1000000)
        M.checkpoint(value,operation,plan()['runId'],plan()['ownedFixtureId'],attempt_id)
        return value,None


class ManualControllerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.path=Path(self.temp.name)/'run';self.time=Time();self.time.ns=self.time.mono=1000000000
        patch=mock.patch.object(HostClock,'linux',side_effect=self.time.factory);patch.start();self.addCleanup(patch.stop)
        patch=mock.patch.object(C.time,'monotonic_ns',side_effect=lambda:self.time.mono);patch.start();self.addCleanup(patch.stop)
        self.producer=Producer(self.time,self.path);self.controller=C.ManualCoreProcedure(self.path,plan(),self.producer)
        self.addCleanup(self.controller.close)
    def entries(self):return self.controller.journal.inspect()['journal']['entries']
    def step(self,index):
        due=self.controller._start+self.controller.plan['steps'][index]['offsetMillis']*1000000
        if self.time.ns<due:self.time.advance((due-self.time.ns)//1000000)
        self.controller.execute_step(index)
        if self.controller._active:self.assertTrue(self.controller._idle.wait(2))
    def through_death(self):
        self.assertTrue(self.controller.open())
        for index in range(4):self.step(index)
    def test_cold_first_work_calls_original_core_once_after_ack_without_fabricating_pss(self):
        self.controller.open();self.step(0)
        self.assertEqual(['OPEN','WORK'],self.producer.calls)
        result=[e['data'] for e in self.entries() if e['kind']=='WORK_RESULT'][0]
        self.assertIsNone(result['resources']);self.assertEqual('SUCCEEDED',result['outcome'])
        self.assertFalse(self.controller.report()['autonomyProven']);self.assertFalse(self.controller.report()['continuousCoverageClaimed'])
    def test_confirmed_original_death_allows_new_planned_work_and_original_refusal_stops_procedure(self):
        self.through_death();self.assertEqual('DEAD_AWAITING_EVIDENCE',self.controller.report()['nativeState'])
        self.producer.confirm=True;self.step(4);self.assertEqual('IDLE',self.controller.report()['nativeState'])
        self.step(5);self.assertEqual(3,self.producer.work_count);self.assertEqual(1,len(set(self.producer.worker_ids)))
        results=[e['data'] for e in self.entries() if e['kind']=='WORK_RESULT']
        self.assertEqual(['SUCCEEDED','PROCESS_DIED','REFUSED'],[v['outcome'] for v in results])
        self.assertEqual('NO_QUALIFIED_PROFILE',results[-1]['refusal']);self.assertEqual('CORE_REFUSED',self.controller.report()['reason'])
        self.assertEqual(3,len({v['attemptId'] for v in results}));self.assertTrue(self.controller.cleanup()['ownedCleanupComplete'])
    def test_unpaired_death_never_generates_a_rebind_work_or_owned_cleanup(self):
        self.through_death();self.step(4);self.step(5)
        self.assertEqual(2,self.producer.work_count);self.assertEqual('DEATH_EVIDENCE_PENDING',self.controller.report()['reason'])
        self.controller.cleanup();self.assertNotIn('CLEANUP',self.producer.calls)
    def test_foreign_confirmation_is_unknown_and_cannot_be_used_for_new_work(self):
        self.through_death();self.producer.confirm=self.producer.foreign=True;self.step(4)
        self.assertEqual('UNKNOWN',self.controller.report()['status'])
        with self.assertRaises((C.Stop,P.Invalid)):self.step(5)
        self.assertEqual(2,self.producer.work_count)
    def test_transport_unknown_is_never_reversed_by_later_source(self):
        self.controller.open();self.producer.failure=TimeoutError('SECRET_TRANSPORT');self.step(0)
        before=list(self.producer.calls);self.producer.confirm=True
        with self.assertRaises((C.Stop,P.Invalid)):self.step(1)
        self.controller.cleanup();self.assertEqual(before,self.producer.calls)
        self.assertTrue(self.controller.journal.inspect()['validation']['pendingInvocation'])
        self.assertNotIn('SECRET',(self.path/'state.json').read_text())
    def test_lost_intent_ack_never_invokes_or_replays_and_retains_pending_evidence(self):
        self.controller.open();append=self.controller.journal.append
        def lost(kind,data,expected):
            value=append(kind,data,expected)
            if kind=='WORK_INTENT':raise OSError('SECRET_LOST_ACK')
            return value
        with mock.patch.object(self.controller.journal,'append',side_effect=lost):
            with self.assertRaises(OSError):self.step(0)
        self.assertEqual(['OPEN'],self.producer.calls);self.assertEqual('UNKNOWN',self.controller.report()['status'])
        self.controller.cleanup();self.assertEqual(['OPEN'],self.producer.calls)
    def test_blocked_work_has_only_os_gap_and_no_second_instrumentation_or_replacement(self):
        self.controller.open();self.producer.entered=threading.Event();self.producer.release=threading.Event()
        self.controller.execute_step(0);self.assertTrue(self.producer.entered.wait(1))
        self.time.advance(100);self.controller.execute_step(1)
        self.assertEqual(['OPEN','WORK'],self.producer.calls);self.assertEqual('OBSERVATION_GAP',self.entries()[-1]['kind'])
        self.assertFalse(self.controller.close());self.controller.cleanup();self.assertNotIn('CLEANUP',self.producer.calls)
        self.producer.release.set();self.assertTrue(self.controller._idle.wait(2))
    def test_suspend_reboot_identity_and_missed_schedule_block_new_requests(self):
        self.controller.open();self.time.advance(1,suspend=True)
        with self.assertRaises(P.Invalid):self.controller.execute_step(0)
        self.assertEqual(['OPEN'],self.producer.calls)
    def test_new_directory_only_and_inspection_never_reconstructs_executable_state(self):
        self.controller.close()
        with self.assertRaises(FileExistsError):C.ManualCoreProcedure(self.path,plan(),self.producer)
        value=J.inspect_existing(self.path);self.assertFalse(value['automaticResume'])
        self.assertEqual(M.JOURNAL_KIND,value['journal']['kind'])
    def test_cleanup_rethrows_same_original_interrupt_after_best_effort_unknown_record(self):
        self.controller.open();self.step(0);original=GeneratorExit('SECRET_CLEANUP_INTERRUPT');call=self.producer.call
        def interrupted(operation,attempt_id=None,**kwargs):
            if operation=='CLEANUP':raise original
            return call(operation,attempt_id,**kwargs)
        self.producer.call=interrupted
        with self.assertRaises(GeneratorExit) as caught:self.controller.cleanup()
        self.assertIs(original,caught.exception);self.assertEqual('UNKNOWN',self.controller.report()['status'])
    def test_known_refusal_ack_does_not_become_unknown_while_worker_publishes_halt(self):
        self.producer.outcomes=['REFUSED'];entered=threading.Event();release=threading.Event();caller_saw=threading.Event()
        append=self.controller.journal.append
        check=self.controller._check
        def observed_check(*args,**kwargs):
            try:return check(*args,**kwargs)
            except C.Stop as failure:
                if failure.reason=='CORE_REFUSED':caller_saw.set()
                raise
        def held(kind,data,expected):
            if kind=='HALT' and threading.current_thread().name=='orion_manual_core_once':
                entered.set();release.wait(2)
            return append(kind,data,expected)
        result=[]
        with mock.patch.object(self.controller.journal,'append',side_effect=held),mock.patch.object(self.controller,'_check',side_effect=observed_check):
            caller=threading.Thread(target=lambda:result.append(self.controller.run()))
            caller.start();self.assertTrue(entered.wait(1));self.assertTrue(caller_saw.wait(1));release.set();caller.join(2)
        self.assertFalse(caller.is_alive());self.assertEqual('CORE_REFUSED',result[0]['reason'])
        self.assertNotEqual('UNKNOWN',result[0]['status'])
    def test_refusal_ack_before_halt_mark_blocks_next_observe_without_becoming_unknown(self):
        self.producer.outcomes=['REFUSED'];entered=threading.Event();release=threading.Event()
        halt=self.controller._halt
        def before_mark(reason,**kwargs):
            if reason=='CORE_REFUSED' and threading.current_thread().name=='orion_manual_core_once':
                entered.set();release.wait(2)
            return halt(reason,**kwargs)
        self.controller.open()
        with mock.patch.object(self.controller,'_halt',side_effect=before_mark):
            self.controller.execute_step(0)
            try:
                self.assertTrue(entered.wait(1));self.assertTrue(self.controller._result_acknowledged)
                result=[event['data'] for event in self.entries() if event['kind']=='WORK_RESULT']
                self.assertEqual(['REFUSED'],[value['outcome'] for value in result])
                self.time.advance(100)
                try:self.controller.execute_step(1)
                except (C.Stop,P.Invalid):pass
                self.controller.cleanup()
                self.assertEqual(['OPEN','WORK'],self.producer.calls)
            finally:
                release.set();self.assertTrue(self.controller._idle.wait(2))
        self.assertNotEqual('UNKNOWN',self.controller.report()['status'])
        self.assertEqual('CORE_REFUSED',self.controller.report()['reason'])
        self.assertFalse(self.controller.journal.inspect()['validation']['unknown'])
    def test_refusal_result_with_lost_ack_remains_unknown_and_never_sets_known_terminal_latch(self):
        self.producer.outcomes=['REFUSED'];self.controller.open();append=self.controller.journal.append
        def lost(kind,data,expected):
            value=append(kind,data,expected)
            if kind=='WORK_RESULT':raise OSError('SECRET_LOST_REFUSAL_ACK')
            return value
        with mock.patch.object(self.controller.journal,'append',side_effect=lost):self.step(0)
        self.assertEqual('UNKNOWN',self.controller.report()['status'])
        self.assertIsNone(self.controller._terminal_result_reason)
        self.time.advance(100)
        with self.assertRaises((C.Stop,P.Invalid)):self.controller.execute_step(1)
        self.controller.cleanup();self.assertEqual(['OPEN','WORK'],self.producer.calls)
        result=[event['data'] for event in self.entries() if event['kind']=='WORK_RESULT']
        self.assertEqual(['REFUSED'],[value['outcome'] for value in result])
    def test_identity_change_is_terminal_even_if_fixture_later_restores_the_old_pid(self):
        self.controller.open();self.producer.pid=200
        with self.assertRaises(P.Invalid):self.controller.execute_step(0)
        self.producer.pid=100
        with self.assertRaises((C.Stop,P.Invalid)):self.controller.execute_step(1)
        self.assertEqual(['OPEN'],self.producer.calls);self.assertEqual('UNKNOWN',self.controller.report()['status'])
    def test_lost_ack_on_later_request_does_not_leave_an_idle_existing_worker_as_active(self):
        self.controller.open();self.step(0);self.step(1);self.step(2);append=self.controller.journal.append
        def lost(kind,data,expected):
            value=append(kind,data,expected)
            if kind=='WORK_INTENT':raise OSError('SECRET_LATER_ACK')
            return value
        with mock.patch.object(self.controller.journal,'append',side_effect=lost):
            with self.assertRaises(OSError):self.step(3)
        self.assertFalse(self.controller._active);self.assertFalse(self.controller._remote.locked())
        self.assertEqual(1,self.producer.work_count);self.assertEqual('UNKNOWN',self.controller.report()['status'])
    def test_actual_confirmation_is_deduplicated_without_renewing_host_cooldown(self):
        self.through_death();self.producer.confirm=True;self.step(4)
        value=death_confirmation(self.producer.dead,self.time.ns//1000000);old=self.controller._resource_fence
        self.time.advance(100);self.controller._confirm_death(value,self.time.ns)
        self.assertEqual(old,self.controller._resource_fence);self.assertEqual(1,self.controller.report()['confirmedAiDeaths'])
    def test_reused_incident_cannot_settle_a_different_original_attempt(self):
        self.through_death();self.producer.confirm=True;self.step(4)
        self.controller._dead=str(uuid.UUID(int=701))
        value=death_confirmation(self.controller._dead,self.time.ns//1000000)
        with self.assertRaises(P.Invalid):self.controller._confirm_death(value,self.time.ns)
    def test_deadline_and_lateness_never_catch_up_missed_steps(self):
        self.controller.open();self.time.advance(100)
        with self.assertRaises(C.Stop):self.controller.execute_step(0)
        self.assertEqual(['OPEN'],self.producer.calls);self.assertEqual('SCHEDULE_MISSED',self.controller.report()['reason'])
    def test_partial_worker_start_failure_never_releases_slot_or_replaces_worker(self):
        self.controller.open();original=GeneratorExit('SECRET_WORKER_START')
        with mock.patch.object(C.threading.Thread,'start',side_effect=original):
            with self.assertRaises(GeneratorExit) as caught:self.controller.execute_step(0)
        self.assertIs(original,caught.exception);self.assertTrue(self.controller._unknown)
        self.assertFalse(self.controller.close());self.controller.cleanup();self.assertEqual(['OPEN'],self.producer.calls)
        # This fixture proves its mock never started a worker. Close only these test-owned local descriptors.
        self.controller._worker=None;self.controller._job=None;self.controller._active=False;self.controller._remote.release()
    def test_constructor_malformed_ack_or_interrupt_closes_lock_without_adoption_or_deletion(self):
        for failure in [None,GeneratorExit('SECRET_INITIAL_ACK')]:
            target=Path(self.temp.name)/('constructor-'+str(failure is None));original=J.ManualInvocationJournal._ack
            def bad(store,*args,**kwargs):
                if failure is not None:raise failure
                value=original(store,*args,**kwargs);value['entries']=True;return value
            with mock.patch.object(J.ManualInvocationJournal,'_ack',bad):
                with self.assertRaises(P.Invalid if failure is None else GeneratorExit):C.ManualCoreProcedure(target,plan(),self.producer)
            self.assertTrue((target/'state.json').exists());self.assertFalse(J.inspect_existing(target)['automaticResume'])
    def test_store_cas_conflict_and_publisher_substitution_never_ack_foreign_bytes(self):
        before=(self.path/'state.json').read_bytes()
        with self.assertRaises(P.Invalid):self.controller.journal.append('HALT',dict(reason='INTERRUPTED',unknown=False),'0'*64)
        self.assertEqual(before,(self.path/'state.json').read_bytes())
        original=J.J.os.replace
        def replaced(src,dst,**kwargs):
            import os
            os.unlink(src,dir_fd=self.controller.journal.fd)
            fd=os.open(src,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o400,dir_fd=self.controller.journal.fd)
            try:os.write(fd,b'{"foreign":"substitution"}')
            finally:os.close(fd)
            return original(src,dst,**kwargs)
        with mock.patch.object(J.J.os,'replace',side_effect=replaced):
            with self.assertRaises(J.J.base.CommitUncertain):self.controller.journal._publish('state.json',b'{"owned":"bytes"}')
        self.assertTrue(self.controller.journal.inspection_required)

if __name__=='__main__':unittest.main()
