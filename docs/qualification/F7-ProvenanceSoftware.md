# F7 — Primeiro incremento de proveniência

O incremento `9d494dc` inicia a F7 sob o
[ADR-0005](../adr/ADR-0005-f7-software-entry.md), após o
[fechamento local de software da F6](F6-SoftwareCloseout.md).
O escopo é o primeiro incremento de [ORION-F7-001](../issues/ORION-F7-001.md).

## Contratos e recuperação

A projeção de leitura retorna origem, confiança, confirmação, sensibilidade,
evidência opcional e o indicador de inferência derivado da origem persistida.
Ela usa os filtros da consulta de memórias declaradas pelo usuário. A projeção
não autoriza ação ou confirmação. `originEventId` é um UUID técnico opcional
definido pelo fluxo de armazenamento e gravado junto da memória na transação
que publica seu evento. Registros legados conservam seu conteúdo, suas versões
e seus estados. A migração não atribui evidência ausente nem reativa registros
`DELETED`. O envelope e o contexto criptográfico de conteúdo continuam
compatíveis com as chaves existentes.

O pacote de [diagnóstico de ingresso](../issues/ORION-INGRESS-DIAGNOSTICS-001.md)
está separado em `c818303`. Ele usa a mesma fonte para o limite de admissão e
observa os totais enfileirados e armazenados em uma consulta. O diagnóstico
explica a recusa de novos pedidos ao atingir o teto mesmo com zero pendências.
A deduplicação e os períodos de retenção continuam fazendo parte do contrato.

## Validação

- Docker concluiu a compilação dos APKs Debug e AndroidTest e os checks de
  dependências, com 36 casos de fronteiras aprovados.
- Os quatro módulos selecionados têm 585 casos JVM sem falhas, erros ou
  skips nos relatórios. São 26 no modelo, 14 em consultas, 226 no orquestrador e
  319 no app. O build final reaproveitou resultados válidos da execução
  inicial, que falhou ao compilar a fixture de capacidade. A fixture agora
  usa o tipo explícito dos parâmetros SQLite.
- Emulador API 35/x86_64 com páginas de 4 KiB. Na primeira execução, em
  `ec1d816`, a instrumentação durou 108,441 s e
  contou 69 iniciados e 69 aprovados, com zero falhas ou
  skips. A seleção inclui migrações a partir dos schemas 1–6, proveniência
  e envelopes em Room, confirmação e esquecimento, recuperação do Core,
  ações persistentes e os testes de saturação no banco e na interface.
- O teste de atualização instalou o APK com versionCode 6 e o substituiu
  pelo 7 após preparar os dados. A verificação no processo novo
  abriu o banco preservando a chave, os envelopes e as filas. As ações
  pendentes e incertas preservaram seus estados. Os SHA-256 do APK
  Debug `14ea64bbecfc384d32a771bd369be70929635bdd4b2db00043ae5120c2f2814a`
  e do APK AndroidTest
  `7d23565c5b35e5201439ac58d158c83c14caaada5ee8490f843936ac0b021461`
  coincidiram com os arquivos instalados.
- O lint inicial encontrou uma leitura de `StateFlow.value` durante a
  composição da fixture de diagnóstico. Após capturar seu snapshot fora da
  composição, `9d494dc` passou no lint, no build Release e nas verificações
  estáticas de ELF, ABI, APK/AAB e alinhamento de 16 KiB. O upgrade instalado
  6→7 voltou a passar. A repetição focada teve três testes aprovados, com zero
  falhas ou skips, cobrindo diagnóstico na UI, saturação em Room e migrações
  dos schemas 1–6.

Os critérios do [gate de saída da F7](../plans/F7-ImplementationPlan.md#4-matriz-do-gate-f7)
e os gates físicos, de estabilidade e de produção seguem abertos.
