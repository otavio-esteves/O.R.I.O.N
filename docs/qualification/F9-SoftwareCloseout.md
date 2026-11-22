# F9 — Voz push-to-talk

A tela **Ajustes → Voz** instala o modelo local `ggml-tiny-q5_1.bin`, variante
multilíngue do Whisper tiny. O modelo é validado por tamanho e SHA-256. O
candidato de software usa whisper.cpp v1.8.7, CPU e dois threads por sessão.

O usuário inicia a captura com **Falar** e pode usar **Concluir fala** ou
**Cancelar**. Cada captura está limitada a oito segundos. O pipeline usa
PCM mono de 16 kHz, VAD por energia, STT local e TTS do Android quando há
uma voz local em português. A resposta fica visível na tela. Desempilhar
a tela ou colocar o app em segundo plano encerra a sessão.

O Core roteia a consulta de bateria, a contagem de tarefas abertas e a proposta
de criação de tarefa. A revisão na tela Tarefas usa a confirmação exata existente.
O ingresso da transcrição final é criptografado, usa o ID estável do pedido
e só consome após a Startup Barrier. O protocolo privado usa sequências por
pedido e uma resposta terminal. As respostas em trânsito têm retenção efêmera.

## Gate e evidência

O Plano Mestre §15.7 exige pipeline de voz, isolamento dos processos, retomada
após morte do MAIN, fila durante recovery e ausência de execução a partir de
transcrições parciais. O Android emulado pode verificar transporte, comportamento
do software e morte de processo. A qualificação no Galaxy S21 deve verificar
microfone e TTS, cobertura do STT, RAM, latência, consumo e compatibilidade.
A aprovação definitiva de modelo e parâmetros depende dessas medições.

[Plano Mestre §15](../../2-plano-mestre.md#15-fase-9--voice-push-to-talk) ·
[ADR-0007](../adr/ADR-0007-f9-software-entry.md) ·
[Modelo publicado](https://huggingface.co/ggerganov/whisper.cpp/blob/main/ggml-tiny-q5_1.bin)
