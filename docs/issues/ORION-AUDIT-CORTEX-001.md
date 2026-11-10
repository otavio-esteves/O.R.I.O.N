# ORION-AUDIT-CORTEX-001 — Sustained bounded Cortex admission

OBJECTIVE: Remove lifetime request saturation using the explicitly accepted
ADR-0002, without evicting active work, replaying retired requests or restarting AI
to clear counters.

FILES ALLOWED: ai/api issued-history and optional Cortex IPC extension, ai/service
request bookkeeping, MAIN Cortex client/owner/ledger, related tests and documents.

CONTRACTS INVOLVED: ADR-0002; Cortex protocol major 1 with negotiated minor 1
issued-request-history capability; logical MAIN identity distinct from transport
identity; explicit fresh-request rebind; monotonic deadlines and resource approval.

INVARIANTS INVOLVED: INV-009, INV-011, INV-012, INV-013, INV-019, INV-027,
INV-028, INV-033, INV-036.

TESTS REQUIRED: thousands of terminal requests; bounded live work/results; retired,
foreign, never-issued and changed-payload rejection; live holes below watermark;
duplicate/cancellation/timeout/queue/close/death races; legacy protocol isolation;
real Binder admission past 64 statuses and real-model cycles beyond 32.

ACCEPTANCE CRITERIA: fresh requests continue after history fills; retired IDs never
start work or acquire another terminal state; existing active and retained duplicate
requests preserve their results; native/MAIN process ownership and fresh governor/
restart checks remain mandatory. Missing extension fails before heavy IPC work.

OUT OF SCOPE: schema/persistence, F6/Skills, production inference profiles, resource
calibration, physical gate closure, merge/publishing.

## Legacy handshake correction

OBJECTIVE: Restore the protocol-1.0 handshake promised by ADR-0002. Its strict
client rejects extra initial-reply keys, so capability discovery uses an appended
extension while the original reply and transaction numbers stay compatible.

FILES ALLOWED: Cortex AIDL/service/MAIN client, related Binder/client/minified
qualification tests, this issue and the ADR-0002 wire clarification.

CONTRACTS INVOLVED: Existing ADR-0002 optional capability negotiation; original
protocol-1.0 reply envelope and transaction numbers; minor-1 issued history.

INVARIANTS INVOLVED: INV-012, INV-013, INV-027, INV-028, INV-033. No schema,
persistence, recovery authority, or production resource-profile change.

TESTS REQUIRED: A frozen raw Binder 1.0 client validates the original strict
handshake, cancels and reads status using original transaction numbers. New MAIN
discovers capabilities separately, skips extension calls for incompatible peers,
and rejects missing/malformed/unavailable capabilities before issuing IDs or heavy
work. Existing Cortex lifecycle cases and minified raw Binder/JNI probes remain
required, along with canonical JVM, lint, dependency and packaging checks.

ACCEPTANCE CRITERIA: Legacy handshake and control calls succeed; new clients use
the issued-history path only after successful capability negotiation; payload
guards remain strict and existing transaction IDs remain unchanged.

OUT OF SCOPE: Other pending audit fixes, the long real-model cycle qualification,
physical gate closure, production inference enablement, commits and publishing.

## Sustained native fixture calibration

The 33-cycle acceptance case uses the same 512-token context as the existing
real-model fixture plan. The pinned llama.cpp rounds contexts up to multiples of
256; requesting 128 correctly trips the native guard against allocating a larger
context than approved. This corrects the test configuration, preserving the guard,
all 33 iterations, both PID checks, one terminal result per request, fresh resource
approval, and the original deadlines. It grants no production profile approval.
