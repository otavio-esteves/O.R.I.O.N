# [ORION-FND-006] Definir contratos de health e readiness do Core

## ORION ID

ORION-FND-006

## Objective

Definir em `core:health` os contratos em memória para a prontidão observável do
Core e o snapshot mínimo de saúde, sem implementar bootstrap, recuperação,
persistência ou execução de ingressos.

## Files / modules allowed

- `core/health/**`
- esta documentação da issue.

## Contracts

- `CoreReadiness` modela `STARTING`, `RECOVERING`, `READY` e `DEGRADED`.
- A barreira de startup só pode estar aberta após `READY` ou `DEGRADED`.
- `OrionHealth` associa, de forma consistente, a prontidão a `UNAVAILABLE`,
  `HEALTHY` ou `DEGRADED`, com horário observado fornecido pelo chamador.
- O contrato não lê relógios de plataforma nem carrega diagnósticos sensíveis.

## Invariants

INV-014 e INV-023. O contrato torna o estado da barreira observável, mas não abre
a barreira operacionalmente, não drena ingressos e não executa efeitos externos.

## Required tests

- `STARTING` e `RECOVERING` mantêm a barreira fechada.
- `READY` e `DEGRADED` permitem a abertura da barreira.
- snapshots incompatíveis de readiness/health são rejeitados.
- `:core:health:testDebugUnitTest`, `:core:health:lint`, verificador de
  fronteiras e build completo no Docker.

## Acceptance criteria

Os contratos públicos existem em `network.orion.core.health`, podem ser usados
sem infraestrutura Android e não afirmam recuperação concluída, persistência ou
resiliência.

## Out of scope

`RecoveryCoordinator`, `IngressCoordinator`, Room/DataStore, health por
subsistema, diagnósticos, callbacks, IPC, retry, scheduler e efeitos externos.
