# F5 software foundation and audit

Work package: [ORION-F5-INTEGRATION-001](../issues/ORION-F5-INTEGRATION-001.md).
Baseline: `6dfb4e9`; implementation branch: `feat/f5-cortex-foundation`.

The user authorized parallel implementation and a final audit while the S21 is
unavailable. The physical 24-hour soak, resource/performance calibration and the
full native/API release matrix remain separate, open qualification obligations.
This document records software scope. Final source, artifact and log identities
are recorded in [f5-software-evidence.json](f5-software-evidence.json).

## Implemented boundaries

MAIN obtains resource/exit/pressure observations and owns Room, model import,
registry recovery and inference admission. The registry migration adds one table
and index to schema 5; preceding schemas and encrypted queues are preserved.
Validated immutable SHA-256 identities and read-only model descriptors cross IPC.
The disposable `:ai` process owns the native engine and never opens repositories
or executes Android Skills. Application construction is lightweight in auxiliaries.

Production qualified model plans are deliberately empty. Public Android process
memory APIs are throttled/cached and do not establish the required complete fresh
aggregate ([Android API reference](https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int%5B%5D))). The default observer preserves that missing evidence instead of using
zero or relabelling it as a fresh qualified measurement. A qualified complete-memory
provider and measured real-model plans remain necessary before production loading.
Tests inject explicitly synthetic observations only in test sources.

Every bind/load/inference requires fresh resource admission. Approval carries its
boot-bound sample/lifetime and exact limits through queue wait to JNI entry. The
restart circuit consumes a fresh history observation per explicit connection;
bounded attempts/deaths and cooldowns prevent automatic relaunch loops. Memory
pressure in AI fences the service, cancels work and releases model state. Failure
requires a new explicit guarded request; no partial text becomes a mutable action.

Registry import checks space, size, complete hash and bounded GGUFv3 structure.
Temporary files and IMPORTED/VALIDATING states precede atomic promotion and READY.
File/directory fsync and same-inode validation/duplication preserve the handoff.
Unsupported types/splits are rejected. READY means structural validity, never
backend, hardware, performance or authorization qualification. Full interrupted-file
hashing runs after deterministic Core readiness and blocks optional inference until
its reconciliation finishes. It cannot delay Core readiness for a multi-GB scan.

Streaming has bounded Unicode-safe frames, sequence/dedup/reorder limits, one
logical terminal state and bounded queues/status retention. Cancellation, monotonic
timeouts, client death and Binder death clean ephemeral data. A stuck native worker
is terminated only in AI after a bounded grace interval. No external side effect,
ActionRequest confirmation or recovery semantics is changed by generated text.

## Native source and fixture identity

llama.cpp is pinned by gitlink to
`7d7f0287530bc8981690f361d8c1627dbae1c99a` (`b12211`), with its MIT license
retained in source and packaged notices. CMake builds only the CPU library/wrapper;
there is no CLI/subprocess/network runtime dependency. JNI owns handles and RAII
objects; file descriptors are checked read-only/regular, callbacks attach native
threads safely, UTF-8 pieces are decoded incrementally, and normal/fatal native log
paths discard model-controlled content. ELF link alignment is 16 KiB; executable
compatibility remains a separate runtime gate.

The optional native smoke uses `ggml-org/models` revision
`499bc8821c6b12b4e53c5bffcb21ec206f212d81`,
`tinyllamas/stories260K.gguf`, SHA-256
`270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d`.
The fixture is fetched by the canonical build with size/hash bounds and is included
only in the opt-in instrumentation APK. Its synthetic policy is TEST_FIXTURE_ONLY.
The software test covers import/load/stream/unload, logical cancellation after
the first chunk, request timeout, real Binder death and explicit rebind. Logical
cancellation/timeout does not alone prove interruption of stuck native code;
bounded escalation is exercised separately by controller fixtures. Neither the
model size nor injected observations qualify a production plan.

## Audit findings addressed

Independent peer reviews and focused regressions identified the following issues
during implementation; source fixes are retained and final checks cover their scope:

| Finding | Correction |
| --- | --- |
| Fatal GGML abort bypassed ordinary logging | Private abort callback drops model-controlled message; AI still terminates |
| Partial JNI callback construction leaked references/pending exceptions | Staged allocation/lookup checks and exception-safe cleanup |
| Optional resource bootstrap could escape Application recovery | Optional construction/registration failure falls back safely |
| Model hash recovery delayed the Core barrier | Deferred interruptible optional reconciliation with a separate inference gate |
| Cancellation could quarantine valid model data | Interrupted-channel exceptions propagate before terminal registry writes |
| FIFO/private special file could block model open | Nonregular precheck, nonblocking NOFOLLOW open and exact descriptor fstat |
| Migration fixtures left queues/lease/binding empty | Nonempty encrypted typed-cell snapshots across 5→6 and decrypt/binding assertions |
| Old watchdog ownership could kill subsequent work | Atomic ownership/eligibility check before escalation |
| Resource approval expired in callbacks/queue | Fresh reapproval and exact boot/monotonic envelope checks at native entry |
| Cancel/close retained queued payloads or stale load state | Terminal cleanup releases prompts/listeners/descriptors and load bookkeeping |
| AI pressure lacked independent fencing | Sticky service pressure fence, cancellation/unload and explicit guarded recreation |
| Logical cancellation reported READY before JNI cleanup returned | Status retains physical execution ownership until cleanup completes |
| Service destruction could stop its watchdog before native close finished | Monitor remains alive through worker termination and escalates a stuck close |
| Old optional health callbacks could overwrite a newer recovery | Ephemeral source ownership serializes invalidation and bounded health publication |
| Mapping-only JNI check could not prove native declarations | Inspect actual minified DEX descriptors/access and reject renamed, mismatched or static fixtures |
| Late failure of an old Binder transaction could disconnect a new binding | Capture transport and connection epoch atomically; disconnect only the captured epoch |
| Native load probes rejected the C++ runtime filename | Permit the literal plus sign in bounded APK library basenames and still load the exact APK entry |
| Opt-in smoke assets were packaged without its Kotlin class | Register the dedicated directory as Kotlin sources and execute the resulting instrumentation class |

## Validation and remaining qualification

The final software checks passed:

| Check | Environment | Result |
| --- | --- | --- |
| JVM contracts, including recovery/resource/registry/controller regressions | CONTAINER | 744 tests, 100 suites; zero failures/errors/skips |
| Host ADB harness contracts | CONTAINER | 125 tests passed |
| Actual DEX JNI positive and five negative fixtures | CONTAINER | Six fixtures passed |
| Build, lint, module boundaries, debug/release APK and release AAB | CONTAINER | Passed |
| Minified JNI declarations and arm64/x86_64 ELF/ABI/16 KiB packaging | CONTAINER | Passed |
| Installed 5→6 replacement, existing ciphertext/keys/queues/PENDING/UNKNOWN | ANDROID_EMULATOR | Both preparation and verification passed without uninstall/data clearing/downgrade |
| New registry migrations and default fail-closed production integration | ANDROID_EMULATOR | Seven tests passed; also included in the full suite |
| Full application instrumentation | ANDROID_EMULATOR | 332 tests passed; zero skips |
| Real Binder/fake-engine transport and Android Keystore | ANDROID_EMULATOR | Eight plus four tests passed; zero skips |
| Pinned GGUF through real registry/Binder/JNI, cancellation/deadline/unload/death/rebind | ANDROID_EMULATOR | One composite test passed; zero skips |
| Non-debuggable R8 APK native loading and healthy storage startup | ANDROID_EMULATOR | Two tests passed |

Android runs used API 35, x86_64 and 4 KiB pages. They are explicitly
`NON_DEFINITIVE` for runtime/hardware qualification. The final normal application
APK is byte-identical to the real JNI smoke application APK. Both native libraries
are also byte-identical between debug and the R8 qualification APK. R8 runtime
coverage establishes ELF loading/startup, while actual inference ran in debug;
it does not establish real-model R8 inference performance or arm64 execution.

The first full emulator attempt recorded three failures: the load probe rejected
`libc++_shared.so`, and two UI flows lost focus to an Android System UI ANR dialog.
The basename guard was corrected without relaxing path checks. A captured screenshot
confirmed the modal while the app screen was loaded; selecting the system dialog's
normal Wait action allowed both unchanged UI tests to pass. The final 332-case suite
passed in a separate clean disposable emulator. The underlying system ANR cause is
not established. The first smoke attempt exposed missing Kotlin source registration;
the corrected APK's DEX was inspected and its real instrumentation executed. Failed
attempts, screenshots and intermediate artifact identities remain preserved.

Canonical build/test/lint, packaging, migration and emulator results must be read
with the exact source/artifact hashes in the accompanying evidence record.
Historical F4 or physical reports are not relabelled as runs of this implementation.
Device-independent fixtures/fake engines establish software contracts only.

Still open: a qualified complete fresh aggregate-memory source, real model limits,
physical S21 Android/OEM resource/death behavior, real-model memory/latency/
thermal/battery measurements, full native and API release compatibility, and the
post-MVP 24-hour soak and review. Production inference and readiness promotion stay
blocked by those independent requirements. Voice, hybrid tool execution, Sentinel,
inferred memory and UI model management are outside this increment.

Request dedup/status state is bounded to 1,024 IDs per client/service lifetime;
exhaustion refuses new work rather than evicting replay protection silently. The
registry storage cap includes invalid/quarantined entries. Reimport of those
identities is refused; an explicit retry/delete API and management UI remain future
work. These limits are operational caps,
not measured S21 budgets or promises of continuous operation.
