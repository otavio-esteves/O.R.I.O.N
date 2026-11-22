# Auditoria após a F12

A revisão partiu da F11 integrada em `94c4a9f` e da implementação
adaptativa `0ced1c3`. A inspeção cobriu os manifests e o grafo de processos,
o acesso a Room por MAIN, a admissão antes de efeitos, a política de
confirmação e recuperação, a seleção de memórias, os limites de recursos
da proatividade e a exclusão de KWS do aplicativo. O PR inclui as regressões
no gate obrigatório. [Código e checks do PR #198](https://github.com/otavio-esteves/O.R.I.O.N/pull/198/checks)

## Achados corrigidos

| Achado | Correção e regressão |
| --- | --- |
| A seleção de memória confiava no índice para comprovar a autorização de busca. Uma entrada obsoleta poderia revelar conteúdo em resultados ou no contexto do chat. | Busca e revalidação para chat verificam o opt-in no envelope original. A regressão injeta uma entrada obsoleta e verifica os dois caminhos. |
| A manutenção de expiração do Sentinel podia cancelar um rascunho durante a recuperação. | A escrita de cancelamento verifica a Startup Barrier sob o lock de admissão e preserva o CAS da ação. A regressão verifica que a recuperação preserva o rascunho e que o cancelamento ocorre após a liberação. |
| O ranking inicial da F12 pouco alterava o caso comum de memórias com a mesma confiança e sem importância definida. | Na ordenação adaptativa, a cobertura dos termos precede o desempate. Entre resultados comparáveis, a recência pode reordenar registros com a confiança padrão. Testes cobrem mudança de ordem, cobertura, paginação e retorno à ordenação original ao revogar. |
| Em API 37, o teste do papel de assistente falhou com `SQLITE_BUSY` ao abrir um segundo Room para contar registros. | A prova lê os dois contadores em uma consulta SQLite somente leitura. O teste preserva as comparações de estado durável, captura de áudio e revogação. |

## Evidência

- **Docker.** O check completo passou com 1.365 testes JVM ativos e os
  runners passaram com 660 testes. O benchmark F11 passou com 7 testes. A suíte focada da F12
  cobre ranking e hábitos. O gate do projeto cobre validação das
  dependências, lint, testes JVM, APKs, AAB e alinhamento nativo.
- **Emulador API 35.** Os dois testes Android da F11 passaram novamente com
  os APKs do CI integrado. O CI executa também F12, a atualização instalada
  de Room 12 para 13, chat sem modelo, persistência, voz e mortes de processo
  selecionadas pelos runners.
- **Emulador API 37.** Chat, persistência, voz e F12 passaram nas seis
  suítes selecionadas, com 75 testes. Os primeiros ensaios de UI ficaram
  bloqueados por diálogos de ANR da plataforma. A repetição no API 37
  preservou as verificações e executou todos os casos.
- **Dispositivo físico.** ADB não encontrou um Galaxy S21 conectado durante
  a auditoria. Wake word segue `DISABLED`. Os soaks e as qualificações
  físicas de estabilidade, áudio e Cortex conservam seus gates.

[Plano Mestre §29](../../2-plano-mestre.md#29-security-gate) ·
[F12](F12-SoftwareCloseout.md) · [F11](F11-SoftwareCloseout.md) ·
[Gate F4](https://github.com/otavio-esteves/O.R.I.O.N/issues/64) ·
[Gate F5](https://github.com/otavio-esteves/O.R.I.O.N/issues/89)
