# Qualified passive monitoring — scoped validation, 2026-11-17 UTC

Collector source: `4c987d761c875a4259c113098724f4d569f8d21d`, based on `5cb524638169252549fd7ea46e15b750ff31e1f9`. Runtime APP source: `3208ace77fbbf3b58b0b91bc89e6df8a9091c6e6`. This documentary refresh changes no collector, APP, profile, model, policy or qualification gate. It corrects the producer method-name reference to `OrionFoundationRuntime.performMaintenance`.

The [evidence index](f5-qualified-monitoring-evidence-20261117.json) binds source hashes, separate run scopes, original-file hashes and the verified [minimized archive](artifacts/f5-qualified-monitoring-evidence-20261117.tar.gz). Original local files remain retained; minimized derivatives are explicitly not raw executable runtime/maintenance protocols. The archive excludes APKs/models/images/private keys/raw serials, hashes raw correlation/session identifiers and removes home/installation paths from text. Original SHA-256 values remain available for exact external-file comparison. The member manifest was checked against every extracted member.

## Separate execution snapshots

| Snapshot | Environment | Recorded result |
| --- | --- | --- |
| Qualification-selection RED: resource collector | DOCKER/HOST synthetic fixtures | 4 tests / 5 errors including subtests, EXIT1; old API could not select the package. |
| Qualification-selection RED: maintenance collector | DOCKER/HOST synthetic fixtures | 3 tests / 3 errors, EXIT1; retained before implementation. |
| First resource/maintenance focused runs | DOCKER/HOST synthetic fixtures | 35 cases in 4.360s and 13 cases in 0.076s, separately; before the final UID-bracketing controls. |
| Final canonical full HOST invocation | DOCKER/HOST synthetic fixtures | **629 tests in 46.260s**, EXIT0, zero failures/errors/skips; four source/test hashes frozen and checked before/after. |
| Short actual collector mechanics | ANDROID_EMULATOR, API35/x86_64/4KiB | Driver EXIT0; selected target, actual PSS, unavailable storage, log filtering and owned teardown checked. Results below retain their actual limitations. |

These runs overlap and are not added to a global unique-case total. The separate pre-#165 619-case HOST snapshot and #163's JVM/Android selections belong to their own evidence package; they are not reruns performed by this documentary update.

## Actual qualified target and limitations

The root driver explicitly installed the owned qualification app/test pair and launched the target **before** the passive collectors. This preparation is an intervention, recorded separately from collection. The principal `network.orion` installation was preserved and coexisted with `network.orion.qualification` under distinct UIDs: 10148 and 10150. The qualification installed APK matched local SHA-256 `cd29083de32e4d844338a0f670cc2648bca653885c06a9a4f587feb332869ca1`; the principal APK SHA-256 was `368f0ad395857fe18747e33bf527af273bb54c97837eae4883fbe70c202b97cb`.

The resource collector recorded two actual qualified-Main memory samples with matching selected package/PID/UID and unchanged endpoint identity. Its requested six-second window ended at 9.367 sampled seconds, with no cadence gaps. Both samples retained `privateStorage` unavailable because the qualification APK is non-debuggable. The resource result remains **INCOMPLETE**; no bypass, zero substitution, principal-package fallback or debug flag change was used.

The same-UID maintenance collector recorded two polls over 6.413 sampled seconds. Three existing baseline records carried the actual twenty-field protocol, including databaseBytes and queue/retention/reminder counters; there were **zero first-seen subsequent records** and three duplicate observations. The result is **HISTORY_COLLECTION_COMPLETE_REVIEW_REQUIRED**, not measured queue/database growth, retention progress or autonomous heartbeat delivery. An empty table's actual zero count is not an inferred value for inaccessible private storage.

The command inventory contains only passive collector queries. Neither observer installed/launched the app, caused work/maintenance/recovery, changed settings or replayed an UNKNOWN action. The root verified ownership before stopping only its emulator; recorded exit code is zero. No physical device/model load, MemoryLimiter incident, normal-background autonomy, continuous coverage or real 24h result was obtained.

## Independent review and current integration state

The independent targeted audit found one original P2 (wrong fixed package/UID) resolved by the frozen #165 sources and no new demonstrable P1/P2 in its reviewed scope. It reviewed source and recorded evidence; it did not execute the canonical suites, Android driver, current CI or an exhaustive repository proof. Its original Markdown/JSON hashes and minimized copies are indexed separately.

At the 2026-11-17 UTC refresh, #151/#153/#158/#159 are integrated through PRs #156/#157/#162/#164. #160/PR #166 merged as `ee0b5d8b429dcbac2f57f13273f18b416e05d9eb` after exact-head `ad259082e97b55a44c42a347a3d84cca18b37708` [CI 43995351105](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/43995351105) succeeded. Both the earlier IN_PROGRESS observation and the later successful/merged GitHub snapshot remain separate archive members. That success does not approve this collector/documentary revision or every F5 package.

#163/#161 are **locally validated and published in PR #167, with required CI/integration pending**; #165 remains **locally validated pending publication/integration/required CI** in this snapshot. The exact PR #167 head `d537b7277b4f2dc8ad571416a213373e82477990` was OPEN/BLOCKED and its [CI 44002282709](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/44002282709) was IN_PROGRESS at 2026-11-17T02:22:16Z. The separately frozen #163 documentary package is local commit `c0606ecc15443b3b90637ccf2e16d31398c63502`; its `F5-ExitPolicyManualValidation-20261116.md`, `f5-exit-policy-manual-evidence-20261116.json` and archive have their own verified hashes in this index and are not copied/relabelled here. Runtime source `3d34e35` is distinct from the whole-tree-matching candidate `2d0b6a2`. Its fresh 118 START/OK and two R8 OPEN controls do not erase the earlier 117 PASS/1 fixture failure, and its NO_QUALIFIED_PROFILE/owner-call-false controls prove no configured factory admission or native-model work.

The [current checklist](F5-QualificationChecklist.md) and [runtime gate](F5-RuntimeGate.md) separate prepared software, integration/CI and physical qualification. General software preparation is not declared complete. Production configuration stays absent; CORTEX_RUNTIME_READY, F4/F5/full-soak/F6, source authentication, measured profiles and physical/24h/autonomy approval remain false/open.
