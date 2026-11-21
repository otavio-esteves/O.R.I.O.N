# F8 — Validação de software

O Sentinel avalia sugestões locais no heartbeat deferrable de 30 minutos.
Retentativas de manutenção seguem separadas de novas tentativas de entrega.
O usuário habilita as sugestões em Ajustes. O candidato inicial considera
tarefas abertas há 24 horas a 7 dias e usa um horário silencioso local fixo
das 22h às 8h. A notificação usa texto genérico. A tela de Diagnóstico mostra
o perfil de recursos e a saúde do Sentinel.

## Critérios do Plano Mestre §14.6

| Critério | Verificação |
| --- | --- |
| Heartbeat | `OrionSchedulerPlatformTest` verifica a periodicidade de 30 minutos e reaproveitamento da mesma instância após reconciliar. |
| Perfis de recursos | `OrionResourceProfilesTest` cobre os cinco perfis, sinais ausentes e restrição de segundo plano. `OrionMinimumResourceGovernorTest` cobre o veto de inferência com bateria crítica. |
| Contexto, cooldown, silêncio e orçamento | `OrionProactivityPolicyTest` verifica pontuação, DST, rollback de relógio, cooldown global e por tópico, orçamento e deduplicação. Somente observações minimizadas são coletadas. |
| Notificação proativa | `OrionSentinelRuntimeTest` cobre opt-in, barreira de heartbeat, perfil restritivo, seleção sem conteúdo sensível, entrega via Android e PendingIntents privados e imutáveis. |
| Feedback | A entrega confirmada e o evento `SHOWN` compartilham uma transação. Interações são admitidas via ingresso durável. A manutenção registra `IGNORED` e `COMPLETED` ao observar o estado da referência. |
| Sem loop | Uma reserva por tarefa e um cooldown por tópico limitam repetição. `OrionProactiveDeliveryTest` cobre feedback atômico, revogação de consentimento, UNKNOWN e ausência de retry. O cenário `sentinel_delivery` interrompe o processo depois do claim persistente. |

A retenção elimina feedback após 30 dias e conserva a referência de deduplicação
enquanto a tarefa estiver aberta, com teto de 512 reservas. Uma interrupção
após a reserva pode consumir a oportunidade daquela tarefa. A recuperação
descarta autorizações expiradas ainda sem claim e preserva efeitos incertos
para evitar reenvio.

## Upgrade e evidências

As execuções do candidato, incluindo a validação do APK instalado e as hashes
dos artefatos, são registradas no [PR #192](https://github.com/otavio-esteves/O.R.I.O.N/pull/192).
Os testes no emulador API 35 usam SQLite 3.44.3, x86_64 e páginas de 4 KiB.

Durante os primeiros ensaios, o SQLite do emulador reportou que a validação
FTS4 do `quick_check(1)` precisava escrever temporariamente quando executada em
uma conexão somente leitura. A mesma cópia sintética passou pela conexão
gravável. A verificação de estrutura mantém o PRAGMA, agora executado em uma
transação Room que fixa a conexão primária gravável. A regressão de concorrência
verifica que a inspeção aguarda a conexão com o índice populado.

O [ADR-0006](../adr/ADR-0006-f8-software-entry.md) mantém abertos os gates
`SENTINEL_READY`, `MEMORY_ENGINE_READY` e `CORTEX_RUNTIME_READY`.
A qualificação física e de estabilidade segue nas issues
[#64](https://github.com/otavio-esteves/O.R.I.O.N/issues/64) e
[#89](https://github.com/otavio-esteves/O.R.I.O.N/issues/89).
