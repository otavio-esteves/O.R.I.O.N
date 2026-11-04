# ORION-DATA-001A — Contrato de ciclo de vida de ActionRequest

## OBJECTIVE
Definir o vocabulário e validar o fluxo básico da Action Outbox antes de persistir
seus estados no schema de negócio. Base: Arquitetura V3.2 §§50–55; Plano Mestre
V1.2 §8.4; AGENTS.md §§5, 15. Parte preparatória de ORION-DATA-001, sem declarar
F0_PASS, TRANSACTION_CORE_READY ou qualificação física.

## FILES ALLOWED
`core/actions/src/main/**`, `core/actions/src/test/**` e este documento.

## CONTRACTS INVOLVED
ActionRequest status, Action Outbox lifecycle, UNKNOWN e RecoveryStrategy.

## INVARIANTS INVOLVED
INV-004/005/006/014/019/021/022. Validação estrutural não concede autorização,
não confirma payload, não interpreta lease e não substitui transação/versionamento.

## TESTS REQUIRED
Matriz independente de todos os 81 pares de estados; fluxo básico com e sem
confirmação obrigatória; falha conhecida e resultado incerto; proibição de retry
genérico de UNKNOWN e reexecução de estados concluídos; cancelamento antes da
execução; exceção determinística para transições inválidas. Testes/lint do módulo
em Docker e revisão de whitespace no host.

## ACCEPTANCE CRITERIA
Nove estados normativos, allowlist explícita, rejeição de self-transition e de
arestas desconhecidas; nenhum efeito ou mudança de disponibilidade no runtime F1.

## OUT OF SCOPE
Room/schema/migrations, confirmação/fingerprint, execução, retry/recovery e resolução
de revisão, ingress, capabilities/policy, UI, IA e voz. Qualificação física F0 e
native LOAD_TEST permanecem pendentes conforme relatório F1.

## Semântica do fluxo básico
PENDING admite NEEDS_CONFIRMATION ou CONFIRMED conforme decisão futura da Policy
Engine. Esta validação não decide se confirmação é dispensável. NEEDS_CONFIRMATION
admite CONFIRMED. CONFIRMED admite RUNNING somente após os gates de execução que
serão implementados no executor transacional. Os três estados anteriores à execução
admitem CANCELLED; cancelar um executor em andamento não prova ausência de efeito.

RUNNING admite SUCCEEDED, FAILED, UNKNOWN ou MANUAL_REVIEW conforme resultado e
estratégia declarada. UNKNOWN admite encaminhamento a MANUAL_REVIEW, nunca retorno
genérico à execução. SUCCEEDED, FAILED e CANCELLED não possuem saídas neste fluxo.
MANUAL_REVIEW aguarda um contrato específico de resolução, fora deste pacote.
Retry seguro, verificação externa e recuperação de RUNNING abandonado precisarão de
operações específicas com suas próprias evidências/gates; esta API não as autoriza.

## Impactos
Contrato puro, sem persistência, logs, permissões ou componentes Android novos.
Não há mudança de schema, migração ou health. Morte de processo perde somente
objetos locais; nada é executado/repetido. O futuro store deverá ler estado/version
persistidos e aplicar validação e optimistic concurrency na mesma transação.

## Evidência de validação
Branch local: `feat/orion-data-001-action-lifecycle`. Ambiente `CONTAINER` via
Docker Compose, sem execução no dispositivo e sem alegação de evidência física.

- Red inicial: `:core:actions:testDebugUnitTest` falhou na compilação por ausência
  de ActionStatus/ActionStateMachine, antes da implementação.
- Uma tentativa intermediária incluiu `:core:actions:testReleaseUnitTest`, tarefa
  não disponível neste módulo; a seleção falhou antes de executar os checks.
- Comando corrigido: `docker compose run --rm android-build
  :core:actions:testDebugUnitTest :core:actions:assemble :core:actions:lint
  verifyModuleBoundaries testModuleBoundaries --max-workers=2`.
- Resultado: BUILD SUCCESSFUL; 69 tarefas, 23 executadas, uma FROM-CACHE e 45
  UP-TO-DATE. Bibliotecas debug/release geradas; sete testes em três suites, zero
  falhas/erros/skips. Quatro testes novos incluem os 81 pares de estados e cenários
  de resultado incerto, cancelamento e conclusão. Os testes existentes de
  idempotência e indisponibilidade de execução continuam aprovados.
- Lint do módulo aprovado; inventário/dependências válidos e 36 casos de fronteiras
  aprovados. No `HOST`, revisão de código/escopo e whitespace dos quatro arquivos
  novos aprovados.

Entrega inicial preparada sem commit, publicação remota ou integração em main. CI remoto e
revisão de PR continuam obrigatórios antes de merge. Schema transacional e seus
testes de migração são o próximo pacote de implementação; este contrato não
substitui os gates de segurança/persistência ou a qualificação física pendente.

Atualização da rodada seguinte: contrato commitado em `09f1d0c` e branch renomeada
para `feat/orion-data-foundation`. Os contratos de confirmação/criptografia e o
store transacional foram acrescentados em pacotes separados, sem habilitar executor.
