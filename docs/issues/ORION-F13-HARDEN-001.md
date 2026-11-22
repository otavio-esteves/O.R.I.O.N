# ORION-F13-HARDEN-001 — Hardening final de software

**Objetivo.** Exercitar a variante minificada no Android e gerar evidências
reproduzíveis do empacotamento Release para o Gate I do Plano Mestre §32.

**Arquivos.** Gradle do app, empacotamento de licenças nativas, verificação
de artefatos, CI, runner Android e registro de qualificação.

**Contratos.** ADR-0001, Plano Mestre §§29–32 e INV-012, INV-025, INV-026,
INV-032, INV-033. Debug continua disponível para instrumentação. A variante
de qualificação usa identidade própria, com o APK de teste correspondente.

**Testes e aceite.** Build Release com R8, verificação do APK/AAB e seus
símbolos pelo Build ID, exercício de simbolização JNI, inventário de
dependências e licenças declaradas, suíte de chat sem modelo no APK
minificado e testes de ciclo de chaves no Android Keystore. O CI deve
guardar o mapping, o inventário e os relatórios.

**Fora do pacote.** Assinatura de distribuição, ativação de Cortex ou wake
word e aprovação dos gates físicos, de desempenho e de estabilidade.
