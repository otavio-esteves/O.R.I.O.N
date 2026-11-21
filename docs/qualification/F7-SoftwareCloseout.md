# F7 — Fechamento de software

A F7 integra busca de memória, revisão humana de afirmações e uso de memórias
relevantes no chat. O [ADR-0005](../adr/ADR-0005-f7-software-entry.md) autoriza
esse trabalho de software. A qualificação física, a estabilidade, o perfil
Cortex de produção e a declaração `MEMORY_ENGINE_READY` seguem abertos.

## Critérios do Plano Mestre §13.8

| Critério | Evidência no candidato |
| --- | --- |
| FTS | `OrionMemorySearchIndexTest` verifica interseção de termos, ranking, paginação, migração e retomada do backfill após falta de chave. |
| Proveniência | `OrionMemoryProvenanceMigrationTest` verifica a preservação de origem, confiança e confirmação nos upgrades. A tela mostra origem, confiança e estado. |
| Afirmações | `OrionMemoryKernelTest` verifica confirmação e rejeição individuais. A operação preserva origem e confiança e rejeita revisão com versão desatualizada. |
| Substituição | `OrionMemoryKernelTest` verifica correção vinculada à versão original, idempotência e conflito. `OrionMemorySearchIndexTest` verifica a remoção das postagens substituídas. |
| Sensibilidade | Apenas memórias `PERSONAL` declaradas e confirmadas pelo usuário são elegíveis para o índice. A consulta de contexto revalida versão, estado, validade e sensibilidade em `MAIN`. |
| Ausência de `SECRET` no FTS | `OrionMemorySearchIndexTest` verifica diretamente as postagens com marcadores sintéticos `PERSONAL`, `SENSITIVE` e `SECRET` e testa a remoção imediata por reclassificação. |
| Inferência sem promoção automática | `OrionMemoryKernelTest` verifica que replay de criação não promove memória inferida e que a confirmação humana se limita à afirmação selecionada. |
| Resumos de conversa | `OrionChatContextTest` verifica o resumo extrativo de mensagens anteriores do usuário. `OrionMemoryKernelTest` cobre arquivo de conversa com confirmação exata, proveniência das mensagens e remoção das cópias duráveis. |
| Recuperação para contexto | `OrionChatContextTest` verifica o tratamento de memória como dado não confiável, a prioridade no orçamento e a exclusão de arquivos locais do prompt. `OrionMemorySearchIndexTest` verifica a exclusão de uma fonte removida entre busca e seleção. |
| Visualizar, corrigir e esquecer | A tela oferece busca, revisão, correção e esquecimento. Os testes de UI verificam confirmação separada para guardar e esquecer. Os testes do kernel verificam a vinculação exata da correção e da revisão de afirmações. |
| Remoção de índices derivados | `OrionMemoryTransactionProcessDeathTest` verifica rollback após `SIGKILL` durante o esquecimento, remoção de postagens e cópias após confirmação explícita e replay sem ressurreição. |

## Execuções e integração

O [registro do candidato](f7-software-gate-20261121.json) preserva a execução
de referência, os hashes dos APKs e o commit de origem. Docker registrou 842
testes JVM e 36 contratos de dependência aprovados. O emulador Android API 35,
x86_64 com páginas de 4 KiB, registrou 70 testes instrumentados aprovados, o
upgrade instalado 8→9 e a recuperação em um novo processo após `SIGKILL`.

Os resultados de CI e a integração de cada pacote são rastreados nos PRs
[#184](https://github.com/otavio-esteves/O.R.I.O.N/pull/184),
[#188](https://github.com/otavio-esteves/O.R.I.O.N/pull/188),
[#187](https://github.com/otavio-esteves/O.R.I.O.N/pull/187),
[#185](https://github.com/otavio-esteves/O.R.I.O.N/pull/185) e
[#189](https://github.com/otavio-esteves/O.R.I.O.N/pull/189). O status exigido
para integração é `docker-build-and-check`.

### Revisão após integração

O [PR #189](https://github.com/otavio-esteves/O.R.I.O.N/pull/189) foi integrado
à `main` em `8ff4530`. O
[CI obrigatório](https://github.com/otavio-esteves/O.R.I.O.N/actions/runs/44932089340/job/136944866675)
passou no head `5ad2875`, com build canônico e execução de chat sem modelo.
Os PRs #185 e #187 foram encerrados após seus commits entrarem pelo #189.
As oito issues de software da F7 foram encerradas. Os campos `PENDING` do
JSON registram o estado no momento da execução do candidato `7bc02e3`.

O resumo de sessão é extrativo e executa em MAIN. O cenário de morte de
`:ai` durante geração de resumo previsto no plano não se aplica a essa
implementação. A morte de `:ai` durante inferência do chat tem
[evidência separada na F6](F6-HybridSoftware-20261118.md).

Durante o upgrade da F8, a execução no emulador API 35 revelou uma falha
intermitente na checagem de integridade FTS4 por conexão somente leitura.
A correção preserva a checagem original e fixa a conexão gravável durante
a inspeção. [Evidência da F8](F8-SoftwareCloseout.md#upgrade-e-evidências)
