# ORION-FND-010 — Storage, health e observabilidade da Foundation

## OBJECTIVE
Abrir Room e ler Preferences DataStore somente em MAIN/desbloqueado; agregar
health básico e prover logging estruturado sem conteúdo do usuário.
Base: Arquitetura §§6.1.1, 46, 49, 96, 99–101; Plano Mestre §§7.6–7.8; ADR-0001.

## FILES ALLOWED
`core/common`, `core/health`, `core/observability`, `data/database`, `data/datastore`,
catálogo de dependências, build raiz, ToolchainProfile e esta documentação.

## CONTRACTS INVOLVED
OrionProcessRole, storage guard, OrionHealth, OrionClock, logger, Room,
Preferences DataStore. Schema v1 técnico exportado, sem entidades de negócio F2.

## INVARIANTS INVOLVED
INV-001/007/011/019/032/033/035. Storage principal credential-protected;
processos auxiliares e Direct Boot não podem abri-lo. Sem destructive migration.

## TESTS REQUIRED
Matriz de acesso por role/unlock/storage; health crítico vs opcional, snapshots;
logging com clock injetado e campos estáticos; abertura/reabertura e leitura de
Room/DataStore em testes Android no pacote de integração FND-011.
Testes/lint/fronteiras/build Docker; teste real Android identificado por ambiente.

## ACCEPTANCE CRITERIA
Factories guardadas antes de criar arquivos; storage abre fora da thread UI;
schema exportado e validado; falhas são explícitas, sem apagar dados ou usar
plaintext fallback; health distingue storage crítico e subsistemas indisponíveis.

## OUT OF SCOPE
Schema de negócio/outboxes F2, criptografia, migração de schema anterior inexistente,
configurações mutáveis, recovery operacional F4, inferência e voz.

## Decisões de storage e evolução
O Room inicial tem somente `orion_storage_metadata` com uma linha técnica de formato
criada na transação de criação. Nenhuma entrada pessoal, histórico ou contador de
sessões é persistido. A verificação lê a metadata e quick_check. Futuras tabelas F2
exigirão versão e migração explícitas, preservando a instalação v1; não há fallback
destrutivo. Preferences DataStore contém apenas configuração técnica não sensível;
a Foundation somente lê os defaults e não oferece escrita de preferências.

Room 2.8.5, DataStore 1.3.0 e KSP 2.3.10 são fixados no catálogo, seguindo as
fontes oficiais consultadas: https://developer.android.com/jetpack/androidx/releases/room,
https://developer.android.com/jetpack/androidx/releases/datastore e
https://kotlinlang.org/docs/ksp-quickstart.html. Kotlin/AGP/SDK permanecem os fixados.
A adição do processador KSP é registrada como extensão do ToolchainProfile.

## Impactos
Morte na criação do banco é protegida pela transação de criação do Room; startup
seguinte reabre e verifica, sem marcar ações como concluídas. Falhas de corrupção
ou configuração bloqueiam prontidão; não há exclusão automática. Logs contêm
somente UUID técnico, enums estáticos, severidade e timestamp do clock injetado.
Não há logs em arquivo, dados pessoais, novo componente exportado ou permissão.

## Evidência
`CONTAINER`: testes/lint de common, health e observability; assemble/lint de database
e datastore; verifyModuleBoundaries/testModuleBoundaries passaram. 225 tarefas,
202 executadas e 23 FROM-CACHE; schema v1 exportado por KSP. O teste do guard cobre
os 16 casos role × unlock × storage; a verificação real por processo é FND-011.
Testes instrumentados e validação canônica final continuam registrados no gate F1.
`HOST`: revisão do diff e do schema técnico, sem destructive fallback.
