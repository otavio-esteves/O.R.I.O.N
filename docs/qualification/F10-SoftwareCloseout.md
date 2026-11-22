# F10 — System Assistant

Em **Ajustes → Configurar assistente no Android**, o O.R.I.O.N. abre a tela de
apps padrão do sistema. A seleção e a remoção do assistente são feitas ali.
O estado do papel aparece em Ajustes e Diagnóstico. A capability
`orion_system_assistant` considera o papel concedido e o componente ativo.

Depois de selecionar O.R.I.O.N., acione o assistente pelo atalho do dispositivo.
A janela exibe um botão de captura que usa o modelo local da F9. Instale o
modelo e autorize o microfone pelo O.R.I.O.N. O estado da sessão independe do
Core continuar disponível pelo texto ou pelo push-to-talk em Ajustes.

## Limites da plataforma

O Android mantém a descoberta do assistente protegida por
`BIND_VOICE_INTERACTION`. O controle roda em `:voice` e a sessão em
`:voice_session`. O framework vincula a sessão pelo componente explícito.
Os dados de Assist e os extras recebidos pela sessão não entram no Core. O
gateway do Core permanece privado. Consultas de voz usam o ingresso da F9.
Preparar uma tarefa continua dependendo do canal do app com presença em MAIN.

O teste da seleção em emulador usa um papel real do Android dentro de um AVD
descartável. O controle de fixture é restrito ao emulador. A sessão é acionada
pelo atalho normal do sistema, com o processo MAIN em execução. A aquisição
em produção segue o fluxo de consentimento de apps padrão do Android.

## Verificação

| Critério | Teste |
| --- | --- |
| Serviços, permissões, metadados, componentes privados | `OrionSystemAssistantPlatformTest` |
| Seleção, abertura da sessão e revogação no framework | `verify-system-assistant.py` |
| Core independente e retomada após morte de processo | Suíte de chat sem modelo, suíte F9 e `verify-voice-process-recovery.py` |
| Segurança da admissão de voz | Testes F9 de transcrição parcial, Startup Barrier, criptografia e confirmação exata para preparar uma tarefa |

O teste manual no emulador API 35 verificou a escolha de O.R.I.O.N. na tela
de apps padrão, o diálogo de consentimento, a abertura da janela e seu
fechamento quando o papel foi revogado. MAIN manteve o mesmo PID. O emulador
API 37 também apresentou a janela de voz pelo atalho nativo de energia.

A qualificação de áudio, tempos, estabilidade e comportamento Samsung/One UI
segue adiada conforme o ADR-0008.

[Plano Mestre §16](../../2-plano-mestre.md#16-fase-10--system-assistant) ·
[ADR-0008](../adr/ADR-0008-f10-software-entry.md) ·
[Documentação Android de VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)
