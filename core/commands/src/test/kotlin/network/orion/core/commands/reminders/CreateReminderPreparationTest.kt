package network.orion.core.commands.reminders

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.UUID
import network.orion.core.commands.Command
import org.junit.Assert.*
import org.junit.Test

class CreateReminderPreparationTest {
    private val reminderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val zone = ZoneId.of("America/Sao_Paulo")

    private fun command(title: String = "Lembrar", at: Instant = Instant.EPOCH, zoneId: ZoneId = zone) =
        CreateReminder(reminderId, title, at, zoneId, key, correlation)

    private fun prepared(request: CreateReminder): ValidatedCreateReminder =
        (OrionCreateReminderPreparer.prepare(request) as CreateReminderPreparation.Ready).reminder

    private fun rejected(request: CreateReminder): CreateReminderRejection =
        (OrionCreateReminderPreparer.prepare(request) as CreateReminderPreparation.Rejected).reason

    @Test fun `validated preparation is separate from unavailable operation and retains explicit identities`() {
        val request = command()
        val typed: Command<CreateReminderResult> = request
        assertSame(request, typed)
        assertEquals(listOf(CreateReminderResult.UNAVAILABLE), CreateReminderResult.entries.toList())
        val reminder = prepared(request)
        assertEquals(reminderId, reminder.reminderId)
        assertEquals(key, reminder.idempotencyKey)
        assertEquals(correlation, reminder.correlationId)
        assertEquals("Lembrar", reminder.title)
        assertEquals(Instant.EPOCH, reminder.scheduledAt)
        assertEquals(0L, reminder.scheduledAtEpochMillis)
        assertEquals(zone, reminder.zoneId)
        assertEquals(1, reminder.argumentSchemaVersion)
        assertEquals("reminder/$reminderId", reminder.normalizedTarget)
    }

    @Test fun `empty and Unicode whitespace only titles are rejected`() {
        for (title in listOf("", " ", "\u00a0\u2003\u202f"))
            assertEquals(CreateReminderRejection.BLANK_TITLE, rejected(command(title)))
    }

    @Test fun `raw length guard rejects before trimming normalization and malformed suffix traversal`() {
        for (title in listOf(" ".repeat(1024) + "a", "a".repeat(1025) + '\ud800', "e\u0301".repeat(512) + "a"))
            assertEquals(CreateReminderRejection.TITLE_TOO_LARGE, rejected(command(title)))
    }

    @Test fun `UTF8 budget accepts exactly 1024 bytes for every scalar width`() {
        for (title in listOf("a".repeat(1024), "é".repeat(512), "€".repeat(341) + "a", "😀".repeat(256))) {
            assertEquals(1024, title.toByteArray(Charsets.UTF_8).size)
            assertEquals(title, prepared(command(title)).title)
        }
        for (title in listOf("a".repeat(1025), "é".repeat(513), "€".repeat(341) + "aa", "😀".repeat(257)))
            assertEquals(CreateReminderRejection.TITLE_TOO_LARGE, rejected(command(title)))
    }

    @Test fun `malformed surrogate pairs fail without replacement encoding`() {
        for (title in listOf("\ud800", "\udfff", "x\ud800y", "x\udc00y", "\ud800\ud800", "😀\udc00", " \ud800 "))
            assertEquals(CreateReminderRejection.INVALID_UNICODE, rejected(command(title)))
        assertEquals("a😀b", prepared(command("a😀b")).title)
    }

    @Test fun `NFC expansion is checked against the same byte budget`() {
        val raw = "\u0344".repeat(512)
        assertEquals(1024, raw.toByteArray(Charsets.UTF_8).size)
        assertEquals(CreateReminderRejection.TITLE_TOO_LARGE, rejected(command(raw)))
        val reminder = prepared(command("\u0344".repeat(256)))
        assertEquals("\u0308\u0301".repeat(256), reminder.title)
        assertEquals(1024, reminder.title.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun `trim and NFC yield stable material while preserving the raw request`() {
        val request = command(" e\u0301\u00a0")
        val reminder = prepared(request)
        assertEquals("é", reminder.title)
        assertEquals(prepared(command("é")).canonicalNormalizedArguments, reminder.canonicalNormalizedArguments)
        assertEquals(" e\u0301\u00a0", request.title)
        assertEquals(prepared(command("K")).canonicalNormalizedArguments,
            prepared(command("\u212a")).canonicalNormalizedArguments)
    }

    @Test fun `case compatibility forms and interior whitespace remain material`() {
        val variants = listOf("Lembrar", "lembrar", "Ｌembrar", "Lem brar", "Lem  brar")
        assertEquals(variants.size, variants.map { prepared(command(it)).canonicalNormalizedArguments }.distinct().size)
        assertEquals("ﬁ", prepared(command("ﬁ")).title)
        assertNotEquals(prepared(command("ﬁ")).canonicalNormalizedArguments,
            prepared(command("fi")).canonicalNormalizedArguments)
    }

    @Test fun `every C0 C1 control and Unicode line separator is rejected before edge trimming`() {
        val forbidden = (0..31).map(Int::toChar) + (127..159).map(Int::toChar) + listOf('\u2028', '\u2029')
        for (character in forbidden) {
            for (title in listOf("a${character}b", "${character}Lembrar", "Lembrar${character}"))
                assertEquals(CreateReminderRejection.INVALID_TITLE_CHARACTER, rejected(command(title)))
        }
    }

    @Test fun `canonical schema has fixed field order lowercase UUID and signed decimal epoch`() {
        val id = UUID.fromString("AABBCCDD-EEFF-0011-2233-445566778899")
        val reminder = prepared(CreateReminder(id, "Tomar café", Instant.ofEpochMilli(-1), zone, key, correlation))
        assertEquals("""{"reminderId":"aabbccdd-eeff-0011-2233-445566778899","title":"Tomar café","scheduledAtEpochMillis":-1,"zoneId":"America/Sao_Paulo"}""",
            reminder.canonicalNormalizedArguments)
    }

    @Test fun `canonical title escapes JSON syntax but preserves supplementary Unicode`() {
        val title = "x\"\\y/é😀"
        val reminder = prepared(command(title))
        assertEquals(title, reminder.title)
        assertEquals("""{"reminderId":"00000000-0000-0000-0000-000000000001","title":"x\"\\y/é😀","scheduledAtEpochMillis":0,"zoneId":"America/Sao_Paulo"}""",
            reminder.canonicalNormalizedArguments)
    }

    @Test fun `authority looking title stays escaped data`() {
        val reminder = prepared(command("x\",\"authorizationLevel\":\"DESTRUCTIVE"))
        assertEquals("""{"reminderId":"00000000-0000-0000-0000-000000000001","title":"x\",\"authorizationLevel\":\"DESTRUCTIVE","scheduledAtEpochMillis":0,"zoneId":"America/Sao_Paulo"}""",
            reminder.canonicalNormalizedArguments)
    }

    @Test fun `signed epoch millisecond endpoints round trip without truncation or overflow`() {
        for (millis in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1001L, -1000L, -999L, -1L,
            0L, 1L, 999L, 1000L, 1001L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            val instant = Instant.ofEpochMilli(millis)
            val reminder = prepared(command(at = instant))
            assertEquals(instant, reminder.scheduledAt)
            assertEquals(millis, reminder.scheduledAtEpochMillis)
            assertTrue(reminder.canonicalNormalizedArguments.contains("\"scheduledAtEpochMillis\":$millis,"))
        }
    }

    @Test fun `aligned instants outside signed epoch millisecond range fail deterministically`() {
        for (instant in listOf(Instant.MIN, Instant.MAX.truncatedTo(ChronoUnit.MILLIS),
            Instant.ofEpochMilli(Long.MIN_VALUE).minusMillis(1), Instant.ofEpochMilli(Long.MAX_VALUE).plusMillis(1)))
            assertEquals(CreateReminderRejection.TIME_OUT_OF_RANGE, rejected(command(at = instant)))
    }

    @Test fun `submillisecond instants fail on either side of epoch rather than silently rounding`() {
        for (instant in listOf(Instant.EPOCH.plusNanos(1), Instant.EPOCH.minusNanos(1),
            Instant.ofEpochMilli(1).plusNanos(1), Instant.ofEpochMilli(-1).minusNanos(1),
            Instant.ofEpochSecond(1, 999999999), Instant.ofEpochSecond(-1, 1)))
            assertEquals(CreateReminderRejection.UNSUPPORTED_TIME_PRECISION, rejected(command(at = instant)))
    }

    @Test fun `past instants remain valid preparation without a current wall clock`() {
        val reminder = prepared(command(at = Instant.parse("1900-01-01T00:00:00Z")))
        assertEquals(-2208988800000L, reminder.scheduledAtEpochMillis)
    }

    @Test fun `already resolved DST overlap instants stay distinct and are never reinterpreted`() {
        val overlapZone = ZoneId.of("America/New_York")
        val firstInstant = Instant.parse("2024-11-03T05:30:00Z")
        val secondInstant = Instant.parse("2024-11-03T06:30:00Z")
        assertEquals(firstInstant.atZone(overlapZone).toLocalDateTime(), secondInstant.atZone(overlapZone).toLocalDateTime())
        val first = prepared(command(at = firstInstant, zoneId = overlapZone))
        val second = prepared(command(at = secondInstant, zoneId = overlapZone))
        assertEquals(firstInstant, first.scheduledAt)
        assertEquals(secondInstant, second.scheduledAt)
        assertNotEquals(first.canonicalNormalizedArguments, second.canonicalNormalizedArguments)
    }

    @Test fun `explicit region aliases and fixed offset IDs are preserved as material`() {
        val aliases = listOf("UTC", "Etc/UTC", "Z", "+05:30", "US/Eastern", "America/New_York")
        val values = aliases.map { name ->
            val reminder = prepared(command(zoneId = ZoneId.of(name)))
            assertEquals(name, reminder.zoneId.id)
            assertTrue(reminder.canonicalNormalizedArguments.endsWith("\"zoneId\":\"$name\"}"))
            reminder.canonicalNormalizedArguments
        }
        assertEquals(aliases.size, values.distinct().size)
    }

    @Test fun `preparation never substitutes the host default time zone`() {
        val originalDefault = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val first = prepared(command()).canonicalNormalizedArguments
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(first, prepared(command()).canonicalNormalizedArguments)
        } finally {
            TimeZone.setDefault(originalDefault)
        }
    }

    @Test fun `every material field changes canonical arguments while trace and key do not`() {
        val request = command("Comprar pão", Instant.ofEpochMilli(1234))
        val original = prepared(request)
        val anotherId = UUID.fromString("00000000-0000-0000-0000-000000000004")
        for (variant in listOf(request.copy(reminderId = anotherId), request.copy(title = "Comprar leite"),
            request.copy(scheduledAt = Instant.ofEpochMilli(1235)), request.copy(zoneId = ZoneId.of("UTC"))))
            assertNotEquals(original.canonicalNormalizedArguments, prepared(variant).canonicalNormalizedArguments)
        assertNotEquals(original.normalizedTarget, prepared(request.copy(reminderId = anotherId)).normalizedTarget)
        val replay = prepared(request.copy(idempotencyKey = anotherId,
            correlationId = UUID.fromString("00000000-0000-0000-0000-000000000005")))
        assertEquals(original.canonicalNormalizedArguments, replay.canonicalNormalizedArguments)
        assertEquals(original.normalizedTarget, replay.normalizedTarget)
        assertNotEquals(original.idempotencyKey, replay.idempotencyKey)
        assertNotEquals(original.correlationId, replay.correlationId)
    }

    @Test fun `diagnostics exclude raw normalized and canonical reminder content`() {
        val request = command(" confidential-reminder-fixture \"42D\" ")
        val result = OrionCreateReminderPreparer.prepare(request) as CreateReminderPreparation.Ready
        for (value in listOf(request, result, result.reminder)) {
            assertFalse(value.toString().contains("confidential-reminder-fixture"))
            assertFalse(value.toString().contains(result.reminder.canonicalNormalizedArguments))
            assertFalse(value.toString().contains("America/Sao_Paulo"))
        }
        assertEquals("CreateReminderPreparation.Rejected(reason=TITLE_TOO_LARGE)",
            OrionCreateReminderPreparer.prepare(command("x".repeat(1025))).toString())
    }
}
