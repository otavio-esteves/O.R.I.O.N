package network.orion.app.resources

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import network.orion.core.time.AndroidOrionClock
import network.orion.voice.service.OrionVoiceControlService
import network.orion.voice.service.OrionVoiceSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionVoiceMemoryAndroidTest {
    @Test fun mainCollectsActualNativeInclusivePssFromBothVoiceRoles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val connected = CountDownLatch(2)
        val connections = listOf(OrionVoiceControlService::class.java, OrionVoiceSessionService::class.java)
            .map { component ->
                Intent(app, component) to object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) = connected.countDown()
                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
            }
        val attempted = mutableListOf<ServiceConnection>()
        try {
            for ((intent, connection) in connections) {
                attempted += connection
                check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            }
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val clock = AndroidOrionClock(app.contentResolver)
            val collector = OrionVoiceMemoryCollector(app, clock, 1)
            val name = app.packageName
            val aiName = "$name:ai"
            // This test runs the two voice endpoints. The AI receipt is only a fixture
            // for checking the collector's join, and gives no inference permit.
            val evidence = checkNotNull(collector.collect {
                val now = clock.monotonicNow()
                OrionAppMemoryEvidence(now, true, setOf(name, aiName),
                    listOf(OrionProcessMemoryReading(aiName, now, 1, true)))
            })
            val voice = evidence.readings.filter { it.processName != aiName }
            assertEquals(setOf("$name:voice", "$name:voice_session"), voice.map { it.processName }.toSet())
            val now = clock.monotonicNow()
            assertTrue(voice.all { it.nativeIncluded && (it.totalPssBytes ?: 0) > 0 && now.elapsedSince(it.sampledAt) != null })
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putLong("voiceControlPssBytes", checkNotNull(voice.single { it.processName == "$name:voice" }.totalPssBytes))
                putLong("voiceSessionPssBytes", checkNotNull(voice.single { it.processName == "$name:voice_session" }.totalPssBytes))
            })
        } finally {
            for (connection in attempted) runCatching { app.unbindService(connection) }
        }
    }
}
