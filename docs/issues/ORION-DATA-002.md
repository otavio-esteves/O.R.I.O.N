# ORION-DATA-002 — Confirmation binding puro

## OBJECTIVE
Implementar o vínculo imutável entre uma confirmação e os campos normalizados de
ActionRequest. Base: Arquitetura V3.2 §51.1; Plano Mestre V1.2 §8.5;
AGENTS.md §§17/19. Não concede autoridade nem executa uma ação.

## FILES ALLOWED
`core/actions/**` e este documento. Dependências permitidas: `core:model` para
metadata normativa e `core:time` para WallClock injetado.

## CONTRACTS INVOLVED
NormalizedActionRequest, normalizedPayloadHash, confirmationFingerprint,
confirmedPayloadHash, payloadLockedAt, confirmação expirada e policy version.

## INVARIANTS INVOLVED
INV-021/022/018/019. O executor futuro deve revalidar capabilities, permissões,
policy, Startup Barrier e versão persistida, na mesma transação da aquisição.

## TESTS REQUIRED
Mudança independente de cada campo material, actionId e policy; concatenações
ambíguas; determinismo Unicode; argumento normalizado vazio; corrupção dos hashes;
expiração exata; clock rollback; ausência de dados sensíveis em toString.
Testes/lint/fronteiras no Docker e revisão do diff no host.

## ACCEPTANCE CRITERIA
Hashes SHA-256 versionados com campos delimitados por tamanho; valores imutáveis;
nenhum campo confirmado é alterado silenciosamente; divergência/expiração/recuo do
relógio geram resultado bloqueador tipado. VALID_BINDING valida somente o vínculo.

## OUT OF SCOPE
Normalização/parsing JSON, Policy Engine, autenticidade do gesto do usuário,
persistência, execução, IPC, Skill, UI e migrações. Sem alegação de F2 concluída.

## Semântica
O chamador entrega argumentos e target já normalizados por código confiável.
Strings são preservadas exatamente; JSON semanticamente equivalente deve ser
canonizado antes desta fronteira. Nenhuma inferência de schema ou autorização.
UTF-16 malformado é rejeitado para impedir colisões por substituição UTF-8.

O hash de payload inclui skill, argumentos, target, authorizationLevel,
argumentSchemaVersion, sideEffectClass, recoveryStrategy e idempotencyMode. Os três
últimos também são campos materiais imutáveis segundo §51.1, mesmo não aparecendo
na fórmula conceitual mínima. Fingerprint inclui hash de payload, actionId e
policyVersion. Ambos usam domínio/versionamento e strings UTF-8 com comprimento
inteiro big-endian, sem concatenação ambígua.

recordConfirmation captura uma declaração cujo gesto/autorização deve ser validado
pelo chamador futuro. Não é autenticação ou assinatura. Snapshot transporta apenas
IDs, hashes, versão de policy e datas; não armazena os argumentos. Campos são vals,
e copy cria outro request/snapshot sujeito a nova validação. Mudar campos materiais
invalida o vínculo; não há setter, reparação ou reconfirmação automática.

CONFIRMATION_STALE inclui confirmedAt futuro ou lock divergente da confirmação;
retrocesso civil antes da confirmação bloqueia, sem fallback monotônico. Limite
de expiração é inclusivo. Policy diferente exige reconfirmação. Nenhum resultado
permite executar isoladamente.

## Impactos
Sem storage, componentes, permissões, logs ou migração. Morte de processo não
reconstitui autorização; o futuro store deve persistir request e snapshot
atomicamente, revalidando o vínculo ao recuperar. toString é redigido para requests,
hashes e snapshots. A expiração continua sujeita ao relógio civil conforme baseline.

## Evidência de validação
Ambiente `CONTAINER`, Docker Compose. A tentativa isolada inicial falhou antes da
execução do build por timeout do lock de journal compartilhado entre containers;
nenhum teste foi executado nessa tentativa. A validação posterior foi serializada
pelo agente principal para evitar essa disputa de cache.

Na rodada central, `:core:actions:testDebugUnitTest` e `:core:actions:lint`
concluíram com sucesso. XML: 15 testes novos de confirmation binding, zero
falhas/erros/skips; módulo completo: 22 testes em quatro suites. Lint do módulo:
zero ocorrências. Os testes incluem vetores SHA-256 V1 independentes gerados com
Python hashlib/struct para fixar a codificação de campos.

O comando integrado ainda estava executando outros módulos/fronteiras ao registrar
esta evidência por tarefa. O relatório final integrado é responsabilidade da rodada
central; este pacote não presume seu resultado ou CI remoto. `HOST`: revisão de
diff/whitespace aprovada. Sem execução no dispositivo nesta validação.
