# F12 — Personalização adaptativa local

Em **Ajustes → Personalização adaptativa**, o usuário pode alterar a ordem dos
resultados de memória e adaptar os horários das sugestões de tarefas.
O controle começa desativado em novas instalações e após a migração Room 12→13.
Diagnóstico mostra a preferência atual.

| Critério do Plano Mestre §18 | Implementação |
| --- | --- |
| Hábitos | MAIN deriva faixas de quatro horas no fuso atual. Cada faixa precisa de três amostras antes de afetar sugestões. A consulta lê até 64 resultados dos últimos 30 dias. |
| Peso do feedback | Cada sugestão exibida rende uma amostra. Conclusão soma 3, abertura soma 2, dispensa subtrai 3, adiamento e ausência de resposta subtraem 1. A conclusão prevalece sobre a dispensa, seguida por adiamento, abertura e ausência de resposta. |
| Ranking | A quantidade de termos encontrados define a primeira ordem. A recência e a importância pesam mais entre resultados com a mesma cobertura. A paginação continua com a mesma regra de ranking ou reinicia quando a preferência muda. |
| Personalização | O usuário controla a preferência de forma independente do consentimento para notificações. O desligamento restaura a busca e as sugestões padrão. Uma nova adesão inicia outra janela de aprendizado. |
| Proatividade adaptativa | Somente uma sugestão já elegível pode ser adiada. A média da faixa deve ser menor ou igual a menos um. Horário silencioso, orçamento, cooldown, capacidade, permissão e consentimento de notificação continuam aplicáveis. |

A migração adiciona um marco de consentimento nulo ao registro já existente de
MAIN. A gravação usa o ingresso durável de Ajustes e publica seu evento na
mesma transação. A revogação descarta o marco. O modelo de hábitos é calculado
sob demanda a partir do feedback já retido pelo Sentinel. A F12 não grava
consultas, texto, conteúdo de notificação nem um perfil de hábitos separado.
O ranking roda depois da seleção de memórias elegíveis pelo índice.

A revogação reserva uma vaga no ingresso e outra no outbox quando o limite
ordinário de 10.000 registros é atingido. O pedido ainda aguarda a Startup
Barrier e, ao ser aplicado, descarta opt-ins anteriores que ficaram na fila.
A atualização da preferência e o evento de revogação são atômicos. A mesma
revisão elimina o conteúdo de ingressos de voz ao atingir um estado terminal.
Após perda do cache, MAIN lê a ação já preparada para recuperar a indicação
de confirmação. A limpeza de registros antigos conserva seus comprovantes.
[Pacote de trabalho da auditoria](../issues/ORION-AUDIT-F12-002.md)

As decisões de adaptação seguem o [ADR-0010](../adr/ADR-0010-f12-software-entry.md).
O CI executa os testes do Sentinel, do ranking e da suíte emulador `f12`, além
da atualização instalada de Room 12→13 com o APK da F11. O [relatório de auditoria](Audit-F12-Closeout.md)
registra os resultados por ambiente e os gates físicos ainda abertos.
