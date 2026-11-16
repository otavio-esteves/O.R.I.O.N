# Finite HOST heavy-soak control component

Work package: [issue #142](https://github.com/otavio-esteves/O.R.I.O.N/issues/142).
This implements one required control component toward a future executor. It is
not an Android adapter, operational workload, full runtime producer or ready
physical/24-hour program. The [v1 protocol](F5-HeavySoakEvidenceProtocol.md),
[JournalStore](F5-HeavySoakJournalStore.md) and `qualification_host_clock.py`
remain unchanged. F4/#64, physical F5/#89, soak and F6 gates remain open.

## Work-package boundary

Objective: enforce one durable intent ACK before at most one typed effect, with
actual typed result or retained uncertainty and no replay. Files are the new
`scripts/android/f5_heavy_soak_controller.py`, its focused synthetic HOST harness
and this note. Contracts are the unchanged heavy v1 events, JournalStore CAS/
durability and suspend-aware HostClock. Affected invariants are
INV-005/006/009/011/012/014/018/019/027/028/029/030/032/035/036.

Acceptance requires real JournalStore ordering/failure tests plus typed synthetic
port controls; central integration owns execution and required CI. Android/IPC,
existing helpers, models, prompts, calibrated parameters, profiles, runtime
capture #141 and physical qualification are outside this package.

## Finite API and explicit dependencies

`HeavySoakController(new_directory, plan, configuration=..., admission=...,
producer=...)` creates and holds exactly one new JournalStore context.
It accepts only a successful exact `INITIALIZED_DURABLE` ACK. An existing
directory, including an interrupted journal, is refused. There is no open,
inspect, adopt, resume, replacement-plan or rebinding API. The plan becomes a
detached frozen/slotted Specification; its hashes are binding references only.

The finite methods are `begin`, `prepare_fixture`, `attempt`, `observe`, `cleanup`,
`finish` and `interrupt`. Each returns a fresh minimized technical report.
`close` releases only this HOST context and never executes cleanup. Concurrent
steps return BUSY without issuing another operation. A close attempted during an
active step returns false; the owner must close after the step finishes. A forked
child cannot operate the inherited controller. Each fixture, attempt UUID,
permit and producer handle is consumed at most once inside this live controller.
Attempt, fixture, record and retained-handle collections obey the existing bounds.

Three abstract typed ports are required and **no production implementation is
provided**:

- ConfigurationPort independently verifies reviewed configuration and actual
  source/artifact/model/target bindings, then revalidates them live.
- AdmissionPort obtains a fresh permit for the exact request and validates its
  identity, lifetime and real Core policy. It must preserve the Core's retained
  resource-exit/restart history across all HOST runs. A new journal does not
  authorize resetting that history or provide admission.
- ProducerPort supplies fresh bound device stamps and read-only observations,
  validates ownership of one opaque handle, and invokes one typed effect. Its
  future adapter must atomically consume/recheck its actual Core authority at
  invocation and retain the Core governor/restart/endpoint guards.

Python types, an adapter returning VALID, or a matching hash are not proof of
authorization, provenance or qualification. These ports are explicit trusted
composition dependencies. Missing ports/configuration refuse all work; a JSON
plan cannot install or enable them. There is no execution CLI, arbitrary callable
or shell/ADB/subprocess command path, loop around #130, automatic scheduler,
model import, workload template or operational parameter default.

The trusted port methods can block, including binding, observation, admission and
effect invocation. This finite component checks fences before calls and after
they return; it supplies no hard effect-duration bound and cannot prove that a
blocked transport has terminated. Operational adapters must define and enforce
their own bounded transport/native contracts. A local timeout or interrupted
wait cannot be translated into an actual Core terminal or release proof, and
does not authorize a replacement invocation while the original may remain active.

## Durable boundary and uncertainty

Fixture preparation, work and cleanup each follow this finite sequence:

1. Check local window/cadence/resource fences and obtain one typed fresh grant.
2. Append the corresponding closed v1 intent to the same store using the exact
   previous state digest. Validate the ACK's exact field set/types, status,
   previous SHA, new SHA, entry count and unchanged MONOTONIC source.
3. After that ACK, re-read the live binding/device boot/window and revalidate the
   producer handle and admission permit. The last HOST clock check follows the
   admission check. Invoke the typed producer once.
4. Validate the result's exact request/grant object identities and fresh
   post-return device binding/boot, rejecting retained/foreign terminals.
   Append its real result using the next CAS token, then update live bookkeeping.

Nothing invokes an effect before the durable intent ACK returns. An unavailable,
malformed, conflicting or uncertain ACK permanently halts the controller without
adopting a new state digest or retrying. Even when no effect was issued, a
committed intent can remain visible without a result. An inspection by a separate
later reader is evidence review only and cannot enable execution.

After an acknowledged intent, loss of identity/admission, reboot, clock failure,
interruption, transport exception, unknown or wrongly typed outcome halts as
UNKNOWN and leaves that intent unresolved. No synthetic WORK_RESULT is added.
A local transport timeout is never a TIMED_OUT inference terminal: only an actual
bound Core terminal supplied through the trusted result contract can record that
outcome. A genuine local guard/typed refusal records REFUSED without invoking work.

Failed/uncertain result publication also halts UNKNOWN. The committed file may
contain either only the intent or the actual result when its ACK was lost; neither
case enables replay. KeyboardInterrupt/SystemExit halt the live controller and
propagate the original object. Abrupt process exit loses ephemeral control state;
the new-only constructor prevents adopting the retained journal. Halting/closing
does not invent a device timestamp or append an assumed terminal/interruption.
Failed cleanup is retained and halts; this component supplies no cleanup retry.

## Clock domains and fences

JournalStore alone assigns its v1 `PYTHON_TIME_MONOTONIC_NS` timestamps. The
controller does not read private store snapshots or rewrite those timestamps.
The public constructor has no clock override. `begin` obtains its clock solely
from `HostClock.linux()`, and every timing checkpoint requires
`clockSource=CLOCK_BOOTTIME`, `evidence=HOST_CLOCK_ONLY`, a valid pinned HOST boot
digest and session. Missing/unavailable/synthetic clocks or factory failure halt
before reading the producer's first device stamp or preparing/invoking anything.
BOOTTIME governs duration, observation gaps and resource cooldown. Unknown HOST
boot/session, rollback, device boot change or device elapsed rollback halts. No
previous-boot elapsed subtraction or cross-session accumulation is allowed.

Tests explicitly mock the platform factory with synthetic counters and fixture
metadata to exercise the unchanged production guards; there is no test bypass in
the controller. That impersonation does not qualify a real clock, elapsed
duration or hardware. Separate regressions reject actual SYNTHETIC_INJECTED,
unavailable/wrong-evidence metadata and factory failure before any effect.
All fixture reports remain UNVERIFIED_INPUT/NOT_QUALIFIED.

The controller brackets each BOOTTIME read with MONOTONIC reads. A BOOTTIME
advance exceeding that outer MONOTONIC interval detects suspend/discontinuity and
halts instead of catching up. This comparison does not relabel or accumulate
either clock domain. The journal's own admitted-intent cadence additionally has a
conservative MONOTONIC fence, preserving v1 after delays. This is detection at
finite checkpoints, not a claim of continuous observation or that arbitrarily
short suspend can be distinguished inside measurement uncertainty.

Every effect rechecks the exclusive run deadline and maximum observation gap.
Late steps halt instead of issuing a catch-up batch. `observe` only accepts typed
complete resource and queue observations; work calls do not substitute for them.
Resource exits retain a same-device-boot fence and increment the death count.
Both BOOTTIME and actual device elapsed time must reach cooldown; at the death
bound subsequent work is refused. Reboot halts the entire context; there is no
reset/recovery event added to v1. Actual policy changes remain Core authority.

`finish` records program end only after owned fixtures have actual successful
cleanup results. It can describe an incomplete short program; it does not claim
that the target duration, heavy work or observation coverage passed. All reports
remain UNVERIFIED_INPUT/NOT_QUALIFIED with source/physical/profile/production,
F4/F5/soak/F6 approval flags false.

## Validation and remaining work

The harness uses actual JournalStore files/fsync/CAS plus synthetic typed ports.
It covers ACK-before-effect, lost/malformed/conflicting ACKs, storage/result
failures, original interruption, a real child-process exit after intent ACK,
one-shot/concurrency fencing, cooldown/death/attempt limits, boot/clock faults,
window/gap/suspend rejection, immutable/minimized records and sensitive sentinels.
These fixtures establish no Android execution, actual terminal provenance,
physical measurements, calibrated limits, autonomy or real elapsed 24 hours.

The author has not run these tests, Docker, Gradle or ADB; root centralizes
validation after handoff. The intended focused command in the canonical HOST
validation environment is:

```bash
python3 -B scripts/android/test_f5_heavy_soak_controller_harness.py
```

Still required: reviewed operational composition and atomic live adapters, actual
trusted calibration/admission inputs, authenticated complete runtime capture
#141, the full heavy-work/stress and observation program, independent coverage,
owned recovery/cleanup review and allocated physical targets/windows. This
component does not make that full workload ready. No schema, native, app, IPC or
architectural-authority change is introduced.
