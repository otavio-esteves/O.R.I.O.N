# ORION-FND-008 — Contrato de intenção interpretada

## OBJECTIVE

Implementar `OrionIntent` em `core:model`, conforme Arquitetura V3.2 §18 e
Plano Mestre V1.2 §7.3, sem implementar Intent Router ou execução de Skills.

## FILES ALLOWED

- `core/model/src/main/kotlin/network/orion/core/model/OrionIntent.kt`;
- testes relacionados em `core/model/src/test/**`;
- esta documentação.

## CONTRACTS INVOLVED

`OrionIntent`: `intentType`, `confidence`, `arguments`, `source`,
`requiresCortex`, `correlationId`. Argumentos tipados em memória, sem codec.

## INVARIANTS INVOLVED

- INV-009: intenção interpretada não contorna validação e policy.
- INV-011/035: representação diagnóstica não expõe conteúdo; o contrato não
  concede autorização para persistir ou transportar argumentos.
- INV-018/021/023: intenção não concede capability, confirmação ou execução
  durante Startup Barrier.

## TESTS REQUIRED

- Preservar os seis campos, argumentos tipados e correlationId do produtor.
- Aceitar confiança nos limites 0 e 1 e valor intermediário.
- Rejeitar NaN, infinitos e confiança fora de [0, 1].
- Rejeitar tipo/source em branco sem incluir dados do produtor no erro.
- Não renderizar argumentos ou metadata potencialmente sensíveis em diagnósticos.
- Preservar indicação de Cortex sem criar roteamento ou autoridade implícita.
- Executar testes/lint específicos e validação canônica em `CONTAINER`.

## ACCEPTANCE CRITERIA

Contrato público compilável, sem dependências de produção novas; testes e lint
aprovados; `check assembleDebug assembleRelease` aprovado no Docker. Nenhuma
mudança de manifest, processo, persistência, policy ou executor.

## OUT OF SCOPE

Intent Router, parsing, normalização, catálogo de tipos/origens, limiares de
confiança, policy, SkillDescriptor, ActionRequest, ingress, IPC/serialização,
persistência e integração UI/IA/voz. Não declara `FOUNDATION_READY` ou `F0_PASS`.

## Decisões de implementação

Os seis campos seguem o contrato conceitual da arquitetura. `confidence` é um
`Double` finito no intervalo inclusivo [0, 1]; o valor não define o limiar de
roteamento nem autoriza ação. Tipo e origem são identificadores não vazios,
sem introduzir catálogo ou normalização silenciosa. `requiresCortex` é apenas
metadata do produtor; não inicia inferência nem substitui ResourceGovernor.

`arguments` usa um parâmetro genérico não nulo, seguindo a abordagem do envelope
`OrionEvent` existente. Cada produtor deve fornecer valores de domínio imutáveis;
o envelope não faz cópia profunda, não valida schema/semântica e não torna dados
externos confiáveis. `Unit` pode representar ausência de argumentos. Nenhum campo
possui default que invente origem, confiança ou correlação.

O tipo não é `data class` para evitar representação gerada de conteúdo. Seu
`toString()` é constante e não avalia argumentos, tipo, origem ou correlação.
Erros de validação também usam mensagens estáticas.

## Processo, segurança e evolução

Contrato efêmero sem efeitos, inicialização Android ou dependências de plataforma.
Morte do processo perde o objeto; ingress durável e Action Outbox futuros continuam
responsáveis por recuperação. Não há replay, retry ou confirmação implícitos.
Não há schema persistido ou wire format: esta entrega não exige migração nem
altera IPC. Dados externos continuam não confiáveis (§58 e §23 do AGENTS.md).

## Base e estado de integração

Implementação parte do commit local `f6107e0` (IPC) em branch própria. Esse é um
encadeamento local de trabalho; não se presume merge, revisão ou CI remoto.
Foram consultados AGENTS.md, Arquitetura §§18/58, Plano Mestre §§7.3/7.8,
ADR-0001 e contratos/testes existentes. Não há decisão arquitetural nova ou ADR.

## Evidência de validação

Ambiente `CONTAINER`, via Docker Compose:

- Red: `:core:model:testDebugUnitTest` falhou na compilação dos testes por
  referência não resolvida a `OrionIntent`, antes da implementação.
- Foco: `:core:model:testDebugUnitTest :core:model:lint` passou; oito testes novos
  de intenção e sete existentes de vocabulário, sem falhas, erros ou skips.
  Lint aprovado; 39 tarefas, 17 executadas e 22 `UP-TO-DATE`.
- Canônico: `check assembleDebug assembleRelease` passou; 2211 tarefas,
  63 executadas, duas `FROM-CACHE` e 2146 `UP-TO-DATE`. Inclui testes e lint dos
  módulos, 36 casos de fronteira, validadores de benchmark e gate nativo.

Ambiente `HOST`: revisão dos três arquivos e do escopo, sem novas dependências,
componentes ou inicializadores; verificação de whitespace dos arquivos novos
sem ocorrências. Não há evidência de execução em dispositivo ou
de CI remoto nesta entrega.
