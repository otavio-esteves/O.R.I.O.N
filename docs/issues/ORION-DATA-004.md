# ORION-DATA-004 — Adapter criptografado de preparação de ações

## OBJECTIVE
Integrar os contratos de confirmação e criptografia ao store Room de ações, sem
habilitar executor ou ingress mutável. Persistir argumentos e target sempre
criptografados, inclusive para classificações menos sensíveis. Recompor/revalidar
o binding ao ler e confirmar uma ação. Base: Arquitetura §§13, 50–57, 70 e Plano
Mestre §§8.4–8.7. Pacote depende de DATA-001B/002/003.

## FILES ALLOWED
`data/actions/**`, testes Android específicos em `app`, builds correspondentes e
este documento. Nenhuma alteração no bootstrap ou ActionEngine.

## CONTRACTS INVOLVED
NormalizedActionRequest, ActionConfirmationBinding, OrionFieldEncryption,
FieldEnvelopeCodec, IdempotencyKeyPolicy e OrionActionDao.

## INVARIANTS INVOLVED
INV-004/006/011/018/019/021/022/023/035. Adapter não é Policy Engine, comando de
confirmação do usuário ou executor. Construção de um receipt não prova gesto humano.

## TESTS REQUIRED
Registro/read/reopen com payload SECRET sem plaintext no banco; replay e conflito;
confirmação íntegra/alterada/expirada/policy alterada; falta/invalidação de chave;
envelope alterado e troca de campos/AAD; corrupção não recebe fallback; conflito
de versão; confirmação destrutiva não dispensável; nenhum caminho RUNNING.

## ACCEPTANCE CRITERIA
Payload/target cifrados com AAD actionId/field/schema; leitura recomputa fingerprint
e hash; falha bloqueia mutação; idempotência preserva primeiro registro e mínimos de
retenção; confirmação aplica compare-and-set e audit no mesmo commit. Nenhum log
ou componente exportado, nenhum produtor de chave efêmera em produção.

## OUT OF SCOPE
Android Keystore wrapping e seu lifecycle persistido, chamadas de Skills, engine,
integração de UI/ingress, recovery operacional e pruning. Esses gates permanecem
necessários antes de habilitar operações no aplicativo.

## Fronteiras e falhas
O chamador fornece request já normalizado, decisão de confirmação e versão atual
da Policy Engine; esses valores não podem vir como autoridade de IPC/LLM. O adapter
nunca comprova gesto humano. Receipt não é MAC nem credencial. Campos cifrados usam
AAD com `action/<UUID>`, nome do campo e schema, e envelope binário versionado.

Readback usa UTF-8 estrito e recomputa hash/fingerprint. Chave ausente/inválida,
falha de autenticação, envelope/tipo/schema inválido ou enum Room desconhecido
bloqueiam com resultado tipado, sem propagar o valor bruto em mensagem. Falhas SQL
operacionais não recebem captura genérica nem retry oculto. Cancelamento local
pré-execução pode ocorrer sem decifrar; não resolve UNKNOWN nem cancela efeito em voo.

Confirmação revalida a ação persistida e policy, controla expiração e retrocesso
civil inclusive entre validação e persistência e usa CAS+audit. Lifetime sub-ms que
se tornaria zero no banco é recusado. Material do request não muda após registro;
mudança relevante exige outro actionId e confirmação quando aplicável.

Chaves usadas nos testes são sintéticas e existem apenas no APK de instrumentação.
Não foi introduzido um fornecedor efêmero de chaves no aplicativo. Sem efeito
externo, schema adicional ou mudança de disponibilidade de health neste pacote.

## Evidência final

`CONTAINER`: build debug/release, lint e comando canônico aprovados. Lint do adapter
sem ocorrências; fronteiras sem dependência proibida. `ANDROID_EMULATOR` API35:
12 testes EncryptedActionStoreTest aprovados, incluindo readback após reabertura,
ausência dos marcadores SECRET nos arquivos SQLite/WAL, replay/conflito, confirmação
alterada/expirada/policy diferente, rollback civil entre validação e persistência,
lifetime sub-ms, chave indisponível/invalidada, troca de campos, hash adulterado,
schema inválido e enum Room desconhecido sem eco do valor bruto. Casos de
indisponibilidade não apagam ou substituem a ação. A suíte Android integrada passou
com 29 testes iniciais + 4 após morte de processo + 4 após retorno permitido de
Force Stop. Evidência NON_DEFINITIVE; sem teste físico ou provider Keystore real.

Revisões independentes de confirmação, AAD/codec e persistência não deixaram achados
bloqueadores. Relatório da rodada: `docs/qualification/F2-ActionPreparation.md`.
