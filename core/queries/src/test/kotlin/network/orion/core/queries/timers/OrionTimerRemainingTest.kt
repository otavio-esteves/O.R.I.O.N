package network.orion.core.queries.timers

import org.junit.Assert.*
import org.junit.Test

class OrionTimerRemainingTest {
    private fun read(boot:Int?=7, elapsed:Long=200, wall:Long=10_000) = OrionTimerRemaining.read(1000, 11_000, 7,100,boot,elapsed,wall)
    @Test fun sameBootDurationIgnoresForwardAndBackwardWallJumps() {
        for(wall in listOf(Long.MIN_VALUE,0L,10_000L,Long.MAX_VALUE)) {
            val r=read(wall=wall)
            assertEquals(900L,r.remainingMillis);assertEquals(TimerClockBasis.MONOTONIC,r.basis)
        }
    }
    @Test fun deadlineEqualityAndElapsedDurationReturnZeroWithoutMutatingAnything() {
        for(elapsed in listOf(1100L,1101L,Long.MAX_VALUE)) assertEquals(0L,read(elapsed=elapsed).remainingMillis)
    }
    @Test fun persistedMonotonicReferenceIsNeverComparedAcrossReboots() {
        val r=read(boot=8,elapsed=0,wall=10_500)
        assertEquals(500L,r.remainingMillis);assertEquals(TimerClockBasis.WALL_FALLBACK,r.basis)
    }
    @Test fun missingBootIdentityUsesExplicitWallFallback() {
        val r=read(boot=null,wall=11_000)
        assertEquals(0L,r.remainingMillis);assertEquals(TimerClockBasis.WALL_FALLBACK,r.basis)
    }
    @Test fun sameBootRegressionDoesNotPretendWallTimeIsAnAccurateDuration() {
        val r=read(elapsed=99)
        assertNull(r.remainingMillis);assertEquals(TimerClockBasis.UNAVAILABLE,r.basis)
    }
    @Test fun wallFallbackRollbackAndArithmeticOverflowAreBoundedByOriginalDuration() {
        for(wall in listOf(Long.MIN_VALUE,-1L,0L,9999L)) assertEquals(1000L,read(boot=8,wall=wall).remainingMillis)
    }
    @Test fun negativeReferencesAndNonPositiveDurationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OrionTimerRemaining.read(0,0,1,0,1,0,0) }
        assertThrows(IllegalArgumentException::class.java) { OrionTimerRemaining.read(1,0,-1,0,1,0,0) }
    }
}
