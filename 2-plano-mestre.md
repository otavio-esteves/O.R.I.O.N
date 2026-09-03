# O.R.I.O.N.

## Operational Reasoning & Intelligent Orchestration Network

# PLANO MESTRE DE IMPLEMENTAÇÃO — V1.2 FINAL

**Base normativa:** Arquitetura Android Nativa V3.2 FINAL CONSOLIDADA
**Status da arquitetura:** Fechada / Hardened / Implementation-Ready
**Linguagem:** Kotlin
**UI:** Jetpack Compose
**IA local:** llama.cpp + GGUF
**Persistência:** Room + SQLite + FTS + DataStore
**Target físico inicial:** Samsung Galaxy S21 — 8 GB RAM
**Princípio:** Local-first
**Objetivo deste documento:** transformar a arquitetura fechada em uma sequência controlada, testável e incremental de implementação.

**Status do plano:** FINAL / ENGINEERING BASELINE / Implementation-Ready

**Revisão V1.2:** fechamento da baseline de engenharia com semântica explícita de processos Android, abstração de tempo, política de idempotência, envelope criptográfico versionado, orçamento agregado de memória por aplicativo, correção da ordem inicial de PRs, migration real do Model Registry e hardening de release para R8/JNI/AIDL/symbolication. Mantém todos os hardenings da V1.1 para toolchain, compatibilidade nativa, limites de memória do Android 17/API 37, ownership de execução, retry/dead-letter, retenção, upgrade, segurança criptográfica, importação de modelos e qualificação antecipada de voz. O hardening pré-primeiro-commit acrescenta bootstrap multiprocesso explícito, separação `:voice`/`:voice_session`, proteção de payloads duráveis genéricos, leases vinculadas a `BootSessionId`, detecção pós-Force-Stop por `ApplicationStartInfo.wasForceStopped()` em API 35+ e precedência normativa explícita de ADR.

---

# 1. OBJETIVO DO PLANO MESTRE

O objetivo não é implementar todo o O.R.I.O.N. de uma só vez.

O objetivo é construir o sistema em camadas nas quais cada etapa:

1. produza software executável;
2. preserve os invariantes arquiteturais;
3. tenha testes objetivos;
4. possa ser validada isoladamente;
5. não dependa prematuramente do LLM;
6. não permita que funcionalidades avançadas contaminem o Core;
7. deixe uma base estável para a etapa seguinte.

O desenvolvimento deverá seguir o princípio:

```text
CONTRATOS
↓
PERSISTÊNCIA
↓
RECOVERY
↓
FUNÇÕES DETERMINÍSTICAS
↓
SCHEDULER
↓
CORTEX
↓
ORQUESTRAÇÃO HÍBRIDA
↓
MEMÓRIA INTELIGENTE
↓
PROATIVIDADE
↓
VOZ
↓
SYSTEM ASSISTANT
↓
WAKE WORD
↓
ADAPTAÇÃO
```

A IA não é o ponto de partida.

O primeiro produto funcional será um O.R.I.O.N. sem LLM, capaz de:

```text
persistir;
recuperar;
agendar;
notificar;
executar Skills determinísticas;
sobreviver a process death;
sobreviver a reboot;
reconciliar estado;
proteger ações;
exibir seu próprio health.
```

Somente depois disso o Cortex será conectado.

---

# 2. REGRA DE EXECUÇÃO DO PROJETO

Toda fase possui cinco elementos obrigatórios:

```text
ENTRY CRITERIA
→ condição necessária para começar;

IMPLEMENTAÇÃO
→ escopo daquela fase;

TESTES
→ o que precisa ser provado;

DELIVERABLE
→ artefato concreto produzido;

EXIT GATE
→ condição obrigatória para avançar.
```

Nenhuma fase será considerada concluída porque:

```text
“parece funcionar”.
```

Será considerada concluída somente quando:

```text
compila
+
testa
+
sobrevive aos cenários definidos
+
respeita invariantes
+
possui observabilidade suficiente
+
passa pelo Exit Gate.
```

---

# 3. REGRA DE OURO DE ESCOPO

Durante a implementação:

```text
arquitetura fechada
≠
parâmetros fechados.
```

Continuam deliberadamente calibráveis:

```text
modelo GGUF;
modelo STT;
threads;
context size;
max tokens;
keep-alive;
tokens/s mínimos;
thresholds térmicos;
thresholds energéticos;
wake word engine;
thresholds de proatividade;
thresholds de memória;
latências aceitáveis.
```

Esses parâmetros devem nascer de:

```text
benchmark
+
telemetria
+
testes físicos.
```

Não devem gerar redesenho arquitetural.

Mudança estrutural após o primeiro commit:

```text
ADR obrigatório.
```

---

# 4. LINHAS DE TRABALHO

O projeto será dividido conceitualmente em oito linhas.

## WS-A — PLATFORM / FOUNDATION

```text
Gradle;
módulos;
Compose;
build;
logging;
CI;
contratos comuns.
```

## WS-B — CORE / DATA / RECOVERY

```text
Room;
repositories;
transactions;
outboxes;
IngressCoordinator;
RecoveryCoordinator;
Startup Barrier;
Health.
```

## WS-C — DETERMINISTIC AGENT

```text
Commands;
Queries;
Skills;
Tasks;
Reminders;
Timers;
Device;
Notifications.
```

## WS-D — CORTEX

```text
:ai;
llama.cpp;
JNI;
IPC;
GGUF;
streaming;
ResourceGovernor.
```

## WS-E — INTELLIGENCE

```text
Intent Router;
Context Engine;
Memory;
Context Budget;
Cognitive Path.
```

## WS-F — SENTINEL

```text
heartbeat;
proatividade;
anti-spam;
feedback;
resource adaptation.
```

## WS-G — VOICE

```text
:voice;
STT;
VAD;
TTS;
VoiceCore IPC;
System Assistant;
wake word.
```

## WS-H — QUALITY / HARDENING

```text
process death;
reboot;
Doze;
memory pressure;
security;
soak;
compatibility;
release gates.
```

WS-H é transversal e começa na Fase 0.

---

# 5. DEPENDÊNCIA CRÍTICA

O caminho crítico será:

```text
FASE 0
  ↓
FASE 1
  ↓
FASE 2
  ↓
FASE 3
  ↓
FASE 4
  ↓
[MVP CORE]
  ↓
FASE 5
  ↓
FASE 6
  ↓
[MVP COGNITIVE]
  ↓
FASE 7
  ↓
FASE 8
  ↓
[V1 INTELLIGENCE]
  ↓
FASE 9
  ↓
[V1 VOICE]
  ↓
FASE 10
  ↓
[SYSTEM ASSISTANT]
  ↓
FASE 11
  ↓
[WAKE WORD SE APROVADA]
  ↓
FASE 12
```

Regra importante:

```text
Cortex não entra antes de:
ResourceGovernor mínimo
+
Recovery funcional
+
Core transacional.
```

Voz avançada não entra antes de:

```text
VoiceCore IPC
+
IngressCoordinator
+
Startup Barrier
+
Core recovery.
```

---

# 6. FASE 0 — DEVICE + API QUALIFICATION

## Objetivo

Descobrir os limites reais do hardware e do Android antes de fixar parâmetros operacionais.

Não construir produto nessa fase.

Construir:

```text
evidência.
```

---

## 6.1. Toolchain Baseline

Antes de qualquer benchmark ou implementação estrutural, fixar e versionar:

```text
minSdk;

targetSdk;

compileSdk;

JDK;

Kotlin;

Android Gradle Plugin;

Gradle;

NDK;

CMake;

ABIs suportadas;

versões e SHAs de dependências nativas relevantes.
```

Criar:

```text
ToolchainProfile
```

Regra:

```text
nenhum benchmark é comparável
sem toolchain identificada.
```

Mudança relevante de toolchain após baseline:

```text
registrar no relatório de compatibilidade
+
reexecutar benchmarks afetados.
```

---

## 6.2. Entregáveis

Criar:

```text
/benchmark
/docs/benchmark
/docs/compatibility
```

Definir:

```text
BenchmarkProfile

CompatibilityProfile
```

Registrar por execução:

```text
device;
SoC;
RAM;
Android build;
API;
app build;
modelo;
hash do modelo;
quantização;
threads;
context;
tokens;
load time;
first token latency;
tokens/s;
RSS baseline;
peak RSS;
RSS after unload;
battery;
thermal;
exit reason;
resultado.
```

---

## 6.3. Benchmark de LLM

Testar inicialmente modelos:

```text
~1B
~1.5B
~2B
```

preferencialmente:

```text
Q4
```

Para cada modelo:

```text
cold load;

context 2048;

context 4096;

1 thread;

2 threads;

4 threads;

configurações tecnicamente razoáveis;

curta inferência;

inferência prolongada;

unload;

reload;

cancelamento;

process kill.
```

Não selecionar modelo definitivo apenas por tokens/s.

Avaliar também:

```text
RAM;
thermal;
stabilidade;
tempo de load;
tempo de unload;
consumo.
```

---

## 6.4. STT

Comparar candidatos definidos pela arquitetura:

```text
whisper.cpp

sherpa-onnx
```

Medir:

```text
latência;

qualidade em português;

RAM;

CPU;

thermal;

tempo de inicialização.
```

---

## 6.5. Android Compatibility

Manter duas matrizes separadas:

### Performance

```text
Galaxy S21
```

### API compatibility

```text
latest release API suportada;

API 37/Android 17 quando aplicável.
```

Testar:

```text
background restrictions;

foreground service;

microphone;

exact alarms;

VoiceInteractionService;

ApplicationExitInfo;

Android 17/API 37 app memory limits;

memory pressure e comportamento de processos isolados;

compatibilidade com páginas de memória de 16 KiB para código nativo.
```

Para binários nativos usados por:

```text
llama.cpp;
STT;
KWS;
outras bibliotecas JNI/NDK,
```

validar desde a F0:

```text
16 KiB page-size compatibility
+
packaging/alignment
+
load em dispositivo/emulador compatível.
```

Criar gate explícito:

```text
NATIVE_COMPATIBILITY_GATE
```

---

## 6.6. Voice Platform Qualification Spike

Não implementar a arquitetura de voz nesta fase.

Executar apenas um spike descartável para descobrir cedo os limites reais de plataforma em:

```text
push-to-talk;

screen off;

background;

foreground service com microfone;

ROLE_ASSISTANT;

VoiceInteractionService;

process recreation;

Samsung One UI no device físico alvo.
```

Objetivo:

```text
provar quais caminhos são tecnicamente permitidos
antes da F9/F10/F11.
```

O spike:

```text
não define a implementação final;

não antecipa :voice;

não autoriza wake word;

produz apenas CompatibilityProfile + evidência.
```

---

## 6.7. Exit Gate F0

A Fase 0 termina quando existir:

```text
[ ] pelo menos um modelo local viável;

[ ] pelo menos um STT candidato viável;

[ ] baseline de RAM;

[ ] baseline térmico;

[ ] baseline de battery impact;

[ ] configuração inicial de context;

[ ] configuração inicial de threads;

[ ] estratégia inicial de unload;

[ ] dados de process exit;

[ ] CompatibilityProfile inicial;

[ ] ToolchainProfile versionado;

[ ] compatibilidade nativa 16 KiB validada ou risco formalmente registrado;

[ ] limites de memória Android 17/API 37 qualificados;

[ ] Voice Platform Qualification Spike concluído;

[ ] relatório comparativo reproduzível.
```

Resultado:

```text
F0_PASS
```

Nenhuma decisão estrutural nasce daqui.

Somente valores de calibração.

---

# 7. FASE 1 — FOUNDATION

## Objetivo

Criar o esqueleto definitivo do produto.

Ao final da Fase 1 o aplicativo deverá:

```text
instalar;
abrir;
navegar;
abrir banco;
ler DataStore;
exibir Health básico;
possuir contratos estáveis;
executar testes.
```

---

# 7.1. Projeto Gradle

Criar os módulos previstos na arquitetura.

Estrutura inicial:

```text
app

core:
  common
  model
  commands
  queries
  events
  ipc
  orchestrator
  policy
  actions
  resources
  time
  recovery
  health
  security
  observability
  ingress
  exitinfo
  skills/metadata

data:
  database
  datastore
  repository
  tasks
  reminders
  actions
  events
  memory

ai:
  api

voice:
  api
  ipc

skills:
  api

scheduler

feature:
  home
  diagnostics
  settings
```

Módulos não implementados podem nascer vazios, mas as fronteiras devem existir desde o início.

---

# 7.2. Dependency Rules

Adicionar testes ou validações de arquitetura para impedir:

```text
ai → database;

voice → database;

policy → ai implementation;

repository → UI;

feature → data implementation direta
quando use case existir.
```

Ciclos Gradle:

```text
PROIBIDOS.
```

---

# 7.3. Contratos fundamentais

Criar os tipos:

```text
OrionEvent

OrionIntent

Command

Query

CoreReadiness

OrionHealth

AgentState

ErrorCategory

SkillDescriptor

Capability

AuthorizationLevel

RecoveryStrategy

SideEffectClass

IdempotencyMode

IdempotencyKeyPolicy

DurablePayloadPolicy

OrionClock

BootSessionId
```

---

# 7.3.1. Time Semantics

Criar abstração obrigatória de tempo desde a Foundation.

Nenhuma lógica de domínio deverá depender diretamente de:

```text
System.currentTimeMillis()

SystemClock.elapsedRealtime()
```

Definir:

```text
OrionClock

WallClock

MonotonicClock

BootSessionId
```

Semântica:

```text
WallClock
→ datas civis persistentes;
→ reminders;
→ confirmationExpiresAt;
→ nextAttemptAt persistido;
→ auditoria temporal.

MonotonicClock
→ durações dentro do mesmo boot;
→ timeouts;
→ medições de latência;
→ leases efêmeros quando tecnicamente apropriado.

BootSessionId
→ impede interpretar timestamp monotônico de outro boot como válido.
```

Regras:

```text
reboot invalida referências monotônicas persistidas;

mudança manual de relógio não pode quebrar timeout monotônico;

timezone/DST afetam datas civis conforme política do domínio;

testes devem usar clock injetável/fake.
```

Testes mínimos:

```text
clock rollback;

clock forward;

timezone change;

DST;

reboot/BootSessionId;

lease expiry;

confirmation expiry;

retry schedule.
```

---

# 7.4. Skeletons obrigatórios

Criar interfaces funcionais mínimas de:

```text
RecoveryCoordinator

IngressCoordinator

ResourceGovernor

CapabilityRegistry

ApplicationExitMonitor

CommandDispatcher

QueryGateway

DomainEventPublisher

ActionEngine
```

Ainda que inicialmente retornem estados simplificados.

## 7.4.1. Multiprocess Bootstrap

Criar desde Foundation:

```text
OrionProcessRole
OrionProcessBootstrapper
```

Roles iniciais:

```text
MAIN
AI
VOICE_CONTROL
VOICE_SESSION
```

O bootstrap deve executar antes da criação de infraestrutura de ownership específico.

Regras:

```text
MAIN
→ Room/DataStore/repositories/scheduler/recovery/ingress;

AI
→ Cortex/JNI/IPC mínimo;

VOICE_CONTROL
→ control plane de voz/IPC mínimo;

VOICE_SESSION
→ pipeline de sessão/áudio sob demanda.
```

Auditar inicializadores automáticos de bibliotecas e DI para garantir:

```text
:ai/:voice/:voice_session
↛ Room
↛ repositories
↛ scheduler/recovery do Core.
```

Adicionar teste/instrumentação de bootstrap por processo.

---

# 7.5. IPC contract

Criar desde já:

```text
protocolMajor = 1
protocolMinor = 0
```

e:

```text
ProtocolInfo
IPC capability negotiation
schemaVersion
MAX_INLINE_IPC_PAYLOAD
```

O contrato será compartilhado futuramente por:

```text
main ↔ :ai

:voice ↔ main
```

---

# 7.6. UI inicial

Criar:

```text
Home
Diagnostics
Settings
```

Home inicialmente:

```text
O.R.I.O.N.

Core: STARTING / READY
Database: status
Cortex: unavailable
Voice: unavailable
```

---

# 7.7. Observabilidade mínima

Criar logging estruturado com:

```text
correlationId;

component;

event;

severity;

timestamp.
```

Sem conteúdo sensível.

---

# 7.8. Testes da Fase 1

```text
[ ] build debug;

[ ] build release;

[ ] unit test baseline;

[ ] module dependency checks;

[ ] Room opens;

[ ] DataStore opens;

[ ] Health aggregation básica;

[ ] ProtocolInfo test;

[ ] schemaVersion test;

[ ] OrionClock/BootSessionId tests;

[ ] OrionProcessBootstrapper por processo;

[ ] processos auxiliares não inicializam Room/repositories/scheduler do Core;

[ ] app recreation;

[ ] process recreation básico.
```

---

# 7.9. Exit Gate F1

```text
App executável
+
arquitetura modular presente
+
contratos centrais compilando
+
CI/testes básicos verdes.
```

Resultado:

```text
FOUNDATION_READY
```

---

# 8. FASE 2 — DATA + TRANSACTION CORE

Esta é a fase mais importante do Core.

Antes de IA, voz ou proatividade, o estado precisa ser confiável.

---

# 8.1. Schema V1

Implementar tabelas para:

```text
TaskEntity

ReminderEntity

ActionRequestEntity

DomainEventOutboxEntity

IngressEnvelopeEntity

MemoryEntity

MemoryClaimEntity

AuditEntity

TechnicalEventEntity
```

---

# 8.2. Optimistic concurrency

Toda entidade crítica recebe:

```text
version
```

Atualizações:

```text
UPDATE ...
WHERE id = ?
AND version = ?
```

Resultado zero:

```text
CONFLICT
```

Criar testes concorrentes desde esta fase.

---

# 8.3. Domain Event Outbox

Aplicar `DurablePayloadPolicy` aos eventos persistidos:

```text
minimização por padrão;

preferir IDs/referências;

schemaVersion;

payloadSensitivity;

SENSITIVE/SECRET
→ envelope criptográfico versionado;

SECRET
→ nunca em log/diagnóstico e nunca em conteúdo bruto
   quando uma referência for suficiente.
```

Implementar atomicamente:

```text
state mutation
+
outbox insertion
```

Exemplo:

```text
BEGIN

Task.insert()

DomainEventOutbox.insert(TaskCreated)

COMMIT
```

Dispatcher:

```text
AT-LEAST-ONCE
```

Consumer:

```text
IDEMPOTENT.
```

Implementar política operacional explícita para outboxes:

```text
attemptCount;

nextAttemptAt;

backoff exponencial com jitter;

maxAttempts quando aplicável;

poison-event detection;

DEAD_LETTER / MANUAL_REVIEW quando retry automático deixar de ser seguro.
```

Regra:

```text
AT-LEAST-ONCE
≠
retry infinito.
```

---

# 8.4. Action Outbox

Implementar integralmente a máquina:

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

Adicionar validação de transições.

Nenhum código deve poder fazer:

```text
SUCCEEDED → RUNNING
```

ou outras transições ilegais.

Implementar ownership explícito de execução para estados `RUNNING`.

Persistir, conforme a estratégia final:

```text
executionId;

claimedBy;

leaseBootSessionId;

leaseStartedElapsedRealtime;

leaseExpiresElapsedRealtime;

leaseWallStartedAt; // auditoria/diagnóstico

attempt;

heartbeat/renewal quando necessário.
```

Semântica:

```text
lease monotônica só é interpretável
quando leaseBootSessionId == BootSessionId atual;

reboot
→ lease anterior abandonada/inválida;

lease inválida
≠ prova de side effect não executado.
```

Recovery deve conseguir distinguir:

```text
RUNNING com executor válido

vs

RUNNING abandonado por process death.
```

Um `RUNNING` abandonado nunca recebe retry cego quando o side effect puder ter ocorrido.

Aplicar:

```text
IDEMPOTENT_RETRY

ou

VERIFY_THEN_RETRY

ou

UNKNOWN

ou

MANUAL_REVIEW
```

conforme `RecoveryStrategy` e `SideEffectClass`.

---

# 8.5. Confirmation Binding

Implementar:

```text
canonical normalization;

normalizedPayloadHash;

confirmationFingerprint;

confirmedPayloadHash;

confirmationExpiresAt;

payloadLockedAt.
```

Testes obrigatórios:

```text
alterar argumento após confirmação
→ falha;

alterar target
→ falha;

alterar authorization
→ falha;

policy relevante mudou
→ revalidação/reconfirmação;

confirmação expirada
→ bloqueio.
```

---

# 8.6. Ingress durable queue

Implementar:

```text
IngressEnvelopeEntity
```

O envelope deve carregar `payloadSensitivity` e obedecer `DurablePayloadPolicy`:

```text
minimizar conteúdo;

preferir ID/referência;

SENSITIVE/SECRET
→ envelope criptográfico versionado;

payload grande temporário
→ arquivo privado referenciado + cleanup/TTL explícitos;

SECRET
→ nunca em logs/diagnostics.
```

com:

```text
QUEUED
PROCESSING
CONSUMED
REJECTED
EXPIRED
FAILED
```

Deduplicação:

```text
ingressId
+
idempotencyKey.
```

Implementar também uma política única de idempotência:

```text
IdempotencyKeyPolicy
```

Cada operação mutável deve definir explicitamente:

```text
quem gera a chave;

escopo da chave;

duração/retenção;

canonical payload associado;

constraint UNIQUE aplicável;

comportamento de replay;

comportamento de conflito.
```

Semântica mínima:

```text
mesma idempotencyKey
+
mesmo canonical payload
→ DEDUP / REPLAY SAFE

mesma idempotencyKey
+
payload canônico diferente
→ IDEMPOTENCY_CONFLICT
→ nunca substituir silenciosamente a operação original.
```

Para operações com side effect externo, a chave deve sobreviver ao process death enquanto existir risco de repetição.

---

# 8.7. Security storage

Implementar:

```text
field encryption para SENSITIVE/SECRET;

AES-GCM;

key wrapping via Android Keystore.
```

Validar:

```text
SECRET não entra em FTS;

SECRET não entra em logs.
```

Implementar envelope criptográfico versionado para cada campo cifrado:

```text
ciphertextVersion;

keyVersion;

algorithm;

nonce/IV;

ciphertext;

authenticationTag quando aplicável;

AAD schema/version.
```

Regras obrigatórias:

```text
nonce/IV nunca reutilizado com a mesma chave;

AAD deve vincular o ciphertext ao contexto lógico apropriado
(ex.: entityId + fieldName + schemaVersion quando aplicável);

falha de autenticação
→ CORRUPT / UNREADABLE
→ nunca fallback para plaintext.
```

Implementar também ciclo de vida criptográfico:

```text
key versioning;

rotação de chave quando necessária;

tratamento de Keystore invalidado;

tratamento de chave indisponível;

recovery seguro de campo indecifrável;

nenhum fallback silencioso para plaintext.
```

Testes obrigatórios:

```text
key invalidation;

key rotation;

nonce uniqueness;

AAD mismatch;

decrypt/authentication failure;

recovery sem vazamento de conteúdo sensível.
```

---

# 8.8. Backup policy

Manifest/extraction rules:

```text
backup automático OFF.
```

Criar teste verificando configuração.

---

# 8.9. Retention + Pruning

Definir política explícita de retenção para dados operacionais de longa duração:

```text
AuditEntity;

TechnicalEventEntity;

DomainEventOutbox processada;

ActionRequest terminal;

IngressEnvelope terminal;

telemetria local;

artefatos temporários.
```

A política deve definir:

```text
TTL/retenção;

limite por quantidade/tamanho;

pruning incremental;

condições de preservação para auditoria/manual review;

compactação quando aplicável.
```

Regra:

```text
modo 24/7 não pode depender de crescimento ilimitado do banco ou logs.
```

---

# 8.10. Migration + Upgrade Infrastructure

Mesmo no schema inicial, criar:

```text
MigrationTestHelper

migration fixture strategy;

upgrade fixture strategy.
```

A suíte de upgrade deve provar cenários do tipo:

```text
Vn instalada
+
Task/Reminder
+
ActionRequest pendente ou UNKNOWN
+
Event Outbox
+
IngressEnvelope
+
Workers/alarms existentes
↓
instalação de Vn+1 por cima
↓
migration
↓
RecoveryCoordinator
↓
reconciliation
↓
mesmo estado lógico
+
nenhum side effect duplicado.
```

Regra:

```text
nenhuma destructive migration em produção.
```

---

# 8.11. Exit Gate F2

```text
[ ] transactions verificadas;

[ ] optimistic concurrency;

[ ] Event Outbox funcional;

[ ] Action Outbox funcional;

[ ] execution ownership/lease funcional;

[ ] RUNNING abandonado possui recovery determinístico;

[ ] retry/backoff/dead-letter funcional;

[ ] UNKNOWN funcional;

[ ] IngressQueue funcional;

[ ] IdempotencyKeyPolicy funcional;

[ ] DurablePayloadPolicy funcional para ingress/outbox;

[ ] SENSITIVE/SECRET durable payload encryption/minimization testados;

[ ] execution lease monotônica vinculada a BootSessionId;

[ ] replay seguro e IDEMPOTENCY_CONFLICT testados;

[ ] confirmation fingerprint funcional;

[ ] payload locking funcional;

[ ] field encryption funcional;

[ ] envelope criptográfico versionado;

[ ] nonce/AAD invariants testados;

[ ] lifecycle de chaves testado;

[ ] backup disabled;

[ ] retention/pruning definidos e testados;

[ ] migration + upgrade test infrastructure;

[ ] process death de transação testado.
```

Resultado:

```text
TRANSACTION_CORE_READY
```

---

# 9. FASE 3 — DETERMINISTIC SKILLS

## Objetivo

Fazer o O.R.I.O.N. tornar-se útil sem IA.

Implementar primeiro:

```text
TaskSkill

ReminderSkill

TimeSkill

TimerSkill

BatterySkill

DeviceStatusSkill

NotificationSkill

MemorySkill básico
```

---

# 9.1. Regra de implementação de Skill

Nenhuma Skill nasce sem:

```text
SkillDescriptor.
```

Para cada Skill definir explicitamente:

```text
authorizationLevel;

recoveryStrategy;

sideEffectClass;

idempotencyMode;

requiredCapabilities;

requiredPermissions;

requiresUserPresence;

confirmationPolicy;

supportsDryRun;

argumentSchemaVersion.
```

---

# 9.2. Tasks

Implementar:

```text
CreateTask

GetTasks

CompleteTask

CancelTask
```

Eventos:

```text
TaskCreated

TaskCompleted

TaskCancelled
```

---

# 9.3. Reminders

Implementar inicialmente o domínio e persistência.

Scheduling completo ficará na Fase 4.

Comandos:

```text
CreateReminder

CancelReminder

SnoozeReminder
```

---

# 9.4. Timer

Criar:

```text
CreateTimer

CancelTimer

GetTimerStatus
```

---

# 9.5. Device information

Apenas informações permitidas e deterministicamente obtidas:

```text
battery;

charging;

time;

basic device status.
```

---

# 9.6. UI

Adicionar telas:

```text
Tasks

Reminders

Activity
```

O usuário já deverá poder controlar todo o Core sem chat.

---

# 9.7. Exit Gate F3

O app precisa funcionar como um assistente determinístico básico:

```text
[ ] criar tarefa;

[ ] listar tarefa;

[ ] concluir tarefa;

[ ] criar reminder;

[ ] criar timer;

[ ] consultar bateria;

[ ] gerar domain events;

[ ] usar SkillDescriptor;

[ ] não executar mutação fora do pipeline;

[ ] process death sem perda de estado.
```

Resultado:

```text
DETERMINISTIC_AGENT_READY
```

---

# 10. FASE 4 — SCHEDULER + RECOVERY

Esta fase transforma persistência em resiliência real.

---

# 10.1. Scheduler

Implementar:

```text
AlarmManager

WorkManager

ReminderReconciler

ExactAlarmCapability
```

---

# 10.2. Exact Alarm

Fluxo:

```text
reminder solicitado
↓
ExactAlarmCapability
↓
exact disponível?
├─ sim → exact
└─ não → APPROXIMATE + fallback
```

Nunca falhar silenciosamente.

Definir e documentar estratégia de permissão/distribuição:

```text
SCHEDULE_EXACT_ALARM
vs
USE_EXACT_ALARM
```

considerando:

```text
elegibilidade real do produto;

política da loja;

capability em runtime;

fluxo de concessão/revogação;

fallback APPROXIMATE.
```

A regra de domínio continua sendo:

```text
Reminder no banco é fonte da verdade.
AlarmManager é mecanismo de entrega, não autoridade de estado.
```

---

# 10.3. Recurrence

Implementar:

```text
LocalDateTime
+
ZoneId
+
RecurrenceRule.
```

A próxima ocorrência é calculada individualmente.

---

# 10.4. DST

Implementar e testar:

```text
GAP

OVERLAP.
```

Registrar decisão realizada.

---

# 10.5. Boot

Implementar receivers necessários.

Direct Boot:

```text
LOCKED_BOOT_COMPLETED
→ não abre banco pessoal
→ não inicia Cortex
→ não inicia microfone.
```

Após storage disponível:

```text
RecoveryCoordinator.
```

---

# 10.6. RecoveryCoordinator real

Implementar exatamente a ordem:

```text
1 logging

2 DB

3 migrations

4 invariants

5 DataStore

6 ApplicationExitInfo

7 IngressCoordinator GATED

8 Event Outbox

9 Action Outbox

10 reminders

11 workers

12 capabilities

13 caches

14 ResourceGovernor

15 Health

16 CORE_READY / CORE_DEGRADED

17 barrier release

18 ingress drain
```

---

# 10.7. Startup Barrier

Criar testes explícitos:

```text
recovery em andamento
+
ActionRequest recebida
→ NÃO EXECUTA.
```

Ingress mutável:

```text
persistido
+
drenado depois.
```

---

# 10.8. ApplicationExitInfo

Classificar:

```text
normal process death;

memory pressure;

Android 17/API 37 memory-limit termination/signal quando aplicável;

native failure;

user/system kill;

Force Stop por sinal de startup em API 35+;

exit reasons para diagnóstico complementar.
```

Em API 35+, usar:

```text
ApplicationStartInfo.wasForceStopped()
```

como sinal primário da primeira inicialização após Force Stop.

Não inferir Force Stop apenas de:

```text
ApplicationExitInfo.REASON_USER_REQUESTED
ou outro exit reason isolado.
```

---

# 10.9. Force Stop

O sistema não tentará contornar Force Stop.

Em Android 15/API 35+, a entrada no estado stopped cancela `PendingIntent`s do pacote.

Na primeira inicialização posterior permitida:

```text
ApplicationStartInfo.wasForceStopped() == true
→ registrar lastForceStopDetected
→ RecoveryCoordinator
→ ReminderReconciler
→ worker/job/callback reconciliation
→ reconstrução dos PendingIntents a partir do banco.
```

`ApplicationExitInfo` permanece diagnóstico complementar e não é usado sozinho como prova de Force Stop.

---

# 10.10. Diagnostics

A tela já deverá mostrar:

```text
CoreReadiness;

last recovery;

last exit;

outbox counts;

ingress count;

reminders;

workers;

exact alarm;

notifications;

background restriction;

API memory-limit status quando observável;

native compatibility status.
```

---

# 10.11. GATE PRINCIPAL — MVP CORE

Esta é a primeira grande linha de chegada.

O MVP Core está aprovado quando:

```text
[ ] tarefas persistem;

[ ] reminders persistem;

[ ] timers funcionam;

[ ] reboot recupera;

[ ] process death recupera;

[ ] Event Outbox recupera;

[ ] Action Outbox recupera;

[ ] UNKNOWN não recebe retry cego;

[ ] IngressQueue recupera;

[ ] Startup Barrier funciona;

[ ] exact alarm possui fallback;

[ ] timezone/time change reconciliam;

[ ] app upgrade Vn→Vn+1 preserva estado e não duplica ações;

[ ] Health é observável;

[ ] app funciona integralmente sem IA.
```

Tag recomendada:

```text
v0.1.0-core
```

---

# 11. FASE 5 — CORTEX RUNTIME

Somente agora entra o LLM.

---

# 11.1. Processo `:ai`

Criar processo dedicado privado do aplicativo:

```text
android:process=":ai"
```

Semântica obrigatória:

```text
:ai é um processo separado pertencente ao mesmo aplicativo/UID conforme a configuração normal de android:process.

:ai NÃO significa android:isolatedProcess="true".

android:isolatedProcess="true" somente poderá ser adotado por ADR específico,
com prova de que as restrições adicionais de identidade/permissões/acesso
não quebram o Model Registry, IPC, arquivos privados ou operação prevista.
```

Sem:

```text
Room;
Repositories;
Skills Android.
```

---

# 11.2. CortexService

Implementar:

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

# 11.3. IPC

Implementar:

```text
AIDL/Parcelable estável;

protocol negotiation;

schemaVersion;

requestId;

sequence;

terminality;

Binder death;

rebind.
```

---

# 11.4. Payload Guard

Inline:

```text
<= 256 KiB.
```

Payload grande:

```text
ParcelFileDescriptor

ou

SharedMemory após validação.
```

---

# 11.5. llama.cpp

Criar wrapper JNI mínimo.

Ownership:

```text
C++ RAII.
```

Main nunca recebe raw native pointer.

---

# 11.6. Model Registry

Criar `ModelRegistryEntity` nesta fase por migration versionada do schema.

Regra:

```text
o schema V1 não antecipa tabelas do Cortex;

a introdução do Model Registry deve exercitar a infraestrutura real de migration/upgrade;

nenhuma destructive migration.
```

Fluxo:

```text
IMPORT
↓
COPY PRIVATE
↓
HASH
↓
VALIDATE
↓
REGISTER
↓
READY
```

Falha:

```text
INVALID / QUARANTINED.
```

Hardening obrigatório de importação:

```text
pré-checagem de espaço em disco;

cópia para arquivo temporário privado;

fsync/flush quando aplicável;

verificação de tamanho;

hash completo;

validação do formato;

rename/commit atômico para READY;

limpeza de temporários órfãos;

recovery após import interrompido;

rejeição de arquivo truncado/corrompido.
```

Regra:

```text
nenhum modelo parcialmente importado pode aparecer como READY.
```

---

# 11.7. Streaming

Implementar:

```text
requestId

sequence

textDelta

isFinal

finishReason.
```

Deduplicar callbacks.

---

# 11.8. Cancelamento

Testar:

```text
cancel before start;

cancel loading;

cancel decoding;

cancel after terminal.
```

---

# 11.9. ResourceGovernor

Expandir para:

```text
battery;

thermal;

memory;

headroom;

per-process memory budget;

app aggregate memory budget;

recent exits;

Android 17/API 37 app memory limits;

process memory pressure signals;

model tier;

context;

threads;

keep-alive.
```

Orçamento de memória deve considerar simultaneamente:

```text
main
+
:ai
+
:voice
+
memória nativa/JNI
+
outros componentes do mesmo aplicativo quando observáveis.
```

Regra:

```text
PER_PROCESS_BUDGET
+
APP_AGGREGATE_BUDGET
```

O fato de `:ai` estar abaixo do seu limite interno não autoriza inferência se o headroom agregado do aplicativo estiver inseguro.

Detecção de Memory Limiter na API alvo deve ser tolerante à variante de plataforma:

```text
ApplicationExitInfo.REASON_MEMORY_LIMITER quando exposto/retornado pela plataforma

ou

REASON_OTHER + descrição contendo sinal de MemoryLimiter
quando essa for a forma reportada pela build/API em teste.
```

A implementação deve qualificar o comportamento real no `CompatibilityProfile`, e não assumir uma única representação do motivo de saída.

---

# 11.10. Restart-loop protection

Cenário:

```text
:ai morre por memória
↓
ApplicationExitInfo
↓
ResourceGovernor degrada
↓
não relança imediatamente
↓
modelo/context/threads reduzidos.
```

Testar múltiplas mortes consecutivas.

---

# 11.11. Exit Gate F5

```text
[ ] modelo importa;

[ ] modelo valida;

[ ] import interrompido não produz READY;

[ ] temporários órfãos são reconciliados;

[ ] falta de espaço/corrupção são tratadas;

[ ] modelo carrega;

[ ] inferência funciona;

[ ] streaming funciona;

[ ] cancel funciona;

[ ] timeout funciona;

[ ] unload funciona;

[ ] :ai pode morrer sem derrubar Core;

[ ] main detecta Binder death;

[ ] rebind funciona;

[ ] payload grande não causa TransactionTooLarge;

[ ] Memory Limiter não causa restart loop.
```

Resultado:

```text
CORTEX_RUNTIME_READY
```

---

# 12. FASE 6 — HYBRID ORCHESTRATOR

Agora o O.R.I.O.N. deixa de ser um executor de comandos estruturados e passa a entender linguagem natural.

---

# 12.1. Fast Path

Primeiro implementar explicitamente as rotas determinísticas.

Exemplo:

```text
"minhas tarefas"
→ GetTasks

"bateria"
→ BatterySkill
```

Sem LLM quando desnecessário.

---

# 12.2. Intent Router

Produzir:

```text
OrionIntent
```

com:

```text
intentType;

confidence;

arguments;

source;

requiresCortex;

correlationId.
```

---

# 12.3. Cognitive Path

Quando necessário:

```text
request
↓
Context Builder
↓
ResourceGovernor
↓
Cortex
↓
structured output
↓
validator
↓
policy
↓
Skill.
```

---

# 12.4. Structured Tool Calling

A saída do Cortex precisa ser estruturada.

Nunca:

```text
texto gerado
→ execução.
```

Sempre:

```text
JSON/DTO
→ parser
→ schema
→ normalization
→ semantics
→ SkillDescriptor
→ Policy.
```

---

# 12.5. Context Budget

Implementar prioridades:

```text
1 system/policy
2 request
3 skill-required
4 safety
5 memory
6 conversation
7 secondary context
```

---

# 12.6. Chat

Adicionar:

```text
feature/chat
```

Primeira versão:

```text
text input;

stream output;

cancel;

retry de inferência segura;

indicator Cortex state.
```

---

# 12.7. GATE — MVP COGNITIVE

```text
[ ] Fast Path funciona sem Cortex;

[ ] linguagem natural simples roteada;

[ ] Cortex usado apenas quando necessário;

[ ] saída estruturada validada;

[ ] Policy não é controlada pelo LLM;

[ ] tool call inválida não executa;

[ ] Cortex morto degrada sem derrubar Core;

[ ] chat local funcional.
```

Tag sugerida:

```text
v0.2.0-cognitive
```

---

# 13. FASE 7 — INTELLIGENT MEMORY

---

# 13.1. FTS

Implementar busca apenas conforme sensitivity policy.

```text
PUBLIC/PERSONAL
→ elegível.

SENSITIVE
→ não usar conteúdo bruto.

SECRET
→ nunca.
```

---

# 13.2. Provenance

Toda memória registra:

```text
origin;

confidence;

userConfirmed;

inferred;

sensitivity.
```

---

# 13.3. Claims

Usar `MemoryClaimEntity` quando uma memória possuir múltiplos fatos independentes.

---

# 13.4. Supersession

Conflito:

```text
new memory
↓
supersedesId
↓
old = SUPERSEDED.
```

Transacionalmente.

---

# 13.5. Memory ranking

Implementar ranking inicial com:

```text
text score

recency

importance

confidence

relevance

status penalties.
```

Não é necessário embeddings no MVP.

---

# 13.6. Conversation memory

Implementar:

```text
recent window
+
session summary
+
relevant memories.
```

Resumo produzido pelo LLM não cria fatos confirmados.

---

# 13.7. User Memory Controls

Criar controles explícitos para o usuário:

```text
visualizar memória;

identificar provenance/status;

corrigir memória;

confirmar ou rejeitar claim inferida;

apagar memória;

solicitar esquecimento;

limpar conjunto selecionado quando permitido.
```

A exclusão deve respeitar:

```text
integridade referencial;

supersession;

auditoria mínima não sensível quando necessária;

remoção de índices derivados.
```

Regra:

```text
memória inteligente não pode ser uma caixa-preta sem controle do usuário.
```

---

# 13.8. Exit Gate F7

```text
[ ] FTS;

[ ] provenance;

[ ] claims;

[ ] supersession;

[ ] sensitivity filters;

[ ] no SECRET FTS;

[ ] no inferred→confirmed automático;

[ ] conversation summaries;

[ ] context retrieval;

[ ] visualizar/corrigir/apagar/esquecer memória;

[ ] remoção de índices derivados após exclusão.
```

Resultado:

```text
MEMORY_ENGINE_READY
```

---

# 14. FASE 8 — SENTINEL + RESOURCE GOVERNOR COMPLETO

---

# 14.1. Heartbeat

Implementar unique work:

```text
orion_heartbeat
```

Baseline:

```text
30 min.
```

Heartbeat não promete execução exata.

---

# 14.2. Context Engine

Implementar `ContextSnapshot` com dados permitidos.

Exemplo:

```text
time;

day;

battery;

charging;

thermal;

screen;

quiet hours;

tasks due;

health.
```

Dados invasivos:

```text
opt-in.
```

---

# 14.3. Proactivity Engine

Implementar score:

```text
urgency
+
priority
+
deadline
+
importance
+
context
+
inactivity
-
fatigue
-
quiet hours
-
dismissal.
```

---

# 14.4. Anti-spam

Obrigatório:

```text
cooldown;

topic suppression;

notification budget;

dismissal penalty;

quiet hours.
```

---

# 14.5. Feedback

Persistir:

```text
shown

opened

dismissed

ignored

snoozed

completed

timeToAction.
```

---

# 14.6. Exit Gate F8

```text
[ ] heartbeat;

[ ] resource profiles;

[ ] context snapshot;

[ ] proactive notification;

[ ] cooldown;

[ ] quiet hours;

[ ] duplicate suppression;

[ ] feedback;

[ ] no endless notification loop.
```

Resultado:

```text
SENTINEL_READY
```

---

# 15. FASE 9 — VOICE PUSH-TO-TALK

A voz começa somente pelo modo mais seguro.

---

# 15.1. Processos de voz

Criar dois papéis de processo desde o início da implementação de voz:

```text
android:process=":voice"
→ VOICE_CONTROL
→ control plane leve

android:process=":voice_session"
→ VOICE_SESSION
→ pipeline pesado sob demanda
```

Semântica obrigatória:

```text
:voice e :voice_session
NÃO significam android:isolatedProcess="true".

Qualquer adoção futura de isolatedProcess exige ADR e nova qualificação de IPC,
permissões, lifecycle e acesso aos recursos necessários.
```

`VOICE_CONTROL` deve permanecer leve.

`VOICE_SESSION` concentra:

```text
record/buffers de áudio;
VAD de sessão;
STT;
TTS/session presentation;
VoiceCoreClient da sessão.
```

Nenhum dos dois carrega:

```text
Room;

Repositories;

Policy;

Cortex.
```

---

# 15.2. VoiceCore IPC v1

Implementar:

```text
VoiceCoreClient

OrionCoreGatewayService
```

Gateway:

```text
exported=false.
```

---

# 15.3. Voice request

Implementar:

```text
VoiceUtteranceRequest

VoiceCoreResponse.
```

Transcrição parcial:

```text
isFinalTranscript=false
→ nunca gera ActionRequest.
```

---

# 15.4. Audio pipeline

```text
push button
↓
:voice control plane
↓
:voice_session sob demanda
↓
record
↓
VAD
↓
STT
↓
final transcript
↓
VoiceCoreClient
↓
Core
↓
response
↓
TTS
↓
encerrar/reter sessão conforme lifecycle permitido.
```

---

# 15.5. TTS

Primeiro:

```text
Android TextToSpeech.
```

---

# 15.6. Core morto

Teste obrigatório:

```text
:voice ou :voice_session vivo
+
main morto
↓
bind OrionCoreGatewayService
↓
main inicia quando Android permitir
↓
RecoveryCoordinator
↓
request espera barrier
↓
execução segura.
```

---

# 15.7. Exit Gate F9

```text
[ ] push-to-talk;

[ ] VAD;

[ ] STT;

[ ] TTS;

[ ] VoiceCore IPC;

[ ] main-dead bind;

[ ] Core RECOVERING → request queued;

[ ] partial transcript não executa;

[ ] :voice death não derruba Core;

[ ] :voice_session death não derruba Core/control plane;

[ ] bootstrap dos processos de voz não abre Room/repositories;

[ ] Core funciona sem voz.
```

Tag sugerida:

```text
v0.3.0-voice
```

---

# 16. FASE 10 — SYSTEM ASSISTANT

Somente depois do Push-to-Talk estar estável.

---

# 16.1. ROLE_ASSISTANT

Implementar fluxo explícito de aquisição.

Perda:

```text
recalcular capabilities
+
degradar para push-to-talk.
```

---

# 16.2. VoiceInteractionService + Session Service

Criar no control plane:

```text
OrionVoiceInteractionService
process = :voice
```

Manifest:

```text
exported=true

permission=
android.permission.BIND_VOICE_INTERACTION
```

Intent filter/meta-data mínimos exigidos.

O `OrionVoiceInteractionService` deve permanecer tão leve quanto possível.

Criar a sessão pesada em processo separado:

```text
OrionVoiceInteractionSessionService
process = :voice_session

OrionVoiceInteractionSession
```

STT, buffers de áudio, UI/session presentation e demais operações pesadas de interação devem permanecer fora do processo `:voice` mantido pelo framework.

---

# 16.3. Security review

Validar:

```text
OrionCoreGatewayService não exportado;

caller validation;

Binder boundary;

intent extras;

no business authority in external payload.
```

---

# 16.4. Compatibility

Executar suíte específica na API Android suportada pelo release.

---

# 16.5. Exit Gate F10

```text
[ ] role grant;

[ ] role revoke;

[ ] VoiceInteractionService leve em :voice;

[ ] VoiceInteractionSessionService/session em :voice_session;

[ ] BIND_VOICE_INTERACTION;

[ ] no unauthorized binder access;

[ ] :voice/:voice_session/main recovery;

[ ] Core permanece independente do role.
```

Resultado:

```text
SYSTEM_ASSISTANT_READY
```

---

# 17. FASE 11 — WAKE WORD QUALIFICATION

Esta fase é um gate experimental.

Não é uma promessa de feature.

---

# 17.1. Pipeline

```text
PCM
↓
lightweight KWS
↓
"Orion"
↓
VAD
↓
STT
↓
Core.
```

Whisper contínuo:

```text
PROIBIDO.
```

---

# 17.2. Testes

Medir:

```text
battery;

thermal;

CPU;

false positive;

false negative;

screen off;

horas de execução;

interação com áudio externo;

Doze;

background policies.
```

---

# 17.3. Resultado

Wake word recebe uma política:

```text
ALWAYS

CHARGING_ONLY

ACTIVE_HOURS

VOICE_MODE_ONLY

DISABLED
```

Se os testes falharem:

```text
DISABLED
```

e o produto continua aprovado.

---

# 18. FASE 12 — ADAPTIVE INTELLIGENCE

Somente depois de todo o pipeline estar estável.

Adicionar:

```text
habit modeling;

feedback weighting;

adaptive ranking;

personalização;

adaptive proactivity.
```

Não alterar:

```text
Policy Engine;

hard confirmations;

authorization level;

security boundaries.
```

Inteligência adaptativa pode recomendar.

Não pode redefinir autoridade.

---

# 19. GATES DE PRODUTO

## GATE A — FOUNDATION

Após F1:

```text
produto estruturalmente saudável.
```

## GATE B — TRANSACTION CORE

Após F2:

```text
estado seguro.
```

## GATE C — MVP CORE

Após F4:

```text
assistente determinístico resiliente.
```

## GATE D — MVP COGNITIVE

Após F6:

```text
assistente com IA local.
```

## GATE E — INTELLIGENT AGENT

Após F8:

```text
memória + contexto + proatividade.
```

## GATE F — VOICE

Após F9:

```text
conversa por voz iniciada pelo usuário.
```

## GATE G — SYSTEM ASSISTANT

Após F10.

## GATE H — WAKE WORD

Condicional.

## GATE I — RELEASE CANDIDATE

Após hardening final.

---

# 20. ORDEM RECOMENDADA DOS PRIMEIROS PRs

Não começar pela interface de chat nem pelo llama.cpp.

Sequência recomendada:

```text
PR-000
Repository bootstrap + Gradle wrapper + ToolchainProfile

PR-001
Module skeleton + CI baseline + OrionProcessRole/OrionProcessBootstrapper + native compatibility checks

PR-002
Core model + Command/Event/Query contracts

PR-003
OrionClock + BootSessionId + time semantics

PR-004
Health + Readiness skeleton

PR-005
Room schema V1

PR-006
Repositories + optimistic concurrency

PR-007
Domain Event Outbox

PR-008
ActionRequest + Action Outbox state machine

PR-009
Confirmation fingerprint + payload lock

PR-010
IngressCoordinator + IngressEnvelope

PR-011
RecoveryCoordinator + Startup Barrier

PR-012
CapabilityRegistry

PR-013
TaskSkill

PR-014
Reminder domain

PR-015
Timer/Time/Battery/Device Skills

PR-016
AlarmManager + ExactAlarmCapability

PR-017
ReminderReconciler

PR-018
WorkManager unique workers

PR-019
Boot + Direct Boot

PR-020
ApplicationExitMonitor

PR-021
Diagnostics screen
```

Nesse ponto:

```text
MVP CORE.
```

Depois:

```text
PR-022
:ai process

PR-023
Cortex IPC

PR-024
JNI wrapper

PR-025
llama.cpp integration

PR-026
ModelRegistry

PR-027
streaming

PR-028
cancel/timeouts

PR-029
ResourceGovernor memory-aware

PR-030
Intent Router

PR-031
Hybrid Orchestrator

PR-032
Structured tool calling

PR-033
Context Budget

PR-034
Chat UI
```

Depois:

```text
Memory
→ Sentinel
→ Voice
→ Assistant
→ Wake word.
```

---

# 21. REGRA DE TAMANHO DAS TAREFAS

Cada tarefa deve representar uma mudança verificável.

Evitar tarefas como:

```text
“implementar Recovery”.
```

Preferir:

```text
ORION-REC-001
Criar CoreReadiness.

ORION-REC-002
Implementar Startup Barrier.

ORION-REC-003
Abrir Room durante recovery.

ORION-REC-004
Executar migrations.

ORION-REC-005
Recuperar Event Outbox.

ORION-REC-006
Recuperar Action Outbox.

ORION-REC-007
Drenar IngressQueue após READY.

ORION-REC-008
Adicionar process-death test.
```

Ideal:

```text
uma issue
→ uma responsabilidade principal
→ testes
→ commit/PR.
```

---

# 22. CONVENÇÃO DE ISSUES

Usar:

```text
ORION-F0-xxx

ORION-FND-xxx

ORION-DATA-xxx

ORION-ACT-xxx

ORION-REC-xxx

ORION-SKL-xxx

ORION-SCH-xxx

ORION-AI-xxx

ORION-ORC-xxx

ORION-MEM-xxx

ORION-SEN-xxx

ORION-VOI-xxx

ORION-SEC-xxx

ORION-TST-xxx
```

Prioridades:

```text
P0
bloqueia arquitetura/invariante;

P1
bloqueia milestone;

P2
necessária para fase;

P3
melhoria/calibração.
```

---

# 23. DEFINITION OF DONE GLOBAL

Nenhuma issue é DONE sem:

```text
[ ] implementação;

[ ] unit tests aplicáveis;

[ ] instrumentation tests aplicáveis;

[ ] error handling;

[ ] logging seguro;

[ ] Health atualizado quando relevante;

[ ] nenhuma dependência proibida;

[ ] nenhuma quebra de invariante;

[ ] documentação mínima;

[ ] migration quando schema mudou;

[ ] process death considerado quando estado é persistente;

[ ] time semantics/clock injetável considerados quando houver expiração, timeout, lease ou scheduling;

[ ] idempotencyKeyPolicy considerada quando houver mutação/replay/side effect;

[ ] app upgrade/migration considerado quando schema ou estado durável mudou;

[ ] retention/pruning considerado para dados de crescimento contínuo;

[ ] native compatibility considerada quando NDK/JNI mudou;

[ ] security boundary considerado quando IPC/Intent;

[ ] bootstrap multiprocesso considerado quando componente/processo foi tocado;

[ ] payload durável genérico possui sensitivity/minimization/encryption quando aplicável;

[ ] lease monotônica persistida está vinculada a BootSessionId quando aplicável;

[ ] testes verdes.
```

---

# 24. REGRA PARA CÓDIGO GERADO/ASSISTIDO

Cada pacote de trabalho deverá informar explicitamente:

```text
OBJETIVO

ARQUIVOS PERMITIDOS

CONTRATOS ENVOLVIDOS

INVARIANTES ENVOLVIDOS

TESTES OBRIGATÓRIOS

CRITÉRIO DE ACEITE

FORA DE ESCOPO
```

Exemplo:

```text
TAREFA:
Implementar ActionRequest state machine.

INVARIANTES:
INV-004
INV-005
INV-006
INV-021
INV-022

FORA DE ESCOPO:
Skill execution real
UI
LLM
Voice

ACEITE:
todas as transições válidas testadas
+
transições inválidas rejeitadas.
```

Isso reduz mudanças acidentais de arquitetura durante implementação automatizada.

---

# 25. CICLO RECOMENDADO DE IMPLEMENTAÇÃO

Para cada issue:

```text
1. Ler contrato arquitetural relacionado.

2. Identificar invariantes.

3. Implementar teste principal.

4. Implementar código mínimo.

5. Rodar testes do módulo.

6. Rodar testes arquiteturais.

7. Rodar build completo.

8. Revisar logs/security.

9. Commit.

10. Atualizar relatório da fase.
```

A cada grupo pequeno de mudanças:

```text
assemble
+
unit tests
+
lint/static checks.
```

Antes de fechar milestone:

```text
instrumentation
+
process death
+
system scenarios correspondentes.
```

---

# 26. ADR GATE

Pergunta obrigatória antes de qualquer mudança estrutural:

```text
“Isso altera uma decisão da V3.2?”
```

Se:

```text
NÃO
→ implementação normal.
```

Se:

```text
SIM
→ não alterar diretamente.
```

Criar:

```text
ADR-xxxx
```

contendo:

```text
contexto;

problema real;

evidência;

alternativas;

decisão;

consequências;

invariantes afetados;

migration plan.
```

---

# 27. MATRIZ DE INVARIANTES × FASE

## F1

Priorizar:

```text
INV-001
INV-002
INV-003
INV-012
INV-024
INV-025
INV-026
INV-032
```

## F2

```text
INV-004
INV-005
INV-006
INV-007
INV-008
INV-011
INV-019
INV-021
INV-022
INV-031
```

## F4

```text
INV-014
INV-015
INV-016
INV-018
INV-023
INV-029
INV-030
```

## F5

```text
INV-002
INV-012
INV-013
INV-027
INV-028
```

## F6–F7

```text
INV-009
INV-010
INV-011
```

## F9–F10

```text
INV-003
INV-017
INV-024
INV-025
INV-026
```

## F11

```text
INV-020
```

## Hardening transversal F1/F2/F4/F9-F10

```text
INV-033
INV-034
INV-035
INV-036
```

Ao final, todos os INV-001–INV-036 devem possuir pelo menos um teste ou mecanismo verificável de review.

---

# 28. TESTE DE PROCESS DEATH COMO REGRA DE ENGENHARIA

Sempre que um recurso possuir:

```text
estado persistente
ou
efeito externo
```

perguntar:

```text
“o que acontece se o processo morrer exatamente aqui?”
```

Pontos mínimos:

```text
antes do commit;

após commit;

antes de side effect;

após side effect;

antes de confirmação persistida;

após confirmação;

durante callback;

durante IPC;

durante reconciliation.
```

O resultado deve ser sempre um entre:

```text
recovery seguro;

idempotent retry;

VERIFY_THEN_RETRY;

UNKNOWN;

MANUAL_REVIEW.
```

Nunca:

```text
“provavelmente executou”.
```

---

# 29. SECURITY GATE

Antes de todo milestone:

```text
[ ] nenhum Binder interno exportado;

[ ] exported somente quando necessário;

[ ] intents explícitas internamente;

[ ] PendingIntent immutable;

[ ] external payload tratado como untrusted;

[ ] LLM sem authority;

[ ] SECRET sem log;

[ ] SECRET sem FTS;

[ ] capabilities revalidadas;

[ ] destructive confirmation obrigatória;

[ ] payload confirmado não mutável;

[ ] envelope criptográfico versionado quando aplicável;

[ ] nonce/IV uniqueness + AAD binding testados quando aplicável;

[ ] lifecycle/rotação/invalidação de chaves testados quando aplicável;

[ ] nenhum fallback de dado cifrado para plaintext.
```

---

# 30. PERFORMANCE GATE

A partir da F5 acompanhar regressões:

```text
cold start;

DB open;

recovery duration;

baseline RSS main;

baseline RSS :voice;

Cortex load;

first token;

tokens/s;

peak RSS :ai;

aggregate app memory (main + :ai + :voice + native);

headroom per-process e agregado;

unload;

battery;

thermal;

compatibilidade nativa 16 KiB;

comportamento sob limites de memória da API alvo.
```

Comparar sempre contra:

```text
BenchmarkProfile anterior.
```

---

# 31. SOAK TEST PROGRAM

## Após MVP Core

```text
24h.
```

Com:

```text
tasks;
reminders;
heartbeat;
reboot;
process death.
```

## Após Cortex

```text
24h
+
memory stress.
```

## Após Sentinel/Voice

```text
72h.
```

## Antes de release estável

```text
vários dias.
```

Verificar:

```text
ANR;

crash;

wakelocks;

battery drain;

thermal;

DB growth;

retention/pruning;

log growth;

event backlog;

action backlog;

ingress backlog;

UNKNOWN;

idempotency conflicts;

clock/timezone anomalies;

restart loops.
```

---

# 32. CRITÉRIO DE RELEASE CANDIDATE

Não declarar V1 operacional enquanto não houver:

```text
[ ] MVP Core aprovado;

[ ] MVP Cognitive aprovado;

[ ] Memory Engine aprovado;

[ ] Sentinel aprovado;

[ ] Push-to-Talk aprovado;

[ ] Recovery soak aprovado;

[ ] process death suite aprovada;

[ ] reboot suite aprovada;

[ ] app upgrade/migration suite aprovada;

[ ] Android system suite aprovada;

[ ] security suite aprovada;

[ ] performance qualification aprovada;

[ ] API compatibility profile aprovado;

[ ] native 16 KiB compatibility gate aprovado;

[ ] memory-limit qualification da API alvo aprovada;

[ ] diagnostics suficientes para investigar falhas;

[ ] R8/ProGuard rules qualificadas em release;

[ ] JNI keep rules qualificadas;

[ ] AIDL/Parcelable compatibility qualificada;

[ ] native debug symbols preservados/publicáveis conforme pipeline;

[ ] mapping de release preservado;

[ ] crash/native symbolication testada;

[ ] dependency/license report gerado;

[ ] invariantes INV-001–INV-036 verificados.
```

System Assistant pode ser requisito da versão pretendida, mas wake word continua condicionado ao gate físico.

---

# 33. O QUE NÃO DEVE BLOQUEAR O INÍCIO

Não esperar definir previamente:

```text
modelo definitivo;

wake word engine definitivo;

todos os thresholds;

embeddings;

CalendarSkill;

ContactsSkill;

MessagingSkill;

Home Automation;

cloud providers;

integrações externas avançadas;

personalização sofisticada.
```

Nada disso bloqueia:

```text
FASE 0
→
FASE 1
→
FASE 2
→
MVP Core.
```

---

# 34. PRIMEIRA META REAL

A primeira meta não deve ser:

```text
“conversar com o Orion”.
```

Deve ser:

```text
“matar o processo do Orion
em qualquer momento crítico
e provar que ele volta
sem corromper estado
nem repetir ações”.
```

Quando isso estiver sólido, conectar o LLM torna-se significativamente menos arriscado.

---

# 35. SEGUNDA META

Depois:

```text
“retirar completamente o Cortex
e provar que o O.R.I.O.N.
continua útil”.
```

Se isso for verdadeiro, a separação arquitetural foi implementada corretamente.

---

# 36. TERCEIRA META

Depois:

```text
“matar :ai durante uma inferência
e provar que o Core permanece saudável”.
```

---

# 37. QUARTA META

Depois:

```text
“matar main
e iniciar uma solicitação por :voice,
permitindo recovery sem side effect prematuro”.
```

Esse teste comprova uma das partes mais difíceis da arquitetura.

---

# 38. QUINTA META

Finalmente:

```text
rodar por vários dias
com memória,
scheduler,
Cortex,
Sentinel,
voz,
reboot,
process death
e pressão de recursos
sem acumular inconsistências.
```

---

# 39. SEXTA META

Provar upgrade seguro com estado real:

```text
instalar Vn;

criar tarefas/reminders;

manter outbox/ingress/action em estados relevantes;

agendar workers/alarms;

atualizar para Vn+1;

executar migration + recovery + reconciliation;

provar:

nenhuma perda de estado
+
nenhuma ação duplicada
+
nenhum reminder órfão
+
nenhum worker inconsistente.
```

Upgrade deve ser tratado como uma forma de recovery.

---

# 40. DEFINITION OF IMPLEMENTATION COMPLETE

A implementação da V3.2 estará realmente concluída quando não apenas as features existirem, mas quando as propriedades arquiteturais existirem no software.

Ou seja:

```text
não basta existir :ai;
:ai precisa ser descartável
e sua semântica de processo precisa ser explícita.

não basta existir Room;
somente main pode possuí-lo.

não basta existir ActionRequest;
UNKNOWN precisa ser seguro
e idempotencyKey precisa possuir semântica verificável.

não basta existir confirmação;
ela precisa pertencer ao payload exato.

não basta existir RecoveryCoordinator;
ingressos precisam respeitar o barrier.

não basta existir :voice;
voz precisa falar com o Core pelo IPC definido.

não basta existir ResourceGovernor;
ele precisa reagir aos sinais de pressão
por processo e pelo orçamento agregado do aplicativo.

não basta existir reminder;
o banco precisa continuar sendo sua fonte da verdade.

não basta existir LLM;
nenhuma saída sua pode possuir authority.

não basta funcionar após reboot;
Force Stop precisa continuar sendo respeitado.

não basta rodar no S21;
a versão Android alvo precisa possuir seu próprio CompatibilityProfile.

não basta migrar o schema;
upgrade Vn→Vn+1 precisa preservar estado lógico e side effects.

não basta usar timestamps;
tempo civil, tempo monotônico e reboot precisam possuir semântica explícita.

não basta usar JNI/NDK;
os binários nativos precisam passar pelo NATIVE_COMPATIBILITY_GATE.

não basta operar 24/7;
banco, logs, outboxes e temporários precisam possuir retenção/pruning bounded.
```

---

# 41. SEQUÊNCIA EXECUTIVA FINAL

```text
AGORA
│
├─ F0 — qualificar hardware/API
│
├─ F1 — criar fundação
│
├─ F2 — fechar Core transacional
│
├─ F3 — Skills determinísticas
│
├─ F4 — scheduler + recovery
│
│      └── MVP CORE
│
├─ F5 — Cortex isolado
│
├─ F6 — orquestração híbrida
│
│      └── MVP COGNITIVE
│
├─ F7 — memória inteligente
│
├─ F8 — Sentinel + recursos
│
│      └── INTELLIGENT AGENT
│
├─ F9 — push-to-talk
│
│      └── VOICE
│
├─ F10 — System Assistant
│
├─ F11 — wake word qualification
│
├─ F12 — adaptive intelligence
│
└─ HARDENING FINAL
       │
       ├─ process death
       ├─ reboot
       ├─ app upgrade/migrations
       ├─ security
       ├─ Android system
       ├─ memory pressure
       ├─ native 16 KiB compatibility
       ├─ retention/pruning
       ├─ soak
       ├─ performance
       └─ compatibility
             │
             ▼
       O.R.I.O.N. V1
```

---

# 42. PONTO EXATO PARA COMEÇAR O CÓDIGO

O primeiro trabalho de engenharia deve ser:

```text
ORION-F0-000
Criar repository bootstrap,
fixar Gradle wrapper + ToolchainProfile
e preparar checks de compatibilidade nativa.
```

Na sequência imediata:

```text
ORION-F0-001
Criar harness de benchmark
e formato BenchmarkProfile.
```

Em paralelo, preparar:

```text
ORION-FND-001
Criar module skeleton,
app bootstrap, CI baseline
e checks iniciais de compatibilidade nativa.
```

Na sequência:

```text
ORION-FND-002
Criar módulos base.

ORION-FND-003
Criar core:model.

ORION-FND-004
Criar contratos
Command/Event/Query.

ORION-FND-005
Criar OrionClock, BootSessionId
e time semantics.

ORION-FND-006
Criar CoreReadiness
e OrionHealth.

ORION-DATA-001
Criar schema Room V1.
```

A partir daí o desenvolvimento deve seguir a cadeia definida neste documento.

---

# 42.1. BASELINE FREEZE

Com a aprovação desta V1.2:

```text
Arquitetura Android Nativa V3.2
+
Plano Mestre de Implementação V1.2 FINAL
=
ENGINEERING BASELINE
```

A baseline está congelada para implementação.

Precedência de ADR a partir do primeiro commit:

```text
ADR aceito
→ supersede somente as decisões/cláusulas
  que declarar explicitamente alteradas;

Arquitetura V3.2 + Plano V1.2
→ continuam normativos em todo o restante.
```

Classificação de mudanças a partir do primeiro commit:

```text
calibração de parâmetro
→ issue normal;

correção de bug dentro dos contratos
→ issue normal;

novo teste/hardening sem alterar autoridade/fronteira
→ issue normal;

nova Skill dentro das fronteiras previstas
→ issue normal;

alteração de ownership, authority, processo, persistência, IPC, policy, recovery ou invariante
→ ADR obrigatório antes do código.
```

Nenhum documento arquitetural adicional é requisito para iniciar a F0/F1.

---

# 43. REGRA FINAL DO PLANO MESTRE

Não construir primeiro o que é mais impressionante.

Construir primeiro o que torna o restante confiável.

Portanto:

```text
persistência antes de inteligência;

recovery antes de autonomia;

policy antes de tool calling;

Action Outbox antes de ações externas;

ResourceGovernor antes do Cortex;

Fast Path antes do Cognitive Path;

Push-to-Talk antes de wake word;

process death tests antes de 24/7;

invariantes antes de features.
```

O caminho de implementação fica, portanto, fechado como:

```text
ARQUITETURA V3.2
↓
PLANO MESTRE
↓
FASE 0
↓
FOUNDATION
↓
TRANSACTION CORE
↓
MVP CORE
↓
CORTEX
↓
MVP COGNITIVE
↓
MEMORY
↓
SENTINEL
↓
VOICE
↓
SYSTEM ASSISTANT
↓
WAKE WORD QUALIFICATION
↓
ADAPTIVE INTELLIGENCE
↓
HARDENING
↓
O.R.I.O.N. V1
```

**Não é necessária nova documentação arquitetural antes do primeiro commit.**

A partir daqui, a unidade principal de trabalho deixa de ser:

```text
“definir arquitetura”
```

e passa a ser:

```text
issue
→ implementação
→ teste
→ gate
→ commit
→ próxima issue.
```

**Status: FINAL / ENGINEERING BASELINE / PRONTO PARA INICIAR A ENGENHARIA.**

A partir desta V1.2, alterações estruturais somente entram por ADR. Correções, calibrações e implementação normal seguem por issue/PR dentro dos gates definidos.

