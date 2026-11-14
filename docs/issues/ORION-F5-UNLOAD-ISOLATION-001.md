# ORION-F5-UNLOAD-ISOLATION-001 — Isolate optional unload sampling

Tracking: [#118](https://github.com/otavio-esteves/O.R.I.O.N/issues/118).

OBJECTIVE: Prevent optional unload clock sampling from holding an execution turn,
operation, cleanup or caller return. Retain conservatively validated observations
of the existing UNLOADED response.

FILES ALLOWED: OrionCortexClient.kt; related unload observation types and bounded
MAIN-owned diagnostic dispatcher; focused app JVM/Android tests; this package and
F5-UnloadObservation.md. No IPC/native/policy/profile/persistence changes.

CONTRACTS INVOLVED: Architecture §§22/32/33; Master Plan §11.11; existing one-turn
ownership and non-cancellable cleanup; OrionClock/BootSessionId and unload evidence.

INVARIANTS INVOLVED: INV-011/013/027/028/033/036.

TESTS REQUIRED: Queued request and outer caller timeout under a deliberately
blocked diagnostic clock; one global in-flight ticket including hung/closed
clients; no-wait/drop/failure accounting; immutable records; actual execution and
endpoint changes while pending; replay/no phantom queued cleanup; existing suite.
Root owns canonical Docker JVM/build/lint and Android fixture execution. Author
runs no Gradle, Docker or ADB.

ACCEPTANCE CRITERIA: No additional clock/provider call in execution/cleanup/return;
no new Cortex RPC. One MAIN-only worker and one global in-flight slot, zero queue
or replacement worker; caller never awaits capture. Busy sampling drops honestly.
No stale confirmation after endpoint/actual execution changes or closure. Exact
original RPC cadence, deadlines, exceptions, terminality and policy preserved;
default disabled path unchanged. Required CI precedes merge.

OUT OF SCOPE: Load/stream hooks, general executors, IPC metrics, native-release
proof, heavy producer, profile/admission/config changes, physical/F5/F6 approval.

DESIGN: A fixed singleton owns a typed final ticket, created only after MAIN role
validation. Submission is CAS plus wake-up, never a callback. A short numeric
owner guard records connection/execution generations and closure; it holds no
Binder/client and executes no clock/IO/user code. The single worker samples time
outside functional ownership, then checks the current guard while publishing to
the existing bounded buffer. Stuck sampling keeps the global slot occupied; new
or closed clients do not create another worker. Counter overflow/contention is
reported conservatively. Publication remains technical, ephemeral and untrusted.

AUTHOR HANDOFF: Regression-first commit supplies the queued/outer-timeout cases
before implementation. The fix supplies nine focused JVM dispatcher/guard cases
and two additional Android integration cases (client total32), preserves the ten
existing pure classifier/buffer cases, and adapts prior evidence assertions for
asynchronous delivery. Canonical execution remains root-owned and pending; no
build/runtime/physical result is claimed here. Static source/whitespace review
checks no new Cortex RPC or synchronous diagnostic Clock call in cleanup.

RECOVERY/SECURITY/MIGRATION: Ephemeral MAIN-only diagnostics; no durable schema,
replay, recovery or authority changes. Numeric generations are local to this
instance/process, can repeat after MAIN death, and are not authentication. No
content is retained. Provider hangs consume the one slot indefinitely; busy/drop
counters state missing coverage. No heavy/profile/physical/soak gate is closed.

GUARD FAILURE HARDENING: Owner construction and all optional connection/endpoint/
execution version boxing and stamp allocation are contained independently of
turn/bind/cancel/close/release work. A failed update first sets a permanent
primitive volatile disabled flag without allocation, clock, RPC or guard lock;
accounting is best-effort and cannot escape. Pending or later tickets can publish only
OBSERVATION_UNAVAILABLE instead of validating stale versions; allocation/recording
failure can still drop a record with the existing conservative loss accounting. Initialization
failure leaves owner/dispatcher absent. A ninth dispatcher JVM case blocks the
real shared worker clock, disables the owner, proves updates/new tickets cannot
revive confirmation, and verifies slot release plus a healthy different owner.
No real OOM, injected allocator/callback or author build/device run is used.
