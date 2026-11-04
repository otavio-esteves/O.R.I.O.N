package network.orion.core.commands.tasks

import java.util.UUID
import network.orion.core.commands.Command
import org.junit.Assert.*
import org.junit.Test

class CreateTaskPreparationTest {
    private val taskId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val correlation = UUID.fromString("00000000-0000-0000-0000-000000000003")

    private fun command(title: String) = CreateTask(taskId, title, key, correlation)
    private fun prepared(command: CreateTask): ValidatedCreateTask =
        (OrionCreateTaskPreparer.prepare(command) as CreateTaskPreparation.Ready).task
    private fun rejected(title: String): CreateTaskRejection =
        (OrionCreateTaskPreparer.prepare(command(title)) as CreateTaskPreparation.Rejected).reason

    @Test fun `command keeps operational result separate from validated preparation and stable identities`() {
        val request = command("Tarefa")
        val typed: Command<CreateTaskResult> = request
        assertSame(request, typed)
        val task = prepared(request)
        assertEquals(taskId, task.taskId)
        assertEquals(key, task.idempotencyKey)
        assertEquals(correlation, task.correlationId)
        assertEquals("Tarefa", task.title)
        assertEquals(1, task.argumentSchemaVersion)
        assertEquals("task/$taskId", task.normalizedTarget)
    }

    @Test fun `empty and Unicode whitespace only titles fail with a typed reason`() {
        for (title in listOf("", " ", "\u00a0\u2003\u202f")) {
            assertEquals(CreateTaskRejection.BLANK_TITLE, rejected(title))
        }
    }

    @Test fun `raw UTF16 admission rejects oversized input before trimming or normalization`() {
        assertEquals(CreateTaskRejection.TITLE_TOO_LARGE, rejected(" ".repeat(1024) + "a"))
        assertEquals(CreateTaskRejection.TITLE_TOO_LARGE, rejected("a".repeat(1025) + '\ud800'))
        assertEquals(CreateTaskRejection.TITLE_TOO_LARGE, rejected("e\u0301".repeat(512) + "a"))
    }

    @Test fun `the title budget is UTF8 bytes rather than UTF16 characters`() {
        for (title in listOf("a".repeat(1024), "é".repeat(512), "€".repeat(341) + "a", "😀".repeat(256))) {
            assertEquals(1024, title.toByteArray(Charsets.UTF_8).size)
            assertEquals(title, prepared(command(title)).title)
        }
        for (title in listOf("a".repeat(1025), "é".repeat(513), "€".repeat(341) + "aa", "😀".repeat(257))) {
            assertEquals(CreateTaskRejection.TITLE_TOO_LARGE, rejected(title))
        }
    }

    @Test fun `unpaired UTF16 surrogates fail instead of replacement encoding`() {
        for (title in listOf("\ud800", "\udfff", "x\ud800y", "x\udc00y", "\ud800\ud800", "😀\udc00", " \ud800 ")) {
            assertEquals(CreateTaskRejection.INVALID_UNICODE, rejected(title))
        }
        assertEquals("a😀b", prepared(command("a😀b")).title)
    }

    @Test fun `NFC expansion is bounded again after raw Unicode validation`() {
        // U+0344 canonically decomposes into two combining marks even under NFC.
        val raw = "\u0344".repeat(512)
        assertEquals(1024, raw.toByteArray(Charsets.UTF_8).size)
        assertEquals(CreateTaskRejection.TITLE_TOO_LARGE, rejected(raw))
        val accepted = prepared(command("\u0344".repeat(256)))
        assertEquals("\u0308\u0301".repeat(256), accepted.title)
        assertEquals(1024, accepted.title.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun `trim and NFC produce equivalent canonical material without modifying the request`() {
        val request = command(" \u0065\u0301\u00a0")
        val first = prepared(request)
        val second = prepared(command("é"))
        assertEquals("é", first.title)
        assertEquals(second.canonicalNormalizedArguments, first.canonicalNormalizedArguments)
        assertEquals(" \u0065\u0301\u00a0", request.title)
        assertEquals(prepared(command("K")).canonicalNormalizedArguments,
            prepared(command("\u212a")).canonicalNormalizedArguments)
    }

    @Test fun `case compatibility characters and interior whitespace remain material`() {
        val variants = listOf("Task", "task", "Ｔask", "Ta sk", "Ta  sk")
        assertEquals(variants.size, variants.map { prepared(command(it)).canonicalNormalizedArguments }.distinct().size)
        assertEquals("ﬁ", prepared(command("ﬁ")).title)
        assertNotEquals(prepared(command("ﬁ")).canonicalNormalizedArguments,
            prepared(command("fi")).canonicalNormalizedArguments)
    }

    @Test fun `canonical schema one has fixed field order and lower case UUIDs`() {
        val id = UUID.fromString("AABBCCDD-EEFF-0011-2233-445566778899")
        val task = prepared(CreateTask(id, "Entregar relatório", key, correlation))
        assertEquals("""{"taskId":"aabbccdd-eeff-0011-2233-445566778899","title":"Entregar relatório"}""",
            task.canonicalNormalizedArguments)
        assertEquals(task.canonicalNormalizedArguments, prepared(CreateTask(id, "Entregar relatório", key, correlation)).canonicalNormalizedArguments)
    }

    @Test fun `canonical JSON escapes quotes and reverse solidus without changing Unicode content`() {
        val title = "x\"\\y/é😀"
        val task = prepared(command(title))
        assertEquals(title, task.title)
        assertEquals("""{"taskId":"00000000-0000-0000-0000-000000000001","title":"x\"\\y/é😀"}""",
            task.canonicalNormalizedArguments)
    }

    @Test fun `single line titles reject every C0 and C1 control and Unicode line separator`() {
        val forbidden = (0..31).map(Int::toChar) + (127..159).map(Int::toChar) + listOf('\u2028', '\u2029')
        forbidden.forEach { character ->
            assertEquals(CreateTaskRejection.INVALID_TITLE_CHARACTER, rejected("a${character}b"))
        }
        for (title in listOf("\tTask", "Task\n", "\t\r\n", "\u0000")) {
            assertEquals(CreateTaskRejection.INVALID_TITLE_CHARACTER, rejected(title))
        }
    }

    @Test fun `authority looking content remains escaped title data`() {
        val task = prepared(command("x\",\"authorizationLevel\":\"DESTRUCTIVE"))
        assertEquals("""{"taskId":"00000000-0000-0000-0000-000000000001","title":"x\",\"authorizationLevel\":\"DESTRUCTIVE"}""",
            task.canonicalNormalizedArguments)
    }

    @Test fun `material target changes differ while replay and trace identities remain outside the material`() {
        val original = prepared(command("Comprar pão"))
        val anotherTask = prepared(CreateTask(UUID.fromString("00000000-0000-0000-0000-000000000004"), "Comprar pão", key, correlation))
        assertNotEquals(original.canonicalNormalizedArguments, anotherTask.canonicalNormalizedArguments)
        assertNotEquals(original.normalizedTarget, anotherTask.normalizedTarget)

        val replay = prepared(CreateTask(taskId, "Comprar pão", UUID.fromString("00000000-0000-0000-0000-000000000005"),
            UUID.fromString("00000000-0000-0000-0000-000000000006")))
        assertNotEquals(original.idempotencyKey, replay.idempotencyKey)
        assertNotEquals(original.correlationId, replay.correlationId)
        assertEquals(original.canonicalNormalizedArguments, replay.canonicalNormalizedArguments)
        assertEquals(original.normalizedTarget, replay.normalizedTarget)
    }

    @Test fun `diagnostic strings never include raw normalized or canonical content`() {
        val request = command(" confidential-task-fixture \"180C\" ")
        val result = OrionCreateTaskPreparer.prepare(request) as CreateTaskPreparation.Ready
        for (value in listOf(request, result, result.task)) {
            assertFalse(value.toString().contains("confidential-task-fixture"))
            assertFalse(value.toString().contains(result.task.canonicalNormalizedArguments))
        }
        val rejected = OrionCreateTaskPreparer.prepare(command("x".repeat(1025)))
        assertEquals("CreateTaskPreparation.Rejected(reason=TITLE_TOO_LARGE)", rejected.toString())
    }
}
