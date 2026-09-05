# [ORION-FND-003] Implementar vocabulário compartilhado de core:model

## ORION ID

ORION-FND-003

## Objective

Implementar em `core:model` o vocabulário compartilhado já definido pela
Arquitetura V3.2, sem comportamento de autorização, retry ou persistência.

## Context

ORION-FND-002 criou os módulos base. O Plano Mestre §42 atribui `core:model` à
FND-003 e reserva Command/Event/Query, tempo e health às FND-004/005/006.
A seleção conservadora desta tarefa usa as taxonomias explícitas da arquitetura:
§53 RecoveryStrategy, §59 AuthorizationLevel, §69 Sensitivity, §97 AgentState,
§98 ErrorCategory e §102 SideEffectClass/IdempotencyMode.

## Files / modules allowed

- `core/model/build.gradle.kts`.
- `core/model/src/main/kotlin/network/orion/core/model/**`.
- `core/model/src/test/kotlin/network/orion/core/model/**`.
- Esta documentação da issue.

## Contracts

- `AgentState`: estado operacional efêmero derivado; não é verdade persistente.
- `ErrorCategory`: categoria e operação determinam a possibilidade de retry;
  a categoria isoladamente não concede retry.
- `AuthorizationLevel`: classificação declarativa, sem hierarquia inferida por ordinal.
- `RecoveryStrategy`: estratégia declarada pela Skill, sem executor nesta tarefa.
- `SideEffectClass` e `IdempotencyMode`: metadata, sem concessão de autoridade.
- `Sensitivity`: classificação compartilhada, sem implementar proteção de payload.
- Valores e grafia conforme as seções da arquitetura citadas acima.

## Invariants

INV-005/006: ambiguidade externa permanece representável sem retry genérico.
INV-009/018/031: metadata não substitui policy, capabilities e descriptor.
INV-011/035: sensibilidade é explícita; o enum não implementa criptografia ou logging.
Nenhum desses comportamentos de runtime é declarado implementado por esta tarefa.

## Required tests

- Testes de contrato com os conjuntos exatos de valores da baseline para os sete tipos.
- Testes de contrato executados em Docker, comprovando taxonomias sem valores
  ausentes, adicionados inadvertidamente ou renomeados.
- `docker compose run --rm android-build :core:model:testDebugUnitTest :core:model:lint`.
- `docker compose run --rm android-build check assembleDebug assembleRelease`.
- `git diff --check` e revisão de ausência de imports Android e dependências de produção.

## Acceptance criteria

- Sete enums públicos Kotlin no namespace `network.orion.core.model`.
- Valores e documentação alinhados à baseline, sem inferir ordem de autorização,
  retry automático, persistência, serialização ou regras de policy.
- Código de produção sem imports Android e sem dependências em outros módulos.
- Testes, lint, build e CI obrigatório aprovados antes do merge.

## Out of scope

OrionEvent/OrionIntent/Command/Query; OrionClock/BootSessionId; CoreReadiness/
OrionHealth; SkillDescriptor e políticas compostas; CapabilityRegistry; entidades
Room e modelos específicos de tarefas/memórias/lembretes; ActionRequest e máquina
de estados; serialização IPC/persistência; qualquer comportamento de runtime.

## Dependencies / blockers

ORION-FND-002 integrada em `219f2d6`; CI de main aprovado no run `33974116750`.
Nenhum bloqueio conhecido. F0 física continua separada.

## Evidence required

Commit, comandos, resultados de testes/build em `CONTAINER` e link do CI no PR.
Inspeção de fontes/diff em `HOST`. Não constitui qualificação de dispositivo.

## Process death / security / migration

Sem novo estado ou efeito durável. AgentState deve continuar derivado após morte
do processo. Não adicionar logs ou valores padrão que reduzam a classificação
recebida. Sem schema, migração, componentes Android ou mudanças arquiteturais.
