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
import kotlinx.coroutines.flow.first
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

/** Used by the owned-emulator harness after Android grants and revokes the test role. */
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
        withTimeout(25_000) {
            while (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) != selected ||
                VoiceInteractionService.isActiveService(app, interactor) != selected) delay(100)
        }

        val expected = if (selected) OrionAssistantRoleState.ACTIVE else OrionAssistantRoleState.NOT_SELECTED
        val runtime = checkNotNull(app.mainRuntime)
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(10_000) { runtime.assistantRoleState.first { it == expected } }
            assertEquals(selected, runtime.capabilities.isAvailable(OrionAssistantRole.capability))
            awaitAssistantFixtureCore(app)
            if (selected) verifySystemSessionLeavesCoreStateUnchanged(app)
        }
    }

    private suspend fun verifySystemSessionLeavesCoreStateUnchanged(app: OrionApplication) {
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val appOpsCommand = "cmd appops get ${app.packageName} RECORD_AUDIO"
        val appOpsBefore = automation.output(appOpsCommand)
        assertTrue("Expected an Android microphone AppOp observation.", appOpsBefore.isNotBlank())
        val before = counts(app)
        try {
            withTimeout(20_000) {
                while (automation.output("cmd voiceinteraction show").isNotBlank()) delay(250)
                while (automation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("Falar")
                        .orEmpty()
                        .none { it.packageName == app.packageName && it.text == "Falar" }) {
                    delay(100)
                }
            }
            awaitAssistantFixtureCore(app)
            assertEquals("Showing the framework session changed durable Core state.", before, counts(app))
            assertTrue("Showing the framework session changed microphone AppOps.",
                appOpsBefore == automation.output(appOpsCommand))
        } finally {
            automation.output("cmd voiceinteraction hide")
        }
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
