# S21 F2 transaction-core physical gate review

Scope: [ORION-QUAL-S21-F2 / #23](https://github.com/otavio-esteves/O.R.I.O.N/issues/23),
Samsung SM-G991B, Android15/API35, arm64, 4 KiB pages. The user resumed physical
qualification on 2026-11-07 after the historical 2026-11-04 deferral.

The target-specific physical obligations have supporting evidence:

| Obligation | Physical evidence |
| --- | --- |
| Canonical build/source/APK identity | Original and runtime-fix reports record Docker commands, commit/source hashes and exact APK identities |
| Real Keystore lifecycle/failure behavior | Four physical Keystore cases passed, zero failures/skips |
| Persistence, reopening and atomic rollback | Full physical application suites passed; the latest rerun passed 319 cases without skips |
| Actual transaction/external-claim death | Seven real run-as SIGKILL scenarios passed, including conservative reminder delivery uncertainty |
| Installed version replacement | Physical 4→5 install replacement passed with original ciphertext, keys and queues; PENDING/UNKNOWN never replayed |
| Process recreation and permitted Force Stop return | Physical recreation plus fresh public primary startup evidence, real notification and conservative recovery passed |

The installed 4→5 replacement was executed against the original F4 app/test pair
recorded in [the initial physical report](f4-s21-physical-api35-20261107.json).
The latest runtime fixes use a different main APK, recorded in
[the follow-up report](f4-s21-fixes-physical-api35-20261107.json). This review does not
claim that the whole app source or all artifact hashes are identical between those
runs. The storage, crypto, migration, action, ingress and clock scopes are unchanged
between original commit `4253378` and merged head `705b8e7`; the new post-recovery
maintenance/startup behavior has its own seven-death and six-lifecycle physical
proofs. No database downgrade, uninstall or destructive reset was used to repeat an
older upgrade artificially.

Decision: the S21/API35 transaction-core physical evidence supports resolution of
issue #23 after this review is merged through required CI. This is not approval of
F4, a 24/7 promise, broader API/OEM behavior, Cortex/voice, or physical arm64/16 KiB
runtime. Those obligations retain their own gates.
