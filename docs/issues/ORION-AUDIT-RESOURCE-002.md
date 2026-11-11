# ORION-AUDIT-RESOURCE-002 — Resource exit backoff and Android trim classification

OBJECTIVE: Close the audit's excessive-resource restart gap and distinguish Android
UI visibility trim signals from actual memory pressure.

FILES ALLOWED: core/resources source/tests; app resources observation source/tests;
this work package and its validation record.

CONTRACTS INVOLVED: Architecture §§33–35.1; Master Plan §§11.9–11.10;
fresh MAIN resource decisions, bounded AI exit history and qualified fallback plans;
OrionClock/BootSessionId. This corrects implementation within existing boundaries.

INVARIANTS INVOLVED: INV-011, INV-013, INV-027, INV-028, INV-032, INV-033, INV-036.

TESTS REQUIRED: excessive-resource exits trigger cooldown, lower qualified plans,
disable preload/keep-alive and exhaust admission under repeated deaths; mixed,
duplicate, shrinking and reconstructed histories preserve evidence; wall/boot
changes cannot bypass backoff; governor independently blocks fresh resource-death
evidence; normal kills remain distinct. Android trim tests cover UI_HIDDEN, running
memory pressure, cached-process levels, unknown/missing evidence and system low
memory, with the production observer using the tested projection.

ACCEPTANCE CRITERIA: neither guard nor governor treats repeated resource kills as
healthy; memory and excessive-resource diagnostics stay distinct; UI_HIDDEN alone
is not critical memory pressure; missing/unknown signals fail safely. Focused tests,
canonical check/build/lint/dependency/native/R8 checks pass.

OUT OF SCOPE: production profiles, actual process relaunch, IPC/schema changes,
physical MemoryLimiter/resource calibration, soak qualification and publishing.
