# PROJETO O.R.I.O.N.

## Operational Reasoning & Intelligent Orchestration Network

## Arquitetura Android Nativa — Versão 3.2 FINAL CONSOLIDADA

### Especificação Arquitetural Fechada / Hardened / Implementation-Ready / 100%

**Data:** 2 de setembro de 2026
**Base:** Arquitetura Android Nativa — Versão 3.2 FINAL
**Hardening incorporado:** Correção Consolidada de Hardening — 1º de setembro de 2026
**Natureza:** consolidação normativa, sem criação de V4
**Plataforma de qualificação física inicial:** Samsung Galaxy S21 — 8 GB RAM
**Compatibility target:** versão/API Android mais recente suportada pelo release, incluindo API 37/Android 17 quando aplicável
**Sistema:** Android / One UI
**Linguagem principal:** Kotlin
**Interface:** Jetpack Compose
**IA local:** llama.cpp + GGUF
**Banco principal:** Room + SQLite + FTS
**Configurações:** Jetpack DataStore
**Arquitetura:** local-first, offline, orientada a comandos e eventos, resiliente a morte de processos, com IA sob demanda, recovery transacional, ingressos protegidos e degradação explícita
**Objetivo:** assistente pessoal autônomo, proativo, persistente, seguro e funcionalmente disponível 24 horas por dia dentro do lifecycle permitido pelo Android.

**Nome oficial:** **O.R.I.O.N. — Operational Reasoning & Intelligent Orchestration Network**

---

# 0. STATUS FINAL DA ARQUITETURA

A V3.2 FINAL CONSOLIDADA incorpora integralmente o hardening final da arquitetura e passa a ser a **única referência normativa de implementação**.

A partir desta versão ficam definidos:

```text
topologia e ownership de processos;
limites de confiança entre processos;

protocolo IPC v1;
versionamento e compatibilidade do IPC;
limites de payload Binder;
streaming, ordenação e deduplicação de chunks;
semântica de cancelamento e terminalidade do Cortex;

contrato IPC Voice ↔ Core v1;
OrionCoreGatewayService;
regras de bind/rebind quando :voice/:voice_session existem e main está morto;
separação entre voice control plane leve e voice session pesada;

Command / Event / Query boundaries;
persistência transacional de eventos;
Domain Event Outbox;

Action Outbox;
semântica de UNKNOWN;
recovery strategies por Skill;
idempotência e effectively-once;

confirmation fingerprint vinculado ao payload normalizado;
imutabilidade da ação após confirmação;
expiração e invalidação de confirmação;

controle de concorrência otimista;

RecoveryCoordinator;
Startup Barrier;
IngressCoordinator;
fila durável de ingressos mutáveis;
comportamento de ingressos durante Startup Barrier;
ordem formal de recuperação;

health model agregado;
Direct Boot policy;

regras de relógio monotônico e civil;
recorrência e DST/timezone;

política de armazenamento sensível;
backup desativado por padrão;

manifest attack surface;
segurança IPC;
política de payloads externos não confiáveis;

permissões e capabilities em tempo de execução;

SkillDescriptor obrigatório;
side-effect class e idempotency mode por Skill;

regra de voz/FGS compatível com Android moderno;
regra de alarmes exatos com fallback;

Android 17 Memory Limiter como restrição de primeira classe;
inspeção de ApplicationExitInfo;
prevenção de crash/restart loop por pressão de memória;

fronteira formal da promessa 24/7;
semântica de Force Stop/package stopped;
semântica de background restriction;

matriz separada de performance física e compatibilidade de API Android;

ordem do roadmap com Resource Governor mínimo antes do Cortex;
critérios de readiness;
critérios de recuperação após falhas ambíguas.
```

**Não restam decisões arquiteturais obrigatórias em aberto para iniciar a implementação.**

Continuam existindo parâmetros deliberadamente dependentes de benchmark:

```text
modelo GGUF final;
modelo STT final;
threads;
context size efetivo;
keep-alive;
tokens/s;
limites térmicos;
limites energéticos;
política final de wake word;
thresholds de proatividade;
thresholds de memória;
latências aceitáveis por hardware.
```

Esses pontos são **calibração**, não arquitetura.

## 0.1. IDENTIDADE E CONVENÇÃO DE NOMENCLATURA

A identidade oficial é:

```text
O.R.I.O.N.
Operational Reasoning & Intelligent Orchestration Network
```

Convenção obrigatória:

```text
Nome de produto/UI:              O.R.I.O.N.
Nome falado / wake word:         Orion
Prefixo de tipos/classes Kotlin: Orion
Prefixo machine-readable:        orion_
```

Exemplos:

```text
OrionEvent
OrionIntent
OrionSkill
OrionHealth
OrionVoiceInteractionService

orion_heartbeat
orion_recovery
```

Nenhum novo:

```text
código;
schema;
worker;
classe;
serviço;
log tag;
namespace conceitual;
identificador persistente;
```

deverá nascer com a nomenclatura anterior.

A alteração de identidade não modifica:

```text
topologia;
contratos;
invariantes;
políticas;
```

e a arquitetura permanece **V3.2 FINAL**.

---

# 1. OBJETIVO

O Projeto O.R.I.O.N. será um assistente pessoal executado diretamente no smartphone, com memória persistente, raciocínio local, interação por voz, proatividade e acesso controlado às funcionalidades do Android.

Premissa principal:

```text
LOCAL FIRST
```

Dados pessoais, memórias, inferências, histórico operacional e contexto permanecem no dispositivo por padrão.

O sistema não será apenas:

```text
um aplicativo de chat com IA.
```

Será:

```text
uma camada inteligente integrada ao Android.
```

O O.R.I.O.N. deverá:

```text
receber comandos por texto e voz;
manter memória de longo prazo;
manter contexto conversacional;
gerenciar tarefas, timers e lembretes;
identificar situações relevantes;
iniciar interações proativamente;
falar e ouvir;
executar ações autorizadas;
reagir a eventos do Android;
operar sem internet;
sobreviver a process death;
recuperar estado após reboot;
aprender preferências e padrões;
utilizar IA somente quando necessário;
continuar oferecendo funções essenciais se a IA falhar.
```

---

# 2. DECISÃO DE PLATAFORMA

A arquitetura principal não utilizará:

```text
Termux;
Python;
cron;
Termux:API;
Termux:Boot;
llama-cli como subprocesso;
shell como Action Engine.
```

Esses elementos poderão existir apenas em ambiente de desenvolvimento para:

```text
benchmark;
conversão de modelos;
automação de build;
diagnóstico;
testes externos.
```

Produto final:

```text
APK Android nativo
+
Kotlin
+
Jetpack Compose
+
Room / SQLite / FTS
+
DataStore
+
WorkManager
+
AlarmManager
+
BroadcastReceiver
+
Android Services
+
VoiceInteraction APIs
+
llama.cpp via NDK/JNI
```

---

# 3. PRINCÍPIO CENTRAL — 24/7 SEM PROCESSO IMORTAL

O.R.I.O.N. 24/7 significa **disponibilidade funcional**, não CPU permanentemente ativa.

Não manter:

```text
LLM inferindo permanentemente;
CPU acordada permanentemente;
wake lock permanente;
Whisper contínuo;
modelo carregado indefinidamente;
PID principal imortal.
```

Níveis:

```text
PRESENÇA
reconstruível sempre que o Android permitir iniciar o pacote.

PERCEPÇÃO
eventual ou contínua de baixo custo.

RACIOCÍNIO
sob demanda.

AÇÃO
somente quando necessária.
```

## 3.1. CONTRATO FORMAL DE DISPONIBILIDADE 24/7

No O.R.I.O.N., `24/7` significa:

```text
disponibilidade funcional persistente e best-effort
+
estado durável reconstruível
+
recovery determinístico
+
degradação explícita quando o Android suspender capabilities.
```

Não significa que o aplicativo possa ignorar estados impostos pelo sistema operacional.

A disponibilidade automática pressupõe:

```text
o pacote não está em package stopped / Force Stop;

o Android/OEM permite a execução necessária em background;

as permissões, roles e special accesses necessários permanecem concedidos;

o dispositivo possui recursos suficientes;

o usuário não desabilitou explicitamente a funcionalidade correspondente.
```

Quando o pacote estiver em estado `stopped` por Force Stop:

```text
O.R.I.O.N. não promete autoativação;

não tenta burlar o estado imposto pelo Android;

PendingIntents cancelados pelo sistema são considerados perdidos
do ponto de vista do scheduler do SO;

a recuperação ocorre somente após o pacote voltar a ser iniciado
por uma interação permitida pelo Android;

após retorno:
RecoveryCoordinator
+
ReminderReconciler
+
worker reconciliation.
```

Após retorno de Force Stop, a detecção segue política explícita:

```text
API 35+
→ usar ApplicationStartInfo.wasForceStopped()
  como sinal primário de primeira inicialização após Force Stop;

registrar lastForceStopDetected;

reconstruir PendingIntents/reminders/jobs/callbacks
a partir das fontes persistentes de verdade;

revalidar capabilities;

recalcular OrionHealth;

não tratar o período parado como falha de consistência do Core.
```

`ApplicationExitInfo` continua sendo entrada de diagnóstico de encerramentos anteriores, mas:

```text
REASON_USER_REQUESTED
ou outro exit reason isolado

NÃO é prova suficiente, por si só,
de que ocorreu Force Stop.
```

Em Android 15/API 35+, a entrada no estado stopped cancela `PendingIntent`s do pacote; por isso a recuperação pós-Force-Stop deve reconstruir os artefatos de scheduling a partir do banco e das configurações persistentes.

Quando o Android/OEM colocar o app em background `RESTRICTED`:

```text
jobs podem não executar;

alarmes podem não disparar;

foreground services podem ser impedidos;

BOOT_COMPLETED/LOCKED_BOOT_COMPLETED podem ser postergados
conforme versão/target;

OrionHealth deve refletir:
BACKGROUND_RESTRICTED / DEGRADED;

a UI de diagnóstico deve explicar a limitação ao usuário.
```

Nenhum mecanismo do O.R.I.O.N. tentará contornar restrições de sistema por comportamento não documentado.

---

# 4. PRINCÍPIOS NÃO NEGOCIÁVEIS

```text
1. O processo principal não depende da sobrevivência do Cortex.

2. O Cortex não é dono de dados.

3. Somente o processo principal abre o Room principal.

4. Nenhuma saída de LLM executa uma ação diretamente.

5. Toda ação mutável relevante passa por ActionRequest.

6. Ações externas são idempotentes ou possuem recovery explícito.

7. Resultado externo incerto vira UNKNOWN.

8. UNKNOWN nunca recebe retry cego.

9. Memória possui origem, confiança e sensibilidade.

10. LLM_INFERRED nunca vira fato confirmado automaticamente.

11. Eventos persistentes usam Domain Event Outbox.

12. Consumers persistentes toleram duplicação.

13. IPC é versionado.

14. Payload Binder possui limite interno conservador.

15. Process death é cenário normal de arquitetura.

16. Recovery acontece antes de novas ações externas inseguras.

17. Banco é fonte de verdade para reminders.

18. WorkManager não substitui precisão temporal.

19. Voz respeita lifecycle, permissões e roles do Android.

20. Wake word contínua depende de qualificação física.

21. Nenhum processo precisa ser imortal.

22. Segurança não pode depender da obediência do LLM.

23. Permissões/capabilities são revalidadas no momento do uso.

24. Estado concorrente crítico usa version/transação.

25. Mudança arquitetural futura exige ADR.

26. :voice e :voice_session comunicam-se com o Core apenas por contrato IPC versionado.

27. Confirmação de ação é vinculada ao payload normalizado exato.

28. Nenhum ingresso mutável contorna o Startup Barrier.

29. Limites de memória impostos pelo Android são restrição arquitetural,
não simples benchmark.

30. 24/7 não significa disponibilidade enquanto o pacote estiver
Force Stopped ou proibido pelo sistema.

31. Toda Skill possui metadata declarativa de autorização,
capabilities, efeito e recovery.

32. Hardware performance target e Android API compatibility target
são dimensões independentes.
```

---

# 5. ARQUITETURA GERAL

Fluxo lógico consolidado:

```text
ANDROID / ONE UI
      │
      ├── UI / user commands
      ├── alarms / broadcasts
      ├── notification actions
      ├── app/deep links permitidos
      ├── system callbacks
      └── :voice / :voice_session / system assistant
              │
              ▼
       INGRESS COORDINATOR
              │
        Startup Barrier
              │
              ▼
    Command Dispatcher / Orchestrator
              │
      ┌───────┴────────┐
      │                │
  FAST PATH         CORTEX
      │                │
      └───────┬────────┘
              ▼
          VALIDATORS
              │
              ▼
            POLICY
              │
              ▼
        ACTION REQUEST
              │
              ▼
        ACTION OUTBOX
              │
              ▼
            SKILL
              │
      ┌───────┴────────┐
      │                │
   RESULTADO         UNKNOWN
      │                │
      └───────┬────────┘
              ▼
        PERSIST / AUDIT
              │
              ▼
    DOMAIN EVENT OUTBOX
```

Todo ingresso capaz de gerar:

```text
mutação persistente
ou
efeito externo
```

passa pelo mesmo boundary de:

```text
readiness
+
policy
+
capabilities.
```

---

# 6. TOPOLOGIA DE PROCESSOS

## 6.1. MAIN PROCESS

Responsável por:

```text
UI;
Room;
DataStore;
Repositories;

Command Dispatcher;
Query Gateway;
Domain Event Publisher;

Orchestrator;
Sentinel;
Context Engine;
Policy Engine;

Action Engine;
Action Outbox;
Skills;

Scheduler;
RecoveryCoordinator;
IngressCoordinator;
Ingress durable queue;

ResourceGovernor;

Notifications;
Health aggregation;

OrionCoreGatewayService;
Voice request routing;

ApplicationExitMonitor;
last-exit diagnostics.
```

**Somente o main process abre o Room principal.**

### 6.1.1. MULTIPROCESS BOOTSTRAP

`Application.onCreate()` e inicializadores Android podem executar em mais de um processo do mesmo aplicativo.

Portanto, antes de inicializar infraestrutura, o app deve resolver explicitamente:

```text
OrionProcessRole

MAIN
AI
VOICE_CONTROL
VOICE_SESSION
```

Componente obrigatório desde Foundation:

```text
OrionProcessBootstrapper
```

Regras:

```text
MAIN
→ pode inicializar Room, DataStore, repositories, scheduler, recovery, ingress e DI do Core;

AI
→ inicialização mínima do Cortex/JNI/IPC;
→ nunca inicializa Room, DataStore de negócio, repositories ou scheduler;

VOICE_CONTROL
→ inicialização mínima do control plane de voz e IPC;
→ nunca inicializa Room/repositories/Policy/Cortex;

VOICE_SESSION
→ inicialização sob demanda do pipeline de sessão/áudio;
→ nunca inicializa Room/repositories/Policy/Cortex.
```

Inicializadores automáticos de bibliotecas devem ser auditados para garantir que não abram infraestrutura de `MAIN` em processos auxiliares.

Nenhum grafo DI compartilhado pode, por efeito colateral, violar ownership de processo.

### OrionCoreGatewayService

Endpoint interno usado por processos auxiliares para entregar requests ao Core.

Regras:

```text
android:exported="false";

bind explícito por ComponentName;

somente callers do próprio UID/package;

nenhum acesso direto a Repository por :voice/:voice_session;

toda request mutável entra pelo IngressCoordinator.
```

## 6.2. PROCESSO `:voice` — VOICE CONTROL PLANE

Responsável por permanecer leve e coordenar a entrada de voz:

```text
OrionVoiceInteractionService quando System Assistant estiver habilitado;

controle de sessão;

KWS/wake word de baixo custo quando habilitada e qualificada;

VoiceCoreClient para eventos leves/control plane;

serialização dos Voice DTOs;

rebind ao Core quando necessário;

criação/coordenação do processo de sessão sob demanda.
```

O `VoiceInteractionService` selecionado pelo usuário deve permanecer tão leve quanto possível.

Operações pesadas de interação de voz não pertencem ao processo mantido vivo pelo framework.

## 6.2.1. PROCESSO `:voice_session` — HEAVY VOICE SESSION

Responsável, sob demanda, por:

```text
VoiceInteractionSessionService quando System Assistant estiver habilitado;

VoiceInteractionSession;

sessão ativa de voz;

ponte/buffers de áudio;

VAD de sessão;

STT;

TTS/session presentation quando aplicável;

VoiceCoreClient;

serialização dos Voice DTOs.
```

O processo `:voice_session` é descartável e não precisa permanecer vivo fora de uma interação ativa.

Tanto `:voice` quanto `:voice_session` não podem:

```text
abrir Room;

carregar LLM;

executar regra de negócio pesada;

persistir memórias diretamente;

executar Skills mutáveis;

confirmar ação em nome do usuário;

executar Policy Engine;

considerar transcrição parcial como Command executável.
```

Quando `:voice` ou `:voice_session` estiver vivo e `main` estiver morto:

```text
VoiceCoreClient
  ↓
bind explícito ao OrionCoreGatewayService
  ↓
Android pode recriar main quando permitido
  ↓
RecoveryCoordinator
  ↓
IngressCoordinator
  ↓
request
```

Enquanto:

```text
CoreReadiness = STARTING/RECOVERING
```

a request não executa mutação externa.

Se o Core não puder ser iniciado:

```text
:voice / :voice_session
degrada a sessão de forma segura.
```

Morte de `:voice_session` encerra apenas a sessão pesada correspondente; o Core e o control plane permanecem independentes.

## 6.3. PROCESSO `:ai`

Responsável por:

```text
llama.cpp;
modelo;
tokenização;
prefill;
decoding;
streaming;
cancelamento;
unload;
telemetria de inferência.
```

Não pode:

```text
abrir Room;

acessar contatos/arquivos diretamente;

enviar mensagens;

agendar alarmes;

executar Skills Android;

tomar decisões finais de autorização;

persistir estado de negócio.
```

Cortex é:

```text
motor de inferência
```

e não:

```text
agente autônomo com authority.
```

## 6.4. COMPONENTES DE VOZ EXPOSTOS AO SISTEMA

A regra geral `exported=false` possui exceção para componentes cujo contrato Android exige descoberta/bind pelo sistema.

Quando implementado como System Assistant:

```text
OrionVoiceInteractionService

android:exported="true"
android:permission="android.permission.BIND_VOICE_INTERACTION"

intent-filter:
android.service.voice.VoiceInteractionService

meta-data:
android.voice_interaction
```

`BIND_VOICE_INTERACTION` é obrigatório.

O `OrionVoiceInteractionService` deve ser um **control plane leve**.

A implementação de `VoiceInteractionSessionService`/`VoiceInteractionSession` e operações pesadas de sessão deve usar processo separado, inicialmente:

```text
:voice_session
```

Essa separação é requisito da baseline e deve ser validada na CompatibilityProfile da Fase 10.

Componentes internos não herdam essa exposição.

---

# 7. TRUST BOUNDARIES DE PROCESSO

## MAIN

Autoridade sobre:

```text
dados;
policy;
actions;
capabilities;
business state;
scheduling;
audit.
```

## `:ai`

Recebe apenas:

```text
DTOs;
contexto explicitamente selecionado;
pedido de inferência;
opções de geração.
```

Não recebe authority para ações Android.

## `:voice` / `:voice_session`

Recebem apenas o necessário para o papel de voz correspondente:

```text
:voice
→ control plane leve, KWS qualificado, coordenação e IPC;

:voice_session
→ captura/sessão/reconhecimento/síntese e IPC sob demanda.
```

Nenhum dos processos recebe authority sobre estado de negócio.

Regras gerais:

```text
serviços internos:
android:exported="false" por padrão;

exceções de sistema:
explicitamente documentadas e permission-protected;

OrionVoiceInteractionService:
exported=true exclusivamente pelo contrato Android;

OrionVoiceInteractionService:
exige android.permission.BIND_VOICE_INTERACTION;

OrionCoreGatewayService:
exported=false;

Intents internas:
explícitas;

validar caller UID em endpoints IPC internos quando aplicável;

nenhum endpoint Binder interno aceita caller externo não autorizado;

payloads são validados antes do uso;

:ai nunca recebe capability token para executar Android APIs;

:voice / :voice_session nunca recebem authority de policy ou business state.
```

---

# 8. OWNERSHIP DOS DADOS

Regra:

```text
MAIN PROCESS = DATA OWNER
```

Fluxo para IA:

```text
Room
 ↓
Repository
 ↓
Use Case / Orchestrator
 ↓
Context Builder
 ↓
DTO IPC
 ↓
:ai
```

Nunca:

```text
:ai → Room
:ai → Repository
```

Fluxo de voz:

```text
:voice / :voice_session
  ↓
VoiceCoreClient
  ↓
OrionCoreGatewayService (main)
  ↓
IngressCoordinator
  ↓
Orchestrator / CommandDispatcher
  ↓
Repositories
  ↓
Room
```

Nunca:

```text
:voice / :voice_session → Repository
:voice / :voice_session → Room
:voice / :voice_session → ActionExecutor
:voice / :voice_session → Skill mutável diretamente
```

---

# 9. COMMAND / EVENT / QUERY

A arquitetura separa três tipos de mensagem.

## COMMAND

Pedido de mudança:

```text
CreateTask
CompleteTask
CreateReminder
ConfirmAction
CancelAction
```

Command pode falhar.

## EVENT

Fato já ocorrido:

```text
TaskCreated
TaskCompleted
ReminderFired
MemoryCreated
ActionSucceeded
```

Event não é um pedido.

## QUERY

Consulta:

```text
GetTasks
SearchMemory
GetHealth
GetReminderStatus
```

Não altera estado.

Componentes:

```text
Command Dispatcher
Event Publisher
Query Gateway
```

---

# 10. ORION EVENT

Contrato conceitual:

```text
OrionEvent

eventId
type
schemaVersion
occurredAt
correlationId
causationId
source
payload
```

Eventos persistentes possuem ID globalmente único.

---

# 11. CORRELATION / CAUSATION / ORDER

Cada fluxo relevante utiliza:

```text
correlationId
causationId
```

`correlationId` acompanha uma cadeia lógica.

`causationId` indica o evento/comando que provocou outro fato.

Não pressupor ordenação global.

Ordenação é garantida apenas quando explicitamente necessária por aggregate/stream.

---

# 12. POLÍTICA DE PERSISTÊNCIA DE EVENTOS

Classificação:

```text
EPHEMERAL
DURABLE
AUDIT
```

## EPHEMERAL

Exemplo:

```text
UI animation;
token parcial;
estado temporário de sessão.
```

Pode ser perdido.

## DURABLE

Exemplo:

```text
TASK_CREATED;
REMINDER_SCHEDULED;
ACTION_SUCCEEDED.
```

Deve sobreviver a process death.

## AUDIT

Exemplo:

```text
ação externa;
confirmação;
security failure.
```

Possui retenção maior.

---

# 13. DOMAIN EVENT OUTBOX

Componente/política transversal obrigatório:

```text
DurablePayloadPolicy
```

Aplicável a:

```text
Domain Event Outbox;
Ingress durable queue;
Action payloads persistidos;
outros envelopes genéricos futuros.
```

Payloads duráveis seguem política de minimização e sensibilidade:

```text
preferir IDs/referências a copiar conteúdo;

persistir apenas o mínimo necessário para replay/publicação;

classificar payloadSensitivity;

SENSITIVE/SECRET persistido
→ usar envelope criptográfico versionado definido pela política de dados;

SECRET
→ nunca entrar em log, diagnóstico ou evento em conteúdo bruto
   quando uma referência persistente for suficiente.
```

`DomainEventOutboxEntity` deve possuir metadata suficiente para aplicar essa política, incluindo `schemaVersion` e classificação de sensibilidade do payload quando houver conteúdo persistido.

Quando uma alteração de banco e um evento persistente fizerem parte da mesma unidade lógica:

```text
BEGIN TRANSACTION

alterar aggregate

inserir DomainEventOutboxEntity

COMMIT
```

Dispatcher posterior publica.

Garantia:

```text
AT-LEAST-ONCE DELIVERY
+
IDEMPOTENT CONSUMERS
```

Não prometer exactly-once distribuído.

---

# 14. SENTINEL

O Sentinel observa:

```text
tarefas;
reminders;
contexto;
health;
recursos;
padrões de interação;
proatividade.
```

Não possui autoridade para ignorar Policy Engine.

---

# 15. ORCHESTRATOR

Responsável por escolher:

```text
FAST PATH
ou
COGNITIVE PATH
```

e coordenar:

```text
Intent Router;
Context;
Cortex;
Validators;
Policy;
Skills.
```

---

# 16. FAST PATH

Usado quando regras determinísticas bastarem.

Exemplos:

```text
listar tarefas;

consultar bateria;

marcar tarefa concluída;

criar timer com parâmetros claros;

executar Query simples;

resolver comandos estruturados.
```

Vantagens:

```text
menor latência;
menor consumo;
maior previsibilidade;
funciona sem Cortex.
```

---

# 17. COGNITIVE PATH

Usado para:

```text
linguagem natural ambígua;

interpretação;

extração;

classificação;

resumo;

conversa;

tool selection;

raciocínio que não possui regra determinística suficiente.
```

A saída continua passando por:

```text
Validator
+
Policy Engine.
```

---

# 18. INTENT ROUTER

Produz:

```text
OrionIntent
```

Com campos conceituais:

```text
intentType
confidence
arguments
source
requiresCortex
correlationId
```

Quando uma intent não puder ser resolvida com confiança suficiente:

```text
pedir esclarecimento
ou
usar Cognitive Path.
```

---

# 19. CORTEX

Stack:

```text
llama.cpp
+
GGUF
```

Faixa inicial:

```text
~1B–2B
Q4
```

Candidatos podem incluir:

```text
Qwen 2.5 1.5B
ou sucessor compatível
```

desde que aprovados na Fase 0.

Modelo concreto é parâmetro de benchmark.

---

# 20. MODEL REGISTRY

Entidade conceitual:

```text
ModelRegistryEntity

modelId
displayName
filePath
sha256
sizeBytes
architecture
quantization
contextLimit
status
createdAt
lastValidatedAt
```

Status:

```text
IMPORTED
VALIDATING
READY
INVALID
QUARANTINED
```

---

# 21. GGUF TRUST FLOW

Modelo importado:

```text
selecionar arquivo
 ↓
copiar para storage privado
 ↓
calcular hash
 ↓
validar formato
 ↓
validar tamanho
 ↓
registrar ModelRegistry
 ↓
somente então permitir load
```

Modelo inválido:

```text
não carregar;
quarentena ou exclusão;
diagnóstico.
```

---

# 22. CORTEX SERVICE

Processo:

```text
:ai
```

Serviço interno:

```text
android:exported="false"
```

Contrato mínimo:

```text
getProtocolInfo()
loadModel()
startInference()
cancelInference()
getRequestStatus()
getStatus()
unloadModel()
healthCheck()
```

---

# 23. IPC PROTOCOL V1

Valores iniciais:

```text
protocolMajor = 1
protocolMinor = 0
```

Compatibilidade:

```text
major diferente:
recusar comunicação;

minor diferente:
negociar capabilities comuns.
```

Handshake:

```text
main
 ↓
getProtocolInfo()
 ↓
:ai / :voice / :voice_session
 ↓
version + capabilities
 ↓
compatible?
```

Se incompatível:

```text
subsistema = DEGRADED/UNAVAILABLE
```

Nunca crash global do app.

O `IPC Protocol v1` é protocolo comum aos boundaries internos relevantes.

## 23.1. VOICE ↔ CORE IPC PROTOCOL V1

Componentes:

```text
:voice / :voice_session
  VoiceCoreClient
       │
       ▼
main
  OrionCoreGatewayService
       │
       ▼
  IngressCoordinator
```

Contrato mínimo:

```text
getProtocolInfo()
getCoreReadiness()
submitUtterance()
cancelVoiceRequest()
getVoiceRequestStatus()
registerVoiceCallback()
unregisterVoiceCallback()
healthCheck()
```

DTO:

```text
VoiceUtteranceRequest

schemaVersion
requestId
sessionId
text
isFinalTranscript
locale
capturedAt
source
priority
correlationId
```

Transcrição parcial:

```text
isFinalTranscript = false
```

não pode gerar `ActionRequest` mutável.

Somente:

```text
transcrição final
ou
evento explicitamente definido como executável
```

pode ingressar no Orchestrator como comando.

Resultados de `submitUtterance()`:

```text
ACCEPTED
QUEUED_DURING_RECOVERY
CORE_UNAVAILABLE
REJECTED_INVALID
DUPLICATE_REQUEST
PROTOCOL_MISMATCH
```

Resposta Core → Voice:

```text
VoiceCoreResponse

schemaVersion
requestId
sequence
kind
textDelta opcional
requiresConfirmation
terminalState opcional
errorCode opcional
```

Aplicam-se:

```text
requestId;

sequence monotônica;

deduplicação;

terminalidade lógica única;

late callback ignore;

Binder death;

major/minor negotiation;

MAX_INLINE_IPC_PAYLOAD.
```

Se callback de `:voice` ou `:voice_session` morrer:

```text
a resposta oral pode ser perdida;

o Core não reexecuta side effect;

a ActionRequest persistente mantém sua própria semântica;

confirmação pendente permanece não confirmada;

nenhum texto parcial é reinterpretado como nova ação.
```

Áudio longo bruto não será enviado inline por Binder.

Quando necessário:

```text
ParcelFileDescriptor
ou
SharedMemory quando validado.
```

---

# 24. DTO VERSIONING

Todo DTO IPC evolutivo possui:

```text
schemaVersion
```

Regras:

```text
campos novos preferem defaults;

cliente ignora campo desconhecido quando contrato permitir;

mudança incompatível exige major novo;

não usar serialização Java genérica como contrato de IPC.
```

Preferir:

```text
AIDL parcelables
ou
Parcelable explícito e estável.
```

---

# 25. BINDER PAYLOAD POLICY

Payload inline:

```text
pequeno e previsível.
```

Limite interno conservador:

```text
MAX_INLINE_IPC_PAYLOAD = 256 KiB
```

Acima disso:

```text
ParcelFileDescriptor

arquivo temporário privado

ou

SharedMemory quando tecnicamente validado.
```

Nunca transportar inline arbitrariamente:

```text
modelo;

áudio longo;

histórico grande;

blob grande.
```

---

# 26. INFERENCE REQUEST

Campos conceituais:

```text
requestId
schemaVersion
prompt
context
modelId
maxTokens
temperature
priority
deadlineElapsedRealtime
correlationId
```

Não enviar authority operacional ao Cortex.

---

# 27. STREAMING PROTOCOL

```text
InferenceChunk

requestId
sequence
kind
textDelta opcional
toolDelta opcional
isFinal
finishReason
errorCode opcional
```

Regras:

```text
sequence começa em 0;

sequência cresce monotonicamente por requestId;

main descarta chunk repetido;

chunk fora de ordem é bufferizado de forma limitada
ou falha a requisição;

terminal chunk ocorre no máximo uma vez logicamente;

callbacks tardios após terminal são ignorados.
```

---

# 28. TERMINALIDADE DA INFERÊNCIA

Estados terminais:

```text
SUCCEEDED
CANCELLED
TIMED_OUT
FAILED
PROCESS_DIED
```

Cada `requestId` deve alcançar **um único estado terminal lógico** no main process.

O cliente deduplica sinais finais repetidos causados por IPC/rebind.

---

# 29. CANCELAMENTO

`cancelInference(requestId)` é:

```text
best effort + idempotente
```

Semântica:

```text
cancel antes de iniciar
→ CANCELLED;

cancel durante decode
→ interromper geração;

cancel durante load
→ marcar cancel requested e abortar assim que seguro;

cancel após terminal
→ no-op.
```

Se código nativo não responder ao cancel dentro do timeout:

```text
encerrar/recriar :ai quando necessário.
```

---

# 30. CONCORRÊNCIA DO CORTEX

Inicialmente:

```text
1 heavy inference at a time.
```

Fila:

```text
priority DESC
+
createdAt ASC
```

Prioridades:

```text
INTERACTIVE
USER_COMMAND
REMINDER
PROACTIVE
BACKGROUND
```

Request duplicada com o mesmo `requestId`:

```text
não inicia segunda inferência;

retorna status existente
ou
DUPLICATE_REQUEST.
```

---

# 31. MORTE DO PROCESSO `:ai`

Usar:

```text
DeathRecipient
```

ou mecanismo equivalente.

Ao morrer:

```text
Cortex = UNAVAILABLE;

request em andamento = PROCESS_DIED;

model state = UNKNOWN;

liberar referências Binder;

registrar technical/audit conforme caso;

aplicar fallback;

rebind somente quando necessário.
```

Nunca reconstruir automaticamente uma ação externa com base apenas no texto parcial do LLM.

## 31.1. MORTE DO PROCESSO `:voice`

Ao morrer o control plane:

```text
voice control subsystem = UNAVAILABLE/RECOVERING conforme contexto;

callbacks Binder = removidos;

Core permanece funcional;

:voice_session ativo pode ser encerrado/degradado conforme lifecycle;

nenhuma memória/tarefa persistida é perdida;

nenhuma ActionRequest é recriada
a partir de áudio/transcrição parcial;

rebind ocorre somente quando nova interação de voz exigir.
```

## 31.2. MORTE DO PROCESSO `:voice_session`

Ao morrer a sessão pesada:

```text
sessão efêmera = encerrada;

buffers/callbacks de sessão = descartados;

:voice control plane pode permanecer saudável;

Core permanece funcional;

nenhuma ActionRequest é recriada
a partir de transcript/áudio parcial.
```

Se uma ação já tiver sido persistida no main:

```text
seu recovery segue Action Outbox;

não depende da sobrevivência de :voice
nem de :voice_session.
```

---

# 32. CICLO DE VIDA DO CORTEX

```text
DISCONNECTED
CONNECTING
UNLOADED
LOADING
READY
INFERENCING
CANCELLING
UNLOADING
FAILED
```

Estado normal:

```text
UNLOADED
```

Acionamento:

```text
Orchestrator
 ↓
ResourceGovernor.canRunInference()
 ↓
CortexClient.acquire()
 ↓
bind
 ↓
load
 ↓
infer
 ↓
keep-alive
 ↓
unload
```

---

# 33. RESOURCE GOVERNOR ANTES DO CORTEX

Nenhuma inferência poderá iniciar sem consulta ao `ResourceGovernor`.

Contrato mínimo desde Foundation:

```text
canRunInference()
allowedModelTier()
maxThreads()
maxContext()
maxTokens()
keepAliveMs()

memoryRisk()
estimatedHeadroom()
recentProcessExitSignals()
canLoadModel(modelProfile)
```

`estimatedHeadroom()` é best-effort e nunca representa garantia rígida de memória disponível.

`canRunInference()` considera:

```text
battery;

thermal;

process importance;

modelo;

context size;

threads;

recent memory pressure;

recent process exits;

Memory Limiter signals quando existentes;

foreground/background state.
```

A implementação evolui depois, mas a dependência existe desde o primeiro Cortex.

---

# 34. PERFIS DE RECURSO

```text
NORMAL
ECONOMY
LOW_POWER
CRITICAL
THERMAL_LIMIT
```

Saídas possíveis:

```text
allowedModel;

threads;

contextLimit;

maxTokens;

keepAlive;

backgroundIntensity;

wakeWordPolicy;

heartbeatPolicy.
```

---

# 35. MEMORY PRESSURE

Ao receber pressão de memória:

```text
limpar caches descartáveis;

reduzir contexto futuro;

cancelar preload;

unload do Cortex quando seguro;

liberar buffers JNI;

reduzir retenção in-memory;

reduzir modelo/tier futuro quando necessário;

evitar restart loop de :ai.
```

Nunca perder estado persistente necessário.

## 35.1. ANDROID 17 MEMORY LIMITER

Android 17/API 37 introduz limites de memória de processo tratados como restrição arquitetural.

O O.R.I.O.N. deverá inspecionar histórico recente de encerramentos via:

```text
ApplicationExitInfo
```

no startup/recovery.

Compatibilidade:

```text
se a plataforma expuser reason dedicado de Memory Limiter:
    usar reason dedicado;

se build/API expuser sinal legado/preview:
    aceitar REASON_OTHER
    +
    description contendo "MemoryLimiter:AnonSwap".
```

A arquitetura não depende de um único encoding do motivo entre previews/builds.

Quando houver evidência no `:ai`:

```text
registrar signal;

marcar Cortex DEGRADED até nova avaliação;

reduzir:
model tier
context
threads
keep-alive;

desabilitar preload;

evitar relançamento imediato repetitivo;

exigir nova passagem pelo ResourceGovernor;

registrar telemetry sem conteúdo sensível.
```

Quando houver evidência no main:

```text
executar recovery normal;

reduzir caches;

inspecionar regressão de memória;

marcar health degradation;

não interpretar morte como corrupção do banco por si só.
```

Distinguir:

```text
LOW_MEMORY global

versus

per-process Memory Limiter

versus

native crash/OOM

versus

user/system kill sem evidência de memória.
```

---

# 36. CLOCK POLICY

Existem dois conceitos:

```text
WALL CLOCK

Instant / ZonedDateTime

para compromissos e horários humanos.
```

e:

```text
MONOTONIC CLOCK

elapsedRealtime

para timeout, duração e performance.
```

Nunca medir timeout com relógio civil.

Nunca armazenar deadline relativo apenas como uptime quando precisar sobreviver a reboot.

---

# 37. TASK ENTITY

```text
id
title
description
createdAt
dueInstant
originalLocalDateTime
zoneId
priority
status
reminderStrategy
lastNotificationAt
notificationCount
snoozedUntil
completedAt
version
```

Status:

```text
PENDING
ACTIVE
SNOOZED
COMPLETED
CANCELLED
```

---

# 38. TIMEZONE E RECORRÊNCIA

Para recorrência:

```text
LocalDateTime
+
ZoneId
+
RecurrenceRule
```

Resolver a próxima ocorrência individualmente.

Não usar `setRepeating()` como fonte primária para recorrências civis exatas.

A cada disparo:

```text
calcular próxima ocorrência
 ↓
persistir
 ↓
agendar próximo alarme
```

---

# 39. DST / HORÁRIO LOCAL INVÁLIDO OU AMBÍGUO

Regra:

```text
GAP
hora local inexistente:

adiar para o primeiro instante local válido após o gap.
```

```text
OVERLAP
hora repetida:

preferir o offset anterior da recorrência quando disponível;

caso contrário usar o offset mais cedo
e registrar decisão.
```

O ajuste deve ser auditável no reminder.

---

# 40. REMINDER ENTITY

```text
id
taskId
triggerInstant
localDateTime
zoneId
precision
scheduleMethod
requestCode
status
fireGeneration
createdAt
lastScheduledAt
lastFiredAt
version
```

`fireGeneration` evita aceitar disparos antigos depois de reagendamento.

---

# 41. EXACT ALARM CAPABILITY

Componente:

```text
ExactAlarmCapability
```

Responsável por:

```text
canScheduleExactAlarms();

explicar necessidade ao usuário;

abrir fluxo de special access quando necessário;

escolher fallback;

revalidar antes de agendar.
```

Política:

```text
usar SCHEDULE_EXACT_ALARM
quando o usuário habilitar lembretes exatos;

não depender de USE_EXACT_ALARM
como premissa do produto;

usar alarmes exatos apenas
para eventos realmente user-facing
e temporalmente precisos.
```

---

# 42. FALLBACK DE ALARME

Se exato estiver indisponível:

```text
precision = APPROXIMATE
```

Alternativas:

```text
setWindow / alarme inexato;

WorkManager quando compatível;

notificação antecipada;

explicação no diagnóstico.
```

Nunca falhar silenciosamente.

---

# 43. RECONCILIAÇÃO DE REMINDERS

Banco é fonte da verdade.

Executar `ReminderReconciler` após:

```text
BOOT_COMPLETED;

USER_UNLOCKED quando necessário;

TIME_CHANGED;

TIMEZONE_CHANGED;

MY_PACKAGE_REPLACED / app update;

concessão de exact alarm;

app start;

alteração relevante de permissão/capability;

recovery;

retorno permitido após Force Stop quando aplicável.
```

---

# 44. WORKMANAGER

Usar para trabalho persistente e adiável:

```text
heartbeat;

cleanup;

reconciliation;

maintenance;

retry interno seguro;

summary generation não urgente;

telemetry compaction.
```

Heartbeat inicial:

```text
30 min
```

É manutenção oportunística, não mecanismo de precisão.

---

# 45. UNIQUE WORK

Workers recorrentes terão nomes determinísticos.

Exemplos:

```text
orion_heartbeat

orion_recovery

orion_reminder_reconcile

orion_log_cleanup

orion_event_outbox_dispatch

orion_action_recovery
```

Usar políticas de unique work coerentes com a natureza de cada trabalho.

---

# 46. BOOT E DIRECT BOOT

Banco pessoal principal permanece em:

```text
credential-protected storage
```

A V3.2 não duplica memórias/tarefas sensíveis em device-protected storage.

`LOCKED_BOOT_COMPLETED`:

```text
não abre Room principal;

não inicia microfone;

não inicia Cortex;

pode registrar apenas marcador técnico mínimo
não sensível se necessário.
```

Após usuário desbloquear / `BOOT_COMPLETED`, conforme disponibilidade:

```text
enqueue RecoveryCoordinator.
```

---

# 47. RECOVERYCOORDINATOR

Componente obrigatório.

Responsável por orquestrar recuperação determinística.

Estados:

```text
NOT_STARTED
STARTING
RECOVERING
READY
DEGRADED
FAILED
```

---

# 48. STARTUP BARRIER

Enquanto `CoreReadiness` não estiver em:

```text
READY
ou
DEGRADED_SAFE
```

não:

```text
executar ação externa;

iniciar proatividade mutável;

consumir ActionRequest que possa duplicar efeito.
```

Leituras locais seguras podem ser liberadas antes quando o banco estiver íntegro.

## 48.1. INGRESS COORDINATOR

Componente obrigatório:

```text
IngressCoordinator
```

Todo ingresso externo ou entre processos capaz de produzir mutação passa por ele.

Fontes:

```text
UI command;

AlarmManager;

BroadcastReceiver;

notification action;

:voice / :voice_session;

app/deep link permitido;

system callback;

future external integration.
```

Classificação:

```text
READ_ONLY_SAFE

MUTATING_DURABLE

EPHEMERAL
```

Durante:

```text
CoreReadiness = STARTING/RECOVERING
```

### READ_ONLY_SAFE

```text
somente quando o estado mínimo necessário
já estiver íntegro.
```

### MUTATING_DURABLE

```text
persistir/enfileirar quando storage estiver disponível;

não executar efeito externo.
```

### EPHEMERAL

```text
rejeitar/degradar
ou
manter fila limitada conforme contrato específico.
```

Entidade durável:

```text
IngressEnvelopeEntity

ingressId
schemaVersion
source
type
payload
payloadSensitivity
receivedAt
priority
idempotencyKey
correlationId
status
expiresAt
attemptCount
lastError
```

Status:

```text
QUEUED
PROCESSING
CONSUMED
REJECTED
EXPIRED
FAILED
```

Política de payload do ingresso:

```text
minimizar conteúdo persistido;

preferir referência/ID a blob textual;

SENSITIVE/SECRET
→ usar envelope criptográfico versionado;

payload temporário grande
→ usar storage privado referenciado,
   com lifetime/cleanup explícitos;

SECRET
→ nunca entrar em logs/diagnostics.
```

Garantias:

```text
ingressId/idempotencyKey evitam duplicação lógica;

consumer é idempotente;

nenhum ingresso mutável ignora Startup Barrier;

ingress persistido é drenado após recovery em ordem:

priority DESC
+
receivedAt ASC;

expiração é explícita para eventos
que deixam de fazer sentido.
```

Ingress Queue não substitui:

```text
Action Outbox
nem
Domain Event Outbox.
```

Papéis:

```text
IngressQueue
=
fato/pedido recebido que ainda não pode ser processado;


ActionOutbox
=
efeito mutável autorizado
que precisa ser executado/reconciliado;


DomainEventOutbox
=
fato de domínio commitado
que precisa ser publicado.
```

---

# 49. ORDEM FORMAL DE STARTUP / RECOVERY

```text
1. Inicializar logging mínimo.

2. Abrir banco.

3. Executar migrations.

4. Verificar invariantes estruturais.

5. Inicializar DataStore/config.

6. Inspecionar ApplicationExitInfo
   e sinais da sessão anterior.

7. Inicializar IngressCoordinator
   em modo GATED.

8. Recuperar Domain Event Outbox pendente.

9. Recuperar Action Outbox.

10. Reconciliar reminders.

11. Reconciliar unique workers.

12. Revalidar capabilities/permissões relevantes.

13. Reconstruir caches/estado efêmero.

14. Aplicar ResourceGovernor inicial
    com sinais de memória/thermal/bateria.

15. Calcular OrionHealth.

16. Publicar CORE_READY ou CORE_DEGRADED.

17. Liberar Startup Barrier.

18. Mudar IngressCoordinator para OPEN
    e drenar ingressos duráveis pendentes.
```

Cortex e voz pesada permanecem fora da recuperação obrigatória do Core.

---

# 50. ACTION REQUEST ENTITY

```text
actionId
skill
argumentsEncryptedOrPlainBySensitivity
authorizationLevel
status
createdAt
updatedAt
idempotencyKey
correlationId
attemptCount
lastError
requiresConfirmation
confirmedAt
executionStartedAt
executedAt
executionToken
recoveryStrategy
version

normalizedPayloadHash
confirmationFingerprint
confirmationExpiresAt
confirmedPayloadHash
policyVersionAtConfirmation
payloadLockedAt
```

Status:

```text
PENDING
NEEDS_CONFIRMATION
CONFIRMED
RUNNING
SUCCEEDED
FAILED
CANCELLED
UNKNOWN
MANUAL_REVIEW
```

---

# 51. ACTION OUTBOX STATE MACHINE

Fluxo básico:

```text
PENDING
  ↓
NEEDS_CONFIRMATION
  ↓
CONFIRMED
  ↓
RUNNING
  ↓
SUCCEEDED
```

Falhas conhecidas:

```text
RUNNING
 ↓
FAILED
```

Resultado externo ambíguo:

```text
RUNNING
 ↓
UNKNOWN
```

`UNKNOWN` não retorna automaticamente a `RUNNING`.

## 51.1. CONFIRMAÇÃO VINCULADA AO PAYLOAD

Nenhuma confirmação será representada apenas por `confirmedAt`.

Antes de apresentar confirmação:

```text
1. normalizar:
   skill
   argumentos
   target
   authorizationLevel;

2. produzir representação canônica estável;

3. calcular normalizedPayloadHash;

4. calcular confirmationFingerprint;

5. persistir versão/policy relevante.
```

Conceitualmente:

```text
confirmationFingerprint = SHA-256(
    skill
    + canonicalNormalizedArguments
    + normalizedTarget
    + authorizationLevel
    + actionId
    + policyVersion
)
```

O hash é vínculo lógico de integridade, não segredo.

Ao confirmar:

```text
confirmedPayloadHash = normalizedPayloadHash;

confirmedAt = now;

confirmationExpiresAt =
policy-defined quando aplicável;

payloadLockedAt = now.
```

Após `CONFIRMED`, tornam-se imutáveis:

```text
skill;

normalized arguments;

target;

authorizationLevel;

side-effect classification;

recoveryStrategy;

idempotency semantics.
```

Se qualquer campo material precisar mudar:

```text
não alterar silenciosamente
a ActionRequest confirmada;

invalidar confirmação;

preferencialmente criar
nova ActionRequest/actionId;

solicitar nova confirmação
quando exigida pela policy.
```

Antes de:

```text
CONFIRMED → RUNNING
```

executar:

```text
recalcular hash/fingerprint;

comparar com confirmado;

verificar expiração;

revalidar capability;

revalidar permission;

revalidar policy.
```

Se houver divergência:

```text
não executar.
```

Resultados possíveis:

```text
CONFIRMATION_STALE

CONFIRMATION_MISMATCH

CONFIRMATION_EXPIRED

POLICY_CHANGED_RECONFIRM_REQUIRED
```

A regra protege contra:

```text
replay semântico
+
TOCTOU entre confirmação e execução.
```

---

# 52. EFFECTIVELY-ONCE

A arquitetura busca:

```text
effectively-once
```

quando possível.

Combinação:

```text
idempotency key
+
persistência
+
recovery strategy
+
consulta de estado externo quando possível.
```

Não prometer exatamente uma execução física quando o sistema externo não fornece garantia suficiente.

---

# 53. RECOVERY STRATEGY

Cada Skill mutável declara estratégia.

Exemplos:

```text
RETRY_SAFE

VERIFY_THEN_RETRY

NO_RETRY

MANUAL_REVIEW
```

`RETRY_SAFE` somente quando duplicação for comprovadamente segura.

`VERIFY_THEN_RETRY` exige consulta externa antes da repetição.

`NO_RETRY` termina com falha/UNKNOWN conforme caso.

`MANUAL_REVIEW` exige decisão posterior.

---

# 54. UNKNOWN

Significa:

```text
não foi possível determinar
se o efeito externo aconteceu.
```

Exemplos:

```text
timeout após request enviada;

process death após chamada externa;

callback perdido;

serviço remoto respondeu de forma ambígua.
```

Em `UNKNOWN`:

```text
não repetir cegamente;

consultar estado externo quando possível;

pedir revisão quando necessário;

registrar audit.
```

---

# 55. EXECUTION TOKEN

Ao entrar em `RUNNING`:

```text
executionToken
```

é persistido.

Quando houver lease temporal persistida para detectar executor morto, ela deve ser vinculada à sessão de boot:

```text
leaseBootSessionId
leaseStartedElapsedRealtime
leaseExpiresElapsedRealtime
leaseWallStartedAt   // apenas auditoria/diagnóstico
```

Semântica obrigatória:

```text
mesmo BootSessionId
→ monotonic clock pode decidir validade/expiração da lease;

BootSessionId diferente após reboot
→ lease antiga é considerada abandonada/inválida;

lease abandonada/inválida
NÃO significa
que o efeito externo não ocorreu.
```

Após process death/reboot, a continuação depende exclusivamente da `RecoveryStrategy`/`SideEffectClass`:

```text
RETRY_SAFE
VERIFY_THEN_RETRY
UNKNOWN
MANUAL_REVIEW
```

Nunca converter reboot em autorização para retry cego.

---

# 56. CONCORRÊNCIA DE ESTADO

Entidades mutáveis críticas utilizam:

```text
version
```

e optimistic concurrency.

Abrange:

```text
TaskEntity

ReminderEntity

MemoryEntity

ActionRequestEntity

aggregates relevantes
```

Operação:

```text
UPDATE ... WHERE id=? AND version=?
```

Se `0 rows`:

```text
CONFLICT
```

Política:

```text
retry limitado
apenas para operações puramente locais
e recomputáveis;

sem retry cego de ação externa;

conflito semanticamente relevante
volta ao Orchestrator/usuário.
```

---

# 57. TRANSAÇÕES ROOM

Sempre que duas alterações formarem unidade lógica:

```text
usar @Transaction
ou
transação explícita.
```

Exemplos:

```text
Task update
+
DomainEventOutbox insert;


Memory supersession
+
nova Memory insert;


Action transition
+
audit event;


Reminder schedule state update
+
domain event.
```

---

# 58. POLICY ENGINE

Determinístico.

Fluxo consolidado:

```text
Structured Intent
 ↓
Schema Validator
 ↓
Semantic Validator
 ↓
SkillDescriptor lookup
 ↓
Policy Engine
 ↓
Capability Check
 ↓
Permission Check
 ↓
Confirmation Policy
 ↓
ActionRequest
+
normalizedPayloadHash
 ↓
confirmation binding quando exigido
```

LLM não consegue elevar `authorizationLevel`.

Nenhuma implementação de Skill pode reduzir em runtime o nível de autorização definido por metadata/policy.

---

# 59. AUTORIZAÇÃO

```text
SAFE_READ

SAFE_WRITE

SENSITIVE_READ

EXTERNAL_ACTION

DESTRUCTIVE
```

Política de confirmação pode depender de:

```text
action type;

user preference permitida;

context;

risco;

external target;

reversibilidade.
```

Nenhuma preferência pode eliminar confirmação obrigatória definida como hard policy para ação destrutiva/externa crítica.

---

# 60. TOOL CALLING

Cortex produz estrutura validável.

Exemplo:

```json
{
  "skill": "task.create",
  "arguments": {
    "title": "Entregar relatório",
    "dueAt": "2026-09-01T16:00:00-03:00"
  }
}
```

Pipeline:

```text
parse
 ↓
schema validation
 ↓
normalization
 ↓
semantic validation
 ↓
SkillDescriptor
 ↓
policy
```

Se JSON inválido:

```text
no máximo uma tentativa controlada
de repair para resultado não sensível;

caso contrário:
falhar
ou
pedir esclarecimento.
```

Nunca executar texto livre.

---

# 61. PROMPT INJECTION DEFENSE

Conteúdo de:

```text
arquivo;

notificação;

mensagem;

site;

OCR;

contato;

calendário;

memória importada;

texto copiado;
```

é:

```text
UNTRUSTED DATA
```

Esse conteúdo:

```text
não concede permissão;

não muda policy;

não aumenta autorização;

não chama Skill diretamente;

não altera system rules;

não pode inserir segredo em logs.
```

---

# 62. MEMORY ENGINE

```text
Room
+
SQLite
+
FTS
```

Categorias:

```text
SEMANTIC
EPISODIC
OPERATIONAL
CONVERSATIONAL
PREFERENCE
STATE
```

Embeddings não fazem parte do MVP obrigatório.

---

# 63. MEMORY ENTITY

```text
id
content
summary
type
tags
importance
confidence
source
origin
originEventId
userConfirmed
inferred
sensitivity
supersedesId
validFrom
validUntil
createdAt
updatedAt
lastAccessedAt
accessCount
expiresAt
status
version
```

---

# 64. MEMORY CLAIMS

Para evitar misturar múltiplas afirmações com confiança diferente:

```text
MemoryEntity
  └── MemoryClaimEntity[]
```

`MemoryClaimEntity` é obrigatório quando uma memória consolidada contiver múltiplos fatos independentes relevantes.

Campos:

```text
claimId
memoryId
statement
origin
confidence
userConfirmed
status
validFrom
validUntil
```

No MVP, memórias simples de uma única afirmação podem existir sem decomposição.

---

# 65. ORIGEM DA MEMÓRIA

```text
USER_DECLARED
SYSTEM_OBSERVED
SYSTEM_DERIVED
LLM_INFERRED
IMPORTED
EXTERNAL_SOURCE
```

---

# 66. REGRA CONTRA FALSAS MEMÓRIAS

`LLM_INFERRED` nunca vira fato confirmado automaticamente.

Memória inferida:

```text
inferred = true

userConfirmed = false

confidence = limitada
```

Informação sensível ou prejudicial não será consolidada apenas por inferência.

---

# 67. CONFIRMAÇÃO E SUPERSESSION

Nova informação conflitante:

```text
nova.supersedesId = antiga.id

antiga.status = SUPERSEDED
```

A operação ocorre em transação.

Histórico não é apagado imediatamente.

---

# 68. STATUS DE MEMÓRIA

```text
ACTIVE
CANDIDATE
SUPERSEDED
EXPIRED
REJECTED
ARCHIVED
```

---

# 69. SENSIBILIDADE

```text
PUBLIC
PERSONAL
SENSITIVE
SECRET
```

Influencia:

```text
exibição;

logs;

FTS;

prompt inclusion;

exportação;

retenção;

criptografia.
```

---

# 70. SEGURANÇA DE DADOS — DECISÃO FINAL

Banco principal protegido por:

```text
Android app sandbox
+
Android file-based encryption do sistema
+
criptografia em nível de aplicação
para campos SENSITIVE/SECRET
```

Chaves:

```text
AES-GCM data key
+
proteção/wrapping via Android Keystore
```

A V3.2 não exige:

```text
SQLCipher
```

no MVP.

Full-database encryption poderá ser adicionada futuramente se requisito formal ou benchmark justificar.

---

# 71. FTS E DADOS SENSÍVEIS

Por padrão:

```text
PUBLIC/PERSONAL
→ elegíveis a FTS conforme política;

SENSITIVE
→ conteúdo criptografado,
fora do FTS bruto;

SECRET
→ nunca indexado em FTS.
```

Pode existir resumo redigido/indexável somente se:

```text
não revelar dado sensível
e
a policy permitir.
```

---

# 72. BACKUP

V1:

```text
automatic cloud backup = OFF
```

Manifest/extraction rules impedem backup automático de:

```text
banco;

modelos;

memórias;

secrets.
```

Exportação futura:

```text
manual;

opt-in;

criptografada;

com confirmação explícita.
```

---

# 73. DATA DELETION

Usuário poderá excluir:

```text
memória;

histórico;

tarefas;

logs;

modelo importado;

todos os dados do O.R.I.O.N.
```

Para campos criptografados:

```text
key destruction
```

pode ser usada para crypto-erasure quando aplicável.

Não prometer apagamento físico garantido de blocos flash.

---

# 74. MEMORY RETRIEVAL

```text
query normalization
 ↓
FTS
 ↓
candidates
 ↓
filter status/sensitivity
 ↓
rank
 ↓
Context Budget
```

Ranking:

```text
text score

+ recency

+ importance

+ confidence

+ relevance

- expired penalty

- superseded penalty
```

---

# 75. CONTEXT ENGINE

Produz:

```text
ContextSnapshot
```

Possíveis campos:

```text
timestamp
dayOfWeek
battery
charging
powerMode
thermalState
screenInteractive
ringerMode
quietHours
lastInteraction
tasksDueSoon
activeConversation
deviceState
voiceState
healthSummary
```

Coleta invasiva é:

```text
opt-in
+
minimizada.
```

---

# 76. CONTEXT BUDGET MANAGER

Entrada:

```text
maxContextTokens

reservedOutputTokens

requestType

resourceProfile
```

Prioridade:

```text
1. system/policy

2. solicitação atual

3. dados obrigatórios da Skill

4. safety constraints

5. memórias altamente relevantes

6. conversa recente

7. contexto secundário
```

Nunca truncar prompt final cegamente por caracteres.

---

# 77. CONVERSATION MEMORY

Estratégia:

```text
recent window
+
session summary
+
relevant memories
```

Resumo não substitui histórico persistido.

Resumo produzido por LLM é:

```text
SYSTEM_DERIVED
ou
LLM_INFERRED
```

conforme o conteúdo.

Nunca promove novos fatos automaticamente.

---

# 78. PROATIVIDADE

Score conceitual:

```text
urgency

+ priority

+ deadlineProximity

+ importance

+ contextRelevance

+ inactivity

- notificationFatigue

- quietHours

- recentDismissal
```

Faixas iniciais configuráveis:

```text
0–39
ignore

40–59
observe

60–79
notify

80–89
high

90–100
critical
```

Thresholds finais são calibração.

---

# 79. ANTI-SPAM

Obrigatório:

```text
cooldown;

notification budget;

quiet hours;

recent dismissal penalty;

same-topic suppression;

max notifications per window.
```

O Sentinel não pode repetir indefinidamente o mesmo alerta.

---

# 80. FEEDBACK DE PROATIVIDADE

Registrar:

```text
shown
opened
dismissed
ignored
snoozed
completed
timeToAction
```

Aprendizado inicial:

```text
regras
+
estatística simples
```

Não depender de LLM.

---

# 81. VOICE ENGINE

Níveis:

```text
1. PUSH-TO-TALK

2. FOREGROUND VOICE MODE

3. SYSTEM ASSISTANT / ROLE_ASSISTANT

4. QUALIFIED WAKE WORD
```

O app funciona sem `ROLE_ASSISTANT`.

Wake word contínua não é requisito para validade do produto.

---

# 82. PUSH-TO-TALK

```text
user action
 ↓
audio capture
 ↓
VAD
 ↓
STT
 ↓
Orchestrator
 ↓
response
 ↓
TTS
```

Primeiro pipeline de voz obrigatório.

---

# 83. STT

Local.

Candidatos:

```text
whisper.cpp

sherpa-onnx
```

Seleção por benchmark.

STT pesado não fica permanentemente ativo.

---

# 84. TTS

V1:

```text
Android TextToSpeech
```

Fallback:

```text
texto na UI/notificação.
```

---

# 85. FOREGROUND VOICE MODE

Ativado explicitamente pelo usuário.

Requer, conforme versão Android:

```text
RECORD_AUDIO

FOREGROUND_SERVICE

FOREGROUND_SERVICE_MICROPHONE

foregroundServiceType="microphone"
```

Regra:

```text
não iniciar microphone FGS
arbitrariamente do background;

preferir início por ação visível do usuário;

usar exceções oficiais
apenas quando realmente aplicáveis;

nunca iniciar microfone
a partir do boot.
```

---

# 86. ROLE_ASSISTANT

Usar:

```text
RoleManager.ROLE_ASSISTANT
```

somente com consentimento explícito.

Perda do role:

```text
capabilities recalculadas;

voz degrada para
push-to-talk / foreground mode;

Core permanece funcional.
```

---

# 87. VOICEINTERACTIONSERVICE

```text
OrionVoiceInteractionService
```

Processo:

```text
:voice
```

Composition root:

```text
mínimo.
```

Não carregar dentro desse processo:

```text
Room;

Repositories;

Cortex;

Policy Engine;

STT pesado;

buffers/UI de sessão pesada.
```

A sessão pesada pertence a:

```text
OrionVoiceInteractionSessionService
+
OrionVoiceInteractionSession
+
processo :voice_session
```

Comunicação de negócio de ambos os processos ocorre por:

```text
VoiceCoreClient
 ↓
OrionCoreGatewayService
 ↓
IngressCoordinator.
```

---

# 88. WAKE WORD GATE

Pipeline quando qualificada:

```text
PCM
 ↓
KWS leve
 ↓
keyword
 ↓
VAD
 ↓
STT
 ↓
Orchestrator
 ↓
TTS
```

Whisper contínuo é proibido como KWS.

---

# 89. POLÍTICA DE WAKE WORD

Resultado do benchmark:

```text
ALWAYS

CHARGING_ONLY

ACTIVE_HOURS

VOICE_MODE_ONLY

DISABLED
```

`ResourceGovernor` pode reduzir dinamicamente a política.

Nunca elevar acima da preferência do usuário.

---

# 90. ANDROID 17+ AUDIO HARDENING POLICY

A arquitetura assume endurecimento de áudio em background.

Portanto:

```text
áudio em background deve estar ligado
a contexto permitido pelo sistema;

foreground service precisa de
tipo/permissões corretos;

ROLE_ASSISTANT/VoiceInteractionService
pode fornecer caminho privilegiado
quando realmente concedido;

sem role ou contexto permitido:
degradar para ação iniciada pelo usuário.
```

Nenhum design depende de comportamento não documentado de OEM.

---

# 91. PERMISSÕES E CAPABILITIES

Permissões são progressivas.

Exemplos:

```text
notificações;

microfone;

contatos;

localização;

exact alarm special access.
```

Existe:

```text
CapabilityRegistry
```

que traduz:

```text
permission
+
role
+
OS state
+
app setting
```

em capabilities funcionais.

---

# 92. REVALIDAÇÃO DE CAPABILITY

Nunca confiar indefinidamente em cache de permissão.

Revalidar:

```text
antes de ação sensível;

ao retornar ao foreground;

no RecoveryCoordinator;

após SecurityException;

após sinais de alteração
de role/special access.
```

---

# 93. MANIFEST ATTACK SURFACE

Regra:

```text
exported=false por padrão.
```

Exceções somente quando contrato oficial Android exigir discovery/bind externo.

Exemplo:

```text
OrionVoiceInteractionService:

  exported=true;

  permission=
  android.permission.BIND_VOICE_INTERACTION;

  intent-filter mínimo exigido pelo framework.
```

```text
OrionCoreGatewayService:

  exported=false;

  bind explícito interno.
```

Para componentes exportados:

```text
permission-protect
quando houver permissão de sistema apropriada;

minimizar intent filters;

validar action/extras;

não aceitar authority de negócio
em payload externo;

não expor Repository/Binder interno.
```

---

# 94. PENDINGINTENT POLICY

Usar:

```text
FLAG_IMMUTABLE por padrão;

requestCode determinístico;

Intent explícita;

action única quando necessário;

extras mínimos.
```

Mutable PendingIntent somente quando a API exigir e com justificativa documentada.

---

# 95. NOTIFICATIONS

Canais separados:

```text
REMINDERS

PROACTIVE

VOICE_SERVICE

SYSTEM_STATUS

ERRORS optional
```

Se `POST_NOTIFICATIONS` estiver negada:

```text
não considerar reminder entregue;

registrar capability degraded;

mostrar diagnóstico na próxima interação;

continuar estado interno.
```

---

# 96. ORION HEALTH

Objeto agregado:

```text
OrionHealth

core
database
scheduler
reminders
actionOutbox
eventOutbox
voice
cortex
permissions
notifications
resourceState
lastRecovery
```

Cada subsistema:

```text
HEALTHY

DEGRADED

UNAVAILABLE

RECOVERING

FAILED
```

Estados específicos relevantes, como:

```text
BACKGROUND_RESTRICTED
```

devem refletir-se na degradação correspondente.

---

# 97. AGENT STATE

```text
OFFLINE

IDLE

LISTENING

PROCESSING_SPEECH

THINKING

ACTING

SPEAKING

LOW_POWER

THERMAL_LIMIT

RECOVERY

DEGRADED

ERROR
```

`AgentState` é:

```text
estado operacional efêmero derivado.
```

Não é fonte de verdade persistente.

Após process death deve ser reconstruído a partir de:

```text
DB
+
subsystem health
+
active sessions.
```

---

# 98. ERROR TAXONOMY

```text
TRANSIENT

PERMANENT

USER_ACTION_REQUIRED

PERMISSION_DENIED

POLICY_DENIED

VALIDATION_FAILED

RESOURCE_DENIED

CANCELLED

TIMEOUT

EXTERNAL_STATE_UNKNOWN

SECURITY_FAILURE

NATIVE_FAILURE
```

Retry somente quando:

```text
a categoria
e
a operação
```

permitirem.

---

# 99. OBSERVABILIDADE

Por inferência:

```text
requestId
model
contextTokens
generatedTokens
loadTime
firstTokenLatency
totalLatency
tokensPerSecond
threads
thermalBefore
thermalAfter
rssBefore
peakRss
rssAfter
result
```

Por action:

```text
actionId
skill
state transitions
attempts
recoveryStrategy
result category
```

Sem conteúdo sensível por padrão.

---

# 100. AUDIT / DOMAIN / TECHNICAL LOG

## AUDIT

Longa duração configurável:

```text
ação externa;

ação destrutiva;

confirmação;

alteração de memória relevante;

security failure;

UNKNOWN resolution.
```

## DOMAIN EVENT

```text
TASK_CREATED

TASK_DUE

TASK_COMPLETED

MEMORY_CREATED

REMINDER_SNOOZED
```

## TECHNICAL LOG

```text
binder connected;

worker start;

model loaded;

performance;

recovery timing.
```

---

# 101. RETENÇÃO

Valores iniciais:

```text
AUDIT
longo prazo/configurável

DOMAIN EVENTS
90–365 dias por tipo

TECHNICAL LOG
7–30 dias

DEBUG VERBOSE
24–72 h
```

`SECRET` nunca é gravado em log.

---

# 102. SKILL CONTRACT

Contrato consolidado:

```kotlin
data class SkillDescriptor(
    val name: String,
    val authorizationLevel: AuthorizationLevel,
    val recoveryStrategy: RecoveryStrategy,
    val sideEffectClass: SideEffectClass,
    val idempotencyMode: IdempotencyMode,
    val requiredCapabilities: Set<Capability>,
    val requiredPermissions: Set<String>,
    val requiresUserPresence: Boolean,
    val confirmationPolicy: ConfirmationPolicy,
    val supportsDryRun: Boolean,
    val argumentSchemaVersion: Int,
)

interface OrionSkill {
    val descriptor: SkillDescriptor

    fun canHandle(intent: OrionIntent): Boolean

    suspend fun execute(
        intent: OrionIntent,
        context: SkillContext
    ): SkillResult
}
```

Enums mínimos:

```text
SideEffectClass

READ_ONLY
LOCAL_MUTATION
EXTERNAL_REVERSIBLE
EXTERNAL_IRREVERSIBLE
DESTRUCTIVE
```

```text
IdempotencyMode

NATURAL
KEYED
VERIFY_THEN_RETRY
NONE
```

Regras:

```text
SkillDescriptor é estático/declarativo
por Skill/version;

Policy Engine lê descriptor
sem depender da implementação concreta;

recoveryStrategy
não é decidido pelo LLM;

requiredCapabilities
são revalidadas imediatamente antes da execução;

requiredPermissions
não substituem CapabilityRegistry;

supportsDryRun
não autoriza execução real;

DESTRUCTIVE
nunca reduz confirmação
por preferência comum.
```

---

# 103. SKILLS INICIAIS

```text
MemorySkill

TaskSkill

ReminderSkill

BatterySkill

NotificationSkill

TimeSkill

TimerSkill

DeviceStatusSkill

TtsSkill
```

Posteriores:

```text
CalendarSkill

ContactsSkill

FilesSkill

PhoneSkill

MessagingSkill

LocationSkill

BluetoothSkill

HomeAutomationSkill
```

---

# 104. DEPENDENCY RULES ENTRE MÓDULOS

Regras:

```text
feature
→ core APIs / use cases

skills
→ skill api + permitted repositories

ai service
→ ai api + native wrapper

voice service
→ voice api

:ai module
não depende de data/database

:voice module
não depende de data/database

core policy
não depende de ai implementation

repositories
não dependem de UI
```

Ciclos de dependência Gradle são proibidos.

---

# 105. ESTRUTURA DE MÓDULOS

```text
app/

core/
├── common/
├── model/
├── commands/
├── queries/
├── events/
├── ipc/
├── orchestrator/
├── sentinel/
├── context/
├── policy/
├── actions/
├── resources/
├── recovery/
├── health/
├── security/
├── observability/
├── ingress/
├── exitinfo/
└── skills/
    └── metadata/

data/
├── database/
├── datastore/
├── repository/
├── memory/
├── tasks/
├── events/
├── reminders/
└── actions/

ai/
├── api/
├── service/
├── cortex/
├── llama/
├── prompt/
├── contextbudget/
├── modelregistry/
└── inference/

voice/
├── api/
├── service/
├── wakeword/
├── vad/
├── stt/
├── tts/
├── assistant/
├── ipc/
└── coreclient/

skills/
├── api/
├── memory/
├── tasks/
├── reminders/
├── device/
├── notifications/
└── ...

scheduler/
├── workers/
├── alarms/
├── reconciliation/
└── boot/

feature/
├── home/
├── chat/
├── tasks/
├── memories/
├── activity/
├── diagnostics/
└── settings/
```

O módulo `voice` pode depender de:

```text
core:ipc
+
DTO APIs estáveis
```

mas não de:

```text
data/database/repositories.
```

---

# 106. DEPENDENCY INJECTION

Main process pode usar DI Android compatível.

`:ai`, `:voice` e `:voice_session`:

```text
composition roots mínimos e explícitos.
```

Não fazer lifecycle de processo auxiliar depender de grafo DI complexo.

---

# 107. BUILD POLICY

```text
compileSdk
=
latest stable disponível e validado;

targetSdk
=
no mínimo requisito vigente do Google Play;

atualização de target
=
compatibility suite antes do release;

minSdk inicial
=
31;

ABI inicial
=
arm64-v8a;

Java/Kotlin toolchain
=
17 ou superior suportado pelo stack validado.
```

Baseline setembro/2026:

```text
targetSdk >= API 36.
```

Android 17/API 37 será adotado quando a suíte específica de compatibilidade passar, sem alterar arquitetura.

---

# 108. NATIVE LAYER

```text
NDK
+
CMake
+
JNI mínimo
```

Regras:

```text
ownership nativo explícito;

RAII no C++;

Kotlin nunca manipula ponteiro cru
fora do wrapper;

callbacks JNI não retêm Activity;

thread attachment/detachment controlado;

exceptions nativas não atravessam JNI
sem tradução.
```

---

# 109. NATIVE FAILURE POLICY

Em:

```text
cancel;

timeout;

unload;

service destroy;

native error;

OOM nativo;

corruption suspicion;
```

liberar recursos quando seguro.

Se runtime nativo entrar em estado indefinido:

```text
matar :ai é preferível
a contaminar o main process.
```

---

# 110. DATABASE MIGRATIONS

Regras:

```text
migration test obrigatória;

sem destructive migration em produção;

backup automático não usado
como estratégia de migration;

versão de schema controlada;

invariantes pós-migration verificadas.
```

Falha irreparável:

```text
Core = FAILED/DEGRADED;

não apagar banco silenciosamente;

oferecer diagnóstico/export recovery
quando tecnicamente possível.
```

---

# 111. TESTES UNITÁRIOS

Obrigatórios:

```text
Intent Router;

Command handlers;

Policy Engine;

Authorization;

Proactivity score;

Memory ranking;

Memory provenance;

Memory claims;

Context Budget;

Time calculations;

DST resolver;

Reminder strategy;

Resource Governor;

Idempotency;

Action transitions;

UNKNOWN recovery;

IPC sequence dedup;

health aggregation;

confirmation fingerprint;

confirmation expiration;

confirmed payload immutability;

SkillDescriptor policy resolution;

IngressCoordinator gating;

ingress dedup/idempotency;

Memory Limiter signal classification;

ApplicationExitInfo classification;

VoiceCore request terminality.
```

---

# 112. TESTES DE BANCO

```text
migration;

transactions;

optimistic concurrency;

Action Outbox recovery;

Domain Event Outbox recovery;

IngressEnvelope persistence/recovery;

memory supersession;

FTS consistency;

encrypted-field handling;

corruption strategy;

backup disabled verification.
```

---

# 113. TESTES DE PROCESS DEATH

Cenários:

```text
matar main;

matar :ai;

matar :voice;

matar :voice_session;

matar durante inferência;

matar durante streaming;

matar durante ActionRequest;

matar depois de confirmação
antes de execução;

matar durante execução;

matar depois de efeito externo
antes de SUCCEEDED;

matar após DB commit
antes de event publish;

matar durante ReminderReconcile;

matar main
e iniciar sessão via :voice/:voice_session;

matar :voice ou :voice_session
durante bind ao Core;

matar :voice ou :voice_session
após submitUtterance;

matar :voice ou :voice_session
com confirmação pendente;

matar main
após receber ingress
e antes de consumir;

matar main
após persistir ingress
e antes de liberar barrier;

matar :ai repetidamente
por limite de memória
e verificar ausência de restart loop.
```

Resultado esperado:

```text
sem duplicação cega;

sem perda silenciosa de estado persistido;

nenhuma confirmação fabricada;

ingresso mutável permanece durável
quando previsto;

UNKNOWN quando necessário;

recovery determinístico;

Core pode ser reconstruído por bind interno
quando Android permitir;

Force Stop não é tratado
como process death recuperável automaticamente.
```

---

# 114. TESTES DE IPC

```text
protocol major mismatch;

minor capability negotiation;

Binder death;

callback duplicado;

chunk duplicado;

chunk fora de ordem;

cancel durante load;

cancel durante decode;

requestId duplicado;

payload acima do limite interno;

rebind;

TransactionTooLarge prevention;

Voice ↔ Core major mismatch;

Voice ↔ Core minor negotiation;

:voice/:voice_session bind com main morto;

CoreReadiness RECOVERING
→ QUEUED_DURING_RECOVERY;

callback de voz morto;

transcrição parcial
tentando gerar ação;

requestId duplicado entre rebinds;

large voice payload path;

caller externo tentando
OrionCoreGatewayService.
```

---

# 115. TESTES DE ANDROID SYSTEM

```text
reboot;

locked boot;

Doze;

battery saver;

background restriction;

notification denied;

microphone revoked;

exact alarm unavailable;

exact alarm granted/revoked;

timezone change;

time change;

app update;

low storage;

memory pressure;

role assistant granted/revoked;

Android 17 background audio constraints
quando aplicável;

Force Stop em Android 15+;

reabertura após Force Stop
e reconstrução de reminders;

background battery state RESTRICTED;

retorno de RESTRICTED
para permitido;

ApplicationExitInfo
após kill por memória;

Android 17/API 37 Memory Limiter;

API 37 voice/background-audio
compatibility suite.
```

Teste de Force Stop deve esperar:

```text
nenhuma auto-recuperação
enquanto package stopped;

recuperação somente depois
de interação permitida pelo sistema.
```

---

# 116. TESTES DE VOZ

Medir:

```text
latência;

WER/acurácia adequada ao caso;

CPU;

bateria;

thermal;

false positive KWS;

false negative KWS;

screen off;

ruído;

uso contínuo;

interação com chamada/áudio de terceiros;

perda de permission/role.
```

---

# 117. TESTES DE SEGURANÇA

```text
LLM chama Skill não autorizada;

prompt injection em arquivo;

argumento inválido;

destructive sem confirmação;

external content tenta mudar policy;

Binder caller inválido;

PendingIntent spoof attempt;

retry duplicado;

memória inferida tenta virar fato;

SECRET tenta entrar em log;

modelo GGUF inválido;

capability revogada durante fluxo;

alteração de payload
após confirmação;

caller externo tentando
OrionCoreGatewayService.
```

Todos falham de forma segura.

---

# 118. SOAK TEST

Antes da estabilidade:

```text
24h

72h

vários dias
```

Com:

```text
heartbeat;

reminders;

inferências;

process death;

reboot;

voz;

uso normal;

Action Outbox;

Event Outbox;

resource pressure;

resource profile downgrade/upgrade;

Memory Limiter induced failures;

restrição de background
e recuperação posterior;

Voice ↔ Core rebind repetido;

ingress backlog durante recovery.
```

Verificar:

```text
crash;

ANR;

battery;

RAM;

thermal;

DB;

duplicidade;

perda de alarmes;

UNKNOWN acumulado;

logs sem limite;

recovery loops;

restart loops de :ai.
```

---

# 119. PHASE 0 — DEVICE + API QUALIFICATION GATE

A Fase 0 possui duas dimensões independentes:

```text
A. PERFORMANCE QUALIFICATION TARGET

Samsung Galaxy S21 — 8 GB RAM
```

e:

```text
B. ANDROID COMPATIBILITY TARGET

versão/API mais recente suportada pelo release,

incluindo API 37/Android 17
em emulador

e, idealmente,
aparelho físico compatível.
```

O S21 é válido para:

```text
LLM performance;

RAM real;

thermal;

battery;

STT/KWS;

long-run.
```

Não é evidência suficiente para comportamento específico de APIs futuras que não execute.

Métricas físicas:

```text
cold load;

first token;

tokens/s;

RAM baseline;

peak RSS;

RAM pós-unload;

temperature;

context 2048;

context 4096;

threads;

battery;

unload;

process death recovery;

STT baseline;

KWS preliminar quando aplicável.
```

Acrescentar:

```text
ApplicationExitInfo baseline;

Memory Limiter behavior em API 37;

model/context tiers após memory kill;

background versus foreground memory behavior;

:ai restart-loop prevention;

VoiceInteractionService manifest/bind validation
na API de compatibilidade.
```

Cada `BenchmarkProfile` registra:

```text
hardware;

OS build;

app build;

model hash;

parameters;

results;

pass/fail thresholds
definidos para aquele release.
```

Cada release também terá:

```text
CompatibilityProfile

androidApi
osBuild
manufacturer/device ou emulator image
appBuild
targetSdk
voiceRoleTests
alarmTests
backgroundTests
memoryLimiterTests
pass/fail
```

---

# 120. CRITÉRIOS DO CORE

Core aprovado quando:

```text
não perde tarefas persistidas;

não duplica ação local idempotente;

recupera Action Outbox;

recupera Domain Event Outbox;

trata UNKNOWN corretamente;

reconcilia reminders;

sobrevive reboot;

sobrevive main process death;

funciona sem Cortex;

não transforma inferência em fato;

mantém banco íntegro;

Startup Barrier impede ação externa
durante recovery inseguro;

IngressCoordinator impede mutação
durante recovery;

ingresso durável sobrevive
process death;

confirmation binding detecta
payload alterado;

Force Stop é reconhecido
como fronteira de disponibilidade;

background restriction aparece
como health degradation;

last-exit memory signals influenciam
ResourceGovernor sem causar loop.
```

---

# 121. CRITÉRIOS DO CORTEX

```text
load previsível;

stream ordenado;

cancel funcional;

timeout funcional;

unload adequado;

process death isolado;

rebind funcional;

sem TransactionTooLarge
no protocolo normal;

performance aceitável
no performance target;

thermal controlável;

Memory Limiter kill
não gera relaunch infinito;

resource downgrade ocorre
de forma determinística;

ApplicationExitInfo é diagnosticável;

API 37 CompatibilityProfile aprovado
quando aplicável.
```

---

# 122. CRITÉRIOS DA VOZ

Push-to-talk aprovado quando:

```text
captura confiável;

STT aceitável;

TTS funcional;

permission loss segura;

sem FGS ilegal;

sem dependência de boot microphone.
```

System Assistant aprovado quando:

```text
role acquisition/loss testado;

VoiceInteractionService estável;

Core funciona sem role;

:voice/:voice_session conseguem bind ao Core
quando main está morto
e Android permite iniciar pacote;

request durante Core RECOVERING
não executa mutação prematuramente;

VoiceInteractionService protegido por
BIND_VOICE_INTERACTION;

Core permanece funcional
se :voice ou :voice_session morrer;

voz não reconstrói ação
a partir de transcript parcial.
```

---

# 123. CRITÉRIOS DA WAKE WORD

Continuidade somente se:

```text
estável por horas/dias;

consumo aceitável versus baseline;

thermal aceitável;

false positive aceitável;

false negative aceitável;

funciona screen-off
no modo suportado;

não degrada STT/Cortex
de forma inaceitável;

compatível com políticas
Android/One UI.
```

Se falhar:

```text
produto continua válido.
```

---

# 124. READINESS MODEL

```text
CoreReadiness

STARTING

RECOVERING

READY

DEGRADED_SAFE

BLOCKED
```

`DEGRADED_SAFE` permite:

```text
funções determinísticas seguras;

UI;

consultas locais disponíveis;

reminders quando scheduler saudável.
```

`BLOCKED` impede ações mutáveis quando invariantes centrais não estiverem garantidos.

---

# 125. DIAGNÓSTICO

Tela deverá exibir:

```text
CoreReadiness;

OrionHealth;

last recovery;

last heartbeat;

DB schema/status;

Action Outbox counts;

UNKNOWN actions;

Event Outbox backlog;

workers;

reminders;

exact alarm capability;

notification capability;

background restriction;

assistant role;

voice state;

Cortex state;

IPC protocol;

model/hash;

tokens/s;

RAM;

thermal;

last inference;

last error;

resource profile;

Voice ↔ Core IPC state;

Voice protocol version;

Ingress queue count;

oldest ingress age;

lastForceStopDetected
quando detectável;

background restriction state;

last process exit reason;

last :ai exit reason;

Memory Limiter signals;

resource downgrade reason;

estimated memory headroom best-effort;

CompatibilityProfile/API status;

confirmation mismatch/security counters
sem payload sensível.
```

---

# 126. HOME

Exemplo:

```text
O.R.I.O.N.
Operational Reasoning & Intelligent Orchestration Network

● DISPONÍVEL

Core: OK
Memória: OK
Lembretes: OK
Cortex: dormindo
Voz: push-to-talk
Recursos: NORMAL

3 tarefas pendentes
1 compromisso hoje

[FALAR]
```

Falha parcial não vira erro global automaticamente.

---

# 127. DATASTORE

Configurações pequenas:

```text
voiceEnabled

heartbeatMinutes

quietHours

proactivityLevel

selectedModel

maxContext

cortexKeepAliveSeconds

wakeWordPolicy

resourceProfile

logRetention

exactReminderPreference

assistantMode
```

Dados relacionais permanecem em Room.

Secrets não ficam em DataStore plaintext.

---

# 128. ROADMAP FINAL

## FASE 0 — DEVICE + API QUALIFICATION

```text
LLM benchmark;

STT preliminar;

RAM;

thermal;

context;

threads;

model;

process death;

ApplicationExitInfo;

Android 17 Memory Limiter test;

API compatibility matrix.
```

Resultado:

```text
performance target qualificado
+
compatibility target qualificado.
```

## FASE 1 — FOUNDATION

```text
Android project;

Compose;

modules;

Room;

DataStore;

Command/Event/Query APIs;

logging;

Health skeleton;

Recovery skeleton;

ResourceGovernor minimal API;

common IPC version contract;

VoiceCore IPC API skeleton;

IngressCoordinator skeleton;

SkillDescriptor API;

ApplicationExitMonitor skeleton;

tests base.
```

## FASE 2 — DATA + TRANSACTION CORE

```text
entities;

repositories;

optimistic concurrency;

Domain Event Outbox;

Action Outbox;

idempotency;

Policy Engine;

migrations;

provenance;

IngressEnvelope persistence;

confirmation fingerprint/hash;

payload lock after confirmation;

SkillDescriptor integration
with Policy Engine.
```

## FASE 3 — DETERMINISTIC SKILLS

```text
Task;

Reminder;

Time;

Timer;

Battery;

Device;

Notification;

Memory basic.
```

## FASE 4 — SCHEDULER + RECOVERY

```text
WorkManager;

AlarmManager;

ExactAlarmCapability;

ReminderReconciler;

BootReceiver;

RecoveryCoordinator;

Startup Barrier;

timezone/DST;

Ingress gating/drain;

Force Stop return reconciliation;

background restriction diagnostics;

ApplicationExitInfo recovery input.
```

## FASE 5 — CORTEX RUNTIME

```text
:ai;

IPC Protocol v1;

CortexService;

Binder/AIDL;

large payload path;

llama.cpp;

GGUF;

stream;

cancel;

timeout;

model registry;

Memory Limiter-aware ResourceGovernor;

restart-loop protection;

exit-reason telemetry.
```

## FASE 6 — HYBRID ORCHESTRATOR

```text
Fast Path;

Cognitive Path;

Intent Router;

validators;

tool schema;

Context Budget.
```

## FASE 7 — INTELLIGENT MEMORY

```text
FTS;

ranking;

provenance;

claims;

supersession;

conversation summaries;

confirmation.
```

## FASE 8 — SENTINEL + FULL RESOURCE GOVERNOR

```text
heartbeat;

context;

resource policy;

proactivity;

anti-spam;

feedback.
```

## FASE 9 — VOICE PUSH-TO-TALK

```text
VAD;

STT;

TTS;

session state;

VoiceCoreClient;

:voice control plane;

:voice_session on-demand session;

OrionCoreGatewayService integration;

request/response terminality;

main-dead bind test.
```

## FASE 10 — SYSTEM ASSISTANT

```text
ROLE_ASSISTANT;

VoiceInteractionService;

VoiceInteractionSession;

VoiceInteractionSessionService;

:voice;

:voice_session;

manifest contract;

BIND_VOICE_INTERACTION protection;

exported component security review;

API compatibility profile.
```

## FASE 11 — WAKE WORD QUALIFICATION

```text
KWS "Orion";

battery;

thermal;

screen-off;

Doze/OS behavior;

long-run.
```

## FASE 12 — ADAPTIVE INTELLIGENCE

```text
feedback modeling;

habit modeling;

adaptive ranking;

personalização;

adaptive proactivity.
```

---

# 129. MVP CORE

Deve:

```text
abrir;

persistir;

criar/listar/concluir tarefas;

agendar reminders;

sobreviver fechamento;

sobreviver reboot;

reconciliar após startup;

executar heartbeat;

notificar;

usar Skills determinísticas;

registrar Action Outbox;

registrar Domain Event Outbox;

não duplicar ação local;

tratar UNKNOWN;

exibir health;

possuir IngressCoordinator;

impedir ingresso mutável
durante recovery inseguro;

vincular confirmação
ao payload exato;

registrar/interpretar
last process exit básico;

exibir background restriction/
availability degradation.
```

Sem IA obrigatória.

---

# 130. MVP COGNITIVE

Adiciona:

```text
chat;

modelo local;

memória pesquisável;

intent natural;

Context Budget;

Cortex isolado;

IPC v1;

structured tool calling.
```

Somente após Core estável.

---

# 131. V1 OPERACIONAL

Deverá:

```text
funcionar offline;

possuir memória;

entender linguagem natural;

usar IA local;

ter Fast Path;

controlar tarefas/reminders;

ser proativo;

falar;

ouvir;

executar Skills;

lembrar contexto;

recuperar reboot/process death;

monitorar recursos;

manter audit;

possuir Policy Engine;

Action Outbox;

Domain Event Outbox;

RecoveryCoordinator;

IngressCoordinator;

Health model;

regras de memória;

confirmation binding;

SkillDescriptors;

degradar graciosamente.
```

---

# 132. O QUE NÃO SERÁ FEITO

A arquitetura proíbe como caminho principal:

```text
shell arbitrário;

comandos gerados pelo LLM;

Room em :ai, :voice ou :voice_session;

microfone clandestino no boot;

wake lock infinito;

burlar Doze/background restrictions;

burlar Force Stop/package stopped;

credenciais plaintext;

áudio bruto salvo por padrão;

retry cego após timeout externo;

inferência virando fato automaticamente;

logs infinitos;

PID eterno;

Binder com blobs grandes arbitrários;

exported components sem necessidade;

destructive migration silenciosa;

tratar RUNNING como “não executado”
após crash;

usar AgentState
como fonte de verdade;

alterar silenciosamente
payload de ActionRequest confirmada;

permitir ingresso mutável
contornar Startup Barrier;

permitir que :voice ou :voice_session
execute business state diretamente;

reiniciar :ai indefinidamente
após Memory Limiter kill.
```

---

# 133. INVARIANTES ARQUITETURAIS

Devem ser verificáveis por testes/review:

```text
INV-001
Somente main abre Room principal.

INV-002
:ai não executa Android Skill.

INV-003
:voice / :voice_session não alteram estado de negócio diretamente.

INV-004
Toda ação mutável relevante possui ActionRequest.

INV-005
Toda ação externa incerta pode terminar UNKNOWN.

INV-006
UNKNOWN não recebe retry genérico automático.

INV-007
Domain event persistente nasce com commit do estado.

INV-008
Consumer persistente tolera duplicação.

INV-009
LLM output nunca pula validator/policy.

INV-010
LLM_INFERRED nunca vira confirmed automaticamente.

INV-011
SECRET não entra em logs/FTS.

INV-012
IPC respeita protocol version e payload guard.

INV-013
Cortex process death não derruba Core.

INV-014
Recovery executa antes de ações externas pendentes.

INV-015
Reminder DB é fonte da verdade.

INV-016
WorkManager não é usado para horário exato.

INV-017
Microfone não é iniciado indiscriminadamente no boot.

INV-018
Capability é revalidada antes de ação sensível.

INV-019
Estado concorrente usa version/transação.

INV-020
Wake word contínua só existe após gate aprovado.

INV-021
Toda confirmação é vinculada ao payload normalizado
exato da ActionRequest.

INV-022
ActionRequest confirmada não pode ter payload material
alterado sem invalidar a confirmação.

INV-023
Nenhum ingresso mutável executa side effect
enquanto Startup Barrier bloquear o Core.

INV-024
:voice / :voice_session comunicam comandos ao Core somente por IPC
versionado e nunca acessa estado de negócio diretamente.

INV-025
OrionCoreGatewayService não é exportado
para apps externos.

INV-026
VoiceInteractionService exposto ao sistema exige
BIND_VOICE_INTERACTION e superfície mínima.

INV-027
Sinal de Memory Limiter altera política de recursos
antes de nova heavy inference.

INV-028
Morte repetida de :ai por recurso não pode gerar
restart loop infinito.

INV-029
Force Stop/package stopped é fronteira explícita
da disponibilidade 24/7.

INV-030
Background restriction é degradada de forma observável;
não é burlada.

INV-031
Toda Skill possui SkillDescriptor coerente com
authorization/recovery/idempotency.

INV-032
Performance target de hardware não substitui
compatibility target da API Android.

INV-033
Bootstrap de processo auxiliar não inicializa
infraestrutura de ownership exclusivo do main.

INV-034
VoiceInteractionService permanece leve; sessão pesada
de System Assistant usa processo separado do control plane.

INV-035
Payload durável genérico de ingress/outbox respeita
minimização, classificação de sensibilidade e criptografia.

INV-036
Lease persistida baseada em relógio monotônico somente
é válida dentro do BootSessionId que a criou.
```

---

# 134. DEFINIÇÃO DE “100% FECHADA”

Nesta documentação, “100% fechada” significa:

```text
um desenvolvedor pode iniciar a implementação

sem precisar escolher:

nova topologia;

novo ownership;

novo modelo de segurança;

nova estratégia de recovery;

nova semântica de action retry;

nova estratégia de eventos;

nova regra de concorrência;

novo contrato de IPC;

nova política de armazenamento;

nova política de scheduling;

nova política de voice lifecycle;

como os processos de voz acordam/conversam com main;

como requests de voz aguardam recovery;

como ingressos concorrentes
com startup são tratados;

como confirmação fica ligada
à ação exata;

como alterações pós-confirmação
são invalidadas;

como Skills declaram
capabilities/efeitos/recovery;

como Memory Limiter
influencia Cortex;

como last process exit
participa do recovery;

qual é a fronteira real do 24/7
diante de Force Stop/restrições;

como separar benchmark físico
de compatibilidade de API.
```

O desenvolvedor continua escolhendo:

```text
valores de calibração
e
bibliotecas concretas
```

dentro dos contratos definidos.

---

# 135. PRINCÍPIO DE RESILIÊNCIA FINAL

Objetivo não é:

```text
“meu processo vive para sempre”.
```

Objetivo:

```text
“o agente continua existindo
porque seu estado,
suas regras
e sua recuperação
não dependem da sobrevivência
de um processo específico”.
```

Persistência:

```text
Room
```

Entrega de eventos:

```text
Domain Event Outbox
```

Ações:

```text
Action Outbox
```

Ingressos:

```text
IngressCoordinator
+
Ingress durable queue
```

Tempo:

```text
AlarmManager
/
WorkManager
/
Reconciler
```

Recuperação:

```text
RecoveryCoordinator
```

Raciocínio:

```text
Cortex descartável
```

Percepção:

```text
Voice process descartável
e reconstruível
quando Android permitir.
```

A resiliência existe dentro do lifecycle permitido pelo Android.

```text
Process death:

cenário recuperável.
```

```text
Reboot:

cenário reconciliável
conforme capabilities
e storage state.
```

```text
Force Stop/package stopped:

suspensão deliberada
imposta pelo sistema/usuário;

não é contornada;

recovery começa depois
que Android permitir
nova inicialização.
```

```text
Background restriction:

capability degradada;

health/diagnóstico
devem refletir estado.
```

---

# 136. FLUXO FINAL DO AGENTE

```text
                 O.R.I.O.N.
                     │
        ┌────────────┴────────────┐
        │                         │
      PERCEBE                   LEMBRA
        │                         │
        └────────────┬────────────┘
                     │
              INGRESS COORDINATOR
                     │
              STARTUP BARRIER
                     │
                     ▼
                  AVALIA
                     │
               intent clara?
                │        │
               sim      não
                │        │
                ▼        ▼
            FAST PATH  CORTEX
                │        │
                └────┬───┘
                     ▼
                 VALIDATOR
                     ▼
              SKILL DESCRIPTOR
                     ▼
                  POLICY
                     ▼
              ação necessária?
                │          │
               não        sim
                │          │
                ▼          ▼
             RESPONDE   ACTION REQUEST
                           │
                    confirmação?
                       │       │
                      não     sim
                       │       │
                       │   BIND PAYLOAD
                       │       │
                       └───┬───┘
                           ▼
                     ACTION OUTBOX
                           │
                           ▼
                         SKILL
                           │
                    ┌──────┴──────┐
                    │             │
                 resultado     incerto
                    │             │
                    ▼             ▼
                SUCCEEDED       UNKNOWN
                    │             │
                    └──────┬──────┘
                           ▼
                    PERSIST / AUDIT
                           ▼
                    DOMAIN EVENTS
                           ▼
                          IDLE
```

---

# 137. REGRA FUNDAMENTAL FINAL

```text
IA quando necessária.

Regras quando suficientes.

Persistência antes de depender do estado.

Dados pertencem ao Core.

LLM nunca executa diretamente.

Memória possui origem e confiança.

Ação possui política,
outbox
e estratégia de recovery.

Evento persistente possui outbox.

Contexto possui orçamento.

Recursos possuem governador.

Alarmes possuem capability
e reconciliação.

IPC possui versão,
limite
e terminalidade.

Concorrência possui versionamento.

Recovery possui ordem
e barrier.

Voz respeita lifecycle
e permissões do Android.

Wake word precisa provar
que vale o custo.

Nenhum processo precisa ser imortal.

Voz entra no Core
por IPC versionado.

Ingressos respeitam Startup Barrier.

Confirmação pertence ao payload exato
que o usuário aprovou.

Skills declaram seus efeitos,
capabilities
e recovery.

Memory Limiter é tratado
pelo Resource Governor.

24/7 respeita Force Stop
e restrições legítimas do Android.

Compatibilidade de API
é testada separadamente
de performance de hardware.
```

---

# 138. DIFERENÇA ENTRE V3.1 E V3.2 FINAL CONSOLIDADA

```text
V3.1
Binder/AIDL conceitual

V3.2
IPC Protocol v1
+
version negotiation
+
payload guard
+
chunk semantics
```

```text
V3.1
Event Bus comum

V3.2
Command/Event/Query
+
Domain Event Outbox
```

```text
V3.1
Action Outbox + UNKNOWN

V3.2
UNKNOWN semantics
+
RecoveryStrategy
+
executionToken
+
effectively-once
```

```text
V3.1
version em Task

V3.2
optimistic concurrency
como regra transversal
```

```text
V3.1
recovery distribuído

V3.2
RecoveryCoordinator
+
Startup Barrier
+
ordem formal
```

```text
V3.1
estado global do agente

V3.2
OrionHealth
+
CoreReadiness
+
AgentState derivado
```

```text
V3.1
segurança de banco
aberta entre alternativas

V3.2
FBE/sandbox
+
field encryption SENSITIVE/SECRET
+
no automatic backup
```

```text
V3.1
boot receiver curto

V3.2
Direct Boot policy explícita
```

```text
V3.1
voz compatível com FGS

V3.2
política explícita
para microphone FGS/background/assistant role
```

```text
V3.1
ResourceGovernor definido

V3.2
API mínima obrigatória
antes do primeiro Cortex
```

Hardening final:

```text
V3.2 pre-hardening
:voice definido como processo,
sem Core IPC completamente normatizado
e sem separação explícita entre control plane e sessão pesada

V3.2 FINAL
VoiceCore IPC v1
+
OrionCoreGatewayService
+
recovery-aware ingress
```

```text
V3.2 pre-hardening
Startup Barrier protege Action execution

V3.2 FINAL
Startup Barrier
+
IngressCoordinator
protegem também entradas concorrentes
```

```text
V3.2 pre-hardening
confirmedAt representa confirmação

V3.2 FINAL
confirmationFingerprint
+
normalizedPayloadHash
+
payload immutability
```

```text
V3.2 pre-hardening
Memory Pressure genérico

V3.2 FINAL
Android 17 Memory Limiter
+
ApplicationExitInfo
+
restart-loop protection
```

```text
V3.2 pre-hardening
24/7 como disponibilidade funcional

V3.2 FINAL
contrato 24/7 explicitamente limitado
por Force Stop e system restrictions
```

```text
V3.2 pre-hardening
Skill contract com authorization/recovery

V3.2 FINAL
SkillDescriptor
com side effects
+
idempotency
+
capabilities
+
confirmation policy
```

Essas alterações fazem parte da V3.2 FINAL e **não constituem V4**.

A renomeação para O.R.I.O.N. também permanece uma correção de identidade, não uma alteração arquitetural.

---

# 139. CONCLUSÃO ARQUITETURAL

A V3.2 FINAL CONSOLIDADA é a versão final da arquitetura antes da implementação.

O O.R.I.O.N. — **Operational Reasoning & Intelligent Orchestration Network** — é definido como:

> Um agente pessoal Android local-first, persistente, orientado a comandos e eventos, resiliente a reboot e process death dentro do lifecycle permitido pelo sistema, com estado transacional, memória estruturada com proveniência, execução idempotente e recuperável, scheduler reconciliável, ingressos protegidos por Startup Barrier, confirmação vinculada ao payload exato, política independente do LLM, Skills declarativas, voz conectada ao Core por IPC versionado, Resource Governor consciente de limites modernos de memória e um Cortex isolado e descartável acionado somente quando necessário.

O modelo de linguagem não é o O.R.I.O.N.

O banco não é o O.R.I.O.N.

O serviço de voz não é o O.R.I.O.N.

O processo principal não é o O.R.I.O.N.

O O.R.I.O.N. é:

```text
o estado persistente
+
os invariantes
+
as regras
+
a memória
+
o contexto
+
a política
+
as Skills
+
a percepção
+
o raciocínio local
+
as outboxes
+
os ingressos
+
a reconciliação
+
a recuperação
+
a coordenação entre subsistemas.
```

```text
O.R.I.O.N. não reivindica capacidade
de sobreviver ativamente a Force Stop.

O.R.I.O.N. reivindica capacidade
de reconstruir seu estado corretamente
quando o Android voltar a permitir sua execução.
```

**Arquitetura: 100% fechada para implementação.**

---

# 140. NOTAS DE CONFORMIDADE ANDROID — SETEMBRO/2026

Premissas normativas da V3.2 FINAL:

```text
Binder possui buffer de transação
limitado e compartilhado;

payload inline interno permanece
limitado conservadoramente a 256 KiB;

alarmes exatos exigem tratamento
de capability/special access
em versões modernas;

WorkManager é apropriado
para trabalho persistente e adiável,
não precisão de relógio;

apps modernos possuem restrições
para iniciar FGS a partir do background;

microphone FGS exige
tipo/permissões adequados;

ROLE_ASSISTANT depende
de consentimento do usuário;

VoiceInteractionService exige
o contrato
android.service.voice.VoiceInteractionService
e proteção BIND_VOICE_INTERACTION;

componentes internos permanecem
não exportados por padrão;

Android 15+ reforça
package stopped/Force Stop
e cancela PendingIntents
do app ao entrar nesse estado;

O.R.I.O.N. não pode auto-recuperar
enquanto pacote permanecer stopped;

background restriction pode bloquear
jobs,
alarmes
e determinados broadcasts/FGS;

Android 17 endurece
interações de áudio em background;

Android 17 introduz limites
de memória de processo
que exigem qualificação específica;

ApplicationExitInfo é entrada
de diagnóstico/recovery
para encerramentos anteriores;

Memory Limiter deve degradar
ResourceGovernor
antes de nova inferência pesada;

Google Play exige target API moderno,
atualmente API 36 ou superior
para novos apps/updates
desde 31/08/2026;

Samsung Galaxy S21
é performance qualification target,
não substituto de testes API 37.
```

Essas regras são tratadas por:

```text
capabilities
+
degradação funcional
+
recovery
+
compatibility testing.
```

A arquitetura não depende de comportamento privilegiado não documentado ou específico de OEM.

---

# 141. DECISÃO FINAL

```text
NÃO CRIAR V4
PARA COMEÇAR A PROGRAMAR.
```

Próximo passo:

```text
FASE 0
↓
FASE 1
↓
implementação incremental
orientada pelos invariantes da V3.2.
```

A Fase 0 valida explicitamente:

```text
hardware performance
+
Android API compatibility
+
Memory Limiter/resource behavior.
```

Qualquer alteração estrutural futura deve ser registrada como ADR e somente aceita se:

```text
teste;

limitação real do Android;

ou

requisito novo
```

demonstrar necessidade de revisar um invariante.

A identidade O.R.I.O.N. será utilizada desde o primeiro commit, evitando aliases legados desnecessários.

---

# 142. ARCHITECTURE DECISION RECORDS — REGRA

As correções incorporadas nesta consolidação pertencem ao fechamento da **V3.2 FINAL**.

Não exigem ADR individual porque corrigem lacunas identificadas antes do início da implementação.

A partir do primeiro commit de engenharia:

```text
mudança estrutural nova
→ ADR obrigatório.
```

Precedência normativa de ADR:

```text
um ADR aceito pode superseder
a baseline apenas nas decisões/cláusulas
que declarar explicitamente alteradas;

todo o restante da Arquitetura V3.2
e do Plano Mestre permanece normativo.
```

ADR não funciona como autorização genérica para ignorar a baseline.

Formato:

```text
ADR-0001
ADR-0002
...
```

Cada ADR contém:

```text
contexto;

decisão;

alternativas;

consequências;

invariantes afetados;

migration plan
se aplicável.
```

Não usar ADR para:

```text
ajuste trivial;

refactoring sem mudança arquitetural;

calibração de benchmark
dentro dos contratos existentes.
```

Isso encerra a fase de arquitetura aberta e inicia a fase de engenharia controlada.

---

# CHECKLIST FINAL DE IMPLEMENTAÇÃO ARQUITETURAL

A V3.2 FINAL somente poderá ser considerada implementada de acordo com a arquitetura quando todas as respostas abaixo forem **SIM**:

```text
[ ] :voice e :voice_session conseguem falar com main
    por IPC v1 sem tocar Room.

[ ] main morto pode ser reconstruído
    por bind quando Android permite iniciar o pacote.

[ ] request de voz recebida durante recovery
    não executa side effect prematuro.

[ ] VoiceInteractionService está protegido
    por BIND_VOICE_INTERACTION.

[ ] OrionCoreGatewayService não é exportado.

[ ] todo ingresso mutável
    passa pelo IngressCoordinator.

[ ] Startup Barrier também protege
    ingressos concorrentes.

[ ] confirmação possui fingerprint/hash
    do payload exato.

[ ] payload confirmado
    não pode mudar silenciosamente.

[ ] SkillDescriptor existe para toda Skill.

[ ] ResourceGovernor usa sinais
    de memória e exits anteriores.

[ ] Memory Limiter kill
    não cria restart loop.

[ ] Force Stop é tratado
    como suspensão não contornável.

[ ] background restriction aparece
    em Health/Diagnostics.

[ ] reminders são reconciliados
    após retorno permitido do pacote.

[ ] Samsung Galaxy S21 é usado
    para benchmark físico.

[ ] API 37 é testada separadamente
    para compatibilidade quando aplicável.

[ ] bootstrap multiprocesso impede
    Room/DataStore/repositories do Core
    em :ai/:voice/:voice_session.

[ ] VoiceInteractionService permanece leve
    e a sessão pesada usa :voice_session.

[ ] payloads duráveis de ingress/outbox
    obedecem minimização/sensitivity/encryption.

[ ] execution lease monotônica
    é vinculada ao BootSessionId.

[ ] API 35+ usa ApplicationStartInfo.wasForceStopped()
    para reconciliação pós-Force-Stop.
```

---

# DECISÃO DE FECHAMENTO

```text
O.R.I.O.N. — V3.2 FINAL CONSOLIDADA

ARQUITETURA FECHADA

HARDENED

IMPLEMENTATION-READY

SEM NECESSIDADE DE V4
ANTES DO CÓDIGO
```

A partir deste ponto, o trabalho correto é:

```text
engenharia
+
benchmark
+
implementação incremental
+
testes
+
validação dos invariantes
+
ADRs quando realmente necessários.
```

Não é necessário novo redesenho arquitetural antes do início do código.

