package network.orion.app.resources

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ApplicationExitInfo.REASON_LOW_MEMORY
import android.app.ApplicationExitInfo.REASON_OTHER
import android.content.ComponentCallbacks2
import network.orion.core.resources.OrionMemoryPressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class OrionAndroidMemoryPressureTest {
    @Test fun onlySmallCachedLowMemorySamplesAreReclaims() {
        val limit = 96 * 1024L
        val observedRss = 60 * 1024L
        assertTrue(isCachedLowMemoryExit(REASON_LOW_MEMORY, IMPORTANCE_CACHED, observedRss, 0, limit))
        assertFalse(isCachedLowMemoryExit(REASON_LOW_MEMORY, IMPORTANCE_FOREGROUND, observedRss, 0, limit))
        assertFalse(isCachedLowMemoryExit(REASON_LOW_MEMORY, IMPORTANCE_CACHED, 0, 0, limit))
        assertFalse(isCachedLowMemoryExit(REASON_LOW_MEMORY, IMPORTANCE_CACHED, limit + 1, 0, limit))
        assertFalse(isCachedLowMemoryExit(REASON_OTHER, IMPORTANCE_CACHED, observedRss, 0, limit))
    }

    @Test fun uiVisibilityAloneIsNotMemoryPressure() {
        assertEquals(OrionMemoryPressure.NONE, classifyAndroidMemoryPressure(false, 0))
        assertEquals(OrionMemoryPressure.NONE,
            classifyAndroidMemoryPressure(false, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
    }

    @Test fun runningAndCachedPressureLevelsStayDistinctFromUiHidden() {
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 14,
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, ComponentCallbacks2.TRIM_MEMORY_MODERATE, 79))
            assertEquals("level $level", OrionMemoryPressure.MODERATE, classifyAndroidMemoryPressure(false, level))
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, 19, ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
            assertEquals("level $level", OrionMemoryPressure.CRITICAL, classifyAndroidMemoryPressure(false, level))
    }

    @Test fun unknownAndMissingEvidenceNeverBecomeHealthy() {
        assertEquals(OrionMemoryPressure.UNKNOWN, classifyAndroidMemoryPressure(null, 0))
        assertEquals(OrionMemoryPressure.UNKNOWN, classifyAndroidMemoryPressure(false, null))
        for (level in listOf(-1, 1, 4, 21, 39, 81, Int.MAX_VALUE))
            assertEquals("level $level", OrionMemoryPressure.UNKNOWN, classifyAndroidMemoryPressure(false, level))
    }

    @Test fun actualSystemLowMemoryDominatesMissingTrimAndUiVisibility() {
        for (level in listOf(null, -1, 0, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, Int.MAX_VALUE))
            assertEquals(OrionMemoryPressure.CRITICAL, classifyAndroidMemoryPressure(true, level))
    }
}
