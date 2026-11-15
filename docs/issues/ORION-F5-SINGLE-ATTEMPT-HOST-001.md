# One physical single-attempt HOST capture

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/130.

OBJECTIVE: Capture one already-installed qualification runner128 invocation from
the Linux HOST. Bind source/local/installed app+test hashes and boot windows;
persist only closed validated technical headers/partial125. NOT_QUALIFIED always.

FILES ALLOWED: New scripts/android/record-f5-single-attempt.py and its focused
harness; this work package and qualification note. No shared helper contracts,
Android/app/build/driver/profile/IPC/DB changes.

CONTRACTS: ADR0001 HOST/DOCKER/PHYSICAL; HOST suspend-aware clock116; exact
runner128 final framing; bounded partial125. Source/window binding is not
source authentication or proof of a READY model.

INVARIANTS: INV-009/011/012/027/028/032/035/036.

TESTS REQUIRED: NoProfile, observed partial+separate release, runner failure;
closed schemas/types/duplicates/extras/authority/size/counts; hash changes,
crossboot/rollback/gap/unknown/synthetic HOST clocks; interruption/timeout with
no replay; physical-only guard and command allowlist; sensitive sentinel output
never persisted. Root owns canonical Docker tests and all ADB work.

ACCEPTANCE: Explicit physical serial/S21 or API37, fixed no-argument instrument
command once, no install/grant/settings/push/import/kill/device cleanup/retry.
Mandatory CLOCK_BOOTTIME/HOST boot; unknown outcome never means terminated or
safe to replay. Source/local/installed hashes before+after. Exact primary pair,
separate release control, no fake result/counters for failure. Header<=1024UTF8,
partial<=8192, textual keys+values<=10KiB; HOST never claims Parcel measurement.
Persist only validated minimized payloads and technical metadata. All authority/
physical/profile/runtime/F5/soak/F6 gates false. Current empty config yields only
prepare refusal, not heavy evidence. No CLI config/admission fields.

OUT OF SCOPE: Model provisioning/import, operational profile/template values,
calibration, installation, workloads/loop/stimulus/24h, full runtime evidence,
physical qualification or F5/F6 closure, new ADR/authority.

VALIDATION: Recorder and 48 focused HOST tests supplied; canonical Docker execution
and peer review pending. Author checked Python AST syntax and whitespace only, not
HOST tests/Gradle/Docker/ADB. No shared contracts or Android files changed. The transport enforces its byte
bound during nonblocking pipe reads and its deadline with BOOTTIME; only its own
local ADB client can be ended, never the server/Android. HOST subprocess tests
use owned Python children, not ADB. Existing
compose discovery uses test_*_harness.py, so no registration change is needed.
See [capture contract](../qualification/F5-SingleAttemptHostCapture.md).
