# ORION-TEST-003 — Installed application upgrade infrastructure

OBJECTIVE: prove state survives replacing an installed APK with a newer version,
using the actual main database and Keystore, without uninstalling or clearing data.

FILES ALLOWED: app version metadata; UpgradePersistenceTest; host recorder and its
contract tests; CI harness checks; related qualification docs.

CONTRACTS INVOLVED: Architecture §§49/70–73/110; Master Plan §8.10;
versioned APK upgrades, encrypted field/key persistence, pending/uncertain state.

INVARIANTS INVOLVED: INV-001/005/006/007/011/014/019/033/035.

TESTS REQUIRED: seed prior APK with task/reminder/memory, claims, pending/UNKNOWN
actions, pending event/ingress; install candidate with `adb install -r`; verify
higher version/new PID; decrypt with original Keystore version; unchanged logical
records and event/ingress state; no execution/retry. Contract tests reject invalid
fixtures/modes and false instrumentation success; no uninstall/reset fallback.

ACCEPTANCE CRITERIA: recorder identifies both APK/test-APK hashes, actual installed
versions, Android environment and outcome. Fixtures use only synthetic content and
clean up their exact IDs after verification. Production moves to versionCode 2.

OUT OF SCOPE: F4 complete RecoveryCoordinator and real scheduler reconciliation,
production Skill side effects, S21/OEM qualification. Explicit migration tests
remain separate from same-schema installed replacement evidence.

Validation: canonical CONTAINER check/build/lint/native gates passed with 202 JVM
cases in 35 suites. The prior APK was rebuilt from d7a2301 with only the fixture
added, a separate Gradle project cache and the same debug-keystore volume. The
baseline fixture passed locally, then the host recorder passed prepare/version
1→2/verify on ANDROID_EMULATOR API35. Candidate startup completed with FIELD_KEYS
HEALTHY; all encrypted content/claims and original key version survived. UNKNOWN
kept its single historical attempt and five audit records, without replay.

The full candidate instrumentation passed 76 cases and the Foundation harness
passed four cases after process recreation plus four after allowed Force Stop
return. Earlier attempts are retained in the qualification report: an outdated
baseline test fixture was detected, and a separate System UI ANR blocked UI
navigation after 76 passing tests. No detector/assertion was weakened; the fixture
was rebuilt from frozen source and the official System UI Close app action was
used before repeating the complete harness. Physical qualification is pending.
