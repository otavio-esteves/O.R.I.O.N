# ORION-F3-NOTIFICATION-001 — Explicit user notification posting

GitHub issue: <https://github.com/otavio-esteves/O.R.I.O.N/issues/53>.

OBJECTIVE: Provide a real user-facing PostNotification Skill with bounded title/body, exact human confirmation and an owned external execution attempt. This is an explicit manual post, never reminder scheduling or F4 delivery.

FILES ALLOWED: Notification namespaces in core commands/policy/orchestrator/metadata, skills/notifications, feature/notifications, app/notifications and related tests; narrow ActionDAO selected pending queries and module build registrations. Root owns FoundationRuntime/MainActivity/manifest permission wiring.

CONTRACTS INVOLVED: Encrypted durable ingress and ActionRequest; same operational StartupBarrier; exact payload/hash/confirmation receipt and original expiry; boot-bound persistent execution ownership; SkillDescriptor; NotificationManager capability and API-specific POST_NOTIFICATIONS permission; reference-only DomainEventOutbox.

INVARIANTS INVOLVED: INV-001/004/005/006/007/008/011/014/018/019/021/022/023/031/033/035/036.

TESTS REQUIRED: Pure Unicode/canonical bounds and redaction; policy/admission origin/exact binding and fresh observations; fake poster proves no DB transaction at notify, actual committed RUNNING ownership, permission/channel revocation before invoke, exact receipt mismatch/expiry, unique attempt and no UNKNOWN retry; terminal event atomicity; Android capability/active-post verification only with explicitly granted permission; UI exact title/body display and separate permission request callback.

ACCEPTANCE CRITERIA: Ingress may prepare/confirm but never invoke Android in a Room transaction. RUNNING claim/lease/audit commits before NotificationManager.notify. Only this bounded local claim holds the StartupBarrier admission monitor; Android invocation and proof run outside both Room and that monitor, so recovery gate closure remains responsive. Terminal owned metadata may finish after gate closure without dispatching a new effect. A trusted immutable per-attempt invocation permit is called by the platform adapter after notification construction and capability reads, immediately before notify. It revalidates actual barrier, policy, permission, channel/app capability, user presence, exact original confirmation and active execution ownership; expiry/lease checks occur after slow authority reads. Only an exact matching active notification after notify proves POSTED; ambiguous outcomes persist UNKNOWN and never auto-retry. Success terminal action+reference event commit atomically. Sensitive notification content is private on lockscreen with generic public representation. No raw content logs, arbitrary exported component or new schema.

IDEMPOTENCY: Producer retains requestId and idempotencyKey; notification.post scope/key binds canonical requestId/title/body for 90 days. Same key+different material conflicts. Actual Android tag is ActionRequest UUID with id0, preventing two records for the same action. New keys represent separate explicit posts. MANUAL_REVIEW recovery prevents automatic external retries; abandoned RUNNING external execution becomes MANUAL_REVIEW. No automatic dispatch of recovered confirmed actions.

OUT OF SCOPE: Scheduler, alarms, reminder delivery, FGS, proactivity, external integration/IPC, schema or authority changes and physical qualification. The OS permission request is an independent explicit Activity action and never fabricated by the Skill.

PROCESS-DEATH / SECURITY: Death after durable claim and before/after Android invoke leaves RUNNING for conservative MANUAL_REVIEW recovery. No uncertain outcome is automatically resent. Baseline REMINDERS/PROACTIVE/VOICE_SERVICE/SYSTEM_STATUS channels remain separate; manual user posts use a dedicated USER_NOTIFICATIONS channel. Root diagnostics report denied/unavailable notification capability. Original confirmation timestamps survive queue replay; fresh focused UI presence remains mandatory for an external invoke.

VALIDATION: Production, UI and permission/health wiring are integrated. Canonical
checks and actual Android/SIGKILL execution will run against the complete F3 graph.
No phase gate is claimed until those checks finish. Independent reviews resolved
late consent expiry and prohibited external invocation under the barrier monitor;
regressions cover both boundaries. Optional notification/permission observations
merge atomically into latest health, preserving concurrent storage failures and
lastRecovery; a deterministic concurrency regression covers that race.
