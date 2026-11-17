"""Manual semantic format on the existing pinned InvocationJournal filesystem/publisher."""
import copy
import os

import f5_instrumented_workload_journal as J
import f5_instrumented_workload_protocol as P
import f5_manual_core_procedure_protocol as M
from qualification_host_clock import HostClock


class ManualInvocationJournal(J.InvocationJournal):
    """New-only concrete store. No executable state is reconstructed by inspection."""
    def __init__(self,path,plan):
        M.validate_plan(plan);self._plan=copy.deepcopy(plan)
        self._clock=HostClock.linux();self._previous=None;self._metadata=dict(self._clock.metadata);self._clock_value()
        try:
            J.base.JournalStore.__init__(self,path,create=True)
            self._initialize_manual()
        except BaseException:
            if hasattr(self,'chain'):
                try:self.close()
                except BaseException:pass
            raise

    def _initialize_manual(self):
        self._check_chain();P.require(self.created and not self._names(),'EXCLUSIVE_NEW_STORE_REQUIRED')
        journal=dict(schemaVersion=2,kind=M.JOURNAL_KIND,planSha256=P.sha(P.encoded(self._plan)),
            hostTiming=self._metadata,reservation=M.reservation(self._plan),entries=[])
        state=dict(schemaVersion=2,kind=M.STORE_KIND,journal=journal);M.validate_journal(self._plan,journal)
        self._publish(J.base.PLAN,P.encoded(self._plan),initial=True)
        self.digest=self._publish(J.base.STATE,P.encoded(state),initial=True);self.entries=0
        self.initial_ack=self._ack('INITIALIZED_DURABLE')

    def _snapshot(self):
        self._check_chain();temporary=self._inventory()
        plan_bytes=self._read(J.base.PLAN,P.MAX_BYTES);plan=P.parse(plan_bytes)
        M.validate_plan(plan);P.require(plan_bytes==P.encoded(plan))
        data=self._read(J.base.STATE,P.MAX_STORE_BYTES);state=P.parse(data,P.MAX_STORE_BYTES)
        P.fields(state,{'schemaVersion','kind','journal'});P.integer(state['schemaVersion'],2,2)
        P.require(state['kind']==M.STORE_KIND)
        validation=M.validate_journal(plan,state['journal']);referenced=set()
        for event in state['journal']['entries']:
            if event['kind']!='RUNTIME_ARTIFACTS':continue
            item=event['data']
            for prefix,key,limit in [('runtime-','runtimeSha256',4*1024*1024),('status-','statusSha256',1024),('assembly-','reportSha256',4096)]:
                if item[key] is None:continue
                name=prefix+item['attemptId']+'.json';referenced.add(name)
                P.require(P.sha(self._read(name,limit))==item[key],'ARTIFACT_CONTENT_CHANGED')
        retained={name for name in self._names() if name.startswith(('runtime-','status-','assembly-'))}
        validation['unreferencedArtifactFiles']=len(retained-referenced)
        P.require(data==P.encoded(state));self._check_chain()
        return plan,state,P.sha(data),validation,temporary

    def append(self,kind,data,expected):
        P.require(os.getpid()==self.owner_pid,'STORE_OWNER_CHANGED')
        with self._mutex:
            self._check_chain();P.require(not self.inspection_required,'INSPECTION_REQUIRED')
            plan,state,current,_,_=self._snapshot();P.require(current==expected==self.digest,'CAS_CONFLICT')
            next_state=copy.deepcopy(state);entries=next_state['journal']['entries']
            entries.append(dict(sequence=len(entries),hostElapsedNanoseconds=self._clock_value(),kind=kind,data=copy.deepcopy(data)))
            M.validate_journal(plan,next_state['journal']);P.require(self._snapshot()[2]==current,'CONCURRENT_CONTENT_CHANGE')
            self.digest=self._publish(J.base.STATE,P.encoded(next_state));self.entries=len(entries)
            return self._ack('APPENDED_DURABLE',current)

    def inspect(self):
        result=super().inspect();result['kind']='ORION_F5_MANUAL_CORE_PROCEDURE_INSPECTION';return result


def inspect_existing(path):
    reader=ManualInvocationJournal.__new__(ManualInvocationJournal)
    try:
        J.base.JournalStore.__init__(reader,path,create=False);return reader.inspect()
    finally:
        if hasattr(reader,'chain'):reader.close()
