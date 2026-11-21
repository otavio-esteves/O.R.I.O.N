# ORION-INGRESS-DIAGNOSTICS-001 — Diagnóstico de ocupação do ingresso

**Objetivo.** Mostrar ao usuário ocupação total, limite e causa da recusa
quando a fila durável atinge a capacidade, mesmo com zero itens a processar.

**Arquivos.** Limite existente do DAO de ingresso, projeção de diagnóstico em
`core/health` e `app/tasks`, tela de diagnóstico e regressões relacionadas.

**Contratos e invariantes.** Arquitetura §§48.1 e 112, AGENTS §§29–30,
INV-008/019/023/035. Leitura somente de contagens. Os comprovantes retidos
continuam contando para o limite e preservam a deduplicação.

**Testes e aceite.** Comprovantes consumidos sintéticos enchem a tabela com
zero entradas processáveis. Um novo pedido é recusado com
`CAPACITY_EXCEEDED`; o diagnóstico reporta zero na fila, ocupação total,
limite e saturação. A UI explica o bloqueio de novas admissões.

**Fora do escopo.** Alterar o teto, retenção, autoridade, envelhecimento,
migração, política de descarte ou o comportamento da deduplicação.
