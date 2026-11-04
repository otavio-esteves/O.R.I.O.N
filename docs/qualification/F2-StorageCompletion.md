# F2 — Complemento local de armazenamento e infraestrutura de upgrade

Este complemento segue o Plano Mestre §8 e não habilita Skills F3 nem o grafo de
recovery/scheduler F4. O fechamento formal de F2/TRANSACTION_CORE_READY continua
dependente da avaliação dos gates e da qualificação aplicável; aprovação local não
substitui dispositivo físico, CI obrigatória ou revisão.

## Pacotes

- [DATA-009](../issues/ORION-DATA-009.md): inicialização segura das chaves no MAIN.
  Um marker DataStore é commitado antes da primeira tentativa. Chaves existentes
  são reutilizadas; ausência após tentativa, invalidação, dados cifrados ou artefatos
  privados existentes nunca autorizam recriação. O teste de banco cobre todas as
  tabelas cifradas. FIELD_KEYS informa falha no diagnóstico, com retry seguro.
- [DATA-010](../issues/ORION-DATA-010.md): repositórios cifrados de lembretes e memória,
  consultas limitadas, CAS, edição/cancelamento de lembretes, supersessão atômica com
  histórico e claims, exclusão de conteúdo/claims e eventos de referência atômicos.
  Nenhuma atualização promove LLM_INFERRED automaticamente.
- [DATA-011](../issues/ORION-DATA-011.md): arquivos temporários privados, sempre cifrados,
  até 16 arquivos de 1 MiB e TTL máximo de 24h; referências UUID, timestamps e
  sensibilidade autenticados por AAD. O ingresso não pode expirar depois do arquivo.
  Cleanup limitado preserva arquivos ilegíveis/chaves indisponíveis e retrocesso de
  relógio; remove apenas expirados autenticados ou escrita incompleta não commitada.
- [TEST-003](../issues/ORION-TEST-003.md): fixture compatível com a versão anterior,
  substituição instalada por APK de versão maior, recuperação das chaves e leitura
  dos mesmos registros, preservação de PENDING/UNKNOWN e filas sem replay.

## Limites e consequências

Sem alteração de schema Room: versão 3 e migrações 1→2→3 permanecem. A versão do
aplicativo passa de versionCode 1 para 2, versionName 0.2.0. Não há novos componentes
Android, permissões, processos, FTS ou logs de conteúdo. Somente MAIN inicializa as
chaves e os arquivos. O backup permanece desativado.

Morte antes de commit de uma mutação não deixa evento órfão; falha no segundo evento
de supersessão reverte novo registro, histórico e claims. A perda do processo após
o marker de provisionamento e antes da chave mantém falha fechada, exigindo
remediação explícita. Arquivos commitados usam AtomicFile; falhas de autenticação
nunca autorizam exclusão automática. Não se promete apagamento físico de páginas
SQLite nem zeroização completa de memória gerenciada.

Os consumidores locais continuam transacionais e sem efeitos externos. O runtime
mantém UnavailableActionEngine, capabilities indisponíveis e ingresso mutável
desabilitado. A existência do armazenamento não concede autorização. Maintenance
periódica, reconciliação real de scheduler, resolução operacional de revisão e
replays específicos de Skills dependem dos contratos F3/F4.

## Gates do Plano Mestre §8.11

| Gate | Evidência local implementada |
| --- | --- |
| Transações | Task/Event, consumidor/receipt, Action/audit, ingress e supersessão |
| Concorrência otimista | CAS de tarefas, lembretes, memória e ações; disputas concorrentes |
| Event Outbox | Dispatcher, tentativas persistidas antes da entrega e receipts |
| Action Outbox | Persistência, transições e confirmação; execução de Skills segue F3 |
| Ownership/lease | Claim/finish guardados e auditoria atômica |
| RUNNING abandonado | UNKNOWN/MANUAL_REVIEW conservadores; nenhum retry genérico |
| Retry/backoff/dead-letter | Budget persistente, jitter limitado, poison schema |
| UNKNOWN | Persistido, sem claim/retry genérico, preservado no upgrade |
| IngressQueue | Fila cifrada, gate operacional e consumo local transacional |
| IdempotencyKeyPolicy | UNIQUE, replay e conflito sem substituição |
| DurablePayloadPolicy | Referências/minimização e envelopes cifrados |
| SENSITIVE/SECRET | Round-trip, falhas, ausência de conteúdo bruto no SQLite/arquivos |
| BootSessionId | Lease antiga/boot desconhecido não autorizam execução |
| Replay/conflito | Mesma chave/payload dedup; payload divergente conflita |
| Confirmation fingerprint | Payload/policy/expiry revalidados antes de claim |
| Payload locking | Material confirmado não possui atualização silenciosa |
| Field encryption | AES-GCM com provider versionado |
| Envelope versionado | Codec estrito e vetores sintéticos independentes |
| Nonce/AAD | Diversidade, tampering e substituição entre entidades rejeitados |
| Lifecycle de chaves | Wrapping/rotação e inicialização com marker durável |
| Backup | Manifest e exclusão de todos os domínios em teste Android |
| Retention/pruning | Batches/TTL/preservação de revisão, idempotência e arquivos |
| Migration/upgrade infrastructure | MigrationTestHelper e substituição instalada; scheduler F4 pendente |
| Morte durante transação | SIGKILL host e verificação em outro PID |

O quadro identifica implementações e testes disponíveis, sem transformar evidência
de emulador em qualificação física.

## Validação final

CONTAINER, comando canônico aprovado após limpeza dos artefatos gerados:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest --max-workers=2
```

2747 tarefas, 5m44s: 1391 executadas, 1048 FROM-CACHE e 308 UP-TO-DATE. Resultados
de cache são incluídos somente após validação dos inputs pelo Gradle. **202 testes
JVM em 35 suites**, sem falhas, erros ou skips. Lint sem erros; dez avisos existentes
no app sobre versões, ABI ChromeOS e ícone. Os módulos novos não apresentaram
ocorrências. APKs debug/release, AAB e ambos os APKs de instrumentação foram gerados.
Gates de fronteiras/ciclos, benchmark, ELF/ABI/empacotamento e alinhamento estático
16 KiB aprovados, incluindo fixtures negativas.

| Ambiente / cenário | Resultado |
| --- | --- |
| HOST / contratos dos três harnesses | 9 testes aprovados |
| ANDROID_EMULATOR API35 / suíte completa | 76 testes aprovados |
| ANDROID_EMULATOR / após SIGKILL e recriação | 4 testes aprovados |
| ANDROID_EMULATOR / retorno permitido de Force Stop | 4 testes aprovados |
| ANDROID_EMULATOR / Keystore real | 4 testes aprovados |
| ANDROID_EMULATOR / transação aberta interrompida | Baseline commitado presente; mutação em voo ausente, em outro PID |
| ANDROID_EMULATOR / upgrade instalado 1→2 | Prepare e verify aprovados, preservando chaves, campos, claims, filas e PENDING/UNKNOWN |
| PHYSICAL_DEVICE / S21 | Não executado; nenhum dispositivo físico conectado |
| CI remota / revisão | Não executada nesta sessão |

Rodada final: **91 execuções Android concluídas**, incluindo repetições e as duas
etapas do upgrade; **80 casos distintos** entre app e Keystore. A preparação
deliberadamente interrompida pelo teste de morte não conta como execução concluída.
Todos os resultados Android deste complemento são **NON_DEFINITIVE** para hardware/OEM.

O baseline do upgrade foi reconstruído de `d7a2301`, acrescentando somente a fixture
de instrumentação compatível. O APK anterior permanece versionCode 1; o candidato
é versionCode 2. O histórico UNKNOWN sintético percorre confirmação, claim e
conclusão válidos, com uma tentativa e cinco linhas de auditoria; nenhum Skill é
invocado. A verificação comprova que o upgrade não incrementa esse histórico.
Migrações 1→2→3 são comprovadas separadamente pelo MigrationTestHelper; esta
substituição instalada usa schema 3 em ambas as versões e não qualifica scheduler F4.

As tentativas anteriores foram preservadas. Uma fixture de baseline desatualizada
foi detectada pelo teste de upgrade; ela foi reconstruída a partir do snapshot fixo,
com os marcadores de auditoria conferidos no DEX. Uma rodada do harness completou
os 76 testes, mas encontrou o diálogo “System UI isn't responding” na navegação.
Após a ação oficial Close app, o harness inteiro passou. Não houve supressão de
testes nem enfraquecimento de assertions. Um conflito de artefatos incrementais
foi resolvido com clean; builds em checkouts distintos passaram a usar caches de
projeto separados e execução sequencial para o cache global compartilhado.

As fontes, configurações e scripts ficaram idênticos durante a rodada final:
snapshot de 245 arquivos, agora commitados em `b77cac1`. Hashes dos APKs do build
coincidem com os usados nos harnesses. Evidências versionadas:

- [Resumo de build, testes, lint, fontes e hashes](f2-storage-completion-build-summary.json).
- [Runtime final](f2-storage-completion-runtime-api35.json) e
  [tentativa bloqueada pelo System UI](f2-storage-completion-runtime-api35-initial.json),
  com [hierarquia do diálogo](f2-storage-completion-system-ui-anr.xml).
- [Upgrade final](f2-storage-completion-installed-upgrade-api35.json) e
  [tentativa com fixture incompatível](f2-storage-completion-installed-upgrade-api35-initial.json).
- [Morte durante transação e novos PIDs](f2-storage-completion-transaction-death-api35.json).

## Pendências externas e de fases posteriores

- PHYSICAL_DEVICE/S21: durabilidade, Keystore/OEM, upgrade e morte de processo;
  nenhum aparelho físico está conectado nesta sessão.
- CI obrigatória e revisão do PR antes de merge; commits locais não substituem CI.
- F3: handlers/Skills, Policy Engine, capabilities e confirmação humana operacional.
- F4: recovery completo, reconciliação de alarmes/workers, manutenção agendada,
  diagnósticos operacionais das filas e fluxos de revisão/replay específicos.
- F0/F5+: qualificação nativa arm64/16 KiB e matriz Android/OEM aplicável, sem
  antecipar Cortex ou voz.
