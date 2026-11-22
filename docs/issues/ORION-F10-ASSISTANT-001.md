# ORION-F10-ASSISTANT-001 — System Assistant

**Objetivo.** Implementar a interface do Android System Assistant sobre o Core
e o pipeline push-to-talk da F9.

**Arquivos.** `voice/service`, app Android e Ajustes, estado de saúde e
capabilities relacionadas, testes Android, verificadores do host e CI.

**Contratos.** Arquitetura V3.2 §§23.1 e 86–90, Plano Mestre §16 e ADR-0008.

**Invariantes.** INV-001, INV-003, INV-012, INV-017, INV-018, INV-021,
INV-023 a INV-026, INV-033 e INV-034.

**Testes.** Separação de processos, metadados e permissões de manifesto,
aquisição e perda do papel no Android, sessão do framework, recusa de
dados externos como autoridade e recuperação do Core sem dependência do papel.

**Aceite.** O usuário escolhe o assistente no Android. A sessão abre no
processo adequado e só inicia a captura por interação explícita. A perda do
papel restaura o modo push-to-talk. Sessões e comandos continuam sujeitos à
Startup Barrier e às regras de confirmação. O resultado diferencia os testes
em emulador da qualificação física adiada.

**Fora do pacote.** Wake word, autorização pela tela bloqueada, captura de
contexto da tela e qualquer execução mutável por entrada externa.
