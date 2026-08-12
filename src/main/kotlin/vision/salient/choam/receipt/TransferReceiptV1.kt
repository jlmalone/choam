package vision.salient.choam.receipt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

const val TRANSFER_RECEIPT_SCHEMA_V1 = "choam.transfer-receipt.v1"
private const val MAX_APPLIED_OBSERVATIONS = 64
private val opaqueId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
private val failureCodePattern = Regex("[A-Z][A-Z0-9_]{0,63}")
private val routeFingerprint = Regex("[a-z0-9][a-z0-9_-]{15,127}")
private val sha256 = Regex("[0-9a-f]{64}")

object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable enum class TransferReceiptState { COMMAND_ACCEPTED, QUEUE_ADMITTED, DEFERRED, ACTIVE, VERIFYING_BYTES, VERIFYING_FILES, DESTINATION_COMMITTED, COMPLETED, FAILED, CANCELLED }
@Serializable enum class DestinationEvidenceProofScheme { LOCAL_AUTHORITATIVE_PROBE_V1, CANONICAL_RECEIPT_SIGNATURE_V1 }

@Serializable data class TransferAuthority(val label: String) { init { require(opaqueId.matches(label)) } }
@Serializable data class TransferRoute(val generation: Long, val fingerprint: String) { init { require(generation >= 0 && routeFingerprint.matches(fingerprint)) } }
@Serializable data class DeclaredHash(val algorithm: String, val expected: String) { init { require(algorithm == "SHA-256" && sha256.matches(expected)) } }
@Serializable data class ObservedHash(val algorithm: String, val value: String) { init { require(algorithm == "SHA-256" && sha256.matches(value)) } }

@Serializable
data class DestinationEvidenceProof(
    val scheme: DestinationEvidenceProofScheme,
    val version: Int = 1,
    val destinationAuthorityKeyFingerprint: String,
    val canonicalReceiptDigestSha256: String? = null,
    val signature: String? = null,
    val localAuthoritativeProbeAttestation: String? = null,
) {
    init {
        require(version == 1 && routeFingerprint.matches(destinationAuthorityKeyFingerprint))
        require(canonicalReceiptDigestSha256 == null || sha256.matches(canonicalReceiptDigestSha256))
        when (scheme) {
            DestinationEvidenceProofScheme.LOCAL_AUTHORITATIVE_PROBE_V1 -> require(localAuthoritativeProbeAttestation?.matches(Regex("[A-Za-z0-9_-]{1,256}")) == true)
            DestinationEvidenceProofScheme.CANONICAL_RECEIPT_SIGNATURE_V1 -> require(canonicalReceiptDigestSha256 != null && signature?.matches(Regex("[A-Za-z0-9_-]{1,512}")) == true)
        }
    }
}

@Serializable
data class DestinationEvidence(
    val authority: TransferAuthority,
    @Serializable(with = InstantIsoSerializer::class) val observedAt: Instant,
    val observedBytes: Long,
    val observedFiles: Long,
    val observedHashes: List<ObservedHash> = emptyList(),
    val proof: DestinationEvidenceProof,
) {
    init { require(observedBytes >= 0 && observedFiles >= 0); requireUniqueHashes(observedHashes.map { it.algorithm to it.value }) }
}

@Serializable
data class TransferTimestamps(
    @Serializable(with = InstantIsoSerializer::class) val commandAcceptedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class) val queueAdmittedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val startedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val verificationStartedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val destinationCommittedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val completedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val failedAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val cancelledAt: Instant? = null,
    @Serializable(with = InstantIsoSerializer::class) val lastObservedAt: Instant? = null,
) {
    init { listOf(commandAcceptedAt, queueAdmittedAt, startedAt, verificationStartedAt, destinationCommittedAt, completedAt, failedAt, cancelledAt, lastObservedAt).filterNotNull().zipWithNext().forEach { require(!it.first.isAfter(it.second)) } }
}

@Serializable
data class PriorAttemptSummary(
    val attemptId: String,
    val terminalOrDeferredState: TransferReceiptState,
    @Serializable(with = InstantIsoSerializer::class) val lastObservedAt: Instant,
    val failureCode: String? = null,
    val route: TransferRoute,
) { init { require(opaqueId.matches(attemptId)); require(terminalOrDeferredState == TransferReceiptState.DEFERRED || terminalOrDeferredState == TransferReceiptState.FAILED); require(failureCode == null || failureCodePattern.matches(failureCode)) } }

@Serializable
data class TransferReceiptV1(
    val schema: String = TRANSFER_RECEIPT_SCHEMA_V1,
    val transferId: String,
    val attemptId: String,
    val queueEntryId: String? = null,
    val sourceAuthority: TransferAuthority,
    val destinationAuthority: TransferAuthority,
    val route: TransferRoute,
    val expectedBytes: Long? = null,
    val expectedFiles: Long? = null,
    val declaredHashes: List<DeclaredHash> = emptyList(),
    val destinationEvidence: DestinationEvidence? = null,
    val timestamps: TransferTimestamps,
    val state: TransferReceiptState = TransferReceiptState.COMMAND_ACCEPTED,
    val processExitCode: Int? = null,
    val failureCode: String? = null,
    val priorAttempts: List<PriorAttemptSummary> = emptyList(),
    val appliedObservationIds: List<String> = emptyList(),
    val lastAppliedObservationSequence: Long = 0,
) {
    init {
        require(schema == TRANSFER_RECEIPT_SCHEMA_V1 && opaqueId.matches(transferId) && opaqueId.matches(attemptId))
        require(queueEntryId == null || opaqueId.matches(queueEntryId)); require(expectedBytes == null || expectedBytes >= 0); require(expectedFiles == null || expectedFiles >= 0)
        requireUniqueHashes(declaredHashes.map { it.algorithm to it.expected }); require(failureCode == null || failureCodePattern.matches(failureCode))
        require(appliedObservationIds.size <= MAX_APPLIED_OBSERVATIONS && appliedObservationIds.distinct().size == appliedObservationIds.size && appliedObservationIds.all(opaqueId::matches))
        require(lastAppliedObservationSequence in 0 until Long.MAX_VALUE)
    }
}

@Serializable
data class TransferReceiptObservation(
    val observationId: String,
    val transferId: String,
    val attemptId: String,
    val sequence: Long,
    @Serializable(with = InstantIsoSerializer::class) val observedAt: Instant,
    val state: TransferReceiptState,
    val route: TransferRoute? = null,
    val destinationEvidence: DestinationEvidence? = null,
    val processExitCode: Int? = null,
    val failureCode: String? = null,
) { init { require(opaqueId.matches(observationId) && opaqueId.matches(transferId) && opaqueId.matches(attemptId)); require(sequence in 0 until Long.MAX_VALUE); require(failureCode == null || failureCodePattern.matches(failureCode)) } }

fun interface DestinationEvidenceVerifier { fun verifies(receipt: TransferReceiptV1, evidence: DestinationEvidence): Boolean }
sealed interface ReceiptApplyResult { data class Applied(val receipt: TransferReceiptV1) : ReceiptApplyResult; data class Ignored(val receipt: TransferReceiptV1, val reason: String) : ReceiptApplyResult; data class Rejected(val reason: String) : ReceiptApplyResult }

object TransferReceiptReducer {
    fun restart(receipt: TransferReceiptV1, newAttemptId: String, deferredAt: Instant, reason: String, route: TransferRoute): TransferReceiptV1 {
        require(receipt.state == TransferReceiptState.DEFERRED || receipt.state == TransferReceiptState.FAILED) { "only deferred or failed attempts are retryable" }
        require(opaqueId.matches(newAttemptId) && newAttemptId != receipt.attemptId && failureCodePattern.matches(reason)); require(!deferredAt.isBefore(receipt.timestamps.lastObservedAt ?: receipt.timestamps.commandAcceptedAt))
        val prior = PriorAttemptSummary(receipt.attemptId, receipt.state, receipt.timestamps.lastObservedAt ?: receipt.timestamps.commandAcceptedAt, receipt.failureCode, receipt.route)
        return receipt.copy(attemptId = newAttemptId, route = route, destinationEvidence = null, timestamps = TransferTimestamps(receipt.timestamps.commandAcceptedAt, receipt.timestamps.queueAdmittedAt, lastObservedAt = deferredAt), state = TransferReceiptState.DEFERRED, processExitCode = null, failureCode = null, priorAttempts = (receipt.priorAttempts + prior).takeLast(MAX_APPLIED_OBSERVATIONS), appliedObservationIds = emptyList(), lastAppliedObservationSequence = 0)
    }

    fun apply(receipt: TransferReceiptV1, observation: TransferReceiptObservation, verifier: DestinationEvidenceVerifier? = null): ReceiptApplyResult {
        if (receipt.transferId != observation.transferId || receipt.attemptId != observation.attemptId) return ReceiptApplyResult.Rejected("IDENTITY_MISMATCH")
        if (observation.observationId in receipt.appliedObservationIds) return ReceiptApplyResult.Rejected("DUPLICATE_OBSERVATION_ID")
        if (observation.sequence <= receipt.lastAppliedObservationSequence) return ReceiptApplyResult.Ignored(receipt, "OUT_OF_ORDER_OBSERVATION")
        if (observation.observedAt.isBefore(receipt.timestamps.lastObservedAt ?: receipt.timestamps.commandAcceptedAt)) return ReceiptApplyResult.Rejected("NON_MONOTONIC_TIMESTAMP")
        if (!allowed(receipt.state, observation.state)) return ReceiptApplyResult.Rejected("ILLEGAL_TRANSITION")
        if (observation.route != null && observation.route != receipt.route && observation.state != TransferReceiptState.DEFERRED) return ReceiptApplyResult.Rejected("ROUTE_CHANGE_REQUIRES_DEFERRED")
        if (observation.state == TransferReceiptState.FAILED && observation.failureCode == null) return ReceiptApplyResult.Rejected("MISSING_FAILURE_CODE")
        val evidence = observation.destinationEvidence ?: receipt.destinationEvidence
        if (observation.state == TransferReceiptState.COMPLETED && !isAuthoritativelyComplete(receipt, evidence, verifier)) return ReceiptApplyResult.Rejected("DESTINATION_EVIDENCE_REQUIRED")
        val timestamps = transitionTimes(receipt.timestamps, observation)
        return ReceiptApplyResult.Applied(receipt.copy(route = observation.route ?: receipt.route, destinationEvidence = evidence, timestamps = timestamps, state = observation.state, processExitCode = observation.processExitCode ?: receipt.processExitCode, failureCode = observation.failureCode ?: receipt.failureCode, appliedObservationIds = (receipt.appliedObservationIds + observation.observationId).takeLast(MAX_APPLIED_OBSERVATIONS), lastAppliedObservationSequence = observation.sequence))
    }

    fun isAuthoritativelyComplete(receipt: TransferReceiptV1, evidence: DestinationEvidence?, verifier: DestinationEvidenceVerifier?): Boolean {
        if (evidence == null || verifier == null || evidence.authority != receipt.destinationAuthority || !verifier.verifies(receipt, evidence)) return false
        if (receipt.expectedBytes == null && receipt.expectedFiles == null && receipt.declaredHashes.isEmpty()) return false
        if (receipt.expectedBytes != null && receipt.expectedBytes != evidence.observedBytes || receipt.expectedFiles != null && receipt.expectedFiles != evidence.observedFiles) return false
        return receipt.declaredHashes.all { declared -> evidence.observedHashes.any { it.algorithm == declared.algorithm && it.value == declared.expected } }
    }

    private fun transitionTimes(old: TransferTimestamps, observation: TransferReceiptObservation): TransferTimestamps = old.copy(
        queueAdmittedAt = if (observation.state == TransferReceiptState.QUEUE_ADMITTED) observation.observedAt else old.queueAdmittedAt,
        startedAt = if (observation.state == TransferReceiptState.ACTIVE) observation.observedAt else old.startedAt,
        verificationStartedAt = if (observation.state in setOf(TransferReceiptState.VERIFYING_BYTES, TransferReceiptState.VERIFYING_FILES)) old.verificationStartedAt ?: observation.observedAt else old.verificationStartedAt,
        destinationCommittedAt = if (observation.state == TransferReceiptState.DESTINATION_COMMITTED) observation.observedAt else old.destinationCommittedAt,
        completedAt = if (observation.state == TransferReceiptState.COMPLETED) observation.observedAt else old.completedAt,
        failedAt = if (observation.state == TransferReceiptState.FAILED) observation.observedAt else old.failedAt,
        cancelledAt = if (observation.state == TransferReceiptState.CANCELLED) observation.observedAt else old.cancelledAt,
        lastObservedAt = observation.observedAt,
    )
    private fun allowed(from: TransferReceiptState, to: TransferReceiptState) = when (from) {
        TransferReceiptState.COMMAND_ACCEPTED -> to in setOf(TransferReceiptState.QUEUE_ADMITTED, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.QUEUE_ADMITTED -> to in setOf(TransferReceiptState.DEFERRED, TransferReceiptState.ACTIVE, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.DEFERRED -> to in setOf(TransferReceiptState.ACTIVE, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.ACTIVE -> to in setOf(TransferReceiptState.VERIFYING_BYTES, TransferReceiptState.DEFERRED, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.VERIFYING_BYTES -> to in setOf(TransferReceiptState.VERIFYING_FILES, TransferReceiptState.DEFERRED, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.VERIFYING_FILES -> to in setOf(TransferReceiptState.DESTINATION_COMMITTED, TransferReceiptState.DEFERRED, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.DESTINATION_COMMITTED -> to in setOf(TransferReceiptState.COMPLETED, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED)
        TransferReceiptState.COMPLETED, TransferReceiptState.FAILED, TransferReceiptState.CANCELLED -> false
    }
}
private fun requireUniqueHashes(values: List<Pair<String, String>>) { require(values.map { it.first }.distinct().size == values.size) { "duplicate hash algorithm" } }
