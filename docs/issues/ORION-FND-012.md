# ORION-FND-012 — Porta mínima VoiceCore

## OBJECTIVE
Completar o skeleton VoiceCore IPC API previsto na Fase 1 da Arquitetura V3.2,
sem antecipar transporte, voz operacional ou DTOs de comandos.

## FILES ALLOWED
`voice/ipc/**` e esta documentação.

## CONTRACTS INVOLVED
VoiceCoreClient, ProtocolInfo v1.0, estado UNAVAILABLE.

## INVARIANTS INVOLVED
INV-012/024/033/034: voz usa o contrato versionado e não acessa negócio/storage.

## TESTS REQUIRED
Versão anunciada 1.0; cliente indisponível não estabelece conexão; disconnect
repetido é seguro; testes/lint e fronteiras no Docker.

## ACCEPTANCE CRITERIA
Porta pública compila, implementação indisponível é determinística e não faz bind,
cria thread, inicializa Android ou envia comandos. Sem dependência de data ou app.

## OUT OF SCOPE
Binder/AIDL, serviços, Voice DTOs, handshake remoto, rebind, terminalidade de
requests, transcrição, áudio, System Assistant e qualquer autoridade de negócio.
Os contratos operacionais completos continuam nas fases de voz.

## Impactos
Sem estado persistido, efeitos, logs, migração ou alteração de IPC wire format.
Perda do cliente efêmero não reconstrói comandos. Não exige ADR.

## Evidência
`CONTAINER`: `:voice:ipc:testDebugUnitTest :voice:ipc:lint verifyModuleBoundaries
 testModuleBoundaries --max-workers=2` passou: 73 tarefas, 35 executadas, seis do
cache e 32 UP-TO-DATE. Sem imports Android ou dependências de data/app.
