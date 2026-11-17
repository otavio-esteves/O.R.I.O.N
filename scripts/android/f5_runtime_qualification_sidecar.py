"""Closed original policy/paired-death facts. Never an approval or a new execution authority."""
import f5_instrumented_workload_protocol as P

MAX_POLICY_BYTES = 8 * 1024
EXIT_GAPS = {'CONFIGURATION_UNAVAILABLE','CLOCK_UNAVAILABLE','CLOCK_DISCONTINUOUS','BOOT_CHANGED',
    'LIVE_UNPAIRED','LIVE_STALE','IDENTITY_MISMATCH','ENDPOINT_CHANGED','PROC_UNAVAILABLE','PID_REUSED',
    'HISTORY_UNAVAILABLE','HISTORY_TRUNCATED','HISTORY_INVALID','NO_MATCH','AMBIGUOUS','CAPACITY'}
GOVERNOR_BLOCKS = {'OBSERVATION_UNAVAILABLE','OBSERVATION_INVALID','OBSERVATION_STALE','EXIT_HISTORY_UNAVAILABLE',
    'MODEL_UNQUALIFIED','BACKGROUND','BATTERY','THERMAL','POWER_SAVER','REQUEST_LIMITS','MEMORY_PRESSURE',
    'MEMORY_EXIT','MEMORY_BUDGET','RESOURCE_EXIT'}
RESTART_BLOCKS = {'HISTORY_UNAVAILABLE','HISTORY_INVALID','HISTORY_STALE','COOLDOWN','FALLBACK_EXHAUSTED'}


def policy_facts(value):
    P.require(len(P.encoded(value)) <= MAX_POLICY_BYTES,'POLICY_BOUND_INVALID')
    P.fields(value,{'schemaVersion','kind','state','gap','droppedAtLeast','contendedAtLeast','recordingFailuresAtLeast',
        'lossAccountingComplete','drainContended','facts','exitObservation','deathSettlement'})
    P.integer(value['schemaVersion'],1,1);P.require(value['kind']=='ORION_ORIGINAL_RESOURCE_POLICY_FACTS')
    P.require(value['state'] in {'AVAILABLE','UNAVAILABLE'})
    for key in ['droppedAtLeast','contendedAtLeast','recordingFailuresAtLeast']:P.integer(value[key])
    for key in ['lossAccountingComplete','drainContended']:P.require(type(value[key]) is bool)
    observation=value['exitObservation'];P.fields(observation,{'gap','paired','newIncident','descriptionEvidence','dedicatedReason'})
    P.require(observation['gap'] is None or observation['gap'] in EXIT_GAPS)
    P.require(type(observation['paired']) is bool and (observation['newIncident'] is None or type(observation['newIncident']) is bool))
    P.require(observation['descriptionEvidence'] is None or observation['descriptionEvidence'] in {'OTHER_ANON_SWAP_MARKER','NO_MATCH','UNAVAILABLE'})
    if observation['dedicatedReason'] is not None:P.integer(observation['dedicatedReason'],17,2**31-1)
    proof=value['deathSettlement']
    if proof is not None:
        P.fields(proof,{'schemaVersion','originalAttemptIdSha256','originalTerminal','incidentIdentitySha256','observedElapsedMillis','bootCount'})
        P.integer(proof['schemaVersion'],1,1);P.digest(proof['originalAttemptIdSha256']);P.digest(proof['incidentIdentitySha256'])
        P.integer(proof['observedElapsedMillis']);P.integer(proof['bootCount'],0,2**31-1)
        P.require(proof['originalTerminal']=='PROCESS_DIED')
    if value['state']=='UNAVAILABLE':
        P.require(value['gap'] in {'DRAIN_CONTENDED','CAPTURE_LIMIT','EXPORT_TOO_LARGE','EXPORT_UNAVAILABLE'} and
            value['facts'] is None and value['lossAccountingComplete'] is False);return value
    P.require(value['gap'] is None and type(value['facts']) is list and len(value['facts']) <= 64)
    common={'batchIndex','kind','inputSample','block','candidateKeepAliveMillis','candidateModelSha256'}
    for index,fact in enumerate(value['facts']):
        P.require(type(fact) is dict and fact.get('kind') in {'GOVERNOR','RESTART'})
        governor=fact['kind']=='GOVERNOR'
        P.fields(fact,common|({'allowedAsReported'} if governor else {'eligibleAsReported','degradedAsReported',
            'preloadAllowedAsReported','memoryDeathsAsReported','excessiveResourceDeathsAsReported'}))
        P.integer(fact['batchIndex'],index,index)
        sample=fact['inputSample']
        if sample is not None:
            P.fields(sample,{'bootCount','elapsedMillis'});P.integer(sample['elapsedMillis'])
            if sample['bootCount'] is not None:P.integer(sample['bootCount'],0,2**31-1)
        P.require(fact['block'] is None or fact['block'] in (GOVERNOR_BLOCKS if governor else RESTART_BLOCKS))
        P.digest(fact['candidateModelSha256'],nullable=True)
        if fact['candidateKeepAliveMillis'] is not None:P.integer(fact['candidateKeepAliveMillis'])
        for key in (['allowedAsReported'] if governor else ['eligibleAsReported','degradedAsReported','preloadAllowedAsReported']):
            P.require(type(fact[key]) is bool)
        if not governor:
            for key in ['memoryDeathsAsReported','excessiveResourceDeathsAsReported']:P.integer(fact[key],0,2**31-1)
    return value


def death(value):
    return None if value['policyFacts'] is None else value['policyFacts']['deathSettlement']


def validate(value):
    """Sidecar and new technical states only; caller retains its own envelope/budget rules."""
    if value['policyFacts'] is not None:policy_facts(value['policyFacts'])
    proof=death(value);operation=value['operation'];incident=value['resourceExit'];core={'WORK','BOOTSTRAP'}
    if value['policyFacts'] is not None and incident is None:
        P.require(value['policyFacts']['exitObservation']['descriptionEvidence'] is None)
    P.require((proof is not None)==(value['settlement']=='ORIGINAL_PROCESS_DEATH_CONFIRMED'))
    if value['executionState']=='DEAD_AWAITING_EVIDENCE':
        P.require(operation in core|{'OBSERVE'} and value['settlement']=='UNPROVEN' and
            (operation not in core or value['outcome']=='PROCESS_DIED' and value['ownerCallInvoked'] is True))
    if proof is not None:
        P.integer(proof['observedElapsedMillis'],0,value['stamp']['deviceElapsedMillis'])
        P.require(operation in core|{'OBSERVE'} and value['executionState']=='IDLE' and
            proof['bootCount']==value['stamp']['bootCount'] and incident is not None and
            incident['correlation']=='PAIRED_LIVE_WINDOW' and incident['windowBootCount']==proof['bootCount'] and
            P.incident_identity(incident)==proof['incidentIdentitySha256'] and value['policyFacts']['exitObservation']['paired'] is True,
            'DEATH_BINDING_INVALID')
        if operation in core:P.require(value['outcome']=='PROCESS_DIED' and value['ownerCallInvoked'] is True and
            proof['originalAttemptIdSha256']==P.sha(value['attemptId'].encode('ascii')))
    return value
