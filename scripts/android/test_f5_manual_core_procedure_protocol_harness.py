"""Synthetic closed manual-procedure grammar; no model/admission/hardware evidence."""
import copy
import re
import unittest
import uuid

import f5_manual_core_procedure_protocol as M
import f5_instrumented_workload_protocol as P
import f5_runtime_qualification_sidecar as S

RUN = str(uuid.UUID(int=101)); FIXTURE = str(uuid.UUID(int=102)); SESSION = str(uuid.UUID(int=103))

def plan():
    return dict(schemaVersion=2,kind='ORION_F5_MANUAL_CORE_PROCEDURE_PLAN',runId=RUN,ownedFixtureId=FIXTURE,
        identity=dict(sourceCommit='a'*40,sourceManifestSha256='b'*64,appSha256='c'*64,testSha256='d'*64,
            modelSha256=None,modelSizeBytes=None,configurationSha256=None),
        target=dict(deviceIdentitySha256='e'*64,buildFingerprintSha256='f'*64,apiLevel=35,abi='arm64-v8a',pageSizeBytes=4096),
        limits=dict(procedureDurationMillis=1000,transportTimeoutMillis=500,minimumAttemptIntervalMillis=100,
            resourceExitCooldownMillis=200,maximumResourceDeaths=2,maximumStartLatenessMillis=50),
        steps=[dict(offsetMillis=0,operation='WORK'),dict(offsetMillis=100,operation='OBSERVE'),
            dict(offsetMillis=200,operation='OBSERVE'),dict(offsetMillis=300,operation='WORK'),
            dict(offsetMillis=500,operation='OBSERVE'),dict(offsetMillis=800,operation='WORK')])

def stamp(at=1000):return dict(bootCount=7,deviceElapsedMillis=at,deviceElapsedUpperMillis=at,mainPid=100)

def checkpoint(operation,outcome,attempt=None,at=1000):
    return dict(schemaVersion=2,kind='ORION_F5_INSTRUMENTED_CHECKPOINT',operation=operation,runId=RUN,
        fixtureId=FIXTURE,attemptId=attempt,sessionId=SESSION,stamp=stamp(at),outcome=outcome,
        refusal='NO_QUALIFIED_PROFILE' if outcome=='REFUSED' else 'NONE',failureCode=None,
        ownerCallInvoked=operation=='WORK' and outcome!='REFUSED',executionState='IDLE',
        settlement='ORIGINAL_UNLOAD_CONFIRMED' if operation=='WORK' and outcome!='REFUSED' else 'NO_OWNER_CALL',
        resources=None,observation=None,resourceExit=None,policyFacts=None,
        modelSha256=None,configurationSha256=None,runtimeSourceSha256=None,**{key:False for key in P.FALSE_FLAGS})

def incident():return dict(pid=101,timestampWallMillis=1234,reason=13,classification='MEMORY_LIMITER',windowBootCount=7,correlation='PAIRED_LIVE_WINDOW')

def death_confirmation(attempt,at=1500):
    value=checkpoint('OBSERVE','OBSERVED',at=at);value['settlement']='ORIGINAL_PROCESS_DEATH_CONFIRMED'
    value['resourceExit']=incident()
    value['policyFacts']=dict(schemaVersion=1,kind='ORION_ORIGINAL_RESOURCE_POLICY_FACTS',state='AVAILABLE',gap=None,
        droppedAtLeast=0,contendedAtLeast=0,recordingFailuresAtLeast=0,lossAccountingComplete=True,drainContended=False,facts=[],
        exitObservation=dict(gap=None,paired=True,newIncident=True,descriptionEvidence='OTHER_ANON_SWAP_MARKER',dedicatedReason=None),deathSettlement=dict(schemaVersion=1,originalAttemptIdSha256=P.sha(attempt.encode('ascii')),
        originalTerminal='PROCESS_DIED',incidentIdentitySha256=P.incident_identity(value['resourceExit']),
        observedElapsedMillis=at,bootCount=7))
    return value

class ManualProtocolTest(unittest.TestCase):
    def test_soak_checkpoint_accepts_same_closed_sidecar_without_changing_its_gate(self):
        attempt=str(uuid.UUID(int=107));value=death_confirmation(attempt)
        P.checkpoint(value,'OBSERVE',RUN,FIXTURE)
        pending=checkpoint('WORK','PROCESS_DIED',attempt)
        pending.update(executionState='DEAD_AWAITING_EVIDENCE',settlement='UNPROVEN')
        P.checkpoint(pending,'WORK',RUN,FIXTURE,attempt)
        self.assertFalse(P.complete_observation(value));self.assertFalse(P.complete_observation(pending))
    def test_manual_plan_is_explicit_closed_and_has_separate_finite_budgets(self):
        selected=plan();M.validate_plan(selected)
        budget=M.reservation(selected)
        self.assertLessEqual(budget['entries'],4096);self.assertLessEqual(budget['journalBytes'],P.MAX_JOURNAL_BYTES)
        for change in [lambda p:p.update(profileApproved=True),lambda p:p['limits'].pop('procedureDurationMillis'),
                       lambda p:p['steps'][0].update(command='SECRET'),lambda p:p['steps'][0].update(operation='RETRY'),
                       lambda p:p['steps'].append(dict(offsetMillis=900,operation='WORK',profile='SECRET'))]:
            broken=copy.deepcopy(selected);change(broken)
            with self.assertRaises(P.Invalid):M.validate_plan(broken)
        with self.assertRaises(P.Invalid):P.validate_plan(selected) # Soak160 is not weakened or selected by this kind.
    def test_confirmed_death_is_explicitly_bound_to_original_attempt_incident_and_actual_stamp(self):
        attempt=str(uuid.UUID(int=104));value=death_confirmation(attempt)
        M.checkpoint(value,'OBSERVE',RUN,FIXTURE)
        self.assertTrue(M.death_matches(value,attempt))
        self.assertFalse(M.death_matches(value,str(uuid.UUID(int=105))))
        for change in [lambda c:c['policyFacts']['deathSettlement'].update(observedElapsedMillis=1501),
                       lambda c:c['policyFacts']['deathSettlement'].update(incidentIdentitySha256='0'*64),
                       lambda c:c['resourceExit'].update(correlation='UNAVAILABLE'),
                       lambda c:c.update(autonomyProven=True)]:
            broken=copy.deepcopy(value);change(broken)
            with self.assertRaises(P.Invalid):M.checkpoint(broken,'OBSERVE',RUN,FIXTURE)
    def test_pending_death_is_not_idle_or_a_source_of_fabricated_zero(self):
        attempt=str(uuid.UUID(int=106));value=checkpoint('WORK','PROCESS_DIED',attempt)
        value.update(executionState='DEAD_AWAITING_EVIDENCE',settlement='UNPROVEN')
        M.checkpoint(value,'WORK',RUN,FIXTURE,attempt)
        self.assertFalse(M.death_matches(value,attempt));self.assertIsNone(value['resources'])
        value['settlement']='ORIGINAL_PROCESS_DEATH_CONFIRMED'
        with self.assertRaises(P.Invalid):M.checkpoint(value,'WORK',RUN,FIXTURE,attempt)
    def test_real_sidecar_maximum_shape_fits_declared_node_object_and_byte_reservation(self):
        value=checkpoint('OBSERVE','OBSERVED',at=P.MAX_INT)
        value['policyFacts']=death_confirmation(str(uuid.UUID(int=109)))['policyFacts']
        policy=value['policyFacts'];policy['deathSettlement']=None
        policy['exitObservation']=dict(gap='LIVE_UNPAIRED',paired=False,newIncident=None,descriptionEvidence=None,dedicatedReason=17)
        for index in range(64):
            fact=dict(batchIndex=index,kind='RESTART',inputSample=dict(bootCount=2**31-1,elapsedMillis=P.MAX_INT),
                block='COOLDOWN',eligibleAsReported=False,degradedAsReported=True,preloadAllowedAsReported=False,
                candidateKeepAliveMillis=P.MAX_INT,candidateModelSha256='a'*64,memoryDeathsAsReported=2**31-1,
                excessiveResourceDeathsAsReported=2**31-1)
            policy['facts'].append(fact)
            if len(P.encoded(policy))>S.MAX_POLICY_BYTES:policy['facts'].pop();break
        self.assertGreater(len(P.encoded(policy)),4096)
        M.checkpoint(value,'OBSERVE',RUN,FIXTURE);P.checkpoint(value,'OBSERVE',RUN,FIXTURE)
        event=dict(sequence=4095,hostElapsedNanoseconds=P.MAX_INT,kind='OBSERVATION',
            data=dict(stepIndex=127,checkpoint=P.journal_checkpoint(value)))
        data=P.encoded(event);unquoted=re.sub(r'"(?:[^"\\]|\\.)*"','""',data.decode('ascii'))
        self.assertLessEqual(len(data),P.MAX_EVENT_BYTES)
        self.assertLessEqual(sum(unquoted.count(ch) for ch in '{[,:'),P.MAX_EVENT_JSON_NODES)
        self.assertLessEqual(unquoted.count('{'),P.MAX_EVENT_JSON_OBJECTS)
        self.assertEqual(event,P.parse(data,P.MAX_EVENT_BYTES))
        policy['facts'].append(dict(policy['facts'][-1],batchIndex=len(policy['facts'])))
        with self.assertRaises(P.Invalid):M.checkpoint(value,'OBSERVE',RUN,FIXTURE)
    def test_dense_schedules_are_rejected_before_open_by_worst_case_shape_reservation(self):
        from test_f5_instrumented_workload_protocol_harness import plan as soak_plan
        dense=soak_plan();dense['limits']['durationMillis']=1000000
        dense['schedule']['observationOffsetsMillis']=list(range(119))
        with self.assertRaises(P.Invalid) as caught:P.validate_plan(dense)
        self.assertEqual('JSON_SHAPE_BUDGET_EXCEEDED',str(caught.exception))
        manual=plan();manual['limits']['minimumAttemptIntervalMillis']=1
        manual['steps']=[dict(offsetMillis=index,operation='WORK') for index in range(M.MAX_STEPS)]
        with self.assertRaises(P.Invalid):M.validate_plan(manual)

if __name__=='__main__':unittest.main()
