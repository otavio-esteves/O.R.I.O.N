# ORION-AUDIT-F12-002 — Revogação e recuperação de voz

**Objetivo.** Corrigir a revogação sob saturação, a retenção de transcrições
terminais e a indicação de revisão de uma tarefa preparada por voz.

**Arquivos.** Sentinel, ingresso de voz, DAOs de ingresso, retenção e eventos,
projeção dos eventos, integração de recuperação e regressões Android.

**Contratos.** Ingresso durável, Startup Barrier, Domain Event Outbox,
idempotência e VoiceCore IPC v1. INV-007, INV-008, INV-012, INV-019,
INV-021, INV-023 e INV-035.

**Testes e aceite.** Saturar o ingresso e o outbox, repetir a revogação
durante recovery e simular falha de gravação do evento. A revogação prevalece
sobre pedidos de opt-in ainda na fila e grava o evento na mesma transação.
Uma nova instância do ingresso de voz recupera a indicação de confirmação
pela ação persistida. Consumo, rejeição, expiração e cancelamento eliminam
a transcrição e preservam a deduplicação. A manutenção limpa os envelopes
terminais legados em lotes.

**Fora do pacote.** Alterações de esquema, AIDL, confirmação da tarefa,
qualificação física e empacotamento de release.
