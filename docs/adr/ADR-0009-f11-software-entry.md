# ADR-0009 — Entrada de software da F11

**Status.** Aceito para a F11 solicitada após a conclusão e auditoria do
software da F10, com as qualificações físicas prorrogadas pelo usuário.

## Decisão

Preparar o experimento offline de detecção de “Orion” sobre a versão da F10
validada nas APIs 35 e 37 e auditada. A integração da F11 segue o merge da F10
e o CI obrigatório. Esta decisão altera somente a espera pelo gate físico
para implementar e integrar o experimento de software do Plano Mestre §17.

## Critérios preservados

- A política efetiva continua `DISABLED` até haver qualificação física. O
  APK usa apenas o reconhecimento iniciado pelo usuário das fases F9 e F10.
- A avaliação do candidato usa PCM em um módulo de qualificação offline,
  fora do grafo de dependências do APK. Ela produz contagens agregadas e não
  publica autorização para captura, automação ou execução de ações.
- O gate físico considera precisão e consumo no dispositivo, estabilidade,
  tela apagada, Doze, políticas do Android e da One UI e impacto no STT/Cortex.
  O resultado define os modos permitidos com os limites da preferência do
  usuário e do ResourceGovernor previstos na arquitetura.

[Arquitetura V3.2 §§88–90](../../1-orion.md#88-wake-word-gate) ·
[Plano Mestre §17](../../2-plano-mestre.md#17-fase-11--wake-word-qualification) ·
[ADR-0008](ADR-0008-f10-software-entry.md)
