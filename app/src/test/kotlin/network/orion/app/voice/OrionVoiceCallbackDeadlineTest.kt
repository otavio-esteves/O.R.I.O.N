package network.orion.app.voice

import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import network.orion.voice.ipc.ORION_VOICE_CALLBACK_TIMEOUT_MILLIS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionVoiceCallbackDeadlineTest {
    @Test fun subscriptionRemainsOpenForLongTurnsAndExpiresAfterTheReplyWindow() {
        val time = TestTimeSource()
        val subscription = OrionCoreGatewayService.Subscriber(UUID.randomUUID(), time)

        time += 16.seconds
        assertFalse(subscription.expired())
        time += (ORION_VOICE_CALLBACK_TIMEOUT_MILLIS - 16_001).milliseconds
        assertFalse(subscription.expired())
        time += 1.milliseconds
        assertTrue(subscription.expired())
    }
}
