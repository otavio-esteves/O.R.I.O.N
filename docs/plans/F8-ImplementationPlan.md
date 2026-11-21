# F8 — Plano de software do Sentinel e ResourceGovernor

O [ADR-0006](../adr/ADR-0006-f8-software-entry.md) autoriza a entrada de
software com a qualificação física adiada. O [Plano Mestre §14](../../2-plano-mestre.md#14-fase-8--sentinel--resource-governor-completo)
e a [Arquitetura V3.2](../../1-orion.md#14-sentinel) definem os critérios.

## Work packages

| Pacote | Entrega | Limite de módulos e contratos |
| --- | --- | --- |
| ResourceGovernor | Perfis de recursos com limites monotônicos de custo, sobre a admissão existente | `core/resources` e composição MAIN de recursos. INV-013, INV-018, INV-027, INV-028, INV-030. |
| Sentinel e contexto | Snapshot minimizado, pontuação determinística, janelas de silêncio, cooldown, orçamento e supressão por tópico | `core/sentinel`, `core/context` e consultas pertinentes. INV-001, INV-009, INV-011, INV-015, INV-023. |
| Persistência e feedback | Reserva versionada de entrega, deduplicação, estados de feedback e integração com eventos e recuperação | `data/database`, armazenamento de feedback e composição MAIN. INV-004–008, INV-014, INV-019, INV-023, INV-035, INV-036. |
| Integração | Heartbeat periódico deferrable, notificação via política/outbox, controles e diagnóstico | `app`, `scheduler`, contratos/Skills de notificação e telas pertinentes. INV-018, INV-023, INV-029–031. |

A integração usa WorkManager para avaliação periódica. O estado autoritativo
e a decisão de elegibilidade permanecem em MAIN. Voz e calibração de produção
seguem os respectivos gates.

## Matriz de evidência de software

| Critério do Plano Mestre §14.6 | Aceite de software |
| --- | --- |
| Heartbeat | WorkManager mantém uma única agenda periódica deferrable; reinício e duplicata exercitados. |
| Perfis de recursos | Uma política determinística aplica limites e degrada com baterias, térmica, pressão e sinais de saída; nenhum perfil experimental habilita inferência de produção. |
| Context snapshot | Observações atuais, ausências explícitas, relógio injetado e coleta minimizada; nenhum dado invasivo sem opt-in. |
| Notificação proativa | Candidato, autorização, claim, entrega Android e observação final exercitados pelo caminho de produto. |
| Cooldown, quiet hours e supressão | Janelas normais e noturnas, concorrência, duplicatas, orçamento e retomada verificados. |
| Feedback | Eventos de interação e passagem do tempo produzem estado persistido, penalidade e tempo até ação quando aplicável. |
| Sem loop de notificações | Repetição do heartbeat e falha entre claim e efeito preservam supressão e não repetem resultado incerto. |

Registrar execução com commit, ambiente, total de testes, falhas e skips.
Upgrade e process death têm evidência própria quando há persistência ou
efeito externo. A qualificação física e de estabilidade acompanha seus
registros e critérios em aberto.
