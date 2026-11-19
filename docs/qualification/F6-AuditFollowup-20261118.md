# F6 audit follow-up — 2026-11-18

Work package: [ORION-F6-AUDIT-001](../issues/ORION-F6-AUDIT-001.md).
Functional source: `9073011` on `fix/f6-audit-followup`; base: `171bbda`.
Scope: the three audit findings, with no F7 runtime implementation.

## Changes and regression evidence

| Trigger | Previous behavior | Corrected behavior |
| --- | --- | --- |
| Confirm a notification while permission/channel access is denied, then dismiss it | Blocked confirmation stays at the head; exact dismissal stays QUEUED | A new dismissal uses existing priority 100 and can commit cancellation; barrier, presence, exact version/binding and legal-state checks still apply |
| Android MAIN receives a Cortex connection callback and the endpoint delays its protocol reply | Synchronous negotiation occupies MAIN | Negotiation runs on one lazy-started worker with one waiting slot, one negotiation per connection and cancellation/close/owner fences |
| Conversation contains an original statement followed by a correction | Recent-first selection is also rendered backwards | Selection still favors recent turns under budget; selected history renders chronologically |

The two new context JVM tests failed against the old implementation. Three
selected Android tests also failed against the old, hash-verified installed APKs:
permission/channel dismissal each returned QUEUED instead of APPLIED, and a MAIN
pulse could not run while the scripted endpoint waited. This proves the UI stall;
it does not assert that the product received an Android ANR dialog.

GREEN includes two new JVM tests and eight new Android cases: permission and
channel denial followed by dismissal; gated/unfocused dismissal; UNKNOWN
protection; a MAIN-dispatched slow connection; caller timeout and explicit new
bind; close with a late reply; and 100 duplicate connection callbacks without
queue saturation or repeated negotiation. Existing close semantics remain
CANCELLED; no terminality or protocol contract was changed.

## Verification

| Check | Environment | Result |
| --- | --- | --- |
| Focused orchestrator/app JVM plus Debug app/test APK build | CONTAINER | PASS; 32 + 319 JVM cases, zero failures/errors/skips |
| Canonical check/build/lint/dependencies/native packaging and four instrumentation APKs | CONTAINER | PASS; 23m10s, 4571 actionable tasks |
| Active-module Debug JVM inventory | CONTAINER | 1129 cases / 141 suites / 53 modules; zero failures/errors/skips |
| Host harness contracts | CONTAINER | PASS; 629 cases |
| Relevant client/IPC/capture/metrics/default-owner, notification, ingress and Compose suites | ANDROID_EMULATOR | PASS; 131 cases, 167.612s, zero failures/skips |
| Minified qualification APK / test APK / native packaging | CONTAINER | PASS; 12m51s, 1809 actionable tasks |
| Non-debuggable native/JNI/storage startup smoke | ANDROID_EMULATOR | PASS; 3 checks on the unchanged installed minified APKs |

Canonical command:

```sh
docker compose run --rm android-build check assembleDebug assembleRelease \
  :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest \
  :ai:llama:assembleDebugAndroidTest :ai:service:assembleDebugAndroidTest \
  --max-workers=2
docker compose run --rm host-harness-check
```

Release-sensitive command:

```sh
docker compose run --rm android-build -PorionQualificationRelease=true \
  toolchainInfo :app:verifyQualificationNativePackage \
  :app:assembleQualificationReleaseAndroidTest --max-workers=2
```

The scoped runtime used task-owned `OrionF6Audit20261118`, API35/x86_64, 4096-byte
pages, two vCPUs and 2048MiB configured RAM. This is functional emulator evidence,
not hardware/performance qualification. Both RED and GREEN target/test APK hashes
were compared with their installed copies before instrumentation. Canonical Debug
outputs were also checked against the tested GREEN hashes. Instrumentation status
frames and final case counts were validated, not merely ADB's process exit code.
Only active Gradle modules contribute to the JVM inventory; valid cached results
are retained, rather than claimed as newly executed tests.

## Attempts and limitations

- The initial Docker run intentionally failed the two context regressions. Its
  Android test compilation still completed. A prematurely started second Gradle
  invocation was stopped; subsequent Gradle builds ran serially. No result is
  credited to that cancelled invocation; its summary/tool transcript is retained,
  rather than a complete standalone raw build log.
- Initial AVD boot had framework ANRs before O.R.I.O.N. was installed. It was
  stopped. The next boot had a Bluetooth/framework failure and system_server
  restart before installation; those failures are retained. Positive RED/GREEN
  instrumentation ran later, with GREEN system_server PID 1761 before and after.
- The RED Android invocation's three failures are expected regression evidence;
  they are not relabelled as passes. The subsequent GREEN invocation passed all
  131 selected tests, including notification/chat UI and real Binder boundaries.
- The temporary emulator was stopped after GREEN while the minified build ran,
  to release host memory. A later smoke invocation has its own runtime record.
- The first minified smoke passed both native checks but failed to observe the
  qualification UI. Its window dump and screenshot show an Android System UI ANR
  dialog covering the screen; logcat records successful Core recovery and Activity
  display. Only that dialog's visible Close app button was used before explicitly
  repeating the unchanged APKs/assertions. The first invocation remains a failure.

## Recovery, security and upgrade review

Dismissal remains a durable local operation. A pre-commit death rolls its writes
back; a committed cancellation is authoritative. Its priority cannot interrupt or
cancel a RUNNING/UNKNOWN external effect, bypass recovery/readiness, fabricate
presence, refresh consent or change a confirmed payload. The prior confirmation
is rejected when subsequently drained against the cancelled action. Tests cover
restored permission and prove no notification invocation or automatic replay.

Existing queued envelopes retain their priority/hash unchanged. A new UI dismissal
uses a new producer key and the new priority; no persisted envelope, action payload
or confirmation is silently rewritten. Room remains schema 6. Installed RED→GREEN
replacement used `adb install -r`, preserving app storage/keys; this is not a new
full historical upgrade qualification matrix.

Connection work and its single-settlement continuation are ephemeral MAIN-owned
mechanics. Cancellation/close releases the pending bind; an old reply cannot
publish a new endpoint or issue/load through a newer connection. Worker/queue
capacity is bounded and idle workers expire. A Binder call itself remains
synchronous on that worker; no claim is made that coroutine cancellation can
interrupt the remote transaction. AI/Core death discards this ephemeral work;
no inference or external action is reconstructed or retried automatically.

No AIDL/capability/version, process topology/ownership, exported component,
permission, persistence strategy, key format, Action Outbox state machine,
ResourceGovernor or production profile changed. No prompt/content logging was
added. New tests use synthetic fixtures. No actual MAIN SIGKILL or new physical
performance/MemoryLimiter/soak evidence is claimed by this follow-up.

Contracts reviewed: INV-001/006/009/011–014/018–019/021–023/027–028/031/033/035–036.
Production profiles remain absent, physical/stability gates remain OPEN and
`CORTEX_RUNTIME_READY`, release and milestone tags are not declared. Remote CI
and review remain preconditions for merge; this follow-up does not merge.

## Evidence

[Machine-readable verification](f6-audit-followup-evidence-20261118.json) and
[verified evidence archive](artifacts/f6-audit-followup-evidence-20261118.tar.gz).
The archive contains 187 files (743682 bytes), SHA-256
`c018134f1ed141698dacd5352475522ed7ee4f7bdc2cb210e55e3b008ad036eb`. All 186 payload files were verified against their size/hash manifest;
the archive checksum also protects the manifest itself.
It retains exact RED/GREEN source snapshots, 141 active JVM XML suites, build/runtime
logs, installed APK hashes, the failed platform attempts, System UI screenshot and
scoped cleanup proof. RED test source recovery was checked byte-for-byte against
the pre-change manifest. Host home prefixes in logs/XML are sanitized; source
snapshots are exact. No APK, GGUF, private key or private app database is included.
Only the task-owned AVD/INI were removed after validation; existing AVDs were untouched.
