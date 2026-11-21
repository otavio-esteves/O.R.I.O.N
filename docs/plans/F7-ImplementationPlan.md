# F7 — Plano de implementação de memória inteligente

**Status.** O [fechamento de software](../qualification/F7-SoftwareCloseout.md)
registra a implementação dos pacotes e as evidências de validação. O gate
`MEMORY_ENGINE_READY` permanece aberto.

Autoridade: [Arquitetura V3.2, §§62–77](../../1-orion.md#62-memory-engine),
[Plano Mestre V1.2, §13](../../2-plano-mestre.md#13-fase-7--intelligent-memory),
[AGENTS.md](../../AGENTS.md) e ADRs aceitos, dentro de seus escopos.
As correções precedentes estão delimitadas em
[ORION-F6-AUDIT-001](../issues/ORION-F6-AUDIT-001.md), com
[validação registrada](../qualification/F6-AuditFollowup-20261118.md) e revisão
separada no [PR #169](https://github.com/otavio-esteves/O.R.I.O.N/pull/169).

## 1. Entrada e limites

A revisão local de saída da F6 está registrada no
[fechamento de software](../qualification/F6-SoftwareCloseout.md), com o gate
cognitivo do Plano Mestre §12.7 e as regressões JVM/Android.
[ADR-0005](../adr/ADR-0005-f7-software-entry.md) autoriza a entrada de software
da F7 com os gates físicos, de estabilidade e de produção ainda abertos.
Cada pacote tem branch própria e segue sujeito a CI obrigatório verde antes
do merge.

O plano utiliza a topologia, a autoridade e a persistência já definidas. MAIN
continua dono de Room, repositórios, política, ingress, Action Outbox e Skills.
`:ai` recebe apenas DTOs selecionados e produz inferência sem autoridade de escrita.
Qualquer mudança real de boundary exige o ADR previsto em AGENTS §1.1.

Sem embeddings obrigatórios, novo processo, serviço de nuvem, execução de memória
por texto livre, auto-confirmação, novo perfil de produção ou dependência de
Cortex no startup/recovery. Sentinel, voz e aprendizado adaptativo pertencem às
fases seguintes. Os pacotes determinísticos devem ser úteis com Cortex ausente.

## 2. Ponto de partida verificado no código

| Já existe | Lacuna de F7 |
| --- | --- |
| Room v6; `MemoryEntity` e `MemoryClaimEntity`, conteúdo em envelopes criptográficos e versões | Completar metadados e ciclo de vida normativos; migrações aditivas e compatíveis |
| Origens e sensibilidade tipadas; guardas contra confirmação automática de inferência | Expor provenance/status e implementar operações explícitas para claims inferidas |
| Primitiva transacional `supersedeWithEvents` e validade de claims | Integrar correção/supersession ao caminho autorizado de produto e aos índices |
| `OrionMemoryQuery`, `MemoryGateway` e tela de memórias declaradas pelo usuário | Busca textual, ranking, paginação e filtros de estado/sensibilidade |
| Criação, exclusão e esquecimento via ingress/policy/ActionRequest | Ampliar controles sem perder confirmação exata, idempotência ou erasure das cópias |
| Esquecimento remove claims, cópias de ações/ingress e mantém proteção mínima de replay | Cobrir também futuros FTS, resumos, histórico e caches derivados |
| `OrionContextBudget` já possui prioridade `MEMORY` | Selecionar memórias relevantes em MAIN e incluí-las sob orçamento e política |
| Chat F6 limitado e efêmero, com histórico selecionado cronologicamente após a correção | Histórico persistido quando autorizado, resumo de sessão e recuperação contextual |

Não recriar esses componentes nem abrir um caminho paralelo de mutação. Schema 6
é o ponto de partida observado; o número de cada migração futura será confirmado
na integração do respectivo pacote, evitando reservar versões que possam conflitar.

## 3. Sequência dos pacotes

Os identificadores abaixo são pacotes propostos; não representam issues remotas
já abertas. Cada pacote vira uma issue/branch/PR de responsabilidade única.

| Ordem | Pacote | Entrega | Depende de |
| --- | --- | --- | --- |
| 1 | ORION-F7-001 | Contratos, provenance e ciclo de vida persistente | Entrada de F7 registrada |
| 2 | ORION-F7-002 | Claims e correção/supersession autorizadas | 001 |
| 3 | ORION-F7-003 | Índice FTS derivado com política de sensibilidade | 001; contratos de mutação de 002 |
| 4 | ORION-F7-004 | Busca e ranking determinísticos | 002, 003 |
| 5 | ORION-F7-005 | Controles completos de memória e esquecimento | 002–004 |
| 6 | ORION-F7-006 | Histórico e resumo de sessão com provenance | 001, 002, 005 |
| 7 | ORION-F7-007 | Retrieval no contexto do chat | 004–006 |
| 8 | ORION-F7-008 | Integração, recovery/upgrade e auditoria do gate | 001–007 |

### ORION-F7-001 — Contratos, provenance e ciclo de vida

**OBJECTIVE:** completar a representação normativa antes de adicionar inferência.
Definir DTOs selecionados de leitura e comandos normalizados com limites explícitos.
Mapear tipos de memória, provenance, confidence, inferred, confirmação humana,
origem/evento, validade, expiração, importância, summary/tags e versionamento.

**FILES ALLOWED:** `core/model`, contratos pertinentes em `core/commands`,
`core/queries`, `core/orchestrator/memories`, `data/database`, `data/memory`,
schemas exportados e testes relacionados.

**CONTRACTS / INVARIANTS:** Arquitetura §§63–69; INV-001/007/010/011/019/035.
Preservar origem e evidência original; dados legados não recebem origem inferida,
confirmação ou confiança fabricadas. Introduzir os estados normativos necessários
sem reativar registros legados `DELETED` ou reinterpretar tombstones.

**TESTS REQUIRED:** migração desde todos os schemas suportados, round-trip de
envelopes/chaves, defaults conservadores, limites e estados inválidos, origem
inferida sem promoção automática, falhas de chave e rollback transacional.

**ACCEPTANCE:** instalação nova e upgrade preservam IDs, conteúdo, versões,
confirmações e proteção de replay; DTOs não expõem campos indevidos. Cada
alteração durável tem migração e invariantes pós-migração verificáveis.

**OUT OF SCOPE:** FTS, ranking, geração de resumos, UI nova ou ativação de Cortex.

### ORION-F7-002 — Claims, confirmação humana e supersession

**OBJECTIVE:** implementar operações explícitas de correção, confirmação/rejeição
de claim e substituição de memória. Reutilizar `supersedeWithEvents` quando
compatível; memória, claims, estado da ação, audit e eventos devem compartilhar
a transação lógica. Múltiplos fatos independentes exigem claims independentes.

**FILES ALLOWED:** contratos de comandos/Skills, `core/policy`,
`core/skills/metadata`, `skills/memory`, `app/memories`, `data/memory`, DAOs e testes
estritamente relacionados; registro explícito dos fatos novos em `core/events`/
`data/events`; schema somente se necessário e com migração.

**CONTRACTS / INVARIANTS:** §§64–68; INV-004/007/008/010/018/019/021–023/031/035.
Definir descriptor, canonicalização, chave de idempotência, retenção, recuperação
e confirmação exata por operação. Uma confirmação humana não pode apagar a
provenance da inferência; sua evidência deve permanecer explícita. Mudanças
materiais exigem nova confirmação. Nenhuma inferência se confirma automaticamente.
Afirmações sensíveis ou prejudiciais não podem ser consolidadas apenas por inferência.
Campos sugeridos pelo LLM não podem definir autoridade, provenance confiável ou
confirmação; a fonte efetiva e os limites de confidence são validados pelo Core.

**TESTS REQUIRED:** conflito de versão, dois corretores concorrentes, replay
igual/conflitante, payload alterado, confirmação expirada, cadeia de supersession,
validade de claims, morte antes/depois do commit e evento duplicado.

**ACCEPTANCE:** substituição e histórico permanecem coerentes após rollback e
recovery; uma operação não confirma claims independentes que o usuário não revisou.

**OUT OF SCOPE:** extração automática de fatos, FTS e confirmação por texto do LLM.

### ORION-F7-003 — FTS derivado e sensibilidade

**OBJECTIVE:** criar busca Room/SQLite FTS apenas sobre conteúdo elegível.
`PUBLIC/PERSONAL` depende da política; conteúdo bruto `SENSITIVE` fica fora e
`SECRET` nunca é indexado. Resumo redigido só é elegível se não revelar o conteúdo
protegido e a política permitir; não implementar redaction presumida por LLM.

**FILES ALLOWED:** DAOs/entities/schema FTS em `data/database`, manutenção e
adapters de memória pertinentes, integração mínima com as mutações existentes,
migrações e testes de armazenamento/upgrade; DTOs de disponibilidade em Core e
observações de health/diagnóstico necessárias para expor rebuild/degradação.

**CONTRACTS / INVARIANTS:** §§69–74; INV-001/007/010/011/019/035.
O índice é reconstruível; a memória continua fonte de verdade. Definir backfill
incremental com cursor persistido, limite de lote e retomada, incluindo falhas de
chave. Exclusão e mudança para sensibilidade inelegível removem o conteúdo FTS na
mesma transação da mutação autoritativa; apenas ocultar um hit não remove texto
protegido do índice. Se a remoção falhar, a mutação faz rollback. Inclusão/rebuild
elegível pode ser incremental, mantendo resultados indisponíveis até validação.

**TESTS REQUIRED:** marcadores sintéticos por nível de sensibilidade em tabelas
FTS e consultas; reclassificação, supersession, exclusão, migração, backfill
interrompido, downgrade de disponibilidade de chave e reconstrução duplicada.

**ACCEPTANCE:** zero conteúdo bruto SENSITIVE/SECRET no FTS; exclusão e mudança
de elegibilidade não deixam hits antigos; índice incompleto é degradação observável
do subsistema, sem se tornar pré-requisito de recovery do Core.

**OUT OF SCOPE:** embeddings, biblioteca de busca externa e alteração da proteção
primária por envelopes/Keystore.

### ORION-F7-004 — Retrieval e ranking determinísticos

**OBJECTIVE:** implementar `normalização → FTS → candidatos → filtros atuais →
ranking → DTO selecionado`. Combinar text score, recency, importância, confidence,
relevância e penalidades de estado com critérios explicáveis e desempate estável.

**FILES ALLOWED:** queries/use cases em Core, `data/memory`, DAO de leitura,
configuração de calibração pertinente e testes. Nenhum acesso de feature a Room.

**CONTRACTS / INVARIANTS:** §§68/69/74/76; INV-001/009–011/019/035.
Paginar candidatos e resultados, reutilizando os limites técnicos das queries
existentes; não carregar todo o banco. Aplicar status, validade, expiração e
sensibilidade também sobre o estado autoritativo atual, inclusive durante backfill.
Memória superseded/expirada não deve reaparecer como fato vigente. Acesso a
histórico, quando solicitado, deve identificá-lo como histórico. Busca não atualiza
`lastAccessedAt`/contadores: eventual contabilização é comando separado e limitado.

**TESTS REQUIRED:** ranking em corpus fixo, empate/paginação, relógio avançado e
recuado, validade, status, query malformada/longa, indisponibilidade do índice e
chaves; limites de custo/candidatos em Docker sem inferência.

**ACCEPTANCE:** resultado reproduzível, limitado, com provenance/confidence/status;
entrada de busca não vira SQL livre nem mutação implícita.

**OUT OF SCOPE:** ranking por LLM, treinamento e benchmark físico definitivo.

### ORION-F7-005 — Controles e esquecimento completo

**OBJECTIVE:** permitir visualizar provenance/status/claims, corrigir, confirmar
ou rejeitar claim inferida, excluir, solicitar esquecimento e limpar seleção
permitida. Conectar as operações de 002 à UI e ampliar o erasure existente.

**FILES ALLOWED:** `feature/memories`, gateways e adapters de memória, descriptors/
policy já delimitados, manutenção de cópias/índices e testes Compose/persistência.

**CONTRACTS / INVARIANTS:** Plano Mestre §13.7; INV-004/007/010/011/018–023/031/035.
Esquecimento cobre entidade, claims, FTS, cópias de processamento/ingress/ações,
resumos e caches derivados aplicáveis. Preservar somente audit/replay mínimos sem
conteúdo. Não destruir uma chave compartilhada para esquecer apenas uma memória.

**TESTS REQUIRED:** fluxo de revisão humana com vínculo exato, rejeição sem
promoção, conflito concorrente, rollback/process death durante esquecimento,
replay após erasure, nenhuma cópia residual pesquisável e falta de chave.

**ACCEPTANCE:** controles são verificáveis pela UI; a memória esquecida não volta
por replay, índice, resumo ou contexto. Não prometer apagamento físico de flash.

**OUT OF SCOPE:** exportação/cloud backup, voz e apagamento físico garantido.

### ORION-F7-006 — Histórico e resumo de sessão

**OBJECTIVE:** implementar a combinação normativa `recent window + session summary
+ relevant memories`. Persistência de histórico deve ter controle explícito de
ativação, classificação, retenção/limites e limpeza; resumo não substitui histórico.

**FILES ALLOWED:** contratos de conversa/resumo, armazenamento MAIN e migrações,
`core/orchestrator/chat`, adapters existentes de Cortex, controles de histórico e
registro dos eventos novos, e testes relacionados. Não alterar AIDL ou criar um
segundo executor de Skills.

**CONTRACTS / INVARIANTS:** §§65/66/69–73/77; INV-001/004/007/009–014/019/021–023/
027/028/035. Classificar proveniência como SYSTEM_DERIVED ou LLM_INFERRED conforme
o conteúdo. Derivação mecânica não pode encobrir uma nova afirmação inferida.
Qualquer persistência passa pelo caminho autorizado de ingress/policy/ação.
Novas tabelas devem entrar no inventário que protege o provisionamento de chaves,
na retenção e na verificação de backup desativado. Chave indisponível preserva os
dados e bloqueia processamento, sem provisionar uma chave substituta silenciosa.

**TESTS REQUIRED:** opt-in e retenção, histórico cronológico, resumo vinculado à
versão exata do histórico, resposta parcial/inválida, cancelamento e morte de AI,
retry explícito, fonte excluída ou alterada durante geração, limpeza e upgrade.

**ACCEPTANCE:** resumo nunca cria fato confirmado nem substitui silenciosamente
as fontes. Sem perfil aprovado/Cortex, histórico e busca determinística continuam
úteis; a geração fica indisponível de forma observável. Teste sintético/emulador
não configura perfil de produção.

**OUT OF SCOPE:** promoção automática de inferência, histórico ilimitado e treino.

### ORION-F7-007 — Memórias no orçamento de contexto

**OBJECTIVE:** integrar retrieval MAIN ao chat existente, com memórias relevantes
na prioridade `MEMORY`, depois de policy/request/Skill/safety e antes da conversa.

**FILES ALLOWED:** use cases de retrieval, `core/orchestrator/chat`, `core/context`,
adapter de composição em app e testes JVM/Android pertinentes.

**CONTRACTS / INVARIANTS:** §§74–77; INV-001/009–011/018/019/027/035.
Revalidar referências e elegibilidade ao compor o prompt; uma fonte excluída,
superseded ou reclassificada durante a consulta não mantém autoridade no contexto.
Incluir só DTOs minimizados permitidos; conteúdo recuperado é dado não confiável.
Preservar contagem de tokens do prompt renderizado, reserva de saída e descarte
de blocos inteiros. Nunca promover memória por ela ter aparecido no prompt.

**TESTS REQUIRED:** memória pertinente incluída, irrelevante omitida, prioridade e
orçamento, SECRET/SENSITIVE bloqueados conforme política, deleção concorrente,
prompt injection em fonte, histórico com correções e Fast Paths sem Cortex.

**ACCEPTANCE:** retrieval é limitado e observável; contexto respeita sensibilidade,
provenance e orçamento; nenhuma consulta ou saída do LLM executa uma Skill.

**OUT OF SCOPE:** novos modelos, mudanças de protocolo, preload ou autorização por LLM.

### ORION-F7-008 — Integração e evidência de saída

**OBJECTIVE:** auditar os onze itens do gate §13.8 de ponta a ponta e registrar
evidência reprodutível, incluindo falhas e limites de ambiente.

**FILES ALLOWED:** testes integrados de F7, harness/evidência necessários e
documentação de gate; correções devem voltar ao pacote responsável, sem waiver.

**CONTRACTS / INVARIANTS:** todos os contratos acima; foco em INV-001/004/007–011/
014/019/021–023/027/028/035/036 e ADR-0001.

**TESTS REQUIRED:** migrações e atualização instalada, morte real de MAIN nos
commits críticos, morte de AI durante resumo, replays, TTL/relógio, backfill,
supersession, exclusão, consistência FTS, controle de claims e fluxo contextual.
Docker cobre build/JVM/lint/dependências/packaging; HOST opera ADB; resultados de
emulador e dispositivo físico permanecem identificados separadamente.

**ACCEPTANCE:** cada item da matriz abaixo possui código, teste e resultado;
inventariar apenas módulos ativos e contar falhas/skips, não apenas exit code de
ADB. Aprovação de software, física, estabilidade e produção não são confundidas.

**OUT OF SCOPE:** fechar gates por documentação, ignorar teste correto ou declarar
`MEMORY_ENGINE_READY` enquanto qualquer critério aplicável permanecer aberto.

## 4. Matriz do gate F7

A matriz define os critérios da revisão de saída. A evidência obtida durante
a implementação está no [fechamento de software](../qualification/F7-SoftwareCloseout.md).

| Critério do Plano Mestre §13.8 | Pacotes | Evidência de aceite |
| --- | --- | --- |
| FTS | 003/004 | Busca, migração/backfill e reconstrução consistentes |
| Provenance | 001/002/005 | Metadados preservados no upgrade e visíveis na revisão |
| Claims | 001/002/005 | Fatos independentes e revisão individual transacional |
| Supersession | 002/004/005 | Histórico coerente, CAS e recovery sem dois fatos vigentes conflitantes |
| Sensitivity filters | 003/004/007 | Filtros no índice, leitura autoritativa e prompt |
| No SECRET FTS | 003/005/008 | Marcadores ausentes antes/depois de upgrade, rebuild e exclusão |
| No inferred→confirmed automático | 001/002/006/007 | Inferência adversarial não produz confirmação ou autoridade |
| Conversation summaries | 006 | Fontes preservadas, provenance, invalidação e cancelamento |
| Context retrieval | 004/007 | Relevância, limites e contagem de tokens renderizados |
| Visualizar/corrigir/apagar/esquecer | 005 | Fluxos UI/ingress/policy/ActionRequest com confirmação exata |
| Remoção de índices derivados | 003/005/006/008 | Sem hits/cópias derivados após exclusão; rollback/replay seguros |

## 5. Primeiro incremento

**ORION-F7-001** começa pela projeção de proveniência e pela referência
opcional ao evento de origem na migração de Room 6→7. Validade, expiração e
transições ainda fazem parte do pacote 001. Em seguida vem a correção
transacional de 002. A primeira entrega útil de busca resulta de 003 + 004 sobre
memórias já declaradas pelo usuário; não depende de inferência. Os controles de
005 precedem a persistência e a geração de resumos de 006.

Cada PR deve registrar efeitos sobre recovery/process death, privacidade/logs,
upgrade e retenção, e os comandos/resultados realmente executados. Nenhum estado
de implementação ou qualificação é inferido da existência deste plano.
