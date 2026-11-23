# ADR-0011 — Memória dos processos de voz na admissão do Cortex

**Status** · Aceito pelo usuário para implementação e testes no S21

O inventário do Cortex instalado aceita MAIN e `:ai`. O manifesto também
declara `:voice` e `:voice_session`. Enquanto o MAIN não puder contabilizar
a memória desses processos, a inclusão de um perfil do Qwen será insuficiente
para admitir inferência no app.

Na sonda de desenvolvimento no Galaxy S21, o Qwen 2.5 0.5B Q4_K_M gerou uma
resposta no formato do chat. A configuração usou CPU, dois threads e contexto
de 1.024 tokens. O maior PSS entre as amostras foi de 545.521 KiB. A medição
isolada caracteriza o candidato para novas medições no app.

No APK de laboratório com a biblioteca otimizada, o MAIN e o `:ai`
somaram 792.570.880 bytes de PSS após o carregamento. Esse valor inclui a
sobrecarga do aplicativo e orienta o limite do diagnóstico com os dois
processos. A medição ainda precisa incluir a voz para um perfil de produção.

## Decisão

Adicionar uma capacidade privada e versionada de telemetria de memória nos
processos de voz. Cada processo mede o próprio PSS, incluindo memória nativa,
e retorna somente a identidade do processo, PID, sessão de boot, instante
monotônico e bytes medidos. O MAIN valida o UID, o PID e a identidade da
instância no retorno Binder e rejeita amostras vencidas.

O MAIN mantém a autoridade sobre o inventário e o `ResourceGovernor`. Antes
de autorizar o carregamento ou a geração, ele valida as amostras de todos os
processos ativos e a memória disponível do sistema. A ausência de um processo
precisa de uma fonte de ciclo de vida que possa atestá-la. Uma desconexão ou
uma resposta ausente deixa o inventário incompleto. A coleta preserva o
ciclo de vida sob demanda da voz.

A admissão precisa considerar a corrida em que um processo de voz começa após
a amostragem. O desenho da implementação deverá demonstrar uma reserva
conservadora para essa transição ou coordenar a admissão do trabalho pesado
nos dois subsistemas. O orçamento agregado e a decisão de inferência continuam no
MAIN. A capacidade de telemetria não concede autoridade para usar o microfone,
criar sessões, executar Skills ou alterar estado.

Os processos de voz mantêm um registro técnico com uma trava liberada pelo
kernel na morte do processo. O MAIN confere esse registro antes e depois das
amostras. A consulta usa um receiver registrado no processo ativo e o retorno
usa Binder com versão, desafio e identidade da instância.

O trabalhador que carrega o modelo mantém uma reserva exclusiva de trabalho
nativo até o descarregamento ou a morte do processo. O Whisper usa a mesma
reserva durante a captura e a transcrição. A prévia do S21 acrescenta uma margem
explícita para a inicialização leve de voz depois da amostragem. Essa margem
faz parte do orçamento agregado e reduz a memória livre considerada pelo
governador.

## Prévia local no S21

O build `s21CortexPreview` é habilitado por `-PorionS21CortexPreview=true` e
preserva o identificador do app para atualizar a instalação de desenvolvimento.
O perfil candidato só é usado no firmware, ABI e tamanho de página medidos.
O processo principal verifica o Qwen provisionado no diretório privado do app
pelo importador existente antes de disponibilizá-lo no Chat. A geração continua
sujeita às duas decisões do governador. O Whisper pode ser incluído no APK pela
propriedade existente `orionVoiceModelFile`.

O S21 reportou um encerramento por `LOW_MEMORY` de `:ai` em cache, com
amostra anterior de 60 MiB de RSS. A prévia contabiliza pressão de memória
e mantém o cooldown para esse caso. A combinação de processo em cache e
RSS observado de até 96 MiB identifica um encerramento de processo leve
para a seleção de tier. A falta dessa evidência e sinais de MemoryLimiter
mantêm o tratamento conservador da proteção contra reinicializações.

Quando um encerramento registrado abre uma pausa de proteção temporária, uma
pergunta explícita da prévia pode aguardar até uma janela de cooldown antes de
iniciar a inferência. As novas medições e decisões de recursos continuam
obrigatórias. Um bloqueio que persiste é mostrado na interface.

O Chat da prévia valida texto para exibição. Os comandos determinísticos
do Core continuam atendendo às operações do aplicativo e aplicando as
confirmações previstas.

Uma pergunta livre enviada pelo modo Voz na prévia também pode usar o Cortex.
O ingresso existente valida e consome a transcrição final antes da inferência.
A sessão em memória permite somente uma resposta textual, com cancelamento e
sem reinferência em replay ou recuperação. A transcrição libera a reserva
nativa antes de o Cortex pedir sua própria autorização de recursos.

## Evidência para aceitação

- Verificar processos ativos, inativos e reiniciados. Cobrir amostras ausentes,
  vencidas e de outro boot, além de inicialização concorrente de voz e Cortex.
- Medir PSS em MAIN, `:ai`, `:voice` e `:voice_session` no S21, com
  captura de voz e geração sob carga. Validar o orçamento por processo e o
  orçamento agregado.
- Revisar o perfil do modelo e os critérios físicos de F5 separadamente,
  incluindo as obrigações de estabilidade e compatibilidade aplicáveis.

Referências normativas nos §§ 9 e 22.2 do [AGENTS.md](../../AGENTS.md) e nos
§§ 32–33 da [Arquitetura V3.2](../../1-orion.md).
