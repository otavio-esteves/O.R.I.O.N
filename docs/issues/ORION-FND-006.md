# [ORION-FND-006] Definir contratos de health e readiness do Core

## ORION ID

ORION-FND-006

Issue: https://github.com/otavio-esteves/O.R.I.O.N/issues/7

## Objective

Alinhar em `core:health` os contratos em memória de prontidão e saúde à Arquitetura
V3.2 §§48, 96 e 124 e ao Plano Mestre §§7.3, 7.8 e 10.7. A implementação anterior
omitia `BLOCKED` e usava `DEGRADED` como prontidão. O ADR-0001 aceito determina
validação no Docker e não altera esses contratos; esta correção não exige novo ADR.

## Files / modules allowed

- `core/health/**`
- esta documentação da issue.

## Contracts

- `CoreReadiness` modela `STARTING`, `RECOVERING`, `READY`, `DEGRADED_SAFE` e `BLOCKED`.
- Somente `READY` e `DEGRADED_SAFE` indicam elegibilidade para abertura da barreira.
  Isso não concede autorização nem dispensa recovery, policy, capabilities e permissões.
- `BLOCKED` representa invariantes centrais não garantidos e impede ações mutáveis.
- `OrionHealth` valida a matriz abaixo, com horário observado fornecido pelo chamador.
- O contrato não lê relógios de plataforma nem carrega diagnósticos sensíveis.

| CoreReadiness | Único OrionHealthStatus válido |
| --- | --- |
| STARTING | UNAVAILABLE |
| RECOVERING | RECOVERING |
| READY | HEALTHY |
| DEGRADED_SAFE | DEGRADED |
| BLOCKED | FAILED |

As classificações seguem §96. `FAILED` expressa o bloqueio do Core por invariantes
não garantidos; não implica falha permanente ou indisponibilidade de toda consulta.
O snapshot continua limitado ao Core, sem agregação de subsistemas.

## Invariants

INV-014 e INV-023. O contrato expõe apenas elegibilidade declarativa; não abre a
barreira operacionalmente, não drena ingressos e não executa efeitos externos.

## Required tests

- O vocabulário contém exatamente os cinco estados de prontidão da arquitetura.
- `STARTING`, `RECOVERING` e `BLOCKED` mantêm a barreira fechada.
- `READY` e `DEGRADED_SAFE` permitem a abertura da barreira conforme o contrato.
- A matriz completa de 25 combinações aceita cinco e rejeita vinte, inclusive
  `BLOCKED` com `DEGRADED`; cópias do snapshot também respeitam a validação.
- `:core:health:testDebugUnitTest`, `:core:health:lint`, verificador de
  fronteiras e build completo no Docker.

## Acceptance criteria

Os cinco estados e a matriz de saúde respeitam a arquitetura, com testes e lint
específicos, `check assembleDebug assembleRelease` no Docker, `git diff --check`
e CI obrigatório do PR aprovados. Os contratos públicos podem ser usados sem
infraestrutura Android e não afirmam recuperação concluída ou resiliência.

## Out of scope

`RecoveryCoordinator`, `IngressCoordinator`, Room/DataStore, health por
subsistema, diagnósticos, callbacks, IPC, retry, scheduler e efeitos externos.

## Process death, security and compatibility

- Snapshot efêmero: não sobrevive a process death e não comprova recuperação.
  A reconstrução e a liberação efetiva da barreira continuam a cargo do futuro Core.
- Sem novos logs, payloads, permissões, componentes exportados ou fronteiras IPC.
- Mudança de contrato fonte: `CoreReadiness.DEGRADED` passa a `DEGRADED_SAFE`;
  `RECOVERING` exige health `RECOVERING`. Os usos existentes estão neste módulo.
  Não há formato persistido/serializado, schema ou migração afetados.
- Sem alegação de F0_PASS, FOUNDATION_READY ou resiliência em dispositivo físico.

## Validation evidence

Ambiente `CONTAINER`, via Docker Compose:

- Antes da correção: `:core:health:testDebugUnitTest`, 52 testes e 36 falhas
  esperadas contra o contrato antigo.
- Depois: `:core:health:testDebugUnitTest :core:health:lint`, 52 testes aprovados,
  lint sem ocorrências; 17 tarefas executadas e 22 UP-TO-DATE.
- `check assembleDebug assembleRelease` aprovado: 2199 tarefas, 78 executadas,
  28 FROM-CACHE e 2093 UP-TO-DATE; cache de configuração reutilizado.
- A primeira validação completa falhou na gravação do relatório de lint de
  `core:time`, com artefatos da branch anterior. `:core:time:clean` e a repetição
  canônica resolveram a falha sem alterar código desse módulo.

Ambiente `HOST`: diff revisado e `git diff --check` aprovado. O resultado do CI
remoto será registrado no PR; build local não substitui esse check.
