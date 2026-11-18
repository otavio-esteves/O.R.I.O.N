# Explicit emulator chat probe only: retain the APIs directly exercised by the test APK.
# Optimization/obfuscation remain enabled; no rules here apply to ordinary Debug/Release.
-keep,allowoptimization,allowobfuscation class network.orion.app.OrionApplication { *; }
-keep,allowoptimization,allowobfuscation class network.orion.app.OrionFoundationRuntime { *; }
-keep,allowoptimization,allowobfuscation class network.orion.app.tasks.OrionTaskKernel { *; }
-keep,allowoptimization,allowobfuscation class network.orion.app.tasks.OrionTaskAdmissionOwner** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.app.tasks.OrionTaskQuery { *; }
-keep,allowoptimization,allowobfuscation class network.orion.app.ai.OrionCortexClient { *; }
-keep,allowoptimization,allowobfuscation class network.orion.app.chat.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.core.orchestrator.chat.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.core.resources.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.core.time.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.core.queries.tasks.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.core.commands.tasks.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.data.database.** { *; }
-keep,allowoptimization,allowobfuscation class network.orion.data.repository.models.** { *; }
