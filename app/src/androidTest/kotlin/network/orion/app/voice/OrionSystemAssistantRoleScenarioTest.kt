package network.orion.app.voice

import android.Manifest
import android.app.UiAutomation
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.service.voice.VoiceInteractionService
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import network.orion.app.MainActivity
import network.orion.app.OrionApplication
import network.orion.core.health.OrionAssistantRoleState
import network.orion.data.database.OrionDatabaseFactory
import network.orion.voice.service.OrionVoiceInteractionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The owned-emulator harness exercises the real role transition without restarting MAIN. */
@RunWith(AndroidJUnit4::class)
class OrionSystemAssistantRoleScenarioTest {
    @Test fun platformRoleAndSessionMatchExpectedState() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("orion_assistant_role_stage")
        assumeTrue(stage in setOf("selected", "revoked"))
        val selected = stage == "selected"
        val app = ApplicationProvider.getApplicationContext<OrionApplication>()
        val roleManager = checkNotNull(app.getSystemService(RoleManager::class.java))
        val interactor = ComponentName(app, OrionVoiceInteractionService::class.java)
        assertTrue(roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
        val runtime = checkNotNull(app.mainRuntime)
        suspend fun observeRole(held: Boolean) {
            withTimeout(25_000) {
                while (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) != held ||
                    VoiceInteractionService.isActiveService(app, interactor) != held) delay(100)
            }
            val expected = if (held) OrionAssistantRoleState.ACTIVE else OrionAssistantRoleState.NOT_SELECTED
            runtime.refreshAssistantRole()
            assertEquals(expected, runtime.assistantRoleState.value)
            assertEquals(held, runtime.capabilities.isAvailable(OrionAssistantRole.capability))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            observeRole(false)
            awaitAssistantFixtureCore(app)
            if (selected) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                assertEquals("1", automation.output("getprop ro.kernel.qemu"))
                assertEquals("0", automation.output("am get-current-user"))
                assertEquals("2000", automation.output("id -u"))
                assertEquals("", automation.output("cmd role get-role-holders --user 0 ${RoleManager.ROLE_ASSISTANT}"))
                withTimeout(20_000) {
                    var focused = false
                    while (!focused) {
                        scenario.onActivity { focused = it.hasWindowFocus() }
                        if (!focused) delay(100)
                    }
                }
                // The harness verifies ownership before launching this scenario. Do not restart the
                // instrumented package after selecting it, or Android will tear down the active service.
                automation.changeRole("add-role-holder", app.packageName)
                observeRole(true)
                verifySystemSessionLeavesCoreStateUnchanged(app, automation) {
                    automation.changeRole("remove-role-holder", app.packageName)
                    observeRole(false)
                }
            }
        }
    }

    private suspend fun verifySystemSessionLeavesCoreStateUnchanged(
        app: OrionApplication,
        automation: UiAutomation,
        revokeRole: suspend () -> Unit,
    ) {
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val appOpsCommand = "cmd appops get ${app.packageName} RECORD_AUDIO"
        val appOpsBefore = automation.output(appOpsCommand)
        assertTrue("Expected an Android microphone AppOp observation.", appOpsBefore.isNotBlank())
        val before = counts(app)
        try {
            val boundVoiceService = Regex("""mBound=true mService=(?!null)\S+""")
            withTimeout(20_000) {
                while (!boundVoiceService.containsMatchIn(automation.output("dumpsys voiceinteraction"))) {
                    delay(100)
                }
            }
            automation.output("input keyevent --longpress KEYCODE_POWER")
            withTimeout(20_000) {
                while (!automation.assistantShown(app.packageName)) delay(100)
            }
            awaitAssistantFixtureCore(app)
            assertEquals("Showing the framework session changed durable Core state.", before, counts(app))
            assertTrue("Showing the framework session changed microphone AppOps.",
                appOpsBefore == automation.output(appOpsCommand))
            revokeRole()
            withTimeout(15_000) {
                while (automation.assistantShown(app.packageName)) delay(100)
            }
            awaitAssistantFixtureCore(app)
        } finally {
            if (automation.assistantShown(app.packageName)) automation.output("input keyevent KEYCODE_BACK")
        }
    }

    private fun UiAutomation.assistantShown(packageName: String): Boolean =
        rootInActiveWindow?.findAccessibilityNodeInfosByText("Falar").orEmpty()
            .any { it.packageName == packageName && it.text == "Falar" }

    private fun UiAutomation.changeRole(operation: String, packageName: String) {
        output("cmd role $operation --user 0 ${RoleManager.ROLE_ASSISTANT} $packageName")
    }

    private suspend fun counts(app: OrionApplication): List<Int> = withContext(Dispatchers.IO) {
        val db = OrionDatabaseFactory.open(app)
        try {
            db.openHelper.readableDatabase.query("""
                SELECT
                    (SELECT COUNT(*) FROM orion_action_requests),
                    (SELECT COUNT(*) FROM orion_ingress_envelopes WHERE idempotencyScope = 'orion_voice_v1')
            """.trimIndent()).use { result ->
                check(result.moveToFirst())
                listOf(result.getInt(0), result.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    private fun UiAutomation.output(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).bufferedReader().use {
            it.readText().trim()
        }
}
