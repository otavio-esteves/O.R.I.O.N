package network.orion.app.resources

import android.content.ComponentCallbacks2
import network.orion.core.resources.OrionMemoryPressure

/** UI_HIDDEN describes visibility, not memory pressure. Legacy cached levels are a separate band. */
@Suppress("DEPRECATION")
internal fun classifyAndroidMemoryPressure(systemLowMemory: Boolean?, lastTrimLevel: Int?): OrionMemoryPressure = when {
    systemLowMemory == true -> OrionMemoryPressure.CRITICAL
    systemLowMemory == null || lastTrimLevel == null -> OrionMemoryPressure.UNKNOWN
    lastTrimLevel == 0 || lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> OrionMemoryPressure.NONE
    lastTrimLevel in ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE until ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
        OrionMemoryPressure.MODERATE
    lastTrimLevel in ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
        lastTrimLevel == ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> OrionMemoryPressure.CRITICAL
    lastTrimLevel in ComponentCallbacks2.TRIM_MEMORY_BACKGROUND until ComponentCallbacks2.TRIM_MEMORY_COMPLETE ->
        OrionMemoryPressure.MODERATE
    else -> OrionMemoryPressure.UNKNOWN
}
