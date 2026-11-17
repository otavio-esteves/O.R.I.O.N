"""Actual ASM and fixed frame controls using explicit local synthetic device fixtures."""
import json
from pathlib import Path
import subprocess
import unittest
import uuid

import f5_instrumented_workload_adapters as A
import f5_instrumented_workload_protocol as P
import f5_manual_core_procedure_adapters as B
from test_f5_manual_core_procedure_protocol_harness import plan,checkpoint,RUN,FIXTURE,SESSION

ATTEMPT=str(uuid.UUID(int=202))
def framed(value,status=None,source=None):
    lines=['INSTRUMENTATION_RESULT: '+A.CHECKPOINT+'='+P.encoded(value).decode()]
    if status is not None:lines.append('INSTRUMENTATION_RESULT: orionRuntimeCaptureStatus='+status)
    if source is not None:lines.append('INSTRUMENTATION_RESULT: orionRuntimeCaptureSource='+source)
    return '\n'.join(lines+['INSTRUMENTATION_RESULT: stream=ORION_WORKLOAD_CHECKPOINT','INSTRUMENTATION_CODE: -1'])

class ManualAdapterTest(unittest.TestCase):
    def test_missing_original_program_header_never_turns_a_checkpoint_into_success(self):
        with self.assertRaises(P.Invalid):B.receipt(framed(checkpoint('WORK','SUCCEEDED',ATTEMPT)),'WORK',RUN,FIXTURE,SESSION,ATTEMPT)
        value,source,status=B.receipt(framed(checkpoint('WORK','REFUSED',ATTEMPT)),'WORK',RUN,FIXTURE,SESSION,ATTEMPT)
        self.assertEqual('REFUSED',value['outcome']);self.assertIsNone(source);self.assertIsNone(status)
    def test_duplicate_terminal_or_surplus_and_foreign_identity_are_closed_errors(self):
        valid=framed(checkpoint('WORK','REFUSED',ATTEMPT))
        for text in [valid+'\nINSTRUMENTATION_RESULT: extra=SECRET',valid+'\nINSTRUMENTATION_CODE: -1',valid+'\nSECRET']:
            with self.assertRaises(P.Invalid):B.receipt(text,'WORK',RUN,FIXTURE,SESSION,ATTEMPT)
        foreign=checkpoint('WORK','REFUSED',ATTEMPT);foreign['sessionId']=str(uuid.uuid4())
        with self.assertRaises(P.Invalid):B.receipt(framed(foreign),'WORK',RUN,FIXTURE,SESSION,ATTEMPT)
    def test_cli_only_accepts_explicit_technical_inputs_and_never_echoes_secret_profile_content(self):
        script=Path(__file__).with_name('f5_manual_core_procedure.py')
        result=subprocess.run(['python3','-B',str(script),'--profile','SECRET_SENTINEL'],capture_output=True,text=True)
        self.assertEqual(2,result.returncode);self.assertNotIn('SECRET_SENTINEL',result.stdout+result.stderr)

    def producer(self,mutate=None,size=None):
        import test_f5_runtime_source_assembler_harness as fixtures
        source,host,expected_document=fixtures.capsule();header=fixtures.status();header['ownerCallInvoked']=True
        if mutate:mutate(source)
        raw=fixtures.encoded(source).decode('ascii');selected=plan();identity=expected_document['identity']
        selected['identity'].update(sourceCommit=identity['sourceCommit'],sourceManifestSha256=identity['sourceManifestSha256'],
            appSha256=identity['appApkSha256'],testSha256=identity['testApkSha256'],modelSha256=identity['modelSha256'],
            modelSizeBytes=size if size is not None else identity['modelSizeBytes'])
        selected['target']['buildFingerprintSha256']=host['deviceBuildSha256']
        value=checkpoint('WORK','SUCCEEDED',ATTEMPT,8000);value.update(modelSha256=identity['modelSha256'],runtimeSourceSha256=P.sha(raw.encode()))
        value['stamp']['mainPid']=123
        output=framed(value,fixtures.encoded(header).decode(),raw)
        class Device:
            plan=selected;_stamp=(str(uuid.UUID(int=900)),None);reads=0
            def revalidate(self):
                self.reads+=1;at=999 if self.reads==1 else 8001
                return dict(bootCount=7,mainPid=123,deviceElapsedMillis=at,deviceElapsedUpperMillis=at)
            def stamp(self):return dict(bootCount=7,mainPid=123,deviceElapsedMillis=8001,deviceElapsedUpperMillis=8001)
            def total_memory(self):return host['totalMemoryBytes']
            def adb(self,*command):
                if command!=A.instrumentation_command('WORK',RUN,FIXTURE,SESSION,ATTEMPT):raise AssertionError('Unexpected fixed command')
                return output
        producer=B.ManualProducer(Device());producer.session=SESSION;return producer
    def test_actual_assembler_positive_has_independent_model_size_and_real_public_validation_seam(self):
        value,artifacts=self.producer().call('WORK',ATTEMPT,invocation_fence=lambda:None)
        self.assertEqual('ORION_F5_RUNTIME_EVIDENCE',json.loads(artifacts.runtime_json)['kind'])
        self.assertEqual('NOT_QUALIFIED',json.loads(artifacts.report_json)['qualification'])
        self.assertEqual('SUCCEEDED',value['outcome']);self.assertNotIn('SECRET',artifacts.report_json.decode())
    def test_actual_assembler_rejects_raw_extra_content_and_independent_size_mismatch(self):
        import f5_runtime_source_assembler as assembler
        for producer in [self.producer(lambda source:source.update(SECRET_SENTINEL='SECRET_CONTENT')),self.producer(size=1024**3+1)]:
            with self.assertRaises(assembler.Rejected) as caught:producer.call('WORK',ATTEMPT,invocation_fence=lambda:None)
            self.assertNotIn('SECRET',str(caught.exception))
    def test_last_host_fence_after_slow_preflight_prevents_the_actual_instrumentation(self):
        producer=self.producer();called=[]
        producer.device.adb=lambda *args:called.append(args)
        original=P.Invalid('PROCEDURE_ENDED')
        def expired():raise original
        with self.assertRaises(P.Invalid) as caught:producer.call('WORK',ATTEMPT,invocation_fence=expired)
        self.assertIs(original,caught.exception);self.assertEqual([],called)

if __name__=='__main__':unittest.main()
