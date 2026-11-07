# ORION-F4-QUAL-001 — Physical/system qualification of the F4 candidate

Tracking: [issue #64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64).

OBJECTIVE: Decide the Master Plan §10.11 F4/MVP Core gate from definitive physical
runtime evidence after the deterministic scheduler/recovery candidate lands. The
user explicitly continued this delivery with physical qualification pending.

FILES ALLOWED: docs/qualification/**, compatibility profiles, host runtime recorders
and focused instrumentation fixes required to capture evidence. Product/architecture
expansion is outside this work package.

CONTRACTS INVOLVED: Architecture V3.2 scheduler/recovery/confirmation/ownership;
Master Plan §10; AGENTS.md §31; ADR-0001; predecessor F2 physical qualification issue #23.

INVARIANTS INVOLVED: INV-001/005/006/007/008/014/015/016/018/019/021/022/023/
027/029/030/032/033/035/036.

TESTS REQUIRED:
- Capture Samsung Galaxy S21 8 GB model, API/build, ABI, page size and actual source/APK hashes.
- Run the full Android and Keystore suites plus installed 4→5 replacement preserving
  ciphertext, keys, pending/uncertain actions and durable queues without data clearing.
- Exercise committed external-claim SIGKILL and verify no blind replay; distinguish
  synthetic external markers from actual NotificationManager delivery proof.
- Observe real confirmed reminder delivery after reboot/unlock, time/timezone changes,
  Doze and explicit permitted return after Force Stop. On API 35+, capture the fresh
  persisted ApplicationStartInfo.wasForceStopped signal. Never promise stopped revival.
- Cover notification denial/channel disable, exact special-access loss/gain with
  approximate fallback, background restriction/battery saver, storage/resource pressure,
  repeated recovery, civil DST and finite-series cancellation/uncertainty boundaries.
- Run and document the required long-running soak, including crash/ANR, battery/RAM,
  storage growth, queues, uncertain actions, reminder loss and recovery loops.
- Keep broader Android API/OEM and native arm64/16 KiB qualification separate from
  S21 performance evidence; record unsupported platform signals explicitly.

ACCEPTANCE CRITERIA: Reports identify PHYSICAL_DEVICE, matching source/artifacts,
actual outcomes and preserved failed attempts. Review every §10.11 item against its
required evidence. Resolve prerequisite physical gates; do not issue an MVP/24-7 tag
while a required qualification remains unproven.

OUT OF SCOPE: Running without an available authorized physical target; AI/voice;
root/Force Stop/Doze bypasses; fabricated future consent; promoting emulator results
to physical evidence; architecture waivers; automatic stable release tagging.
