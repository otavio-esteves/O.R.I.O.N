package network.orion.data.database

import java.util.UUID

internal val TECHNICAL_NAME = Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")
internal val SHA256_HEX = Regex("[0-9a-f]{64}")
internal const val MAX_DURABLE_FIELD_BYTES = 1_048_704

internal fun requireTechnicalUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "Expected canonical UUID." }
}
