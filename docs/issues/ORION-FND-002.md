# [ORION-FND-002] Criar módulos base

## ORION ID

ORION-FND-002

## Objective

Criar e registrar os módulos base da seção 7.1 do Plano Mestre V1.2, com
fronteiras verificáveis e sem implementar funcionalidades das próximas issues.

## Context

A base local já contém `app`, `core:common`, bootstrap por processo e CI.
Esta tarefa completa a estrutura inicial prevista pelo plano, preservando o
bootstrap existente. Módulos sem implementação podem permanecer vazios.

Referências normativas: Arquitetura V3.2, Plano Mestre V1.2 §§7.1–7.2 e 42,
AGENTS.md §§5–9 e ADR-0001.

## Files / modules allowed

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` e
  configurações Gradle estritamente necessárias à estrutura modular.
- Configurações, manifests mínimos e estrutura dos módulos abaixo:
  `core:common`, `core:model`, `core:commands`, `core:queries`, `core:events`,
  `core:ipc`, `core:orchestrator`, `core:policy`, `core:actions`, `core:resources`,
  `core:time`, `core:recovery`, `core:health`, `core:security`,
  `core:observability`, `core:ingress`, `core:exitinfo`, `core:skills:metadata`,
  `data:database`, `data:datastore`, `data:repository`, `data:tasks`,
  `data:reminders`, `data:actions`, `data:events`, `data:memory`, `ai:api`,
  `voice:api`, `voice:ipc`, `skills:api`, `scheduler`, `feature:home`,
  `feature:diagnostics`, `feature:settings`.
- `app/build.gradle.kts` apenas se necessário para integração da estrutura.
- `scripts/validate-module-boundaries.pl` e testes específicos dessa validação.
- Documentação da issue e evidências diretamente relacionadas.

## Contracts

- Estrutura inicial de módulos e regras de dependência do Plano Mestre §§7.1–7.2.
- Ownership por processo e bootstrap explícito já existentes.
- Ausência de ciclos Gradle; APIs e implementações mantêm fronteiras explícitas.
- Docker como ambiente canônico de build e validação independente de hardware.

## Invariants

INV-001, INV-002, INV-003, INV-009, INV-024 e INV-033 orientam a separação
entre Core, persistência, AI e voz. A tarefa verifica fronteiras estáticas;
não pretende comprovar o comportamento futuro desses subsistemas em runtime.
INV-032 rege a classificação das evidências de validação.

## Required tests

- Validação da presença de todos os módulos iniciais e de seus build files.
- Casos negativos para módulos ausentes, dependências proibidas e ciclos Gradle.
- Casos positivos para a estrutura e dependências permitidas.
- Preservação dos testes existentes de processo/bootstrap.
- Em Docker: `check assembleDebug assembleRelease`, incluindo lint, testes
  pertinentes, verificação de fronteiras e gate nativo existente.
- `git diff --check`.

## Acceptance criteria

- Todos os módulos da seção 7.1 registrados e configuráveis pelo Gradle.
- Build debug e release concluídos no ambiente canônico.
- Verificação de fronteiras cobre a estrutura ampliada e rejeita ciclos,
  `ai`/`voice` → database, policy → implementação AI e repository → UI.
- Features respeitam a fronteira de APIs/use cases; não adicionar dependências
  em implementações de dados para preencher módulos vazios.
- Entradas da tarefa de validação abrangem todos os build files relevantes,
  evitando resultado obsoleto após alteração de dependências.
- Nenhuma nova inicialização de infraestrutura ou alteração de ownership.
- Testes relevantes e CI obrigatório `docker-build-and-check` verdes antes do merge.

## Out of scope

Implementação de modelos e contratos de domínio, Command/Event/Query,
OrionClock/BootSessionId, health/readiness, Room/DataStore, recovery,
Skills, scheduler, telas funcionais, inferência, voz e bibliotecas nativas.
Não alterar arquitetura, topologia de processos, toolchain ou qualificação F0.

## Dependencies / blockers

- Base da ORION-FND-001 presente localmente no commit `a4e25b5`, seguida do
  reforço de CI/workflow em `2d4b394`.
- Acesso autenticado ao repositório confirmado em 2026-09-05. CI de `main`
  concluído com sucesso para a fundação (run `33886720193`) e o reforço do
  workflow (run `33888610395`). Revalidar a base antes de integrar a implementação.
- Qualificação física F0 continua separada; esta tarefa não declara F0_PASS.

## Evidence required

Registrar commit validado, comandos e resultados em ambiente `CONTAINER` para
build/testes/lint, resultados dos casos positivos e negativos do validador,
e link do CI obrigatório no PR. Evidência de estrutura/build não comprova
comportamento Android em dispositivo físico.

## Process death / security / migration

Sem mudanças previstas em estado durável, efeitos externos, permissões,
componentes exportados ou schemas. Preservar o bootstrap por processo e não
introduzir inicializadores automáticos de infraestrutura nos módulos vazios.
