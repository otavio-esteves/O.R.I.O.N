# ORION-FND-009 — Contratos restantes da Foundation

## OBJECTIVE
Completar metadata de Skills, capabilities e políticas declarativas de payload e
idempotência, além das portas mínimas de recovery, ingress, recursos, exits e actions.
Base: Arquitetura §§13, 33, 47–59, 91–92, 102; Plano Mestre §§7.3–7.4.

## FILES ALLOWED
`core/model`, `core/skills/metadata`, `core/security`, `core/actions`, `core/ingress`,
`core/recovery`, `core/resources`, `core/exitinfo` e esta documentação, com testes e
builds estritamente necessários.

## CONTRACTS INVOLVED
SkillDescriptor, Capability, ConfirmationPolicy, CapabilityRegistry,
IdempotencyKeyPolicy, DurablePayloadPolicy, RecoveryCoordinator,
IngressCoordinator, ResourceGovernor, ApplicationExitMonitor, ActionEngine.

## INVARIANTS INVOLVED
INV-004/005/006/009/011/014/018/021/023/027/028/031/033/035.
Metadata não concede autoridade; nenhuma execução externa ou persistência genérica.

## TESTS REQUIRED
Descriptor destrutivo exige confirmação; versões/identificadores válidos;
replay idêntico deduplica e payload divergente conflita; dados classificados exigem
proteção; governor/ingress/actions indisponíveis negam execução; portas compilam.
Testes e lint focados, fronteiras e build canônico em Docker.

## ACCEPTANCE CRITERIA
Todos os tipos existem e compilam, contratos testados, nenhum efeito de negócio,
retry automático, bypass de policy/barrier ou dependência proibida. APIs mínimas
podem negar recursos ainda não implementados, sem alegar fila ou execução reais.

## OUT OF SCOPE
Implementações F2/F4 de outboxes, criptografia, política de execução, fila durável,
recovery transacional, monitor/classificação de memory limiter, Skills, Cortex e voz.

## Decisões e limites
Capability é identificador funcional estático, distinto de permissão e IPC feature.
ConfirmationPolicy ALWAYS e POLICY_DEPENDENT descrevem se a confirmação é obrigatória
por metadata ou ainda depende da policy; não existe opção que dispense hard policy.
SkillDescriptor segue os onze campos normativos; os conjuntos devem ser imutáveis
fornecidos por código confiável. O executor futuro revalida a policy e capabilities.
IdempotencyKeyPolicy é declarativa: compara fingerprints SHA-256 canônicos e exige
issuer, scope, retenção positiva, versão e constraint. Não cria chave nem tabela.
DurablePayloadPolicy descreve referência/conteúdo mínimo e exigência de envelope
criptográfico para SENSITIVE/SECRET, inclusive referências assim classificadas.
As implementações indisponíveis negam inferência, efeitos e ingress mutável; nunca
retornam QUEUED sem persistência. Não há alteração arquitetural nem ADR necessário.

## Impactos
Sem estado durável, componentes Android, migração ou efeito recuperável. Perda de
objetos após morte de processo não implica replay. Diagnósticos não renderizam keys
ou fingerprints. Sem novo acesso a dados em processos auxiliares.

## Evidência
`CONTAINER`: testes inicialmente falharam por contratos ausentes. A rodada focada
com testes/lint dos módulos afetados e fronteiras passou: 334 tarefas, 158 executadas.
Revisão adicionou vínculo explícito de schema no fingerprint e testes de negação de
actions/capabilities. Após corrigir um erro de compilação no teste, nova rodada
`:core:actions:testDebugUnitTest :core:actions:lint :core:model:testDebugUnitTest
:core:model:lint` passou: 78 tarefas, nove executadas. `HOST`: diff/whitespace revisados.
Validação integrada final será registrada no relatório F1, sem presumir CI remoto.

Revisão de coerência: metadata mutável RETRY_SAFE exige modo NATURAL ou KEYED;
NONE e VERIFY_THEN_RETRY não justificam repetição sem verificação. READ_ONLY não
é bloqueado por essa restrição. Isso elimina uma contradição declarativa, sem
conceder retry nem provar que a implementação futura de idempotência é correta.
O teste red reproduziu a aceitação da combinação inválida antes da correção.
A rodada green de coerência passou em `CONTAINER`: testes e lint de metadata,
71 tarefas, 16 executadas e 55 UP-TO-DATE. O teste red havia falhado por aceitar
RETRY_SAFE mutável com NONE/VERIFY_THEN_RETRY; agora os oito casos inválidos são
rejeitados e os modos NATURAL/KEYED permanecem representáveis.
