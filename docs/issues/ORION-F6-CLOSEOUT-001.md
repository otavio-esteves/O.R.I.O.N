# ORION-F6-CLOSEOUT-001 — Correções de confiabilidade do chat

**Objetivo.** Corrigir os três P2 da auditoria de `f054e2e` e avaliar a saída de
software da F6 contra o Plano Mestre §12.7.

**Arquivos.** Controlador e testes de chat em `core/orchestrator`, editor em
`feature/chat`, testes Compose do chat, runner HOST e regressões em
`scripts/android`, e registro da validação.

**Contratos e invariantes.** Arquitetura §§15–18, 58–61 e 75–76, ADR-0001/0004,
INV-004/009/011/021–023/035. O limite continua sendo aplicado antes da admissão.
Rejeitar uma edição preserva o rascunho, e seu envio exige corrigir a entrada ou
escolher explicitamente o texto preservado. Atualizar pedidos preserva o aviso
de inferência e a possibilidade de repetir somente a inferência. O runner
verifica a ausência dos dois pacotes antes de tocar no AVD e a instalação
recusa substituição. O runner só remove instalações que ele próprio concluiu.

**Testes e aceite.** Tocar em Enviar após uma colagem rejeitada não admite o
rascunho anterior. Selecionar o rascunho preservado só reabilita o envio. A
atualização manual e a retomada do chat preservam o retry cognitivo. App ou
APK de testes preexistentes bloqueiam o runner antes de qualquer mutação.
Executar checks JVM/HOST/Docker e as duas classes de chat sem modelo em um AVD
exclusivo. A evidência do modelo real da F6 permanece vinculada a seus próprios
artefatos.

**Fora do escopo.** Qualificação física, ativação de perfis, mudança de IPC,
autoridade, persistência ou política, promoção de readiness e estabilidade.
O diagnóstico da capacidade de ingresso tem um pacote próprio.
