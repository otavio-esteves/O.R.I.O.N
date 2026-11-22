# ORION-AUDIT-F12-001 — Auditoria após a F12

**Objetivo.** Verificar as fronteiras de armazenamento, autorização e
recuperação e corrigir desvios na busca, no ranking, na manutenção do Sentinel
e na prova de seleção do assistente.

**Arquivos.** Busca e ranking de memórias, entrega do Sentinel, testes e
seleção do runner, evidência de qualificação.

**Contratos.** Arquitetura V3.2 §§74–80, Plano Mestre §§18 e 29, INV-009,
INV-011, INV-014, INV-019 e INV-023.

**Testes.** Entrada obsoleta de índice sem opt-in, revalidação do mesmo
alvo para o chat, recência com a confiança padrão das memórias declaradas,
expiração durante e após recuperação e execução na matriz Android disponível.
A prova do assistente observa contagens duráveis sem competir pelo escritor.

**Aceite.** Busca e chat validam o opt-in na cópia autoritativa. Rascunho
expirado permanece íntegro durante o bloqueio de recuperação e é
cancelado uma vez após a liberação.

**Fora do pacote.** Mudanças no contrato do índice ou da Startup Barrier,
no grafo de processos ou nos critérios dos gates físicos.
