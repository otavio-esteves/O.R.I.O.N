# F2 — Incremento de persistência, chaves e filas transacionais

Estado: incremento implementado e validado localmente. F2 permanece aberta; este
incremento não declara TRANSACTION_CORE_READY nem recovery operacional completo.

Atualização posterior: provisionamento MAIN, adapters completos de lembretes/memória,
arquivos privados temporários e infraestrutura de upgrade instalado foram tratados
no [complemento de armazenamento](F2-StorageCompletion.md). As evidências e limitações
abaixo descrevem a rodada original, anterior a esse complemento.

## Escopo entregue

- Provider MAIN de data keys, protegidas por wrapping AES-GCM Android Keystore,
  registry atômico versionado, provisionamento explícito e rotação com leitura
  histórica. Ausência/corrupção/invalidação não recria chaves silenciosamente.
- Room schema 3, migração explícita 2→3 e caminho 1→2→3; preservação de ações
  existentes. Novas tabelas para tarefas, lembretes, memórias/claims, auditoria,
  eventos técnicos, Event Outbox, receipts de consumidores e ingress.
- TaskStore cifrado; mutação local e evento de referência na mesma transação;
  controle CAS; consumidores locais com receipts duráveis; dispatcher com tentativas
  persistidas antes da entrega, backoff/jitter limitado e DEAD_LETTER.
- Action ownership: claim e audit atômicos, lease monotônica vinculada ao boot,
  conclusão somente pelo executor válido e recuperação conservadora de abandonadas
  para UNKNOWN/MANUAL_REVIEW. UNKNOWN não possui retry genérico.
- Ingress cifrado, minimizado e deduplicado; fingerprint canônico e AAD vinculado ao
  registro e validade; prioridade/TTL; processamento local atômico com gate também
  no store. Requests acima de 256 KiB são recusados antes de copiar o conteúdo.
- Startup Barrier operacional com gerações de recovery, separada de health;
  runner de etapas ordenadas e falha fechada; primitiva ainda sem grafo F4 real.
- Retenção incremental com cutoffs explícitos, preservação de estados incertos,
  revisão manual e chaves de idempotência, limites de admissão para filas.
- Harness host para matar o processo com uma transação SQLite aberta, verificar
  PID diferente e provar baseline commitado presente/mutação em voo ausente.

Pacotes: [chaves](../../core/security/KEY-LIFECYCLE.md),
[ownership](../issues/ORION-DATA-006.md), [ingress](../issues/ORION-DATA-007.md),
[persistência/outbox](../issues/ORION-DATA-008.md),
[morte durante transação](../issues/ORION-TEST-002.md).

## Contratos e invariantes

Arquitetura §§10–13, 48–57, 63–73 e Plano Mestre §8.
INV-001/004/005/006/007/008/010/011/014/018/019/021/022/023/033/035/036.
Nenhuma permissão, componente exportado ou topologia de processos nova no produto.
Os adapters não concedem autoridade; recebem decisões de componentes confiáveis.
A Foundation mantém ações/ingress mutável indisponíveis e não executa Skills.

## Processo, segurança e upgrade

As transações que falham preservam o estado anterior; fatos e mutações não ficam
separados. A morte depois do commit de um consumidor pode redeliver o evento;
consumidores que commitam mutação e receipt juntos evitam repetir a mutação
local (a implementação concreta atual é consumeTechnical). O dispatcher exige
esse contrato dos consumidores, sem torná-lo automático para qualquer callback.
Uma lease abandonada não comprova que um efeito externo deixou de ocorrer. Callback tardio não sobrescreve recovery.

TaskStore, ActionStore e IngressStore cifram conteúdo; eventos guardam referências.
DAOs de lembretes/memórias recebem envelopes opacos de produtores confiáveis; seus
adapters completos continuam pendentes e esses DAOs não autenticam ciphertext.
Não há FTS bruto, log de payload ou fallback plaintext. Chaves históricas continuam necessárias até
que todos os respectivos campos sejam migrados/removidos. O registry tem limite
explícito de versões e não elimina chaves antigas automaticamente.

A migração adiciona tabelas/índices sem apagar ou reescrever ações existentes.
Backup/transferência continuam excluídos. Probes e fixtures são de instrumentação;
nenhum arquivo de fixture contém informação pessoal.

## Validação

Comando canônico aprovado em CONTAINER (2706 tarefas: 203 executadas, 19 FROM-CACHE,
2484 UP-TO-DATE; 5m37s):

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest --max-workers=2
```

| Ambiente | Resultado final |
| --- | --- |
| CONTAINER/JVM | 196 testes em 34 suites; zero falhas, erros ou skips |
| CONTAINER/lint | zero erros; 10 avisos no app sobre versões, ABI ChromeOS e ícone; módulos novos sem ocorrências |
| CONTAINER/build | APK debug/release, AAB e APKs de instrumentação app/Keystore gerados |
| CONTAINER/gates | fronteiras/ciclos e fixtures negativas, benchmark, ELF/ABI/APK/AAB/16 KiB estáticos aprovados |
| HOST | 3 testes do harness Foundation + 3 do harness transacional; actionlint e whitespace aprovados |
| ANDROID_EMULATOR/app | 61 testes iniciais + 4 após SIGKILL/recriação + 4 após retorno permitido de Force Stop |
| ANDROID_EMULATOR/Keystore | 4 testes reais de wrapping, reopen/rotação, perda/corrupção e AtomicFile interrompido |
| ANDROID_EMULATOR/transação | SIGKILL com transação aberta; verificação em novo PID confirmou baseline e ausência da mutação em voo |

Total final Android: **74 execuções concluídas aprovadas**, incluindo as repetições
após recriação e a verificação transacional; 65 casos distintos entre app e Keystore.
A preparação deliberadamente interrompida pelo host não é contada como teste concluído.
A tentativa anterior também passou os 61 casos, mas a etapa UI falhou por diálogo
identificado como “System UI isn't responding”. A tentativa foi preservada; após
usar a ação Close app do próprio sistema, todo o harness foi repetido e passou.
Nenhum teste/detector foi removido ou suprimido.

Os testes de concorrência também expuseram um recovery obsoleto que publicava health
ou fechava o gate mais novo. Validação/publicação/release/drain agora compartilham o
lock; abort/cancelamento só afetam o ticket atual. Os nove testes de recovery passam.
A árvore de fontes/config/scripts foi congelada antes do build final e seu hash
permaneceu idêntico após build e testes. Hashes dos APKs do build coincidem com os
instalados nos dois harnesses. Release mantém somente launcher e receiver de tooling
ProfileInstaller protegido por DUMP como componentes exportados esperados.

Evidências técnicas versionadas, sem chaves/payloads pessoais:

- [Build, testes, lint, hashes e snapshot das fontes](f2-transaction-core-build-summary.json).
- [Runtime final API35](f2-transaction-core-runtime-api35.json).
- [Tentativa inicial com etapa UI bloqueada](f2-transaction-core-runtime-api35-initial.json).
- [Morte durante transação e novos PIDs](f2-transaction-core-transaction-death-api35.json).

Comandos HOST executados incluem os dois `test_*_harness.py`, actionlint em container,
`verify-foundation-runtime.py` e `verify-transaction-process-death.py` com serial
`emulator-5580`, `--environment ANDROID_EMULATOR` e ADB do host. Keystore:
`adb -s emulator-5580 shell am instrument -w network.orion.core.security.test/androidx.test.runner.AndroidJUnitRunner`.

ANDROID_EMULATOR: comportamento SQLite/Keystore/API35 é evidência NON_DEFINITIVE para hardware/OEM. PHYSICAL_DEVICE:
não executado nesta rodada; nenhum dispositivo físico estava conectado.

Durante a validação, foi corrigida uma corrida preexistente no build: os validadores
nativos podiam abrir o AAB enquanto o empacotador escrevia o arquivo. Agora dependem
do bundle pronto e aguardam assemblers de instrumentação quando agendados. Nenhum
check/fixture foi desativado; [pacote FND-016](../issues/ORION-FND-016.md).

## Pendências para fechar F2 e fases posteriores

- Integrar provisionamento seguro de chaves à inicialização/configuração real,
  comprovando armazenamento cifrado vazio antes do primeiro provisionamento.
- Completar repositórios/semântica de atualização e consulta de lembretes e memória,
  além dos records e criação transacional. Não antecipar ranking/FTS da F7.
- Conectar comandos, Policy Engine, capabilities e confirmação humana real ao fluxo
  autorizado. O executor de Skills e o runtime mutável continuam indisponíveis.
- Implementar replay específico RETRY_SAFE e verificação externa para
  VERIFY_THEN_RETRY; o tratamento atual é conservador e exige revisão.
- Montar o grafo real de RecoveryCoordinator e reconciliação de scheduler F4 antes
  de permitir efeitos externos; health/diagnósticos operacionais das novas filas,
  chaves e capacidade fazem parte dessa integração.
- Agendar manutenção/pruning e definir resolução operacional de DEAD_LETTER/revisão.
- Lifecycle de arquivos privados temporários grandes: ainda não há; o ingresso
  recusa conteúdo grande em vez de persistir blobs arbitrários.
- Upgrade com estado de negócio e artefatos reais de scheduler, qualificação S21,
  matriz de APIs/OEM e LOAD_TEST nativo arm64/16 KiB.

A fila preserva ordem e bloqueia a drenagem se seu primeiro item não puder ser
lido por indisponibilidade de chave ou retrocesso civil. Essa política segura
precisa de diagnóstico operacional na futura integração, sem descartar pedidos.

## Estimativa de desenvolvimento

A estimativa de progresso global é aproximadamente **15–20% do plano completo**.
É uma estimativa de implementação por fases, não horas restantes, linhas de código
ou aprovação de gates: F1 implementada, F0 parcialmente evidenciada, F2 avançada,
F3–F12 ainda não implementadas como produto. As fases têm esforços diferentes;
nenhum número mais preciso é sustentado pelo backlog atual.

O gate F2 continua aberto enquanto houver itens parciais acima. A existência de
schemas/portas/testes isolados não equivale a integração operacional nem a
qualificação física. Build local aprovado também não substitui CI/revisão do PR.
