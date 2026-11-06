package network.orion.app.device

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import network.orion.app.MainActivity
import network.orion.core.queries.activity.*
import network.orion.core.queries.device.*
import network.orion.feature.activity.ActivityScreen
import network.orion.feature.device.DeviceScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OrionReadOnlyScreenTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun deviceUnknownStateAndExplicitRefreshUseOnlyTheReadPort() {
        val available=AtomicBoolean(false)
        val reads=AtomicInteger()
        val gateway=DeviceInformationGateway {
            reads.incrementAndGet()
            if (!available.get()) null else DeviceInformation(Instant.parse("2026-11-05T12:34:56Z"),ZoneId.of("UTC"),72,
                ChargingState.CHARGING,PowerConnection.USB,"Test","Device",35,false,true,true)
        }
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { MaterialTheme { DeviceScreen(gateway) } } }
        compose.waitUntil(10_000) { reads.get()>=1 }
        compose.onNodeWithText("Informações indisponíveis. Tente atualizar.").assertExists()
        available.set(true)
        compose.onNodeWithText("Atualizar dispositivo").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Carga: 72%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Estado: carregando").assertExists()
        compose.onNodeWithText("Conexão: USB").assertExists()
        compose.onNodeWithText("Informações indisponíveis. Tente atualizar.").assertDoesNotExist()
        assertEquals(2,reads.get())
    }
    @Test fun activityLoadsBoundedPagesAndRefreshRestartsAtTheNewestCursor() {
        val cursor=ActivityCursor(1000,"E:00000000-0000-0000-0000-000000000001")
        val reads=CopyOnWriteArrayList<GetActivity>()
        val gateway=ActivityGateway { request ->
            reads += request
            if (request.after==null) ActivityPage(listOf(ActivityEntry(ActivityKind.DOMAIN_EVENT,"TASK_CREATED","PENDING",Instant.ofEpochMilli(1000))),cursor)
            else ActivityPage(listOf(ActivityEntry(ActivityKind.DOMAIN_EVENT,"TIMER_CREATED","PENDING",Instant.ofEpochMilli(900))),null)
        }
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { MaterialTheme { ActivityScreen(gateway) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tarefa criada").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Carregar mais atividade").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Timer criado").fetchSemanticsNodes().isNotEmpty() }
        assertSame(cursor,reads[1].after)
        compose.onNodeWithText("Atualizar atividade").performScrollTo().performClick()
        compose.waitUntil(10_000) { reads.size>=3 }
        assertNull(reads.last().after)
        compose.onNodeWithText("Timer criado").assertDoesNotExist()
        compose.onNodeWithText("Tarefa criada").assertExists()
    }
}
