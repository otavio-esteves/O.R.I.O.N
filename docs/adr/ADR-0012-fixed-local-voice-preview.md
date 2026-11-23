# ADR-0012 — Voz local de timbre fixo na prévia do Galaxy S21

**Status** · Prévia de desenvolvimento solicitada para teste no Galaxy S21.

As frases prontas usam a voz masculina `pm_alex` do Kokoro. A síntese pelo
Android pode escolher outro timbre em respostas livres. Para atender ao teste
solicitado, a prévia com os insumos explícitos de voz usa o mesmo locutor em
português brasileiro para respostas prontas e livres. Ela inclui o Kokoro v1.0
e o runtime Sherpa-ONNX 1.12.33. Isso estende a escolha de síntese da seção 84
da Arquitetura V3.2 somente para esse pacote de prévia.

A síntese roda sob demanda em `:voice_session` com a reserva de trabalho
nativo existente. O áudio é efêmero. Quando a síntese fica indisponível,
a interface apresenta a resposta textual. O pacote Android convencional
continua seguindo a seção 84. As autoridades do Core e os contratos IPC
existentes se aplicam a ambos os pacotes.

O pacote usa a exportação INT8 de `kokoro-multi-lang-v1_0`. A prévia valida
o arquivo do modelo e o AAR por SHA-256. A adaptação filtra o diagnóstico
nativo do Sherpa e redireciona o `stderr` durante a síntese, pois o runtime
pode incluir o texto de entrada em erros de fonetização.

A escolha de Whisper e dos parâmetros de decodificação continua regida pela
seção 83. A prévia valida os pesos por tamanho e SHA-256 e mede a transcrição
em português no Galaxy S21. O APK da rodada inclui Whisper Small multilíngue
Q5_1. Ele usa contexto de áudio proporcional à gravação. Reconheceu sete de
oito frases sintéticas após normalizar pontuação, acentos, números e a grafia
de “por que”. Tiny e Base reconheceram seis cada. No mesmo aparelho, o Small
usou de 6,8 a 8,4 segundos por trecho com o contexto reduzido e de 16,2 a 18,1
segundos com 768 posições. A evidência tem escopo de desenvolvimento.

O build da prévia recebe `orionVoiceModelFile` com `ggml-small-q5_1.bin`,
`orionNeuralVoiceModel` com `kokoro-int8-multi-lang-v1_0.tar.bz2` e
`orionNeuralVoiceAar` com `sherpa-onnx-static-link-onnxruntime-1.12.33.aar`.

[Arquitetura V3.2 §§83–85](../../1-orion.md#83-stt) ·
[ADR-0011](ADR-0011-cortex-voice-memory-telemetry.md) ·
[Sherpa-ONNX 1.12.33](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.33) ·
[Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)
