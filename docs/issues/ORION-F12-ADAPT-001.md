# ORION-F12-ADAPT-001 — Personalização local e recomendações adaptativas

**Objetivo.** Aprender faixas horárias de receptividade por feedback do
Sentinel e dar mais peso às memórias recentes e importantes na busca.
Usar consentimento explícito, local e reversível em Ajustes.

**Arquivos.** Sentinel, busca de memórias, Room e migração, composição do
MAIN, ajustes e diagnóstico, testes, suíte Android no CI e qualificação.

**Contratos.** Arquitetura V3.2 §§74–80 e 128, Plano Mestre §18 e ADR-0010.
Preservar INV-001, INV-004, INV-007, INV-008, INV-009, INV-010, INV-011,
INV-018, INV-019, INV-021, INV-022, INV-023 e INV-035.

**Testes.** Migração Room 12→13, consentimento padrão/opt-in/revogação,
novas amostras por período de consentimento, feedback de uma sugestão
contado uma vez, limite temporal, fallback com dados escassos, ranking
com cursor, bloqueios de proatividade e execução em emulador.

**Aceite.** Personalização começa desativada. A F12 usa somente metadados
já elegíveis de memória e feedback posterior à adesão. Norma de ranking
é estável na paginação e a proatividade adaptativa pode adiar sugestões
permitidas pelo Sentinel.

**Fora do pacote.** Alteração do Policy Engine ou dos contratos Android,
Cortex, wake word, calibração em hardware e promoção de prontidão física.
