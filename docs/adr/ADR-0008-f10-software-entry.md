# ADR-0008 — Entrada de software da F10

**Status.** Aceito para a execução da F10 solicitada pelo usuário com a
prorrogação das qualificações físicas.

## Decisão

A F9 integrada permite implementar, validar e integrar o software de System
Assistant da F10. Este ADR altera somente o sequenciamento do Plano Mestre
§§2 e 16 quanto à espera pela qualificação física da F9. A matriz de software
continua a verificar os contratos do Android nas APIs que puder executar e
registra o ambiente de cada ensaio.

## Critérios preservados

- O usuário escolhe o assistente pelo Android. MAIN reavalia a capability
  quando o papel muda e o push-to-talk continua utilizável.
- O serviço de controle exige `BIND_VOICE_INTERACTION` e permanece em `:voice`.
  Captura, STT, TTS e UI da sessão pertencem a `:voice_session`. O gateway
  de MAIN continua privado e versionado.
- Assist data, extras e resultados externos não concedem autoridade para
  iniciar captura, confirmar ou executar uma ação.
- O CI obrigatório deve passar antes da integração. Os ensaios em emulador
  registram resultados de software. A aprovação física de áudio, compatibilidade
  Samsung/One UI e estabilidade continua pendente para a promoção de prontidão
  em dispositivo e eventual release.

[Arquitetura V3.2 §§86–90](../../1-orion.md#86-role_assistant) ·
[Plano Mestre §16](../../2-plano-mestre.md#16-fase-10--system-assistant) ·
[ADR-0007](ADR-0007-f9-software-entry.md)
