# ORION-FND-014 — Verificação integrada e gate F1

## OBJECTIVE
Consolidar a evidência de cada requisito F1 e incluir compilação do APK de testes
no CI canônico, mantendo qualificação física/API separada. Base: Plano Mestre
§§7.1–7.9, Arquitetura Fase 1, AGENTS §§6/31–34, ADR-0001.

## FILES ALLOWED
`.github/workflows/ci.yml`, `README.md`, `docs/qualification/**`, esta documentação
e metadata técnica do recorder em `scripts/android/verify-foundation-runtime.py`.

## CONTRACTS INVOLVED
Gate FOUNDATION_READY, contratos F1, processos/ownership, evidência versionada,
NATIVE_COMPATIBILITY_GATE, CI docker-build-and-check.

## INVARIANTS INVOLVED
INV-001/009/011/012/014/018/023/024/027/028/031/032/033/034/035/036.
Sem nova implementação de arquitetura ou alteração de autoridade.

## TESTS REQUIRED
`check assembleDebug assembleRelease :app:assembleDebugAndroidTest --max-workers=2`
em Docker; harness host de navegação/recriação/processos/Force Stop/guardas IPC e
backup. Revisar manifest release, schema exportado, dependências, whitespace e
estado Git. Acompanhar CI remoto em PRs por pacote quando disponível.

## ACCEPTANCE CRITERIA
Todos os requisitos implementáveis F1 têm evidência, checks locais e CI básicos
verdes; status e limitações explícitos. Nunca tratar emulador como PHYSICAL_DEVICE
ou declarar native load/F0_PASS/resiliência F4 a partir de testes básicos.

## OUT OF SCOPE
Merge administrativo sem revisão, F0 física, runtime API 37/OEM, load nativo físico,
F2, recovery operacional F4 e todas as fases posteriores.

## Impactos
Somente automação/documentação e metadata não sensível do recorder. Sem schema,
permissão, efeito de negócio ou migração. A restauração após Force Stop depende de
retorno permitido pelo Android; não há bypass. Sem nova agenda ou serviço.

## Evidência final local
Comando canônico aprovado (2608 tarefas), 124 testes JVM/25 suites sem falhas, lint
com zero erros e nove avisos explícitos não suprimidos. Harness final passou em
13 execuções Android; após reinstalação estável, mais quatro testes passaram. JSONs
registram ambiente, APK/AAB hashes, assinatura pública e equivalência de source trees.
CI é acompanhado nas PRs encadeadas e na consolidação. Este pacote não faz merge.
