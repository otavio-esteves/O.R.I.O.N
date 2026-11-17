"""Synthetic grammar fixtures; never physical, calibrated or admission evidence."""
import copy
import unittest
import uuid
import f5_instrumented_workload_protocol as P

ID = str(uuid.UUID(int=1)); FIXTURE = str(uuid.UUID(int=2)); SESSION = str(uuid.UUID(int=3))
def plan():
    return {'schemaVersion': 2, 'kind': 'ORION_F5_INSTRUMENTED_WORKLOAD_PLAN', 'runId': ID, 'bootstrapOperation':'NONE',
        'identity': dict(sourceCommit='a'*40, sourceManifestSha256='b'*64, appSha256='c'*64, testSha256='d'*64,
                         modelSha256=None, modelSizeBytes=None, configurationSha256=None),
        'target': dict(deviceIdentitySha256='e'*64, buildFingerprintSha256='f'*64, apiLevel=35, abi='arm64-v8a', pageSizeBytes=4096),
        'limits': dict(durationMillis=1000, transportTimeoutMillis=500, maximumObservationGapMillis=500,
                       minimumAttemptIntervalMillis=100, resourceExitCooldownMillis=200, maximumResourceDeaths=2, maximumStartLatenessMillis=100),
        'schedule': dict(workOffsetsMillis=[0], observationOffsetsMillis=[0, 500, 999]), 'ownedFixtureId': FIXTURE}
def stamp(t=1): return dict(bootCount=7, deviceElapsedMillis=t, deviceElapsedUpperMillis=t, mainPid=100)
def checkpoint(operation, outcome, attempt=None):
    return dict(schemaVersion=2, kind='ORION_F5_INSTRUMENTED_CHECKPOINT', operation=operation, runId=ID,
        fixtureId=FIXTURE, attemptId=attempt, sessionId=SESSION, stamp=stamp(), outcome=outcome,
        refusal='NO_QUALIFIED_PROFILE' if outcome=='REFUSED' else 'NONE', failureCode=None, ownerCallInvoked=operation in {'WORK','BOOTSTRAP'} and outcome!='REFUSED', executionState='IDLE', settlement='ORIGINAL_UNLOAD_CONFIRMED' if operation in {'WORK','BOOTSTRAP'} and outcome!='REFUSED' else 'NO_OWNER_CALL', resources=None, observation=None,
        resourceExit=None, policyFacts=None, modelSha256=None, configurationSha256=None, runtimeSourceSha256=None,
        **{k:False for k in P.FALSE_FLAGS})

class ProtocolTest(unittest.TestCase):
    def complete(self, at):
        c=checkpoint('OBSERVE','OBSERVED');c['stamp']=stamp(at)
        c['resources']=dict(mainPid=100,aiPid=101,mainPssBytes=100,aiPssBytes=200,aggregatePssBytes=300,
            thermalStatus=0,memoryPressure='NONE',serviceState='UNLOADED',nativeRuntimeLoaded=False,
            actionPendingCount=0,actionUnknownCount=0)
        c['observation']=dict(startedElapsedMillis=at,completedElapsedMillis=at,mainSampledElapsedMillis=at,
            aiSampledElapsedMillis=at,maximumSampleAgeMillis=10,bootCount=7,aiBootCount=7,
            mainNativeIncluded=True,aiNativeIncluded=True,nativeValidationCompleted=False)
        return c
    def test_closed_plan_rejects_permissions_parameters_and_defaults(self):
        P.validate_plan(plan())
        for change in [lambda p:p.update(profileApproved=True),lambda p:p['limits'].pop('durationMillis'),
                       lambda p:p['identity'].update(prompt='SECRET'),lambda p:p['schedule'].update(workOffsetsMillis=[200,200]),
                       lambda p:p['target'].update(apiLevel=38),lambda p:p.pop('bootstrapOperation')]:
            p=plan();change(p)
            with self.assertRaises(P.Invalid):P.validate_plan(p)
    def test_entry_budget_and_schedule_cadence(self):
        p=plan();p['limits']['durationMillis']=1000000;p['schedule']['workOffsetsMillis']=list(range(0,25600,100))
        p['schedule']['observationOffsetsMillis']=list(range(3582))
        with self.assertRaises(P.Invalid):P.validate_plan(p)
    def test_checkpoint_scope_flags_and_null_queue_semantics(self):
        c=checkpoint('WORK','REFUSED',str(uuid.UUID(int=4)));c.update(ownerCallInvoked=False,settlement='NO_OWNER_CALL');P.checkpoint(c,'WORK',ID,FIXTURE,c['attemptId'])
        for key,value in [('autonomyProven',True),('sessionId','bad'),('configurationSha256','bad')]:
            broken=copy.deepcopy(c);broken[key]=value
            with self.assertRaises(P.Invalid):P.checkpoint(broken,'WORK',ID,FIXTURE,c['attemptId'])
    def test_strict_input_rejects_duplicate_boolean_numeric_float_and_content(self):
        for data in [b'{"a":1,"a":2}',b'{"a":0.1}',b'{"a":NaN}',b'\xff']:
            with self.assertRaises(P.Invalid):P.parse(data)
        p=plan();p['limits']['durationMillis']=True
        with self.assertRaises(P.Invalid):P.validate_plan(p)

    def test_lexical_bounds_and_python_huge_integer_are_closed_errors_without_content(self):
        for data in [b'{"a":'+b'9'*5000+b'}',b'['*13+b'0'+b']'*13,
                     b'['+b','.join([b'{}']*50001)+b']']:
            with self.assertRaises(P.Invalid) as caught:P.parse(data)
            self.assertNotIn('99999999',str(caught.exception))

    def test_maximum_closed_checkpoint_fits_reserved_event_and_explicit_byte_budget(self):
        from unittest import mock
        c=self.complete(P.MAX_INT);c['stamp']['bootCount']=2**31-1
        c['observation']['bootCount']=c['observation']['aiBootCount']=2**31-1
        c['resourceExit']=dict(pid=2**31-1,timestampWallMillis=P.MAX_INT,reason=2**31-1,
            classification='EXCESSIVE_RESOURCE',windowBootCount=2**31-1,correlation='PAIRED_LIVE_WINDOW')
        for key in ['modelSha256','configurationSha256','runtimeSourceSha256']:c[key]='a'*64
        for key in ['actionPendingCount','actionUnknownCount']:c['resources'][key]=1000000
        c['resources'].update(mainPssBytes=2**39,aiPssBytes=2**39,aggregatePssBytes=2**40)
        P.checkpoint(c,'OBSERVE',ID,FIXTURE)
        event=dict(sequence=4095,hostElapsedNanoseconds=P.MAX_INT,kind='OBSERVATION',data=P.journal_checkpoint(c))
        self.assertLessEqual(len(P.encoded(event)),P.MAX_EVENT_BYTES)
        with mock.patch.object(P,'MAX_JOURNAL_BYTES',1000):
            with self.assertRaises(P.Invalid) as caught:P.validate_plan(plan())
        self.assertEqual('BYTE_BUDGET_EXCEEDED',str(caught.exception))

    def test_representable_24h_schedule_needs_actual_reported_span_and_keeps_all_claims_false(self):
        p=plan();p['limits'].update(durationMillis=86400000,maximumObservationGapMillis=60000)
        p['schedule']['observationOffsetsMillis']=list(range(0,86400001,60000));P.validate_plan(p)
        meta=dict(schemaVersion=1,clockSource='CLOCK_BOOTTIME',bootIdentitySha256='a'*64,
            sessionId=str(uuid.UUID(int=99)),evidence='HOST_CLOCK_ONLY',qualification='NOT_QUALIFIED')
        entries=[]
        def add(kind,data):entries.append(dict(sequence=len(entries),hostElapsedNanoseconds=len(entries),kind=kind,data=data))
        add('OPEN_INTENT',dict(attemptId=None,fixtureId=FIXTURE,stamp=stamp(1),invocationFenceSha256='b'*64))
        add('OPEN_RESULT',P.journal_checkpoint(checkpoint('OPEN','OPENED')))
        for offset in p['schedule']['observationOffsetsMillis']:
            add('OBSERVATION',P.journal_checkpoint(self.complete(1+offset)))
        journal=dict(schemaVersion=2,kind='ORION_F5_INVOCATION_JOURNAL',planSha256=P.sha(P.encoded(p)),hostTiming=meta,entries=entries)
        result=P.validate_journal(p,journal)
        self.assertTrue(result['windowCoveredByCheckpoints']);self.assertEqual(86400000,result['reportedSourceSpanMillis'])
        self.assertLess(len(P.encoded(journal)),P.MAX_JOURNAL_BYTES)
        self.assertEqual(journal,P.parse(P.encoded(journal),P.MAX_JOURNAL_BYTES))
        self.assertTrue(all(e['data'][key] is False for e in entries if e['kind']=='OBSERVATION' for key in P.FALSE_FLAGS))
        short=copy.deepcopy(journal);short['entries'].pop()
        self.assertFalse(P.validate_journal(p,short)['windowCoveredByCheckpoints'])

if __name__=='__main__':unittest.main()
