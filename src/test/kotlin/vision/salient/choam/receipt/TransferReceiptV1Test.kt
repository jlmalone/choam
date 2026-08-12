package vision.salient.choam.receipt

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferReceiptV1Test {
    private val hash = "a".repeat(64)
    private val proof = DestinationEvidenceProof(DestinationEvidenceProofScheme.LOCAL_AUTHORITATIVE_PROBE_V1, destinationAuthorityKeyFingerprint = "destination-key-fingerprint-v1", transferId = "transfer-001", attemptId = "attempt-001", route = TransferRoute(1, "route-fingerprint-v1"), localAuthoritativeProbeAttestation = "synthetic_probe_attestation")
    private val verifier = DestinationEvidenceVerifier { _, evidence -> evidence.proof == proof }
    private fun receipt(state: TransferReceiptState = TransferReceiptState.ACTIVE) = TransferReceiptV1(transferId = "transfer-001", attemptId = "attempt-001", queueEntryId = "queue-001", sourceAuthority = TransferAuthority("source-label"), destinationAuthority = TransferAuthority("destination-label"), route = TransferRoute(1, "route-fingerprint-v1"), expectedBytes = 128, expectedFiles = 1, declaredHashes = listOf(DeclaredHash("SHA-256", hash)), timestamps = timestampsFor(state), state = state)
    private fun evidence(bytes: Long = 128, files: Long = 1, observedHash: String = hash) = DestinationEvidence(TransferAuthority("destination-label"), Instant.parse("2026-08-12T00:01:00Z"), bytes, files, listOf(ObservedHash("SHA-256", observedHash)), proof)
    private fun observation(id: String, sequence: Long, state: TransferReceiptState, evidence: DestinationEvidence? = null, at: Instant = Instant.parse("2026-08-12T00:01:00Z"), failure: String? = null) = TransferReceiptObservation(id, "transfer-001", "attempt-001", sequence, at, state, destinationEvidence = evidence, failureCode = failure)
    private fun timestampsFor(state: TransferReceiptState): TransferTimestamps {
        val command = Instant.parse("2026-08-12T00:00:00Z")
        val observed = Instant.parse("2026-08-12T00:01:00Z")
        return when (state) {
            TransferReceiptState.VERIFYING_FILES -> TransferTimestamps(command, queueAdmittedAt = command, startedAt = command, verificationStartedAt = command, lastObservedAt = command)
            TransferReceiptState.DESTINATION_COMMITTED -> TransferTimestamps(command, queueAdmittedAt = command, startedAt = command, verificationStartedAt = command, destinationCommittedAt = observed, lastObservedAt = observed)
            TransferReceiptState.COMPLETED -> TransferTimestamps(command, queueAdmittedAt = command, startedAt = command, verificationStartedAt = command, destinationCommittedAt = observed, completedAt = observed, lastObservedAt = observed)
            else -> TransferTimestamps(command)
        }
    }

    @Test fun `default projection never self asserts delivery`() {
        val completed = receipt(TransferReceiptState.COMPLETED).copy(destinationEvidence = evidence())
        assertFalse(ManagerTransferProjection.project(completed).deliveryCompleted)
        assertTrue(ManagerTransferProjection.project(completed, verifier).deliveryCompleted)
    }
    @Test fun `completion requires verifier authoritative expectations and exact canonical hashes`() {
        val committed = TransferReceiptReducer.apply(receipt(TransferReceiptState.VERIFYING_FILES), observation("obs-001", 1, TransferReceiptState.DESTINATION_COMMITTED, evidence()), verifier) as ReceiptApplyResult.Applied
        assertIs<ReceiptApplyResult.Rejected>(TransferReceiptReducer.apply(committed.receipt, observation("obs-002", 2, TransferReceiptState.COMPLETED)))
        assertIs<ReceiptApplyResult.Applied>(TransferReceiptReducer.apply(committed.receipt, observation("obs-002", 2, TransferReceiptState.COMPLETED), verifier))
        for (bad in listOf(evidence(127), evidence(files = 2), evidence(observedHash = "b".repeat(64)))) assertIs<ReceiptApplyResult.Rejected>(TransferReceiptReducer.apply(receipt(TransferReceiptState.DESTINATION_COMMITTED), observation("obs-${bad.observedBytes}-${bad.observedFiles}", 1, TransferReceiptState.COMPLETED, bad), verifier))
    }
    @Test fun `hostile identifiers and invalid content declarations are rejected`() {
        assertFails { TransferAuthority("host.example") }; assertFails { TransferAuthority("/private/path") }; assertFails { TransferRoute(-1, "route-fingerprint-v1") }
        assertFails { DeclaredHash("sha256", hash) }; assertFails { DeclaredHash("SHA-256", "A".repeat(64)) }; assertFails { receipt().copy(declaredHashes = listOf(DeclaredHash("SHA-256", hash), DeclaredHash("SHA-256", hash))) }
        assertFails { DestinationEvidence(TransferAuthority("destination-label"), Instant.parse("2026-08-12T00:01:00Z"), 1, 1, listOf(ObservedHash("SHA-256", hash), ObservedHash("SHA-256", hash)), proof) }
        assertFails { observation("obs-003", -1, TransferReceiptState.FAILED, failure = "RETRYABLE_FAILURE") }; assertFails { observation("obs-004", Long.MAX_VALUE, TransferReceiptState.FAILED, failure = "RETRYABLE_FAILURE") }
    }
    @Test fun `idempotence rejects reused IDs preserves sequence and timestamps`() {
        val current = receipt().copy(appliedObservationIds = listOf("obs-001"), lastAppliedObservationSequence = 4, timestamps = TransferTimestamps(Instant.parse("2026-08-12T00:00:00Z"), lastObservedAt = Instant.parse("2026-08-12T00:02:00Z")))
        assertIs<ReceiptApplyResult.Rejected>(TransferReceiptReducer.apply(current, observation("obs-001", 5, TransferReceiptState.FAILED, failure = "RETRYABLE_FAILURE")))
        assertIs<ReceiptApplyResult.Ignored>(TransferReceiptReducer.apply(current, observation("obs-002", 4, TransferReceiptState.FAILED, failure = "RETRYABLE_FAILURE")))
        assertIs<ReceiptApplyResult.Rejected>(TransferReceiptReducer.apply(current, observation("obs-002", 5, TransferReceiptState.FAILED, at = Instant.parse("2026-08-12T00:01:00Z"), failure = "RETRYABLE_FAILURE")))
    }
    @Test fun `restart only creates new immutable attempts from retryable state`() {
        val failed = receipt(TransferReceiptState.FAILED).copy(failureCode = "NETWORK_FAILURE", timestamps = TransferTimestamps(Instant.parse("2026-08-12T00:00:00Z"), failedAt = Instant.parse("2026-08-12T00:01:00Z"), lastObservedAt = Instant.parse("2026-08-12T00:01:00Z")))
        val restarted = TransferReceiptReducer.restart(failed, "attempt-002", Instant.parse("2026-08-12T00:02:00Z"), "RETRYABLE_FAILURE", TransferRoute(2, "route-fingerprint-v2"))
        assertEquals("attempt-001", restarted.priorAttempts.single().attemptId); assertEquals(TransferReceiptState.FAILED, failed.state)
        assertFails { TransferReceiptReducer.restart(receipt(TransferReceiptState.COMPLETED), "attempt-002", Instant.parse("2026-08-12T00:02:00Z"), "RETRYABLE_FAILURE", TransferRoute(2, "route-fingerprint-v2")) }
    }
    @Test fun `decode is tolerant redacted and rejects unsupported mixed children`() {
        val legacy = resource("legacy-manager-queue.json"); val decoded = ManagerTransferProjection.decode(legacy)
        assertEquals(TransferReceiptState.VERIFYING_FILES, decoded.views.first().state); assertEquals("LEGACY_FAILURE_DETAIL_REDACTED", decoded.views.last().failureCode)
        assertEquals(ReceiptDecodeRejectionCode.MALFORMED_JSON, ManagerTransferProjection.decode("{").rejections.single().code)
        assertEquals(ReceiptDecodeRejectionCode.MIXED_ENVELOPE, ManagerTransferProjection.decode("{\"schema\":\"choam.transfer-receipts.v1\",\"queue\":[],\"transferReceipts\":[]}").rejections.single().code)
        assertEquals(ReceiptDecodeRejectionCode.UNSUPPORTED_CHILD_SCHEMA, ManagerTransferProjection.decode("{\"schema\":\"choam.transfer-receipts.v1\",\"transferReceipts\":[{\"schema\":\"v9\"}]}").rejections.single().code)
    }
    @Test fun `decoded v1 requires explicit synthetic verifier`() { val payload = resource("receipt-v1.json"); assertFalse(ManagerTransferProjection.decode(payload).views.single().deliveryCompleted); assertTrue(ManagerTransferProjection.decode(payload, verifier).views.single().deliveryCompleted) }
    @Test fun `decoded completed receipts require full destination committed lineage`() {
        val payload = resource("receipt-v1.json").replace("\"destinationCommittedAt\":\"2026-08-12T00:01:00Z\",", "")
        assertEquals(ReceiptDecodeRejectionCode.MALFORMED_RECEIPT, ManagerTransferProjection.decode(payload, verifier).rejections.single().code)
    }
    @Test fun `completion rejects evidence bound to another attempt or route`() {
        val committed = receipt(TransferReceiptState.DESTINATION_COMMITTED).copy(destinationEvidence = evidence())
        val staleProof = proof.copy(attemptId = "attempt-002")
        assertIs<ReceiptApplyResult.Rejected>(TransferReceiptReducer.apply(committed, observation("obs-003", 1, TransferReceiptState.COMPLETED, evidence().copy(proof = staleProof)), verifier))
        val routeChanged = TransferReceiptReducer.apply(receipt(TransferReceiptState.QUEUE_ADMITTED).copy(destinationEvidence = evidence()), observation("obs-004", 1, TransferReceiptState.DEFERRED).copy(route = TransferRoute(2, "route-fingerprint-v2"))) as ReceiptApplyResult.Applied
        assertEquals(null, routeChanged.receipt.destinationEvidence)
    }
    @Test fun `receipts always declare a delivery expectation`() {
        assertFails { receipt().copy(expectedBytes = null, expectedFiles = null, declaredHashes = emptyList()) }
    }
    private fun resource(name: String) = javaClass.getResource("/vision/salient/choam/receipt/$name")!!.readText()
    private fun assertFails(block: () -> Unit) { var failed = false; try { block() } catch (_: IllegalArgumentException) { failed = true }; assertTrue(failed) }
}
