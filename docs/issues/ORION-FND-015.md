# ORION-FND-015 — Assinatura debug estável entre containers

## OBJECTIVE
Permitir update/reinstalação consistente de APK debug produzido em execuções
Compose diferentes. A chave default não pode desaparecer ao remover o container.
Base: ADR-0001, Plano Mestre §7.8 app/process recreation e AGENTS §6/§10.

## FILES ALLOWED
`compose.yaml`, `README.md`, ToolchainProfile, metadata/erros do harness host e
esta documentação. Nenhuma alteração de chave release, Room ou código de negócio.

## CONTRACTS / INVARIANTS
Ambiente canônico Docker → host ADB; preservação de dados em upgrade; INV-019/032.
Não altera a estratégia de persistência do APK nem cria dependência runtime de Docker.

## TESTS REQUIRED
Validar geração/leitura da mesma chave em containers distintos; reinstalar APK com
`adb install -r`, executar harness de recriação e repetir instalação; compilar debug
em Docker e conferir ausência de chave/credencial no Git.

## ACCEPTANCE CRITERIA
ANDROID_USER_HOME de build fica em volume nomeado por projeto; compilações usam o
UID do host; chave debug default não é commitada nem usada para release. Erros de
instalação ficam explícitos e nunca causam uninstall automático ou exclusão de dados.

## OUT OF SCOPE
Signing release, distribuição, rotação de chave de produção, migração de dados,
restauração de assinatura perdida e manipulação de dados em aparelho pessoal.

## Diagnóstico e evolução
O Android recusou `install -r` com INSTALL_FAILED_UPDATE_INCOMPATIBLE porque o
keystore de /tmp/.android era efêmero. O volume contém apenas estado do ambiente
Android de build; a chave é a chave debug padrão do Android, não um segredo de
produção ou uma chave Android Keystore de dados do app.
APKs antigos assinados com chave efêmera perdida continuam incompatíveis por regra
do Android. O harness falha e preserva dados. Apenas o fixture criado nesta sessão
em emulador read-only é recriado explicitamente para a nova assinatura; isso não é
uma estratégia de recovery/migration do produto.

## Evidência
`CONTAINER`: validação de signing regenerada no volume; dois containers descartáveis
leram o mesmo fingerprint público; app e APK de testes usam esse certificado.
`HOST` + `ANDROID_EMULATOR`: fixture de O.R.I.O.N. recriado explicitamente uma vez;
harness final passou com 5 + 4 + 4 testes, guards de protocolo/payload, navegação,
Activity/process recreation e retorno permitido após Force Stop. Nova reinstalação
`install -r` de app/test manteve o inode do banco técnico e quatro testes de runtime
passaram após o update. Não houve uninstall automático no recorder.
Canônico no Docker: 2608 tarefas, 57 executadas, uma FROM-CACHE e 2550 UP-TO-DATE,
configuration cache reutilizado. APKs e certificado identificados por hashes nos
artefatos de evidência do gate F1. Nenhum keystore foi adicionado ao Git.
