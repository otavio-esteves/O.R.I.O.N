# ORION-DATA-006 — Ownership persistido e recuperação conservadora de ações

## OBJECTIVE
Implementar claim transacional de ações confirmadas, lease monotônica vinculada ao
boot, conclusão pelo executor atual e classificação segura de RUNNING abandonado.
Base: Arquitetura V3.2 §§50–56; Plano Mestre V1.2 §§8.4–8.5.

## FILES ALLOWED
`core/actions/**`, `data/actions/**`, `data/database/**/OrionActionDao.kt`,
`app/src/androidTest/**/ActionExecutionOwnershipTest.kt` e este documento.

## CONTRACTS INVOLVED
ActionRequest, Action Outbox, ActionConfirmationBinding, SkillDescriptor,
ActionExecutionAuthority, BootSessionId e auditoria transacional.

## INVARIANTS INVOLVED
INV-004/005/006/014/018/019/021/022/023/031/036.

## TESTS REQUIRED
Fronteiras de lease/reboot/identidade ausente/regressão monotônica; todas as
combinações de recovery/effect; policy/capability/permission/presence/barrier;
claim concorrente com único vencedor; rollback de claim por falha de auditoria;
callback atrasado/owner incorreto; UNKNOWN sem retry; binding corrompido/expirado.

## ACCEPTANCE CRITERIA
Claim muda CONFIRMED→RUNNING com CAS, contador, executionId/token e ownership na
mesma transação da auditoria. O adapter recompõe o binding cifrado, exige snapshot
de confirmação íntegro e consulta portas confiáveis de policy, descriptor,
capabilities, permissões, presença e Startup Barrier. Lease de 1 ms até 5 min;
ID de boot ausente bloqueia claim. A conclusão exige execução/versão/boot/lease
ativos e admite um único terminal. Recovery preserva ownership como evidência e
nunca agenda retry: efeito externo incerto vira UNKNOWN; demais abandonados ou
estratégia MANUAL_REVIEW viram MANUAL_REVIEW. Batches usam cursor por actionId,
evitando starvation por leases ainda ativas.

## OUT OF SCOPE
Execução de Skills, engine de produção, wiring de comandos/UI/ingress, heartbeat,
verificação de estado externo, replay específico de RETRY_SAFE/VERIFY_THEN_RETRY,
resolução de revisão, qualificação física, scheduler e recovery F4 completo.

## Limites e impactos
Não muda schema: campos de ownership já existem na Room v2. Migração não é
necessária para estes campos. A nova dependência core:actions→core:skills:metadata
preserva os limites e não concede autoridade a processos auxiliares. As portas
de autoridade são síncronas, internas à chamada main; nunca são DTOs ou decisões
vindas de LLM/IPC. Runtime continua com UnavailableActionEngine.

Morte após claim antes da chamada de Skill é indistinguível de morte depois do
efeito: a recuperação conserva incerteza. RETRY_SAFE não recebe replay genérico;
um fluxo específico futuro deve provar segurança e revalidar todas as condições.
UNKNOWN só segue para revisão por contrato separado, sem voltar a RUNNING.
Nenhum conteúdo de argumentos/target/chaves é registrado em logs; resultados e
leases redigem dados nas representações textuais. O provider de chaves não é
contornado por ownership: claim depende da leitura autenticada da ação.

## Validação
Verificações locais `HOST`: `git diff --check` e validação de fronteiras passaram.
Testes JVM e instrumentados foram adicionados; execução Docker/Android será
registrada no relatório integrado. Este documento não declara aprovação desses
testes antes da rodada centralizada, nem TRANSACTION_CORE_READY.
