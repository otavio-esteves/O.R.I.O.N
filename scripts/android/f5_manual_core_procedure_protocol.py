"""Closed finite manual Core procedure. Facts and invocation fences never grant admission."""
import re
import f5_instrumented_workload_protocol as P
import f5_runtime_qualification_sidecar as S

MAX_STEPS = 128
MAX_EVENT_BYTES = P.MAX_EVENT_BYTES
MAX_CHECKPOINT_BYTES = P.MAX_CHECKPOINT_BYTES
PLAN_KIND = 'ORION_F5_MANUAL_CORE_PROCEDURE_PLAN'
JOURNAL_KIND = 'ORION_F5_MANUAL_CORE_PROCEDURE_JOURNAL'
STORE_KIND = 'ORION_F5_MANUAL_CORE_PROCEDURE_STORE'
REASONS = {'PROCEDURE_COMPLETED','PROCEDURE_ENDED','SCHEDULE_MISSED','CORE_REFUSED','CORE_FAILED',
    'DEATH_EVIDENCE_PENDING','RESOURCE_COOLDOWN','RESOURCE_LIMIT','CADENCE','BUSY','IDENTITY_CHANGED',
    'TRANSPORT','INTERRUPTED','STORAGE_UNAVAILABLE','CLEANUP_REFUSED'}


def reservation(plan):
    work = sum(step['operation'] == 'WORK' for step in plan['steps'])
    observe = len(plan['steps']) - work
    entries = 8 + 3*work + 2*observe
    journal = P.MAX_HEADER_BYTES + entries*MAX_EVENT_BYTES
    artifacts = work*(4*1024*1024+1024+4096)
    return dict(entries=entries,journalBytes=journal,artifactBytes=artifacts,totalBytes=P.MAX_BYTES+journal+artifacts,
        jsonObjects=P.MAX_HEADER_JSON_OBJECTS+entries*P.MAX_EVENT_JSON_OBJECTS,jsonNodes=P.MAX_HEADER_JSON_NODES+entries*P.MAX_EVENT_JSON_NODES)


def validate_plan(plan):
    P.fields(plan,{'schemaVersion','kind','runId','ownedFixtureId','identity','target','limits','steps'})
    P.integer(plan['schemaVersion'],2,2);P.require(plan['kind'] == PLAN_KIND)
    P.identifier(plan['runId']);P.identifier(plan['ownedFixtureId'])
    identity=plan['identity'];P.fields(identity,P.IDENTITY_KEYS)
    P.require(type(identity['sourceCommit']) is str and re.fullmatch('[0-9a-f]{40}',identity['sourceCommit']) is not None)
    for key in P.IDENTITY_KEYS-{'sourceCommit','modelSizeBytes'}:
        P.digest(identity[key],nullable=key in {'modelSha256','configurationSha256'})
    P.require((identity['modelSizeBytes'] is None) == (identity['modelSha256'] is None))
    if identity['modelSizeBytes'] is not None:P.integer(identity['modelSizeBytes'],1,4*1024**3)
    target=plan['target'];P.fields(target,{'deviceIdentitySha256','buildFingerprintSha256','apiLevel','abi','pageSizeBytes'})
    P.digest(target['deviceIdentitySha256']);P.digest(target['buildFingerprintSha256']);P.integer(target['apiLevel'],31,37)
    P.require(target['abi'] in {'arm64-v8a','armeabi-v7a','x86','x86_64'} and target['pageSizeBytes'] in {4096,16384})
    limits=plan['limits'];P.fields(limits,{'procedureDurationMillis','transportTimeoutMillis','minimumAttemptIntervalMillis',
        'resourceExitCooldownMillis','maximumResourceDeaths','maximumStartLatenessMillis'})
    for key,maximum in [('procedureDurationMillis',7*86400000),('transportTimeoutMillis',180000),
        ('minimumAttemptIntervalMillis',3600000),('resourceExitCooldownMillis',86400000),
        ('maximumResourceDeaths',16),('maximumStartLatenessMillis',3600000)]:P.integer(limits[key],1,maximum)
    steps=plan['steps'];P.require(type(steps) is list and 1 <= len(steps) <= MAX_STEPS,'STEP_BOUND_INVALID')
    previous=-1;last_work=None
    for step in steps:
        P.fields(step,{'offsetMillis','operation'});P.integer(step['offsetMillis'],previous+1,limits['procedureDurationMillis']-1)
        P.require(step['operation'] in {'OBSERVE','WORK'},'STEP_OPERATION_INVALID');previous=step['offsetMillis']
        if step['operation']=='WORK':
            P.require(last_work is None or previous-last_work >= limits['minimumAttemptIntervalMillis'],'SCHEDULE_CADENCE_INVALID')
            last_work=previous
    P.require(last_work is not None,'WORK_STEP_REQUIRED')
    budget=reservation(plan)
    P.require(budget['entries'] <= P.MAX_ENTRIES and budget['journalBytes'] <= P.MAX_JOURNAL_BYTES and
        budget['totalBytes'] <= 768*1024*1024 and budget['jsonObjects']<=P.MAX_JSON_OBJECTS and
        budget['jsonNodes']<=P.MAX_JSON_NODES,'UPFRONT_BUDGET_EXCEEDED')
    return plan




def checkpoint(value,operation,run,fixture,attempt=None,*,persisted=False):
    P.require(len(P.encoded(value)) <= MAX_CHECKPOINT_BYTES,'CHECKPOINT_BOUND_INVALID')
    session='sessionIdSha256' if persisted else 'sessionId'
    P.fields(value,{'schemaVersion','kind','operation','runId','fixtureId','attemptId',session,'stamp','outcome','refusal',
        'failureCode','ownerCallInvoked','executionState','settlement','resources','observation','resourceExit','policyFacts',
        'modelSha256','configurationSha256','runtimeSourceSha256',*P.FALSE_FLAGS})
    P.integer(value['schemaVersion'],2,2);P.require(value['kind']=='ORION_F5_INSTRUMENTED_CHECKPOINT')
    P.require(value['operation']==operation and value['runId']==run and value['fixtureId']==fixture and value['attemptId']==attempt,
        'CHECKPOINT_SCOPE_MISMATCH')
    if persisted:P.digest(value[session])
    else:P.identifier(value[session])
    P.stamp(value['stamp'])
    allowed={'OPEN':{'OPENED','REFUSED','BUSY','UNAVAILABLE'},'WORK':{'SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','PROCESS_DIED',
        'REFUSED','BUSY','UNAVAILABLE'},'OBSERVE':{'OBSERVED','REFUSED','BUSY','UNAVAILABLE'},'CLEANUP':{'CLEANED','ABSENT','REFUSED','BUSY','UNAVAILABLE','FAILED'}}
    P.require(operation in allowed and value['outcome'] in allowed[operation] and value['refusal'] in P.REFUSALS)
    P.require((value['outcome']=='REFUSED') == (value['refusal']!='NONE'))
    P.require(value['failureCode'] in P.FAILURES and (value['failureCode'] is None or value['outcome']=='FAILED'))
    P.require(value['ownerCallInvoked'] is None or type(value['ownerCallInvoked']) is bool)
    P.require(operation=='WORK' or value['ownerCallInvoked'] is not True)
    P.require(value['executionState'] in {'IDLE','ACTIVE','UNCERTAIN','DEAD_AWAITING_EVIDENCE'})
    P.require(value['settlement'] in {'NO_OWNER_CALL','ORIGINAL_UNLOAD_CONFIRMED','UNPROVEN','ORIGINAL_PROCESS_DEATH_CONFIRMED'})
    if value['settlement']=='NO_OWNER_CALL':P.require(value['ownerCallInvoked'] is False)
    if value['settlement']=='ORIGINAL_UNLOAD_CONFIRMED':
        P.require(operation=='WORK' and value['ownerCallInvoked'] is True and value['executionState']=='IDLE' and
            value['outcome'] in {'SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','PROCESS_DIED'})
    if operation=='WORK' and value['executionState']=='IDLE':P.require(value['settlement']!='UNPROVEN')
    if value['executionState']=='DEAD_AWAITING_EVIDENCE':
        P.require(operation in {'WORK','OBSERVE'} and value['settlement']=='UNPROVEN' and
            (operation!='WORK' or value['outcome']=='PROCESS_DIED' and value['ownerCallInvoked'] is True))
    if value['resources'] is not None:P.resources(value['resources'])
    if value['observation'] is not None:P.observation(value['observation'],value['resources'],value['stamp'])
    P.require(value['resources'] is None or value['observation'] is not None)
    for key in ['modelSha256','configurationSha256','runtimeSourceSha256']:P.digest(value[key],nullable=True)
    incident=value['resourceExit']
    if incident is not None:
        P.incident_identity(incident);P.require(operation in {'WORK','OBSERVE'})
    S.validate(value)
    P.require(all(value[key] is False for key in P.FALSE_FLAGS),'AUTHORITY_CLAIM_FORBIDDEN')
    return value


def death_matches(value,attempt):
    death=S.death(value)
    return death is not None and value['executionState']=='IDLE' and value['settlement']=='ORIGINAL_PROCESS_DEATH_CONFIRMED' and \
        death['originalTerminal']=='PROCESS_DIED' and death['originalAttemptIdSha256']==P.sha(attempt.encode('ascii'))


def fence(plan,operation,attempt,stamp,step):
    return P.sha(P.encoded(dict(runId=plan['runId'],fixtureId=plan['ownedFixtureId'],attemptId=attempt,operation=operation,
        stamp=stamp,stepIndex=step,planSha256=P.sha(P.encoded(plan)))))


def validate_journal(plan,journal):
    validate_plan(plan)
    P.fields(journal,{'schemaVersion','kind','planSha256','hostTiming','reservation','entries'})
    P.integer(journal['schemaVersion'],2,2);P.require(journal['kind']==JOURNAL_KIND)
    P.require(journal['planSha256']==P.sha(P.encoded(plan)) and journal['reservation']==reservation(plan))
    meta=journal['hostTiming'];P.fields(meta,{'schemaVersion','clockSource','bootIdentitySha256','sessionId','evidence','qualification'})
    P.integer(meta['schemaVersion'],1,1);P.require(meta['clockSource']=='CLOCK_BOOTTIME' and
        meta['evidence']=='HOST_CLOCK_ONLY' and meta['qualification']=='NOT_QUALIFIED')
    P.digest(meta['bootIdentitySha256']);P.identifier(meta['sessionId'])
    entries=journal['entries'];P.require(type(entries) is list and len(entries)<=reservation(plan)['entries'])
    previous=-1;pending=None;session=None;owner=None;started=None;step_index=0;used=set();artifacts=set()
    phase='IDLE';dead=None;confirmed=None;unknown=halted=ended=cleanup=False;seen_incidents={}
    for index,event in enumerate(entries):
        P.fields(event,{'sequence','hostElapsedNanoseconds','kind','data'});P.integer(event['sequence'],index,index)
        P.integer(event['hostElapsedNanoseconds'],max(previous,0));previous=event['hostElapsedNanoseconds']
        P.require(len(P.encoded(event))<=MAX_EVENT_BYTES and not ended,'EVENT_BOUND_OR_TERMINAL_INVALID')
        kind,data=event['kind'],event['data'];current_stamp=None
        if kind in {'OPEN_INTENT','WORK_INTENT','CLEANUP_INTENT'}:
            P.fields(data,{'attemptId','fixtureId','stamp','invocationFenceSha256','stepIndex'})
            P.require(pending is None and not unknown,'UNRESOLVED_OR_UNKNOWN_INVOCATION');P.stamp(data['stamp'])
            current_stamp=data['stamp'];operation=kind.split('_')[0]
            P.require(data['fixtureId']==plan['ownedFixtureId'] and data['invocationFenceSha256']==
                fence(plan,operation,data['attemptId'],current_stamp,data['stepIndex']),'INVOCATION_FENCE_INVALID')
            if operation=='OPEN':P.require(index==0 and data['attemptId'] is None and data['stepIndex'] is None)
            elif operation=='WORK':
                P.require(not halted and not cleanup and session is not None and started is not None and phase=='IDLE')
                P.identifier(data['attemptId']);P.require(data['attemptId'] not in used);used.add(data['attemptId'])
                P.integer(data['stepIndex'],step_index,step_index);P.require(step_index<len(plan['steps']) and
                    plan['steps'][step_index]['operation']=='WORK','STEP_SELECTION_INVALID');step_index+=1
                dead=confirmed=None
            else:
                P.require(session is not None and not cleanup and phase=='IDLE' and data['attemptId'] is None and data['stepIndex'] is None)
                cleanup=True
            pending=event
        elif kind in {'OPEN_RESULT','WORK_RESULT','CLEANUP_RESULT'}:
            P.require(pending is not None and pending['kind']==kind.replace('RESULT','INTENT'),'INTENT_MISSING')
            operation=kind.split('_')[0];checkpoint(data,operation,plan['runId'],plan['ownedFixtureId'],pending['data']['attemptId'],persisted=True)
            current_stamp=data['stamp'];old=pending['data']['stamp']
            P.require(current_stamp['bootCount']==old['bootCount'] and current_stamp['mainPid']==old['mainPid'] and
                current_stamp['deviceElapsedMillis']>=old['deviceElapsedMillis'],'OWNER_CHANGED')
            if operation=='OPEN':
                if data['outcome']=='OPENED':session=data['sessionIdSha256']
            else:P.require(data['sessionIdSha256']==session,'OWNER_CHANGED')
            if operation=='WORK':
                if data['outcome']=='PROCESS_DIED':
                    dead=data['attemptId']
                    if data['executionState']=='DEAD_AWAITING_EVIDENCE':phase='DEAD_AWAITING_EVIDENCE'
                    elif death_matches(data,dead):
                        phase='IDLE';confirmed=S.death(data)['incidentIdentitySha256']
                        P.require(confirmed not in seen_incidents or seen_incidents[confirmed]==dead,'INCIDENT_REUSED_FOR_OTHER_ATTEMPT')
                        seen_incidents[confirmed]=dead
                    else:unknown=True;phase='UNCERTAIN'
                elif data['executionState']!='IDLE':unknown=True;phase='UNCERTAIN'
                if data['outcome'] not in {'SUCCEEDED','PROCESS_DIED'}:halted=True
            pending=None
        elif kind=='PROCEDURE_START':
            P.fields(data,{'startedHostNanoseconds','stamp'});P.stamp(data['stamp']);current_stamp=data['stamp']
            P.integer(data['startedHostNanoseconds'],0,event['hostElapsedNanoseconds'])
            P.require(started is None and session is not None and pending is None and not halted and not unknown)
            started=data['startedHostNanoseconds']
        elif kind in {'OBSERVATION','OBSERVATION_GAP','STEP_REFUSED'}:
            P.require(not unknown and not halted and started is not None and not cleanup,'ENTRY_AFTER_HALT_OR_UNKNOWN')
            P.fields(data,{'stepIndex','checkpoint'} if kind=='OBSERVATION' else {'stepIndex','reason','stamp'})
            P.integer(data['stepIndex'],step_index,step_index);P.require(step_index<len(plan['steps']),'STEP_BOUND_INVALID')
            selected=plan['steps'][step_index];step_index+=1
            if kind=='OBSERVATION':
                P.require(selected['operation']=='OBSERVE' and pending is None,'OVERLAPPING_INSTRUMENTATION')
                value=data['checkpoint'];checkpoint(value,'OBSERVE',plan['runId'],plan['ownedFixtureId'],persisted=True)
                P.require(value['sessionIdSha256']==session,'OWNER_CHANGED');current_stamp=value['stamp']
                if S.death(value) is not None:
                    P.require(dead is not None and death_matches(value,dead),'FOREIGN_DEATH_SETTLEMENT')
                    incident=S.death(value)['incidentIdentitySha256']
                    P.require(confirmed is None or confirmed==incident,'DEATH_SETTLEMENT_CHANGED');confirmed=incident;phase='IDLE'
                    P.require(incident not in seen_incidents or seen_incidents[incident]==dead,'INCIDENT_REUSED_FOR_OTHER_ATTEMPT')
                    seen_incidents[incident]=dead
                elif phase=='DEAD_AWAITING_EVIDENCE':P.require(value['executionState']=='DEAD_AWAITING_EVIDENCE')
                elif value['executionState']!='IDLE':unknown=True;phase='UNCERTAIN'
            else:
                P.require(data['reason'] in ({'WORK_INFLIGHT'} if kind=='OBSERVATION_GAP' else
                    {'DEATH_EVIDENCE_PENDING','RESOURCE_COOLDOWN','RESOURCE_LIMIT','CADENCE','BUSY'}))
                P.require(selected['operation']==('OBSERVE' if kind=='OBSERVATION_GAP' else 'WORK'))
                if data['stamp'] is not None:P.stamp(data['stamp']);current_stamp=data['stamp']
        elif kind=='RUNTIME_ARTIFACTS':
            P.fields(data,{'attemptId','runtimeSha256','statusSha256','reportSha256'})
            P.require(pending is not None and pending['kind']=='WORK_INTENT' and data['attemptId']==pending['data']['attemptId'] and
                data['attemptId'] not in artifacts,'ARTIFACT_ORDER_INVALID');artifacts.add(data['attemptId'])
            P.digest(data['runtimeSha256'],nullable=True);P.digest(data['statusSha256']);P.digest(data['reportSha256'])
        elif kind in {'HALT','RUN_END'}:
            P.fields(data,{'reason','unknown'});P.require(data['reason'] in REASONS and type(data['unknown']) is bool)
            P.require(not unknown or data['unknown'],'UNKNOWN_CANNOT_BE_REVERSED')
            unknown|=data['unknown'];halted=True
            if kind=='RUN_END':P.require(pending is None and not unknown and cleanup);ended=True
        else:raise P.Invalid('MANUAL_EVENT_KIND_INVALID')
        if current_stamp is not None:
            if owner is None:owner=current_stamp
            P.require(current_stamp['bootCount']==owner['bootCount'] and current_stamp['mainPid']==owner['mainPid'],'OWNER_CHANGED')
    P.require(len(P.encoded(journal))<=P.MAX_JOURNAL_BYTES,'JOURNAL_BYTE_BOUND_INVALID')
    return dict(pendingInvocation=pending is not None,unknown=unknown,nativeState=phase,entries=len(entries),stepsConsumed=step_index,
        sessionIdSha256=session,deadAttemptIdSha256=None if dead is None else P.sha(dead.encode('ascii')),
        confirmedIncidentIdentitySha256=confirmed,ended=ended,automaticResume=False,continuousCoverageClaimed=False)
