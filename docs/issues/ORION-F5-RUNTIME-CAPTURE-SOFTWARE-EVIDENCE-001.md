# ORION-F5-RUNTIME-CAPTURE-SOFTWARE-EVIDENCE-001 — software evidence closeout

Tracking: [issue #154](https://github.com/otavio-esteves/O.R.I.O.N/issues/154).
Documentary baseline: main `dc130a0e0728e50cc2da99f24d79aa2c4be4668e` on
2026-11-16. This package records existing checks and integration; it runs no workload
and grants no runtime, profile or phase approval.

OBJECTIVE: Preserve immutable software evidence for integrated PRs #147/#149/#150/
#152 and reconcile the F5 checklist with delivered components and remaining work.

FILES ALLOWED: This note; [external index](../qualification/f5-runtime-capture-software-evidence-20261116.json);
[archive](../qualification/artifacts/f5-runtime-capture-software-evidence-20261116.tar.gz);
focused updates to [F5-QualificationChecklist](../qualification/F5-QualificationChecklist.md).
Sources, tests, protocols, earlier artifacts and other worktrees remain outside
this documentary package.

CONTRACTS: AGENTS environment/CI/gate rules, [capture contract #141](../qualification/F5-RuntimeCaptureContract.md)
and immutable audit supplements. INV-011/032/035 and phase sequencing apply.

TESTS REQUIRED / ACCEPTANCE: Check all staged and archived SHA-256 values, exact
source/test identities, JUnit inventories, raw Android method/status counts,
installed-hash bindings, safe archive paths and local links. Preserve failed
controls and distinguish local historical sources from later integration CI.
No Gradle, ADB, functional test execution, APK/model reading or binary inclusion.
Required CI for this documentary PR remains mandatory before merge.

## Captured scope

The archive preserves all 224 staging payload entries and adds the exact PR #152
integration record and a final snapshot summary. It has 226 manifest-listed payload
files plus the manifest itself, all regular relative members; no symlinks, path
traversal, APKs, models, device disks, keystores or physical-target data. Original staging
and prior frozen repository artifacts remain unchanged. Historical notes and raw
staging metadata retain their old PENDING text; the external index and final
integration record state current integration without rewriting those snapshots.

Docker JVM invocations are separate and overlap:

| Source package | Cases / suites | Failures / errors / skips |
| --- | --- | --- |
| #144 observation foundation | 309 / 40 | 0 / 0 / 0 |
| #146 Lifecycle event source | 304 / 38 | 0 / 0 / 0 |
| #148 Client/Owner capture | 316 / 39 | 0 / 0 / 0 |

The #142 canonical Docker HOST harness passed 462 cases, including 426 existing
cases. Its earlier 462-case invocation reproduced four interruption/ownership
failures and remains a failed RED control. These counts are not additional unique
coverage and are never added across invocations.

Actual #148 framework execution used an owned API35/x86_64 emulator with 4096-byte
pages, four vCPUs and 3072 MiB RAM. The final selection has 76 START/OK records,
zero ignores/assumptions, comprising 64 existing and 12 new controls. Source clocks
and endpoints are synthetic. RED records one expected turn-fence failure; RED2
records 11 cases with ten pass and one expected disconnect-fence failure. GREEN
passes both regressions. Those failed invocations remain separate historical
controls, not release failures or additional green totals. Owned emulator-stop
records and source/local/installed APK hashes are retained; no APK bytes were read
or copied for this package.

Local final #148 source is `c4687b620b692fbef910fdddb9e826ae52e030fd`.
Its eight source/test hashes match PR #152 head
`45488c816353626b38e0ea38a73783782e9a8a73` and integrated main. Patches over public
`52dea25b8d54d3594ef216dd440cef7dbcf7014d` reconstruct RED, RED2 and GREEN relevant
files. The source-time note and postvalidation candidate note are separate.

Exact-head integration CI is recorded independently:

- [PR #147](https://github.com/otavio-esteves/O.R.I.O.N/pull/147): [CI SUCCESS](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/43791626025/job/133051128950).
- [PR #149](https://github.com/otavio-esteves/O.R.I.O.N/pull/149): [CI SUCCESS](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/43794950176/job/133061523783).
- [PR #150](https://github.com/otavio-esteves/O.R.I.O.N/pull/150): [CI SUCCESS](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/43798859089/job/133073898884).
- [PR #152](https://github.com/otavio-esteves/O.R.I.O.N/pull/152): [CI SUCCESS](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/43804706512/job/133092689370), completed 2026-11-16 04:58:09 UTC before merge to the documentary baseline.

## Integrity and limits

Archive SHA-256:
`158a0e0fb4eb0839b2d50d4b3125ef47007bdc79fb79123f29701874b221954d`.
Payload manifest SHA-256:
`f8452151ff1e27e515bf9521b72434a85a8d59d76290b95b982c6aa04c38fd12`.
External index SHA-256:
`953749fba940c07a5deeda5c678abbdbca01fbca0a35906a3d2d2f5075c9a24a`.
Every manifest entry and archive member was read back and verified. The archive
contains 227 safe regular members and is 422686 bytes. The external index binds
each payload file and the archive; the internal manifest excludes its own hash.
Local links and the four-file documentary diff are checked before commit.

Delivered #140/#141/#142/#144/#146/#148 correspond to PRs #143/#145/#147/#149/#150/
#152. Sampler #151 and encoder #153 remain IN_PROGRESS at this baseline. Complete
RuntimeEvidence v1 acquisition/render/export/HOST assembly, full heavy-work
executor/adapters and their integration are still incomplete. Default capture/
qualified plans remain absent/empty and no operational model/template is selected.
No physical devices were available at the last user confirmation on 2026-11-15.
No physical, native-model, API37, MemoryLimiter, calibrated profile, performance,
autonomy or real 24-hour evidence is added. F4/#64, F5/#89, soak and F6 gates remain
open; source authentication, profile/production/full-gate approval fields remain
false. This package changes no authority, recovery, persistence, IPC or ADR.

OUT OF SCOPE: Implementation, new runtime checks, operational parameter/model/
template choices, protocol/schema/ADR changes, hardware allocation or phase closure.
