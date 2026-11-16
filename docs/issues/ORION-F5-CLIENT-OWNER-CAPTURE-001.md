# ORION-F5-CLIENT-OWNER-CAPTURE-001 — genuine internal Client/Owner checkpoints

Tracking: #148. Base: 1d9d7a720ac0d37fa0978734760725423987a991.

OBJECTIVE: Join genuine existing Owner/Client checkpoints with sources144/146
without enabling production composition or changing original execution semantics.

FILES ALLOWED: New final internal app/ai capture session/frame/receipt types;
narrow Client/Owner hooks; minimum recorder/DTO historical-import evolution;
focused JVM/Android tests and this note. No source146/core-time, factory, profiles,
IPC/formatters/native/persistence or other worktree edits.

CONTRACTS: RuntimeCaptureContract141, bytes140, foundation144, lifecycle146,
original Owner guards, ledger, turnstile and cleanup. INV-009/011/012/013/014/019/
027/028/032/033/035/036. ADR0001/0002/0003 unchanged.

TESTS REQUIRED: Absent/present/faulted equivalence of original clock, validation,
encoding/RPC order, status/Throwable/callback/List identity; actual owner versus
replay/join/queued expiry/turn; independence from old collectors; identity/epoch/
turn/PID/boot/model/death/close/exhaustion fences; genuine load/infer/unload points;
manual historical import, original times and status, canonical final, loss/bounds.

ACCEPTANCE: Final closed types, capture null by default, one shared observation
clock/source per session. Only source146 binds in the true winning Client runOnce
before the already-existing initial status call. No extra original Clock, status,
content validation/encoding or RPC. Technical model copy follows original READY/
profile/openReadOnly checks; no request/model entity/content/path/raw error retained.
Stamps publish under original already-owned monitors and read without waiting.
Unknown identity remains unresolved until an existing validated original witness;
invalidated/exhausted capture cannot recover. Missing proof never triggers an RPC.
One explicit manual import uses exact source/scope, its own historical timelines,
original status/time/sequence/loss and independent terminal storage, never retiming.

OUT OF SCOPE: Sampler/delivery consumer/encoder/HOST/controller/Android build-mode
composition, factory activation, operational choices, physical/API/limiter/soak
execution or F4/F5/F6 approval. AFTER_TERMINAL scalar accessor remains a future
source146/sampler dependency. No architecture waiver is claimed.

IMPLEMENTATION: Existing run/runObserved keep capture absent. Explicit internal
begin/runCaptured/read methods use the same Owner/Client, guards and callback.
Minimal SHA/size/logical/deadline data are copied only after the original READY,
profile and read-only open succeed; no model entity/descriptor/request is retained.
Only the winning Client runOnce activates source146 before its original initial
status read. Constructor/replay/join sources never bind or attach retained facts.

Independent primitive connection/turn generations and Binder references are
maintained at existing Client monitor sections. Optional immutable stamps publish
only there and are read through volatile fields; source operations use tryLock.
PID/boot association consumes the typed result of the original existing telemetry
validation, inside its original resource evaluation. It adds no health/state call
or original Clock/content guard. Missing proof remains UNRESOLVED until an original
validated witness arrives; missing final identity is unavailable, never a guessed
PID or a reason to bind/query. A future sampler needs its own separately reviewed
read-only diagnostic port and capture-only bootstrap; it must not reuse
currentMemoryTelemetry, whose original failure behavior calls lostConnection.

LOAD_TRUE_DISPATCH has its own adjacent observation-clock point. The older original
started instant is explicitly LOAD_LOCAL_REQUEST_START, published after the original
ACCEPTED reply; ACCEPTED is retained separately as proof, not substituted as entry
time. Confirmed load terminal, inference dispatch and unload dispatch/completion
are captured at their actual original branches/calls. Load false/ambiguous paths
stay unavailable. The existing exact UNLOADED-key check is shared with the new
opt-in source as technical proof; no functional wire validator or polling call is
repeated. Completion uses a real post-reply observation against the original cleanup
deadline. Void return, pre-RPC poll and late/unknown replies never prove cleanup.

The original return/throw publishes scalar runReturned state only. It does not close
the capture, read a Clock, snapshot, import, wait for a sampler or extend the Core
turn. Explicit manual read later obtains one end point, imports the exact source146
once, and copies bounded immutable receipts. A later turn/endpoint/close invalidates
new capture and is checked before/after manual work; Core is never held for evidence.
The historical import keeps source times/status/sequence/loss, canonical final time
and a reserved terminal independently of later unload publication high-water.
Missing terminal/time is not reconstructed from the return or final metadata.

LIMITS / RECOVERY / PRIVACY: The existing 4097 chunks/128 memory/32 operation bounds
remain; unresolved operations have a separate at-most32 staging bound. Only one
exact session/source is imported, without an unbounded request map. No sampler,
AFTER_TERMINAL accessor, delivery consumer, encoder or transport is supplied.
Process death discards ephemeral data; capture never cancels/unloads/unbinds/closes
the shared runtime or changes retry/recovery policy. No durable schema/migration,
permissions, manifest, IPC, calibration, factory or operational values change.
All authentication/physical/profile/inference/F5/soak/F6 flags remain false.

VALIDATION: Root canonical Docker checks passed in 4m 47s on source
`c4687b620b692fbef910fdddb9e826ae52e030fd`:
`:ai:api:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
:app:assembleDebugAndroidTest verifyModuleBoundaries testModuleBoundaries
:ai:api:lintDebug :app:lintDebug --max-workers=2`.
The selected XML reports contain 316 JVM cases in 39 suites (75 ai/api, 241 app),
zero failures/errors/skips, including twelve new JVM cases.

Actual Android framework execution passed all 76 mechanical cases, with only
START/OK statuses and no ignores/assumption failures: 32 Client, 9 OwnerDefault,
1 queued deadline, 10 cycle, 11 new capture Client and 13 generation-metrics Client.
Twelve Android cases are new (eleven capture Client, one Owner default). This ran
on an isolated API35/default/x86_64 emulator with 4096-byte pages, four vCPUs and
3072 MiB. Built and pulled installed app/test APK SHA256 values matched exactly.
The fixture clocks and endpoints are synthetic; this is framework/ownership proof,
not native inference, profile, physical, API37, performance or 24-hour evidence.
The owned emulator was verified by name/serial and stopped after each run.

Independent review found three capture-publication ordering windows. Minimal fixes
invalidate the old frame before turn publication, disconnect publication and
closed-state publication, preserving the relative order of all functional code.
The known-PID guard is unchanged. Two deterministic regressions use only the
existing unload-owner lock through test reflection, with bounded worker-state/stack
checks and cleanup in finally; no production callback/fault seam was added.
A pre-fix run on source875ec3 executed one test and reproduced its exact turn
assertion failure. A second run on source7de2331 executed the whole capture class:
ten passed (including the turn fix), and only the planned disconnect assertion
failed. Both regressions pass in the final 76-case run. Close write order was
verified by source review rather than an artificial production test seam.

The first provisional build was deliberately interrupted for review (exit143),
then retained as an intermediate log, never claimed as a successful final build.
An intermediate turn-only fix also passed its checks; it does not replace the
final source/runtime proof above. Raw logs, method results, source revisions,
APK bindings and environment metadata remain separate for RED, RED2 and GREEN.
Original files are not rewritten to conceal failing controls.

Final independent code review found no further blocker in the minimal fixes.
Required exact-head PR CI must pass before merge. Capture remains optional and
absent from production composition; no calibrated settings, complete runtime
exporter/heavy workload or phase-gate approval is supplied.
