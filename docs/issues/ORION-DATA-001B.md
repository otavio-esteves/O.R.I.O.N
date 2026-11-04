# ORION-DATA-001B — Persistência inicial de ações e auditoria

## OBJECTIVE
Introduzir ActionRequestEntity e AuditEntity no Room v2, preservando o marcador
técnico v1 por migração explícita. Implementar registro idempotente e transições
pré-execução com auditoria atômica e optimistic concurrency. Base: Arquitetura
V3.2 §§50–57, Plano Mestre V1.2 §§8.2/8.4/8.10 e ADR-0001.

## FILES ALLOWED
`data/database/**`, `data/actions/**`, testes Android em `app`, catálogo Gradle e
builds dos módulos envolvidos, README e este documento. Contratos de confirmação
e criptografia são pacotes separados ORION-DATA-002 e ORION-DATA-003.

## CONTRACTS INVOLVED
ActionRequest, Action Outbox, audit, IdempotencyKeyPolicy, confirmation binding,
field encryption, Room migration. O schema inicial de negócio é v2 porque v1 já
foi distribuído com metadata técnica. formatVersion permanece 1.

## INVARIANTS INVOLVED
INV-001/004/005/006/011/014/019/021/022/023/033/035/036. Nenhuma operação habilita
execução de Skills ou efeitos externos. O runtime mantém ActionEngine/ingress
mutável indisponíveis; persistência não concede autorização.

## TESTS REQUIRED
MigrationTestHelper v1→v2, criação nova e reabertura; preservação de metadata e
schema exportado; registro e dedup/conflito; optimistic concurrency incluindo
disputa concorrente; falha de auditoria reverte a ação; UNKNOWN/RUNNING persistidos
não são reexecutados ao reabrir; payloads não aparecem em string/log ou plaintext
no banco. Build/lint/fronteiras em Docker e instrumentação host/emulador identificada.

## ACCEPTANCE CRITERIA
Schema v2 exportado, migração sem exclusão, transações ação+audit atômicas, nenhuma
substituição em conflito de idempotência ou de versão. Sem API genérica que leve a
RUNNING: executor, policy e leases operacionais pertencem a pacotes posteriores.

## OUT OF SCOPE
Tasks/reminders/memory, Domain Event Outbox, durable ingress, dispatch/recovery de
ações externas, produtor Android Keystore, FTS, scheduler, UI operacional, IA/voz.
Não declara TRANSACTION_CORE_READY, resiliência F4 ou qualificação física F0.

## Contrato de armazenamento
`orion_action_requests` usa ID de ação e UNIQUE(scope, key), nomes de enums e
versionamento inteiro. Colunas de payload/target são blobs opacos para o DAO; ele
não comprova criptografia nem autorização. Apenas o adapter confiável de DATA-004
deve produzir registros operacionais, cifrar os campos e recompor o binding. Os
fixtures de baixo nível exercitam SQLite, não demonstram proteção criptográfica.

Registro começa em PENDING/version=0. Toda escrita de estado e sua auditoria mínima
usam a mesma transação. A auditoria tem FK RESTRICT e UNIQUE(actionId, actionVersion).
Falha na inserção da auditoria reverte também a ação, versão e timestamps. Conflito
otimista não registra uma transição que não ocorreu. Material do request não possui
UPDATE público; os únicos updates SQL alteram espera/cancelamento ou campos da
confirmação validada. Nenhuma API leva a RUNNING ou resolve resultado externo.

Replay compara hash/schema e metadata de execução/policy/classificação, preservando
o registro original. Retenção mínima de idempotência fica persistida, mas não há
pruning neste pacote; futuras rotinas precisam preservar ações não resolvidas e
risco de duplicação. Não há delete genérico nem destructive fallback.

Campos de lease incluem BootSessionId, mas são apenas representação nesta entrega.
Reabrir RUNNING/UNKNOWN preserva o estado sem interpretar deadline, autorizar retry
ou afirmar que o efeito não aconteceu. A reabertura testada não substitui o teste
físico de morte de processo no ponto crítico de um executor real.

## Impactos de segurança, upgrade e diagnóstico
Payloads não entram na auditoria nem em toString. Sem componentes/permissões novos,
FTS ou acesso de processos auxiliares. Room continua sob o guard MAIN/unlocked.
Migrar adiciona tabelas/índices e preserva o marcador técnico; falha não apaga banco.
O diagnóstico mostra schema=2, mantendo formatVersion=1. ActionEngine e ingresso
mutável continuam UNAVAILABLE; storage disponível não significa executor pronto.

## Correção de build
A primeira compilação das duas variantes identificou corrida na exportação do
mesmo schema JSON pelo KSP debug/release. `kspReleaseKotlin.mustRunAfter` ordena
esses escritores quando ambos estão no grafo, sem acrescentar dependência de uma
variante na outra. A repetição focada de assemble/lint concluiu com sucesso.

O primeiro APK instrumentado executou 29 testes, com 28 aprovações e uma falha
no parser do MigrationTestHelper: o runtime de serialização 1.7.3 do APK alvo foi
imposto ao JSON 1.8.1 de room-testing pela resolução consistente do AGP. A interface
no DEX alvo possuía método abstrato que o serializer novo esperava herdar como
default. `debugImplementation` alinha apenas o alvo debug ao core 1.8.1 exigido
pelos testes; release permanece com sua resolução anterior. Nenhuma atualização
de Room/Compose/Gradle/JDK foi feita. A correção exige repetir a migração no Android.

Referências primárias: [release kotlinx.serialization 1.8.0](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.8.0)
e [relato/explicação do mesmo erro](https://github.com/Kotlin/kotlinx.serialization/issues/2968).

## Evidência final

- `CONTAINER`: comando canônico `check assembleDebug assembleRelease
  :app:assembleDebugAndroidTest --max-workers=2` aprovado, 2625 tarefas (2072
  executadas, 553 FROM-CACHE). 160 testes JVM em 29 suites, zero falhas/erros/skips;
  lint do banco sem ocorrências e 36 casos de fronteiras aprovados. Um erro inicial
  no teste (RoomDatabase não aceita `use`) foi corrigido para fechamento explícito
  antes da compilação instrumentada aprovada.
- `ANDROID_EMULATOR`, API35/x86_64/4 KiB: 12 testes ActionPersistenceTest aprovados,
  incluindo migração real com MigrationTestHelper, reabertura, idempotência por
  escopo, disputa concorrente e rollback integral de confirmação/auditoria.
- Rodada integrada: 29 testes iniciais e quatro após cada cenário de processo/Force
  Stop (37 execuções), todos aprovados. A morte de processo usou o harness corrigido
  de ORION-TEST-001; preservação de RUNNING/UNKNOWN em fixtures não é recovery de
  side effect. Ambiente NON_DEFINITIVE, sem dispositivo físico.
- Revisão independente comparou migration SQL e schema KSP em tipos/nullability,
  defaults, PKs, índices e FKs; nenhum desvio. Schema v1 original foi preservado.
- `HOST`: revisão de escopo/diff/whitespace e orquestração ADB. Relatório integrado
  e hashes em `docs/qualification/F2-ActionPreparation.md`.
