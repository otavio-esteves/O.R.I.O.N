# ORION-F3-INGRESS-001 — Isolate deterministic domain queue consumers

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/45>.

OBJECTIVE: Allow task, reminder and timer consumers to share durable ingress
storage without consuming or rejecting requests belonging to another domain.

FILES ALLOWED: OrionIngressDao, OrionDurableIngressStore, existing task kernel
constructor wiring, focused DurableIngressStoreTest regressions and this package.

CONTRACTS INVOLVED: DurableIngressQueue, IngressCoordinator, StartupBarrier,
encrypted envelope integrity and atomic local-consumer transaction. A trusted
optional consumer scope selects only matching queued rows; existing unscoped
storage callers retain their behavior. Scoped stores admit only their own scope.
Scopes route work, never grant authority. Each consumer still validates source,
schema, policy, presence and confirmation under the operational barrier.

INVARIANTS INVOLVED: INV-007/008/014/019/023/035. No authority boundary or durable
payload semantics change, no migration and no ADR required.

TESTS REQUIRED: Interleaved scopes, priorities and expiry; no cross-domain claim,
attempt/version/status mutation; wrong-scope submission rejection; scoped consumer
failure rolls back its own writes without touching foreign rows. Existing
unscoped queue regressions remain applicable. Canonical Docker build/unit/lint/
dependency checks and host/emulator instrumentation when available.

ACCEPTANCE CRITERIA: Each scoped consumer sees only its domain, including when a
foreign row has higher priority or has expired. Shared barrier gating, encrypted
payload verification and atomic state/action/ingress writes remain unchanged.

OUT OF SCOPE: New Skills, schema, scheduler/F4, external effects, AI and voice.
Process death still rolls back the consumer and ingress claim together. No new
permissions, exported surfaces or content logging; no physical qualification.

VALIDATION: Canonical Docker focused checks passed in 4m35s, with 27 JVM cases,
zero failures, both requested lint gates, debug/test APK assembly and all 36
module-boundary assertions. Host/ANDROID_EMULATOR API35/x86_64 instrumentation
passed all 10 DurableIngressStoreTest cases, including three new isolation
regressions. Independent read-only review found no blockers. Source and APK
hashes: [evidence](../qualification/f3-ingress-scopes-evidence.json). Physical
qualification remains deferred in #23. Required CI must pass before merge.
