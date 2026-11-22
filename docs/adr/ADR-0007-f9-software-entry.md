# ADR-0007 — Entrada de software da F9

**Status.** Aceito para a entrada de software solicitada pelo usuário em
“faça correção de tudo. finaliza F8 e inicia F9.”

A entrada de software da F9 pode ser preparada enquanto a qualificação física
da F8 e dos gates anteriores continua pendente. Sua integração segue o
fechamento das correções de software da F8 pelo CI obrigatório. Esta decisão
altera apenas o sequenciamento do Plano Mestre §2 para desenvolvimento e
integração do software da F9.

Continuam exigidos os critérios dos gates do Galaxy S21, da matriz nativa/API,
do MemoryLimiter, dos perfis de produção e dos testes de 24 horas. Os critérios
da F9 e os limites de processos, autorização, recuperação e uso do microfone
continuam definidos pela Arquitetura V3.2 §23.1 e pelo Plano Mestre §15.
