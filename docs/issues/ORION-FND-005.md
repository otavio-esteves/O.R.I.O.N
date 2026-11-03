# ORION-FND-005 — OrionClock, BootSessionId e semântica de tempo

## Contrato de trabalho

- Objetivo: abstrações mínimas de tempo civil, duração/deadline monotônico e sessão
  de boot, com injeção determinística e rejeição de referências de outro boot.
- Arquivos permitidos: `core/time/**` e esta documentação. Única alteração de build:
  JUnit 4 no módulo, usando o catálogo existente.
- Baseline consultada: Arquitetura V3.2 §§36–39, 55 e INV-036; Plano Mestre V1.2
  §§7.3–7.3.1; `AGENTS.md` §§9, 19, 31–34; ADR-0001 (Accepted, não supersede
  contratos de tempo). Não há assinaturas anteriores de tempo no código/documentos.
- Invariantes: INV-036, INV-033 e separação civil/monotônico da seção 19.
- Aceitação: testes determinísticos, lint, fronteiras e build canônico aprovados;
  nenhuma dependência de produção adicional ou infraestrutura de fases seguintes.
- Fora do escopo: consumidores, persistência, leases operacionais, confirmação,
  retry/recovery, scheduler, IPC, health operacional e qualificação física completa.
- Estado inicial: `main` sem alterações locais; `core:time` só tinha build/manifest.
  Command/Event/Query e CoreReadiness/OrionHealth já existiam e foram preservados.
  Branch local: `feat/orion-fnd-005-time`. Sem publicação remota.

## Contratos e unidades

- `WallClock.wallNow(): Instant`: instante civil UTC, independente de timezone,
  sujeito a correção manual/automática e retrocesso. Android fornece precisão de
  milissegundos desde Unix epoch; instantes anteriores ao epoch são válidos.
- `MonotonicClock.monotonicNow(): MonotonicInstant`: leitura não negativa em
  milissegundos desde boot, incluindo deep sleep, acompanhada da identidade atual.
  Não é um instante civil nem uma fonte para agendamento persistente entre boots.
- `OrionClock`: reúne as duas interfaces; consumidores podem depender apenas da
  menor interface necessária. Leituras civil e monotônica não são snapshot atômico.
- `BootSessionId(bootCount)`: contador inteiro não negativo de boot, no escopo do
  mesmo dispositivo e da mesma continuidade dos dados de sistema. Não é ID de
  processo, identificador global de dispositivo ou credencial de segurança.
- `MonotonicInstant.elapsedSince(start)`: `Duration` com resolução de milissegundos;
  retorna `null` quando falta identidade em qualquer lado, os boots diferem ou a
  leitura retrocede. Duas identidades ausentes nunca validam uma referência.
- `deadlineAfter(durationMillis)`: duração inteira não negativa; zero expira no
  instante inicial. Soma com overflow lança `ArithmeticException`; valores negativos
  lançam `IllegalArgumentException`. Deadline retém início e fim para rejeitar regressão.
- `MonotonicDeadline.statusAt(now)`: `ACTIVE`, `EXPIRED` (inclusive igualdade) ou
  `INVALID_REFERENCE`. Não traduz invalidez em expiração nem em autorização de execução.
- `AndroidOrionClock`: única fronteira de chamadas diretas a `System.currentTimeMillis`
  e `SystemClock.elapsedRealtime` neste módulo. Recebe `ContentResolver` explicitamente;
  não lê fontes na construção, não guarda Activity e não inicializa infraestrutura.
  O chamador deve fornecer o resolver de aplicação e escolher o contexto de execução
  apropriado à leitura síncrona do provider. Cada leitura monotônica consulta a fonte;
  falhas não preservam uma identidade anterior em cache.
- Os testes usam `FakeClock : OrionClock` com estado controlável e lambdas injetadas
  no adaptador. Não há dependência em relógio real, espera ou infraestrutura Android
  para provar os cálculos. Os contratos permitem que futuros consumidores façam o mesmo.

## Fonte da identidade e limitações

A fonte adotada é `Settings.Global.BOOT_COUNT`, API pública desde 24, anterior ao
`minSdk=31` deste projeto (`compileSdk/targetSdk=37`). A documentação define contador
de boots desde API 24 e settings globais compartilhados entre usuários, legíveis por
aplicações. A API AndroidX Stopwatch também exige comparar boot count do dispositivo
antes de usar elapsed realtime. Não adicionamos AndroidX, permissões ou serviço.

`BootCountSource` lê a representação string do setting e aceita somente inteiro
representável não negativo. Ausência, valor malformado/negativo/overflow e
`RuntimeException` da leitura do provider (incluindo negação de acesso) retornam
`null`. Essa captura é restrita à fronteira do provider; não captura erros fatais da
VM nem registra detalhes da exceção. O civil continua disponível. Comparações de
referências monotônicas ficam indisponíveis, inclusive para timeouts novos: o
consumidor deve tratar a degradação, sem fallback silencioso para relógio civil.

O contador não é globalmente único nem resistente a reset de fábrica, restauração
de settings, alteração privilegiada ou cópia para outro aparelho. Referências
monotônicas não podem ser importadas/restauradas nesses cenários. Um futuro formato
persistido deve preservar a identidade e unidades e invalidar referências se a
continuidade do dispositivo/dados não for garantida. O módulo não implementa backup,
migração, detecção de adulteração ou recuperação. Overflow negativo é indisponível;
reutilização de contador por manipulação/reset não é detectável por esta fonte.

Alternativas avaliadas: `/proc/sys/kernel/random/boot_id` representa ID de boot do
kernel e tem histórico de regras SELinux no AOSP, mas não é contrato público Android
de SDK e sua acessibilidade depende do sistema/OEM; não é usado nem é fallback.
UUID por processo viola estabilidade; `currentTimeMillis - elapsedRealtime` muda
com ajuste civil; `uptimeMillis` exclui deep sleep. Nenhuma dessas alternativas é
usada como identidade ou substituição silenciosa.

Fontes oficiais consultadas:

- [Settings.Global e BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)
- [SystemClock e elapsedRealtime](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime())
- [Stopwatch.getBaseTimeMillis](https://developer.android.com/reference/androidx/appsearch/builtintypes/Stopwatch#getBaseTimeMillis())
- [AOSP: histórico SELinux de proc_random/boot_id](https://android.googlesource.com/platform/system/sepolicy/+/9d9c370f31556c33f62e7889f64ad3a2728f1863%5E%21/)

## Cobertura e responsabilidades futuras

Os testes de contrato cobrem independência dos relógios; avanço/retrocesso civil;
duração e deadline antes/no/depois do limite; zero, negativos e overflow; identidade
recriada no mesmo boot; outro boot com leitura menor ou maior; identidade ausente em
um ou ambos os lados; regressão monotônica; fonte estável, alterada, indisponível e
recuperada; falhas de acesso/provider; projeção de timezone e transições DST gap/overlap
de instantes já resolvidos. São testes JVM com fontes injetadas, não testes do provider
Android nem evidência de morte de processo/reboot real.

| Cenário do plano | Coberto nesta abstração | Responsabilidade futura |
| --- | --- | --- |
| Lease expiry/reboot | Limite monotônico e rejeição de boot diferente/ausente | Persistência, ownership, abandono e RecoveryStrategy (§55) |
| Confirmation expiry | Instant civil ajustável, independente do monotônico | Expiração, fingerprint, policy e autorização |
| Retry schedule | Instant civil para deadline persistente | Backoff, orçamento, idempotência e não retry de UNKNOWN |
| Timezone/DST | Projeção de instantes sem mudar identidade/tempo monotônico | Recorrência por LocalDateTime/ZoneId; gap no primeiro instante válido e overlap conforme §39 |

## Morte de processo, segurança e persistência

A recriação do adaptador consulta o mesmo setting do sistema, sem UUID ou arquivo
por processo. Reboot diferente invalida a referência antiga; ausência da fonte
também impede validação. Isso não prova se um efeito externo aconteceu e não concede
retry. Nenhum estado de negócio ou schema foi criado, portanto não há migração nesta
entrega. Futuros consumidores devem persistir o par identidade/referência quando
aplicável e manter datas civis para trabalho que sobreviva a reboot.

Sem logs, payloads de usuário, permissões, componentes exportados, inicializadores,
Room, DataStore ou dependências entre módulos novas. INV-033 é preservado: não há
bootstrap de recursos do main. A identidade indisponível fica observável pelo retorno
tipado/nulo; integração com health/diagnósticos cabe aos consumidores futuros.

## Evidência de validação

Ambiente `CONTAINER`, via Docker Compose:

- Red inicial: `:core:time:testDebugUnitTest` falhou na compilação porque os contratos
  ainda não existiam; nenhuma simulação foi tratada como evidência Android.
- Validação focada: `:core:time:testDebugUnitTest :core:time:lint
  verifyModuleBoundaries testModuleBoundaries` aprovada; 12 testes, zero falhas/erros,
  lint sem ocorrências e 36 casos do verificador de fronteiras aprovados; 41 tarefas,
  22 executadas e 19 `UP-TO-DATE`.
- Validação canônica: `check assembleDebug assembleRelease` aprovada; 2204 tarefas,
  51 executadas, uma `FROM-CACHE` e 2152 `UP-TO-DATE`.

Ambiente `HOST`: revisão de diff e `git diff --check` aprovados. Esses resultados
provam contratos/cálculos JVM e build Android; não provam comportamento runtime do
provider, multiprocessos, process death, reboot, deep sleep ou hardware físico.

## Qualificação física pendente

Validar no Galaxy S21 e na matriz de APIs suportadas a leitura do setting sem
permissões, igualdade entre processos/recriações do app, incremento após reboot,
disponibilidade antes/depois do desbloqueio, elapsed realtime durante deep sleep e
independência frente a ajuste civil/timezone. Registrar dispositivo, build/API e
ambiente `PHYSICAL_DEVICE`. Comportamento OEM/provider deve ser qualificado; esta
entrega não afirma `FOUNDATION_READY`, F0_PASS ou resiliência operacional.
