"""Fixed adapter/transport controls. All ADB-like executables are synthetic local subprocesses."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid
from unittest import mock

import f5_instrumented_workload_adapters as A
import f5_instrumented_workload_protocol as P
from test_f5_instrumented_workload_protocol_harness import checkpoint, plan, ID, FIXTURE, SESSION

ATTEMPT=str(uuid.UUID(int=4))
def output(operation='WORK',outcome='REFUSED',attempt=ATTEMPT):
    value=checkpoint(operation,outcome,attempt)
    return 'INSTRUMENTATION_RESULT: '+A.CHECKPOINT+'='+P.encoded(value).decode()+\
           '\nINSTRUMENTATION_RESULT: stream=ORION_WORKLOAD_CHECKPOINT\nINSTRUMENTATION_CODE: -1'

class AdapterTest(unittest.TestCase):
    def test_review_transport_preserves_same_original_interrupt_and_attempts_every_cleanup(self):
        import test_f5_instrumented_workload_harness as fixtures
        original=GeneratorExit('SECRET_ORIGINAL_INTERRUPT');selector=mock.Mock()
        selector.select.side_effect=original;selector.close.side_effect=OSError('SECONDARY_CLEANUP')
        child=mock.Mock();child.stdout.fileno.return_value=83;child.poll.return_value=None
        adapter=A.FixedAdb('synthetic-adb','synthetic-target',1000)
        with mock.patch.object(A.HostClock,'linux',return_value=fixtures.Time().factory()), \
             mock.patch.object(A.subprocess,'Popen',return_value=child), \
             mock.patch.object(A.selectors,'DefaultSelector',return_value=selector),mock.patch.object(A.os,'set_blocking'):
            with self.assertRaises(GeneratorExit) as caught:adapter(*A.instrumentation_command('OPEN',ID,FIXTURE))
        self.assertIs(original,caught.exception);self.assertTrue(adapter.uncertain)
        selector.close.assert_called_once();child.kill.assert_called_once();child.wait.assert_called_once_with(timeout=1)
        child.stdout.close.assert_called_once();self.assertFalse(adapter._instrument.locked())
    def test_closed_command_has_no_profile_or_arbitrary_shell_seam(self):
        valid=A.instrumentation_command('WORK',ID,FIXTURE,SESSION,ATTEMPT)
        self.assertTrue(A.allowed_command(valid))
        for command in [('shell','sh','-c','echo SECRET'),('shell','am','force-stop',A.PACKAGE),
                        ('shell','am','instrument','-w','-r',A.RUNNER),
                        valid[:-1]+('-e','modelId','SECRET',valid[-1])]:
            self.assertFalse(A.allowed_command(command))
        self.assertTrue(A.allowed_command(('shell','am','help')))
        self.assertFalse(A.allowed_command(('shell','cat','/proc/../../SECRET/stat')))
        bootstrap=A.instrumentation_command('BOOTSTRAP',ID,FIXTURE,SESSION,ATTEMPT)
        self.assertTrue(A.allowed_command(bootstrap))
        with self.assertRaises(P.Invalid):A.instrumentation_command('BOOTSTRAP',ID,FIXTURE,SESSION)
    def test_selector_creation_interruption_marks_unknown_and_releases_only_owned_transport_lock(self):
        adapter=A.FixedAdb('synthetic-adb','synthetic-target',1000);original=SystemExit('SECRET_SELECTOR_INTERRUPT')
        with mock.patch.object(A.selectors,'DefaultSelector',side_effect=original):
            with self.assertRaises(SystemExit) as caught:adapter(*A.instrumentation_command('OPEN',ID,FIXTURE))
        self.assertIs(original,caught.exception);self.assertTrue(adapter.uncertain);self.assertFalse(adapter._instrument.locked())
    def test_frame_accepts_exact_closed_actual_no_source_refusal(self):
        value,source,status=A.receipt(output(),'WORK',ID,FIXTURE,SESSION,ATTEMPT)
        self.assertEqual('REFUSED',value['outcome']);self.assertIsNone(source);self.assertIsNone(status)
    def test_result_after_terminal_duplicate_code_or_surplus_is_rejected(self):
        for text in [output()+'\nINSTRUMENTATION_RESULT: ignored=SECRET',output()+'\nINSTRUMENTATION_CODE: -1',
                     output()+'\nSECRET',output().replace('CODE: -1','CODE: 0')]:
            with self.assertRaises(P.Invalid):A.receipt(text,'WORK',ID,FIXTURE,SESSION,ATTEMPT)
    def test_changed_graph_nonce_or_attempt_or_false_authority_rejected(self):
        for key,value in [('sessionId',str(uuid.UUID(int=50))),('attemptId',str(uuid.UUID(int=60))),('autonomyProven',True)]:
            original=checkpoint('WORK','REFUSED',ATTEMPT);original[key]=value
            text='INSTRUMENTATION_RESULT: '+A.CHECKPOINT+'='+P.encoded(original).decode()+\
                 '\nINSTRUMENTATION_RESULT: stream=ORION_WORKLOAD_CHECKPOINT\nINSTRUMENTATION_CODE: -1'
            with self.assertRaises(P.Invalid):A.receipt(text,'WORK',ID,FIXTURE,SESSION,ATTEMPT)
    def executable(self,directory,body):
        path=Path(directory)/'synthetic-adb';path.write_text('#!/usr/bin/env python3\n'+body);path.chmod(0o700);return path
    def test_real_subprocess_timeout_makes_instrumentation_unknown_without_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path=self.executable(directory,'import time\ntime.sleep(2)\n')
            adb=A.FixedAdb(path,'synthetic-target',50)
            command=A.instrumentation_command('WORK',ID,FIXTURE,SESSION,ATTEMPT)
            with self.assertRaises(A.TransportUnknown):adb(*command)
            self.assertTrue(adb.uncertain)
            with self.assertRaises(P.Invalid):adb(*command)
    def test_real_subprocess_failure_and_output_overflow_are_not_core_terminals(self):
        with tempfile.TemporaryDirectory() as directory:
            for body in ['raise SystemExit(7)\n',"print('x'*(256*1024+1))\n"]:
                path=self.executable(directory,body);adb=A.FixedAdb(path,'synthetic-target',1000)
                with self.assertRaises(A.TransportUnknown):adb(*A.instrumentation_command('WORK',ID,FIXTURE,SESSION,ATTEMPT))
                self.assertTrue(adb.uncertain)
    def test_cli_rejects_profile_content_without_echoing_it_or_running_adb(self):
        script=Path(__file__).with_name('f5_instrumented_workload.py')
        value=subprocess.run(['python3','-B',str(script),'--profile','SECRET_SENTINEL'],capture_output=True,text=True)
        self.assertEqual(2,value.returncode);self.assertNotIn('SECRET_SENTINEL',value.stdout+value.stderr)
        self.assertIn('CLI_ARGUMENTS_INVALID',value.stderr)
    def test_target_lock_is_exclusive_and_never_claims_android_liveness(self):
        identity=uuid.uuid4().hex*2;first=A.TargetLock(identity)
        try:
            with self.assertRaises(BlockingIOError):A.TargetLock(identity)
        finally:first.close()
        second=A.TargetLock(identity);second.close()

    def source_producer(self, mutate=None, expected_size=None):
        import test_f5_runtime_source_assembler_harness as fixtures
        source,host,document=fixtures.capsule();header=fixtures.status();header['ownerCallInvoked']=True
        if mutate:mutate(source)
        source_text=fixtures.encoded(source).decode('ascii')
        selected=plan();ident=document['identity']
        selected['identity'].update(sourceCommit=ident['sourceCommit'],sourceManifestSha256=ident['sourceManifestSha256'],
            appSha256=ident['appApkSha256'],testSha256=ident['testApkSha256'],modelSha256=ident['modelSha256'],
            modelSizeBytes=expected_size or ident['modelSizeBytes'])
        selected['target']['buildFingerprintSha256']=host['deviceBuildSha256']
        value=checkpoint('WORK','SUCCEEDED',ATTEMPT);value.update(modelSha256=ident['modelSha256'],
            runtimeSourceSha256=P.sha(source_text.encode('ascii')))
        value['stamp'].update(mainPid=123,deviceElapsedMillis=8000,deviceElapsedUpperMillis=8000)
        framed='INSTRUMENTATION_RESULT: '+A.CHECKPOINT+'='+P.encoded(value).decode()+\
            '\nINSTRUMENTATION_RESULT: orionRuntimeCaptureStatus='+fixtures.encoded(header).decode()+\
            '\nINSTRUMENTATION_RESULT: orionRuntimeCaptureSource='+source_text+\
            '\nINSTRUMENTATION_RESULT: stream=ORION_WORKLOAD_CHECKPOINT\nINSTRUMENTATION_CODE: -1'
        class Device:
            plan=selected
            _stamp=(str(uuid.UUID(int=900)),None)
            reads=0
            def revalidate(self):
                self.reads+=1
                return dict(bootCount=7,mainPid=123,deviceElapsedMillis=999 if self.reads==1 else 8001,
                            deviceElapsedUpperMillis=999 if self.reads==1 else 8001)
            def stamp(self):return dict(bootCount=7,mainPid=123,deviceElapsedMillis=8001,deviceElapsedUpperMillis=8001)
            def total_memory(self):return host['totalMemoryBytes']
            def adb(self,*command):
                if command!=A.instrumentation_command('WORK',ID,FIXTURE,SESSION,ATTEMPT):raise AssertionError('unexpected command')
                return framed
        producer=A.InstrumentedProducer(Device());producer.session=SESSION
        return producer
    def test_real_assembler_integration_uses_independent_identity_and_host_window(self):
        value,artifacts=self.source_producer().call('WORK',ATTEMPT)
        runtime=json.loads(artifacts.runtime_json)
        self.assertEqual('ORION_F5_RUNTIME_EVIDENCE',runtime['kind'])
        self.assertEqual('5'*64,runtime['identity']['modelSha256'])
        self.assertEqual(P.sha(str(uuid.UUID(int=900)).encode()),runtime['session']['bootIdSha256'])
        self.assertEqual('NOT_QUALIFIED',json.loads(artifacts.report_json)['qualification'])
        self.assertEqual('SUCCEEDED',value['outcome'])
    def test_real_assembler_rejects_raw_extra_content_before_any_persistable_artifact(self):
        import f5_runtime_source_assembler as assembler
        producer=self.source_producer(lambda source:source.update(SECRET_SENTINEL='SECRET_CONTENT'))
        with self.assertRaises(assembler.Rejected) as caught:producer.call('WORK',ATTEMPT)
        self.assertNotIn('SECRET',str(caught.exception))
    def test_real_assembler_refuses_model_size_reconstructed_from_source(self):
        import f5_runtime_source_assembler as assembler
        with self.assertRaises(assembler.Rejected):self.source_producer(expected_size=1024**3+1).call('WORK',ATTEMPT)

if __name__=='__main__':unittest.main()
