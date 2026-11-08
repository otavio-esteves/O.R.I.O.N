# ORION-F4-QUAL-003 — Honor queued original consent in soak preparation

OBJECTIVE: Reproduce valid QUEUED confirmation with a bounded ingress backlog and
make the explicit soak preparation wait for that original action's committed
success without submitting another confirmation. Preserve failed physical evidence.

FILES ALLOWED: app/src/androidTest/** soak preparation/helper and focused task
kernel regression tests; related qualification documentation.

CONTRACTS INVOLVED: TaskGateway/TimerGateway QUEUED, bounded durable ingress drain,
Startup Barrier, exact confirmation binding and expiry, owned execution.

INVARIANTS INVOLVED: INV-004/007/008/018/019/021/022/023/032/035.

TESTS REQUIRED: Deterministic backlog returns QUEUED despite an open barrier;
single original confirmation then bounded explicit processing commits once with
the same binding/timestamps. Expired/blocked/failed consent never receives a new
confirmation or a passing result. Docker build/checks; emulator instrumentation
is API evidence only and never substitutes for the physical soak.

ACCEPTANCE CRITERIA: Soak preparation accepts only APPLIED/QUEUED submissions,
requires actual committed SUCCEEDED and one attempt, compares original hashes and
fingerprint, and times out on missing progress. No production API, policy, schema,
retry, process topology or authority changes. The specific earlier physical
QUEUED cause remains unproven unless separately reproduced from that execution.

OUT OF SCOPE: Changing bounded ingress semantics, reconfirming automatically,
reproducing a full physical 24-hour run, changing qualification gates or starting F5.
