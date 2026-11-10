# ORION-AUDIT-REMINDER-001 — Verify exact pending reminder display

OBJECTIVE: Resolve the audit's first finding by testing the existing full
date/time/offset/zone projection through durable pending reads and UI recreation.
The audit missed the formatting already performed by OrionReminderKernel.pending;
no production behavior change is required for that finding.

FILES ALLOWED: reminder kernel/display/Compose instrumentation tests and this issue.

CONTRACTS INVOLVED: existing normalized reminder creation/snooze payload, exact
confirmation binding, pending read projection, Activity recreation.

INVARIANTS INVOLVED: INV-019, INV-021, INV-022, INV-023.

TESTS REQUIRED: same-title different-time requests remain distinguishable after DB
reopen; snooze shows its exact new time; DST overlap offsets and milliseconds stay
visible; Activity recreation retains the exact pending display before confirmation.

ACCEPTANCE CRITERIA: displayed times match persisted normalized payloads, including
zone/offset/milliseconds; read/recreation creates no consent or domain mutation.

OUT OF SCOPE: confirmation semantics, schema, scheduling, Cortex and qualification.
