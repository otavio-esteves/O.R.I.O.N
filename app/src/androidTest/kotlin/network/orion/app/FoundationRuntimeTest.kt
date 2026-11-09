package network.orion.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.core.health.*
import network.orion.core.ipc.InlineIpcPayloadPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationRuntimeTest {
    private val app: OrionApplication get() = ApplicationProvider.getApplicationContext()

    @Test fun storageOpensAndHealthPublishesOnlyAfterBothStoresLoad() = runBlocking {
        val runtime = checkNotNull(app.mainRuntime)
        val report = withTimeout(30_000) { runtime.health.first { it.core.readiness.isStartupBarrierOpen } }
        assertEquals(OrionHealthStatus.HEALTHY, report.subsystems[OrionSubsystem.DATABASE])
        assertEquals(OrionHealthStatus.HEALTHY, report.subsystems[OrionSubsystem.CONFIGURATION])
        assertEquals(OrionHealthStatus.HEALTHY, report.subsystems[OrionSubsystem.FIELD_KEYS])
        assertEquals(OrionHealthStatus.UNAVAILABLE, report.subsystems[OrionSubsystem.CORTEX])
        assertEquals(OrionHealthStatus.UNAVAILABLE, report.subsystems[OrionSubsystem.VOICE])
        assertEquals(1, runtime.databaseFormatVersion)
        assertEquals(6, runtime.databaseSchemaVersion)
        assertEquals(1, runtime.configurationFormatVersion)
        assertNotNull(report.lastRecovery)
    }

    @Test fun taskCapabilityRequiresActualRecoveredBarrierAndDoesNotGrantUnboundExecution() = runBlocking {
        val runtime = checkNotNull(app.mainRuntime)
        runtime.recover()
        withTimeout(30_000) { while (!runtime.taskStartupBarrier.isOpen()) kotlinx.coroutines.delay(10) }
        assertNotNull(runtime.taskKernel)
        assertNotNull(runtime.reminderKernel)
        assertNotNull(runtime.timerKernel)
        assertNotNull(runtime.memoryKernel)
        assertNotNull(runtime.notificationKernel)
        val localCapabilities = listOf(
            network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor.WRITE_CAPABILITY,
            network.orion.core.skills.metadata.reminders.OrionCreateReminderDescriptor.WRITE_CAPABILITY,
            network.orion.core.skills.metadata.timers.OrionCreateTimerDescriptor.WRITE_CAPABILITY,
            network.orion.core.skills.metadata.memories.OrionCreateMemoryDescriptor.WRITE_CAPABILITY,
        )
        localCapabilities.forEach { assertTrue(runtime.capabilities.isAvailable(it)) }
        val notificationCapability = network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor.WRITE_CAPABILITY
        assertEquals(runtime.notificationKernel!!.poster.availability() ==
            network.orion.core.orchestrator.notifications.NotificationAvailability.AVAILABLE,
            runtime.capabilities.isAvailable(notificationCapability))
        assertEquals(CoreReadiness.DEGRADED_SAFE, runtime.health.value.core.readiness)
        assertTrue(runtime.capabilities.isAvailable(network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor.WRITE_CAPABILITY))
        assertFalse(runtime.capabilities.isAvailable(network.orion.core.model.Capability("orion_unknown")))
        assertEquals(network.orion.core.actions.ActionExecutionResult.UNAVAILABLE, runtime.actions.execute(java.util.UUID.randomUUID()))
        runtime.taskStartupBarrier.beginRecovery()
        localCapabilities.forEach { assertFalse(runtime.capabilities.isAvailable(it)) }
        assertFalse(runtime.capabilities.isAvailable(notificationCapability))
        assertFalse(runtime.capabilities.isAvailable(network.orion.core.skills.metadata.tasks.OrionCreateTaskDescriptor.WRITE_CAPABILITY))
        assertEquals(CoreReadiness.DEGRADED_SAFE, runtime.recover())
        assertTrue(runtime.taskStartupBarrier.isOpen())
        localCapabilities.forEach { assertTrue(runtime.capabilities.isAvailable(it)) }
    }

    @Suppress("DEPRECATION")
    @Test fun componentSurfaceKeepsCorePrivateAndToolingPermissionProtected() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS)
        info.services.orEmpty().forEach {
            if (it.exported) {
                assertEquals("androidx.work.impl.background.systemjob.SystemJobService", it.name)
                assertEquals("android.permission.BIND_JOB_SERVICE", it.permission)
            }
        }
        info.providers.orEmpty().forEach { assertFalse(it.name, it.exported) }
        info.activities.orEmpty().filter { it.exported }.forEach {
            assertEquals(MainActivity::class.java.name, it.name)
        }
        info.receivers.orEmpty().filter { it.exported }.forEach {
            assertEquals("androidx.profileinstaller.ProfileInstallReceiver", it.name)
            assertEquals("android.permission.DUMP", it.permission)
        }
    }

    @Test fun backupAndDeviceTransferExcludeEveryStorageDomain() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val expected = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        val sections = mutableMapOf<String, MutableSet<String>>()
        app.resources.getXml(R.xml.orion_data_extraction_rules).use { parser ->
            var section: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (parser.name in setOf("cloud-backup", "device-transfer")) {
                        section = parser.name
                        sections[parser.name] = mutableSetOf()
                    } else if (parser.name == "exclude" && section != null) {
                        assertEquals(".", parser.getAttributeValue(null, "path"))
                        sections.getValue(section).add(parser.getAttributeValue(null, "domain"))
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == section) {
                    section = null
                }
                parser.next()
            }
        }
        assertEquals(setOf("cloud-backup", "device-transfer"), sections.keys)
        sections.values.forEach { assertEquals(expected, it) }
    }

    @Test fun allAuxiliaryProcessesRejectMainStorageAndNeverCreateMainGraph() {
        for ((service, expectedRole) in listOf(
            "OrionAiBootstrapProbeService" to "AI",
            "OrionVoiceBootstrapProbeService" to "VOICE_CONTROL",
            "OrionVoiceSessionBootstrapProbeService" to "VOICE_SESSION",
        )) {
            val reply = CountDownLatch(1)
            val invalidReply = CountDownLatch(1)
            var evidence: Bundle? = null
            val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
                evidence = message.data.apply {
                    putInt("protocolMajor", message.arg1)
                    putInt("protocolMinor", message.arg2)
                }
                reply.countDown()
                true
            })
            val rejectedReceiver = Messenger(Handler(Looper.getMainLooper()) {
                invalidReply.countDown()
                true
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val remote = Messenger(binder)
                    remote.send(Message.obtain(null, 1, 2, 0).apply { replyTo = rejectedReceiver })
                    remote.send(Message.obtain(null, 1, 1, 0).apply {
                        replyTo = rejectedReceiver
                        data = Bundle().apply {
                            putByteArray("unsupported", ByteArray(InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES + 1))
                        }
                    })
                    val request = Message.obtain(null, 1, 1, 1)
                    request.replyTo = receiver
                    remote.send(request)
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            val intent = Intent().setComponent(ComponentName(app, "network.orion.app.testing.$service"))
            assertTrue(app.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            try {
                assertTrue("No bootstrap evidence for $expectedRole", reply.await(20, TimeUnit.SECONDS))
                val result = checkNotNull(evidence)
                assertFalse("Invalid protocol/payload received a report", invalidReply.await(1, TimeUnit.SECONDS))
                assertEquals(1, result.getInt("schemaVersion"))
                assertEquals(1, result.getInt("protocolMajor"))
                assertEquals(0, result.getInt("protocolMinor"))
                assertEquals(expectedRole, result.getString("role"))
                assertFalse(result.getBoolean("mainGraphCreated"))
                assertFalse(result.getBoolean("workManagerInitialized"))
                assertTrue(result.getBoolean("databaseDenied"))
                assertTrue(result.getBoolean("datastoreDenied"))
            } finally { app.unbindService(connection) }
        }
    }
}
