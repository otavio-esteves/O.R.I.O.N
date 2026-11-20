package network.orion.core.orchestrator.chat

/** Technical reasons contain no input, arguments or identifiers. */
enum class OrionChatInputError(val message: String) {
    EMPTY_INPUT("Escreva uma mensagem."),
    INPUT_TOO_LARGE("A mensagem deve ter no máximo 4.096 bytes UTF-8."),
    INVALID_CHARACTER("Use uma única linha, sem caracteres de controle."),
    INVALID_UNICODE("A mensagem contém Unicode inválido. Revise o texto."),
    MISSING_TASK_TITLE("Informe o título da tarefa."),
    TASK_TITLE_TOO_LARGE("O título da tarefa deve ter no máximo 1.024 bytes UTF-8."),
    INVALID_TASK_TITLE("O título da tarefa é inválido. Revise o texto."),
}

/** Local admission only. Accepted never means inference completion or durable action execution. */
sealed interface OrionChatAdmission {
    data object Accepted : OrionChatAdmission
    data class Rejected(val reason: OrionChatInputError) : OrionChatAdmission
    data object Busy : OrionChatAdmission
    data object Closed : OrionChatAdmission
}

/** Shared raw-input guard. No trim, normalization, replacement encoding or content retention. */
object OrionChatInputPolicy {
    const val MAX_UTF8_BYTES = 4096

    fun validate(text: String): OrionChatInputError? {
        // Every valid scalar needs at least as many UTF-8 bytes as UTF-16 code units.
        // Guard allocations/work before scanning an arbitrarily large paste.
        if (text.length > MAX_UTF8_BYTES) return OrionChatInputError.INPUT_TOO_LARGE
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 == text.length || !text[index + 1].isLowSurrogate())
                        return OrionChatInputError.INVALID_UNICODE
                    bytes += 4
                    index += 2
                }
                character.isLowSurrogate() -> return OrionChatInputError.INVALID_UNICODE
                character.isISOControl() || character == '\u2028' || character == '\u2029' ->
                    return OrionChatInputError.INVALID_CHARACTER
                else -> {
                    bytes += when {
                        character.code <= 0x7f -> 1
                        character.code <= 0x7ff -> 2
                        else -> 3
                    }
                    index++
                }
            }
            if (bytes > MAX_UTF8_BYTES) return OrionChatInputError.INPUT_TOO_LARGE
        }
        return if (text.isBlank()) OrionChatInputError.EMPTY_INPUT else null
    }
}
