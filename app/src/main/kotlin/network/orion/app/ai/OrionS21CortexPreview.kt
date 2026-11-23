package network.orion.app.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.orion.app.BuildConfig
import network.orion.app.chat.QWEN_MODEL_ID
import network.orion.app.resources.OrionCortexResourceFactBuffer
import network.orion.app.resources.OrionVoiceMemoryCollector
import network.orion.core.resources.InferenceModelProfile
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.resources.OrionResourceBlock
import network.orion.core.resources.OrionRestartBlock
import network.orion.core.time.OrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionExpectedModelFile
import network.orion.data.repository.models.OrionModelImportCoordinator

internal enum class OrionS21ModelStatus { CHECKING, READY, MISSING, REJECTED }
internal const val S21_CORTEX_PREVIEW_TIER = "S21_DEVELOPMENT_PREVIEW"

/** Opt-in local build for one measured firmware. The normal build uses no candidate profile. */
internal object OrionS21CortexPreview {
    private const val MIB = 1_048_576L
    private const val FIRMWARE = "a91399143f406214bc34b0a0980205b4b231fa171d38841ea912f240144c6fab"

    val enabled: Boolean by lazy {
        if (!BuildConfig.ORION_S21_CORTEX_PREVIEW || Build.MODEL != "SM-G991B" ||
            Build.VERSION.SDK_INT != 35 || Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") false
        else runCatching {
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(Build.FINGERPRINT.toByteArray()).joinToString("") { "%02x".format(it) }
            fingerprint == FIRMWARE && Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L
        }.getOrDefault(false)
    }

    val expectedModel = OrionExpectedModelFile(QWEN_MODEL_ID, 491_400_032L)
    val resourceFacts by lazy { OrionCortexResourceFactBuffer(12) }
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    fun reportRestart(block: OrionRestartBlock?) {
        if (block == null) return
        mutableNotice.value = when (block) {
            OrionRestartBlock.COOLDOWN -> "Cortex em pausa de proteção. Aguardando para gerar a resposta."
            OrionRestartBlock.FALLBACK_EXHAUSTED -> "O perfil local foi suspenso após pressão de memória."
            else -> "O Cortex aguarda dados confiáveis de reinício."
        }
    }

    fun reportResource(block: OrionResourceBlock?) {
        mutableNotice.value = when (block) {
            null -> null
            OrionResourceBlock.BACKGROUND -> "Mantenha o O.R.I.O.N. em primeiro plano durante a resposta."
            OrionResourceBlock.THERMAL -> "O aparelho está quente demais para gerar uma resposta agora."
            OrionResourceBlock.BATTERY, OrionResourceBlock.POWER_SAVER -> "A política de bateria pausou a geração local."
            OrionResourceBlock.MEMORY_PRESSURE, OrionResourceBlock.MEMORY_BUDGET -> "O Cortex aguarda mais memória livre no aparelho."
            OrionResourceBlock.MEMORY_EXIT, OrionResourceBlock.RESOURCE_EXIT -> "O Cortex está em pausa de proteção."
            else -> "O Cortex aguarda uma medição confiável dos recursos do aparelho."
        }
    }
    private val timing = OrionCortexResourceTiming(2_000, 60_000)
    // Allows light role startup after the inventory fence. The kernel reservation
    // prevents Whisper and Cortex from owning their native workspaces together.
    private const val VOICE_STARTUP_RESERVE_BYTES = 256 * MIB
    // The S21 reported a cached :ai exit with 60 MiB RSS and no PSS sample.
    private const val CACHED_AI_RSS_LIMIT_KIB = 96 * 1024L
    private val plan = OrionInferenceResourcePlan(
        InferenceModelProfile(QWEN_MODEL_ID, 544 * MIB), S21_CORTEX_PREVIEW_TIER,
        2, 1024, 96, 0, 1_152 * MIB, 1_792 * MIB, 1_024 * MIB, 6, PowerManager.THERMAL_STATUS_LIGHT,
    )

    fun createOwner(app: Context, importer: OrionModelImportCoordinator, db: OrionDatabase,
        clock: OrionClock, ready: () -> Boolean): OrionCortexRuntimeOwner? =
        if (!enabled) null else OrionCortexRuntimeOwner(app, importer, db, clock,
            qualifiedPlans = listOf(plan), timing = timing, coreReady = ready,
            resourceFacts = resourceFacts,
            cachedAiRssLimitKiB = CACHED_AI_RSS_LIMIT_KIB,
            voiceMemory = OrionVoiceMemoryCollector(app, clock, VOICE_STARTUP_RESERVE_BYTES))
}
