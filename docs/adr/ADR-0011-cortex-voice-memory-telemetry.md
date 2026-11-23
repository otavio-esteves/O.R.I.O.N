# ADR-0011 — Memória dos processos de voz na admissão do Cortex

**Status** · Proposto

O inventário do Cortex instalado aceita MAIN e `:ai`. O manifesto também
declara `:voice` e `:voice_session`. Enquanto o MAIN não puder contabilizar
a memória desses processos, a inclusão de um perfil do Qwen será insuficiente
para admitir inferência no app.

Na sonda de desenvolvimento no Galaxy S21, o Qwen 2.5 0.5B Q4_K_M gerou uma
resposta no formato do chat. A configuração usou CPU, dois threads e contexto
de 1.024 tokens. O maior PSS entre as amostras foi de 545.521 KiB. A medição
isolada caracteriza o candidato para novas medições no app.

## Proposta

Adicionar uma capacidade privada e versionada de telemetria de memória nos
processos de voz. Cada processo mede o próprio PSS, incluindo memória nativa,
e retorna somente a identidade do processo, PID, sessão de boot, instante
monotônico e bytes medidos. O MAIN valida o UID e o componente da conexão
Binder e rejeita amostras vencidas ou de uma instância anterior.

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
