package network.orion.core.commands.notifications

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PostNotificationPreparationTest {
    private val id=UUID.fromString("00000000-0000-0000-0000-000000000001")
    private fun command(title:String="Café",body:String="Água\nPão")=PostNotification(id,title,body,id,id)
    private fun ready(c:PostNotification)=(OrionPostNotificationPreparer.prepare(c) as PostNotificationPreparation.Ready).notification
    private fun reject(c:PostNotification)=(OrionPostNotificationPreparer.prepare(c) as PostNotificationPreparation.Rejected).reason
    @Test fun canonicalSchemaBindsExactTitleBodyAndRequestWithNewlinesEscaped() {
        assertEquals("""{"requestId":"00000000-0000-0000-0000-000000000001","title":"Café","body":"Água\nPão"}""",ready(command()).canonicalNormalizedArguments)
        assertEquals("notification/$id",ready(command()).normalizedTarget)
        val quote=ready(command("a\"b","x\\y😀"))
        assertEquals("""{"requestId":"$id","title":"a\"b","body":"x\\y😀"}""",quote.canonicalNormalizedArguments)
    }
    @Test fun boundedRawAndNormalizedInputsRejectOverflowBeforeAllocatingCanonicalData() {
        assertEquals(256,ready(command("a".repeat(256))).title.length)
        assertEquals(4096,ready(command(body="a".repeat(4096))).body.length)
        for(c in listOf(command("a".repeat(257)),command(body="é".repeat(2049)),command("😀".repeat(65)),command("\u0344".repeat(128))))
            assertEquals(PostNotificationRejection.TOO_LARGE,reject(c))
        assertEquals("😀".repeat(64),ready(command("😀".repeat(64))).title)
    }
    @Test fun malformedUnicodeAndForbiddenControlsDoNotReachPolicy() {
        for(s in listOf("\ud800","x\udc00","\ud800x")) {
            assertEquals(PostNotificationRejection.INVALID_UNICODE,reject(command(s)))
            assertEquals(PostNotificationRejection.INVALID_UNICODE,reject(command(body=s)))
        }
        for(c in (0..31).map(Int::toChar)+(127..159).map(Int::toChar)+listOf('\u2028','\u2029')) {
            assertEquals(PostNotificationRejection.INVALID_CHARACTER,reject(command("a${c}b")))
            if(c!='\n')assertEquals(PostNotificationRejection.INVALID_CHARACTER,reject(command(body="a${c}b")))
        }
    }
    @Test fun nfcAndEdgeTrimDoNotAlterCaseCompatibilityOrInteriorText() {
        assertEquals(ready(command("é","é")).canonicalNormalizedArguments,ready(command(" e\u0301 "," e\u0301 ")).canonicalNormalizedArguments)
        assertNotEquals(ready(command("X")).canonicalNormalizedArguments,ready(command("x")).canonicalNormalizedArguments)
        assertEquals("a  b",ready(command(body="a  b")).body)
        assertEquals(PostNotificationRejection.BLANK_TITLE,reject(command(" \u00a0")))
        assertEquals(PostNotificationRejection.BLANK_BODY,reject(command(body="\n \u00a0")))
    }
    @Test fun replayTraceIsNotMaterialButEveryDisplayedFieldAndTargetIs() {
        val c=command();val original=ready(c)
        assertEquals(original.canonicalNormalizedArguments,ready(c.copy(idempotencyKey=UUID.randomUUID(),correlationId=UUID.randomUUID())).canonicalNormalizedArguments)
        for(other in listOf(c.copy(requestId=UUID.randomUUID()),c.copy(title="Other"),c.copy(body="Other")))
            assertNotEquals(original.canonicalNormalizedArguments,ready(other).canonicalNormalizedArguments)
    }
    @Test fun diagnosticsDoNotRevealAnyContent() {
        val request=command("secret-title","secret-body");val result=OrionPostNotificationPreparer.prepare(request) as PostNotificationPreparation.Ready
        for(value in listOf(request,result,result.notification)) {
            assertFalse(value.toString().contains(request.title));assertFalse(value.toString().contains(request.body))
        }
    }
}
