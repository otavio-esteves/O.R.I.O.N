# F6 — Entrada confiável e avaliação funcional em português

Contrato de trabalho: [ORION-F6-CHAT-001](../issues/ORION-F6-CHAT-001.md).
Base: `79ae400`; branch local `fix/f6-chat-reliability`.
Escopo: confiabilidade de software da F6, sem autorização de F7 ou inferência de
produção. Data do registro em UTC: 2026-11-20; execução local iniciada em 2026-11-19
(America/Sao_Paulo).

## Entrada e admissão

`OrionChatController.send` devolve `Accepted`, `Rejected(reason)`, `Busy` ou
`Closed` sincronamente. `Accepted` significa que a mensagem entrou no fluxo local,
com uma única entrada de usuário; não confirma inferência, persistência ou execução.
A UI limpa o editor e recolhe o teclado somente após esse resultado. Dois envios
iguais deliberados continuam sendo dois pedidos; não há deduplicação por conteúdo.

Rejeição devolve motivo técnico tipado e mensagem estática em português. Não altera
histórico, operação, aviso anterior ou retry de inferência, nem consulta gateways,
Cortex ou armazenamento. Busy/Closed também não alteram o estado do controlador.
O feedback do editor pertence à UI. Editar uma entrada rejeitada remove o erro
anterior quando o novo texto é válido. Falhas posteriores à admissão continuam no
fluxo existente; uma admissão mutável incerta nunca restaura/reenvia o rascunho nem
vira retry de inferência.

`OrionChatInputPolicy` é a mesma validação pura usada pelo editor e pelo roteador:

- entrada **bruta**, antes de trim, case folding, NFC ou reconhecimento;
- máximo **4096 bytes UTF-8**, sem cortar bytes ou pares surrogate;
- Unicode bem formado; sem substituição silenciosa de surrogates;
- sem C0/C1, LF, CR, tabulação, NUL, U+0085, U+2028 ou U+2029;
- entrada vazia/em branco pede mensagem; título ausente pede título;
- o título mantém seu limite próprio de **1024 bytes**, validado pelo preparador
  de domínio, inclusive antes de trim/NFC.

O limite de comprimento UTF-16 é somente uma guarda inicial de trabalho/memória;
a decisão para texto válido conta bytes UTF-8 por escalares. Colagem/edição acima
do limite é recusada com explicação e mantém o rascunho anterior. Texto limitado
inválido já no editor permanece exato e editável, com erro, e é recusado no envio.
Apresentação de uma linha não concede validade a quebras coladas. Nenhum rascunho,
mensagem ou argumento novo é gravado em disco, SavedState ou logs.

## Gramática de consulta

Depois da validação bruta: trim nas bordas conforme Kotlin, lowercase com
`Locale.ROOT`, redução de sequências de **espaços ASCII U+0020** entre palavras e
remoção de **no máximo um** `?` ou `.` terminal, com espaços ASCII opcionais antes
dele. A expressão inteira deve coincidir com um alias:

| Consulta existente | Aliases fechados |
| --- | --- |
| Bateria | `bateria`, `consultar bateria`, `como está a bateria` |
| Listagem sem filtro | `listar tarefas`, `ver tarefas`, `minhas tarefas`, `quais são minhas tarefas` |

Não se apagam palavras, negações, aspas, filtros ou intenções adicionais.
`bateria!`, `bateria??`, `"bateria"`, `tarefas pendentes`, pedidos compostos e
erros de digitação ficam no fallback cognitivo existente. Isso é interpretação,
sem autoridade. A gramática de escrita não muda: tudo após `criar tarefa:` é título
literal, submetido apenas à normalização do domínio. `Comprar pão?` mantém o `?`.
Origem externa e entrada parcial continuam bloqueadas antes do reconhecimento.

## Corpus e limites da avaliação

O arquivo [portuguese-chat-v1.tsv](../../core/orchestrator/src/test/resources/portuguese-chat-v1.tsv)
contém **69 entradas sintéticas** com ID, categoria, entrada, rota, título,
esclarecimento/rejeição, operações proibidas, confirmação e dependência de modelo.
O codec de fixture preserva controles/surrogates por escapes explícitos. Categorias
cobrem consultas, criação, título ausente, negação, citação, ambiguidade, composição,
erros de digitação, autorização textual, correções não suportadas e entrada inválida.
Na coluna de esclarecimento/rejeição, `-` significa que o roteador não produz um
motivo imediato. Para `COGNITIVE`, não declara que a frase é semanticamente clara:
essa decisão de linguagem não foi avaliada. `if_proposed` mantém confirmação
obrigatória se houver proposta cognitiva; não prevê nem autoriza sua geração.

Há três avaliações distintas:

1. `OrionPortugueseCorpusTest`: 69 casos de rota/argumentos e 69 de fronteiras de
   chamadas do controlador com modelo indisponível; **não mede compreensão de LLM**.
2. `OrionPortugueseResponseContractTest`: dez saídas **simuladas** validam o parser
   fechado e propostas sem autoridade; não medem qualidade semântica de modelo.
3. `OrionChatRealModelTest`: permanece opt-in, separado e dependente do GGUF fixado
   e da configuração explícita no [procedimento anterior](F6-HybridSoftware-20261118.md).
   Não há download de modelo nem resultado semântico fabricado no corpus obrigatório.

Execução focada, sem modelo:

```sh
docker compose run --rm android-build :core:orchestrator:testDebugUnitTest --max-workers=2
docker compose run --rm host-harness-check
```

## CI e execução Android

O job canônico de build `android-build-and-check` também compila/verifica explicitamente:

```sh
docker compose run --rm android-build \
  -PorionQualificationRelease=true -PorionHybridChatQualification=true \
  :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest \
  --max-workers=2
```

Essa é uma verificação de **build**, com identidade `network.orion.qualification`,
R8 e regras existentes restritas à variante. Os modos continuam exclusivos;
Debug/Release e perfis de produção não mudam. Nenhuma regra keep/dontwarn foi adicionada.

O novo job `chat-ui-no-model` recebe os APKs Debug produzidos no Docker, acompanhados
por SHA-256, e executa Android/ADB no HOST. Usa API35/x86_64, Emulator 36.5.11.0
(build 15261927), AVD de nome exclusivo, boot limitado a 300s e job de 20 minutos.
Apenas `OrionChatScreenTest` (2) e `OrionChatInputScreenTest` (7) são selecionadas.
`OrionChatRealModelTest` fica excluída da seleção obrigatória.

O check final mantém o nome já exigido `docker-build-and-check`, mas agora depende
do build e da UI, e reprova qualquer resultado diferente de `success`, inclusive
skip/cancelamento. Nenhuma proteção ou configuração remota de branch foi alterada.

O [runner HOST](../../scripts/android/verify-chat-ui.py) exige AVD/emulador explícito,
confere hashes antes e depois da instalação e valida os nove pares START/terminal,
identidades exatas, quantidade declarada, resumo e término da instrumentação.
Zero testes, skips/assumptions, repetição, classes inesperadas, falha de ADB, crash,
timeout ou falha de limpeza tornam o job falho. Código de saída zero do ADB não basta.
Timeout da instrumentação: 300s; instalação: 90s; demais comandos: 30s.

Para repetir localmente, preparar uma pasta com `app-debug.apk`,
`app-debug-androidTest.apk` e `SHA256SUMS` (SHA-256 seguido de dois espaços e nome),
usando os APKs de `app/build/outputs/apk/{debug,androidTest/debug}`. Em um AVD
**exclusivo da tarefa**, já iniciado:

```sh
python3 scripts/android/verify-chat-ui.py \
  --adb /caminho/do/sdk/platform-tools/adb --serial emulator-5554 \
  --avd-name OrionChatUiCI --apk-dir /caminho/dos/apks \
  --report-dir /caminho/do/relatorio
```

O runner remove somente os pacotes instalados nessa sessão. A ação de emulador
encerra sua própria instância inclusive na falha; o passo final remove apenas o AVD
nomeado pelo job. O CI publica somente JSON/JUnit com contagens, nomes de testes
permitidos, códigos técnicos e metadados/hashes. Stacks, stream bruto e logcat não
são exportados: podem conter o texto do editor. As ações novas são fixadas por SHA.
Referências primárias: [Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner),
[upload-artifact](https://github.com/actions/upload-artifact),
[download-artifact](https://github.com/actions/download-artifact).

## Reprodução e correções dos testes

As falhas anteriores não foram contadas como sucesso:

- Antes da correção, a suíte de admissão teve **3 testes, 3 falhas**: o método ainda
  devolvia `Unit`. O teste de aliases teve **1 teste, 1 falha** no roteador anterior.
- O teste Compose com o APK anterior reproduziu rascunho vazio após rejeição:
  **1 teste, 1 falha**, usando a asserção corrigida de `EditableText`. O APK alvo
  era o anterior; somente o APK de teste tinha sido atualizado nessa reprodução.
- A preparação do AVD inicialmente falhou por espaço para userdata. A primeira
  instrumentação não iniciou testes (crash da plataforma); a seguinte iniciou um
  teste e falhou esperando foco, com diálogo de ANR do SystemUI visível. Essas são
  falhas de infraestrutura, não evidência do comportamento do editor.
- Duas execuções completas intermediárias tiveram respectivamente **3/9** e
  **6/9** testes aprovados, zero skips. Corrigiram-se buscas de supporting text
  na árvore semântica não mesclada, comparação da string exata sem comparar
  anotações de estilo e espera pelo término do refresh inicial antes da edição.
  Os limites de tempo existentes não foram aumentados. A execução final abaixo
  usa comparação exata do texto e testes reais de rejeição, sem enfraquecer o contrato.

## Resultados e revisão final

O build/check canônico concluiu em **15m58s**, com 4571 tasks (205 executadas,
7 de cache e 4359 atualizadas). Incluiu lint, fronteiras de módulos (36 casos),
validação dos perfis, Debug, Release, AAB, quatro APKs AndroidTest e checks estáticos
de ABI/ELF/empacotamento de 16 KiB. O gate de compatibilidade continua `PENDING`
quanto à qualificação; passar no validador não altera esse estado.

O inventário JVM dos módulos ativos soma **1322 testes, 145 suítes, zero falhas,
erros ou skips**, em 32 módulos com testes, dos 53 ativos. No check integrado,
somente `:app:testDebugUnitTest` foi reexecutado (**319 testes**); 31 tasks de
teste estavam atualizadas e 21 não tinham fontes. Os **225 testes do orchestrator**
foram executados no build focado final (2m27s), incluindo 138 do corpus e dez do
parser simulado. Não se afirma que os 1322 foram todos reexecutados do zero.

O harness HOST em Docker executou **650 testes, zero falhas/skips**, em 46,571s,
incluindo 21 regressões novas do runner/protocolo/seleção/CI. `actionlint` passou
em Docker (imagem `b1934ee5f1c5…`), sem diagnóstico. O build específico minificado
da qualificação híbrida passou em **19m22s**, com 1808 tasks (28 executadas, três de
cache e 1777 atualizadas), incluindo R8 do alvo/testes, verificação nativa e APKs
de qualificação. O lint também passou; não houve alteração de regras ou limites
durante a execução demorada de minificação.

Imagem Android usada: `4e1b776c374c…`; Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.10 e
NDK 29.0.14206865, sem atualização da stack. A compilação informa depreciação de
`createAndroidComposeRule`, API já usada pela suíte de chat. Não houve supressão
do warning ou migração de API/dispatcher neste trabalho.

Comandos de validação efetivamente executados no Docker:

```sh
docker compose run --rm android-build \
  :core:orchestrator:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  --max-workers=2

docker compose run --rm android-build check assembleDebug assembleRelease \
  :app:assembleDebugAndroidTest :core:security:assembleDebugAndroidTest \
  :ai:llama:assembleDebugAndroidTest :ai:service:assembleDebugAndroidTest \
  --max-workers=2

docker compose run --rm android-build \
  -PorionQualificationRelease=true -PorionHybridChatQualification=true \
  :app:verifyQualificationNativePackage :app:assembleQualificationReleaseAndroidTest \
  --max-workers=2

docker compose run --rm host-harness-check
docker run --rm -v "$PWD:/repo:ro" -w /repo rhysd/actionlint:latest \
  -color .github/workflows/ci.yml
```

A execução final da UI no HOST concluiu **9 iniciados, 9 aprovados, 0 falhos,
0 ignorados**, em AVD exclusivo
API35/x86_64, páginas de 4096 bytes, Emulator 36.5.11.0. Os dois APKs instalados
tiveram SHA-256 verificado pelo runner. Isso é execução Android em **emulador**,
não qualificação física nem execução da variante minificada.

Comando HOST executado (APKs e relatórios em diretório temporário da tarefa):

```sh
python3 scripts/android/verify-chat-ui.py \
  --adb /home/otavio/Android/Sdk/platform-tools/adb --serial emulator-5590 \
  --avd-name OrionF6ChatReliability \
  --apk-dir /tmp/orion-f6-chat-reliability/apks \
  --report-dir /tmp/orion-f6-chat-reliability/ui-green-3
```

O corpus obrigatório aprovou **69/69 rotas**, **7/7 títulos esperados**, **5/5
esclarecimentos** e **8/8 rejeições**. As 16 consultas não chamaram configuração
nem geração; as sete criações somente admitiram o pedido, sem confirmação ou
execução automática. Nas 36 entradas fora do fallback houve **0/36 acionamentos
desnecessários do Cortex**. As 33 entradas cognitivas alcançaram o fallback com
configuração indisponível, sem geração ou escrita. Nenhuma foi contada como
compreensão correta de um modelo. As dez saídas simuladas passaram no parser:
quatro aceitas e seis rejeitadas; não há denominador de qualidade semântica real.

O runner desinstalou seus dois pacotes. O AVD `OrionF6ChatReliability`, serial
`emulator-5590`, foi encerrado após conferir seu nome, e somente seus diretórios
temporários foram removidos. O emulador preexistente `emulator-5554` permaneceu
conectado; não houve alteração de dispositivo físico, reset do ADB ou USB em Docker.

Revisão de impacto: nenhuma mudança de processo, ownership, IPC, política,
confirmação, lease, recuperação, schema Room (continua 6), migração ou perfil
de inferência. Não há novo mecanismo de retry durável; falha após admissão mutável
continua incerta e não produz reenvio. A validação rejeita antes de gateways/Cortex;
diagnósticos usam motivos estáticos, e relatórios de UI excluem mensagens e stacks.
Debug/Release normais e isolamento/R8 da qualificação permanecem cobertos por build.

Pendências: observar o novo workflow no GitHub quando houver publicação autorizada;
esta execução não fez push, merge, issue/PR remoto ou alteração de proteção. Não
foram executados modelo real, nova campanha de morte de processo/upgrade, matriz
de APIs nem dispositivo físico. A execução Android foi limitada às duas classes
de chat selecionadas; as demais suítes AndroidTest foram apenas compiladas. O
build minificado também não equivale a execução de instrumentação minificada.
As outras configurações de qualificação do workflow não foram reexecutadas localmente;
o build de qualificação desta tarefa selecionou explicitamente o chat híbrido.
Modelo real permanece opt-in e não houve download de GGUF nesta tarefa; o ambiente
Android usado foi o emulador API35, sem evidência física. Esses trabalhos não são pré-requisito artificial
do corpus sem modelo e continuam sujeitos aos gates existentes. F7 permanece
planejada, sem implementação ou alteração de autorização. Não se declara
estabilidade, qualificação física, inferência de produção ou prontidão de produção.

Registro verificável: [índice JSON](f6-chat-reliability-evidence-20261120.json) e
[arquivo de evidências](artifacts/f6-chat-reliability-evidence-20261120.tar.gz).
O arquivo contém logs de build/harness, resultados JVM, JSON/JUnit sanitizados
das três execuções completas de UI, contagens das falhas anteriores e snapshots
dos 18 arquivos do escopo nos commits de código. Manifestos SHA-256 verificam os
membros e as fontes; o índice externo verifica o arquivo. Não contém APK, modelo,
banco, chave privada, captura bruta de instrumentação ou logcat. São 45 membros,
89319 bytes; SHA-256 do arquivo:
`883a6039c8623cda7cca4fc52d1548cb227ac0df15bedbb256cedc66b9b06f62`.
Commit de código validado: `2f4b16a`; o commit posterior contém somente documentação
e evidência. Os hashes do par Debug após o build integrado coincidem com os APKs
instalados e executados na aprovação de 9/9 testes de UI.
