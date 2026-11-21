# ORION-F7-001 — Proveniência de memória e evidência de origem

**Objetivo.** Iniciar F7 pelo contrato de proveniência da memória. A leitura
selecionada retorna os fatos já persistidos e a referência opcional ao evento
de origem, quando fornecida pelo fluxo de armazenamento autorizado.

**Arquivos.** Contratos em core/model e core/queries, data/database,
data/memory, schema exportado, configuração de versão do APK e testes
afetados pelo schema e pela projeção.

**Contratos e invariantes.** Arquitetura §§63–69 e Plano Mestre §§13.2–13.4,
INV-001/007/010/011/019/035. Inferido é derivado da origem. Confirmação e
confiança são preservadas. Uma referência de origem é um UUID técnico, pode
sobreviver ao ciclo de entrega do evento e nunca é preenchida pelo próprio
evento de criação da memória. Legado sem evidência mantém referência ausente.
Consulta existente conserva seus filtros de autorização e sensibilidade.

**Testes e aceite.** Testar o contrato da proveniência, upgrades de todos os
schemas suportados, preservação de conteúdo, IDs, estado e versões de memória
e claims, leitura com criptografia existente, round-trip do evento de origem
e bloqueio da promoção por inferência.

**Fora do escopo deste incremento.** Implementar novas transições, validade e
expiração, tags/resumos, acesso/retention, novos comandos, FTS, ranking, UI,
histórico, Cortex e o gate de saída F7. Esses itens seguem o plano da fase.
