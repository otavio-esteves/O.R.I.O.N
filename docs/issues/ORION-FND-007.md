# ORION-FND-007 — Contrato comum IPC v1

## Contrato de trabalho

### OBJECTIVE

Definir o contrato comum e declarativo do IPC v1: versão 1.0, `ProtocolInfo`,
negociação determinística de capabilities, `schemaVersion` e limite inline de
256 KiB.

### FILES ALLOWED

- `core/ipc/**`;
- esta documentação.

### CONTRACTS INVOLVED

- `OrionIpcProtocol`;
- `ProtocolInfo`;
- `IpcCapabilityId`;
- `IpcProtocolNegotiation`;
- `IpcSchemaVersion`;
- `InlineIpcPayloadPolicy`.

### INVARIANTS INVOLVED

- INV-012: major incompatível recusa comunicação; minor negocia capabilities comuns.
- INV-024: o contrato compartilhado não concede à voz acesso direto ao Core.
- INV-033: nenhum initializer ou infraestrutura exclusiva de MAIN é criado.

### TESTS REQUIRED

Valores canônicos 1.0 e 256 KiB; versões, schema e capabilities inválidos;
compatibilidade na mesma versão; interseção em minor diferente; recusa por major;
simetria da negociação compatível; limites 0, máximo, máximo + 1, negativo e
`Int.MAX_VALUE`.

### ACCEPTANCE CRITERIA

Contratos públicos compilam em `core:ipc`; negociação é determinística e recusa
major diferente; schema version é positivo; payload acima de 256 KiB exige canal
externo; testes, lint, fronteiras e build canônico passam no Docker; nenhuma
dependência de produção ou alteração operacional de processo/manifest.

### OUT OF SCOPE

AIDL/Parcelable, Binder, serviços, DTOs de Cortex/voz, `VoiceCoreClient`,
`OrionCoreGatewayService`, payload externo real, SharedMemory/PFD, health, logging,
retry, ingress, policy, persistência, IA e voz operacional.

## Base e elegibilidade

Foram lidos `AGENTS.md`, Arquitetura V3.2, Plano Mestre V1.2, ADR-0001,
`CONTRIBUTING.md` e todas as issues locais. A arquitetura §§23–25 e o plano §7.5
fecham os valores e a semântica implementados aqui; o ADR-0001 somente define o
ambiente de validação e não os supersede. A mudança não altera arquitetura e não
exige ADR.

ORION-FND-005 está implementada e validada no commit local `6bed255`; não há mudança
adicional necessária nessa implementação. O encerramento externo ainda depende de
PR, CI remoto e revisão. A consulta remota desta tarefa não pôde ser concluída:
`gh auth status` informou token inválido e `gh issue list`/`gh pr list` também não
conseguiram acessar `api.github.com`. Nenhum estado remoto é alegado.

`core:ipc` existia apenas com build e manifest vazio. `ProtocolInfo` e seus testes
continuavam pendentes no gate F1, portanto ORION-DATA-001 ainda não é elegível como
próximo passo. Outros contratos e testes obrigatórios da Foundation permanecem para
pacotes posteriores; esta entrega não declara `FOUNDATION_READY`.

## Decisões do contrato

- `OrionIpcProtocol.CURRENT` anuncia exatamente major 1, minor 0 e nenhuma
  capability opcional implícita.
- `ProtocolInfo` exige major positivo e minor não negativo. Major diferente produz
  `MajorMismatch`; nenhuma capability é negociada ou executada.
- Com o mesmo major, o minor negociado é o menor dos dois e somente a interseção
  exata de `IpcCapabilityId` é habilitada. Interseção vazia continua compatível no
  nível base, mas não autoriza operação opcional. `ProtocolInfo` copia o conjunto
  recebido para que mutação posterior do chamador não altere o anúncio.
- `IpcCapabilityId` é um identificador estático de feature de transporte. Rejeita
  vazio, whitespace nas extremidades e caracteres de controle. Não representa
  `CapabilityRegistry`, permissão Android, policy ou autoridade de negócio.
- `IpcSchemaVersion` aceita somente inteiro positivo. Não é versão do protocolo e
  não implementa codec, default de campos ou compatibilidade de um DTO específico.
- Capability IDs são metadata técnica e não devem carregar dados do usuário. A
  negociação usa igualdade literal, sem ordinal, hash semântico ou fallback para
  major diferente. O catálogo concreto pertence aos futuros contratos de Cortex e voz.
- `InlineIpcPayloadPolicy` recebe tamanho já serializado em bytes. Zero e exatamente
  262144 bytes são inline; acima disso retorna `EXTERNAL_REQUIRED`; negativo é
  inválido. O contrato não escolhe nem implementa PFD, arquivo privado ou SharedMemory.
- O tamanho inclui todo o envelope/overhead e deve ser medido pelo futuro codec antes
  da chamada Binder. A classificação não prova que um payload é seguro ou válido.

## Processo, segurança e evolução

Não há componente Android, chamada Binder, processo, initializer, DI ou dependência
entre módulos nova. Assim, os processos auxiliares não ganham acesso a Room,
DataStore, repositories, scheduler, recovery, ingress ou policy. O contrato não
executa comandos e não contorna Startup Barrier ou ActionRequest.

Os tipos são efêmeros e não persistem estado. Process death perde apenas o objeto de
handshake, que deverá ser renegociado por uma futura conexão. Major incompatível
deverá degradar somente o subsistema opcional, nunca causar crash global. Binder
death, reconnect e health operacional continuam fora do escopo.

Não há schema de banco, migração ou dado durável. Mudanças incompatíveis futuras no
protocolo exigem novo major conforme a baseline; DTOs evolutivos terão seu próprio
`IpcSchemaVersion`, defaults e regras de compatibilidade. Esta entrega não estabelece
wire encoding nem significado persistido para nomes de enum ou classes Kotlin.

## Evidência de validação

Ambiente `CONTAINER`, via Docker Compose:

- Red inicial: `:core:ipc:testDebugUnitTest` falhou na compilação porque os tipos
  do contrato ainda não existiam.
- Foco inicial: `:core:ipc:testDebugUnitTest :core:ipc:lint
  verifyModuleBoundaries testModuleBoundaries` passou; os 36 casos de fronteira
  foram aprovados; 41 tarefas, 22 executadas e 19 `UP-TO-DATE`.
- Validação focada final, após testes de snapshot e estados impossíveis:
  `:core:ipc:testDebugUnitTest :core:ipc:lint` passou com 11 testes, zero
  falhas/erros e lint sem ocorrências; 39 tarefas, 17 executadas e 22 `UP-TO-DATE`.
- Validação canônica: `check assembleDebug assembleRelease` passou; 2211 tarefas,
  48 executadas, uma `FROM-CACHE` e 2162 `UP-TO-DATE`; configuration cache reutilizado.

Ambiente `HOST`: revisão de escopo/diff e `git diff --check` passaram. A validação
canônica também executou novamente os verificadores registrados como dependências de
`check`.

Todos os testes desta entrega são JVM no ambiente `CONTAINER`. Eles comprovam os
cálculos do contrato, não transporte Binder, comportamento multiprocesso, Android
runtime, dispositivo físico, compatibilidade OEM ou CI remoto.

## Pendências da Foundation

Após esta entrega ainda permanecem contratos/skeletons e gates F1 não implementados,
incluindo `OrionIntent`, `SkillDescriptor`, `Capability`/`CapabilityRegistry`,
`IdempotencyKeyPolicy`, `DurablePayloadPolicy`, portas mínimas ainda vazias,
observabilidade básica, abertura real de Room/DataStore, agregação completa de
health, UI inicial e testes instrumentados de bootstrap/recriação. Cada item exige
work package próprio e revisão contra a sequência normativa. F0 física permanece
separada e o benchmark exemplo não comprova `F0_PASS`.
