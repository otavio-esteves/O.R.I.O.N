# ORION-F4-SOAK-001 — Bounded passive soak observations

OBJECTIVE: Prepare a host-only, read-only technical observer for the F4 recovery
soak while the physical target is unavailable. Completion of collection never
closes the soak or phase gate automatically.

FILES ALLOWED: scripts/android/record-soak-observation.py and its host contract
tests; compose.yaml; the existing host-contract CI step; related qualification docs.

CONTRACTS INVOLVED: Architecture V3.2 §118; Master Plan §31; ADR-0001;
AGENTS.md §§29–32. ADB remains on the host; Docker runs device-independent tests.

INVARIANTS INVOLVED: INV-001/011/029/030/032/035. No Room opening, content export,
automatic revival, restriction bypass or substitution for physical evidence.

TESTS REQUIRED: Fake-ADB end-to-end collection and interruptions; device/APK
identity mismatch; unsupported/malformed metrics; bounded sampling and monotonic
duration; privacy filtering; all existing host contracts in a pinned Docker image.

ACCEPTANCE CRITERIA: Record exact installed base APK identity, declared environment,
bounded technical samples and explicit observation gaps. Never change settings,
launch/kill/install the app, or declare PASS from elapsed time. Preserve interrupted
evidence and refuse existing evidence directories. Existing app/APK stays untouched.

OUT OF SCOPE: Executing a physical soak now; crash-free or delivery guarantees from
sampled snapshots; queue/UNKNOWN counts or Room inspection; adding runtime APIs;
new AI/voice phases; closing F4 or claiming 24/7 readiness.
