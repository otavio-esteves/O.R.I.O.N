# Scoped minified native qualification

Work package: [ORION-F4-NATIVE-001](../issues/ORION-F4-NATIVE-001.md).

The explicit `-PorionQualificationRelease=true` profile builds
`network.orion.qualification` with R8, `debuggable=false`, test signing and
arm64-v8a/x86_64 libraries. Its instrumentation targets that identity alone. The
variant is disabled by default. Normal debug/release identities, release signing
and the production arm64-only ABI policy are unchanged. This separate artifact
is qualification evidence, not a production-signed distribution approval.

```bash
docker compose run --rm android-build -PorionQualificationRelease=true \
  :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest \
  --max-workers=2
```

The verification task requires an R8 mapping and checks exact qualification package
identity, non-debuggable manifest, ELF machine/16KiB segment alignment and APK
16KiB native packaging. Qualification-mode x86 acceptance never weakens the
ordinary release checker. CI builds these artifacts after the canonical checks.

The instrumentation loads every library for the running ABI directly from its
exact target APK zip entry, preventing similarly named test dependencies from
satisfying the probe. It reports actual ABI/page size and the load assertion. A
platform-only Java `Instrumentation` runner launches the target with its public
package intent, observes the app's accessibility tree, opens Diagnostics and requires
recovered Core readiness plus `DATABASE: HEALTHY`. Every wait/traversal/scroll is
bounded. It references no application classes or shrunk support-library APIs. No
application keep rule or missing-class suppression was added.

An earlier AndroidX runner attempt failed before executing probes: it referenced
`androidx.tracing.Trace`, removed from the optimized target. Both incomplete runs,
APK identities and successful cleanup remain retained. The replacement runner
uses framework APIs directly and excludes qualification-only AndroidX test
dependencies; normal debug instrumentation is unchanged.

Runtime measurements must bind the exact APK pair, R8 mapping, source, device
fingerprint, ABI and page size. The recorded host procedure validates separate
package and instrumentation identities before installation, refuses preexisting
qualification packages and uses no overwrite option. Cleanup verifies installed
APK hashes before removing only the two packages created by that attempt. It
never replaces, clears or instruments `network.orion` and does not change its
permissions, alarms, clock or power policy.

Actual scoped runtime results and limitations are recorded separately. No debug,
x86 emulator, 4KiB or separately signed qualification result supplies physical
arm64/16KiB or complete native-gate approval. F4 and its real-duration gate remain
pending their required evidence and review.

Scoped results: the same minified application APK loaded both packaged libraries
on physical S21/API35/arm64/4KiB. Its startup and healthy-database assertion also
passed on API35 and official API37 AOSP ATD/x86_64/4KiB emulators. The S21 run was
native-only and required no foreground UI. Every temporary qualification install
was removed, and existing primary APK identity was unchanged at both endpoints.
Canonical Docker check/build/lint/static/native checks passed (4,111 actionable
tasks), as did the final minified build and eight isolated host procedure guards.

The API37 startup initially observed successful native loading but could not find
the healthy-database assertion after asynchronous scrolling. The runner now waits
for that exact assertion after each scroll within the original 30-second/12-step
bounds. It never relaunches or relaxes the expected state. The complete scoped
probe then passed; the incomplete attempt is retained.

Separately, the current default-debug API37 suite passed 323 of 325 cases with no
skips. Task/reminder recreation tests failed to rediscover their exact confirmation
selectors despite a readable matching DB pending record. These test sources were
unchanged by this package; the cause remains unproven and requires a separate
focused investigation. Cleanup and original emulator settings/display restoration
passed. This run does not supply complete compatibility approval. See the
[artifact-bound results and retained attempts](f4-minified-native-evidence.json).

The subsequent [recreation qualification](F4-UiRecreation.md) resolves the two
legacy test-environment failures through a two-import v2 setup change, preserving
all original assertions. Its final default API37 manifest passed 325/325 with zero
skips. The earlier incomplete run above remains historical evidence.
