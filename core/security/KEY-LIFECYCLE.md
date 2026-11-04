# F2 — Android Keystore data-key lifecycle

## Work package

- OBJECTIVE: protect versioned AES-GCM field data keys with Android Keystore,
  retaining historical read access across provider recreation and rotation.
- FILES ALLOWED: `core/security/**` only.
- CONTRACTS INVOLVED: `FieldKeyProvider`, field envelope V1, main-process credential
  storage ownership. Architecture V3.2 §§70–73; Master Plan V1.2 §8.7.
- INVARIANTS INVOLVED: INV-011, INV-019, INV-033, INV-035. This infrastructure grants
  no permission, confirmation or action-execution authority.
- TESTS REQUIRED: restart/reopen, historical reads after rotation, lost/invalidated
  keys, authenticated corruption, interrupted/failed write, no raw key on disk,
  no implicit provisioning, concurrent provider instances, bounded parsing.
- ACCEPTANCE CRITERIA: reads fail closed; missing keys/registries are not recreated;
  only authenticated wrapped key material is persisted; failed rotation preserves
  a readable committed registry; production entry point enforces MAIN/unlocked
  credential storage before opening infrastructure.
- OUT OF SCOPE: wiring business commands, F4 recovery, user remediation UI, deleting
  historical keys, bulk re-encryption, wrapping-key replacement, hardware/OEM claims.

## Lifecycle contract

`AndroidKeystoreFieldKeyProvider.open(context)` checks the existing main-process
storage policy and uses credential-encrypted `noBackupFilesDir`. It neither creates
a key nor provisions a registry. Call all operations outside the UI thread.

The trusted data owner may call `provisionNewInstallation()` only after proving
there are no encrypted records to preserve. This is an explicit installation step,
not a fallback for read/decrypt failure. The implementation refuses provisioning
if either a registry or the wrapping alias already exists. If provisioning crashes
after creating the alias but before committing the registry, reads stay unavailable;
manual remediation must establish that no encrypted records exist before any
future destructive reset. No such reset API is supplied.

AES-256 data keys are serialized into one versioned registry. The entire registry
is encrypted and authenticated by a non-exportable AES-256 Android Keystore key,
using a provider-generated random GCM nonce, 128-bit authentication tag, and fixed
domain/version/algorithm AAD. Registry version, current pointer and every historical
key are inside the authenticated ciphertext. Wrapping V1 has a four-byte format
version, 12-byte nonce and ciphertext/tag; unsupported versions are rejected.

`rotate()` atomically replaces the wrapped registry with the next data-key version
and retains all previous versions. `AtomicFile`, explicit file sync and readback
verification protect commits. One in-process lock serializes all registry instances;
the public entry point admits only MAIN. Reads during a rotation therefore observe
the previous or the newly committed complete registry. Rotation does not rewrite
existing field ciphertext. The cap of 1024 retained versions bounds storage and
parsing; reaching it produces `VERSION_LIMIT_REACHED`, never key eviction.

Missing alias/registry and transient Keystore/I/O failures produce `Unavailable`.
Permanently invalidated/unrecoverable keys or failed registry authentication produce
`Invalidated`. Corrupt registry structures also fail closed. No failure deletes or
replaces the committed file. There are no payload/key logs. The registry is bounded
to 64 KiB before parsing; secret serialization buffers are cleared after use, without
claiming complete managed-memory erasure.

## Validation and remaining qualification

`DurableFieldKeyProviderTest` covers the pure state/storage mechanism using a real
JCA AES-GCM wrapping fixture. `AndroidKeystoreLifecycleTest` uses unique test-only
aliases/files for real Keystore round-trip, historical reads, key loss, corruption
and interrupted atomic writes; it does not delete production aliases or files.

Required container tasks: `:core:security:testDebugUnitTest`,
`:core:security:lint`, `:core:security:assembleDebugAndroidTest`. Android runtime
tests require the host/device boundary. These tests alone do not qualify hardware
backing, OEM invalidation behavior, reboot, physical process-death durability, or
production command integration. Parent integration records executed results.
