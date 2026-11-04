# ORION-TEST-001 — Morte controlada do processo no harness Android

## OBJECTIVE
Tornar explícita a injeção de morte de processo no harness host. Na execução API35,
`am kill` não encerrou o processo, mesmo após Home com launcher em foco, Activity
STOPPED e estado de processo 15. O gate não pode declarar recriação sem morte real.
Base: AGENTS.md §§7.0/31/34, ADR-0001 e testes de process death do Plano Mestre.

## FILES ALLOWED
`scripts/android/verify-foundation-runtime.py`, teste host correspondente, `.gitignore`
para cache Python, `.github/workflows/ci.yml`, README e
este documento. Não altera APK, contrato de recovery, processo ou startup do produto.

## CONTRACTS INVOLVED
Harness HOST + ADB, pacote debug explícito e evidência ANDROID_EMULATOR/PHYSICAL_DEVICE.

## INVARIANTS INVOLVED
INV-014/023/029/032/033/036 preservados. Teste de Force Stop continua separado;
process death não significa package-stopped ou resolução de side effect.

## TESTS REQUIRED
PID único positivo, rejeição de PID zero/grupo/negativo/múltiplo ou shell injection;
comando limitado ao UID debug do próprio pacote; negação não possui fallback root
ou Force Stop. Execução real deve observar desaparecimento do PID, PID diferente
após relançamento e navegação restaurada. Executar o harness Android completo.

## ACCEPTANCE CRITERIA
`run-as network.orion kill -9 <PID>` usa o UID do app e falha quando não permitido.
PID revalidado antes de sinalizar, sem alteração de permissões do sistema. Evidência
registra RUN_AS_SIGKILL e PIDs reais. Não aceita apenas sucesso do comando como morte.

## OUT OF SCOPE
APK release/non-debuggable, package externo, root, boot/reboot, executor de ações,
recuperação de efeitos externos ou qualificação física indisponível.

## Ambiente
O código é tooling HOST para ADB. Os três testes de argumento/comando usam ADB fake
no host, sem dispositivo; a comprovação Android é registrada separadamente no
relatório da rodada. Não atribuir a validação fake a comportamento de runtime.
O CI executa esses testes host antes do build Docker, sem acesso a ADB/dispositivo;
o nome do check obrigatório e o build Android canônico permanecem os mesmos.

## Evidência final

`HOST`: `python3 scripts/android/test_foundation_runtime_harness.py`, três testes
aprovados, sem ADB real nesse teste. `ANDROID_EMULATOR` API35: harness completo
aprovado; PID 2942 desapareceu e o relançamento criou PID 3036 com Diagnóstico
restaurado. Quatro testes runtime passaram depois da recriação, e quatro após
Force Stop seguido de lançamento permitido. A ausência do PID foi observada antes
de relançar, e RUN_AS_SIGKILL foi registrado. Não há qualificação física.
