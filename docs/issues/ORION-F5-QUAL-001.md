# ORION-F5-QUAL-001 — Physical Cortex qualification and post-Cortex soak

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/89

OBJECTIVE: Close the remaining F5 hardware/resource/stability gates using definitive
physical evidence for the integrated audit candidate, then decide whether
CORTEX_RUNTIME_READY and entry to F6 are justified.

FILES ALLOWED: docs/qualification/**, docs/compatibility/**, docs/benchmark/**;
this work package at docs/issues/ORION-F5-QUAL-001.md;
existing HOST recorders and focused instrumentation fixes required for evidence.

CONTRACTS INVOLVED: Architecture V3.2 §§25–35; Master Plan §§11.9–11.11 and §31;
ADR-0001; F4 physical/stability prerequisite tracked by issue #64.

INVARIANTS INVOLVED: INV-001/009/011/012/013/014/018/019/027/028/029/030/032/033/036.

TESTS REQUIRED:
- Bind the exact reviewed commit/source manifest and Docker-built APK/test hashes
  to the actually installed artifacts, physical model, API/build, ABI and page size.
- On Galaxy S21 8GB, verify real arm64 model import/load/inference/stream/cancel/
  timeout/unload, AI death/Core survival and explicit rebind, recording failures.
- Measure cold/warm load, prefill/decode, per-process and aggregate memory/headroom,
  thermal/battery behavior, foreground/background transitions and storage pressure.
- On a compatible physical API37 build, capture actual MemoryLimiter classification
  and prove the next heavy request receives changed resource policy, bounded cooldown
  and eventual admission refusal under repeated resource deaths.
- Complete the required native/API compatibility matrix independently from S21
  performance. Record unsupported platform signals instead of inferring support.
- Run at least 24 real elapsed hours of post-Cortex soak plus memory stress. Record
  crash/ANR, resource exits, battery/thermal, memory/storage growth, queues, UNKNOWN
  actions, lost reminders and recovery/restart loops. Preserve interrupted attempts.
- Approve production model/resource profiles only from reviewed measurements.

ACCEPTANCE CRITERIA: Required physical/stability checks pass against the frozen
candidate with reproducible evidence, F4 prerequisite gaps are resolved, and every
F5 exit criterion is reviewed. Only then declare CORTEX_RUNTIME_READY and begin F6.

OUT OF SCOPE: Fabricating physical or 24-hour evidence; substituting emulator
success for hardware qualification; enabling an unqualified production profile;
implementing F6 while its gate is open; architecture changes; stable release tags.

Current blocker (2026-11-10): the user confirmed there is no access to the Galaxy S21
and no physical API37 device. No physical run or 24-hour soak has started. Container
and emulator results remain scoped evidence, and F5 remains OPEN.
