# ADR-0010 — Entrada de software da F12

**Status.** Aceito para o escopo de software da F12 solicitado em
“finaliza f11. inicia f12 e termina f12”, na sequência de trabalho com
qualificações físicas adiadas.

O [fechamento de software da F11](../qualification/F11-SoftwareCloseout.md)
fixa o resultado em `DISABLED`. Com a F11 integrada e seu CI aprovado,
a F12 pode implementar, validar e integrar recomendações adaptativas no
software enquanto os gates físicos e de estabilidade de longa duração
estão pendentes. A exceção se limita à espera por esses gates para o
sequenciamento do [Plano Mestre §§2 e 18](../../2-plano-mestre.md#18-fase-12--adaptive-intelligence).

Continuam exigidos os critérios de qualificação física e estabilidade dos
gates anteriores. F12 usa os mecanismos existentes de MAIN, ingressos,
consentimento, retenção e seleção de memória. O resultado da adaptação
altera a ordem de recomendações ou adia uma sugestão já elegível.
Policy Engine, confirmações, autorização e limites de segurança seguem o
Plano Mestre §18. A integração exige o CI obrigatório.
