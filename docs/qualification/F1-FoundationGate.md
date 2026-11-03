# F1 — Foundation: relatório de gate

Estado: requisitos F1 implementados e verificados localmente. CI obrigatório é
acompanhado nas PRs abaixo; integração em main depende do fluxo de revisão/merge.
Não declara F0_PASS, TRANSACTION_CORE_READY ou resiliência operacional.

## Matriz de aceitação

| Requisito do Plano Mestre | Implementação / evidência |
| --- | --- |
| §7.1 módulos e §7.2 fronteiras | Inventário completo e 36 casos de fronteira, incluindo dependências transitivas e ciclos |
| §7.3 vocabulário, intent, event, command/query | core:model/commands/events/queries; FND-003/004/008 |
| §7.3 SkillDescriptor e Capability | core:model + core:skills:metadata; confirmação destrutiva obrigatória e registry indisponível nega autorização |
| §7.3 idempotência e payload durável | core:actions/security; fingerprints ligados a scope/schema, conflito não substitui payload; proteção de SENSITIVE/SECRET declarada |
| §7.3.1 tempo | OrionClock/BootSessionId/MonotonicDeadline; testes existentes FND-005 |
| §7.4 portas mínimas | RecoveryCoordinator, IngressCoordinator, ResourceGovernor, CapabilityRegistry, ApplicationExitMonitor, ActionEngine e portas Command/Query/Event |
| §7.4.1 bootstrap | Grafo MAIN após resolução de role; probes reais de AI/VOICE_CONTROL/VOICE_SESSION não criam grafo MAIN e recebem negação tipada ao tentar storage |
| §7.5 IPC comum | v1.0, negociação, schemaVersion e 256 KiB; probes debug medem Parcel e recusam major/payload inválidos |
| Arquitetura F1 VoiceCore skeleton | voice:ipc porta versionada sem bind ou comando operacional |
| §7.6 UI | Home, Diagnostics e Settings em Compose; estado de navegação preservado após recriação de Activity/processo |
| §7.7 observabilidade | UUID técnico + component/event/severity/timestamp injetado; sem mensagens/payloads arbitrários; DEGRADED_SAFE preservado no evento |
| §7.8 Room/DataStore | Abrir/migrar/verificar Room técnico v1 e ler configuração em IO; guard de unlock/credential storage/role |
| §7.8 health | Agregação de storage crítico e subsistemas opcionais; falha crítica bloqueia, indisponibilidade é explícita |
| §7.8 builds/testes | Comando canônico Docker e harness Android host; resultados finais abaixo |
| Gate nativo inicial | ELF/ABI/APK/AAB estáticos; fixtures reais negativas; LOAD_TEST físico continua NOT_RUN |

## Limites de fase e segurança

A Foundation tem apenas `orion_storage_metadata`, com uma linha de formato. Room
cria a linha na transação inicial e startup verifica unicidade, formato e integridade.
F2 deverá migrar explicitamente esse schema antes de adicionar tabelas de negócio;
não há destructive fallback. Configuração somente é lida e falhas não apagam arquivo.
Não há outboxes/filas/reminders/workers a reconciliar neste schema; os motores e o
ingress mutável retornam UNAVAILABLE. O coordenador F1 não substitui recovery F4.

Nenhum áudio, inferência, Skill, ação externa ou confirmação operacional existe.
O governor indisponível impede load/inferência independentemente de exit signals.
Dados duráveis genéricos e criptografia real pertencem à F2. Não há payload de usuário
em logs/diagnósticos; regras de extração excluem os domínios de cloud/device transfer.
O receiver AndroidX ProfileInstaller é exportado apenas com proteção DUMP para seu
contrato de tooling; serviços/providers/Core permanecem privados. Probes de processos
são exclusivos de debug e não aparecem no release.

## Evidência de ambiente

CONTAINER: toolchain fixada, builds, JVM, lint, fronteiras, schema e ELF/packaging.
HOST: ADB/instalação/harness e revisão Git. ANDROID_EMULATOR: API 35, x86_64,
páginas de 4096 bytes; evidência Android NON_DEFINITIVE para hardware/OEM.

O emulador apresentou ANR de startup do System UI antes da instalação do app. O
harness registrou falha por diálogo cobrindo a UI; após tratar a condição do
ambiente, a repetição passou. Isso não foi classificado como ANR do O.R.I.O.N.

## Pendências fora da implementação F1

- F0 física no Galaxy S21 e matriz de compatibilidade, incluindo API 37/OEM.
- Native LOAD_TEST físico arm64/16 KiB; gate nativo global permanece PENDING.
- Qualificação física de BOOT_COUNT, reboot, deep sleep e relógios conforme FND-005.
- Transações/outboxes/idempotência persistida/criptografia F2 e recovery F4.
- Revisão e integração dos PRs no fluxo obrigatório; nenhum merge direto em main.

## Resultados finais

- `CONTAINER`: comando canônico com 2608 tarefas, aprovado; última repetição teve
  57 tarefas executadas, uma FROM-CACHE e 2550 UP-TO-DATE, configuration cache
  reutilizado. 124 testes JVM em 25 suites, zero falhas/erros/skips. Inclui testes
  de fronteiras, benchmark, native gate e fixtures reais negativas de ELF/ABI/ZIP.
- `ANDROID_EMULATOR`: cinco testes na primeira rodada e quatro após cada cenário de
  processo/Force Stop (13 execuções). Valida storage/schema/health, navegação e
  Activity recreation, três roles auxiliares, guardas IPC e backup. PIDs diferentes
  e restauração da tela foram verificados pelo host. Evidência NON_DEFINITIVE.
- `HOST`/emulador: assinatura default estável em dois containers, certificados de
  app/test correspondentes; novo `install -r` manteve o inode do banco técnico e
  quatro testes adicionais passaram após update. O recorder não faz uninstall.
- Lint: zero erros; nove avisos visíveis de versões disponíveis, ABI ChromeOS fora
  do alvo arm64 inicial e ícone de aplicativo ainda não definido. Nenhum detector
  foi suprimido. Atualizações da toolchain e acabamento de publicação não foram
  misturados ao fechamento dos contratos F1.
- Release manifest revisado: probes ausentes; Room invalidation service/Startup
  provider em MAIN privados; launcher e receiver de tooling DUMP são as superfícies
  exportadas esperadas. Não há componente de voz/IA operacional ou permissão de áudio.

Artefatos técnicos (sem dados pessoais/keystores):

- [Resumo de build e hashes](f1-build-summary.json).
- [Runtime API 35](f1-runtime-api35.json).
- [Assinatura debug e reinstalação](f1-debug-signing.json).
- [Equivalência das árvores de implementação](f1-source-equivalence.json).

O recorder captura commit e dirty flag reais. Os pacotes foram reorganizados em
branches encadeadas para manter PRs por responsabilidade; `git diff` comprovou
árvores de implementação idênticas. A consolidação acrescenta documentação,
metadata do recorder e compilação do APK de instrumentação ao CI, sem alterar o
código Android qualificado. Resultados intermediários estão em FND-009 a 015.

## PRs e CI

Integrar na ordem abaixo. Cada PR tem o check obrigatório `docker-build-and-check`;
o estado atual do CI é visível nos links, sem presumir integração em main.

| Ordem | Pacote | PR |
| --- | --- | --- |
| 1 | FND-005 tempo | [#9](https://github.com/otavio-esteves/O.R.I.O.N/pull/9) |
| 2 | FND-007 IPC | [#10](https://github.com/otavio-esteves/O.R.I.O.N/pull/10) |
| 3 | FND-008 intent | [#11](https://github.com/otavio-esteves/O.R.I.O.N/pull/11) |
| 4 | FND-009 contratos/metadata | [#12](https://github.com/otavio-esteves/O.R.I.O.N/pull/12) |
| 5 | FND-012 VoiceCore skeleton | [#13](https://github.com/otavio-esteves/O.R.I.O.N/pull/13) |
| 6 | FND-010 storage/health/log | [#14](https://github.com/otavio-esteves/O.R.I.O.N/pull/14) |
| 7 | FND-011 bootstrap/UI + FND-013 native | [#15](https://github.com/otavio-esteves/O.R.I.O.N/pull/15) |
| 8 | FND-015 signing debug | [#16](https://github.com/otavio-esteves/O.R.I.O.N/pull/16) |

A PR de consolidação FND-014 acrescenta este relatório e o APK de instrumentação ao
comando CI. O gate técnico de branch deve ser distinguido de revisão/merge, F0
física e autorização para desenvolver fases posteriores.
