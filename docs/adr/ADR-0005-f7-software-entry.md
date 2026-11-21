# ADR-0005 — Entrada de software da F7 com qualificação física adiada

**Status.** Aceito para o escopo solicitado pelo usuário ao instruir
“beleza, aplique as correções. fecha f6 e inicia f7.” A decisão segue a
distinção entre fechamento de software e qualificação discutida na auditoria.

## Decisão

Após validar as correções da F6 e registrar a revisão de software do
gate cognitivo (§12.7 do Plano Mestre V1.2), iniciar a F7 em pacotes separados,
começando por contratos e proveniência de memória. A entrada pode ocorrer
localmente enquanto integração remota, qualificação física, estabilidade e
perfil de produção seguem pendentes.

Esta decisão substitui somente a exigência de aguardar a aprovação dos
gates de qualificação ainda abertos para **iniciar a implementação de software
da F7**, no sequenciamento geral do Plano Mestre §2. O ADR-0004 continua
regendo a exceção de entrada da F6 e preserva o escopo de F5.

## Critérios preservados

- CI obrigatório verde antes de merge. Qualificação da F4/F5, matriz nativa
  física, S21, MemoryLimiter em API 37, perfis de produção e soaks de 24 horas
  mantêm seus critérios e seus estados.
- Regras de saída das fases mantidas. A entrada de software não declara
  CORTEX_RUNTIME_READY, MEMORY_ENGINE_READY, estabilidade ou release.
- MAIN permanece autoridade sobre dados, política, memória e ações. Ingresso,
  confirmação, criptografia, limites de IPC e todos os invariantes seguem
  vigentes. A F7 não depende de habilitar inferência em produção.

Referências: [Arquitetura V3.2](../../1-orion.md),
[Plano Mestre V1.2](../../2-plano-mestre.md),
[ADR-0004](ADR-0004-f6-development-before-physical-qualification.md),
[plano F7](../plans/F7-ImplementationPlan.md).
