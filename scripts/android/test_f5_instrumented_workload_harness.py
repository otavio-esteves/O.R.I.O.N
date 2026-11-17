"""Real temporary fsync journals, synthetic Core producers/clocks; no physical admission evidence."""
import copy
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
import uuid
from unittest import mock

import f5_instrumented_workload as W
import f5_instrumented_workload_protocol as P
import f5_instrumented_workload_journal as J
from qualification_host_clock import HostClock
from test_f5_instrumented_workload_protocol_harness import plan, stamp, checkpoint, SESSION


class Time:
    ns = 1000000000
    mono = 1000000000
    def advance(self,millis,suspend=False):
        self.ns += millis*1000000
        if not suspend: self.mono += millis*1000000
    def factory(self):
        value = HostClock(lambda:self.ns, lambda:str(uuid.UUID(int=900)))
        value.metadata.update(clockSource='CLOCK_BOOTTIME',evidence='HOST_CLOCK_ONLY')
        value.read_ns(); return value


class Producer:
    def __init__(self,time,directory):
        self.time=time;self.directory=directory;self.session=None;self.calls=[]
        self.work_outcome='SUCCEEDED';self.failure=None;self.boot=7;self.pid=100
        self.entered=self.release=None;self.partial=False;self.foreign=False;self.resource_exit=None
    def stamp(self): return dict(bootCount=self.boot,deviceElapsedMillis=self.time.ns//1000000,deviceElapsedUpperMillis=self.time.ns//1000000,mainPid=self.pid)
    def revalidate(self):
        if self.boot!=7 or self.pid!=100: raise P.Invalid('OWNER_CHANGED')
        return self.stamp()
    def call(self,operation,attempt_id=None):
        state=json.loads((self.directory/'state.json').read_text())['journal']['entries']
        if operation in {'OPEN','WORK','CLEANUP'}:
            assert state[-1]['kind']==operation+'_INTENT'
        self.calls.append(operation)
        if operation=='WORK':
            if self.entered:self.entered.set();self.release.wait(2)
            if self.failure:raise self.failure
        outcome={'OPEN':'OPENED','WORK':self.work_outcome,'OBSERVE':'OBSERVED','CLEANUP':'CLEANED'}[operation]
        value=checkpoint(operation,outcome,attempt_id);value['stamp']=self.stamp()
        if self.foreign:value['sessionId']=str(uuid.UUID(int=50))
        if operation=='OPEN':self.session=value['sessionId']
        if operation=='WORK':
            value['resourceExit']=self.resource_exit
            if outcome=='REFUSED':value.update(ownerCallInvoked=False,settlement='NO_OWNER_CALL')
        if operation=='OBSERVE':
            value['resources']=dict(mainPid=100,aiPid=101,mainPssBytes=100,aiPssBytes=200,aggregatePssBytes=300,
                thermalStatus=0,memoryPressure='NONE',serviceState='UNLOADED',nativeRuntimeLoaded=True,
                actionPendingCount=None if self.partial else 0,actionUnknownCount=None if self.partial else 0)
        if value['resources'] is not None:
            at=self.time.ns//1000000
            value['observation']=dict(startedElapsedMillis=at,completedElapsedMillis=at,mainSampledElapsedMillis=at,
                aiSampledElapsedMillis=at,maximumSampleAgeMillis=10,bootCount=self.boot,aiBootCount=self.boot,
                mainNativeIncluded=True,aiNativeIncluded=True,nativeValidationCompleted=True)
        return value,None


class ColdProducer(Producer):
    """Synthetic cold graph: only the real bootstrap-operation boundary creates its endpoint fixture."""
    def __init__(self,*args):super().__init__(*args);self.warm=False;self.bootstrap_settlement='ORIGINAL_UNLOAD_CONFIRMED'
    def call(self,operation,attempt_id=None):
        if operation=='BOOTSTRAP':
            state=json.loads((self.directory/'state.json').read_text())['journal']['entries']
            assert state[-1]['kind']=='BOOTSTRAP_INTENT'
            self.calls.append(operation)
            if self.failure:raise self.failure
            self.warm=True
            value=checkpoint(operation,'SUCCEEDED',attempt_id);value['stamp']=self.stamp()
            value.update(ownerCallInvoked=True,settlement=self.bootstrap_settlement,
                executionState='IDLE' if self.bootstrap_settlement=='ORIGINAL_UNLOAD_CONFIRMED' else 'UNCERTAIN',resourceExit=self.resource_exit)
            return value,None
        value,source=super().call(operation,attempt_id)
        if operation=='OBSERVE' and not self.warm:
            for key in ['aiPid','aiPssBytes','aggregatePssBytes','nativeRuntimeLoaded']:value['resources'][key]=None
            value['resources']['serviceState']='UNAVAILABLE'
            for key in ['aiSampledElapsedMillis','aiBootCount','aiNativeIncluded','nativeValidationCompleted']:value['observation'][key]=None
        return value,source


class WorkloadTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.directory=Path(self.temp.name)/'run';self.time=Time();self.time.ns=self.time.mono=1000000000
        self.patch=mock.patch.object(HostClock,'linux',side_effect=self.time.factory);self.patch.start();self.addCleanup(self.patch.stop)
        self.mono=mock.patch.object(W.time,'monotonic_ns',side_effect=lambda:self.time.mono);self.mono.start();self.addCleanup(self.mono.stop)
        self.producer=Producer(self.time,self.directory)
        self.executor=W.InstrumentedWorkload(self.directory,plan(),self.producer);self.addCleanup(self.executor.close)
    def start(self):
        self.executor.open();self.executor.observe()
    def entries(self):return self.executor.journal.inspect()['journal']['entries']
    def finish_work(self):
        self.executor.select_work(0);self.executor._worker.join(2);self.assertFalse(self.executor._worker.is_alive())
    def test_real_ack_precedes_only_one_core_invocation_and_nonce_is_not_persisted(self):
        self.start();self.finish_work()
        self.assertEqual(['OPEN','OBSERVE','WORK'],self.producer.calls)
        self.assertEqual(['OPEN_INTENT','OPEN_RESULT','OBSERVATION','WORK_INTENT','WORK_RESULT'],[e['kind'] for e in self.entries()])
        text=(self.directory/'state.json').read_text();self.assertNotIn(SESSION,text)
        self.assertIn(P.sha(SESSION.encode()),text);self.assertNotIn('admissionDecisionSha256',text)
    def test_actual_core_no_profile_refusal_is_recorded_after_host_selection(self):
        self.start();self.producer.work_outcome='REFUSED';self.finish_work()
        result=self.entries()[-2]['data'];self.assertEqual('REFUSED',result['outcome']);self.assertEqual('NO_QUALIFIED_PROFILE',result['refusal'])
        self.assertFalse(self.executor.report()['autonomyProven']);self.assertFalse(self.executor._unknown)
        self.assertTrue(self.executor.cleanup()['ownedCleanupComplete'])
    def test_storage_failure_before_intent_ack_never_calls_work(self):
        self.start()
        with mock.patch.object(self.executor.journal,'append',side_effect=OSError('SECRET_STORAGE_ERROR')):
            with self.assertRaises(OSError):self.executor.select_work(0)
        self.assertEqual(['OPEN','OBSERVE'],self.producer.calls);self.assertTrue(self.executor._unknown)
        self.executor.cleanup();self.assertEqual(['OPEN','OBSERVE'],self.producer.calls)
    def test_lost_durable_intent_ack_keeps_intent_without_effect_or_replay(self):
        self.start();original=self.executor.journal.append
        def lost(kind,data,expected):
            value=original(kind,data,expected)
            if kind=='WORK_INTENT':raise OSError('lost ack')
            return value
        with mock.patch.object(self.executor.journal,'append',side_effect=lost):
            with self.assertRaises(OSError):self.executor.select_work(0)
        self.assertEqual(['OPEN','OBSERVE'],self.producer.calls)
        self.assertTrue(self.executor.journal.inspect()['validation']['pending'])
        with self.assertRaises(P.Invalid):self.executor.select_work(0)
    def test_result_publication_uncertainty_never_retries_or_cleans(self):
        self.start();original=self.executor.journal.append
        def lost(kind,data,expected):
            value=original(kind,data,expected)
            if kind=='WORK_RESULT':raise OSError('lost result ack')
            return value
        with mock.patch.object(self.executor.journal,'append',side_effect=lost):self.finish_work()
        self.assertEqual(1,self.producer.calls.count('WORK'));self.assertTrue(self.executor._unknown)
        self.executor.cleanup();self.assertNotIn('CLEANUP',self.producer.calls)
    def test_transport_timeout_is_unknown_and_not_core_timeout(self):
        self.start();self.producer.failure=TimeoutError('SECRET');self.finish_work()
        self.assertTrue(self.executor._unknown);self.assertFalse(any(e['kind']=='WORK_RESULT' for e in self.entries()))
        self.assertTrue(self.executor.journal.inspect()['validation']['pending'])
        self.assertNotIn('SECRET',(self.directory/'state.json').read_text())
    def test_observer_records_gap_while_work_blocked_without_second_instrumentation(self):
        self.start();self.producer.entered=threading.Event();self.producer.release=threading.Event()
        self.executor.select_work(0);self.assertTrue(self.producer.entered.wait(1))
        self.executor.observe();self.assertEqual(1,self.producer.calls.count('OBSERVE'))
        self.assertEqual('WORK_INFLIGHT',self.entries()[-1]['data']['reason'])
        self.assertFalse(self.executor.close());self.executor.cleanup();self.assertNotIn('CLEANUP',self.producer.calls)
        self.producer.release.set();self.executor._worker.join(2)
    def test_unavailable_queue_counts_remain_null_and_gap_not_zero(self):
        self.start();self.producer.partial=True;self.executor.observe()
        observations=[e for e in self.entries() if e['kind']=='OBSERVATION']
        self.assertIsNone(observations[-1]['data']['resources']['actionPendingCount'])
        self.assertEqual('METRIC_UNAVAILABLE',self.entries()[-1]['data']['reason'])
    def test_deadline_equality_and_missed_schedule_refuse_no_catch_up(self):
        self.start();self.time.advance(1000);self.executor.observe()
        with self.assertRaises(W.Stop) as failure:self.executor.select_work(0)
        self.assertEqual('WINDOW_ENDED',failure.exception.reason)
        self.assertEqual(['OPEN','OBSERVE','OBSERVE'],self.producer.calls)
    def test_stale_observation_prevents_further_work(self):
        self.start();self.time.advance(501)
        with self.assertRaises(W.Stop):self.executor.select_work(0)
        self.assertEqual(['OPEN','OBSERVE'],self.producer.calls)
    def test_suspend_detected_without_adding_mono_domains(self):
        self.start();self.time.advance(1,suspend=True)
        with self.assertRaises(P.Invalid):self.executor._check()
    def test_reboot_or_changed_main_pid_prevents_invocation(self):
        self.start();self.producer.pid=200
        with self.assertRaises(P.Invalid):self.executor.select_work(0)
        self.assertEqual(['OPEN','OBSERVE'],self.producer.calls)
    def test_cleanup_after_expired_window_only_removes_owned_buffers_once(self):
        self.start();self.finish_work();self.time.advance(1500)
        self.executor._halt('WINDOW_ENDED')
        self.assertTrue(self.executor.cleanup()['ownedCleanupComplete'])
        self.executor.cleanup();self.assertEqual(1,self.producer.calls.count('CLEANUP'))
    def test_cleanup_foreign_or_ambiguous_result_is_retained(self):
        self.start();self.finish_work();self.producer.foreign=True
        self.assertFalse(self.executor.cleanup()['ownedCleanupComplete']);self.assertTrue(self.executor._unknown)
    def test_new_run_never_adopts_an_existing_directory(self):
        with self.assertRaises(FileExistsError):W.InstrumentedWorkload(self.directory,plan(),self.producer)
    def test_cas_conflict_rejected_without_changing_snapshot(self):
        original=(self.directory/'state.json').read_bytes()
        with self.assertRaises(P.Invalid):self.executor.journal.append('HALT',dict(reason='INTERRUPTED',unknown=False),'0'*64)
        self.assertEqual(original,(self.directory/'state.json').read_bytes())
    def test_symlink_and_unknown_files_fail_closed(self):
        (self.directory/'unknown').write_text('SECRET')
        with self.assertRaises(P.Invalid):self.executor.journal.inspect()
    def test_reused_open_refused_and_all_gate_flags_false(self):
        self.start()
        with self.assertRaises(P.Invalid):self.start()
        for key in P.FALSE_FLAGS:self.assertIs(False,self.executor.report()[key])

    def test_first_real_complete_observation_is_required_before_work(self):
        self.executor.open()
        with self.assertRaises(W.Stop):self.executor.select_work(0)
        self.assertEqual(['OPEN'],self.producer.calls)
        self.assertIsNone(self.executor._last_complete)
    def test_observe_after_unknown_never_starts_instrumentation(self):
        self.start();self.producer.failure=TimeoutError();self.finish_work()
        before=list(self.producer.calls);self.executor.observe();self.executor.cleanup()
        self.assertEqual(before,self.producer.calls)
    def test_same_resource_incident_observation_does_not_renew_cooldown_or_count(self):
        self.start()
        value=checkpoint('OBSERVE','OBSERVED');value['stamp']=self.producer.stamp()
        value['resourceExit']=dict(pid=101,timestampWallMillis=1234,reason=13,classification='MEMORY_LIMITER',
            windowBootCount=7,correlation='PAIRED_LIVE_WINDOW')
        self.executor._resource_observed(value);old=self.executor._resource_fence
        self.time.advance(100);value['stamp']=self.producer.stamp();self.executor._resource_observed(value)
        self.assertEqual(1,self.executor._resource_deaths);self.assertEqual(old,self.executor._resource_fence)
        value['resourceExit']['classification']='UNCLASSIFIED';self.executor._resource_observed(value)
        self.assertEqual(1,self.executor._resource_deaths)
    def test_thread_start_exception_cannot_release_uncertain_slot_for_observe_or_cleanup(self):
        self.start();original=RuntimeError('SECRET_THREAD_START')
        with mock.patch.object(W.threading.Thread,'start',side_effect=original):
            with self.assertRaises(RuntimeError) as caught:self.executor.select_work(0)
        self.assertIs(original,caught.exception);self.assertTrue(self.executor._unknown)
        before=list(self.producer.calls);self.executor.observe();self.executor.cleanup()
        self.assertEqual(before,self.producer.calls);self.assertFalse(self.executor.close())
        # Fixture proves no thread was actually started by its mock; release only HOST test descriptors.
        self.executor._active=False;self.executor._remote.release()
    def test_constructor_bad_ack_or_generator_exit_closes_store_lock_without_deleting_evidence(self):
        for failure in [None,GeneratorExit('SECRET_INTERRUPT')]:
            target=Path(self.temp.name)/('constructor-'+str(failure is None))
            original=J.InvocationJournal._ack
            def bad(store,*args,**kwargs):
                if failure is not None:raise failure
                value=original(store,*args,**kwargs);value['entries']=True;return value
            with mock.patch.object(J.InvocationJournal,'_ack',bad):
                with self.assertRaises(P.Invalid if failure is None else GeneratorExit):
                    W.InstrumentedWorkload(target,plan(),self.producer)
            # Locked inode can be inspected independently: no leaked constructor owner.
            self.assertTrue((target/'state.json').exists())
            self.assertFalse(J.inspect_existing(target)['automaticResume'])
    def test_short_schedule_never_claims_target_duration_was_observed(self):
        self.start();self.finish_work()
        state=self.executor.journal.inspect()['validation']
        self.assertFalse(state['windowCoveredByCheckpoints'])
        self.assertFalse(self.executor.report()['targetWindowCoveredByCompleteCheckpoints'])

    def test_journal_byte_exhaustion_preserves_unknown_without_invocation_or_replay(self):
        self.start()
        with mock.patch.object(P,'MAX_JOURNAL_BYTES',1):
            with self.assertRaises(P.Invalid):self.executor.select_work(0)
        self.assertTrue(self.executor._unknown);self.assertEqual(['OPEN','OBSERVE'],self.producer.calls)
        before=list(self.producer.calls);self.executor.observe();self.executor.cleanup()
        self.assertEqual(before,self.producer.calls)

    def test_worker_generator_exit_is_preserved_before_any_later_clock_failure(self):
        self.start();original=GeneratorExit('SECRET_ORIGINAL_INTERRUPT');self.producer.failure=original;self.finish_work()
        with mock.patch.object(self.executor,'_now',side_effect=RuntimeError('SECONDARY_CLOCK_ERROR')):
            with self.assertRaises(GeneratorExit) as caught:self.executor._check()
        self.assertIs(original,caught.exception);self.assertTrue(self.executor._unknown)
        self.assertNotIn('SECRET',(self.directory/'state.json').read_text())

    def cold(self,mode):
        p=plan();p['bootstrapOperation']=mode
        directory=Path(self.temp.name)/('cold-'+mode);producer=ColdProducer(self.time,directory)
        executor=W.InstrumentedWorkload(directory,p,producer);self.addCleanup(executor.close)
        return executor,producer
    def test_cold_none_keeps_ai_unavailable_and_refuses_work_without_binding_observer(self):
        executor,producer=self.cold('NONE');executor.open();executor.observe()
        with self.assertRaises(W.Stop):executor.select_work(0)
        self.assertEqual(['OPEN','OBSERVE'],producer.calls);self.assertIsNone(executor._start)
    def test_cold_explicit_bootstrap_has_durable_intent_and_actual_complete_baseline_before_work(self):
        executor,producer=self.cold('CORE_ONCE');executor.open()
        with mock.patch.object(W.time,'sleep',side_effect=lambda seconds:self.time.advance(max(1,int(seconds*1000)))):
            self.assertTrue(executor.bootstrap())
        self.assertIsNone(executor._start);executor.observe();self.assertIsNotNone(executor._start)
        executor.select_work(0);executor._worker.join(2)
        self.assertEqual(['OPEN','BOOTSTRAP','OBSERVE','WORK'],producer.calls)
        entries=executor.journal.inspect()['journal']['entries'];kinds=[e['kind'] for e in entries]
        self.assertLess(kinds.index('BOOTSTRAP_INTENT'),kinds.index('BOOTSTRAP_RESULT'))
        with self.assertRaises((W.Stop,P.Invalid)):executor.bootstrap()
        self.assertEqual(1,producer.calls.count('BOOTSTRAP'))
    def test_cold_bootstrap_unknown_never_starts_window_or_replays_or_cleans(self):
        executor,producer=self.cold('CORE_ONCE');executor.open();producer.failure=TimeoutError('SECRET_BOOTSTRAP')
        self.assertFalse(executor.bootstrap());self.assertTrue(executor._unknown);self.assertIsNone(executor._start)
        executor.observe();executor.cleanup()
        self.assertEqual(['OPEN','BOOTSTRAP'],producer.calls)
        self.assertNotIn('SECRET',(producer.directory/'state.json').read_text())
    def test_bootstrap_without_original_unload_confirmation_never_starts_window(self):
        executor,producer=self.cold('CORE_ONCE');executor.open();producer.bootstrap_settlement='UNPROVEN'
        self.assertFalse(executor.bootstrap());self.assertTrue(executor._unknown);self.assertIsNone(executor._start)
        executor.observe();executor.cleanup();self.assertEqual(['OPEN','BOOTSTRAP'],producer.calls)
    def test_bootstrap_lost_intent_ack_never_invokes_core_or_replays(self):
        executor,producer=self.cold('CORE_ONCE');executor.open();append=executor.journal.append
        def lost(kind,data,expected):
            value=append(kind,data,expected)
            if kind=='BOOTSTRAP_INTENT':raise OSError('SECRET_LOST_ACK')
            return value
        with mock.patch.object(executor.journal,'append',side_effect=lost):self.assertFalse(executor.bootstrap())
        self.assertTrue(executor._unknown);self.assertEqual(['OPEN'],producer.calls)
        with self.assertRaises(P.Invalid):executor.bootstrap()
        self.assertTrue(executor.journal.inspect()['validation']['pending'])
    def test_bootstrap_resource_incident_consumes_conservative_cooldown_before_scheduled_work(self):
        executor,producer=self.cold('CORE_ONCE');executor.open()
        producer.resource_exit=dict(pid=101,timestampWallMillis=1234,reason=13,classification='MEMORY_LIMITER',
            windowBootCount=7,correlation='PAIRED_LIVE_WINDOW')
        with mock.patch.object(W.time,'sleep',side_effect=lambda seconds:self.time.advance(max(1,int(seconds*1000)))):
            self.assertTrue(executor.bootstrap())
        executor.observe();executor.select_work(0)
        self.assertEqual(1,executor._resource_deaths);self.assertNotIn('WORK',producer.calls)
        self.assertEqual('RESOURCE_COOLDOWN',executor.journal.inspect()['journal']['entries'][-1]['data']['reason'])

    def test_review_publisher_ack_is_bound_to_actual_published_inode_and_bytes(self):
        original=J.os.replace
        def substituted(src,dst,**kwargs):
            os.unlink(src,dir_fd=self.executor.journal.fd)
            fd=os.open(src,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o400,dir_fd=self.executor.journal.fd)
            try:os.write(fd,b'{"foreign":"substitution"}')
            finally:os.close(fd)
            return original(src,dst,**kwargs)
        intended=b'{"intent":"owned"}'
        with mock.patch.object(J.os,'replace',side_effect=substituted):
            try:ack=self.executor.journal._publish('state.json',intended)
            except (P.Invalid,J.base.CommitUncertain,J.base.StoreError,OSError):return
        self.assertEqual(P.sha((self.directory/'state.json').read_bytes()),ack)

    def test_review_cleanup_preserves_same_original_interrupt_after_unknown_record_attempt(self):
        self.start();self.finish_work();original=GeneratorExit('SECRET_OWNED_CLEANUP_INTERRUPT')
        call=self.producer.call
        def interrupted(operation,attempt_id=None):
            if operation=='CLEANUP':raise original
            return call(operation,attempt_id)
        self.producer.call=interrupted
        with self.assertRaises(GeneratorExit) as caught:self.executor.cleanup()
        self.assertIs(original,caught.exception);self.assertEqual('UNKNOWN',self.executor.report()['status'])

if __name__=='__main__':unittest.main()
