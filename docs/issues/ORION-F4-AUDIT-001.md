# ORION-F4-AUDIT-001 — Functional exit and qualification scope review

OBJECTIVE: Audit the deterministic Core against Master Plan §10.11 and Architecture
§120, distinguishing functional phase exit from the post-MVP soak program and
release qualification. Verify existing evidence integrity and current regressions.

FILES ALLOWED: this work package, focused functional-audit report/evidence and
clarifications in F4-Closeout.md. No runtime, authority, schema or normative baseline edits.

CONTRACTS: Master Plan §§2/6.7/10.11/11.9/31/32; Architecture §§33/118–121;
ADR-0001. INV-001/004/006/007/008/014/015/016/018/019/021/022/023/027/028/029/030/032.

TESTS: Fresh canonical Docker check/build/lint/static/native validation and all
host harness contracts; verify existing physical evidence archive SHA256/CRC;
verify current test/source/artifact identities and retain historical differences.

ACCEPTANCE: Every functional criterion has scoped evidence. No short/emulator run
becomes a physical24-hour or complete native-release approval. Distinguish permission
to start governor-first F5 implementation from permission to enable Cortex.

OUT OF SCOPE: Waiving stability/native/API qualification, changing architecture,
starting inference, promoting readiness or issuing a release tag.
