# ORION-F9-IPC-001 — Handshake e consultas do gateway de voz

**Objetivo**
Iniciar o IPC da F9 com DTOs limitados, negociação de versão, consulta de
readiness e bind explícito ao gateway privado de MAIN.

**Arquivos**
`core/ipc`, `voice/ipc`, gateway Android em `app/voice`, dependências,
manifesto e testes específicos da fase.

**Contratos**
Arquitetura §23.1 e Plano Mestre §§15.1–15.3. Os papéis VOICE_CONTROL e
VOICE_SESSION já existem no bootstrap. O contrato é compartilhado pelos dois
papéis e MAIN mantém a propriedade da prontidão.

**Invariantes**
INV-001, INV-012, INV-023 a INV-026, INV-033 e INV-034.

**Aceite deste pacote**
Versão maior incompatível degrada a conexão. O cliente consulta prontidão por
bind interno. O gateway só responde a chamadas da UID do app. DTOs rejeitam
texto inválido ou além do limite e a transcrição parcial não é elegível para
comando.

**Fora do pacote**
Captura de áudio, STT, VAD, TTS, ingresso mutável, callbacks de resposta e
ensaio de bind com MAIN morto. Esses itens entram nos próximos pacotes da F9.
