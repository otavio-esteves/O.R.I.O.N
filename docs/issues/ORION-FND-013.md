# ORION-FND-013 — Ativar gate nativo transitivo da Foundation

## OBJECTIVE
Ativar o gate associado a ORION-FND-001 para bibliotecas transitivas de Compose e
DataStore, verificando ELF, ABI e packaging de debug/release/AAB sem alegar teste
físico de load. Base: Arquitetura §§28/113, AGENTS §28; ADR-0001.

## FILES ALLOWED
`Dockerfile`, `build.gradle.kts`, `app/build.gradle.kts` (pin NDK), `scripts/benchmark`, `docs/compatibility`, ToolchainProfile e
esta documentação. Nenhuma alteração de JNI ou dependência para evitar checks.

## CONTRACTS / INVARIANTS
NATIVE_COMPATIBILITY_GATE; INV-032. Build estático é distinto de load em dispositivo.

## TESTS REQUIRED
Fixtures de gate não aplicável rejeitam nativos mesmo após ativação do gate real;
PENDING não vira PASS com check ausente; ELF de cada .so tem segmentos LOAD >=16 KiB;
ABIs corretas; zipalign -P16 em APKs; AAB anuncia PAGE_ALIGNMENT_16K e preserva libs.
Executar via Docker com SDK/NDK fixados e em check/CI.

## ACCEPTANCE CRITERIA
Gate PENDING enquanto LOAD_TEST físico não foi executado; artefatos debug/release e
AAB passam checks estáticos reproduzíveis. Não usar resultado x86/4 KiB como prova
de load arm64/16 KiB. Nenhum check desabilitado ou exclusão de biblioteca necessária.

## OUT OF SCOPE
Qualificação física, runtime API 37, novo código JNI, llama.cpp/STT e F0_PASS.

## Implicações
As libs são `libandroidx.graphics.path.so` e `libdatastore_shared_counter.so`.
Release mantém arm64-v8a; debug também inclui x86_64 para o emulador. Sem mudança de
processo, dados, recuperação ou permissões. Não exige ADR. Checks de packaging não
provam disponibilidade de recursos, desempenho ou compatibilidade runtime OEM.
Fonte do procedimento: https://developer.android.com/guide/practices/page-sizes.

A primeira rodada expôs executáveis SDK/NDK com modo 0744 instalados pelo Android
CLI, inacessíveis ao UID não-root do Compose. O Dockerfile normaliza somente os
arquivos já executáveis pelo owner para a+x, sem mudar o conjunto de executáveis,
versões, ownership do workspace ou executar build como root. A task de AAB usa
propriedade Gradle @InputFile e @TaskAction, sem capturar o script e sem desligar
configuration cache. Ambas as falhas são corrigidas na infraestrutura canônica.

O módulo app agora fixa também `android.ndkVersion` no valor do catálogo. O NDK
instalado no Docker não deve depender da versão default do AGP ao processar libs
transitivas. Clang e llvm-strip do perfil foram executados com o UID não-root.

## Evidência
`CONTAINER`: imagem reconstruída com permissões SDK/NDK normalizadas; executáveis
Clang/llvm-strip acessíveis ao UID não-root. Reprocessamento explícito de símbolos
foi seguido do pin NDK no app. Validação focada final passou: ELF/ABI e zipalign de
debug/release APK, ELF/ABI do AAB, PAGE_ALIGNMENT_16K do BundleConfig, fixtures reais
compiladas pelo NDK que rejeitam 4 KiB ELF/APK desalinhado/ABI inválida, fixtures de
gate e APK de instrumentação. A nova task também armazenou configuration cache.
LOAD_TEST continua NOT_RUN físico e o gate permanece PENDING. O emulador API 35
usa x86_64/4 KiB e não qualifica load arm64 nem 16 KiB. O `check` final revalida o
JSON com as três evidências estáticas PASS, sem atribuir PASS ao gate global.
