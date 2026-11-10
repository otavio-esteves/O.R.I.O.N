# ORION-AUDIT-RETENTION-001 — Preserve delivered reminder history after pruning

OBJECTIVE: A delivered reminder remains DELIVERED after eligible terminal action
retention deletes its historical ActionRequest. Preserve uncertain/legacy outcomes.

FILES ALLOWED: data/reminders read projection, related Android tests and this issue.

CONTRACTS INVOLVED: reminder database as source of truth, committed FIRED/lastFiredAt
metadata, bounded action retention. This is a read-only projection correction.

INVARIANTS INVOLVED: INV-005, INV-006, INV-015, INV-019.

TESTS REQUIRED: original confirmed owned delivery followed by real retention;
read after DB reopen; retained UNKNOWN remains UNCERTAIN without reinvocation;
missing action without committed matching delivery evidence never becomes DELIVERED.

ACCEPTANCE CRITERIA: pruning changes no reminder state/content and historical
delivery remains visible. Queries never confer execution or confirmation authority.

OUT OF SCOPE: schema/migrations, pruning policy, delivery execution and qualification.
