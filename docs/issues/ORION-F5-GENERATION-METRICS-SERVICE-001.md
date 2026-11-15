# ORION-F5-GENERATION-METRICS-SERVICE-001 — request-local AI adapter

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/119.
Base: 43082e1 (accepted ADR-0003).

OBJECTIVE: Adapt optional metrics from the already-owned llama generation into
one immutable cached snapshot per existing issued inference entry. Keep logical
terminal, JNI return and worker-slot cleanup independently observed.

FILES ALLOWED: ai/service main sources, build.gradle.kts and focused tests;
this work package and service technical note. Root owns ai/api/AIDL and the
explicit ai/llama receipt port; their WIP signatures are dependencies only.
Variant follow-up at f1ccb0e: ai/service/build.gradle.kts, the existing app
OrionCortexBoundaryTest.a_privateManifestAndVersionedProtocol and these docs only.
The actual normal APK must advertise mask zero even with both qualification and
metrics properties true. Only the matching qualificationRelease service variant
may enable capture; creating it changes packaging, not admission or authority.

CONTRACTS INVOLVED: Accepted ADR-0003; unchanged ADR-0002 issued-history ownership,
admission/retirement; original generation/callback/cancellation/watchdog behavior;
root-owned closed metrics model/codec and same-runtime llama receipt port.

INVARIANTS INVOLVED: INV-002/009/011/012/013/027/028/032/033/035/036.

TESTS REQUIRED: Bounded recorder lifecycle, cached query stability, independent
logical/native/final states, actual admitted bounds, source mapping, disabled and
fault/unavailable paths, invocation/sequence exhaustion, retirement/no resurrection.
Variant follow-up: freeze a default Debug test APK containing the mask-zero
assertion, run it against the normal Debug target built with both properties true
to reproduce the vulnerable artifact, then repeat after the fix; verify ordinary
Release also has source false and separate qualificationRelease can advertise
source true only with explicit opt-in. Root owns all artifact/build/device work.
Root runs canonical JVM/Android/native/R8/compatibility checks; no local builds or
device operations by this author.

ACCEPTANCE CRITERIA: Default false qualification-only build opt-in; discovery
and queries inspect cached state only, with no new clock or execution. Original
decision/output/finish/exception/cleanup behavior remains unchanged. One-shot
collector on the same runtime; only proven native receipts get process-local
positive invocation identity. Time remains explicitly unavailable. No arbitrary
observer callback, additional history or persistence. Disabled/faulted/exhausted
capture remains unavailable without affecting inference. Late publication cannot
restore retired history.
Normal Debug/Release service variants always compile source false, including
when both properties are true. The service qualificationRelease variant matches
the app variant by name, falls back to release for dependencies and is disabled
unless orionQualificationRelease=true. Only it consumes the metrics enable flag.

OUT OF SCOPE: ai/api/native edits, MAIN client, new authority/policy/profile,
new runtime or clocks, benchmarking workload, physical qualification or F5/F6
closure. Opt-in compilation is not model/resource admission.

VALIDATION: Sixteen JVM and two Android metadata-only tests supplied. Execution
is pending coordinated root/CI checks; no Gradle/Docker/ADB/device operations by
this author. See [service note](../qualification/F5-GenerationMetricsService.md)
for source boundaries, missing clocks and finality/retirement behavior.
