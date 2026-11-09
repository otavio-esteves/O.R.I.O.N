package network.orion.core.health

/** Counts and fixed technical codes only; contains no model path, prompt or generated text. */
data class OrionInferenceDiagnostics(
    val cortexState: String,
    val registeredModels: Int?,
    val structurallyReadyModels: Int?,
    val qualifiedModelPlans: Int,
    val resourceBlock: String?,
)
