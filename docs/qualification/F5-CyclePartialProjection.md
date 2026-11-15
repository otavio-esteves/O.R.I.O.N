# Minimized manual MAIN cycle projection

[Issue125](https://github.com/otavio-esteves/O.R.I.O.N/issues/125),
[work package](../issues/ORION-F5-CYCLE-PROJECTION-001.md), source contract:
[issue123 owner cycle](F5-OwnerCycleObservation.md).

`OrionCortexCyclePartialProjector.project(receipt)` is an explicit manual pure
operation on a supplied receipt. It is not called by the owner/client/factory or
by their run/cleanup paths. It does not query a source, run a model, read a clock,
evaluate policy, perform IO, install a callback, or create a sink. An absent
receipt returns null. A general diagnostic failure also returns null; optional
section failures are contained as unavailable sections. No source object changes.

The output is an immutable detached JSON String plus its exact UTF-8 byte count.
The String contains only ASCII literals, names from typed enums, booleans,
nonnegative integer counts and masks. The constructor is private. No caller
String or arbitrary object is encoded, escaped, inspected with reflection or
converted with `toString`. There is no file/log/network/Android dependency or
automatic export. Future storage or export still needs its own authorized scope.

The closed format is `ORION_MAIN_CYCLE_PARTIAL`, `schemaVersion=1`. It is **not**
RuntimeEvidenceProtocol v1, a complete runtime cycle or a heavy-soak receipt.
Qualification is always `NOT_QUALIFIED`. Source authentication, physical/profile/
production-inference approval, RuntimeProtocol v1 completeness, F5/soak gate
closure and F6 authorization are all hard-coded false. These flags remain false
even if a supplied native snapshot reports FINAL. A typed receipt or a JSON file
is not provenance authentication. Mechanical/default/UNKNOWN source values do
not become evidence that actual execution or capture occurred.

All identifiers are omitted: logical/issued request ID, client scope, PID,
connection epoch, native invocation/sequence, boot count, model identity/hash,
paths and arbitrary labels. The trusted-model field is never read. All timestamps
are omitted, including native capture/wrapper times and exit-history wall time.
Plans, budgets, process-memory details, exit/history entries, content, chunks,
pieces, raw errors and exception descriptions are omitted. Early NO_ATTEMPT
therefore contains only source enums, booleans and its actual unavailable reason;
Completed serializes only typed status/stage/terminal/failure, never a request ID.
`throwClassificationAsReported` is only CANCELLED/INTERRUPTED/OTHER or null.

The source state/source kind and nullable actual-turn/accepted markers are copied
as reported. They are not recomputed from other fields. Each of `outcome`,
`stream`, `resources`, `native`, `unload`, `sourceIssueMask` and `observerLoss`
contains exactly `presence` and `value`. Presence is PRESENT, ABSENT or
UNAVAILABLE. A null optional source is ABSENT; malformed/faulty data is
UNAVAILABLE. Both have JSON null value. Required source collections can be known
empty: resources is PRESENT with `[]`, issues is PRESENT with mask zero. Unknown
and absence are never filled with synthetic zero counts or a fabricated native
NOT_STARTED/FINAL state.

Stream fields copy the three nonnegative lower-bound counts. They are independent
after LOSS; a lost attempt event can leave returned-at-least greater than
attempted-at-least. No exact counter relationship or token/delivery assertion is
invented. Observer drop/failure counts explicitly remain **observer-global**
cumulative lower bounds, not per-token counts, with the reported accounting flag.

At most twelve resource summaries copy callback/phase, endpoint/snapshot presence,
snapshot-limitation mask and the actual reported decision flags/enums. Restart
death counts are bounded by the existing 64-entry guard. No plan, budget, clock,
memory/history reconstruction or new resource decision accompanies the summary.
Endpoint presence is not endpoint authentication or identity correlation proof.

Schema1 source-issue mask positions, from bit zero upward, are PENDING, LOSS,
NO_ACTUAL_TURN, RESOURCE_FACTS_MISSING, METRICS_UNAVAILABLE, UNLOAD_FACTS_MISSING,
POST_REPLY_TIME_UNAVAILABLE, ENDPOINT_UNAVAILABLE. Resource-limitation positions
are MEMORY_TELEMETRY_UNAVAILABLE, EXIT_HISTORY_UNAVAILABLE,
EXIT_HISTORY_TRUNCATED, BATTERY_UNAVAILABLE, POWER_UNAVAILABLE,
SYSTEM_MEMORY_UNAVAILABLE, PROCESS_STATE_UNAVAILABLE. The mapping uses an explicit
ordered allowlist and enum identity, independent of future source enum order;
foreign or repeated enum members make that section unavailable.

Native summaries copy only supplied logical/native/finish/slot/snapshot/partial
enums, stage mask, time-state enum and the actual present metrics. They retain at
most the sixteen ADR-0003 metric keys, with its fixed bit positions and
representation bounds. No actual admitted bounds are guessed from a different
resource row. `timeStateAsReported=KNOWN_BOOT` copies only that source enum: boot
and timestamp fields remain deliberately absent, so this partial format cannot
validate that time window. UNAVAILABLE/DISCONTINUOUS stay explicit. Native API-wall
durations keep their source meaning and STEADY_CLOCK_NS domain, including measured
zero; absent durations stay absent. There is no TTFT, rate, total backend-compute
or freshness inference. A logical Completed/SUCCEEDED or an unload reply cannot
create a native snapshot, JNI return, RELEASED or FINAL.

Unload summaries copy endpoint/deadline/poll-reference presence and existing
no-endpoint/reply/validity/failure facts only. They do not calculate deadline
validity, post-reply time, memory reclamation or native release. The omitted load,
chunk and terminal timings remain missing; no RuntimeEvidence v1 completion is
claimed or repaired.

Iteration is bounded before projection: resource lists read at most thirteen
items, enum sets at most their allowlist size plus one. Their reported size or
`toList` optimizations cannot bypass this bound. Oversize is unavailable, never
silently clipped into complete evidence. Native access uses sixteen fixed typed
keys; masks/counts outside representation bounds are unavailable. The encoder
checks the remaining capacity before every append and permits at most 8192 ASCII
bytes. Overflow cannot yield a truncated JSON document; overall encoding failure
returns null. Repeated projection of the same immutable source is deterministic.
Detached output cannot change if a malformed caller later mutates source lists.

Sixteen focused JVM tests are supplied for privacy, independent JSON syntax/key
checks, unknown/absent fields, flags, known zero versus absent duration, lower
bounds, enum-type/cardinality faults, dishonest/faulty collections, malformed
native fields, source preservation, immutable detachment and the byte ceiling.
They use mechanical typed source receipts, not actual owner/native/device
evidence. The author ran no Kotlin/Gradle/Docker/ADB or device tests; root owns
canonical validation and CI. Only light source/link/diff checks are claimed at
handoff. No profile, physical result or phase gate changes here.
