# ORION-F4-QUAL-002 — Remaining physical matrix and soak protocol

Parent: #64. Prerequisite runtime fixes: PR #66. Passive observations: PR #67.

OBJECTIVE: Complete the remaining F4 physical evidence and prepare a reproducible
soak protocol. The currently available S21 window is only a few hours; a short run
must never be promoted to the required real 24-hour soak.

FILES ALLOWED: focused app/src/androidTest qualification probes; new host qualification
and soak recorders plus their contracts; qualification/compatibility documentation.
Production runtime, schema, process topology, policy and confirmation semantics are
outside this package unless a reproduced in-scope defect needs a separate fix.

CONTRACTS INVOLVED: Architecture V3.2 scheduler/recovery, §118/119/120; Master Plan
§10.11 and §31; ADR-0001; exact confirmation and boot-bound execution ownership.

INVARIANTS INVOLVED: INV-001/004/005/006/007/008/011/014/015/016/018/019/021/022/
023/029/030/032/033/035/036.

TESTS REQUIRED: Real permission denial then grant without changed consent; exact
access loss with persisted approximate fallback then grant; actual battery saver
delivery; bounded disposable SQLite SQLITE_FULL/rollback and retention/resource
tests; synthetic task/timer/reminder persistence and scoped queue/UNKNOWN observations
during soak. Existing runtime regression tests remain mandatory.

ACCEPTANCE CRITERIA: Matching APK/source identities and explicit execution environments;
real NotificationManager evidence before verification; no false FIRED/attempt while
permission is denied; preserved original bindings; no unrelated data cleanup or
whole-device storage filling; all temporary system settings restored. CI must pass
before merge. F4 remains open while required evidence or the full soak is missing.

OUT OF SCOPE: AI/voice, production diagnostics APIs, exported components, policy or
Doze exemptions, fabricated consent, changing release API support, replacing physical
evidence with emulator results, automatic release tagging or administrator bypass.
