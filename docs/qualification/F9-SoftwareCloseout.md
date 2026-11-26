# F9 — Voz push-to-talk

A tela **Ajustes → Voz** instala o modelo local `ggml-tiny-q5_1.bin`, variante
multilíngue do Whisper tiny. O modelo é validado por tamanho e SHA-256. O
candidato de software usa whisper.cpp v1.8.7, CPU e dois threads por sessão.
A decodificação tem contexto e saída limitados. Se o código nativo ultrapassar
o limite externo, apenas o processo de sessão é encerrado.

Em um APK Debug de teste, a propriedade `orionVoiceModelFile` permite incluir
essa mesma versão do modelo. O Gradle verifica o arquivo e inclui sua licença
MIT. Na tela **Voz**, o botão **Instalar modelo local** copia o modelo para o
armazenamento privado e o verifica novamente. A opção de importar um arquivo
continua disponível.

```bash
docker compose run --rm -v "$ORION_MODELS_DIRECTORY:/orion-input:ro" android-build \
  :app:assembleDebug -PorionVoiceModelFile=/orion-input/ggml-tiny-q5_1.bin
```

O usuário inicia a captura com **Falar** e pode usar **Concluir fala** ou
**Cancelar**. Cada captura está limitada a oito segundos. O pipeline usa
PCM mono de 16 kHz, VAD por energia, STT local e TTS do Android quando há
uma voz local em português. A resposta fica visível na tela. Desempilhar
a tela ou colocar o app em segundo plano encerra a sessão.

O Core roteia a consulta de bateria, a contagem de tarefas abertas e a proposta
de criação de tarefa. A revisão na tela Tarefas usa a confirmação exata existente.
O ingresso de criação de tarefa é criptografado e usa o ID estável do pedido.
Consultas e propostas de lembrete ficam em uma fila limitada em memória. O Core
processa os dois caminhos após a Startup Barrier. O protocolo privado usa
sequências por pedido e uma resposta terminal. A assinatura tem prazo alinhado
ao limite do turno, incluindo a pausa de proteção do Cortex.

## Gate e evidência

O Plano Mestre §15.7 exige pipeline de voz, isolamento dos processos, retomada
após morte do MAIN, fila durante recovery e ausência de execução a partir de
transcrições parciais. O Android emulado pode verificar transporte, comportamento
do software e morte de processo. A qualificação no Galaxy S21 deve verificar
microfone e TTS, cobertura do STT, RAM, latência, consumo e compatibilidade.
A aprovação definitiva de modelo e parâmetros depende dessas medições.

| Verificação | Evidência de software |
| --- | --- |
| IPC e processos | A suíte Android `f9` cobre versão, topologia privada, callbacks, admissão durante recovery, preparação real de tarefa e conversa em primeiro plano. |
| Retomada do Core | `verify-voice-process-recovery.py` mata MAIN com `SIGKILL`, confirma que `:voice_session` continua vivo e verifica o bind e a conclusão de uma consulta após a recuperação. O script também mata cada processo de voz e verifica que os outros sobrevivem. |
| Core independente | A suíte de chat sem modelo verifica o uso por texto com os processos de voz desligados. |
| VAD e STT | O teste JVM cobre a seleção de fala no PCM. No emulador API 35 x86_64, o teste instrumentado reconheceu a palavra `bateria` numa frase sintética em 12,7 s de decodificação. |
| Pacote nativo | `readelf` confirmou alinhamento de 16 KiB nos segmentos `LOAD` da biblioteca do Whisper em `arm64-v8a` e `x86_64`. `zipalign -c -P 16 4` passou no APK debug. |

O teste nativo opcional `OrionWhisperInstrumentedTest` usa o modelo com hash
verificado e uma frase sintética em português. Instale o APK de teste do módulo
`:voice:service`, copie o modelo para `no_backup/orion_voice_tiny_q5_1.bin` e um
PCM mono de 16 kHz e 16 bits little-endian da frase “Como está a bateria?” para
`files/orion_voice_battery.pcm` no sandbox do APK. Passe `-e orion_voice_fixture
battery` para ativar o teste. O resultado verifica a palavra `bateria` e registra
o tempo de decodificação. Use áudio sintético para manter dados de voz pessoais
fora da evidência de teste.

[Plano Mestre §15](../../2-plano-mestre.md#15-fase-9--voice-push-to-talk) ·
[ADR-0007](../adr/ADR-0007-f9-software-entry.md) ·
[Modelo publicado](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-tiny-q5_1.bin)
