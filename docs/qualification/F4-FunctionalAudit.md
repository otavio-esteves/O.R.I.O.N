# F4 functional exit audit

Work package: [ORION-F4-AUDIT-001](../issues/ORION-F4-AUDIT-001.md).
Audited tree: `3f8774384edbad9c1e8831d75c3099c24943fcc4`.

Decision: **the deterministic functional exit in Master Plan §10.11 passes**.
F5 may begin with its resource-governor prerequisite. This decision does not enable
Cortex, approve continuous operation, close the native release matrix or authorize
a stable release/readiness promotion.

The earlier closeout conflated functional phase exit with subsequent stability
qualification. Master Plan §2 requires the declared exit gate; §10.11 enumerates
15 functional checks. §31 schedules the 24-hour workload **after MVP Core**.
Architecture §118 places soak qualification before stability; §33 requires a
functional minimum governor before the first Cortex. Master Plan §6.7 permits
native 16 KiB risk to remain formally recorded; §32 still requires native/recovery/
performance approval before a release candidate. These obligations remain open.
No baseline clause or invariant is waived or edited by this clarification.

| §10.11 criterion | Evidence reviewed |
| --- | --- |
| Tasks persist | Physical S21 application/transaction-death suites; current regression build |
| Reminders persist | Physical S21 persistence/generation and actual notification records |
| Timers work | Physical timer and preserved task/timer checkpoint in the short workload |
| Reboot recovers | Recorded physical boot-count increase, autonomous notification before verification |
| Process death recovers | Recorded physical SIGKILL/new-PID delivery and transactional death scenarios |
| Event Outbox recovers | Physical receipt/rollback/idempotent delivery/backoff/dead-letter cases |
| Action Outbox recovers | Physical committed claim/ownership/death and recovery cases |
| UNKNOWN never blindly retries | Physical ambiguous-claim/manual-review/upgrade cases |
| IngressQueue recovers | Encrypted durable receipts, deduplication and transaction-death evidence |
| Startup Barrier works | Shared barrier/admission and blocked-invocation regressions |
| Exact alarm fallback | Actual physical approximate registration and exact-access loss/gain |
| Time/timezone reconcile | Physical notification delivery with original instant/binding |
| Upgrade preserves state | Recorded installed 4→5 upgrade; original keys/ciphertext/queues retained |
| Health observable | Physical capability/restriction diagnostics; subsequent read-only diagnostics tests |
| Integral deterministic use without AI | Physical task/reminder/timer/memory/device/notification workflows; Cortex absent |

The exact recorded device/artifact scope remains in the linked
[physical closeout](f4-closeout-s21-api35-20261107.json),
[physical runtime fixes](f4-s21-fixes-physical-api35-20261107.json) and
[current regression](f4-ui-recreation-evidence.json). Historical hardware evidence
is not relabelled as a fresh test of every later APK. Business authority/recovery
contracts were preserved by subsequent instrumentation and diagnostic work.

Fresh audit checks passed: canonical Docker check/build/lint/architecture/native
packaging (4,111 actionable tasks) and 125 host contracts. The recorded 325-case API37
suite remains scoped emulator evidence, not a newly executed physical suite. Three
physical evidence archives were checked against their committed SHA256 and ZIP CRCs.
Current-source differences are retained: the earlier diagnostics record predates
qualification build configuration and does not hash the current Gradle file.

## Entry discipline

The current `UnavailableResourceGovernor` safely rejects all loading/inference.
It is not a calibrated inference policy. The first F5 work package must implement
and test a deterministic, fail-closed minimum policy using fresh resource/exit
signals. Cortex service/JNI/model loading/inference remain disabled until that
policy is functional, wired to qualified inputs and consulted before each request.
Model/performance/API/native qualification remains independently required for its
actual scope. No old report's `fullF4GateClosed=false` or incomplete outcome is
rewritten: those records describe the original attempts and wider qualification.

Remaining: real post-MVP soak and analysis (battery/thermal/crash/ANR/backlog/growth),
complete native/release/API matrices and approved inference profiles. No S21 window
or 24-hour device allocation was used by this audit.

[Audit identities and verification results](f4-functional-audit-20261108.json).
