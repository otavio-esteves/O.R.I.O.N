# Explicit emulator chat probe only: retain the APIs directly exercised by the test APK.
# Separate instrumentation APKs need stable dependency APIs; optimize other app code normally.
# No rules here apply to ordinary Debug/Release or the ordinary R8 qualification probe.
-keep,allowobfuscation class network.orion.app.OrionApplication { *; }
-keep,allowobfuscation class network.orion.app.OrionFoundationRuntime { *; }
-keep,allowobfuscation class network.orion.app.tasks.OrionTaskKernel { *; }
-keep,allowobfuscation class network.orion.app.tasks.OrionTaskAdmissionOwner** { *; }
-keep,allowobfuscation class network.orion.app.tasks.OrionTaskQuery { *; }
-keep,allowobfuscation class network.orion.app.ai.OrionCortexClient { *; }
-keep,allowobfuscation class network.orion.app.chat.** { *; }
-keep,allowobfuscation class network.orion.core.orchestrator.chat.** { *; }
-keep,allowobfuscation class network.orion.core.resources.** { *; }
-keep,allowobfuscation class network.orion.core.time.** { *; }
-keep,allowobfuscation class network.orion.core.queries.tasks.** { *; }
-keep,allowobfuscation class network.orion.core.commands.tasks.** { *; }
-keep,allowobfuscation class network.orion.data.database.** { *; }
-keep,allowobfuscation class network.orion.data.repository.models.** { *; }

# The test APK shares these dependencies with its target, so AGP removes duplicate
# definitions from test dex. Retain their APIs in the target even when product
# call sites inline them (e.g. androidx.tracing.Trace and ComponentActivity.setContent).
-keep,allowobfuscation class androidx.** { *; }
-keep,allowobfuscation class kotlin.** { *; }
-keep,allowobfuscation class kotlinx.** { *; }
-keep,allowobfuscation class com.google.common.** { *; }
-keep,allowobfuscation class network.orion.core.** { *; }
-keep,allowobfuscation class network.orion.app.tasks.** { *; }
-keep,allowobfuscation class network.orion.feature.chat.** { *; }
