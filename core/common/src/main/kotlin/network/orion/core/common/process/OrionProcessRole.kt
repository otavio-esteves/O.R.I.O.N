package network.orion.core.common.process

enum class OrionProcessRole(
    val processSuffix: String?,
) {
    MAIN(null),
    AI(":ai"),
    VOICE_CONTROL(":voice"),
    VOICE_SESSION(":voice_session"),
    ;

    companion object {
        fun fromProcessName(
            applicationId: String,
            processName: String,
        ): OrionProcessRole {
            require(applicationId.isNotBlank()) { "applicationId must not be blank" }
            require(processName.isNotBlank()) { "processName must not be blank" }

            return entries.firstOrNull { role ->
                processName == applicationId + (role.processSuffix ?: "")
            } ?: throw IllegalArgumentException("Unsupported O.R.I.O.N. process: $processName")
        }
    }
}
