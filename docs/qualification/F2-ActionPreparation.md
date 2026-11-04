# F2 — Incremento de preparação transacional de ações

Estado: incremento validado; F2 permanece em andamento. Não declara
TRANSACTION_CORE_READY, recovery operacional ou qualificação física.

## Entrega e commits

| Pacote | Commit | Responsabilidade |
| --- | --- | --- |
| DATA-001A | `09f1d0c` | Nove estados e validação do ciclo básico de ActionRequest |
| DATA-002 | `459c2ca` | Hash/fingerprint, confirmação imutável e validação temporal/policy |
| DATA-003 | `1afdce7` | AES-GCM, envelope/codec versionados e provider de chaves injetado |
| DATA-001B | `846c810` | Room v2, migração explícita, idempotência, CAS e auditoria atômica |
| DATA-004 | `4c01f2c` | Adapter de argumentos/target cifrados e confirmação ligada ao registro |
| TEST-001 | `4f61c95` | Injeção de morte do processo debug, testes de segurança do comando e CI |

Branch: `feat/orion-data-foundation`. Commits locais, sem publicação, merge ou
alegação de CI remoto aprovado. A consolidação acrescenta este relatório e evidências.

O banco preserva metadata formatVersion=1 e evolui schemaVersion=1→2. Ações começam
em PENDING e usam UNIQUE(scope, key). Replay preserva a primeira ação; divergência
gera conflito. Estado, versão e audit mínimo são commitados juntos. Material não
possui update público; confirmação e cancelamento não iniciam execução.

Argumentos e target são sempre cifrados pelo adapter, com AAD actionId/campo/schema.
Readback recompõe o binding e recusa corrupção, enum desconhecido, chave ausente ou
invalidada. Nenhum fallback plaintext, FTS, log de payload ou fornecedor efêmero de
chaves de produção foi adicionado. O runtime mantém ação/ingress mutável indisponíveis.

## Validação

`CONTAINER`: comando canônico aprovado:

```bash
docker compose run --rm android-build check assembleDebug assembleRelease :app:assembleDebugAndroidTest --max-workers=2
```

2625 tarefas: 2072 executadas e 553 FROM-CACHE. 160 testes JVM em 29 suites, zero
falhas/erros/skips; 36 casos de fronteiras aprovados. Bibliotecas debug/release,
APKs e AAB gerados. ELF/ABI/alinhamento/packaging e fixtures negativas aprovados.
Lint sem erros; módulos alterados sem ocorrências. App mantém dez avisos visíveis
de versões disponíveis, ChromeOS ABI e ícone. Nenhuma supressão foi acrescentada.

`HOST`: três testes do harness com ADB fake e validação do workflow por actionlint
em container aprovados. Os testes fake também integram o check obrigatório de CI.
O harness, instalações e controle do emulador foram executados pelo host.

`ANDROID_EMULATOR`: API35/x86_64/4096 bytes, instância read-only dedicada. Suíte
inicial com 29 testes: 12 de persistência/migração, 12 do adapter cifrado e cinco
da Foundation. Quatro testes adicionais após morte real do processo e quatro após
Force Stop com retorno permitido: 37 execuções aprovadas na rodada final.
Diagnóstico foi restaurado com PID diferente. Evidência Android NON_DEFINITIVE.

Migração preservou o marcador técnico e validou todas as tabelas. Testes de
concorrência tiveram exatamente um vencedor; falha de auditoria reverteu ação,
versão e confirmação. Fixtures RUNNING/UNKNOWN sobreviveram à reabertura e replay
não recriou a ação incerta. Isso não qualifica recovery de uma Skill externa.

## Problemas encontrados e resolvidos

- KSP debug/release disputavam o mesmo JSON novo: escritores ordenados sem criar
  dependência obrigatória entre variantes.
- Teste de migração usava `use` em RoomDatabase: fechamento explícito corrigido.
- Runtime debug de serialização 1.7.3 era imposto ao parser 1.8.1 de room-testing:
  alvo debug alinhado a 1.8.1. Release conserva a resolução anterior. Migração
  isolada e suíte completa passaram após o ajuste; fontes em DATA-001B.
- System UI do emulador apresentou diálogo de ANR cobrindo a Activity. Foi tratado
  pela opção do próprio sistema e a rodada repetida; não foi classificado como
  ANR do O.R.I.O.N.
- `am kill` não encerrou o processo parado no launcher: harness passou a injetar
  SIGKILL sob o UID debug, com validação de PID e sem root/Force Stop como fallback.
  O teste observa a morte e registra o método; Force Stop continua cenário separado.

## Evidências e limites

- [Build, hashes e snapshots de fonte](f2-action-preparation-build-summary.json).
- [Runtime final API35](f2-action-preparation-runtime-api35.json).

Os recorders preservam commit/dirty flag reais: o build e o runtime ocorreram na
árvore de implementação antes dos commits finais. A reconstrução do hash confirmou
que, depois do build, só mudaram arquivos host/CI explicitamente listados no resumo;
fontes/configuração dos APKs permaneceram idênticas. O harness novo foi validado
no host e no Android; hashes dos APKs coincidem com os instalados no teste final.
Alterações posteriores de consolidação são apenas documentação.

Permanecem necessários: provider Android Keystore com wrapping/lifecycle persistido,
Domain Event Outbox, demais schemas F2, fila de ingress, executor/recovery de ações,
retenção/pruning e upgrade com estado de negócio. Skill execution, UI operacional,
IA e voz não foram habilitados. Qualificação física S21, matriz de APIs e native
LOAD_TEST arm64/16 KiB continuam pendentes; gate nativo global permanece PENDING.
