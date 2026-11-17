"""Closed v2 HOST invocation evidence. No field grants Core admission or qualification."""
import hashlib
import json
import re
import uuid

MAX_BYTES = 2 * 1024 * 1024
MAX_JOURNAL_BYTES = 20 * 1024 * 1024
MAX_STORE_BYTES = MAX_JOURNAL_BYTES + 1024
MAX_EVENT_BYTES = 20 * 1024
MAX_CHECKPOINT_BYTES = 16 * 1024
MAX_HEADER_BYTES = 16384
MAX_JSON_OBJECTS = 50000
MAX_JSON_NODES = 500000
MAX_EVENT_JSON_OBJECTS = 160
MAX_EVENT_JSON_NODES = 2048
MAX_HEADER_JSON_OBJECTS = 64
MAX_HEADER_JSON_NODES = 4096
MAX_ENTRIES = 4096
MAX_INT = 2**63 - 1
FALSE_FLAGS = ('sourceAuthenticated', 'physicalEvidenceVerified', 'real24HourDurationProven',
               'autonomyProven', 'profileQualified', 'productionInferenceEnabled',
               'fullF4GateClosed', 'fullF5GateClosed', 'fullSoakGateClosed', 'f6Authorized')
IDENTITY_KEYS = {'sourceCommit', 'sourceManifestSha256', 'appSha256', 'testSha256', 'modelSha256', 'modelSizeBytes', 'configurationSha256'}
STAMP_KEYS = {'bootCount', 'deviceElapsedMillis', 'deviceElapsedUpperMillis', 'mainPid'}
RESOURCE_KEYS = {'mainPid', 'aiPid', 'mainPssBytes', 'aiPssBytes', 'aggregatePssBytes', 'thermalStatus',
                 'memoryPressure', 'serviceState', 'nativeRuntimeLoaded', 'actionPendingCount', 'actionUnknownCount'}
OUTCOMES = {'OPENED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'PROCESS_DIED', 'REFUSED',
            'CLEANED', 'ABSENT', 'BUSY', 'UNAVAILABLE', 'OBSERVED'}
REFUSALS = {'NO_QUALIFIED_PROFILE', 'RESOURCE_CONFIGURATION_UNAVAILABLE', 'CORE_NOT_READY', 'OWNER_UNAVAILABLE',
            'CLOSED', 'TEMPLATE_UNAVAILABLE', 'TEMPLATE_INVALID', 'MODEL_NOT_READY', 'PROFILE_MISMATCH',
            'SELECTION_UNAVAILABLE', 'RESOURCE_REFUSED', 'IDENTITY_CHANGED', 'ACTIVE_EXECUTION', 'UNKNOWN_EXECUTION',
            'FOREIGN_OWNERSHIP', 'ALREADY_CONSUMED', 'NONE'}
REFUSALS |= {'OBSERVER_UNAVAILABLE','SELECTION_UNAVAILABLE','PROCESS_INVENTORY_UNAVAILABLE','MODEL_UNAVAILABLE','REQUEST_RETIRED'}
REFUSALS.add('CONFIGURATION_UNAVAILABLE')
FAILURES = {None,'SCHEMA_MISMATCH','INVALID_TIME_REFERENCE','REORDER_LIMIT','CONFLICTING_CHUNK','CHUNK_TOO_LARGE','REMOTE_ERROR','TRANSPORT_FAILURE'}
GAPS = {'WORK_INFLIGHT', 'TRANSPORT', 'CADENCE', 'METRIC_UNAVAILABLE', 'DEVICE_REBOOT', 'HOST_INTERRUPTED',
        'OWNER_CHANGED', 'CLOCK_DISCONTINUOUS'}


class Invalid(ValueError):
    pass


def require(value, code='INPUT_INVALID'):
    if not value:
        raise Invalid(code)


def fields(value, expected):
    require(type(value) is dict and set(value) == set(expected), 'FIELDS_INVALID')


def integer(value, low=0, high=MAX_INT):
    require(type(value) is int and low <= value <= high, 'NUMBER_INVALID')


def digest(value, nullable=False):
    require(nullable and value is None or type(value) is str and re.fullmatch('[0-9a-f]{64}', value) is not None,
            'HASH_INVALID')


def identifier(value):
    require(type(value) is str and len(value) == 36, 'ID_INVALID')
    try:
        require(str(uuid.UUID(value)) == value and uuid.UUID(value).int != 0, 'ID_INVALID')
    except ValueError:
        raise Invalid('ID_INVALID') from None


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def parse(data, maximum=MAX_BYTES):
    require(type(data) is bytes and 0 < len(data) <= maximum, 'INPUT_BOUND_INVALID')
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'DUPLICATE_FIELD'); result[key] = value
        return result
    def invalid(_):
        raise Invalid('NUMBER_INVALID')
    try:
        text = data.decode('utf-8')
        depth = objects = nodes = 0; quoted = escaped = False
        for ch in text:
            if quoted:
                if escaped: escaped = False
                elif ch == '\\': escaped = True
                elif ch == '"': quoted = False
                continue
            if ch == '"': quoted = True
            elif ch in '{[':
                depth += 1; nodes += 1; objects += int(ch == '{')
                require(depth <= 12 and objects <= MAX_JSON_OBJECTS, 'JSON_SHAPE_BOUND_INVALID')
            elif ch in '}]': depth -= 1; require(depth >= 0, 'JSON_INVALID')
            elif ch in ',:': nodes += 1
            require(nodes <= MAX_JSON_NODES, 'JSON_SHAPE_BOUND_INVALID')
        require(depth == 0 and not quoted, 'JSON_INVALID')
        return json.loads(text, object_pairs_hook=pairs, parse_float=invalid, parse_constant=invalid)
    except Invalid: raise
    except (UnicodeError, json.JSONDecodeError, RecursionError, ValueError):
        raise Invalid('JSON_INVALID') from None


def validate_plan(plan):
    fields(plan, {'schemaVersion', 'kind', 'runId', 'identity', 'target', 'limits', 'schedule', 'ownedFixtureId', 'bootstrapOperation'})
    integer(plan['schemaVersion'], 2, 2)
    require(plan['kind'] == 'ORION_F5_INSTRUMENTED_WORKLOAD_PLAN')
    identifier(plan['runId']); identifier(plan['ownedFixtureId'])
    require(plan['bootstrapOperation'] in {'NONE','CORE_ONCE'}, 'BOOTSTRAP_SELECTION_INVALID')
    identity = plan['identity']; fields(identity, IDENTITY_KEYS)
    require(type(identity['sourceCommit']) is str and re.fullmatch('[0-9a-f]{40}', identity['sourceCommit']) is not None)
    for key in IDENTITY_KEYS - {'sourceCommit','modelSizeBytes'}:
        digest(identity[key], nullable=key in {'modelSha256', 'configurationSha256'})
    require((identity['modelSizeBytes'] is None) == (identity['modelSha256'] is None))
    if identity['modelSizeBytes'] is not None: integer(identity['modelSizeBytes'],1,4*1024**3)
    target = plan['target']; fields(target, {'deviceIdentitySha256', 'buildFingerprintSha256', 'apiLevel', 'abi', 'pageSizeBytes'})
    digest(target['deviceIdentitySha256']); digest(target['buildFingerprintSha256'])
    integer(target['apiLevel'], 31, 37); require(target['abi'] in {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'})
    require(target['pageSizeBytes'] in {4096, 16384})
    limits = plan['limits']
    fields(limits, {'durationMillis', 'transportTimeoutMillis', 'maximumObservationGapMillis',
                    'minimumAttemptIntervalMillis', 'resourceExitCooldownMillis', 'maximumResourceDeaths', 'maximumStartLatenessMillis'})
    for key, maximum in [('durationMillis', 7 * 86400000), ('transportTimeoutMillis', 180000),
                         ('maximumObservationGapMillis', 3600000), ('minimumAttemptIntervalMillis', 3600000),
                         ('resourceExitCooldownMillis', 86400000), ('maximumResourceDeaths', 16), ('maximumStartLatenessMillis', 3600000)]:
        integer(limits[key], 1, maximum)
    schedule = plan['schedule']; fields(schedule, {'workOffsetsMillis', 'observationOffsetsMillis'})
    for key, maximum in [('workOffsetsMillis', 256), ('observationOffsetsMillis', 3582)]:
        values = schedule[key]; require(type(values) is list and 1 <= len(values) <= maximum, 'SCHEDULE_INVALID')
        for value in values: integer(value, 0, limits['durationMillis'] - (1 if key == 'workOffsetsMillis' else 0))
        require(values == sorted(set(values)), 'SCHEDULE_INVALID')
    work = schedule['workOffsetsMillis']
    require(schedule['observationOffsetsMillis'][0] == 0, 'INITIAL_OBSERVATION_REQUIRED')
    require(all(b - a >= limits['minimumAttemptIntervalMillis'] for a, b in zip(work, work[1:])), 'SCHEDULE_CADENCE_INVALID')
    # OPEN pair, WORK pairs, observations/gaps, HALT/END and cleanup pair. No unbounded catch-up.
    budget = 8 + 3 * (len(work) + int(plan['bootstrapOperation'] == 'CORE_ONCE')) + 2 * len(schedule['observationOffsetsMillis'])
    require(budget <= MAX_ENTRIES, 'ENTRY_BUDGET_EXCEEDED')
    require(MAX_HEADER_BYTES + budget*MAX_EVENT_BYTES <= MAX_JOURNAL_BYTES, 'BYTE_BUDGET_EXCEEDED')
    require(MAX_HEADER_JSON_OBJECTS + budget*MAX_EVENT_JSON_OBJECTS <= MAX_JSON_OBJECTS and
            MAX_HEADER_JSON_NODES + budget*MAX_EVENT_JSON_NODES <= MAX_JSON_NODES, 'JSON_SHAPE_BUDGET_EXCEEDED')
    return plan


def stamp(value):
    fields(value, STAMP_KEYS)
    integer(value['bootCount'], 0, 2**31 - 1); integer(value['deviceElapsedMillis']); integer(value['mainPid'], 1, 2**31 - 1)
    integer(value['deviceElapsedUpperMillis'], value['deviceElapsedMillis'], min(MAX_INT, value['deviceElapsedMillis'] + 999))
    return value


def observation(value, resource, actual_stamp):
    fields(value, {'startedElapsedMillis','completedElapsedMillis','mainSampledElapsedMillis','aiSampledElapsedMillis',
                   'maximumSampleAgeMillis','bootCount','aiBootCount','mainNativeIncluded','aiNativeIncluded','nativeValidationCompleted'})
    integer(value['bootCount'], 0, 2**31 - 1)
    require(value['bootCount'] == actual_stamp['bootCount'])
    integer(value['startedElapsedMillis'])
    integer(value['completedElapsedMillis'], value['startedElapsedMillis'], actual_stamp['deviceElapsedUpperMillis'])
    integer(value['mainSampledElapsedMillis'], value['startedElapsedMillis'], value['completedElapsedMillis'])
    for key in ['mainNativeIncluded','aiNativeIncluded','nativeValidationCompleted']:
        require(value[key] is None or type(value[key]) is bool)
    if value['aiBootCount'] is not None: integer(value['aiBootCount'], 0, 2**31 - 1)
    if value['maximumSampleAgeMillis'] is not None: integer(value['maximumSampleAgeMillis'], 1, 1000)
    if value['aiSampledElapsedMillis'] is not None:
        integer(value['aiSampledElapsedMillis'], value['startedElapsedMillis'], value['completedElapsedMillis'])
    require(resource is not None and resource['mainPid'] == actual_stamp['mainPid'])
    return value


def complete_observation(value):
    resource, timing = value['resources'], value['observation']
    if resource is None or timing is None or any(resource[k] is None for k in RESOURCE_KEYS): return False
    if resource['serviceState'] == 'UNAVAILABLE' or resource['memoryPressure'] == 'UNAVAILABLE': return False
    age = timing['maximumSampleAgeMillis']
    return age is not None and timing['aiSampledElapsedMillis'] is not None and timing['aiBootCount'] == timing['bootCount'] and \
        timing['mainNativeIncluded'] is True and timing['aiNativeIncluded'] is True and \
        timing['completedElapsedMillis'] - timing['startedElapsedMillis'] < age and \
        value['stamp']['deviceElapsedUpperMillis'] - timing['mainSampledElapsedMillis'] < age and \
        value['stamp']['deviceElapsedUpperMillis'] - timing['aiSampledElapsedMillis'] < age


def incident_identity(value):
    fields(value, {'pid','timestampWallMillis','reason','classification','windowBootCount','correlation'})
    integer(value['pid'], 1, 2**31 - 1); integer(value['timestampWallMillis']); integer(value['reason'], 0, 2**31 - 1)
    integer(value['windowBootCount'], 0, 2**31 - 1)
    require(value['classification'] in {'MEMORY_LIMITER','LOW_MEMORY','EXCESSIVE_RESOURCE','UNCLASSIFIED'})
    require(value['correlation'] in {'PAIRED_LIVE_WINDOW','UNAVAILABLE'})
    # Classification/description availability changing cannot make an old incident new.
    return sha(encoded(dict(pid=value['pid'], processName='network.orion.qualification:ai',
                            timestampWallMillis=value['timestampWallMillis'], reason=value['reason'])))


def resources(value):
    # V2 preserves partial unknowns; it does not relabel v1's all-or-none resource object.
    fields(value, RESOURCE_KEYS)
    for key in ['mainPid','aiPid']:
        if value[key] is not None: integer(value[key], 1, 2**31 - 1)
    if value['mainPid'] is not None and value['aiPid'] is not None: require(value['mainPid'] != value['aiPid'])
    for key in ['mainPssBytes','aiPssBytes','aggregatePssBytes']:
        if value[key] is not None: integer(value[key], 1, 1024**4)
    if value['aggregatePssBytes'] is not None:
        require(value['mainPssBytes'] is not None and value['aiPssBytes'] is not None and
                value['aggregatePssBytes'] == value['mainPssBytes'] + value['aiPssBytes'])
    if value['thermalStatus'] is not None: integer(value['thermalStatus'], 0, 6)
    require(value['memoryPressure'] in {'NONE','MODERATE','CRITICAL','UNAVAILABLE'})
    require(value['serviceState'] in {'UNLOADED','LOADING','LOADED','INFERENCING','UNLOADING','UNAVAILABLE'})
    require(value['nativeRuntimeLoaded'] is None or type(value['nativeRuntimeLoaded']) is bool)
    require(value['serviceState'] not in {'LOADED','INFERENCING'} or value['nativeRuntimeLoaded'] is True)
    counts = [value['actionPendingCount'],value['actionUnknownCount']]
    require(all(v is None for v in counts) or all(type(v) is int and 0 <= v <= 1000000 for v in counts))
    return value


def checkpoint(value, operation, run_id, fixture_id, attempt_id=None, *, persisted=False):
    require(len(encoded(value)) <= MAX_CHECKPOINT_BYTES, 'CHECKPOINT_BOUND_INVALID')
    session_key = 'sessionIdSha256' if persisted else 'sessionId'
    fields(value, {'schemaVersion', 'kind', 'operation', 'runId', 'fixtureId', 'attemptId', session_key, 'stamp',
                   'outcome', 'refusal', 'failureCode', 'ownerCallInvoked', 'executionState', 'settlement', 'resources', 'observation', 'resourceExit', 'policyFacts', 'modelSha256',
                   'configurationSha256', 'runtimeSourceSha256', *FALSE_FLAGS})
    integer(value['schemaVersion'], 2, 2); require(value['kind'] == 'ORION_F5_INSTRUMENTED_CHECKPOINT')
    require(value['operation'] == operation and value['runId'] == run_id and value['fixtureId'] == fixture_id and
            value['attemptId'] == attempt_id, 'CHECKPOINT_SCOPE_MISMATCH')
    if persisted: digest(value[session_key])
    else: identifier(value[session_key])
    stamp(value['stamp'])
    allowed = {'OPEN': {'OPENED','REFUSED','BUSY','UNAVAILABLE'},
               'WORK': {'SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','PROCESS_DIED','REFUSED','BUSY','UNAVAILABLE'},
               'OBSERVE': {'OBSERVED','REFUSED','BUSY','UNAVAILABLE'},
               'CLEANUP': {'CLEANED','ABSENT','REFUSED','BUSY','UNAVAILABLE','FAILED'}}
    allowed['BOOTSTRAP'] = allowed['WORK']
    require(value['outcome'] in allowed[operation] and value['refusal'] in REFUSALS, 'CHECKPOINT_OUTCOME_INVALID')
    require(value['executionState'] in {'IDLE', 'ACTIVE', 'UNCERTAIN','DEAD_AWAITING_EVIDENCE'})
    require(value['failureCode'] in FAILURES and (value['failureCode'] is None or value['outcome'] == 'FAILED'))
    require(value['ownerCallInvoked'] is None or type(value['ownerCallInvoked']) is bool)
    require(operation in {'WORK','BOOTSTRAP'} or value['ownerCallInvoked'] is not True)
    require(value['settlement'] in {'ORIGINAL_UNLOAD_CONFIRMED','NO_OWNER_CALL','UNPROVEN','ORIGINAL_PROCESS_DEATH_CONFIRMED'})
    if value['settlement'] == 'NO_OWNER_CALL': require(value['ownerCallInvoked'] is False)
    if value['settlement'] == 'ORIGINAL_UNLOAD_CONFIRMED':
        require(value['ownerCallInvoked'] is True and value['executionState'] == 'IDLE' and
                value['outcome'] in {'SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','PROCESS_DIED'})
    if operation in {'WORK','BOOTSTRAP'} and value['executionState'] == 'IDLE':
        require(value['settlement'] != 'UNPROVEN', 'SETTLEMENT_UNPROVEN')
    if value['resources'] is not None: resources(value['resources'])
    if value['observation'] is not None: observation(value['observation'],value['resources'],value['stamp'])
    require(value['resources'] is None or value['observation'] is not None)
    for key in ['modelSha256', 'configurationSha256', 'runtimeSourceSha256']: digest(value[key], nullable=True)
    if value['resourceExit'] is not None: incident_identity(value['resourceExit'])
    # Additive #163 sidecar; old null fixtures remain valid and soak execution gates remain unchanged.
    import f5_runtime_qualification_sidecar as sidecar
    sidecar.validate(value)
    require(all(value[k] is False for k in FALSE_FLAGS), 'AUTHORITY_CLAIM_FORBIDDEN')
    if value['outcome'] == 'REFUSED': require(value['refusal'] != 'NONE')
    else: require(value['refusal'] == 'NONE')
    require(value['resourceExit'] is None or operation in {'WORK','BOOTSTRAP','OBSERVE'})
    return value


def validate_journal(plan, journal):
    validate_plan(plan)
    fields(journal, {'schemaVersion', 'kind', 'planSha256', 'hostTiming', 'entries'})
    integer(journal['schemaVersion'], 2, 2); require(journal['kind'] == 'ORION_F5_INVOCATION_JOURNAL')
    require(journal['planSha256'] == sha(encoded(plan)), 'PLAN_CONTENT_MISMATCH')
    meta = journal['hostTiming']; fields(meta, {'schemaVersion', 'clockSource', 'bootIdentitySha256', 'sessionId', 'evidence', 'qualification'})
    require(meta['schemaVersion'] == 1 and meta['clockSource'] == 'CLOCK_BOOTTIME' and meta['evidence'] == 'HOST_CLOCK_ONLY' and
            meta['qualification'] == 'NOT_QUALIFIED', 'HOST_CLOCK_INVALID')
    digest(meta['bootIdentitySha256']); identifier(meta['sessionId'])
    entries = journal['entries']; require(type(entries) is list and len(entries) <= MAX_ENTRIES, 'ENTRY_BOUND_INVALID')
    previous = -1; pending = None; session = None; used = set(); cleanup = False; ended = False; halted = False
    owner_stamp = None; first_complete = last_complete = None; gaps = 0; artifacts = set()
    bootstrap_seen = False; bootstrap_ready = plan['bootstrapOperation'] == 'NONE'; observed = False
    for index, entry in enumerate(entries):
        fields(entry, {'sequence', 'hostElapsedNanoseconds', 'kind', 'data'}); integer(entry['sequence'], index, index)
        require(len(encoded(entry)) <= MAX_EVENT_BYTES, 'EVENT_BYTE_BOUND_EXCEEDED')
        integer(entry['hostElapsedNanoseconds'], max(previous,0)); previous = entry['hostElapsedNanoseconds']
        require(not ended, 'ENTRY_AFTER_END'); data = entry['data']; kind = entry['kind']
        if kind in {'OPEN_INTENT', 'BOOTSTRAP_INTENT', 'WORK_INTENT', 'CLEANUP_INTENT'}:
            fields(data, {'attemptId', 'fixtureId', 'stamp', 'invocationFenceSha256'})
            require(pending is None, 'UNRESOLVED_INVOCATION'); require(data['fixtureId'] == plan['ownedFixtureId'])
            stamp(data['stamp']); digest(data['invocationFenceSha256'])
            if kind in {'WORK_INTENT','BOOTSTRAP_INTENT'}:
                identifier(data['attemptId']); require(session is not None and data['attemptId'] not in used and not cleanup and not halted)
                if kind == 'BOOTSTRAP_INTENT':
                    require(plan['bootstrapOperation'] == 'CORE_ONCE' and not bootstrap_seen and not observed and not used, 'BOOTSTRAP_ORDER_INVALID')
                    bootstrap_seen = True
                else: require(bootstrap_ready, 'BOOTSTRAP_REQUIRED')
                used.add(data['attemptId'])
                require(len(used) <= len(plan['schedule']['workOffsetsMillis']) + int(bootstrap_seen), 'ATTEMPT_BOUND_EXCEEDED')
            else: require(data['attemptId'] is None)
            require(kind != 'OPEN_INTENT' or index == 0)
            if kind == 'CLEANUP_INTENT': require(session is not None and not cleanup); cleanup = True
            pending = entry
        elif kind in {'OPEN_RESULT', 'BOOTSTRAP_RESULT', 'WORK_RESULT', 'CLEANUP_RESULT'}:
            require(pending is not None and pending['kind'] == kind.replace('RESULT', 'INTENT'), 'INTENT_MISSING')
            operation = kind.split('_')[0]
            checkpoint(data, operation, plan['runId'], plan['ownedFixtureId'], pending['data']['attemptId'], persisted=True)
            require(data['stamp']['bootCount'] == pending['data']['stamp']['bootCount'] and
                    data['stamp']['mainPid'] == pending['data']['stamp']['mainPid'] and
                    data['stamp']['deviceElapsedMillis'] >= pending['data']['stamp']['deviceElapsedMillis'], 'OWNER_CHANGED')
            if kind == 'OPEN_RESULT':
                require(data['outcome'] in {'OPENED','REFUSED','UNAVAILABLE','BUSY'})
                if data['outcome'] == 'OPENED': session = data['sessionIdSha256']
            else: require(data['sessionIdSha256'] == session, 'OWNER_CHANGED')
            if kind == 'BOOTSTRAP_RESULT':
                bootstrap_ready = data['outcome'] == 'SUCCEEDED' and data['executionState'] == 'IDLE' and data['settlement'] == 'ORIGINAL_UNLOAD_CONFIRMED'
            pending = None
        elif kind == 'OBSERVATION':
            require(bootstrap_ready, 'BOOTSTRAP_REQUIRED'); observed = True
            checkpoint(data, 'OBSERVE', plan['runId'], plan['ownedFixtureId'], persisted=True)
            require(session is not None and data['sessionIdSha256'] == session, 'OWNER_CHANGED')
            if complete_observation(data):
                timing = data['observation']
                if first_complete is None: first_complete = timing['startedElapsedMillis']
                if last_complete is not None:
                    require(timing['completedElapsedMillis'] >= last_complete)
                    if timing['startedElapsedMillis']-last_complete > plan['limits']['maximumObservationGapMillis']: gaps += 1
                last_complete = timing['completedElapsedMillis']
            else: gaps += 1
        elif kind == 'RUNTIME_ARTIFACTS':
            fields(data, {'attemptId','runtimeSha256','statusSha256','reportSha256'})
            require(pending is not None and pending['kind'] in {'WORK_INTENT','BOOTSTRAP_INTENT'} and
                    data['attemptId'] == pending['data']['attemptId'], 'INTENT_MISSING')
            require(data['attemptId'] not in artifacts, 'DUPLICATE_ARTIFACTS'); artifacts.add(data['attemptId'])
            digest(data['runtimeSha256'],nullable=True); digest(data['statusSha256']); digest(data['reportSha256'])
        elif kind == 'SELECTION_REFUSED':
            fields(data, {'attemptId','reason','stamp'})
            identifier(data['attemptId']); stamp(data['stamp'])
            require(data['reason'] in {'RESOURCE_COOLDOWN','RESOURCE_LIMIT','CADENCE','BUSY'})
            require(data['attemptId'] not in used and not halted and session is not None); used.add(data['attemptId'])
            require(len(used) <= len(plan['schedule']['workOffsetsMillis']) + int(bootstrap_seen), 'ATTEMPT_BOUND_EXCEEDED')
        elif kind == 'OBSERVATION_GAP':
            fields(data, {'reason', 'stamp'}); require(data['reason'] in GAPS)
            if data['stamp'] is not None: stamp(data['stamp'])
            gaps += 1
        elif kind in {'HALT', 'RUN_END'}:
            fields(data, {'reason', 'unknown'}); require(type(data['unknown']) is bool)
            require(data['reason'] in {'COMPLETED', 'SHORT_INCOMPLETE', 'REFUSED', 'WINDOW_ENDED', 'OBSERVATION_STALE', 'RESOURCE_COOLDOWN',
                'RESOURCE_LIMIT', 'IDENTITY_CHANGED', 'CLOCK_DISCONTINUOUS', 'TRANSPORT', 'INTERRUPTED', 'STORAGE_UNAVAILABLE', 'CLEANUP_REFUSED'})
            if kind == 'HALT': halted = True
            if kind == 'RUN_END': require(pending is None); ended = True
        else: raise Invalid('EVENT_KIND_INVALID')
        current = data.get('stamp')
        if current is not None:
            if owner_stamp is None: owner_stamp = current
            require(current['bootCount'] == owner_stamp['bootCount'] and current['mainPid'] == owner_stamp['mainPid'], 'OWNER_CHANGED')
    span = None if first_complete is None else last_complete-first_complete
    return {'pending': pending is not None, 'entries': len(entries), 'sessionIdSha256': session, 'ended': ended,
            'reportedSourceSpanMillis':span, 'windowCoveredByCheckpoints':span is not None and span >= plan['limits']['durationMillis'] and gaps == 0}


def journal_checkpoint(value):
    """Raw MAIN nonce stays in RAM/transport, never retained in the technical journal."""
    result = dict(value)
    nonce = result.pop('sessionId')
    result['sessionIdSha256'] = sha(nonce.encode('ascii'))
    return result
