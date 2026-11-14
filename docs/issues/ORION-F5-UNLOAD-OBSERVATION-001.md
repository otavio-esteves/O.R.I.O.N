# ORION-F5-UNLOAD-OBSERVATION-001 — Passive unload observation

Tracking: https://github.com/otavio-esteves/O.R.I.O.N/issues/110

OBJECTIVE: Record a bounded passive technical observation of the existing Cortex
client unload, distinguishing an actual observed UNLOADED from unavailable or
uncertain cleanup. Inference completion never substitutes for unload evidence.

FILES ALLOWED: OrionCortexClient.kt; one related internal observation/helper file;
focused app JVM/Android tests; this work package and F5-UnloadObservation.md.

CONTRACTS INVOLVED: Architecture §§22/32/33; Master Plan §11.11; existing AIDL
unloadModel/getStatus, OrionClock/BootSessionId and private connection ownership.

INVARIANTS INVOLVED: INV-011/012/013/027/028/032/036.

TESTS REQUIRED: Observed/unconfirmed scripted results; absent/lost/replaced/closed
endpoint; deadline/boot/regression/clock failure across getStatus; malformed and
transport replies; bounded concurrent collection, loss reporting and immutable
drain; unchanged inference terminal/replay/cleanup and Cortex RPC counts.
Root owns canonical Docker JVM/lint/build and applicable Android regressions.

ACCEPTANCE CRITERIA: Existing getStatus is the only confirmation source, requiring
its strict UNLOADED envelope, same private endpoint/connection and valid original
unload deadline after the reply. Observer absence preserves the existing call
path. The optional final owned buffer uses tryLock without arbitrary callbacks,
is ephemeral and content-free, and drops/faults cannot affect inference, connection
cleanup, policy or admission. No extra Cortex RPC, bind, retry or profile.

OUT OF SCOPE: Load/stream hooks, heavy runner, JNI/performance/physical measures,
new IPC/persistence/owner configuration, approved profiles, F5/F6 or soak closure.

RECOVERY/SECURITY/MIGRATION: No durable state or schema changes. Local scope/epoch
metadata is not authentication or a replacement PID; absent PID/time stays null.
No prompt/chunk/model/error content is retained. UNLOADED is a point observation,
not proof of native release, memory reclamation or future residency. Extra opt-in
OrionClock reads use the existing Android BOOT_COUNT source and remain isolated
from the original cleanup decisions; there are no new Cortex calls.

VALIDATION: Ten focused JVM tests and fourteen new Android fixture cases are
supplied (client class total 28), pending root's canonical JVM/Android/build/lint
execution. Existing cancellation/initial-expiry tests also assert observation
scope. Static review confirms unchanged Cortex/bind/unbind call sites and
git diff --check passes. No Gradle, Docker, ADB or device was executed here.

Review hardening moves extra clock/classification/publication after functional
timeout/catch and loaded=null; preserves clock-failure classification on each
poll; and rejects post-reply time below the pre-RPC poll even when both fit the
original deadline. The technical note documents scope/epoch, non-atomic loss
accounting, optional provider IPC and conservative observation-time limits.
