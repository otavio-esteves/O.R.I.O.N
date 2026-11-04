# ORION-FND-016 — Serialize native gate with package writers

## OBJECTIVE
Prevent native gate validation from reading an AAB/APK while its producer is still
writing the ZIP. Discovered during F2 parallel canonical validation: unzip exited9
with an incomplete AAB central directory while :app:packageReleaseBundle ran.

## FILES ALLOWED
`build.gradle.kts` and this document; qualification report records the rerun.

## CONTRACTS INVOLVED
ADR-0001 reproducible Docker checks; native compatibility gate/packaging inspection.

## INVARIANTS INVOLVED
INV-032. Static packaging does not substitute physical native loading.

## TESTS REQUIRED
Run the full canonical Docker command with parallel workers2 and both app/Keystore
instrumentation APKs. Retain existing ELF/ABI/APK/AAB checks and negative fixtures.

## ACCEPTANCE CRITERIA
Both native gate tasks depend on the completed release bundle and run after
instrumentation assemblers when those tasks are scheduled. No validator or fixture
is disabled, no NOT_RUN physical check promoted to PASS.

## OUT OF SCOPE
Native libraries/toolchain changes, physical qualification, product architecture.
