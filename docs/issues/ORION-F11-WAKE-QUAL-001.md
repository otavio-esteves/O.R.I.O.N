# ORION-F11-WAKE-QUAL-001 — Qualificação offline de “Orion”

**Objetivo.** Implementar um candidato leve de KWS, medir seu comportamento
offline com amostras de referência e avaliação distintas e registrar o
estado efetivo de wake word.

**Arquivos.** `voice/wakeword`, verificação do grafo Gradle, diagnóstico do
app, testes relacionados, CI e documentação de qualificação.

**Contratos.** Arquitetura V3.2 §§88–90 e 123, Plano Mestre §17 e ADR-0009.

**Invariantes.** INV-011, INV-017, INV-020, INV-029, INV-030 e INV-032.

**Testes.** Limites e silêncio na entrada PCM, classificação offline de
amostras independentes, denominadores e erros do candidato, ausência da
biblioteca de KWS no APK, metadata de voz e estado no diagnóstico Android.

**Aceite.** A avaliação offline pode ser repetida sem rede e identifica o
ambiente usado. O app informa `DISABLED`, preserva a voz sob demanda e o
build rejeita a inclusão direta ou transitiva do candidato no APK.

**Fora do pacote.** Qualificação em hardware, captura contínua, alteração de
Role Assistant, novos caminhos de autorização e Adaptive Intelligence.
