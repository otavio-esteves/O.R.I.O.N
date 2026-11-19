# ADR-0004: Begin F6 software development before physical qualification

- Status: **ACCEPTED — explicitly authorized by the user on 2026-11-18**
- Date: 2026-11-18
- Decision record: In this conversation the user instructed: “faça as correções
  de f5 e parta para f6, deixamos os gates físicos para depois”.
- Scope: Development sequencing only; defer physical qualification while beginning
  bounded F6 software work after the diagnostic corrections.
- Supersedes: Only the prerequisite that *all F6 development* wait for physical F5
  qualification, as expressed in ORION-F5-QUAL-001's acceptance sentence “Only then
  declare CORTEX_RUNTIME_READY and begin F6”, its prohibition on implementing F6
  while that gate is open, and the corresponding F6-development status statements
  in F5-RuntimeGate/F5-QualificationChecklist. In that acceptance sentence only
  “begin F6” changes; CORTEX_RUNTIME_READY keeps its existing qualification rules.

## Context

The normative baseline is [Architecture V3.2](../../1-orion.md) and
[Master Plan V1.2](../../2-plano-mestre.md), including its phase-exit rule in
section 2 and F6 definition in section 12. Only the requirement that physical
qualification precede beginning F6 software development is deferred; no phase is
declared completed without its exit criteria. The deterministic functional F4 exit
is already recorded in F4-FunctionalAudit.
F5 has software implementation and scoped container/emulator evidence, while
physical S21/resource/API37/native and real-duration stability obligations remain
open under #64/#89. The user now explicitly changes the development sequence.
AGENTS section 1 gives accepted ADRs precedence only over their stated clauses.
This decision records that narrow exception before implementing F6 code.

## Decision

1. Correct the two diagnostic findings with focused regression tests first.
2. Permit bounded F6 implementation and container/emulator validation in dedicated
   work packages before the deferred physical qualifications are complete.
3. Start with deterministic Intent Router interpretation using existing immutable
   command/query contracts. A routing result is a proposal, never execution,
   confirmation, a Skill permission or a ResourceGovernor permit.
4. Keep physical and stability work explicitly OPEN, with their original required
   evidence and failed/partial attempts intact. Neither software completion nor
   this sequencing decision is qualification evidence.

## Unaffected obligations

ADR-0001's Docker/HOST/physical evidence boundary, ADR-0002/0003 and every
INV-001 through INV-036 remain binding. No process, database, IPC, persistence,
recovery, confirmation, ingress, scheduling or Policy Engine contract changes.
The first F6 package introduces no new executor, Cortex binding or model loading.
Unmatched text may be marked as a cognitive candidate; that does not start work.

Production qualified model/resource configuration remains absent. This decision
does not authorize invented limits, an unqualified production profile, bypassing
ResourceGovernor, generic retries of UNKNOWN, CORTEX_RUNTIME_READY, stable release,
continuous-operation claims or automatic F6 completion. A future configured
Cortex path still needs fresh resource/restart decisions and reviewed inputs.

The existing S21 hardware measurements, actual API37 MemoryLimiter policy response,
complete applicable native/API matrix and real 24-hour/stress obligations remain
required before their respective readiness/stability/profile approvals. The user
has deferred their execution, not converted their status to PASS.

## Implementation and review

Each F6 work package must state its bounded objective, allowed files, contracts,
invariants, tests, acceptance and exclusions. Normal dedicated-branch/PR/required-CI
rules continue to apply. Tests must distinguish deterministic interpretation from
policy/authorization, preserve command/query separation and fail safely on invalid,
partial or untrusted command input. Subsequent F6 increments must follow Master
Plan sections 12.3–12.7 and the unaffected architecture, with no additional
authority or structural changes inferred from this sequencing exception.

References: [F4 functional exit](../qualification/F4-FunctionalAudit.md),
[F5 qualification contract](../issues/ORION-F5-QUAL-001.md),
[F6 first work package](../issues/ORION-F6-ROUTER-001.md).

## Authorized software completion scope (2026-11-18)

The user subsequently instructed “tenta finalizar f6 (de ponta a ponta ignorando
aparelho físico)”. Continue the software increments through Master Plan §§12.3–12.7
and record software gate evidence separately. This expands the implementation
work, not the sequencing exception: production resource profiles, physical
qualification and CORTEX_RUNTIME_READY remain governed by the unaffected rules.
See ORION-F6-HYBRID-001.
