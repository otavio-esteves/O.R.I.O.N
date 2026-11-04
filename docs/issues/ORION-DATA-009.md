# ORION-DATA-009 — First-install key initialization

OBJECTIVE: wire explicit Keystore provisioning into MAIN startup only after proving
the authoritative encrypted stores empty. Persist an attempt marker before key
creation so interrupted provisioning and lost keys cannot trigger silent recreation.

FILES ALLOWED: core/security coordinator/tests; data/datastore marker;
data/database empty-storage query; app startup/tests; core/health and diagnostics;
related Gradle wiring and qualification reports.

CONTRACTS INVOLVED: Architecture §§49, 70–73; Master Plan §8.7; versioned key
lifecycle, credential storage, health and Startup Barrier.

INVARIANTS INVOLVED: INV-001/011/014/019/023/033/035.

TESTS REQUIRED: fresh install; existing ciphertext refuses provisioning; marker
committed before key creation; interrupted initialization stays blocked; available
historical keys reopen; invalidated keys never regenerate; marker write failure
does not call provider; Android runtime and Docker build/lint/boundaries.

ACCEPTANCE CRITERIA: key health observable; missing/invalidated keys block protected
storage; no plaintext fallback; mutable ingress/execution remain unavailable.

OUT OF SCOPE: F3 Skills, F4 scheduler/recovery, key reset/deletion, physical/OEM
qualification, bulk re-encryption and enabling product commands.

Validation: CONTAINER `:core:security:testDebugUnitTest :core:security:lint
:app:assembleDebug :app:assembleDebugAndroidTest verifyModuleBoundaries
--max-workers=2` passed; six new initialization tests passed. HOST/ANDROID_EMULATOR
API35: FoundationRuntimeTest plus the encrypted-table emptiness proof passed
(5 cases). Physical qualification remains pending. Key failure is reported as
FIELD_KEYS=FAILED and Core DEGRADED_SAFE; protected mutation remains unavailable.
