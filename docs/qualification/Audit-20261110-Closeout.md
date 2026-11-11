# Audit integration — 2026-11-10 closeout

The resource, retention and Cortex findings are corrected in three scoped reviews:
[#86](https://github.com/otavio-esteves/O.R.I.O.N/pull/86),
[#87](https://github.com/otavio-esteves/O.R.I.O.N/pull/87) and
[#88](https://github.com/otavio-esteves/O.R.I.O.N/pull/88).
All three passed `docker-build-and-check` before merging into the delivery branch.
The protected-main delivery requires its own check; its PR records the exact head,
final check and merge. The [integration work package](../issues/ORION-AUDIT-INTEGRATION-001.md)
is tracked by [#90](https://github.com/otavio-esteves/O.R.I.O.N/issues/90).

The fixes preserve the existing architecture: resource exits consume the bounded
restart fallback ladder, Android UI_HIDDEN is treated as visibility evidence,
Core owns the single operational retention policy and its accounting, retained
Cortex IDs reject changed payloads, and expired/cancelled requests cannot continue
model preparation after their admission expires. No Room migration, AIDL/protocol
change, process change or production profile activation is added.

## Integrated validation

| Environment | Result | Scope |
| --- | --- | --- |
| CONTAINER | PASS | Canonical check/lint/module/dependency/Debug/Release/APK/AAB/native ELF/ABI/16KiB packaging and R8 builds |
| CONTAINER | 798 JVM cases, 108 suites; 127 host harness cases | Preserved aggregate reports, zero failures/errors/skips; unchanged tasks may reuse valid Gradle results |
| ANDROID_EMULATOR API35/x86_64/4KiB | PASS | Actual installed 5→6 replacement preserves encrypted records, keys and queues; PENDING/UNKNOWN are not replayed |
| ANDROID_EMULATOR API35/x86_64/4KiB | 3 PASS | Real pinned model streaming/unload, actual AI death/Core survival/explicit rebind, and service timeout/native unwind |
| ANDROID_EMULATOR API37/x86_64/16KiB | 395 PASS (962.559s) | Full application suite with the pinned model, including 33 load/infer/unload cycles; no failures or assumption skips |
| ANDROID_EMULATOR API37/x86_64/16KiB | 2 PASS scenarios | MAIN SIGKILL after verified file commit and after IMPORTED registry commit; new processes verify orphan cleanup and selected validation before READY |
| ANDROID_EMULATOR API37/x86_64/16KiB | 3 PASS | Minified native library load, AI constructor/corrupt-model rejection and non-debuggable Core storage startup |

The earlier focused 176 JVM, 26 Binder/owner/model Android, 15 client/queue Android,
75 persistence Android and API35 R8 runs retain their own identities and logs.
Selections overlap; their counts must not be added as unique cases. The sustained
test bounds technical stage observations at 64; its successful method result and
assertions prove all 33 iterations, rather than the number of emitted stage rows. The discovery
run that listed 395 tests used `log=true` and is not execution evidence.

## Source, artifact and failure provenance

The reviewed component integration tree exactly matches the frozen integrated
candidate. Every non-documentation input is compared again before publishing the
evidence. Existing canonical APKs are reused only after proving their build/runtime
sources are unchanged; the documentation-only change is explicitly recorded.
Local and actually installed APK SHA256 values are compared before the new
instrumentation runs. The upgrade recorder separately binds both version pairs;
the prior v5 binaries were HOST-built and only re-signed in Docker, as recorded in
the baseline provenance. It is not described as a canonical v5 rebuild.

The [evidence index](audit-20261110-evidence.json) records source manifests, reviews,
CI links, exact commands, APK/model identities, execution environments, results and
pending checks. Its [compressed evidence bundle](artifacts/orion-audit-20261110-evidence.tar.gz)
contains the raw selected logs, source manifests and recorders. It contains no APK,
model weights, emulator image or private signing key. Both owned temporary
emulators are stopped and removed; existing user AVDs and other project devices
are preserved.

Historical failures retain their scope: two earlier R8 visual attempts were blocked
by platform ANR dialogs, and the canonical stream client exited 143 while its build
container continued successfully to exit 0. Successful replacement runs have their
own logs; no failed attempt is relabelled. The preceding audit's source/summary and
persistence evidence are separate from the corrected Cortex candidate.

## Qualification still open

**F5 remains OPEN; CORTEX_RUNTIME_READY is not declared. Production inference
remains disabled, and F6 implementation remains gated.** The user confirmed that
neither the Galaxy S21 nor a physical API37 device is available. No physical run or
24-hour soak started in this integration.

The [physical qualification work package](../issues/ORION-F5-QUAL-001.md), tracked
by [#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89), specifies the missing
S21 arm64/model performance, aggregate memory, thermal/battery, actual target-build
MemoryLimiter, full native/API matrix, approved resource profiles, and at least
24 real elapsed hours of post-Cortex soak plus memory stress. Remaining F4 stability
work stays under [#64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).
Emulator functional success and the 33-cycle regression do not close those gates.
