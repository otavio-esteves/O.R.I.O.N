# ORION-DATA-011 — Bounded private temporary ingress payloads

OBJECTIVE: provide the F2 private-file path for payloads larger than inline ingress,
with encrypted files, opaque UUID references, admission bounds and explicit TTL.

FILES ALLOWED: data/repository private-payload adapter; app instrumentation/tests;
MAIN key-initialization empty-storage proof; qualification documentation.

CONTRACTS INVOLVED: Architecture §§48/70–73/101; Master Plan §§8.6/8.7/8.9;
DurablePayloadPolicy, versioned field encryption and credential storage ownership.

INVARIANTS INVOLVED: INV-001/011/019/023/033/035.

TESTS REQUIRED: >256 KiB encrypted round-trip/reopen; AAD/header tampering;
TTL/rollback; missing keys preserve files; count/size admission; bounded cleanup;
orphan atomic-write cleanup; no raw payload on disk; reference request expires
no later than its file. Docker build/lint/boundaries and Android instrumentation.

ACCEPTANCE CRITERIA: MAIN/unlocked, no backup; UUID paths only; up to 16 files,
1 MiB plaintext each, TTL at most 24 hours; no plaintext files; immutable receipts;
expiry authenticated before cleanup; no key regeneration over existing artifacts.
Interrupted uncommitted writes may be removed, never committed unreadable content.

OUT OF SCOPE: large Binder transport, raw audio, model imports, scheduler F4,
automatic periodic maintenance, external effects and physical/OEM qualification.

Validation: CONTAINER `:app:assembleDebug :app:assembleDebugAndroidTest
:data:repository:lint :app:lint verifyModuleBoundaries --max-workers=2` passed.
ANDROID_EMULATOR API35: six PrivatePayloadStoreTest cases passed, including
expiry, tampering, missing keys, quota, orphan recovery and queue clock skew.
Final integration reruns validate the final source snapshot. Locks do not span
file access and SQLite acquisition simultaneously during enqueue, avoiding a
cycle with transactional consumers that read referenced files.

There is no automatic maintenance scheduler in F2: the explicit bounded cleanup
entry point and queue admission cap remain available for the F4 owner to invoke.
