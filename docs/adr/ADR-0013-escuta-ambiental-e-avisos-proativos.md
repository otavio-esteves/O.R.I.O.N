# ADR-0013 — Escuta ambiental e avisos proativos falados

**Status** · Aceito pelo usuário para implementação. A viabilidade no Galaxy S21 ainda requer evidência.

## Contexto

O O.R.I.O.N. deve ouvir, organizar, avisar e sugerir por iniciativa própria. A operação começa assim que o Android disponibiliza a execução e as permissões necessárias, sem botão de início, ativação de modo ou outra configuração no O.R.I.O.N.

O usuário diz espontaneamente “putz, tenho uma reunião amanhã às 19h.” No dia seguinte, às 18h, o O.R.I.O.N. deve dizer “senhor, não se esqueça da reunião às 19h”. A captura, a interpretação, o agendamento e a fala acontecem com a interface fechada, sem palavra de ativação, comando ou confirmação para cada compromisso.

O início automático do Core recupera dados e alarmes. A voz atual começa por ação do usuário. O Sentinel atual avalia tarefas já persistidas em um heartbeat deferrable. O experimento F11 avalia trechos pré-segmentados para identificar uma palavra de ativação. A captação de fala espontânea e a entrega falada a partir dela exigem contratos próprios. [Arquitetura V3.2 §§78–90](../../1-orion.md#78-proatividade) · [Plano Mestre §§14 e 17](../../2-plano-mestre.md#14-fase-8--sentinel--resource-governor-completo) · [Evidência F11](../qualification/F11-SoftwareCloseout.md)

## Decisão

- Acrescentar escuta ambiental iniciada automaticamente à Arquitetura V3.2 §§81 e 85. A permissão de microfone concedida pelo usuário no Android, com a finalidade apresentada, satisfaz a adesão exigida para essa coleta pela Arquitetura V3.2 §75 e pelo Plano Mestre §14.2. O O.R.I.O.N. apresenta a finalidade de escuta ambiental no fluxo de permissão do sistema e inicia a captura quando a plataforma permite. Se a implementação depender do papel de assistente, a seleção desse papel acontece nas configurações do Android. Expor o estado da captura e controles de pausa. A revogação da autorização interrompe a captura e invalida avisos ambientais ainda pendentes. [Permissões do serviço de microfone](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- Manter o controle do ciclo de vida em `:voice` e a captura e o processamento de áudio em `:voice_session`. Usar um buffer limitado em memória e reconhecimento local apenas nos trechos selecionados por detecção de fala. Descartar o áudio e o texto integral após a análise. Estender o IPC versionado do §23.1 com a capacidade de propor observações derivadas da fala final ao Core.
- O usuário autorizou fornecer uma referência de voz local uma vez. O perfil deve manter somente uma representação protegida da voz vinculada à versão do extrator. A referência não participa do início da sessão. Ela limita quais observações podem ser atribuídas ao usuário. Calibrar o limiar no S21 antes de autorizar qualquer ação a partir desse resultado.
- O Core recebe a observação pelo `IngressCoordinator`. A política valida a atribuição ao usuário, a existência do compromisso e a data e hora. Descartar hipóteses, negações e candidatos cuja atribuição ou horário permaneça ambíguo. Resolver “amanhã” usando o instante e o fuso da observação pelas regras civis dos §§38–39. Para o cenário inicial, programar o aviso uma hora antes.
- A observação mantém sua origem e seu estado inferido. O Core organiza os compromissos e aplica a política dos §§58–59 para admitir avisos pessoais no dispositivo sob a autorização vigente. Criar um `ActionRequest` idempotente para a entrega autorizada e persistir com ele o mínimo de dados necessário, com criptografia conforme a classificação. Room conserva a autoridade sobre os horários. AlarmManager entrega o disparo conforme a capacidade. O Core revalida a autorização e as capacidades antes da fala e aplica recuperação sem reexecutar um efeito em `UNKNOWN`.
- O Sentinel pode sugerir ações relacionadas aos compromissos organizados quando o contexto justificar a intervenção. Aplicar os limites de frequência, horários de silêncio e histórico de feedback dos §§78–80. A sugestão apresenta uma opção ao usuário. A execução de uma ação sugerida passa pela política do Core aplicável àquela ação.
- A entrega usa a voz local no horário do aviso. Respeitar modo silencioso, permissões de áudio, horários de silêncio e limites de repetição conforme a política. Expor interrupções da escuta e da entrega nos diagnósticos. Uma notificação textual em caso de áudio indisponível registra a degradação do cenário.

## Alternativas de plataforma e evidência

1. **Serviço Android do tipo microfone.** A documentação prevê continuar a captura em segundo plano quando o serviço é iniciado num contexto permitido. Avaliar o início automático após a concessão de permissão, com tela apagada e durante o uso de outros apps. O início direto pelo receiver de boot sofre restrições de plataforma. [Tipos de serviços em primeiro plano](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
2. **Assistente selecionado no Android.** Verificar se `VoiceInteractionService` oferece ao O.R.I.O.N. um contexto público permitido para retomar a captura após desbloqueio, sem abrir sua interface. A documentação descreve exceções à criação de serviços e regras de concorrência de áudio que distinguem assistentes pré-instalados privilegiados. A seleção como assistente é uma hipótese de viabilidade para o S21. [Restrições de início em segundo plano](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions) · [Compartilhamento de áudio de entrada](https://developer.android.com/media/platform/sharing-audio-input)
3. **Palavra de ativação.** O requisito de fala espontânea exclui esse gatilho para o cenário. O detector avaliado na F11 só reconhece a palavra configurada em trechos pré-segmentados. [Evidência F11](../qualification/F11-SoftwareCloseout.md)

O ensaio também precisa provar a reprodução falada com a interface fechada. Para apps que miram Android 15 ou superior, a obtenção de foco de áudio exige que o app esteja no topo ou tenha um serviço em primeiro plano. [Foco de áudio no Android](https://developer.android.com/media/optimize/audio-focus)

## Consequências e invariantes

O novo ingresso e a fala agendada exigem revisão de IPC, posse do microfone, privacidade, consumo e política de autorização. Aplicam-se INV-001, INV-003–009, INV-010–012, INV-014–018, INV-021–026, INV-029–035. A política de avisos ambientais usa a autorização informada do microfone. Quando a política exige confirmação para uma ação, o Core a vincula ao payload exato. A captura só pode ocorrer em contexto permitido pela plataforma. O Core reconcilia os avisos pendentes quando o Android volta a permitir a execução do pacote.

## Migração e aceite

Começar com um ensaio em APK de desenvolvimento e dados sintéticos. Na atualização, validar a autorização do Android para a finalidade de escuta ambiental. Se uma versão anterior solicitou o microfone apenas para sessões interativas, apresentar a nova finalidade no fluxo de autorização do sistema antes de iniciar a coleta. A eventual migração de dados de avisos será aditiva e testada junto à recuperação.

- Após as autorizações do Android, verificar que a escuta começa sem ativação adicional no O.R.I.O.N. Com a interface fechada e a tela apagada, dizer a frase do cenário. Às 18h do dia seguinte, observar a frase falada uma vez e verificar a origem, o horário e o registro da execução. Exercitar também reinício entre captura e entrega e retomada da escuta após o primeiro desbloqueio sem abrir a interface.
- Com um compromisso já organizado, verificar que o Sentinel produz uma sugestão pertinente no contexto previsto pela política, sem pedido do usuário.
- Verificar repetições, correções, falas hipotéticas, negativas, vozes de outras pessoas e áudio de TV. Medir falsos avisos por hora, compromissos perdidos, precisão do horário e qualidade da atribuição em português brasileiro.
- Fixar os limites de aceitação antes de medir. Executar 24 horas no Galaxy S21 com tela apagada, Doze, uso concorrente de áudio, pausa e retirada de consentimento. Medir bateria, temperatura, memória e trechos em que a política da plataforma interrompe a captação ou a fala. Registrar firmware, One UI e API e validar a compatibilidade de API separadamente.

## Evidência inicial de desenvolvimento

O APK Debug com `-PorionAmbientQualification=true` inclui uma sessão de medição de dois minutos. A seleção como assistente e a concessão de microfone são feitas pelo Android. A sessão registra sinais técnicos de acesso ao microfone. Com o Whisper local disponível, a qualificação encaminha trechos delimitados para que o Core reconheça o formato do compromisso de forma efêmera.

No emulador Android 15 x86_64, a captura ficou ativa após a seleção como assistente e com a tela apagada. O reboot elevou a contagem de boot de 3 para 4. O papel continuou atribuído ao pacote e o Android deixou o serviço de interação sem implementação ativa. A sessão de captura ficou ausente nesse retorno. A imagem também retornou uma lista vazia de serviços de reconhecimento e um reconhecedor padrão nulo. O código do AOSP considera o reconhecedor ao restaurar o assistente, o que aponta para uma condição a verificar numa imagem com reconhecedor e no S21. O teste usou o microfone virtual sem áudio do host e tem escopo de compatibilidade da plataforma. [Implementação do Android 15](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java)

Os testes do Core verificaram a interpretação da frase, o cálculo de 18h no horário civil e a preparação, em SQLite, de um único compromisso criptografado e um `ActionRequest` vinculado sob autorização de teste. A entrega falada e o fluxo a partir de uma fala viva identificada ainda exigem integração e validação.

O extrator de qualificação usa o AAR do Sherpa-ONNX já fixado pelo projeto e o modelo 3D-Speaker validado por SHA-256. `-PorionAmbientSpeakerAar` habilita a comparação em um build de qualificação. Em instrumentação Android 15 x86_64, a gravação sintética da mesma voz com outra frase obteve similaridade maior que a gravação de outra voz. O limiar em português, a rejeição de reprodução da voz do titular e o uso prolongado exigem a amostra autorizada e dados físicos. [Modelos de identificação de locutor](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)

## Pendências para retomada

- [ ] Obter o caminho local da amostra de voz já autorizada pelo usuário. Implementar o cadastro da referência derivada e protegida, com revogação e sem reter o áudio original. Calibrar a comparação em português brasileiro com a voz do usuário, falas de terceiros e gravações reproduzidas.
- [ ] Conectar o Galaxy S21 ao ADB. Verificar se o Android reativa o serviço do assistente e a captura após reinício e primeiro desbloqueio, com a interface fechada. Registrar API, One UI, permissões, papel de assistente e reconhecedor padrão. No emulador, o papel permaneceu atribuído após o reboot e o serviço não voltou.
- [ ] Conectar a atribuição de voz ao ingresso do Core e à preparação do compromisso. A sonda atual reconhece o formato da fala de forma efêmera. O teste em SQLite exercita a preparação separadamente, com uma observação já atribuída.
- [ ] Integrar o aviso falado à execução agendada e as sugestões ao Sentinel. Verificar que o aviso das 18h é falado uma única vez, com o app fechado, e que o Core recupera o agendamento após reinício. Exercitar revogação entre captura e entrega, duplicatas, atraso, ausência de acesso a áudio e o desfecho `UNKNOWN`.
- [ ] Executar a qualificação física de 24 horas do cenário completo antes da ativação no APK padrão. A execução deve cobrir tela apagada, Doze, concorrência de áudio, consumo, temperatura e perda de permissão.
- [x] Corrigir o registro do receiver privado de telemetria para todas as APIs suportadas. O lint isolado de `:voice:service` passou no Docker. Os 353 testes JVM, os 25 testes instrumentados de lembretes e a comparação sintética passaram na validação inicial.
