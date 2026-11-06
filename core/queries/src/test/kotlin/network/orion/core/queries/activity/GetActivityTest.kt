package network.orion.core.queries.activity

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GetActivityTest {
    @Test fun requestAndCursorAreStrictlyBounded() {
        for (limit in listOf(Int.MIN_VALUE,0,101,Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { GetActivity(limit=limit) }
        for (key in listOf("", "E:secret", "X:00000000-0000-0000-0000-000000000001", "E:0-0-0-0-1"))
            assertThrows(IllegalArgumentException::class.java) { ActivityCursor(0,key) }
        val cursor = ActivityCursor(Long.MIN_VALUE,"E:00000000-0000-0000-0000-000000000001")
        assertFalse(cursor.toString().contains("00000000"))
        assertEquals(100,GetActivity(cursor,100).limit)
    }
    @Test fun pagesDefensivelyCopyAndDoNotExposeCursorMetadataInDiagnostics() {
        val values = arrayListOf(ActivityEntry(ActivityKind.DOMAIN_EVENT,"TASK_CREATED","PENDING",Instant.EPOCH))
        val page=ActivityPage(values,null)
        values.clear(); assertEquals(1,page.items.size)
        assertThrows(UnsupportedOperationException::class.java) { (page.items as MutableList<ActivityEntry>).clear() }
        assertEquals("ActivityPage(<redacted>)",page.toString())
        assertEquals("ActivityEntry(<redacted>)",page.items.single().toString())
    }
}
