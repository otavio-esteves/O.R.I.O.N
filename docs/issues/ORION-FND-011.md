# ORION-FND-011 — Bootstrap MAIN, Compose e testes Android

## OBJECTIVE
Integrar storage/health/logging da Foundation em MAIN; prover navegação Home,
Diagnostics e Settings em Compose; testar inicialização/recriação e isolamento
real de processos auxiliares. Base: Arquitetura §§6, 46–49, 96, 102 e Fase 1;
Plano Mestre §§7.4.1, 7.6–7.9; ADR-0001.

## FILES ALLOWED
`app`, `feature/home`, `feature/diagnostics`, `feature/settings`, `scripts/android`,
`core/common` (negação tipada de ownership), `core/health` (estado do exit monitor), `data/database/build.gradle.kts` (API exposta),
catálogo de dependências apenas se necessário, e esta documentação.

## CONTRACTS / INVARIANTS
RecoveryCoordinator, IngressCoordinator, ResourceGovernor, ApplicationExitMonitor,
OrionProcessBootstrapper, OrionHealth; INV-001/009/011/013/014/017/018/023/024/025/
027/028/032/033/034/035. Serviços de probe são exclusivos de debug, não exportados,
sem áudio, comandos de negócio ou autoridade. Release não contém esses componentes.

## TESTS REQUIRED
Room/DataStore carregam em MAIN; health fica íntegro antes de liberar leituras;
roles auxiliares reais não criam grafo MAIN e rejeitam factories de storage;
navegação e Activity recreation; host testa morte/recriação básica do processo e
Force Stop seguido de retorno permitido. Compilar APK de testes via Docker;
instalar/executar via ADB no host, registrando ambiente e limitações.

## ACCEPTANCE CRITERIA
App abre/navega/exibe health real; Cortex/voz indisponíveis; storage fora da UI;
failure bloqueia prontidão sem apagar dados; mutable ingress e actions continuam
indisponíveis; testes, lint e debug/release aprovados. Nenhuma permissão sensível nova, endpoint de negócio exportado ou inicializador
MAIN nos processos auxiliares.

## OUT OF SCOPE
Recovery F4, filas/outboxes e mutações de negócio F2, Skills, gateway/Binder de
produção, System Assistant, inferência, áudio, observação contínua de capabilities,
24/7, F0 física e qualificação API 37/OEM.

## Sequência e limites
O coordenador F1 inicializa logging, abre/migra/verifica Room, lê configuração,
consulta exits, mantém ingress conservador, aplica governor indisponível e agrega
health antes das leituras. Outboxes, reminders e workers não existem no schema F1:
não há trabalho de negócio a recuperar, drenar ou executar. Isso não implementa nem
substitui o recovery F4. Exits são metadata numérica sem descriptions e sem inferir
Force Stop. O governor nunca permite inferência, independentemente da consulta.
A UI somente navega e consulta estado técnico; Settings não escreve preferências.
Falha crítica bloqueia o Core; retry explícito repete verificações locais seguras.

## Impactos
O processo principal reconstrói seu grafo após recriação a partir dos mesmos arquivos;
metadata de formato permanece única. Falhas não apagam banco/config, não repetem
efeitos e não iniciam voz/IA. Sem migração adicional além do schema técnico FND-010.
Probes usam Messenger somente em debug com protocolo 1 e mensagens técnicas fixas;
não são VoiceCoreClient nem Core gateway de produção. Evidência em emulador é
NON_DEFINITIVE para hardware/runtime físico e não declara F0_PASS.

Revisão do teste de isolamento: o probe verifica uma negação tipada de processo,
não qualquer IllegalStateException, para não confundir restrição de thread do Room
ou falha de provider com a proteção de ownership. Backup/extraction rules excluem
todos os domínios de cloud/device transfer; teste Android verifica flags e XML.
Fonte de API: https://developer.android.com/identity/data/autobackup.

Auditoria de componentes: AndroidX inclui a permissão privada de assinatura
`network.orion.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` e o receiver oficial de
ProfileInstaller, exportado e protegido por `android.permission.DUMP`. Esse receiver
é limitado ao tooling de perfis e não é um gateway Core. Serviços e providers
permanecem privados; o Startup provider (emoji/lifecycle/profile installer) pertence
a MAIN por declaração de manifest, sem inicializadores Room/DataStore/repositories.
Um teste Android verifica a allowlist da superfície exportada. Diagnostics exibe
também a versão real de schema obtida do SQLite, distinta do marcador de formato.

## Evidência intermediária
`CONTAINER`: integração inicial, testes health, APK debug, APK de instrumentação,
lint e fronteiras passaram (701 tarefas). Refinamento do guard tipado e regras de
extração também compilou e passou testes/lint (693 tarefas, 66 executadas).
`ANDROID_EMULATOR`, API 35/x86_64/4 KiB: navegação e Activity recreation, três
processos auxiliares, storage, negação tipada e exclusões de backup passaram; após
morte de background e após Force Stop/retorno permitido, testes repetidos passaram
(4 + 3 + 3 execuções), com PID alterado e tela restaurada. Resultado NON_DEFINITIVE.
A primeira tentativa do harness encontrou ANR de startup do System UI, registrado
antes da instalação do app; a repetição sem esse diálogo passou. Nenhum ANR do app
foi observado. Evidência final após auditoria adicional constará no gate F1.
