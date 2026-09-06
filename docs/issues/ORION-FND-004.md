# [ORION-FND-004] Definir contratos Command/Event/Query

## ORION ID

ORION-FND-004

## Objective

Definir contratos separados para pedidos de mudança, consultas e fatos ocorridos,
com resultados tipados e metadata explícita de eventos, sem implementar execução.

## Context

Arquitetura V3.2 §§9–13 separa Command/Event/Query, define OrionEvent,
correlation/causation e classificação de persistência. Plano Mestre §§7.3–7.4
prevê contratos e interfaces de CommandDispatcher, QueryGateway e
DomainEventPublisher. FND-003 fornece Sensitivity em core:model.

## Files / modules allowed

- `core/commands/**`, `core/queries/**`, `core/events/**`: configuração, fontes e testes.
- Esta documentação da issue.

## Contracts

- `Command<R>`: pedido de mudança com tipo de resultado; pode falhar.
- `Query<R>`: consulta tipada que não deve alterar estado.
- `CommandDispatcher` e `QueryGateway`: interfaces suspensas e distintas, sem implementação.
- `OrionEvent<P>`: envelope de fato com eventId UUID, type, schemaVersion positivo,
  occurredAt Instant, correlationId UUID, causationId UUID opcional, source e payload tipado.
- `EventPersistence`: EPHEMERAL, DURABLE, AUDIT (§12), classificação sem garantia de entrega.
- Eventos exigem classificação explícita de persistência e Sensitivity, sem defaults.
- `DomainEventPublisher`: interface de publicação, sem event bus ou outbox.
- IDs e horário fornecidos pelo chamador; sem geração de UUID nem leitura de relógio.
- Não há codec ou significado persistido de ordinal. UUID/Instant são escolhas de
  representação em memória para os campos conceituais, sem estabelecer protocolo IPC.
- Falhas dos dispatchers/gateways devem ser propagadas; nenhum retry/fallback implícito.

## Invariants

INV-004/009/023: Command não substitui ActionRequest, policy ou IngressCoordinator.
INV-007/008: classificação DURABLE não substitui transação/outbox nem deduplicação.
INV-011/035: toString do envelope não expõe payload; classificação não substitui
minimização, criptografia ou política de payload durável.
INV-019: metadata de envelope é imutável; payloads concretos devem ser imutáveis.

## Required tests

- Resultados de Command/Query mantêm seus tipos, com portas separadas.
- Falhas de implementações de teste chegam ao chamador sem retry.
- Evento preserva metadata/payload, inclusive correlação/causa e Instant fornecido.
- Rejeita type/source em branco e schemaVersion não positivo, sem vazar conteúdo.
- toString não chama toString do payload nem revela metadata fornecida pelo usuário.
- Classificação EPHEMERAL/DURABLE/AUDIT conforme baseline; sem geração de identidade
  ou alegação de ordenação global.
- Docker: testes unitários e lint dos três módulos; depois
  `check assembleDebug assembleRelease` com verificações de fronteiras.
- HOST: revisão de diff e ausência de imports Android, relógios, logging e serialização.

## Acceptance criteria

Contratos públicos documentados nos módulos correspondentes, sem implementação de
produção de dispatchers/publisher. Testes, lint, build e CI obrigatório aprovados.
Somente core:events depende de core:model para Sensitivity; as portas não dependem
umas das outras, de dados, AI, voz ou UI.

## Out of scope

OrionIntent/IntentRouter; comandos, queries ou eventos reais de produto; handlers;
Policy/Action Engine; ingestão/startup barrier; Room/outboxes; DurablePayloadPolicy;
retry/idempotência executável; criptografia; IPC/serialização; relógio e BootSessionId;
health; mudanças de topologia ou autoridade. Payloads continuam objetos em memória.

## Dependencies / blockers

FND-003 integrada em `356101d`; CI do main aprovado no run `33999408030`.
Nenhum bloqueio conhecido; qualificação física F0 permanece separada.

## Evidence required

Commit e resultados em CONTAINER no PR; link do CI obrigatório. Testes com portas
falsas comprovam os contratos, não a ausência de efeitos de futuros handlers.

## Process death / security / migration

Nenhuma persistência ou efeito externo introduzido. Evento durável real deverá ser
inserido atomicamente no outbox e manter identidade no replay. Não logar envelopes
ou payloads crus; toString do envelope é redigido. Sem schema ou migração.
