# ORION-TEST-002 — Morte real durante transação Room

## OBJECTIVE
Provar em Android que um commit técnico anterior persiste e uma escrita local
em transação aberta é revertida após SIGKILL do processo debug. Base: Plano Mestre
V1.2 §§8.10–8.11, AGENTS.md §6 e ADR-0001.

## FILES ALLOWED
`app/src/androidTest/**/TransactionProcessDeathTest.kt`,
`scripts/android/verify-transaction-process-death.py`,
`scripts/android/test_transaction_process_death_harness.py` e este documento.

## CONTRACTS INVOLVED
Transações Room/SQLite, fronteira HOST→ADB→Android, evidência de process death.

## INVARIANTS INVOLVED
INV-001/007/019/032. O fixture não executa Skills ou efeitos externos.

## TESTS REQUIRED
Invocação comum prova rollback; invocação especial prepara transação aberta,
checkpoint privado contendo PID positivo e aguarda no máximo 45 segundos; host
valida checkpoint contra pidof, injeta run-as SIGKILL e verifica PID encerrado;
nova instrumentação valida commit anterior presente e escrita não commitada
ausente, com PID diferente. Harness rejeita PIDs inválidos e argumentos inválidos
antes de contatar ADB, sem Force Stop/root como fallback.

## ACCEPTANCE CRITERIA
Banco dedicado `orion_transaction_death_<UUID>.db`, markers UUID sintéticos e
nenhum payload de usuário. Verificação reabre banco e remove fixture/checkpoint.
Recorder exige APKs pré-compilados, serial e ambiente explícitos; valida emulador
por ro.kernel.qemu; registra hashes de fontes/APKs, commit/dirty flag, Android/API,
ABI/page size, PIDs, método e falhas. Evidência de emulador é NON_DEFINITIVE.

## OUT OF SCOPE
Efeitos externos, duplicação de Skill, reboot, upgrade entre APKs e qualificação
física. O teste usa relógio fixo para marcador técnico; a espera do checkpoint é
apenas coordenação de teste, sem deadlines persistidos de produto.

## Validação
HOST: três testes do harness aprovados; parsing Python e whitespace aprovados.
CONTAINER/ANDROID: execução será registrada pelo relatório integrado, sem alegação
antecipada de aprovação. Invocar o recorder somente após build dos APKs atualizados
e sem outra instrumentação concorrente no mesmo serial.
