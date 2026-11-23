package network.orion.app.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import java.security.MessageDigest
import network.orion.app.BuildConfig
import network.orion.app.chat.QWEN_MODEL_ID
import network.orion.app.resources.OrionCortexResourceFactBuffer
import network.orion.app.resources.OrionVoiceMemoryCollector
import network.orion.core.resources.InferenceModelProfile
import network.orion.core.resources.OrionInferenceResourcePlan
import network.orion.core.time.OrionClock
import network.orion.data.database.OrionDatabase
import network.orion.data.repository.models.OrionExpectedModelFile
import network.orion.data.repository.models.OrionModelImportCoordinator

internal enum class OrionS21ModelStatus { CHECKING, READY, MISSING, REJECTED }

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
    private val timing = OrionCortexResourceTiming(2_000, 60_000)
    // Allows light role startup after the inventory fence. The kernel reservation
    // prevents Whisper and Cortex from owning their native workspaces together.
    private const val VOICE_STARTUP_RESERVE_BYTES = 256 * MIB
    private val plan = OrionInferenceResourcePlan(
        InferenceModelProfile(QWEN_MODEL_ID, 544 * MIB), "S21_DEVELOPMENT_PREVIEW",
        2, 1024, 32, 0, 1_152 * MIB, 1_792 * MIB, 1_024 * MIB, 6, PowerManager.THERMAL_STATUS_LIGHT,
    )

    fun createOwner(app: Context, importer: OrionModelImportCoordinator, db: OrionDatabase,
        clock: OrionClock, ready: () -> Boolean): OrionCortexRuntimeOwner? =
        if (!enabled) null else OrionCortexRuntimeOwner(app, importer, db, clock,
            qualifiedPlans = listOf(plan), timing = timing, coreReady = ready,
            resourceFacts = resourceFacts,
            voiceMemory = OrionVoiceMemoryCollector(app, clock, VOICE_STARTUP_RESERVE_BYTES))
}
