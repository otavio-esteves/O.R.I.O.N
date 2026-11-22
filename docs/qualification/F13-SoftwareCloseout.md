# F13 — Hardening final de software

F13 é o hardening final da sequência executiva do Plano Mestre §41.
O Gate I continua sujeito aos critérios de Release Candidate do §32.
[Pacote de trabalho](../issues/ORION-F13-HARDEN-001.md) · [Plano Mestre §32](../../2-plano-mestre.md#32-critério-de-release-candidate)

## Evidência de software

O build Release arm64 usa R8 e produz mapping e símbolos nativos completos.
O verificador confere o mapping embutido no AAB. Para as duas bibliotecas JNI
do projeto, confere o Build ID do APK, do AAB e dos símbolos e resolve um
endereço de cada binário até uma linha do código-fonte.

O inventário registra os módulos resolvidos do runtime Release, as licenças
declaradas nos POMs e as revisões e somas dos arquivos-fonte nativos.
Entradas com licença não declarada ou POM indisponível ficam explícitas.
O pacote guarda as licenças MIT do llama.cpp e do whisper.cpp e as somas
dos artefatos para conferência.

```sh
docker compose run --rm android-build :app:stageReleaseEvidence \
  verifyNativePackageArtifacts verifyBundlePageAlignment --max-workers=2
```

O CI guarda o pacote em `orion-release-evidence`. A suíte Android executa
também o chat sem modelo com os APKs de qualificação minificados, identificados
por `network.orion.qualification`. O runner verifica os SHA-256 dos APKs
instalados e exige todos os resultados esperados antes de aprovar o check.
O mesmo check executa a suíte de Android Keystore, incluindo rotação,
indisponibilidade da chave, autenticação e escrita interrompida.

## Validação local

A árvore de software de `75cec03` gerou os artefatos Release e de
qualificação no Docker. As verificações de empacotamento e de 16 KiB
passaram. O pacote de evidências contém 127 módulos Maven. O POM direto
de `com.google.guava:listenablefuture:1.0` deixa a licença em branco.
O POM pai `com.google.guava:guava-parent:26.0-android` declara Apache 2.0.
O Retrace também recuperou classe, método e linha de um stack trace sintético.

No emulador API 35/x86_64, com páginas de 4 KiB, o chat minificado passou
9 de 9 casos e o Keystore passou 4 de 4. Os runners sem dispositivo
passaram 662 testes em Docker.

## Estado do Gate I

| Obrigação | Evidência ou condição pendente |
| --- | --- |
| Core, recuperação, morte de processo, reboot e upgrade | Histórico funcional em S21. O CI cobre regressões de persistência, upgrade e morte de processo no emulador. O soak físico do Core de 24 horas segue pendente. |
| Cortex, desempenho e memória | Perfis de produção, classificação do MemoryLimiter em dispositivo API 37 e 24 horas com estresse de memória seguem pendentes. |
| Memory Engine, Sentinel e voz | Validação de software registrada até F12. Qualificação física de áudio, latência, consumo e 72 horas após Sentinel/Voice seguem pendentes. |
| API e compatibilidade nativa | Verificações estáticas de ABI e 16 KiB e verificações em emulador. A matriz física de carregamento nativo permanece em aberto. |
| Segurança, IPC e diagnóstico | Verificações de fronteira no CI, testes transacionais e diagnósticos. A revisão final dos invariantes depende da matriz anterior. |
| R8, JNI, simbolização e licenças | Evidência gerada pelo build e pelo teste minificado. A simbolização exercitada usa endereços JNI do artefato Release. Entradas sem declaração de licença requerem revisão. |

A decisão do Gate I aguarda as provas físicas e o teste de estabilidade de
vários dias previsto para release estável. Cortex continua sem perfil de
produção aprovado. A palavra de ativação segue `DISABLED`.

[F4](F4-Closeout.md) · [F5](F5-QualificationChecklist.md) ·
[Compatibilidade](../compatibility/CompatibilityProfile.md) ·
[F11](F11-SoftwareCloseout.md) · [F12](F12-SoftwareCloseout.md)
