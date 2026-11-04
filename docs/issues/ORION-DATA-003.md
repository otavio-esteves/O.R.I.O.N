# ORION-DATA-003 — Envelope criptográfico para campos

## OBJECTIVE
Implementar o mecanismo AES-GCM de campos com envelope versionado e contexto AAD
vinculado a entidade/campo/schema, antes de integrá-lo à persistência. Base:
Arquitetura V3.2 §§70–73; Plano Mestre V1.2 §8.7; AGENTS.md §25.

## FILES ALLOWED
`core/security/**` e este documento, sem dependências de produção novas.

## CONTRACTS INVOLVED
Field envelope, versioned data-key provider, AAD binding e DurablePayloadPolicy.

## INVARIANTS INVOLVED
INV-011/019/033/035. Criptografia não concede autorização nem minimiza payloads.

## TESTS REQUIRED
Round-trip sem plaintext no envelope; tamper de ciphertext/nonce/tag/metadados;
contexto AAD divergente; rotação mantendo leitura de versões anteriores;
chave indisponível/invalidada; envelope não suportado; diversidade de nonces;
cópias defensivas e strings redigidas. Testes e lint em Docker.
Fixtures fixas de wire V1 e AES-GCM/AAD geradas independentemente por
Node 22.23.3/OpenSSL 3.5.8 em container, com chave/nonces sintéticos, protegem a
compatibilidade do formato sem depender de round-trip pelo mesmo código.

## ACCEPTANCE CRITERIA
AES-GCM com nonce aleatório de 96 bits e tag de 128 bits; AAD codificado sem
ambiguidade; ciphertextVersion/keyVersion/algorithm/aadSchemaVersion no envelope;
falha tipada sem fallback plaintext; nenhuma chave ou conteúdo em logs/strings.
Codec binário estrito com versão própria, comprimentos delimitados, limite antes de
alocação e rejeição de bytes residuais; parsing nunca substitui autenticação GCM.

## OUT OF SCOPE
Persistência/provisionamento de data keys, wrapping Android Keystore, Android/OEM
qualification, rotação transacional em lote, Room/FTS, payload policy enforcement,
UI e runtime wiring. A implementação não é um provedor de chaves de produção:
essa integração continua exigindo wrapping/proteção por Android Keystore.

## Contrato
O provider injetado entrega a chave atual para cifrar e resolve versões históricas
para decifrar. Chaves indisponíveis/invalidas são resultados explícitos; nunca são
recriadas silenciosamente. Rotação troca apenas a versão atual, preservando versões
anteriores enquanto os campos correspondentes existirem.
Falhas esperadas do provider/Keystore devem virar Unavailable/Invalidated no
provider. Lookups ficam fora da captura de exceções do cipher; bugs inesperados
propagam, sem serem tratados como recuperação bem-sucedida.

O AAD V1 usa UTF-8 estrito e campos prefixados por comprimento em bytes, com domínio
fixo e metadados do envelope, entityId, fieldName e schemaVersion. Identidade de
entidade deve incluir o escopo lógico necessário (por exemplo, tipo de entidade e
ID); não se deve reutilizar a mesma combinação para domínios diferentes.

O limite inicial por campo é 1 MiB; isso não altera os limites próprios de IPC.
O envelope faz cópias defensivas de nonce/ciphertext, e o resultado plaintext
também devolve cópias. Nonces são produzidos pelo SecureRandom em cada operação,
sem usar relógio, contador de processo ou dados do usuário. A gestão de volume por
chave/rotação deve manter o risco de colisão criptograficamente desprezível.

## Impactos
Sem schema, componentes Android, permissões, logs ou inicializadores novos.
Morte de processo interrompe somente a operação local; o chamador futuro deve
persistir o envelope atomicamente e tratar indisponibilidade da chave sem apagar
ou substituir dados. Não há promessa de zerar memória gerenciada nem de recuperação
de chaves perdidas. O gate F2 de ciclo de vida Keystore continua pendente.

## Evidência de validação
`CONTAINER`: o teste inicial `:core:security:testDebugUnitTest` falhou na compilação
por ausência dos novos contratos antes da implementação. Os vetores sintéticos
foram gerados por implementação independente (Node/OpenSSL) em container; isso
valida formato/criptografia, sem alegação de comportamento Android/Keystore.

A validação centralizada posterior executou `:core:security:testDebugUnitTest
:core:security:assemble :core:security:lint` no mesmo Docker Compose canônico.
Essas tarefas concluíram com sucesso: 18 testes em três suites (12 de criptografia,
cinco de codec e um de payload policy), zero falhas/erros/skips; AARs debug/release
gerados; relatório XML de lint sem ocorrências. Os vetores fixos, a alteração de
versão de chave sem trocar a chave e as falhas de autenticação passaram nessa rodada.

`HOST`: revisão de integração de AAD/serialização no adapter e revisão independente
do mecanismo não encontraram bloqueadores. Whitespace dos arquivos novos aprovado.
Não foram executados testes em dispositivo, Keystore ou de rotação de dados
persistidos neste pacote.
