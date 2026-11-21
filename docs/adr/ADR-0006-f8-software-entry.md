# ADR-0006 — Entrada de software da F8 com qualificação física adiada

**Status.** Aceito para o escopo de software solicitado pelo usuário ao
instruir “inicia e termina a f8, de ponta a ponta” e adiar os testes físicos
no Galaxy S21.

## Decisão

Usar o [fechamento de software da F7](../qualification/F7-SoftwareCloseout.md)
integrado à `main` para iniciar, validar e integrar o software da F8 conforme
o Plano Mestre §14. A F8 pode prosseguir enquanto a qualificação física, a
estabilidade de longa duração e a calibração dos perfis de produção permanecem
abertas.

Este ADR substitui apenas a exigência de aguardar esses gates para iniciar
e integrar software da F8 no sequenciamento do Plano Mestre §2. Os ADRs
[0004](ADR-0004-f6-development-before-physical-qualification.md) e
[0005](ADR-0005-f7-software-entry.md) mantêm seus escopos.

## Critérios preservados

- O CI requerido deve passar antes do merge. O gate de software da F8 precisa
  de evidências por critério, com build, testes de contratos e cenários Android
  adequados registrados por ambiente.
- A qualificação da F4/F5, o Galaxy S21, a matriz física nativa/API, o
  MemoryLimiter em API 37, os perfis de produção e os soaks de 24 horas mantêm
  seus critérios. O fechamento de software por este ADR não declara
  `CORTEX_RUNTIME_READY`, `MEMORY_ENGINE_READY`, `SENTINEL_READY`, estabilidade
  ou release.
- MAIN continua dono de contexto, agenda, Room, política, ações e feedback.
  Propostas do Sentinel passam pelas regras existentes de ingresso, autorização,
  efeitos externos e recuperação. Perfis de recursos preservam a admissão
  conservadora do Cortex e os mecanismos de proteção contra relançamentos.

Referências de implementação na [Arquitetura V3.2, §§14, 33–35 e 75–80](../../1-orion.md#14-sentinel)
e no [Plano Mestre V1.2, §14](../../2-plano-mestre.md#14-fase-8--sentinel--resource-governor-completo).
