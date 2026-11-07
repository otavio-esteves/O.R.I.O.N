package network.orion.app.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.orion.app.OrionApplication
import network.orion.scheduler.alarms.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android artifacts only; no reminder rows, delivery or hardware qualification. */
@RunWith(AndroidJUnit4::class)
class OrionSchedulerPlatformTest {
    private val app: OrionApplication get() = ApplicationProvider.getApplicationContext()
    private val receiver get() = ComponentName(app.packageName,
        "network.orion.app.scheduling.OrionReminderAlarmReceiver")
    private val future = Instant.parse("2099-01-01T00:00:00Z")
    private val approximate = AlarmRequirements(false, true, true)

    private fun pending(token: OrionAlarmToken, component: ComponentName = receiver): PendingIntent? =
        PendingIntent.getBroadcast(app, token.requestCode,
            Intent(AndroidOrionAlarmBackend.ACTION_REMINDER_DUE).setComponent(component)
                .setData(Uri.parse(token.identity)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    @Test fun approximateRegistrationCreatesImmutableExplicitIdentityAndCancellationRemovesIt() {
        val token = OrionAlarmToken(UUID.randomUUID(), 1, 31_001)
        val alarms = OrionReminderAlarms(AndroidOrionAlarmBackend(app, receiver))
        assertNull(pending(token))
        try {
            val registered = alarms.schedule(token, future, approximate) as AlarmRegistrationResult.Registered
            assertEquals(AlarmPrecision.APPROXIMATE, registered.selection.precision)
            val operation = checkNotNull(pending(token))
            assertTrue(operation.isImmutable)
            assertTrue(operation.isBroadcast)
            assertEquals(app.packageName, operation.creatorPackage)
            // The lookup includes the actual explicit receiver, action and generation URI.
            assertNull(pending(token, ComponentName(app.packageName, "network.orion.app.UnknownReceiver")))
            assertNull(pending(token.copy(generation = 2)))
            assertTrue(alarms.cancel(token))
            assertNull(pending(token))
        } finally {
            alarms.cancel(token)
        }
    }

    @Test fun replayReplacesSameArtifactWhileStaleCancellationCannotCancelNewGeneration() {
        val token = OrionAlarmToken(UUID.randomUUID(), 8, 31_002)
        val newer = token.copy(generation = 9)
        val alarms = OrionReminderAlarms(AndroidOrionAlarmBackend(app, receiver))
        try {
            assertTrue(alarms.schedule(token, future, approximate) is AlarmRegistrationResult.Registered)
            val original = checkNotNull(pending(token))
            assertTrue(alarms.schedule(token, future.plusSeconds(60), approximate) is AlarmRegistrationResult.Registered)
            assertEquals(original, pending(token))
            assertTrue(alarms.schedule(newer, future, approximate) is AlarmRegistrationResult.Registered)
            assertNotEquals(original, pending(newer))
            assertTrue(alarms.cancel(token))
            assertNull(pending(token))
            assertNotNull(pending(newer))
        } finally {
            alarms.cancel(token)
            alarms.cancel(newer)
        }
    }

    @Test fun exactCapabilityReflectsCurrentPlatformAccessWithoutChangingPermission() {
        val manager = checkNotNull(app.getSystemService(AlarmManager::class.java))
        val expected = if (manager.canScheduleExactAlarms()) ExactAlarmAvailability.AVAILABLE else ExactAlarmAvailability.DENIED
        assertEquals(expected, AndroidOrionAlarmBackend(app, receiver).availability())
    }

    @Suppress("DEPRECATION")
    @Test fun schedulerComponentsArePrivateMainOwnedAndAutomaticWorkInitializerIsAbsent() {
        val manager = app.packageManager
        for (component in listOf(receiver, ComponentName(app.packageName,
            "network.orion.app.scheduling.OrionReconciliationReceiver"))) {
            val info = manager.getReceiverInfo(component, PackageManager.GET_META_DATA)
            assertFalse(info.name, info.exported)
            assertFalse(info.name, info.directBootAware)
            assertEquals(info.name, app.packageName, info.processName)
        }
        val info = manager.getPackageInfo(app.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.USE_EXACT_ALARM"))
        info.providers.orEmpty().forEach {
            assertFalse(it.name, it.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true)
        }
        assertFalse(info.receivers.orEmpty().any { it.name == "androidx.work.impl.diagnostics.DiagnosticsReceiver" })
    }

    @Test fun recoveredMainOwnsInitializedWorkManager() = runBlocking {
        val runtime = checkNotNull(app.mainRuntime)
        withTimeout(30_000) { runtime.health.first { it.core.readiness.isStartupBarrierOpen } }
        assertTrue(WorkManager.isInitialized())
        assertNotNull(WorkManager.getInstance(app))
    }
}
