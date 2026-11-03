package network.orion.core.time

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant

/**
 * Android time boundary; no initializer, persistence, service or permission setup.
 *
 * Supply the application ContentResolver. Construction does not read any source.
 * Each monotonic reading synchronously reads BOOT_COUNT (public since API 24);
 * provider failure is explicit as null identity, without caching previous success.
 * Platform integration and OEM behavior require device qualification.
 */
class AndroidOrionClock internal constructor(
    private val wallTimeMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val bootCountSource: BootCountSource,
) : OrionClock {
    constructor(contentResolver: ContentResolver) : this(
        wallTimeMillis = System::currentTimeMillis,
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        bootCountSource = BootCountSource {
            Settings.Global.getString(contentResolver, Settings.Global.BOOT_COUNT)
        },
    )

    override fun wallNow(): Instant = Instant.ofEpochMilli(wallTimeMillis())

    override fun monotonicNow(): MonotonicInstant {
        val bootSessionId = bootCountSource.read()
        return MonotonicInstant(bootSessionId, elapsedRealtimeMillis())
    }
}

/** Only the settings-provider read is guarded; failure details are never logged. */
internal class BootCountSource(private val readSetting: () -> String?) {
    fun read(): BootSessionId? {
        val setting = try {
            readSetting()
        } catch (_: RuntimeException) {
            // Includes denied access and provider/Binder runtime failures. No stale fallback.
            return null
        }
        val count = setting?.toIntOrNull() ?: return null
        return if (count >= 0) BootSessionId(count) else null
    }
}
