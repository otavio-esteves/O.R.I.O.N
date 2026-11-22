# F11 — Qualificação de wake word

O aplicativo informa **Palavra de ativação desativada (`DISABLED`)** em
Diagnóstico. O push-to-talk e o assistente do Android continuam disponíveis
por ação do usuário. O detector experimental permanece fora do APK.

## Candidato offline

`:voice:wakeword` avalia trechos PCM mono a 16 kHz que já foram segmentados.
Ele transforma o áudio em coeficientes cepstrais e compara a sequência com até
oito referências de “Orion” usando DTW com janela limitada. O limiar inicial é
`0,24`. Cada trecho tem entre 200 ms e 1,6 s. O detector rejeita silêncio e
mantém somente as características das referências durante a execução.

Os testes de software medem acertos, recusas e aceitações indevidas por clipe
em conjuntos de referência e avaliação distintos. A latência registrada é a
da chamada de classificação na JVM. O CI retém o resultado agregado. A suíte
Android verifica o diagnóstico, a ausência do detector no APK, a declaração
de serviços e as restrições do assistente na tela bloqueada.

```sh
docker compose run --rm android-build :voice:wakeword:testDebugUnitTest
```

O teste aceita opcionalmente um corpus privado em `ORION_WAKE_WORD_PCM_CORPUS`.
Monte-o no Docker em modo somente leitura e use as pastas `enroll`, `positive`
e `negative`. Cada arquivo deve ser WAV mono PCM16 little-endian a 16 kHz.
Use de 1 a 8 referências e de 1 a 64 clipes em cada classe de avaliação. O
mesmo PCM fica restrito a uma amostra. Passe `--rerun` à tarefa quando alterar
o corpus. `ORION_WAKE_WORD_BENCHMARK_REPORT` permite gravar o JSON agregado
em um arquivo novo. Use áudio sintético ou material com consentimento e mantenha
o corpus fora dos artefatos compartilhados.

## Gate físico adiado

O ensaio no Galaxy S21 deve registrar a versão do Android e da One UI, o
build do O.R.I.O.N., o candidato e o corpus avaliados. Os limites de aceitação
devem ser definidos antes da coleta. As medições incluem falso positivo por
hora, falso negativo por tentativa, CPU, bateria e temperatura comparadas
ao baseline, horas de estabilidade, tela apagada, áudio externo, Doze e
restrições de segundo plano. A medição também deve verificar impacto em
STT e Cortex e o comportamento ao retirar a permissão ou o papel de assistente.

O benchmark de software mantém a qualificação de hardware como
`NOT_EVALUATED`. A política do APK permanece `DISABLED` até que um perfil físico
aprovado estabeleça o que o dispositivo suporta.

[Plano Mestre §17](../../2-plano-mestre.md#17-fase-11--wake-word-qualification) ·
[ADR-0009](../adr/ADR-0009-f11-software-entry.md)
