"""Manual semantic receipt on the SAME fixed qualification runner/ADB/ASM boundaries."""
import f5_instrumented_workload_adapters as A
import f5_instrumented_workload_protocol as P
import f5_manual_core_procedure_protocol as M


def receipt(output,operation,run,fixture,session=None,attempt=None):
    P.require(operation in {'OPEN','WORK','OBSERVE','CLEANUP'})
    P.require(type(output) is str and 0<len(output.encode('utf-8'))<=A.MAX_OUTPUT,'OUTPUT_BOUND_INVALID')
    results={};code=None
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_RESULT: '):
            P.require(code is None,'RESULT_AFTER_TERMINAL')
            key,separator,value=line[24:].partition('=');P.require(separator and key not in results,'RESULT_FRAMING_INVALID')
            results[key]=value
        elif line.startswith('INSTRUMENTATION_CODE: '):
            P.require(code is None and line=='INSTRUMENTATION_CODE: -1','RESULT_FRAMING_INVALID');code=-1
        else:P.require(line=='','SURPLUS_OUTPUT')
    P.require(code==-1 and set(results) in ({A.CHECKPOINT,'stream'},{A.CHECKPOINT,'stream','orionRuntimeCaptureStatus'},
        {A.CHECKPOINT,'stream','orionRuntimeCaptureStatus','orionRuntimeCaptureSource'}),'RESULT_FRAMING_INVALID')
    P.require(results['stream']=='ORION_WORKLOAD_CHECKPOINT')
    value=P.parse(results[A.CHECKPOINT].encode('utf-8'),M.MAX_CHECKPOINT_BYTES)
    M.checkpoint(value,operation,run,fixture,attempt)
    if session is not None:P.require(value['sessionId']==session,'OWNER_CHANGED')
    source=results.get('orionRuntimeCaptureSource');status=results.get('orionRuntimeCaptureStatus')
    P.require((source is not None)==(value['runtimeSourceSha256'] is not None),'SOURCE_REFERENCE_INVALID')
    if source is not None:
        P.require(source.isascii() and len(source)<=96*1024 and P.sha(source.encode('ascii'))==value['runtimeSourceSha256'],
            'SOURCE_REFERENCE_INVALID')
    P.require(operation=='WORK' or source is None and status is None)
    P.require(operation!='WORK' or value['ownerCallInvoked'] is not True or status is not None,'ORIGINAL_PROGRAM_FRAME_MISSING')
    return value,source,status


class ManualProducer:
    def __init__(self,device):M.validate_plan(device.plan);self.device=device;self.session=None
    def stamp(self):return self.device.stamp()
    def revalidate(self):return self.device.revalidate()

    def call(self,operation,attempt_id=None,*,invocation_fence):
        P.require(operation in {'OPEN','WORK','OBSERVE','CLEANUP'})
        P.require(callable(invocation_fence),'HOST_INVOCATION_FENCE_REQUIRED')
        plan=self.device.plan;before=self.device.revalidate();total=self.device.total_memory()
        # Last HOST time/ownership fence follows potentially slow read-only preflight, immediately before transport.
        # It grants no Core admission: the original Main Owner still performs every policy/governor check.
        invocation_fence()
        output=self.device.adb(*A.instrumentation_command(operation,plan['runId'],plan['ownedFixtureId'],self.session,attempt_id))
        value,source,status=receipt(output,operation,plan['runId'],plan['ownedFixtureId'],self.session,attempt_id)
        after_window=self.device.stamp();P.require(self.device.total_memory()==total,'TOTAL_MEMORY_CHANGED')
        after=self.device.revalidate();stamp=value['stamp']
        P.require(stamp['bootCount']==before['bootCount']==after['bootCount'] and
            stamp['mainPid']==before['mainPid']==after['mainPid'] and
            before['deviceElapsedMillis']<=stamp['deviceElapsedMillis']<=after['deviceElapsedUpperMillis'] and
            stamp['deviceElapsedMillis']==stamp['deviceElapsedUpperMillis'],'OWNER_CHANGED')
        no_selection=value['outcome']=='REFUSED' and value['ownerCallInvoked'] is False and value['modelSha256'] is None and \
            value['configurationSha256'] is None and source is None
        identity=plan['identity']
        P.require(no_selection or value['modelSha256']==identity['modelSha256'] and
            value['configurationSha256']==identity['configurationSha256'],'CONFIGURATION_CHANGED')
        artifacts=None
        if status is not None:
            import f5_runtime_source_assembler as assembler
            expected=None if identity['modelSha256'] is None else dict(sourceCommit=identity['sourceCommit'],
                sourceManifestSha256=identity['sourceManifestSha256'],appPackage=A.PACKAGE,testPackage=A.TEST_PACKAGE,
                appApkSha256=identity['appSha256'],testApkSha256=identity['testSha256'],
                modelSha256=identity['modelSha256'],modelSizeBytes=identity['modelSizeBytes'])
            host=dict(declaredEnvironment='PHYSICAL_DEVICE',bootCount=before['bootCount'],bootIdSha256=P.sha(self.device._stamp[0].encode('ascii')),
                startedElapsed=before['deviceElapsedMillis'],completedElapsed=after_window['deviceElapsedUpperMillis'],api=plan['target']['apiLevel'],
                abi=plan['target']['abi'],pageSizeBytes=plan['target']['pageSizeBytes'],deviceBuildSha256=plan['target']['buildFingerprintSha256'],
                totalMemoryBytes=total)
            assembly=assembler.assemble_json(source.encode('ascii') if source is not None else None,status.encode('ascii'),
                P.encoded(expected) if expected is not None else None,P.encoded(host))
            header=P.parse(assembly.status_json,1024)
            P.require(header['ownerCallInvoked'] is None or value['ownerCallInvoked'] is None or
                header['ownerCallInvoked']==value['ownerCallInvoked'],'OWNER_CALL_MISMATCH')
            artifacts=A.RuntimeArtifacts(assembly.runtime_json,assembly.status_json,assembly.report_json)
        if operation=='OPEN':self.session=value['sessionId']
        return value,artifacts
