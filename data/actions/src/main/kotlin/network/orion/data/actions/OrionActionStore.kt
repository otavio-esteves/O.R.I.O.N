package network.orion.data.actions

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import network.orion.core.actions.*
import network.orion.core.model.AuthorizationLevel
import network.orion.core.model.Sensitivity
import network.orion.core.model.SideEffectClass
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*

enum class ActionStoreOutcome {
    REGISTERED, DEDUP, IDEMPOTENCY_CONFLICT, ACTION_ID_CONFLICT, APPLIED, CONFLICT,
    NOT_FOUND, INVALID_STATE, KEY_UNAVAILABLE, KEY_INVALIDATED, PAYLOAD_TOO_LARGE, UNREADABLE,
    CONFIRMATION_STALE, CONFIRMATION_MISMATCH, CONFIRMATION_EXPIRED, POLICY_CHANGED_RECONFIRM_REQUIRED,
}

data class ActionStoreResult(val outcome: ActionStoreOutcome, val actionId: UUID? = null) {
    override fun toString(): String = "ActionStoreResult(outcome=$outcome)"
}

data class StoredAction(
    val request: NormalizedActionRequest,
    val status: ActionStatus,
    val version: Long,
    val requiresConfirmation: Boolean,
    val confirmation: ActionConfirmationSnapshot?,
) {
    override fun toString(): String = "StoredAction(<redacted>)"
}

sealed interface ActionReadResult {
    data class Ready(val action: StoredAction) : ActionReadResult {
        override fun toString(): String = "ActionReadResult.Ready(<redacted>)"
    }
    data class Blocked(val reason: ActionStoreOutcome) : ActionReadResult
}

/**
 * Blocking main-process persistence adapter: call outside the UI thread.
 * Inputs must already pass trusted normalization/descriptor/policy validation.
 * Confirmation receipts do not establish user presence or a confirmation gesture.
 * No method starts execution, opens the startup barrier or grants Skill authority.
 * The production key provider/wrapping and ingress/command boundary are not wired.
 */
class OrionActionStore(
    private val database: OrionDatabase,
    private val encryption: OrionFieldEncryption,
    private val clock: WallClock,
) {
    private val binding = ActionConfirmationBinding(clock)

    fun register(request: NormalizedActionRequest, idempotency: IdempotencyKeyPolicy,
        idempotencyKey: String, correlationId: UUID, sensitivity: Sensitivity,
        requiresConfirmation: Boolean): ActionStoreResult {
        require(idempotencyKey.isNotBlank()) { "Idempotency key is required." }
        require(idempotency.canonicalPayloadSchemaVersion == 1 &&
            idempotency.uniqueConstraint == IDEMPOTENCY_UNIQUE_CONSTRAINT) { "Unsupported idempotency policy." }
        require(requiresConfirmation || (request.authorizationLevel != AuthorizationLevel.DESTRUCTIVE &&
            request.sideEffectClass != SideEffectClass.DESTRUCTIVE)) { "Destructive actions require confirmation." }
        val arguments = encrypt(request, "arguments", request.canonicalNormalizedArguments)
        if (arguments is CipherWrite.Blocked) return ActionStoreResult(arguments.reason)
        val target = encrypt(request, "target", request.normalizedTarget)
        if (target is CipherWrite.Blocked) return ActionStoreResult(target.reason)
        val now = clock.wallNow()
        val at = now.toEpochMilli()
        val retainUntil = now.plus(idempotency.retention).toEpochMilli()
        require(retainUntil > at) { "Idempotency retention must span at least one persisted millisecond." }
        val hashes = binding.prepare(request)
        val entity = ActionRequestEntity(
            actionId = request.actionId.toString(), skill = request.skill,
            argumentSchemaVersion = request.argumentSchemaVersion,
            argumentsEnvelope = (arguments as CipherWrite.Ready).bytes,
            targetEnvelope = (target as CipherWrite.Ready).bytes, payloadSensitivity = sensitivity,
            authorizationLevel = request.authorizationLevel, sideEffectClass = request.sideEffectClass,
            recoveryStrategy = request.recoveryStrategy, idempotencyMode = request.idempotencyMode,
            policyVersion = request.policyVersion, status = ActionStatus.PENDING,
            createdAt = at, updatedAt = at, idempotencyScope = idempotency.scope, idempotencyKey = idempotencyKey,
            idempotencyKeyIssuer = idempotency.issuer, canonicalPayloadSchemaVersion = idempotency.canonicalPayloadSchemaVersion,
            idempotencyRetainUntil = retainUntil, correlationId = correlationId.toString(),
            requiresConfirmation = requiresConfirmation, normalizedPayloadHash = hashes.normalizedPayloadHash,
            confirmationFingerprint = hashes.confirmationFingerprint,
        )
        val dao = database.actions()
        val registration = try { dao.registerDraft(entity, audit(entity, null, ActionStatus.PENDING, 0, at)) }
            catch (_: IllegalArgumentException) { return ActionStoreResult(ActionStoreOutcome.UNREADABLE) }
        return when (registration) {
            ActionRegistration.INSERTED -> ActionStoreResult(ActionStoreOutcome.REGISTERED, request.actionId)
            ActionRegistration.DEDUP -> {
                try {
                    val original = dao.findByKey(idempotency.scope, idempotencyKey)
                        ?: return ActionStoreResult(ActionStoreOutcome.NOT_FOUND)
                    ActionStoreResult(ActionStoreOutcome.DEDUP, UUID.fromString(original.actionId))
                } catch (_: IllegalArgumentException) { ActionStoreResult(ActionStoreOutcome.UNREADABLE) }
            }
            ActionRegistration.IDEMPOTENCY_CONFLICT -> ActionStoreResult(ActionStoreOutcome.IDEMPOTENCY_CONFLICT)
            ActionRegistration.ACTION_ID_CONFLICT -> ActionStoreResult(ActionStoreOutcome.ACTION_ID_CONFLICT)
        }
    }

    fun read(actionId: UUID): ActionReadResult {
        val entity = when (val result = lookup(actionId)) {
            is EntityLookup.Present -> result.entity
            is EntityLookup.Blocked -> return ActionReadResult.Blocked(result.reason)
        }
        return readEntity(entity)
    }

    fun requireConfirmation(actionId: UUID, expectedVersion: Long): ActionStoreResult =
        changeDraftStatus(actionId, expectedVersion, ActionStatus.NEEDS_CONFIRMATION)

    /** Local cancellation is safe even when encrypted content is unavailable. */
    fun cancel(actionId: UUID, expectedVersion: Long): ActionStoreResult =
        changeDraftStatus(actionId, expectedVersion, ActionStatus.CANCELLED)

    fun confirm(actionId: UUID, expectedVersion: Long, confirmation: ActionConfirmationSnapshot,
        currentPolicyVersion: String): ActionStoreResult {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        require(currentPolicyVersion.isNotBlank()) { "Current policy version is required." }
        // Waiting for the SQLite writer must not preserve expired/future-dated
        // consent. Read state and clocks only after acquiring that transaction.
        return database.runInTransaction(Callable {
            confirmInTransaction(actionId, expectedVersion, confirmation, currentPolicyVersion)
        })
    }

    private fun confirmInTransaction(actionId: UUID, expectedVersion: Long, confirmation: ActionConfirmationSnapshot,
        currentPolicyVersion: String): ActionStoreResult {
        val entity = when (val result = lookup(actionId)) {
            is EntityLookup.Present -> result.entity
            is EntityLookup.Blocked -> return ActionStoreResult(result.reason)
        }
        if (entity.version != expectedVersion) return ActionStoreResult(ActionStoreOutcome.CONFLICT)
        if (!ActionStateMachine.isTransitionAllowed(entity.status, ActionStatus.CONFIRMED) ||
            (entity.requiresConfirmation && entity.status != ActionStatus.NEEDS_CONFIRMATION)) {
            return ActionStoreResult(ActionStoreOutcome.INVALID_STATE)
        }
        if (currentPolicyVersion != entity.policyVersion) return ActionStoreResult(ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED)
        if (confirmation.confirmedAt.isBefore(Instant.ofEpochMilli(entity.createdAt)) ||
            confirmation.confirmedAt.isBefore(Instant.ofEpochMilli(entity.updatedAt))) {
            return ActionStoreResult(ActionStoreOutcome.CONFIRMATION_STALE)
        }
        val loaded = when (val result = readEntity(entity)) {
            is ActionReadResult.Ready -> result.action
            is ActionReadResult.Blocked -> return ActionStoreResult(result.reason)
        }
        val validation = binding.validate(loaded.request, confirmation)
        if (validation != ConfirmationBindingResult.VALID_BINDING) {
            return ActionStoreResult(when (validation) {
                ConfirmationBindingResult.CONFIRMATION_STALE -> ActionStoreOutcome.CONFIRMATION_STALE
                ConfirmationBindingResult.CONFIRMATION_MISMATCH -> ActionStoreOutcome.CONFIRMATION_MISMATCH
                ConfirmationBindingResult.CONFIRMATION_EXPIRED -> ActionStoreOutcome.CONFIRMATION_EXPIRED
                ConfirmationBindingResult.POLICY_CHANGED_RECONFIRM_REQUIRED -> ActionStoreOutcome.POLICY_CHANGED_RECONFIRM_REQUIRED
                ConfirmationBindingResult.VALID_BINDING -> error("Unexpected validation result.")
            })
        }
        val recheckedAt = clock.wallNow()
        if (recheckedAt.isBefore(confirmation.confirmedAt)) return ActionStoreResult(ActionStoreOutcome.CONFIRMATION_STALE)
        val at: Long
        val expiresAt: Long?
        try {
            at = confirmation.confirmedAt.toEpochMilli()
            expiresAt = confirmation.confirmationExpiresAt?.toEpochMilli()
        } catch (_: ArithmeticException) {
            return ActionStoreResult(ActionStoreOutcome.CONFIRMATION_STALE)
        }
        // Persisted confirmation uses milliseconds. Reject a lifetime lost to truncation.
        if (expiresAt != null && (expiresAt <= at || !recheckedAt.isBefore(Instant.ofEpochMilli(expiresAt)))) {
            return ActionStoreResult(ActionStoreOutcome.CONFIRMATION_EXPIRED)
        }
        return mutation(database.actions().confirmDraft(actionId.toString(), expectedVersion,
            confirmation.normalizedPayloadHash, confirmation.confirmationFingerprint, currentPolicyVersion, at, expiresAt,
            audit(entity, entity.status, ActionStatus.CONFIRMED, expectedVersion + 1, at)), actionId)
    }

    private fun changeDraftStatus(actionId: UUID, expectedVersion: Long, status: ActionStatus): ActionStoreResult {
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        val entity = when (val result = lookup(actionId)) {
            is EntityLookup.Present -> result.entity
            is EntityLookup.Blocked -> return ActionStoreResult(result.reason)
        }
        if (entity.version != expectedVersion) return ActionStoreResult(ActionStoreOutcome.CONFLICT)
        if (!ActionStateMachine.isTransitionAllowed(entity.status, status)) return ActionStoreResult(ActionStoreOutcome.INVALID_STATE)
        val at = clock.wallNow().toEpochMilli()
        return mutation(database.actions().changeDraftStatus(actionId.toString(), expectedVersion, status, at,
            audit(entity, entity.status, status, expectedVersion + 1, at)), actionId)
    }

    private fun readEntity(entity: ActionRequestEntity): ActionReadResult {
        return try { readCheckedEntity(entity) } catch (_: IllegalArgumentException) {
            ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
        }
    }

    private fun readCheckedEntity(entity: ActionRequestEntity): ActionReadResult {
        if (entity.version < 0 || entity.attemptCount < 0 || entity.canonicalPayloadSchemaVersion != 1) {
            return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
        }
        val arguments = decrypt(entity, "arguments", entity.argumentsEnvelope)
        if (arguments is ClearRead.Blocked) return ActionReadResult.Blocked(arguments.reason)
        val target = decrypt(entity, "target", entity.targetEnvelope)
        if (target is ClearRead.Blocked) return ActionReadResult.Blocked(target.reason)
        return try {
            val request = NormalizedActionRequest(UUID.fromString(entity.actionId), entity.skill,
                (arguments as ClearRead.Ready).text, (target as ClearRead.Ready).text, entity.authorizationLevel,
                entity.argumentSchemaVersion, entity.sideEffectClass, entity.recoveryStrategy, entity.idempotencyMode, entity.policyVersion)
            val hashes = binding.prepare(request)
            if (hashes.normalizedPayloadHash != entity.normalizedPayloadHash ||
                hashes.confirmationFingerprint != entity.confirmationFingerprint) {
                return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
            }
            val confirmedAt = entity.confirmedAt
            val confirmation = if (confirmedAt == null) {
                if (entity.status == ActionStatus.CONFIRMED || entity.confirmedPayloadHash != null ||
                    entity.policyVersionAtConfirmation != null || entity.payloadLockedAt != null || entity.confirmationExpiresAt != null) {
                    return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
                }
                null
            } else {
                ActionConfirmationSnapshot(request.actionId, entity.normalizedPayloadHash,
                    entity.confirmedPayloadHash ?: return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE),
                    entity.confirmationFingerprint,
                    entity.policyVersionAtConfirmation ?: return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE),
                    Instant.ofEpochMilli(confirmedAt), entity.confirmationExpiresAt?.let(Instant::ofEpochMilli),
                    entity.payloadLockedAt?.let(Instant::ofEpochMilli) ?: return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE))
            }
            if (confirmation != null && (confirmation.confirmedPayloadHash != hashes.normalizedPayloadHash ||
                confirmation.policyVersionAtConfirmation != request.policyVersion ||
                confirmation.payloadLockedAt != confirmation.confirmedAt ||
                confirmation.confirmationExpiresAt?.let { !it.isAfter(confirmation.confirmedAt) } == true)) {
                return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
            }
            if (!entity.requiresConfirmation && (request.authorizationLevel == AuthorizationLevel.DESTRUCTIVE ||
                request.sideEffectClass == SideEffectClass.DESTRUCTIVE)) return ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
            ActionReadResult.Ready(StoredAction(request, entity.status, entity.version, entity.requiresConfirmation, confirmation))
        } catch (_: IllegalArgumentException) {
            ActionReadResult.Blocked(ActionStoreOutcome.UNREADABLE)
        }
    }

    private fun encrypt(request: NormalizedActionRequest, field: String, text: String): CipherWrite {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return try {
            when (val encrypted = encryption.encrypt(bytes,
                FieldEncryptionContext("action/${request.actionId}", field, request.argumentSchemaVersion))) {
                is FieldEncryptionResult.Encrypted -> CipherWrite.Ready(FieldEnvelopeCodec.encode(encrypted.envelope))
                is FieldEncryptionResult.Failure -> CipherWrite.Blocked(cryptoFailure(encrypted.reason))
            }
        } finally { bytes.fill(0) }
    }

    private fun decrypt(entity: ActionRequestEntity, field: String, bytes: ByteArray): ClearRead {
        val envelope = when (val parsed = FieldEnvelopeCodec.decode(bytes)) {
            is FieldEnvelopeDecodingResult.Decoded -> parsed.envelope
            is FieldEnvelopeDecodingResult.Failure -> return ClearRead.Blocked(ActionStoreOutcome.UNREADABLE)
        }
        return when (val result = encryption.decrypt(envelope,
            FieldEncryptionContext("action/${entity.actionId}", field, entity.argumentSchemaVersion))) {
            is FieldDecryptionResult.Failure -> ClearRead.Blocked(cryptoFailure(result.reason))
            is FieldDecryptionResult.Plaintext -> {
                val plaintext = result.copyBytes()
                try {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    ClearRead.Ready(decoder.decode(ByteBuffer.wrap(plaintext)).toString())
                } catch (_: CharacterCodingException) {
                    ClearRead.Blocked(ActionStoreOutcome.UNREADABLE)
                } finally { plaintext.fill(0) }
            }
        }
    }

    private fun audit(entity: ActionRequestEntity, from: ActionStatus?, to: ActionStatus, version: Long, at: Long) =
        ActionAuditEntity(UUID.randomUUID().toString(), entity.actionId, version, from, to, at, entity.correlationId)

    private fun mutation(result: ActionMutation, actionId: UUID) = ActionStoreResult(when (result) {
        ActionMutation.APPLIED -> ActionStoreOutcome.APPLIED
        ActionMutation.CONFLICT -> ActionStoreOutcome.CONFLICT
        ActionMutation.NOT_FOUND -> ActionStoreOutcome.NOT_FOUND
    }, actionId)

    private fun cryptoFailure(reason: FieldCryptoFailure) = when (reason) {
        FieldCryptoFailure.KEY_UNAVAILABLE -> ActionStoreOutcome.KEY_UNAVAILABLE
        FieldCryptoFailure.KEY_INVALIDATED -> ActionStoreOutcome.KEY_INVALIDATED
        FieldCryptoFailure.FIELD_TOO_LARGE -> ActionStoreOutcome.PAYLOAD_TOO_LARGE
        else -> ActionStoreOutcome.UNREADABLE
    }

    private fun lookup(actionId: UUID): EntityLookup = try {
        val entity = database.actions().find(actionId.toString())
        if (entity == null) EntityLookup.Blocked(ActionStoreOutcome.NOT_FOUND) else EntityLookup.Present(entity)
    } catch (_: IllegalArgumentException) {
        // Room enum decoding rejects unknown persisted names. Never echo the stored value.
        EntityLookup.Blocked(ActionStoreOutcome.UNREADABLE)
    }

    private sealed interface EntityLookup {
        class Present(val entity: ActionRequestEntity) : EntityLookup
        class Blocked(val reason: ActionStoreOutcome) : EntityLookup
    }

    private sealed interface CipherWrite {
        class Ready(val bytes: ByteArray) : CipherWrite
        class Blocked(val reason: ActionStoreOutcome) : CipherWrite
    }
    private sealed interface ClearRead {
        class Ready(val text: String) : ClearRead
        class Blocked(val reason: ActionStoreOutcome) : ClearRead
    }

    companion object {
        const val IDEMPOTENCY_UNIQUE_CONSTRAINT = "index_orion_action_requests_idempotencyScope_idempotencyKey"
    }
}
