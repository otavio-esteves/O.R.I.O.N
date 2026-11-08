# F5 first package — minimum resource policy

Work package: [ORION-F5-RES-001](../issues/ORION-F5-RES-001.md).
Entry review: [F4 functional audit](F4-FunctionalAudit.md).

`OrionMinimumResourceGovernor` implements the existing ResourceGovernor API using
one trusted observation and a qualified model/limit plan. It does not probe Android,
open Room, load a model, start/restart a process or authorize a Skill. The app still
uses `UnavailableResourceGovernor`; Cortex activation remains disabled.

Each call evaluates fresh input. Consumers needing matching model/limits use one
`evaluate()` immediately before a request and apply its complete envelope. A
returned decision is not a reusable authorization token. Headroom is best-effort.
Unknown, missing, inconsistent, expired or boot-invalid observations deny all work;
blocked decisions have no model plan, zero execution limits and zero keep-alive.

Plans come from trusted qualified configuration, never LLM output. The model ID
must be an immutable validated registry identity. Both ID and declared memory
reservation must match; callers cannot substitute a smaller estimate. Context,
threads and output tokens must fit the plan's qualified envelope. No real-model
plan or hardware thresholds are supplied by this package; unit values are fixtures.

The policy independently checks process, aggregate-app and available-system memory.
The aggregate includes every app process plus native/JNI memory. It reserves the
entire qualified model estimate in addition to measured usage; this is deliberately
conservative and does not optimize already-resident models. Arithmetic uses bounded
subtraction rather than potentially overflowing usage-plus-estimate sums. Battery,
thermal state, foreground/importance, power saver and pressure are mandatory inputs.

A first-seen memory exit starts a boot-bound monotonic cooldown, including historical
evidence first observed after construction/reboot. Wall timestamps identify exit
records but cannot shorten that duration. Duplicate observations do not renew it;
a distinct memory event renews it. Both incoming history copies and dedup state are
bounded. History loss and unclassified exit evidence remain non-permissive.
Provider failure denies work; cancellation/interruption retains its semantics.

No permission is cached or persisted. Process death discards ephemeral decisions;
recovery must supply fresh observations and exit evidence again. This policy never
retries an external UNKNOWN or performs an inference/relaunch. Dedicated Cortex
restart-loop coordination remains a separate F5 obligation, not a claimed result.

Next prerequisites: a MAIN-owned Android observation adapter, qualified real-model
plans and runtime wiring, then Cortex/IPC/model-registry/native work with its own
qualification. F5's complete exit gate, real inference/performance, native 16 KiB
and the post-MVP soak remain unapproved.

Validation: 23 resource JVM tests passed with zero failures/errors/skips, including
the existing unavailable fallback. A focused negative regression first reproduced
incorrect approval for unclassified exit history; the final guard rejects it and
the complete suite passes. Canonical Docker check/build/lint/module/native checks
passed (4,111 actionable tasks). [Source/test/log identities](f5-resource-governor-evidence.json)
retain the negative result separately. No new Android runtime or hardware result
is claimed for this package.
