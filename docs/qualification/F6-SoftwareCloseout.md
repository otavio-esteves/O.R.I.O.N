# F6 — Fechamento de software

A revisão de software do gate cognitivo do Plano Mestre §12.7 está encerrada
localmente em `e6e69cc` para o pacote
[ORION-F6-CLOSEOUT-001](../issues/ORION-F6-CLOSEOUT-001.md).

As consultas determinísticas seguem disponíveis com Cortex desabilitado. O
repertório em português, parser, orçamento de contexto e proteção da Policy
estão cobertos pelas suítes JVM. As provas de interpretação com o modelo real,
streaming, cancelamento e morte de `:ai` com MAIN sobrevivente permanecem
vinculadas à [validação híbrida anterior](F6-HybridSoftware-20261118.md).
A execução nova valida o chat sem modelo, incluindo criação de tarefa com
confirmação humana, colagem rejeitada e retry de inferência após atualizar ou
reabrir a tela.

## Verificações

- Docker executou 226 testes do orquestrador sem falhas ou skips. O build final
  do APK Debug e da instrumentação passou. A primeira execução canônica montou
  Debug, Release e AAB e passou nas checagens estáticas e no lint, mas terminou
  com uma falha JVM ao combinar a implementação anterior com a asserção mais
  restritiva de refresh. A recompilação focada final passou.
- Docker executou 652 testes dos scripts HOST sem falhas. O runner recusou no
  emulador uma instalação preexistente e deixou intacto o arquivo sintético
  privado. O Android confirmou a recusa de substituição com `-R`.
- No emulador API 35/x86_64, páginas de 4 KiB, a execução final contou
  9 iniciados, 9 aprovados e zero falhos ou ignorados. Os hashes locais e
  instalados do par Debug coincidiram.
  - APK `c66a7fd6e2686eec5dc6612c8cc254250d6469c13fc63258e054d0d8bfe84e3d`
  - APK AndroidTest `3619f7582c481f9ddc6782655e40d77c35b3aa2a3f2d0f8d95c4fae08ca36eeb`
- A primeira execução Android expirou com ANRs do sistema. A seguinte passou
  8 de 9 e revelou que um callback repetido do teclado rearmava Enviar. A
  execução final inclui a correção e a asserção de bloqueio após o toque.

A proteção do rascunho e o feedback de retry são efêmeros. A preparação
durável permanece sujeita a ingresso, política e confirmação exata. Esse pacote
preserva schema 6, IPC, permissões, criptografia e recuperação existentes e
mantém os relatórios HOST restritos a contagens e códigos técnicos.

CI remoto, qualificação física, estabilidade, perfis de produção e
`CORTEX_RUNTIME_READY` continuam pendentes. O [ADR-0004](../adr/ADR-0004-f6-development-before-physical-qualification.md)
preserva os critérios de qualificação aplicáveis.
